package ru.computershik.troubadour.player

import org.json.JSONArray
import org.json.JSONObject
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.net.Api
import ru.computershik.troubadour.net.Http
import ru.computershik.troubadour.net.Json
import ru.computershik.troubadour.net.mediaUserAgent
import ru.computershik.troubadour.net.NSig
import ru.computershik.troubadour.net.PoToken
import ru.computershik.troubadour.net.refreshedPlayerResponse
import ru.computershik.troubadour.net.playerResponse
import ru.computershik.troubadour.net.streamBinding
import java.util.Locale

/**
 * Одна дорожка из `streamingData.adaptiveFormats`.
 *
 * Порт `PlayerFormatModel` из Video.xaml.cs — поля те же и в том же
 * смысле. Диапазоны `initRange`/`indexRange` приходят строками, ими
 * и остаются: они уходят прямо в заголовок `Range`.
 */
class Format {

    var url: String? = null
    var width: Int = 0
    var height: Int = 0
    var mimeType: String = ""
    var itag: Int = 0
    var fps: Int = 0
    var bitrate: Int = 0
    var averageBitrate: Int = 0

    /**
     * Длина дорожки в байтах, как её называет сервер; 0 — не сказал.
     *
     * Нужна скачиванию: без неё общий объём двух дорожек становится
     * известен только тогда, когда вторая уже пошла, и доля успевает
     * дойти до сотни, а потом откатиться назад.
     */
    var contentLength: Long = 0

    var initialRangeStart: String? = null
    var initialRangeEnd: String? = null
    var indexRangeStart: String? = null
    var indexRangeEnd: String? = null

    var hasAudio: Boolean = false
    var hasVideo: Boolean = false

    /** Многоязычный звук; у обычных роликов пусто. */
    /** Пометки дорожки: язык, происхождение звука, — как их пишет сервер. */
    var xtags: String? = null

    var audioTrackId: String? = null
    var audioTrackName: String? = null
    var audioIsDefault: Boolean = false

    /** Родная дорожка — та, на которой ролик сняли, а не озвучка. */
    var audioIsOriginal: Boolean = false

    /** Родная, но с поджатой громкостью: `drc=1`. */
    var audioIsCompressed: Boolean = false

    /**
     * Ступень качества — «1080p».
     *
     * Порт `QualityTierOf`: у вертикального ролика (1080×1920) это
     * **меньшая** сторона, а не высота, иначе подпись читалась бы
     * как «1920p».
     */
    fun qualityTier(): Int {
        if (width > 0 && height > 0) {
            return Streams.canonicalTier(minOf(width, height))
        }

        return Streams.canonicalTier(if (height > 0) height else width)
    }

    /** Только H.264: остального старое железо не декодирует. */
    fun isH264(): Boolean = mimeType.lowercase(Locale.US).contains("avc1")
}

/** Озвучка: `ru.4`, как её называет YouTube, и пометка основной. */
class AudioTrack(val id: String, val title: String, val isDefault: Boolean)

/**
 * Разбор потоков и выбор пары дорожек.
 *
 * В iOS-версии эта пара шла в свой демуксер, оттуда в ремуксер MPEG-TS,
 * оттуда в локальный HLS-прокси и только потом в `AVPlayer`. Здесь она
 * идёт прямо в ExoPlayer: он играет и раздельные дорожки, и фрагменты
 * из памяти. Половина сложности оригинала — `YTHlsProxy` на 2599 строк
 * и `YTTsMuxer` на 621 — исчезла вместе с прокси, ровно как и обещала
 * записка о переносе.
 */
object Streams {

    /**
     * Приводит размер к знакомой ступени.
     *
     * Сервер присылает то 1078, то 1082 вместо 1080 — кадр обрезан
     * по-своему. В меню качества такие числа выглядят опечаткой,
     * а сравнивать их с потолком приходится с оглядкой. Восьмая доля
     * допуска покрывает всё, что встречается, и не сливает соседние
     * ступени.
     */
    private val STEPS = intArrayOf(144, 240, 360, 480, 720, 1080, 1440, 2160, 4320)

    fun canonicalTier(raw: Int): Int {
        if (raw <= 0) {
            return raw
        }

        for (step in STEPS) {
            val gap = if (raw > step) raw - step else step - raw

            if (gap * 8 <= step) {
                return step
            }
        }

        return raw
    }

    // --- Состояние подачи -------------------------------------------------

    private var lastSabr: Sabr? = null

    /** Потолок и озвучка, выбранные человеком для ближайшего захода. */
    private var sabrCap = Int.MAX_VALUE
    private var sabrTrack: String? = null

    /** Названа ли ступень человеком, или качеством распоряжается сервер. */
    private var sabrExact = false

    /** Идёт ли сейчас трансляция — у неё своя разметка времени. */
    private var sabrLive = false

    /** Что было на выбор в последнем ответе — для меню. */
    private var lastSabrHeights: List<Int> = emptyList()
    private var lastSabrTracks: List<AudioTrack> = emptyList()

    /** Ступень каждой видеодорожки по её номеру — чтобы узнать сыгранную. */
    private var sabrTiers = HashMap<Int, Int>()

    /** Когда подачу обновляли в последний раз — чтобы не долбить `/player`. */
    private var lastRenew = 0L

    /** Сколько раз обновление подряд не удалось — после третьего перестаём. */
    private var renewFailures = 0

