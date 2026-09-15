package ru.computershik.troubadour.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.ui.async
import ru.computershik.troubadour.ui.main
import ru.computershik.troubadour.ui.mainAfter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Расшифровка параметра `n` в адресах видео.
 *
 * В каждой ссылке на поток и в адресе подачи SABR есть параметр `n`,
 * и сервер ждёт его **преобразованным**. Нерасшифрованный он означает
 * отказ 403 — пустой, без единого слова объяснения.
 *
 * Преобразование задано не форматом, а кодом: в скрипте плеера лежит
 * функция, которая перемешивает строку по своему списку действий.
 * Ни повторить её на другом языке, ни угадать нельзя — её меняют.
 * Поэтому она **исполняется как есть**, в невидимом веб-виде.
 *
 * Найти её в файле — отдельная работа, и раньше она делалась поиском
 * по метке `.set("n"`. С сентября 2026 метки нет: все строковые литералы
 * вокруг расшифровки вынесены в таблицу, а номера строк считаются
 * на ходу. Искать по тексту больше нечего, и мы **не ищем**: в веб-вид
 * загружается скрипт плеера целиком, а нужное находится по поведению —
 * через собственный класс адреса плеера, который сам применяет
 * преобразование к параметру `n`. Как именно это делается, описано
 * в `assets/web/nsig.js`; здесь — только доставка скрипта и связь.
 *
 * Скрипт плеера — два с половиной мегабайта, и меняется он раз в несколько
 * недель, поэтому лежит на диске под номером своей сборки.
 */
object NSig {

    private const val STORE = "troubadour"

    /** Имена, под которыми прежняя версия хранила вырезанную функцию. */
    private const val OLD_CODE_KEY = "YTNSigCode"
    private const val OLD_PLAYER_KEY = "YTNSigPlayer"

    private const val SOLVER_ASSET = "web/nsig.js"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    /** Обратный канал: тем же способом, что у BotGuard, — переходом. */
    private const val RESULT_SCHEME = "nsig-result://"
    private const val BOOT_SCHEME = "nsig-boot://"

    /** Известная строка и её эталон для проверки, что канал исправен. */
    private const val SAMPLE = "DhpWuaCJRFiGbHK"

    /** Сколько ждать готовности, если подготовка ещё идёт. */
    private const val READY_WAIT_MS = 12_000L

    /** Сколько ждать отчёта решателя, прежде чем счесть подготовку неудавшейся. */
    private const val BOOT_TIMEOUT_MS = 60_000L

    private var web: WebView? = null

    private var started = false

    /** Сборка плеера, под которую идёт или закончилась подготовка. */
    private var preparedFor: String? = null

    @Volatile
    private var ready = false

    /** Подготовка закончилась — удачей или нет; по нему ждут в `transform`. */
    @Volatile
    private var settled = CountDownLatch(1)

    private var latch: CountDownLatch? = null
    private var result: String? = null

    private val store
        get() = App.require().getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun isReady(): Boolean = ready

    // --- Подготовка -------------------------------------------------------

    /**
     * Запускает подготовку, если она ещё не шла. Возвращается сразу —
     * скачивание и разбор идут своим чередом.
     */
    fun prepare() {
        prepare(force = false)
    }

    private fun prepare(force: Boolean) {
        synchronized(this) {
            if (started && !force) {
                return
            }

            started = true
            ready = false
            settled = CountDownLatch(1)
        }

        async {
            val player = PlayerJs.playerId()

            if (player.isNullOrEmpty()) {
                finish(null, "[YouTube/Ключ] Сборка плеера неизвестна — расшифровка `n` не добыта")

                return@async
            }

            synchronized(this) {
                preparedFor = player
            }

            val script = obtainScript(player)

            if (script == null || script.isEmpty()) {
                finish(player, "[YouTube/Ключ] Расшифровка `n` не добыта")

                return@async
            }

            val solver = solverText()

            if (solver.isNullOrEmpty()) {
                finish(player, "[YouTube/Ключ] Решатель не прочитался из assets")

                return@async
            }

            main { install(solver, Base64.encodeToString(script, Base64.NO_WRAP)) }
        }
    }

