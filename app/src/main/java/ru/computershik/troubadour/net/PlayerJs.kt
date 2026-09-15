package ru.computershik.troubadour.net

import android.content.Context
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log

/**
 * Одно-единственное число из плеерного скрипта — `signatureTimestamp`.
 *
 * Порт `GetSignatureTimestampAsync` и `ScanPlayerJsForStsAsync`.
 *
 * TV-клиент, обращаясь к `/player`, обязан назвать версию плеера, под
 * которую он просит подписи. Число это лежит в `base.js` — файле на три
 * мегабайта, — и сервер сверяет его со своим: устаревшее он встречает
 * ответом «the page needs to be reloaded», а не отказом, так что по коду
 * ответа беду не видно.
 *
 * Файл просматривается по байтам, без превращения в строку: имя, которое
 * ищем, целиком из латиницы, а строка на три мегабайта стоила бы вдвое
 * дороже самих данных. Найденное живёт в настройках: меняется оно раз
 * в несколько недель, так что чтение это разовое.
 */
object PlayerJs {

    /** Имена, под которыми запомнены число и сборка, из которой оно взято. */
    private const val STORE = "troubadour"
    private const val STS_KEY = "YTSignatureTimestamp"
    private const val STS_PLAYER_KEY = "YTSignatureTimestampPlayer"

    private const val USER_AGENT =
        "Mozilla/5.0 (SMART-TV; LINUX; Tizen 5.0) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/5.0 TV Safari/537.36"

    private var cachedSts = 0
    private var cachedStsPlayer: String? = null
    private var cachedPlayerId: String? = null

    private val store
        get() = App.require().getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /**
     * Тело запроса к сети под именем TV-клиента.
     *
     * Без кук. Страница `/tv` и скрипт плеера — общие для всех, аккаунт
     * тут ни при чём, а лишние заголовки на статике только повод
     * для отказа. Здесь это выходит само собой: куки прикладываются
     * только там, где мы их приложили руками.
     */
    internal fun fetch(url: String, range: String?): ByteArray? {
        val builder = Http.request(url) ?: return null

        builder.header("User-Agent", USER_AGENT)

        if (!range.isNullOrEmpty()) {
            builder.header("Range", range)
        }

        val response = Http.send(builder.build(), 0, caching = false)

        return if (response.isSuccessful) response.body else null
    }

    /**
     * Номер сборки плеера со страницы `/tv`.
     *
     * Со страницы приходит адрес **TV-плеера** (`tv-player-ias.js`),
     * а нужны нам другие сборки того же номера: `player_ias` ради
     * `signatureTimestamp` и `player_ias_tce` ради расшифровки `n`.
     * Поэтому из адреса берётся только номер, а имя файла подставляется
     * своё.
     */
    private fun scanPlayerId(): String? {
        val page = fetch("https://www.youtube.com/tv", "bytes=0-262143") ?: return null

        /**
         * Latin-1, а не UTF-8, и это важно.
         *
         * Кусок, отрезанный по числу байт, почти наверняка рассекает
         * многобайтовый знак. UTF-8 на таком подставляет замену, а в иных
         * реализациях и отказывает вовсе — весь разбор молча превращается
         * в «не нашли». Latin-1 не отвергает ни одного байта, а ищем мы
         * только латиницу.
         */
        val text = String(page, Charsets.ISO_8859_1)

        if (text.isEmpty()) {
            return null
        }

        /**
         * Ищем `/s/player/<версия>/<что-то>.vflset/<что-то>.js`. Разбирать
         * страницу целиком незачем: адрес встречается в ней первым же
         * упоминанием плеера.
         */
        val marker = text.indexOf("/s/player/")

        if (marker < 0) {
            return null
        }

        val tail = text.substring(marker)
        val stop = tail.indexOf(".js")

        if (stop < 0 || stop > 200) {
            return null
        }

        // В разметке адрес приходит с экранированными косыми.
        val path = tail.substring(0, stop + 3).replace("\\/", "/")

        val parts = path.split("/")

        // «/s/player/<номер>/…» — номер третий по счёту после пустого начала.
        return if (parts.size > 3) parts[3] else null
    }