    /**
     * Подача посреди просмотра потеряна и восстановлению не поддалась.
     *
     * Рассылается, когда сервер трижды подряд отказался обновить ответ
     * `/player`. Слушает плеер: у него есть чем доиграть — готовые адреса,
     * а место просмотра он сохраняет.
     */
    private fun giveUpOnSabr() {
        renewFailures++

        if (renewFailures < 3) {
            return
        }

        Log.d { "[YouTube/Подача] Обновиться не удалось трижды — подача потеряна" }

        Notify.post(SABR_LOST)
    }

    const val SABR_LOST = "sabr-lost"

    // --- Разбор ответа ----------------------------------------------------

    private fun rangeField(format: JSONObject, key: String, edge: String): String? =
        Json.text(Json.obj(format, key), edge)

    private fun audioTrackField(format: JSONObject, key: String): String? =
        Json.text(Json.obj(format, "audioTrack"), key)

    /**
     * Приписка для журнала: под какого клиента и под какой адрес в сети
     * подписана ссылка — то, что раздача сверяет и на чём молча отказывает.
     */
    fun signatureNote(url: String): String {
        val note = StringBuilder()

        for (name in listOf("c", "ip")) {
            val found = url.indexOf("&$name=")

            if (found < 0) {
                continue
            }

            var tail = url.substring(found + name.length + 2)
            val stop = tail.indexOf('&')

            if (stop >= 0) {
                tail = tail.substring(0, stop)
            }

            note.append(if (note.isEmpty()) " (" else ", ").append("$name=$tail")
        }

        if (note.isNotEmpty()) {
            note.append(")")
        }

        return note.toString()
    }

    private fun sabrFormatFrom(format: JSONObject): SabrFormat {
        val result = SabrFormat(
            Json.int(format, "itag"),
            Json.text(format, "lastModified")?.toLongOrNull() ?: 0
        )

        result.xtags = Json.text(format, "xtags")

        return result
    }

    private fun tierIn(format: JSONObject): Int {
        val width = Json.int(format, "width")
        val height = Json.int(format, "height")

        if (width > 0 && height > 0) {
            return canonicalTier(minOf(width, height))
        }

        return canonicalTier(if (height > 0) height else width)
    }

    /**
     * Пометки дорожки словами.
     *
     * `xtags` — это протобуф в base64: внутри пары вроде `acont=original`
     * и `lang=en`. Разбирать его по правилам незачем — нам нужны только
     * слова, а они лежат в нём открытым текстом.
     */
    private fun marksIn(format: JSONObject): String {
        val tags = Json.text(format, "xtags")

        if (tags.isNullOrEmpty()) {
            return ""
        }

        return try {
            val padded = tags.replace('-', '+').replace('_', '/') +
                "=".repeat((4 - tags.length % 4) % 4)

            String(
                android.util.Base64.decode(padded, android.util.Base64.DEFAULT),
                Charsets.ISO_8859_1
            )
        } catch (error: Throwable) {
            ""
        }
    }

    /**
     * Родная ли это дорожка — та, на которой ролик сняли.
     *
     * Различать их приходится самим. Сервер помечает «основной»
     * (`audioIsDefault`) не родную, а ту, что подходит **языку запроса**:
     * просим ответ по-русски — и основной названа русская озвучка.
     * Человек при этом просил включить ролик, а не перевести его,
     * и слышать чужой голос поверх родного не ждал.
     *
     * Настоящий признак лежит в пометках: `acont=original` у родной,
     * `acont=dubbed` у озвучек, `acont=descriptive` у дорожки
     * с описанием происходящего для незрячих. Названия дорожек
     * («Английский (оригинальная)») сервер переводит на язык запроса,
     * поэтому они годятся только запасным ходом.
     */
    fun isOriginalTrack(format: JSONObject): Boolean {
        val marks = marksIn(format)

        if (marks.contains("acont")) {
            return marks.contains("original")
        }

        val name = audioTrackField(format, "displayName") ?: return false

        return name.contains("ориг", true) || name.contains("original", true)
    }

    /**
     * Дорожка с поджатой громкостью (`drc=1`).
     *
     * Она тоже родная, но звук в ней прижат к середине — тише громкое,
     * громче тихое. Это выбор для шумной улицы, а не то, что человек
     * ждёт по умолчанию.
     */
    private fun isCompressed(format: JSONObject): Boolean =
        marksIn(format).contains("drc")

    /** Есть ли у ролика родная дорожка вообще. */
    fun hasOriginalTrack(formats: JSONArray?): Boolean {
        if (formats == null) {
            return false
        }

        for (index in 0 until formats.length()) {
            val format = formats.opt(index) as? JSONObject ?: continue

            if (Json.obj(format, "audioTrack") == null) {
                continue
            }

            if (isOriginalTrack(format)) {
                return true
            }
        }

        return false
    }

    /** Помечена ли дорожка основной — или у неё вовсе нет озвучек. */
    private fun isDefaultTrack(format: JSONObject): Boolean {
        val track = Json.obj(format, "audioTrack") ?: return true

        return Json.bool(track, "audioIsDefault")
    }

    /** Адрес подачи в ответе `/player`; null, если его там нет. */
    private fun sabrUrlIn(playerResponse: JSONObject?): String? =
        Json.text(Json.obj(playerResponse, "streamingData"), "serverAbrStreamingUrl")

    /** Настройки подачи оттуда же, как есть — в записи base64url. */
    private fun sabrConfigIn(playerResponse: JSONObject?): String? =
        Json.text(
            Json.obj(
                Json.obj(
                    Json.obj(playerResponse, "playerConfig"), "mediaCommonConfig"
                ),
                "mediaUstreamerRequestConfig"
            ),
            "videoPlaybackUstreamerConfig"
        )