    /**
     * Подготовка закончилась. `ready` к этому моменту уже выставлен,
     * если удалась; здесь только отпускаем ждущих и пишем строку.
     */
    private fun finish(player: String?, line: String?) {
        if (line != null) {
            Log.d { line }
        }

        synchronized(this) {
            if (player == null) {
                // Сборку не узнали — следующий вызов попробует снова.
                started = false
            }

            settled.countDown()
        }
    }

    /** Текст решателя из assets. */
    private fun solverText(): String? = try {
        App.require().assets.open(SOLVER_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (error: Exception) {
        null
    }

    private fun scriptFile(player: String): File =
        File(App.require().filesDir, "player-$player.js")

    /**
     * Скрипт плеера — с диска либо из сети.
     *
     * Берётся сборка TV-плеера: именно на неё ссылается страница `/tv`,
     * откуда мы узнаём номер, и она на полмегабайта легче обычной.
     * Если её не отдали — обычная, из которой берётся `signatureTimestamp`.
     * Расшифровка `n` в них одна и та же.
     */
    private fun obtainScript(player: String): ByteArray? {
        val file = scriptFile(player)

        if (file.isFile && file.length() > 0) {
            val stored = try {
                file.readBytes()
            } catch (error: Exception) {
                null
            }

            if (stored != null && stored.isNotEmpty()) {
                Log.d {
                    "[YouTube/Ключ] Скрипт плеера $player прочитан с диска (${stored.size / 1024} КБ)"
                }

                return stored
            }
        }

        val addresses = listOf(
            "https://www.youtube.com/s/player/$player/tv-player-ias.vflset/tv-player-ias.js",
            "https://www.youtube.com/s/player/$player/player_ias.vflset/en_US/base.js"
        )

        for (address in addresses) {
            val builder = Http.request(address) ?: continue

            builder.header("User-Agent", USER_AGENT)

            val response = Http.send(builder.build(), 0, caching = false)
            val body = response.body

            if (!response.isSuccessful || body == null || body.isEmpty()) {
                Log.d { "[YouTube/Ключ] Скрипт плеера не прочитался: код ${response.statusCode}" }

                continue
            }

            Log.d { "[YouTube/Ключ] Скрипт плеера $player скачан (${body.size / 1024} КБ)" }

            store(player, body)

            return body
        }

        return null
    }

    /** Кладёт скрипт на диск и убирает скрипты прежних сборок. */
    private fun store(player: String, body: ByteArray) {
        try {
            val directory = App.require().filesDir

            directory.listFiles()?.forEach { old ->
                if (old.name.startsWith("player-") && old.name.endsWith(".js")) {
                    old.delete()
                }
            }

            scriptFile(player).writeBytes(body)
        } catch (error: Exception) {
            Log.d { "[YouTube/Ключ] Скрипт плеера не сохранился: ${error.message}" }
        }

        // Остатки прежнего способа — вырезанная функция в настройках.
        store.edit().remove(OLD_CODE_KEY).remove(OLD_PLAYER_KEY).apply()
    }

    /**
     * Заводит веб-вид и загружает в него страницу с решателем и скриптом
     * плеера.
     *
     * Скрипт передаётся в base64 внутри строкового литерала: так в разметку
     * не попадает ни `</script>`, ни иной знак, способный сбить разбор
     * страницы, а прочитать строку в три мегабайта веб-виду ничего
     * не стоит. Сам решатель запускается не отсюда, а по готовности
     * страницы — на ещё не поднятой странице исполнять нечего.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun install(solver: String, scriptBase64: String) {
        loaded = false

        var view = web

        if (view == null) {
            view = WebView(App.require())

            view.settings.javaScriptEnabled = true
            view.settings.loadsImagesAutomatically = false

            view.webViewClient = Client()
            view.webChromeClient = Chrome()

            web = view
        }

        val html = StringBuilder(solver.length + scriptBase64.length + 256)

        html.append("<html><head><meta charset=\"utf-8\"><script>")
            .append(solver)
            .append("</script></head><body><script>window.__p=\"")
            .append(scriptBase64)
            .append("\";</script></body></html>")

        view.loadDataWithBaseURL(
            "https://www.youtube.com",
            html.toString(),
            "text/html", "UTF-8", null
        )

        /**
         * Сторож: если страница так и не отчиталась — ни удачей, ни
         * отказом, — ждущие отпускаются, чтобы каждое воспроизведение
         * не простаивало по [READY_WAIT_MS].
         */
        val gate = synchronized(this) { settled }

        mainAfter(BOOT_TIMEOUT_MS) {
            if (gate.count > 0) {
                finish(synchronized(this) { preparedFor }, "[YouTube/Ключ] Решатель не отчитался за ${BOOT_TIMEOUT_MS / 1000} с")
            }
        }
    }

    @Volatile
    private var loaded = false

    /**
     * Страница поднялась — запускаем решатель. Отчёт приходит переходом
     * на `nsig-boot://…`: `evaluateJavascript` с возвратом значения
     * появился в API 19, а нижняя граница у нас 16.
     */
    private fun boot() {
        run(
            "(function(){var r;try{r=YTSolver.bootBase64(window.__p);}" +
                "catch(e){r='решатель упал: '+e;}window.__p=null;" +
                "document.location='$BOOT_SCHEME'+encodeURIComponent(r);})()"
        )
    }

    /** Разбор отчёта решателя. */
    private fun booted(report: String) {
        val player = synchronized(this) { preparedFor }

        if (!report.startsWith("ok ")) {
            finish(player, "[YouTube/Ключ] Решатель не завёлся (плеер $player): $report")

            return
        }

        /**
         * Самопроверка на известной строке — тем же обратным каналом,
         * которым пойдут настоящие значения. Тем же плеером она даёт
         * один и тот же ответ где угодно, и строку можно сверить
         * с эталоном. Без этого ошибка в канале выглядит как исправная
         * работа: решатель что-то отдал, а до адреса оно не дошло.
         */
        async {
            val sample = transformInternal(SAMPLE, ignoreReady = true)

            if (sample.isNullOrEmpty() || sample == SAMPLE) {
                finish(player, "[YouTube/Ключ] Расшифровка не отвечает по каналу (${report.substring(3)})")

                return@async
            }

            ready = true

            finish(
                player,
                "[YouTube/Ключ] Готово: расшифровка `n` заведена, плеер $player, " +
                    "${report.substring(3)} (проверка: $SAMPLE → $sample)"
            )
        }
    }

    private class Client : WebViewClient() {

        @Suppress("OverridingDeprecatedMember", "DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            if (url == null) {
                return false
            }

            val scheme = when {
                url.startsWith(RESULT_SCHEME) -> RESULT_SCHEME
                url.startsWith(BOOT_SCHEME) -> BOOT_SCHEME
                else -> return false
            }

            val value = try {
                java.net.URLDecoder.decode(url.substring(scheme.length), "UTF-8")
            } catch (error: Exception) {
                ""
            }

            if (scheme == BOOT_SCHEME) {
                booted(value)

                return true
            }

            synchronized(NSig) {
                result = value

                latch?.countDown()
            }

            return true
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (loaded) {
                return
            }

            loaded = true

            Log.d { "[YouTube/Ключ] Страница решателя поднялась" }

            boot()
        }

        @Suppress("OverridingDeprecatedMember", "DEPRECATION")
        override fun onReceivedError(
            view: WebView?, errorCode: Int, description: String?, failingUrl: String?
        ) {
            Log.d { "[YouTube/Ключ] Страница решателя: ошибка $errorCode $description" }
        }
    }

