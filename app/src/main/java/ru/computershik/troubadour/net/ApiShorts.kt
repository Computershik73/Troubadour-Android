package ru.computershik.troubadour.net

import org.json.JSONObject
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.model.VideoItem

/**
 * Shorts — своя поверхность со своими правилами.
 *
 * У reel-точек всё своё: версии клиентов, номер TV-клиента (**7**, а не 85),
 * обязательный `X-Goog-Visitor-Id` у веб-клиентов, свои `Origin` и `Referer`,
 * адрес без `prettyPrint`. Общий [Api.post] шлёт другое, и с ним эти точки
 * отвечают отказом.
 */

/** Лента Shorts: карточки и токен продолжения. */
class ShortsPage(val items: List<VideoItem>, val sequence: String?)

/** Всё, что странице Shorts нужно знать об одном ролике. */
class ShortsPlayback {
    /** Готовый склеенный поток; пусто — играть придётся подачей. */
    var url: String? = null

    /**
     * Сам ответ `/player`: склеенного потока может не быть вовсе, и тогда
     * играть придётся подачей — а для неё нужен весь ответ, а не одна ссылка.
     */
    var player: JSONObject? = null

    var title: String? = null
    var channelTitle: String? = null
    var channelId: String? = null
    var channelThumbnail: String? = null
    var subscribers: String? = null
    var likes: String? = null
    var comments: String? = null
    var commentsToken: String? = null
    var liked: Boolean = false
    var disliked: Boolean = false
    var subscribed: Boolean = false

    /** Упёрлись в проверку «вы не робот» — страница покажет причину. */
    var botGate: Boolean = false
}

/**
 * Один заход за лентой Shorts определённым клиентом.
 *
 * Тело у seedless-запроса своё: `inputType`, `params`
 * и `disablePlayerResponse` — порт `BuildSeedlessShortsPayload`.
 * У продолжения тело другое, из одного `sequenceParams`.
 */
private fun Api.shortsAttempt(
    sequence: String?,
    client: String,
    authorize: Boolean
): JSONObject? {
    val body = JSONObject()
    val endpoint: String

    if (!sequence.isNullOrEmpty()) {
        endpoint = "reel/reel_watch_sequence"

        body.put("sequenceParams", sequence)
    } else {
        endpoint = "reel/reel_item_watch"

        body.put("inputType", "REEL_WATCH_INPUT_TYPE_SEEDLESS")
        body.put("params", "CA8%3D")
        body.put("disablePlayerResponse", true)
    }

    return shortsPost(endpoint, body, client, authorize)
}

/**
 * Запрос к поверхности Shorts — порт `PostInnertubeJsonAsync`.
 *
 * Здесь важна каждая мелочь, и особенно номер TV-клиента: у reel-запросов
 * это **7**, а не 85, как у `browse`. С чужим номером тело TVHTML5
 * противоречит заголовкам, и запрос отвечает отказом.
 * `X-Goog-Visitor-Id` у веб-клиентов тоже обязателен — без него reel-точки
 * не отвечают ничем полезным.
 */