    /**
     * Обновляет подачу посреди просмотра, если сервер попросил взять ответ
     * `/player` заново.
     *
     * Такое случается не при открытии, а через минуту-другую игры: сессия
     * подачи протухает, и сервер вместо медиа присылает просьбу обновиться.
     * Без этого он не даст больше ни байта — сколько ни спрашивай, ответы
     * будут пустыми, а плеер встанет на месте.
     */
    fun renewSabr(sabr: Sabr?): Boolean {
        if (sabr == null || !sabr.needsReload) {
            return false
        }

        // Трижды не вышло — больше не пробуем.
        if (renewFailures >= 3) {
            return false
        }

        synchronized(this) {
            if (!sabr.needsReload) {
                return true
            }

            val now = System.currentTimeMillis()

            if (lastRenew > 0 && now - lastRenew < 3000) {
                return false
            }

            lastRenew = now

            val token = sabr.reloadToken
            val videoId = sabr.videoId

            Log.d {
                "[YouTube/Подача] Обновляем подачу на ходу: " +
                    if (!token.isNullOrEmpty()) {
                        "токен ${token.length} знаков"
                    } else {
                        "токена сервер не дал, спросим по-обычному"
                    }
            }

            val fresh = Api.refreshedPlayerResponse(videoId, token)

            if (fresh == null) {
                Log.d { "[YouTube/Подача] Перезапрос на ходу не удался" }

                giveUpOnSabr()

                return false
            }

            var url = sabrUrlIn(fresh)
            var config = sabrConfigIn(fresh)

            if (url.isNullOrEmpty() || config.isNullOrEmpty()) {
                Log.d {
                    "[YouTube/Подача] Перезапрос по токену пуст — спросим " +
                        "ответ обычным путём"
                }

                val plain = Api.playerResponse(videoId)

                if (plain != null) {
                    url = sabrUrlIn(plain)
                    config = sabrConfigIn(plain)
                }
            }

            if (url.isNullOrEmpty() || config.isNullOrEmpty()) {
                Log.d {
                    val what = when {
                        url.isNullOrEmpty() && config.isNullOrEmpty() -> "адреса, ни настроек"
                        url.isNullOrEmpty() -> "адреса"
                        else -> "настроек"
                    }

                    "[YouTube/Подача] В свежем ответе нет ни $what — играть нечем"
                }

                giveUpOnSabr()

                return false
            }

            sabr.adopt(url, Sabr.dataFromBase64Url(config))

            renewFailures = 0

            Log.d {
                "[YouTube/Подача] Свежие адрес и настройки поставлены — " +
                    "продолжаем с того же места"
            }

            return true
        }
    }

    /**
     * Пробный заход к подаче.
     *
     * Делает запрос и, если сервер попросил обновить ответ `/player`,
     * повторяет — но не более двух раз: дальше это уже не «устарело»,
     * а отказ.
     */
    private fun probeSabr(playerResponse: JSONObject) {
        var response = playerResponse

        for (attempt in 0 until 2) {
            val token = trySabr(response) ?: return

            val videoId = Json.text(Json.obj(response, "videoDetails"), "videoId")

            val fresh = Api.refreshedPlayerResponse(videoId, token)

            if (fresh == null) {
                Log.d { "[YouTube/Подача] Перезапрос не удался" }

                return
            }

            Log.d { "[YouTube/Подача] Ответ /player взят заново по токену" }

            response = fresh
        }
    }