    /**
     * Номер сборки плеера — часть адреса вида `/s/player/<номер>/…`.
     *
     * Нужен не только здесь: тем же номером берётся сборка `_tce`,
     * из которой достаётся расшифровка `n` (см. [NSig]).
     */
    fun playerId(): String? {
        synchronized(this) {
            cachedPlayerId?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        val found = scanPlayerId()

        if (found.isNullOrEmpty()) {
            return null
        }

        synchronized(this) {
            cachedPlayerId = found
        }

        return found
    }

    /**
     * Забывает, какая сборка плеера в ходу.
     *
     * Номер сборки читается раз за запуск и дальше держится в памяти —
     * ходить за ним к каждому ролику незачем. Но YouTube выкатывает новую
     * сборку когда захочет, случалось и трижды за день, и если это застало
     * приложение открытым, в руках остаются метка подписи и расшифровка `n`
     * от старой сборки. Раздача на такие адреса отвечает отказом.
     *
     * Вызывается, когда воспроизведение не задалось: следующая попытка
     * перечитает номер, а `signatureTimestamp` и расшифровка `n`
     * подтянутся за ним сами — они помнят, для какой сборки посчитаны.
     */
    fun forgetPlayerId() {
        synchronized(this) {
            val known = cachedPlayerId

            if (known.isNullOrEmpty()) {
                return
            }

            Log.d { "[YouTube/Ключ] Забыли сборку плеера $known — перечитаем" }

            cachedPlayerId = null
        }
    }

    /** Адрес обычной сборки плеера — в ней лежит `signatureTimestamp`. */
    private fun playerScriptUrl(): String? {
        val identifier = playerId()

        if (identifier.isNullOrEmpty()) {
            return null
        }

        return "https://www.youtube.com/s/player/$identifier/player_ias.vflset/en_US/base.js"
    }

    /**
     * Ищет число прямо в байтах, без превращения файла в строку.
     *
     * `base.js` — почти три мегабайта; строка из него на слабом устройстве
     * стоила бы вдвое дороже самих данных и всё это ради пяти цифр.
     * Искомое имя целиком из латиницы, так что сравнение идёт побайтно.
     *
     * 0, если имени в файле нет.
     */
    private fun stsIn(data: ByteArray): Int {
        val needle = "signatureTimestamp".toByteArray(Charsets.US_ASCII)

        if (data.size <= needle.size) {
            return 0
        }

        var index = 0

        outer@ while (index + needle.size < data.size) {
            for (offset in needle.indices) {
                if (data[index + offset] != needle[offset]) {
                    index++
                    continue@outer
                }
            }

            var position = index + needle.size

            // Между именем и числом стоит двоеточие либо знак равенства.
            while (position < data.size) {
                val symbol = data[position].toInt().toChar()

                if (symbol == ':' || symbol == '=' || symbol == ' ' ||
                    symbol == '"' || symbol == '\''
                ) {
                    position++
                } else {
                    break
                }
            }

            var value = 0
            var digits = 0

            while (position < data.size) {
                val symbol = data[position].toInt().toChar()

                if (symbol < '0' || symbol > '9') {
                    break
                }

                value = value * 10 + (symbol - '0')
                digits++
                position++
            }

            if (digits > 0) {
                return value
            }

            index++
        }

        return 0
    }

    /**
     * `signatureTimestamp` — либо 0, если добыть не удалось.
     *
     * Блокирующий: звать с фоновой очереди. Повторные вызовы отдают
     * запомненное и в сеть не ходят.
     */
    fun signatureTimestamp(): Int {
        /**
         * Число запоминается **вместе со сборкой плеера**, из которой взято.
         *
         * У каждой сборки оно своё, а меняет их YouTube по нескольку раз
         * в день: за один день видели `b1558f06` с числом 20675,
         * `627778fa` с 20677 и `b0d2d49a` с 20676. Пока проверки не было,
         * приложение продолжало просить подписи под версию, которой уже
         * нет, — ответ приходил обычный, а готовые ссылки раздача отбивала
         * отказом 403 без объяснения.
         *
         * Для расшифровки `n` такая проверка есть с самого начала; здесь
         * её просто забыли, и это стоило дня разбирательств.
         */
        val player = playerId()

        if (player.isNullOrEmpty()) {
            Log.d { "[YouTube/Плеер] Сборка плеера не определилась" }

            return 0
        }

        synchronized(this) {
            if (cachedSts > 0 && cachedStsPlayer == player) {
                return cachedSts
            }
        }

        if (store.getString(STS_PLAYER_KEY, null) == player) {
            val stored = store.getInt(STS_KEY, 0)

            if (stored > 0) {
                synchronized(this) {
                    cachedSts = stored
                    cachedStsPlayer = player
                }

                return stored
            }
        }

        val script = playerScriptUrl()

        if (script.isNullOrEmpty()) {
            Log.d { "[YouTube/Плеер] Адрес плеерного скрипта не найден" }

            return 0
        }

        /**
         * Файл берётся целиком, а не кусками.
         *
         * Кусками было изящнее — читать до первого попадания и бросить, —
         * но тело приходит сжатым, и границы диапазонов живут в байтах
         * сжатого потока, а не текста. Совпасть они не могут, и вся
         * бережливость оборачивалась чтением того же файла дважды.
         * Три мегабайта один раз в несколько недель дешевле.
         */
        val body = fetch(script, null)

        if (body == null || body.isEmpty()) {
            Log.d { "[YouTube/Плеер] Плеерный скрипт не прочитался" }

            return 0
        }

        val found = stsIn(body)

        if (found <= 0) {
            Log.d {
                "[YouTube/Плеер] signatureTimestamp не найден " +
                    "(прочитано ${body.size / 1024} КБ)"
            }

            return 0
        }

        Log.d {
            "[YouTube/Плеер] signatureTimestamp = $found (плеер $player, " +
                "скрипт ${body.size / 1024} КБ)"
        }

        synchronized(this) {
            cachedSts = found
            cachedStsPlayer = player
        }

        store.edit()
            .putInt(STS_KEY, found)
            .putString(STS_PLAYER_KEY, player)
            .apply()

        return found
    }
}