internal fun Api.shortsPost(
    endpoint: String,
    body: JSONObject,
    client: String,
    authorize: Boolean
): JSONObject? {
    val payload = JSONObject(body.toString())

    /**
     * Контекст клиента у reel-запросов свой — `BuildShortsClientJson`:
     * у TV к обычному набору добавляется `clientFormFactor`, у MWEB —
     * платформа MOBILE, и версии тоже свои.
     */
    val context = JSONObject()

    context.put("clientName", client)
    context.put("hl", hl())
    context.put("gl", gl())

    val version = when (client) {
        "TVHTML5" -> {
            context.put("platform", "TV")
            context.put("clientFormFactor", "UNKNOWN_FORM_FACTOR")

            Api.TV_PLAYER_VERSION
        }

        "MWEB" -> {
            context.put("platform", "MOBILE")

            Api.SHORTS_MWEB_VERSION
        }

        "ANDROID" -> {
            // `BuildAndroidClientJson`: имя, версия, androidSdkVersion,
            // hl и gl — и больше ничего. Лишние поля здесь тоже расхождение.
            context.put("androidSdkVersion", 30)

            Api.SHORTS_ANDROID_VERSION
        }

        else -> Api.SHORTS_WEB_VERSION
    }

    context.put("clientVersion", version)

    val whole = JSONObject().put("client", context)

    /**
     * От чьего имени просим — как и во всех прочих запросах.
     *
     * Контекст здесь собирается свой, мимо общего построителя, и потому
     * выбранный канал сюда не попадал вовсе: человек переключался, а лента
     * Shorts оставалась от того канала, какой выберет сервер.
     */
    val behalf = activeAccountPage

    if (!behalf.isNullOrEmpty()) {
        whole.put("user", JSONObject().put("onBehalfOfUser", behalf))
    }

    payload.put("context", whole)

    // Адрес без `prettyPrint`: в `PostInnertubeJsonAsync` его нет, только ключ.
    val builder = Http.request(
        "https://www.youtube.com/youtubei/v1/$endpoint?key=${Api.INNERTUBE_KEY}"
    ) ?: return null

    builder.post(Http.jsonBody(Json.encode(payload)))
    builder.header("Content-Type", "application/json")
    builder.header("Accept", "application/json")

    if (authorize) {
        val token = Auth.accessToken()

        if (token.isNotEmpty()) {
            builder.header("Authorization", "Bearer $token")
        }
    }

    builder.header("Accept-Language", "${hl()},${hl()};q=0.9")
    builder.header("X-YouTube-Client-Version", version)

    when (client) {
        "ANDROID" -> {
            // У ANDROID заголовков всего три: номер, версия и User-Agent.
            // Ни visitor-id, ни Origin с Referer там нет.
            builder.header("X-YouTube-Client-Name", "3")
            builder.header(
                "User-Agent",
                "com.google.android.youtube/${Api.SHORTS_ANDROID_VERSION} " +
                    "(Linux; U; Android 11) gzip"
            )
        }

        "TVHTML5" -> {
            builder.header("X-YouTube-Client-Name", "7")
            builder.header("User-Agent", Api.TV_USER_AGENT)
            builder.header("Origin", "https://www.youtube.com")
            builder.header("Referer", "https://www.youtube.com/tv")
        }

        "MWEB" -> {
            builder.header("X-YouTube-Client-Name", "2")
            builder.header(
                "User-Agent",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) " +
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 " +
                    "Mobile/15E148 Safari/604.1"
            )
            builder.header("X-Goog-Visitor-Id", Api.FALLBACK_VISITOR_DATA)
            builder.header("Origin", "https://m.youtube.com")
            builder.header("Referer", "https://m.youtube.com/shorts/")
        }

        else -> {
            builder.header("X-YouTube-Client-Name", "1")
            builder.header("User-Agent", Api.WEB_USER_AGENT)
            builder.header("X-Goog-Visitor-Id", Api.FALLBACK_VISITOR_DATA)
            builder.header("Origin", "https://www.youtube.com")
            builder.header("Referer", "https://www.youtube.com/shorts/")
        }
    }

    Log.d {
        "[YouTube/Shorts] → $endpoint ($client${if (authorize) ", с токеном" else ""})"
    }

    val response = Http.send(builder.build(), 12 * 1024 * 1024)

    if (!response.isSuccessful) {
        Log.d { "[YouTube/Shorts] $endpoint ($client): код ${response.statusCode}" }

        return null
    }

    val parsed = Json.parse(response.body)

    captureSession(parsed)

    return parsed
}

/**
 * Есть ли в ответе хоть один ролик.
 *
 * Проверять надо именно это, а не «ответ пришёл». Отказ поверхности reel
 * приезжает таким же разобранным словарём, как и лента, и перебор клиентов
 * на нём останавливался: первый же ответивший считался удачей, хотя роликов
 * в нём не было ни одного.
 */
private fun shortsUsable(json: JSONObject?): Boolean {
    if (json == null) {
        return false
    }

    return Json.findAll("reelWatchEndpoint", json, 60000).isNotEmpty()
}

/**
 * Кто отдал прошлую страницу ленты Shorts и с токеном ли.
 *
 * Держится между страницами затем, что `sequenceParams` выдан **тем**
 * клиентом: продолжение чужого перечня — уже не тот перечень.
 */
private var shortsClient: String? = null
private var shortsAuthorized = false

/**
 * Лента Shorts. [sequence] — токен продолжения из прошлого ответа либо null
 * для первой страницы.
 */