    /**
     * Один заход: собрать подачу и попросить первые куски.
     *
     * Возвращает null при удаче (подача легла в [lastSabr]) либо токен
     * перезапроса, если сервер попросил обновить ответ `/player`.
     */
    private fun trySabr(playerResponse: JSONObject): String? {
        val streaming = Json.obj(playerResponse, "streamingData")

        val url = sabrUrlIn(playerResponse)
        val config = sabrConfigIn(playerResponse)

        if (url.isNullOrEmpty() || config.isNullOrEmpty()) {
            Log.d {
                "[YouTube/Подача] Нечем начать: " +
                    (if (url.isNullOrEmpty()) "нет адреса" else "") +
                    (if (config.isNullOrEmpty()) " нет настроек" else "")
            }

            return null
        }

        val adaptive = Json.array(streaming, "adaptiveFormats")

        /**
         * Есть ли у ролика родная дорожка — узнаём наперёд.
         *
         * Отбор идёт по одной дорожке за раз, а решение «брать родную»
         * имеет смысл, только когда родная вообще названа. У ролика без
         * озвучек пометок нет вовсе, и отбор по ним оставил бы нас
         * без звука.
         */
        val nativeVoice = hasOriginalTrack(adaptive)

        var video: JSONObject? = null
        var lowest: JSONObject? = null
        var audio: JSONObject? = null

        if (adaptive != null) {
            for (index in 0 until adaptive.length()) {
                val format = adaptive.opt(index) as? JSONObject ?: continue
                val mime = Json.text(format, "mimeType") ?: continue

                if (mime.contains("avc1")) {
                    val tier = tierIn(format)

                    // Дорожка без размеров ни с чем не сравнивается.
                    if (tier <= 0) {
                        continue
                    }

                    if (Json.int(format, "fps") > 31 &&
                        (prefersThirtyFrames() || tier > sixtyCap())
                    ) {
                        continue
                    }

                    if (tier <= sabrCap && (video == null || tier > tierIn(video))) {
                        video = format
                    }

                    // Про запас: у ролика может не быть ничего ниже потолка.
                    if (lowest == null || tier < tierIn(lowest)) {
                        lowest = format
                    }
                } else if (mime.contains("mp4a")) {
                    if (audio == null ||
                        (isDefaultTrack(format) && !isDefaultTrack(audio))
                    ) {
                        audio = format
                    }
                }
            }
        }

        if (video == null) {
            video = lowest
        }

        if (video == null || audio == null) {
            Log.d { "[YouTube/Подача] Подходящих дорожек нет" }

            return null
        }

        val videoId = Json.text(Json.obj(playerResponse, "videoDetails"), "videoId") ?: ""

        Log.d {
            "[YouTube/Подача] Пробуем: видео itag ${Json.int(video, "itag")} " +
                "(${tierIn(video)}p, ${Json.int(video, "fps")} кадр/с), " +
                "звук itag ${Json.int(audio, "itag")}, потолок " +
                if (sabrCap == Int.MAX_VALUE) "нет" else "${sabrCap}p"
        }

        val sabr = Sabr(
            url,
            Sabr.dataFromBase64Url(config),
            videoId,
            PoToken.tokenFor(videoId)
        )

        sabr.wantedHeight = if (sabrExact) tierIn(video) else 0

        val allVideo = ArrayList<SabrFormat>()
        val everyVideo = ArrayList<SabrFormat>()
        val everyAudio = ArrayList<SabrFormat>()
        val allAudio = ArrayList<SabrFormat>()

        val tiers = HashSet<Int>()
        val tracks = ArrayList<AudioTrack>()
        val seenTracks = HashSet<String>()

        sabrTiers = HashMap()

        if (adaptive != null) {
            for (index in 0 until adaptive.length()) {
                val format = adaptive.opt(index) as? JSONObject ?: continue
                val mime = Json.text(format, "mimeType") ?: continue

                if (mime.contains("avc1")) {
                    val tier = tierIn(format)

                    // То же правило, что и при выборе: шестидесятикадровых
                    // на слабом железе не предлагаем и серверу.
                    if (Json.int(format, "fps") > 31 &&
                        (prefersThirtyFrames() || tier > sixtyCap())
                    ) {
                        continue
                    }

                    if (tier > 0) {
                        tiers.add(tier)

                        sabrTiers[Json.int(format, "itag")] = tier
                    }

                    if (tier <= sabrCap) {
                        allVideo.add(sabrFormatFrom(format))
                    }

                    // Про запас — если ниже потолка не окажется ничего.
                    everyVideo.add(sabrFormatFrom(format))
                } else if (mime.contains("mp4a")) {
                    val track = Json.obj(format, "audioTrack")
                    val identifier = Json.text(track, "id")

                    if (!identifier.isNullOrEmpty() && seenTracks.add(identifier)) {
                        tracks.add(
                            AudioTrack(
                                identifier,
                                Json.text(track, "displayName") ?: identifier,
                                /**
                                 * Отмечаем ту, что зазвучит сама, — иначе
                                 * в списке галочка у одной дорожки,
                                 * а слышно другую.
                                 */
                                if (nativeVoice) {
                                    isOriginalTrack(format)
                                } else {
                                    isDefaultTrack(format)
                                }
                            )
                        )
                    }

                    /**
                     * Без выбора человека играем родную дорожку.
                     *
                     * Родной нет — тогда уж ту, что сервер зовёт основной:
                     * у ролика без озвучек она единственная и есть.
                     */
                    val wanted = if (!sabrTrack.isNullOrEmpty()) {
                        identifier == sabrTrack
                    } else if (nativeVoice) {
                        isOriginalTrack(format) && !isCompressed(format)
                    } else {
                        isDefaultTrack(format)
                    }

                    Log.d {
                        "[YouTube/Потоки] Звук itag ${Json.int(format, "itag")}: " +
                            "${identifier ?: "—"} «${Json.text(track, "displayName") ?: "—"}»" +
                            (if (isDefaultTrack(format)) " основная" else "") +
                            (Json.text(format, "xtags")?.let { " [$it]" } ?: "") +
                            (if (wanted) " ← берём" else "")
                    }

                    if (wanted || identifier.isNullOrEmpty()) {
                        allAudio.add(sabrFormatFrom(format))
                    }

                    // Про запас — если отбор не оставит ни одной.
                    everyAudio.add(sabrFormatFrom(format))
                }
            }
        }

        lastSabrHeights = tiers.sorted()
        lastSabrTracks = if (tracks.size > 1) tracks else emptyList()

        if (allVideo.isEmpty()) {
            Log.d { "[YouTube/Потоки] Ниже ${sabrCap}p дорожек нет — берём ближайшее" }

            allVideo.addAll(everyVideo)
        }

        /**
         * Без звука не остаёмся ни при каком отборе.
         *
         * Родная дорожка могла найтись только в поджатом виде, а выбранная
         * человеком озвучка — исчезнуть из ответа вовсе. Молчащий ролик
         * хуже, чем не тот голос.
         */
        if (allAudio.isEmpty()) {
            Log.d { "[YouTube/Потоки] Отбор не оставил звука — берём что есть" }

            allAudio.addAll(everyAudio)
        }

        sabr.liveMode = sabrLive

        sabr.setAvailable(allVideo, allAudio)

        Log.d {
            "[YouTube/Подача] В предпочтениях: видео ${allVideo.size}, " +
                "звука ${allAudio.size}"
        }

        if (sabr.request(sabrFormatFrom(video), sabrFormatFrom(audio), 0)) {
            Log.d {
                "[YouTube/Подача] Получилось: заголовок видео " +
                    "${sabr.videoInit?.size ?: 0} байт, первый фрагмент " +
                    "${sabr.videoSegment(1)?.size ?: 0} байт, всего фрагментов " +
                    "${sabr.videoSegmentCount}, ${sabr.duration.toInt()} с"
            }

            lastSabr = sabr

            return null
        }

        return sabr.reloadToken
    }