    /** Строки `console.log` из страницы — в наш журнал, для разбора на устройстве. */
    private class Chrome : WebChromeClient() {

        override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
            if (message != null) {
                Log.d { "[YouTube/Ключ] Страница: ${message.message()} (${message.lineNumber()})" }
            }

            return true
        }
    }

    // --- Работа -----------------------------------------------------------

    /**
     * Расшифрованное значение `n`; null, если подготовка не удалась.
     *
     * Если подготовка ещё идёт — ждёт её, но не дольше [READY_WAIT_MS]:
     * первый ролик после запуска приходит раньше, чем веб-вид прочтёт
     * скрипт плеера, а отказ 403 обходится дороже нескольких секунд.
     * Если сборка плеера сменилась с тех пор, как готовились, —
     * готовимся заново.
     */
    fun transform(n: String?): String? {
        if (n.isNullOrEmpty()) {
            return null
        }

        val player = PlayerJs.playerId()
        val stale = synchronized(this) {
            started && !player.isNullOrEmpty() && player != preparedFor
        }

        if (stale) {
            Log.d { "[YouTube/Ключ] Сборка плеера сменилась на $player — расшифровка заново" }

            prepare(force = true)
        } else {
            prepare(force = false)
        }

        val gate = synchronized(this) { settled }

        try {
            gate.await(READY_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            return null
        }

        return transformInternal(n, ignoreReady = false)
    }

    private fun transformInternal(n: String?, ignoreReady: Boolean): String? {
        if ((!ignoreReady && !isReady()) || n.isNullOrEmpty()) {
            return null
        }

        val wait = CountDownLatch(1)

        synchronized(this) {
            latch = wait
            result = null
        }

        main {
            run(
                "(function(){try{document.location='$RESULT_SCHEME'+" +
                    "encodeURIComponent(window.YTn(${JSONObject.quote(n)})||'');}" +
                    "catch(e){document.location='$RESULT_SCHEME';}})()"
            )
        }

        val delivered = try {
            wait.await(4, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            false
        }

        val value = synchronized(this) {
            latch = null

            result
        }

        if (!delivered || value.isNullOrEmpty()) {
            return null
        }

        return value
    }

    /**
     * Тот же адрес с расшифрованным `n`.
     *
     * Если параметра в адресе нет или расшифровка не готова — адрес
     * возвращается как был: лучше попробовать и получить отказ, чем
     * не пробовать вовсе.
     */
    fun fixUrl(url: String): String {
        val marker = url.indexOf("&n=")

        if (marker < 0) {
            return url
        }

        val tail = url.substring(marker + 3)
        val stop = tail.indexOf('&')

        val value = if (stop < 0) tail else tail.substring(0, stop)

        val fixed = transform(value)

        if (fixed.isNullOrEmpty() || fixed == value) {
            return url
        }

        Log.d { "[YouTube/Ключ] `n` расшифрован: $value → $fixed" }

        return url.replace("&n=$value", "&n=$fixed")
    }

    private fun run(script: String) {
        val view = web ?: return

        try {
            /**
             * Код исполняется, а не открывается как адрес.
             *
             * `javascript:` — это адрес, и `%` в нём читается как начало
             * шестнадцатеричной пары, а `#` обрывает строку. Движок
             * Android 4.1 при этом ничего обратно не раскодирует:
             * percent-кодирование даёт «Unexpected token %» на первом же
             * знаке. Тот же приём, что у PO-токена: код едет в адресе
             * основанием 64 в url-безопасном виде, а раскодирует его
             * сама страница.
             */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                view.evaluateJavascript(script, null)
            } else {
                val payload = Base64.encodeToString(
                    script.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP
                )

                view.loadUrl(
                    "javascript:(function(p){try{eval(decodeURIComponent(escape(" +
                        "atob(p.replace(/-/g,\"+\").replace(/_/g,\"/\"))" +
                        ")))}catch(e){}})(\"" + payload + "\")"
                )
            }
        } catch (error: Throwable) {
            Log.d { "[YouTube/Ключ] Не исполнилось: ${error.message}" }
        }
    }
}