fun Api.shorts(sequence: String?): ShortsPage? {
    val first = sequence.isNullOrEmpty()

    // Новая лента — и клиента выбираем заново.
    if (first) {
        shortsClient = null
        shortsAuthorized = false
    }

    var json: JSONObject? = null
    var served: String? = null
    var servedAuth = false

    /**
     * Продолжает тот, кто начал.
     *
     * Прежде каждая страница выбирала клиента заново, с самого начала
     * перечня. Стоило TV-клиенту разок промолчать посреди ленты — и
     * следующая страница приезжала от другого, а то и вовсе без токена.
     * Со стороны это ровно то, на что жаловались: несколько роликов своих,
     * а дальше как будто случайные.
     */
    if (!first && shortsClient != null) {
        json = shortsAttempt(sequence, shortsClient!!, shortsAuthorized)

        if (shortsUsable(json)) {
            served = shortsClient
            servedAuth = shortsAuthorized
        } else {
            json = null
        }
    }

    /**
     * Порядок перебора — из `ShortsAuthClients`. Токен выдан TV-клиенту,
     * и какой из reel-клиентов его примет, заранее неизвестно: тело обязано
     * совпадать с заголовками, поэтому каждый пробуется целиком.
     */
    if (json == null && Auth.isSignedIn()) {
        for (client in listOf("TVHTML5", "MWEB", "WEB")) {
            val attempt = shortsAttempt(sequence, client, true)

            if (shortsUsable(attempt)) {
                json = attempt
                served = client
                servedAuth = true

                break
            }
        }
    }

    /**
     * Без токена — только у гостя и только на первой странице.
     *
     * Гостю иначе ленты не видать вовсе, а первой странице простительно:
     * лучше общая лента, чем пустой раздел. А вот подменять учётную запись
     * **посреди** ленты нельзя: человек листает своё, и вдруг пошло чужое —
     * без единого слова о том, что случилось. Прежде так и было.
     */
    if (json == null && (first || !Auth.isSignedIn())) {
        val attempt = shortsAttempt(sequence, "WEB", false)

        if (shortsUsable(attempt)) {
            json = attempt
            served = "WEB"
            servedAuth = false

            if (Auth.isSignedIn()) {
                Log.d {
                    "[YouTube/Shorts] Ни один клиент не принял токен — " +
                        "лента будет общей, не по учётной записи"
                }
            }
        }
    }

    if (json == null) {
        Log.d {
            "[YouTube/Shorts] Продолжения не дал никто" +
                if (!first && Auth.isSignedIn()) {
                    " — чужую ленту вместо своей не подставляем"
                } else {
                    ""
                }
        }

        return null
    }

    shortsClient = served
    shortsAuthorized = servedAuth

    val items = ArrayList<VideoItem>()
    val seen = HashSet<String>()

    /**
     * Ролики лежат за `reelWatchEndpoint` — и в `entries`, и, у части
     * ответов, прямо в корне под `replacementEndpoint`. Поиск по всему
     * дереву находит оба случая, как и `ExtractShortsEntries` с его
     * запасным обходом.
     */
    for (endpoint in Json.findAll("reelWatchEndpoint", json, 60000)) {
        val videoId = Json.text(endpoint, "videoId")

        if (videoId == null || videoId.length != 11 || !seen.add(videoId)) {
            continue
        }

        val item = VideoItem()

        item.videoId = videoId
        item.title = "Shorts"
        item.isShort = true

        // Превью у Shorts в ответе нет: `BuildHqThumbnailUrl` собирает
        // его из идентификатора, и здесь то же самое.
        item.thumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        // Пропуск на ленту с этого ролика — он же и в карточке из поиска.
        item.shortsSequence = Json.text(endpoint, "sequenceParams")

        /**
         * Подписи в ленте reel есть не всегда: у seedless-ответа их обычно
         * нет вовсе, и тогда они приезжают позже, вместе с потоком,
         * из `videoDetails`. Но если оверлей всё же прислали — берём
         * оттуда, как `ApplyShortsUiMetadata` в оригинале.
         */
        val overlay = Json.findFirst("reelPlayerOverlayRenderer", endpoint, 2000)

        Json.renderedText(overlay, "reelTitleText")?.let { item.title = it }

        val header = Json.findFirst("reelPlayerHeaderRenderer", endpoint, 2000)

        Json.renderedText(header, "channelTitleText")?.let { item.channelTitle = it }

        item.channelThumbnail = Json.thumbnail(header, "channelThumbnail", 88)

        items.add(item)
    }

    var token = Json.text(json, "sequenceContinuation")

    if (token == null) {
        val command = Json.findFirst("continuationCommand", json, 60000)

        token = Json.text(command, "token")
    }

    Log.d {
        "[YouTube/Shorts] роликов: ${items.size}, отдал $served " +
            "(${if (servedAuth) "с токеном" else "без токена"}), " +
            "продолжение: ${if (token != null) "есть" else "нет"}"
    }

    return ShortsPage(items, token)
}