    /**
     * Готовая подача для ответа `/player`, у которого нет обычных адресов.
     *
     * @param exact `false` — «Авто»: подаче сообщается размер экрана,
     *   а качеством распоряжается сервер, включая снижение при слабой сети.
     *   `true` — ступень названа человеком: её и просим, а сервер снижает
     *   только когда иначе не может.
     */
    fun sabrFor(
        playerResponse: JSONObject,
        maxHeight: Int,
        audioTrack: String?,
        exact: Boolean = false
    ): Sabr? {
        lastSabr = null
        sabrCap = if (maxHeight > 0) maxHeight else Int.MAX_VALUE
        sabrTrack = audioTrack
        sabrExact = exact

        renewFailures = 0
        lastRenew = 0

        /** Эфиру нужна своя точка отсчёта времени — скажем об этом подаче. */
        sabrLive = isLiveResponse(playerResponse)

        probeSabr(playerResponse)

        val ready = lastSabr

        /**
         * У эфира заголовков дорожек в ответе подачи нет.
         *
         * Обычный ролик сервер начинает с них: сперва заголовок дорожки
         * (`ftyp` и `moov`), потом куски. У идущей трансляции он сразу
         * шлёт куски с живого края — заголовка нет, а без него плееру
         * их нечем раскодировать: подача считалась неподнявшейся,
         * и эфир не играл вовсе.
         *
         * Заголовок у эфира берётся отдельно, нулевым куском адреса
         * дорожки (`&sq=0`), — этим и добираем.
         */
        if (ready != null && ready.videoInit == null && isLiveResponse(playerResponse)) {
            attachLiveInits(ready, playerResponse)
        }

        if (ready == null || ready.videoInit == null) {
            /**
             * Молчать здесь нельзя.
             *
             * Этим же путём поднимается подача для скачивания, и когда
             * она не поднималась, в журнале не оставалось ни строки:
             * закачка отказывала «нет дорожек», а почему — неизвестно.
             */
            Log.d {
                "[YouTube/Подача] Не поднялась: " +
                    (if (ready == null) "проба не дала подачи" else "нет заголовка видео")
            }

            return null
        }

        return ready
    }

    /** Идёт ли трансляция прямо сейчас. */
    fun isLiveResponse(playerResponse: JSONObject?): Boolean {
        val about = Json.obj(playerResponse, "videoDetails")

        return Json.bool(about, "isLive") || Json.bool(about, "isLiveNow")
    }

    /**
     * Добирает заголовки дорожек эфира.
     *
     * У эфира куски адресуются номером: `&sq=1`, `&sq=2` и так далее,
     * а нулевой — это и есть заголовок дорожки. Диапазонов `initRange`
     * в ответе нет, поэтому иначе его не взять.
     *
     * Берём заголовки тех дорожек, куски которых сервер уже прислал:
     * что он выбрал, то и играем, — спрашивать заголовок другой дорожки
     * бессмысленно, куски к нему не подойдут.
     */
    private fun attachLiveInits(sabr: Sabr, playerResponse: JSONObject): Boolean {
        val formats = formatsFrom(playerResponse)

        val video = sabr.deliveredVideoItag
        val audio = sabr.deliveredAudioItag

        if (video <= 0) {
            Log.d { "[YouTube/Подача] Эфир: сервер не прислал ни куска — нечего доснастить" }

            return false
        }

        val videoUrl = formats.firstOrNull { it.itag == video }?.url
        val audioUrl = formats.firstOrNull { it.itag == audio }?.url

        val head = fetchLiveInit(videoUrl, video) ?: return false

        val voice = fetchLiveInit(audioUrl, audio)

        sabr.adoptInit(head, video, voice, audio)

        Log.d {
            "[YouTube/Подача] Эфир: заголовки добраны — видео ${head.size} б" +
                (voice?.let { ", звук ${it.size} б" } ?: ", звука нет")
        }

        return true
    }

    private fun fetchLiveInit(url: String?, itag: Int): ByteArray? {
        if (url.isNullOrEmpty()) {
            return null
        }

        val builder = Http.request("$url&sq=0") ?: return null

        builder.header("User-Agent", Api.mediaUserAgent())

        val response = Http.send(builder.build())

        val body = response.body

        if (!response.isSuccessful || body == null || body.size < 8) {
            Log.d {
                "[YouTube/Подача] Эфир: заголовок дорожки $itag не дался " +
                    "(код ${response.statusCode})"
            }

            return null
        }

        /**
         * Убеждаемся, что это и вправду заголовок, а не первый кусок.
         *
         * У заголовка в начале `ftyp`, у куска — `moof`. Подсунуть
         * плееру кусок вместо заголовка значит получить не отказ,
         * а тишину с чёрным кадром, и искать причину потом негде.
         */
        val mark = String(body, 4, 4, Charsets.ISO_8859_1)

        if (mark != "ftyp" && mark != "styp" && mark != "moov") {
            Log.d { "[YouTube/Подача] Эфир: вместо заголовка дорожки $itag пришло «$mark»" }

            return null
        }

        return body
    }

