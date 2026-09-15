package ru.computershik.troubadour.net

import android.os.Looper
import okhttp3.CacheControl
import okhttp3.ConnectionSpec
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.TlsVersion
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Ответ сервера. Тело уже прочитано целиком либо оборвано по лимиту. */
class HttpResponse {

    var statusCode: Int = 0
    var body: ByteArray? = null
    var headers: Map<String, String> = emptyMap()

    /** Сетевая ошибка либо превышение лимита тела; null, если ответ пришёл. */
    var error: Throwable? = null

    /** Сколько байт обещает сервер; -1, если он этого не сказал. */
    var expectedLength: Long = -1

    /**
     * Когда пришли заголовки, в миллисекундах от запуска; 0 до того.
     *
     * Делит время запроса надвое: до заголовков это соединение, рукопожатие
     * TLS и раздумья сервера, после — само тело. На неспешном устройстве
     * половины эти несопоставимы, и без такого деления «превью идут долго»
     * ничего не объясняет.
     */
    var headersAt: Long = 0

    val isSuccessful: Boolean
        get() = error == null && statusCode in 200..299

    /** Тело строкой в UTF-8. */
    val text: String
        get() {
            val data = body ?: return ""

            if (data.isEmpty()) {
                return ""
            }

            /**
             * Разбираем той кодировкой, которую назвал сервер.
             *
             * Почти всё, куда мы ходим, отвечает в UTF-8 и так и пишет.
             * Но служба подсказок — старая, из времён до InnerTube, —
             * отдаёт в кодировке, заказанной параметром `oe`, и по
             * умолчанию это не UTF-8. Разбор чужой кодировки как UTF-8
             * не падает, а молча подменяет каждый непонятный байт
             * знаком замены: на экране выходил ряд ромбиков с вопросом
             * вместо подсказок.
             *
             * В оригинале та же беда оборачивалась иначе:
             * `initWithData:encoding:` на негодных байтах возвращает
             * `nil`, и подсказки просто не появлялись — тише, но так же
             * неверно.
             */
            return String(data, charset())
        }

    /** Кодировка из `Content-Type`; UTF-8, если сервер её не назвал. */
    private fun charset(): java.nio.charset.Charset {
        val type = header("Content-Type") ?: return Charsets.UTF_8

        val at = type.indexOf("charset=", ignoreCase = true)

        if (at < 0) {
            return Charsets.UTF_8
        }

        val name = type.substring(at + 8)
            .substringBefore(';')
            .trim()
            .trim('"')

        return try {
            java.nio.charset.Charset.forName(name)
        } catch (error: Exception) {
            // Назвал невесть что — считаем, что UTF-8.
            Charsets.UTF_8
        }
    }

    /** Заголовок без оглядки на регистр — сервер пишет их как хочет. */
    fun header(name: String): String? {
        headers[name]?.let { return it }

        val lower = name.lowercase()

        for (entry in headers) {
            if (entry.key.lowercase() == lower) {
                return entry.value
            }
        }

        return null
    }
}

/**
 * Один HTTP-клиент на всё приложение: и запросы к InnerTube, и превью,
 * и сегменты плеера идут через него — значит, и через одну проверку
 * сертификатов, и через один пул соединений.
 *
 * Ходить в сеть системным `HttpURLConnection` нельзя ровно по той причине,
 * по какой в оригинале не годился системный стек `AVPlayer`: он берёт корни
 * из системы, а на 4.1 нужных корней там нет. То же и с `MediaPlayer` —
 * поэтому плеер здесь ExoPlayer, которому источник данных подсовываем свой.
 */
object Http {

    /** Код ошибки «ответ больше лимита» — своё исключение, чтобы отличать. */
    class BodyTooLargeException(val limit: Int) :
        IOException("Ответ больше $limit байт")

    private var instance: OkHttpClient? = null

    private var trustChecksCount = 0
    private var trustSecondsTotal = 0.0