/** Ответ `/player` для вертикального ролика; [gate] — упёрлись ли в проверку. */
fun Api.shortsPlayerResponse(videoId: String?, gate: BooleanArray? = null): JSONObject? {
    gate?.set(0, false)

    if (videoId.isNullOrEmpty()) {
        return null
    }

    /**
     * Сперва — общий путь, тот же, которым играют обычные ролики.
     *
     * Порядок из оригинала (ANDROID, WEB, WEB с токеном) сложился, когда
     * безымянные клиенты ещё отдавали потоки. Сейчас все трое отвечают
     * `LOGIN_REQUIRED`, и вертикальные ролики не играли вовсе: в журнале
     * это три отказа подряд и «потока нет».
     *
     * Подача SABR от TV-клиента работает и здесь — ролик тот же самый,
     * поверхность другая. Прежняя цепочка остаётся за ней: вдруг
     * когда-нибудь снова заработает, а склеенный поток играть проще.
     */
    val main = playerResponse(videoId)

    if (playerHasStreams(main) ||
        Json.text(Json.obj(main, "streamingData"), "serverAbrStreamingUrl") != null
    ) {
        Log.d { "[YouTube/Shorts] $videoId: поток общим путём (${streamNote(main)})" }

        return main
    }

    /**
     * Порядок и тела — из `GetShortPlaybackUrlAsync`: ANDROID без токена,
     * затем WEB без токена, затем WEB с токеном. Там же сказано, почему
     * первый заход без токена: `/player` у ANDROID-клиента отвечает
     * на `Bearer` отказом 400 INVALID_ARGUMENT.
     */
    for (attempt in listOf("ANDROID", "WEB", "WEB-AUTH")) {
        val authorize = attempt == "WEB-AUTH"
        val client = if (authorize) "WEB" else attempt

        if (authorize && !Auth.isSignedIn()) {
            continue
        }

        val body = JSONObject()

        body.put("videoId", videoId)
        body.put("contentCheckOk", true)
        body.put("racyCheckOk", true)

        if (client == "WEB") {
            body.put(
                "playbackContext",
                JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject().put("html5Preference", "HTML5_PREF_WANTS")
                )
            )
        }

        val json = shortsPost("player", body, client, authorize)

        if (!shortsUrlIn(json).isNullOrEmpty()) {
            Log.d {
                "[YouTube/Shorts] $videoId: поток от $client" +
                    if (authorize) " с токеном" else " без токена"
            }

            return json
        }

        Log.d {
            "[YouTube/Shorts] $videoId: $client${if (authorize) " с токеном" else ""} " +
                "без потока (${playabilityReason(json)})"
        }

        if (gate != null && isBotGate(json)) {
            gate[0] = true
        }
    }

    /**
     * Стена и на общем пути — тоже стена: до перебора дело доходит ровно
     * потому, что `/player` ответил `LOGIN_REQUIRED`.
     */
    if (gate != null && isBotGate(main)) {
        gate[0] = true
    }

    return null
}

/**
 * Готовый к воспроизведению адрес — порт `SelectPlayableShortUrl`.
 *
 * Берётся **склеенный** поток из `formats`: mp4 со звуком внутри.
 * Разбирать раздельные дорожки здесь не нужно, и в оригинале они тоже
 * не разбираются: у вертикальных роликов склеенная дорожка есть всегда,
 * а плеер играет её сам.
 *
 * Из нескольких выбирается самый высокий, не выше выбранного в настройках;
 * если ниже потолка нет ничего — самый высокий вообще, как
 * в `ChooseShortByPreferredQuality`.
 */