    /**
     * Подача для того, кто **не** плеер, — для скачивания.
     *
     * Отличается только тем, что не трогает общего состояния: последняя
     * подача, ступени и озвучки остаются те, что были у просмотра. Иначе
     * загрузка, начатая во время просмотра, подменяла бы плееру и меню
     * качества, и перечень озвучек — от другого ролика.
     */
    fun detachedSabrFor(playerResponse: JSONObject, maxHeight: Int): Sabr? {
        synchronized(this) {
            val keepSabr = lastSabr
            val keepHeights = lastSabrHeights
            val keepTracks = lastSabrTracks
            val keepTiers = sabrTiers
            val keepCap = sabrCap
            val keepTrack = sabrTrack
            val keepExact = sabrExact

            val fresh = sabrFor(playerResponse, maxHeight, null, true)

            lastSabr = keepSabr
            lastSabrHeights = keepHeights
            lastSabrTracks = keepTracks
            sabrTiers = keepTiers
            sabrCap = keepCap
            sabrTrack = keepTrack
            sabrExact = keepExact

            return fresh
        }
    }

    /** Ступени качества, что были в последнем ответе с подачей. */
    fun sabrHeights(): List<Int> = lastSabrHeights

    /**
     * Ступень, которая играет на подаче прямо сейчас; 0, если неизвестна.
     *
     * Выбирает её сервер из того, что мы предложили, и совпадать с нашим
     * пожеланием она не обязана: попросив 1080p, легко получить 720p.
     */
    fun sabrPlayingHeight(): Int {
        val itag = lastSabr?.playingItag ?: 0

        if (itag <= 0) {
            return 0
        }

        return sabrTiers[itag] ?: 0
    }

    /** Озвучки из последнего ответа с подачей. */
    fun sabrAudioTracks(): List<AudioTrack> = lastSabrTracks

    /**
     * Озвучки, какие есть у **готовых** дорожек.
     *
     * Пара к [sabrAudioTracks], и нужна ровно потому, что путей
     * воспроизведения два. Подача присылает перечень озвучек сама,
     * а когда играем по готовым адресам — того перечня нет вовсе, хотя
     * сами дорожки в ответе есть и помечены `audioTrack`.
     */
    fun audioTracksIn(formats: List<Format>): List<AudioTrack> {
        val tracks = ArrayList<AudioTrack>()
        val seen = HashSet<String>()

        for (format in formats) {
            if (!format.hasAudio || format.hasVideo) {
                continue
            }

            val identifier = format.audioTrackId

            if (identifier.isNullOrEmpty() || !seen.add(identifier)) {
                continue
            }

            tracks.add(
                AudioTrack(
                    identifier,
                    format.audioTrackName ?: identifier,
                    format.audioIsDefault
                )
            )
        }

        return tracks
    }

    /** Длительность ролика в секундах по ответу `/player`; 0, если её там нет. */
    fun lengthIn(playerResponse: JSONObject?): Double =
        Json.text(Json.obj(playerResponse, "videoDetails"), "lengthSeconds")
            ?.toDoubleOrNull() ?: 0.0

    /**
     * Готовый склеенный поток из `formats` — видео и звук в одном файле.
     *
     * Нужен там, где раздельных дорожек не дали.
     */
    /**
     * Какой ступени оказался последний найденный склеенный поток.
     *
     * Спрашивать его отдельным разбором незачем: ищется он всё равно
     * тут же, рядом с адресом, а нужен ровно тому, кто этот адрес взял.
     * Загрузчик по нему подписывает запись — иначе в списке скачанного
     * стояло бы просимое качество, а в файле лежало бы другое.
     */
    @Volatile
    var progressiveHeight = 0
        private set

    fun progressiveUrlIn(playerResponse: JSONObject?): String? {
        val streaming = Json.obj(playerResponse, "streamingData")
        val formats = Json.array(streaming, "formats") ?: return null

        val poToken = PoToken.tokenFor(Api.streamBinding())

        var best: String? = null
        var bestHeight = -1

        for (index in 0 until formats.length()) {
            val format = formats.opt(index) as? JSONObject ?: continue

            val url = Json.text(format, "url") ?: continue
            val mime = Json.text(format, "mimeType") ?: continue

            // Без адреса брать нечего: расшифровывать `signatureCipher`
            // мы не умеем — ради этого потоки и просятся у других клиентов.
            if (!mime.contains("video/mp4")) {
                continue
            }

            // Склеенный — значит со звуком внутри.
            val hasAudio = mime.contains("mp4a") || format.opt("audioChannels") != null

            if (!hasAudio) {
                continue
            }

            val height = Json.int(format, "height")

            if (height > bestHeight) {
                bestHeight = height
                best = url
            }
        }

        var found = best ?: return null

        progressiveHeight = if (bestHeight > 0) bestHeight else 0

        if (!poToken.isNullOrEmpty() && !found.contains("&pot=")) {
            found += "&pot=$poToken"
        }

        found = NSig.fixUrl(found)

        Log.d { "[YouTube/Потоки] Склеенный поток ${bestHeight}p${signatureNote(found)}" }

        return found
    }