    val client: OkHttpClient
        get() {
            instance?.let { return it }

            synchronized(this) {
                instance?.let { return it }

                val trust = Tls.trustManager()

                val built = OkHttpClient.Builder()
                    .sslSocketFactory(Tls.socketFactory(trust), trust)
                    .connectionSpecs(listOf(spec(), ConnectionSpec.CLEARTEXT))
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)

                    /**
                     * Перенаправления идут сами: у googlevideo узел может
                     * ответить «переезжай на другой» посреди потока, и это
                     * штатное поведение подачи, а не ошибка.
                     */
                    .followRedirects(true)
                    .followSslRedirects(true)

                    /**
                     * Дисковый кеш заведён ради превью: CDN отдаёт их
                     * с `max-age` на год, а весят они по сотне килобайт.
                     * Ответы InnerTube приходят с `no-store` и сюда
                     * не попадают — для них есть кратковременный кеш
                     * в памяти, ниже.
                     */
                    .cache(okhttp3.Cache(File(App.require().cacheDir, "http"), 32L * 1024 * 1024))
                    .build()

                instance = built

                return built
            }
        }

    /**
     * Набор версий протокола задан явно.
     *
     * На 4.1 нет ни GCM, ни ChaCha20 — там доступны только CBC-варианты,
     * и `MODERN_TLS` из OkHttp 3.12 их уже не содержит. Берём его список
     * и дополняем тем, что понимает старая система: современные шифры
     * стоят первыми, поэтому новые устройства ничего не теряют.
     */
    private fun spec(): ConnectionSpec {
        return ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1)
            .allEnabledCipherSuites()
            .build()
    }

    internal fun noteTrustCheck(seconds: Double) {
        synchronized(this) {
            trustChecksCount += 1
            trustSecondsTotal += seconds
        }
    }

    /**
     * Сколько раз проверялась цепочка сертификатов и сколько на это ушло.
     *
     * Проверка бывает по одной на соединение, поэтому счётчик заодно считает
     * и рукопожатия TLS: если на два десятка запросов к одному узлу их тоже
     * два десятка — соединения не переиспользуются, и каждая картинка платит
     * за новое знакомство. На неспешном железе это главная доля ожидания.
     */
    fun trustChecks(): Int = synchronized(this) { trustChecksCount }

    fun trustSeconds(): Double = synchronized(this) { trustSecondsTotal }

    // --- Сборка запроса ---------------------------------------------------

    /**
     * Процентное кодирование значения параметра.
     *
     * Кодируется **только подставляемое значение**, не адрес целиком:
     * в готовом адресе уже есть и разделители, и заранее закодированные
     * куски, и пропущенный через кодировщик знак процента превратился бы
     * в `%25`.
     *
     * `URLEncoder` кодирует пробел плюсом — это годится для тела формы,
     * но не для пути и не для параметра запроса, где плюс означает плюс.
     * Правим на месте, как это делается везде.
     */
    fun encodeParameter(value: String?): String {
        if (value.isNullOrEmpty()) {
            return ""
        }

        return try {
            URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~")
        } catch (error: Exception) {
            ""
        }
    }

    fun request(url: String): Request.Builder? {
        return try {
            Request.Builder().url(url)
        } catch (error: Exception) {
            /**
             * Молчаливый null здесь дорого обходится: запрос просто
             * не уходит, и в журнале не остаётся ни строки. Чаще всего
             * виноват пробел или кириллица в подставленном значении.
             */
            Log.d { "[YouTube/HTTP] Адрес не разобран: $url" }
            null
        }
    }

    val JSON: MediaType = MediaType.parse("application/json; charset=utf-8")!!

    fun jsonBody(data: ByteArray): RequestBody = RequestBody.create(JSON, data)

    fun formBody(text: String): RequestBody =
        RequestBody.create(MediaType.parse("application/x-www-form-urlencoded"), text)

    // --- Отправка ---------------------------------------------------------

    /**
     * Выполняет запрос и ждёт ответа. Звать только с фоновой очереди:
     * на главном потоке это заморозит интерфейс на время запроса, а начиная
     * с Android 3 система за такое ещё и снимает приложение
     * (`NetworkOnMainThreadException`).
     *
     * @param bodyLimit потолок на тело; 0 означает «без ограничения».
     *   Ограничение не перестраховка: ответ «Главной» у InnerTube — это
     *   несколько мегабайт JSON, а страница `/watch` в разметке доходит
     *   до десяти.
     * @param caching класть ли ответ в дисковый кеш. По умолчанию кладём:
     *   кеш заведён ради превью. А вот сегменты видео туда попадать
     *   не должны — каждый весит мегабайты, они не повторяются, и запись
     *   их на диск во время воспроизведения это лишняя работа ровно тогда,
     *   когда её меньше всего можно себе позволить.
     */
    fun send(request: Request?, bodyLimit: Int = 0, caching: Boolean = true): HttpResponse {
        val result = HttpResponse()

        if (request == null) {
            result.error = IOException("Адрес не разобран")

            return result
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.d { "[YouTube/HTTP] Запрос с главного потока: ${request.url()}" }
        }

        val prepared = if (caching) {
            request
        } else {
            request.newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build()
        }

        val startedAt = System.currentTimeMillis()

        try {
            client.newCall(prepared).execute().use { response ->
                fill(result, response, startedAt)

                val stream = response.body()?.byteStream()

                result.body = if (stream == null) {
                    ByteArray(0)
                } else {
                    readAll(stream, bodyLimit)
                }
            }
        } catch (error: Throwable) {
            result.error = error
        }

        return result
    }

    private fun fill(result: HttpResponse, response: Response, startedAt: Long) {
        result.statusCode = response.code()
        result.headersAt = System.currentTimeMillis() - startedAt
        result.expectedLength = response.body()?.contentLength() ?: -1

        val map = HashMap<String, String>()
        val names = response.headers().names()

        for (name in names) {
            map[name] = response.header(name) ?: ""
        }

        result.headers = map
    }

    private fun readAll(stream: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(16 * 1024)
        val collected = java.io.ByteArrayOutputStream(64 * 1024)

        while (true) {
            val read = stream.read(buffer)

            if (read <= 0) {
                break
            }

            collected.write(buffer, 0, read)

            if (limit > 0 && collected.size() > limit) {
                throw BodyTooLargeException(limit)
            }
        }

        return collected.toByteArray()
    }

    /**
     * Потоковое чтение: заголовки отдаются, как только пришли, а тело —
     * кусками по мере получения, без накопления целиком.
     *
     * В оригинале это нужно было ровно одному месту — прокси плеера, — и
     * причина была та же, что здесь: пока сегмент читался в память целиком
     * и лишь потом отдавался, плеер получал первый байт только после того,
     * как скачался последний.
     *
     * Прокси у нас нет, но потребитель нашёлся другой и не менее важный:
     * подача SABR. Ответ там — поток кусков UMP, из которых собираются
     * фрагменты, и ждать его конца нельзя вовсе: конца может не быть
     * минутами.
     *
     * [onChunk] возвращает false, если получатель отвалился, — тогда
     * загрузка обрывается и мы не тянем остаток впустую. Оба замыкания
     * зовутся с того потока, который позвал `stream`.
     */
    fun stream(
        request: Request?,
        onHeaders: ((HttpResponse) -> Unit)? = null,
        onChunk: (ByteArray, Int) -> Boolean
    ): HttpResponse {
        val result = HttpResponse()

        if (request == null) {
            result.error = IOException("Адрес не разобран")

            return result
        }

        val startedAt = System.currentTimeMillis()

        val prepared = request.newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build()

        try {
            client.newCall(prepared).execute().use { response ->
                fill(result, response, startedAt)

                onHeaders?.invoke(result)

                val stream = response.body()?.byteStream() ?: return result
                val buffer = ByteArray(64 * 1024)

                while (true) {
                    val read = stream.read(buffer)

                    if (read <= 0) {
                        break
                    }

                    if (!onChunk(buffer, read)) {
                        break
                    }
                }
            }
        } catch (error: Throwable) {
            result.error = error
        }

        return result
    }

    // --- Кратковременный кеш в памяти -------------------------------------

    private class CacheEntry(val response: HttpResponse, val storedAt: Long)

    private val memoryCache = LinkedHashMap<String, CacheEntry>()
    private val locks = LinkedHashMap<String, Any>()

    /**
     * Ответ на несколько секунд запоминается в памяти по адресу и телу.
     *
     * Витрину и ленты недолго держать полезно — на медленной сети это
     * разница между «экран сразу» и «пустой экран на три секунды».
     * Состояние (оценка, подписка) через это не ходит никогда.
     *
     * **Ключ — адрес и тело.** Одним адресом обойтись нельзя, и это
     * выяснилось на устройстве. У InnerTube все обращения идут на горстку
     * одинаковых адресов: лента «Главной», подписки, история и канал — это
     * всё `youtubei/v1/browse`, а различаются они только телом запроса.
     * С ключом по одному адресу ответ подписок (клиент TVHTML5, мегабайт)
     * оседал в кеше и возвращался в ответ на запрос ленты (клиент WEB):
     * запрос «выполнялся» за четыре миллисекунды, разбор проходил, роликов
     * в чужом ответе не находилось — и «Главная» оставалась пустой
     * до истечения срока.
     */
    fun sendCached(request: Request?, bodyLimit: Int, ttlSeconds: Double): HttpResponse {
        if (request == null || ttlSeconds <= 0) {
            return send(request, bodyLimit)
        }

        val key = cacheKey(request)
        val ttlMillis = (ttlSeconds * 1000).toLong()

        synchronized(memoryCache) {
            val entry = memoryCache[key]

            if (entry != null && System.currentTimeMillis() - entry.storedAt < ttlMillis) {
                return entry.response
            }
        }

        /**
         * Одинаковые запросы, ушедшие одновременно, ждут первого.
         *
         * Кеш проверяется до отправки и заполняется после ответа — между
         * этими двумя мгновениями он пуст, и всё, что успело спросить
         * то же самое, уходило в сеть отдельно. При запуске это было видно
         * прямо в журнале: два одинаковых запроса ленты и четыре
         * `accounts_list` подряд — разделы просыпаются разом, и каждый
         * спрашивает своё.
         */
        synchronized(lockFor(key)) {
            synchronized(memoryCache) {
                val entry = memoryCache[key]

                // Время берётся заново: пока ждали замок, могло пройти
                // сколько угодно, и ответ, годный минуту назад, мог успеть
                // устареть.
                if (entry != null && System.currentTimeMillis() - entry.storedAt < ttlMillis) {
                    return entry.response
                }
            }

            val response = send(request, bodyLimit)

            if (response.isSuccessful) {
                synchronized(memoryCache) {
                    // Держать всю историю переходов незачем: кеш нужен
                    // на секунды, чтобы возврат назад не перезапрашивал
                    // ту же ленту.
                    if (memoryCache.size > 32) {
                        memoryCache.clear()
                    }

                    memoryCache[key] = CacheEntry(response, System.currentTimeMillis())
                }
            }

            return response
        }
    }

    private fun cacheKey(request: Request): String {
        /**
         * Тело целиком в ключ не кладём: у продолжений там токен
         * в килобайт. Хватает его длины и хеша — совпадение обоих
         * у разных тел настолько маловероятно, что ради него не стоит
         * держать лишнюю память.
         */
        val body = request.body()

        val length = try {
            body?.contentLength() ?: 0
        } catch (error: Exception) {
            0L
        }

        val digest = try {
            if (body == null) {
                0
            } else {
                val sink = okio.Buffer()

                body.writeTo(sink)
                sink.readByteArray().contentHashCode()
            }
        } catch (error: Exception) {
            0
        }

        return "${request.url()}|$length|$digest"
    }

    private fun lockFor(key: String): Any {
        synchronized(locks) {
            locks[key]?.let { return it }

            // Столько же, сколько записей в кеше: замки нужны ровно тем
            // ключам, что в нём живут.
            if (locks.size > 64) {
                locks.clear()
            }

            val lock = Any()

            locks[key] = lock

            return lock
        }
    }

    /** Сбрасывает кратковременный кеш ответов — например, при входе в аккаунт. */
    fun dropMemoryCache() {
        synchronized(memoryCache) {
            memoryCache.clear()
        }
    }
}