internal fun Api.shortsUrlIn(playerResponse: JSONObject?): String? {
    val formats = Json.array(Json.obj(playerResponse, "streamingData"), "formats")
        ?: return null

    var best: String? = null
    var bestHeight = -1
    var tallest: String? = null
    var tallestHeight = -1

    /**
     * Потолок берётся из настройки Shorts, а не общей: у вертикальных
     * роликов выбор качества свой — так и в оригинале, где у них своя
     * шестерёнка в правом верхнем углу.
     */
    val preferred = if (Settings.shortsHeight > 0) {
        Settings.shortsHeight
    } else {
        Settings.preferredHeight
    }

    for (index in 0 until formats.length()) {
        val format = formats.opt(index) as? JSONObject ?: continue

        val url = Json.text(format, "url") ?: continue
        val mime = Json.text(format, "mimeType") ?: continue

        if (!mime.contains("video/mp4")) {
            continue
        }

        val hasAudio = mime.contains("mp4a") || format.opt("audioChannels") != null

        if (!hasAudio) {
            continue
        }

        val height = Json.int(format, "height")

        if (height > tallestHeight) {
            tallestHeight = height
            tallest = url
        }

        if (preferred > 0 && height <= preferred && height > bestHeight) {
            bestHeight = height
            best = url
        }
    }

    return best ?: tallest
}

/**
 * Сведения о ролике из ответа `/player` — порт `ApplyPlayerResponseToShort`.
 *
 * Лента reel присылает только идентификаторы: ни названия, ни автора,
 * ни кружка в ней нет. Всё это лежит в `videoDetails` ответа `/player`,
 * который и так запрашивается ради потока.
 */
fun Api.shortsPlayback(videoId: String): ShortsPlayback? {
    val gate = BooleanArray(1)

    val json = shortsPlayerResponse(videoId, gate)

    if (json == null) {
        // Причину отказа стоит донести до страницы: проверку человек может
        // пройти, а пустой экран ему ничего не говорит.
        if (!gate[0]) {
            return null
        }

        val refused = ShortsPlayback()

        refused.botGate = true

        return refused
    }

    val result = ShortsPlayback()

    result.url = shortsUrlIn(json)
    result.player = json

    val details = Json.obj(json, "videoDetails")

    result.title = Json.text(details, "title")
    result.channelTitle = Json.text(details, "author")
    result.channelId = Json.text(details, "channelId")

    val microformat = Json.findFirst("playerMicroformatRenderer", json, 2000)

    Json.text(microformat, "ownerChannelName")?.let { result.channelTitle = it }

    /**
     * Счётчики у ленты reel есть не всегда: seedless-ответ присылает одни
     * идентификаторы, а `reelPlayerOverlayRenderer` приходит не к каждому
     * ролику. Тот же запрос, которым для обычного ролика берутся лайк
     * и подписка, знает и о вертикальном — ролик тот же, поверхность другая.
     */
    watchState(videoId)?.let { state ->
        state.title?.let { result.title = it }
        state.channelTitle?.let { result.channelTitle = it }
        state.channelThumbnail?.let { result.channelThumbnail = it }
        state.likes?.let { result.likes = it }
        state.comments?.let { result.comments = it }
        state.commentsToken?.let { result.commentsToken = it }

        result.liked = state.liked
        result.disliked = state.disliked
        result.subscribed = state.subscribed
    }

    /**
     * Без входа состояние молчит — недостающее берём обычным путём
     * страницы ролика.
     *
     * Тот запрос ходит клиентом WEB и без учётной записи: поставлен ли
     * лайк, он не знает, а **сколько** их — знает, как знает и кружок
     * автора, и метку комментариев. Оттого у вертикальных роликов без входа
     * не было ни счётчиков, ни аватарки: единственный, кто их приносил,
     * требовал входа.
     *
     * Запрос лишний, поэтому идёт только когда нужного и правда нет.
     */
    val missing = result.likes == null || result.comments == null ||
        result.channelThumbnail == null || result.commentsToken == null ||
        result.channelTitle == null || result.subscribers == null

    if (missing) {
        videoDetails(videoId, null)?.let { page ->
            if (result.likes == null) result.likes = page.likes
            if (result.comments == null) result.comments = page.commentsCount
            if (result.channelThumbnail == null) result.channelThumbnail = page.channelThumbnail
            if (result.commentsToken == null) result.commentsToken = page.commentsToken
            if (result.channelTitle == null) result.channelTitle = page.channelTitle
            if (result.subscribers == null) result.subscribers = page.subscribers
        }

        Log.d {
            "[YouTube/Shorts] $videoId: добрали со страницы ролика — " +
                "лайки ${result.likes ?: "нет"}, комментарии ${result.comments ?: "нет"}, " +
                "кружок ${if (result.channelThumbnail != null) "есть" else "нет"}"
        }
    }

    return result
}