    /**
     * Все дорожки из ответа `/player`. Порт `ParseAdaptiveFormatsForDemux`:
     * дорожки без `url` пропускаются молча — у них вместо адреса шифр
     * подписи, а расшифровать его нам нечем.
     */
    fun formatsFrom(playerResponse: JSONObject?): List<Format> {
        val streaming = Json.obj(playerResponse, "streamingData")
        val adaptive = Json.array(streaming, "adaptiveFormats")

        val poToken = PoToken.tokenFor(Api.streamBinding())

        Log.d {
            if (!poToken.isNullOrEmpty()) {
                "[YouTube/Потоки] Адреса с PO-токеном сеанса (${poToken.length} знаков)"
            } else {
                "[YouTube/Потоки] Адреса без PO-токена — клиент его не просил"
            }
        }

        val result = ArrayList<Format>()

        if (adaptive != null) {
            for (index in 0 until adaptive.length()) {
                val format = adaptive.opt(index) as? JSONObject ?: continue

                var url = Json.text(format, "url") ?: continue

                val mime = Json.string(format, "mimeType", "") ?: ""
                val mimeLower = mime.lowercase(Locale.US)

                val entry = Format()

                if (!poToken.isNullOrEmpty() && !url.contains("&pot=")) {
                    url += "&pot=$poToken"
                }

                url = NSig.fixUrl(url)

                entry.url = url
                entry.width = Json.int(format, "width")
                entry.height = Json.int(format, "height")
                entry.mimeType = mime
                entry.itag = Json.int(format, "itag")
                entry.fps = Json.int(format, "fps")
                entry.bitrate = Json.int(format, "bitrate")
                entry.averageBitrate = Json.int(format, "averageBitrate")

                // Приходит строкой, а не числом, — как и всё длинное
                // в этом ответе.
                entry.contentLength = Json.string(format, "contentLength")
                    ?.toLongOrNull() ?: 0

                entry.initialRangeStart = rangeField(format, "initRange", "start")
                entry.initialRangeEnd = rangeField(format, "initRange", "end")
                entry.indexRangeStart = rangeField(format, "indexRange", "start")
                entry.indexRangeEnd = rangeField(format, "indexRange", "end")

                entry.hasAudio = mimeLower.contains("audio") ||
                    format.opt("audioChannels") != null

                entry.hasVideo = mimeLower.contains("video") ||
                    format.opt("width") != null

                entry.xtags = Json.text(format, "xtags")
                entry.audioTrackId = audioTrackField(format, "id")
                entry.audioTrackName = audioTrackField(format, "displayName")
                entry.audioIsDefault = Json.bool(
                    Json.obj(format, "audioTrack"), "audioIsDefault"
                )
                entry.audioIsOriginal = isOriginalTrack(format)
                entry.audioIsCompressed = isCompressed(format)

                result.add(entry)
            }
        }

        Log.d {
            val summary = result.joinToString(" ") {
                "${it.itag}:${it.height}p" + if (it.isH264()) "" else "(не H.264)"
            }

            "[YouTube/Потоки] Разобрано дорожек: ${result.size} — " +
                summary.ifEmpty { "пусто" }
        }

        /**
         * Звуковые дорожки — отдельной строкой, со всеми пометками.
         *
         * У ролика с озвучками их несколько, и по одному номеру itag
         * не понять, какая из них родная. Различают их `audioTrack`
         * и пометки `xtags`, и когда играет не тот язык, разговор
         * начинается именно с этой строки.
         */
        Log.d {
            val voices = result.filter { it.hasAudio && !it.hasVideo }

            if (voices.isEmpty()) {
                "[YouTube/Потоки] Звуковых дорожек нет"
            } else {
                "[YouTube/Потоки] Звук: " + voices.joinToString("; ") { one ->
                    "itag ${one.itag} ${one.bitrate / 1000}к " +
                        "${one.audioTrackId ?: "—"} «${one.audioTrackName ?: "—"}»" +
                        (if (one.audioIsDefault) " основная" else "") +
                        (one.xtags?.let { " [$it]" } ?: "")
                }
            }
        }

        return result
    }

    /**
     * Ступени качества прямо из ответа `/player` — даже когда адресов нет.
     *
     * [formatsFrom] выбрасывает всё, у чего нет `url`: на подаче адресов
     * не бывает вовсе, и список выходил пустым. Меню скачивания на этом
     * объявляло, что забирать нечего, — хотя забрать можно, только
     * не диапазонами байт, а подачей.
     */
    fun heightsInResponse(playerResponse: JSONObject?): List<Int> {
        val streaming = Json.obj(playerResponse, "streamingData")
        val adaptive = Json.array(streaming, "adaptiveFormats") ?: return emptyList()

        val tiers = HashSet<Int>()

        for (index in 0 until adaptive.length()) {
            val format = adaptive.opt(index) as? JSONObject ?: continue

            val mime = Json.string(format, "mimeType", "")?.lowercase(Locale.US) ?: ""

            if (!mime.contains("video") || !mime.contains("avc1")) {
                continue
            }

            val tier = tierIn(format)

            if (tier > 0) {
                tiers.add(tier)
            }
        }

        return tiers.sorted()
    }

    /** Ступени качества, которые предлагает ролик, по возрастанию. */
    fun heightsIn(formats: List<Format>): List<Int> {
        val tiers = HashSet<Int>()

        for (format in formats) {
            if (!format.hasVideo || format.hasAudio || !format.isH264()) {
                continue
            }

            val tier = format.qualityTier()

            if (tier > 0) {
                tiers.add(tier)
            }
        }

        return tiers.sorted()
    }

    /**
     * Видеодорожка не выше запрошенной ступени.
     *
     * Берётся самая крупная из подходящих. Если под потолок не подошло
     * ничего — самая мелкая из имеющихся: у ролика, который весь выше
     * потолка, лучше показать мелкое, чем ничего.
     */
    fun chooseVideo(formats: List<Format>, maxHeight: Int): Format? {
        var best: Format? = null

        for (format in formats) {
            // Дорожка должна быть чисто видеоряд, без звука: плеер
            // собирает поток из двух дорожек сам.
            if (!format.hasVideo || format.hasAudio || !format.isH264()) {
                continue
            }

            /**
             * Шестидесятикадровые обходим по тому же правилу, что и подача.
             *
             * Здесь его не было вовсе, и это дорого стоило. Подача с тем же
             * ответом `/player` выбирала 720p30, а стоило ей не задаться —
             * запасной выбор брал 1080p60, самую тяжёлую дорожку из всех
             * предложенных: на GT-N8000 от открытия до первого кадра
             * проходило тридцать шесть секунд. Один и тот же ролик, один
             * и тот же ответ, разница только в том, кто выбирал.
             */
            val tier = format.qualityTier()

            if (format.fps > 31 && (prefersThirtyFrames() || tier > sixtyCap())) {
                continue
            }

            if (maxHeight > 0 && tier > maxHeight) {
                continue
            }

            if (best == null || tier > best.qualityTier()) {
                best = format
            }
        }

        if (best != null) {
            return best
        }

        for (format in formats) {
            if (!format.hasVideo || format.hasAudio || !format.isH264()) {
                continue
            }

            if (best == null || format.qualityTier() < best.qualityTier()) {
                best = format
            }
        }

        return best
    }

    /** Звуковая дорожка; при многоязычии — предпочтённая либо «по умолчанию». */
    fun chooseAudio(formats: List<Format>, preferredTrack: String?): Format? {
        val audio = ArrayList<Format>()

        for (format in formats) {
            if (!format.hasAudio || format.hasVideo) {
                continue
            }

            /**
             * Только AAC.
             *
             * В оригинале причина была двойная: Opus не декодирует старое
             * железо, а ремуксер умел заворачивать в ADTS именно AAC.
             * Второй причины больше нет — ремуксера нет вовсе, — но первая
             * осталась: Opus в контейнере MP4 система умеет с Android 10,
             * а до неё не умеет никак.
             */
            if (!format.mimeType.lowercase(Locale.US).contains("mp4a")) {
                continue
            }

            audio.add(format)
        }

        if (audio.isEmpty()) {
            return null
        }

        var best: Format? = null

        for (format in audio) {
            // Выбранная человеком дорожка языка важнее всего остального.
            if (!preferredTrack.isNullOrEmpty() && format.audioTrackId != preferredTrack) {
                continue
            }

            if (best == null || format.bitrate > best.bitrate) {
                best = format
            }
        }

        if (best != null) {
            return best
        }

        /**
         * Человек ничего не выбирал — значит, играем родную.
         *
         * Не «основную»: основной сервер зовёт ту, что подходит языку
         * запроса, и у ролика с озвучками это озвучка. Просивший включить
         * ролик перевода не заказывал.
         */
        for (format in audio) {
            if (!format.audioIsOriginal || format.audioIsCompressed) {
                continue
            }

            if (best == null || format.bitrate > best.bitrate) {
                best = format
            }
        }

        if (best != null) {
            return best
        }

        // Родной нет — тогда отмеченную сервером основной,
        // а если и такой нет, самую щедрую по битрейту.
        for (format in audio) {
            if (format.audioIsDefault && (best == null || format.bitrate > best.bitrate)) {
                best = format
            }
        }

        if (best != null) {
            return best
        }

        for (format in audio) {
            if (best == null || format.bitrate > best.bitrate) {
                best = format
            }
        }

        return best
    }

    // --- Возможности устройства -------------------------------------------

    /**
     * Потолок разрешения.
     *
     * Здесь и проходит главная граница между версиями. В iOS это была
     * таблица моделей — и не от лени: спросить декодер до iOS 8 нельзя
     * вовсе. Android спрашивается, и спрашивается точно; всё, что нужно
     * знать об этом, лежит в [Capabilities].
     */
    fun deviceMaxHeight(): Int = Capabilities.maxHeight()

    /**
     * Выше ли это того, что устройство заведомо тянет.
     *
     * Потолок здесь — совет, а не запрет: выбрать можно любое качество,
     * но про негодное человека надо предупредить, а не молча подсунуть
     * звук без картинки.
     */
    fun isBeyondDevice(height: Int): Boolean = height > deviceMaxHeight()

    /**
     * Нужно ли обходить шестидесятикадровые дорожки.
     *
     * Ответ учитывает и настройку: разрешить их можно на любом устройстве,
     * запрета здесь нет — только осторожность по умолчанию.
     */
    fun prefersThirtyFrames(): Boolean = !Settings.allowsSixtyFrames

    /**
     * То же по одному лишь железу, без оглядки на настройку.
     *
     * Нужно самой настройке: по этому ответу она решает, что показать
     * по умолчанию и стоит ли предупреждать о рывках.
     */
    fun deviceDislikesSixtyFrames(): Boolean = !Capabilities.supportsSixtyFrames()

    /**
     * До какой ступени устройство тянет шестьдесят кадров.
     *
     * Мерка эта отдельная от общего потолка, и вот почему. GT-N8000
     * играет 720p60, а на 1080p60 его декодер отвечает отказом
     * `NO_EXCEEDS_CAPABILITIES`: по размеру кадра ступень ему по силам,
     * а вот столько макроблоков в секунду — уже нет. Прежде мы знали
     * только «тянет шестьдесят кадров» без ступени и выбирали 1080p60,
     * после чего плеер показывал ошибку кодека.
     */
    fun sixtyCap(): Int = Capabilities.maxSixtyHeight()
}
