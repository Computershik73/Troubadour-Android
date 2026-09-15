package ru.computershik.troubadour.net

import okhttp3.Request
import org.json.JSONObject
import ru.computershik.troubadour.Delivery
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Settings

/**
 * Ответ `/player` — там лежат потоки.
 *
 * Отдаётся как есть, разбирают его Streams и Sabr: формат ответа
 * достаточно замысловат, чтобы не размазывать знание о нём по нескольким
 * местам.
 *
 * Цепочка клиентов перенесена дословно, вместе с порядком и причинами.
 * Порядок этот не выдуман, а выстрадан: каждый шаг в нём стоит на месте
 * потому, что предыдущий однажды подвёл.
 */

/**
 * Есть ли в ответе **пригодные** потоки, а не просто перечень дорожек.
 *
 * Проверять число дорожек мало, и это выяснилось на живом ответе.
 * WEB-клиент вошедшего отвечает `status: OK` и присылает три десятка
 * `adaptiveFormats` — **без единого адреса**: там только размеры, куски
 * и длительность, а само видео он ждёт через `serverAbrStreamingUrl`,
 * то есть по своему протоколу подачи. Склеенная дорожка (itag 18) адрес
 * тоже не содержит: вместо него `signatureCipher`.
 *
 * Толк от строгости прямой: цепочка клиентов идёт дальше вместо того,
 * чтобы остановиться на ответе, который выглядит удачным и ничего
 * не играет.
 */
internal fun playerHasStreams(json: JSONObject?): Boolean {
    val streaming = Json.obj(json, "streamingData") ?: return false

    for (name in listOf("adaptiveFormats", "formats")) {
        val list = Json.array(streaming, name) ?: continue

        for (index in 0 until list.length()) {
            val format = list.opt(index) as? JSONObject ?: continue

            if (!Json.text(format, "url").isNullOrEmpty()) {
                return true
            }
        }
    }

    return false
}

/**
 * Есть ли в ответе **раздельные** дорожки с адресами.
 *
 * Отличать их от склеенных приходится потому, что склеенная — это
 * единственный формат 18: 360p, звук внутри, и никакого выбора качества.
 * Ответ, где есть только она, формально «с потоками», и цепочка
 * останавливалась на нём, не дойдя до клиента, у которого дорожки
 * настоящие.
 */
internal fun playerHasAdaptiveStreams(json: JSONObject?): Boolean {
    val list = Json.array(Json.obj(json, "streamingData"), "adaptiveFormats") ?: return false

    for (index in 0 until list.length()) {
        val format = list.opt(index) as? JSONObject ?: continue

        if (!Json.text(format, "url").isNullOrEmpty()) {
            return true
        }
    }

    return false
}

/**
 * Что на самом деле лежит в ответе — строка для журнала.
 *
 * Отказ от `/player` виден сразу, а вот удачный ответ без единого
 * играбельного адреса выглядит в журнале точно так же, как рабочий.
 * Различать надо три случая: адреса готовы; адреса зашифрованы
 * (`signatureCipher`); адресов нет вовсе и подача идёт через SABR.
 */
internal fun streamNote(json: JSONObject?): String {
    val streaming = Json.obj(json, "streamingData") ?: return "без потоков"

    var ready = 0
    var sealed = 0
    var total = 0

    for (name in listOf("adaptiveFormats", "formats")) {
        val list = Json.array(streaming, name) ?: continue

        for (index in 0 until list.length()) {
            val format = list.opt(index) as? JSONObject ?: continue

            total++

            if (!Json.text(format, "url").isNullOrEmpty()) {
                ready++
            } else if (!Json.text(format, "signatureCipher").isNullOrEmpty()) {
                sealed++
            }
        }
    }

    val sabr = if (Json.text(streaming, "serverAbrStreamingUrl") != null) ", подача SABR" else ""

    return "дорожек $total: готовых $ready, шифрованных $sealed$sabr"
}

internal fun playabilityReason(json: JSONObject?): String {
    if (json == null) {
        return "пустой ответ"
    }

    val status = Json.obj(json, "playabilityStatus") ?: return "нет streamingData"

    val state = Json.string(status, "status", "?")
    val reason = Json.text(status, "reason")

    return if (reason != null) "$state: $reason" else state ?: "?"
}

/**
 * Упёрся ли ответ `/player` в проверку «вы не робот».
 *
 * Отличать её от прочих отказов нужно потому, что лечится она иначе:
 * не повтором и не другим клиентом, а тем, что человек проходит проверку
 * в браузере. Признак — состояние `LOGIN_REQUIRED`.
 */
fun Api.isBotGate(playerResponse: JSONObject?): Boolean {
    val status = Json.obj(playerResponse, "playabilityStatus")

    return Json.string(status, "status", "") == "LOGIN_REQUIRED"
}

/**
 * К чему привязывать PO-токен в этом сеансе.
 *
 * У вошедшего это `datasyncId` — признак учётной записи, который сервер
 * присылает в `responseContext`; у гостя `visitorData`. Идентификатор
 * ролика сюда не годится: им привязывают токен только там, где сеанса
 * нет вовсе.
 */
fun Api.sessionBinding(): String? {
    synchronized(Api) {
        sessionDatasyncId?.takeIf { it.isNotEmpty() }?.let { return it }
        sessionVisitorData?.takeIf { it.isNotEmpty() }?.let { return it }
    }

    return null
}

/**
 * Привязка, годная для адресов **текущего** ролика, — либо null, если
 * клиент, который их добыл, PO-токеном не пользовался.
 */
fun Api.streamBinding(): String? = synchronized(Api) { streamBindingValue }

/**
 * Имя, под которым надо ходить за самими кусками видео.
 *
 * Ссылки подписаны под клиента, который их получил (`c=ANDROID_VR` прямо
 * в адресе), и CDN сверяет, тем ли клиентом за ними пришли. Поэтому
 * за кусками надо идти тем же именем, что и за ссылками.
 */
fun Api.mediaUserAgent(): String =
    synchronized(Api) { streamUserAgent } ?: Api.ANDROID_VR_USER_AGENT

internal fun Api.setStreamUserAgent(userAgent: String?, binding: String?) {
    synchronized(Api) {
        streamUserAgent = userAgent

        streamClient = when (userAgent) {
            Api.TV_USER_AGENT -> "TVHTML5"
            Api.WEB_USER_AGENT -> "WEB"
            Api.IOS_USER_AGENT -> "IOS"
            Api.VISION_USER_AGENT -> "VISIONOS"
            else -> "ANDROID_VR"
        }

        streamBindingValue = binding
    }

    Log.d { "[YouTube/Плеер] За кусками идём как ${synchronized(Api) { streamClient }}" }
}

/**
 * Чем представляться подаче: описание клиента, который добыл нынешние
 * адреса потоков.
 *
 * Подача сверяет это с тем, кому выдан адрес; несовпадение она встречает
 * просьбой обновить ответ `/player` — без единого слова о причине.
 */
class StreamClientInfo(
    val number: Int,
    val version: String,
    val osName: String,
    val osVersion: String,
    val make: String?,
    val model: String?
)

fun Api.streamClientInfo(): StreamClientInfo {
    return when (synchronized(Api) { streamClient }) {
        "TVHTML5" -> StreamClientInfo(85, Api.TV_VERSION, "Tizen", "5.0", "Samsung", "SmartTV")
        "WEB" -> StreamClientInfo(1, Api.WEB_VERSION, "Windows", "10.0", null, null)
        "IOS" -> StreamClientInfo(5, Api.IOS_VERSION, "iOS", "18.0", "Apple", "iPhone16,2")
        "VISIONOS" -> StreamClientInfo(
            101, Api.VISION_VERSION, "visionOS", "1.0.2.21O209", "Apple", "RealityDevice14,1"
        )
        else -> StreamClientInfo(
            28, Api.ANDROID_VR_VERSION, "Android", "12L", "Oculus", "Quest 3"
        )
    }
}

/**
 * `visitorData` для запроса потоков.
 *
 * Порт `GetSessionVisitorDataAsync`. Значение выдаёт сам сервер,
 * и ANDROID_VR без него упирается в анти-бота («Sign in to confirm you're
 * not a bot»). Берётся оно из `responseContext` любого ответа youtubei;
 * если своего ещё нет — делается один лёгкий анонимный запрос WEB
 * `/player` ровно ради него, а в самом крайнем случае идёт вшитая строка.
 *
 * Замок общий, чтобы десяток экранов не пошёл добывать его разом.
 */
internal fun Api.sessionVisitorData(videoId: String?): String {
    synchronized(Api) {
        sessionVisitorData?.takeIf { it.isNotEmpty() }?.let { return it }

        val fetched = fetchFreshVisitorData(videoId)

        if (!fetched.isNullOrEmpty()) {
            sessionVisitorData = fetched

            return fetched
        }
    }

    // Раскодируем: в Config.cs строка лежит экранированной, а в заголовок
    // и в тело она должна уйти обычной.
    return try {
        java.net.URLDecoder.decode(Api.FALLBACK_VISITOR_DATA, "UTF-8")
    } catch (error: Exception) {
        Api.FALLBACK_VISITOR_DATA
    }
}

internal fun Api.invalidateVisitorData() {
    synchronized(Api) {
        sessionVisitorData = null
    }
}

/**
 * Запоминает `visitorData` и признак учётной записи из любого ответа
 * youtubei — порт `CaptureVisitorData`.
 *
 * Зовётся из [Api.post] на каждый ответ: в оригинале это делалось руками
 * в полудюжине мест, и всякий новый запрос приходилось не забыть туда
 * вписать.
 */
internal fun Api.captureSession(json: JSONObject?) {
    val context = Json.obj(json, "responseContext") ?: return

    /**
     * Признак учётной записи лежит в `mainAppWebResponseContext`
     * и приходит только у вошедшего; хвост из палок в нём лишний —
     * привязка идёт по самому номеру.
     */
    var datasync = Json.text(Json.obj(context, "mainAppWebResponseContext"), "datasyncId")

    if (!datasync.isNullOrEmpty()) {
        val bar = datasync.indexOf('|')

        if (bar >= 0) {
            datasync = datasync.substring(0, bar)
        }

        synchronized(Api) {
            if (datasync != sessionDatasyncId) {
                sessionDatasyncId = datasync

                Log.d { "[YouTube/Плеер] Привязка сеанса: учётная запись" }
            }
        }
    }

    val visitorData = Json.text(context, "visitorData") ?: return

    synchronized(Api) {
        if (sessionVisitorData.isNullOrEmpty()) {
            sessionVisitorData = visitorData
        }
    }
}

/** Один анонимный WEB `/player` ради `responseContext.visitorData`. */
private fun Api.fetchFreshVisitorData(videoId: String?): String? {
    if (videoId.isNullOrEmpty()) {
        return null
    }

    val client = JSONObject()

    client.put("clientName", "WEB")
    client.put("clientVersion", Api.VISITOR_SEED_VERSION)
    client.put("hl", hl())
    client.put("gl", gl())

    val payload = JSONObject()

    payload.put("context", JSONObject().put("client", client))
    payload.put("videoId", videoId)
    payload.put("contentCheckOk", true)
    payload.put("racyCheckOk", true)

    val json = postPlayerPayload(
        payload,
        mapOf(
            "User-Agent" to Api.WEB_USER_AGENT,
            "X-YouTube-Client-Name" to "1",
            "X-YouTube-Client-Version" to Api.VISITOR_SEED_VERSION
        )
    )

    return Json.text(Json.obj(json, "responseContext"), "visitorData")
}

/**
 * Первичный `/player` — клиентом IOS, как `BuildPlayerPayload`.
 *
 * В оригинале это самый первый запрос страницы ролика, и делается он
 * не ради потоков: из его `responseContext` берётся `visitorData`,
 * с которым следом идёт ANDROID_VR. Без этого шага ANDROID_VR упирается
 * в анти-бота, а свежий `visitorData` от анонимного WEB стену не снимает.
 *
 * Заодно в ответе лежит `hlsManifestUrl` — он нужен запасным путём, если
 * потоков не отдадут вовсе.
 *
 * @param authorize приложить ли к запросу учётную запись. Без неё запрос
 *   уходит гостем, и стену «подтвердите, что вы не бот» он проходит через
 *   раз: когда проходит — отдаёт два с лишним десятка настоящих раздельных
 *   дорожек, вплоть до 2160p, без шифра и без `n`. Это лучшее, что нам
 *   вообще отвечают, и терять его из-за случайности обидно.
 */
internal fun Api.iosPlayerResponse(videoId: String?, authorize: Boolean = false): JSONObject? {
    if (videoId.isNullOrEmpty()) {
        return null
    }

    val client = JSONObject()

    client.put("clientName", "IOS")
    client.put("clientVersion", Api.IOS_VERSION)
    client.put("deviceMake", "Apple")
    client.put("deviceModel", "iPhone16,2")
    client.put("osName", "iOS")
    client.put("osVersion", "18.0")
    client.put("hl", hl())
    client.put("gl", gl())

    val headers = HashMap<String, String>()

    headers["User-Agent"] = Api.IOS_USER_AGENT
    headers["X-YouTube-Client-Name"] = "5"
    headers["X-YouTube-Client-Version"] = Api.IOS_VERSION

    if (authorize) {
        val token = Auth.accessToken()

        if (token.isEmpty()) {
            return null
        }

        headers["Authorization"] = "Bearer $token"

        val visitorData = sessionVisitorData(videoId)

        if (visitorData.isNotEmpty()) {
            client.put("visitorData", visitorData)
            headers["X-Goog-Visitor-Id"] = visitorData
        }
    }

    val payload = JSONObject()

    payload.put("context", JSONObject().put("client", client))
    payload.put("videoId", videoId)
    payload.put("contentCheckOk", true)
    payload.put("racyCheckOk", true)

    return postPlayerPayload(payload, headers)
}

/**
 * Ответ `/player` — там лежат потоки.
 *
 * Порядок — из `LoadVideoDetailsFastAsync`: сперва IOS, и только потом
 * остальные. Первый запрос нужен не ради потоков, а ради `visitorData`.
 */
fun Api.playerResponse(videoId: String?): JSONObject? {
    if (videoId.isNullOrEmpty()) {
        return null
    }

    synchronized(Api) { streamVideoId = videoId }

    val primary = iosPlayerResponse(videoId)

    /**
     * TV-клиент спрашивается первым, и если он ответил подачей SABR —
     * берём его, не пробуя остальных.
     *
     * Так раздаёт видео сам YouTube, и так у нас доступны все качества
     * и все звуковые дорожки. Готовые адреса от ANDROID_VR дают меньше
     * и отказывают чаще: ссылка привязана к адресу, с которого её взяли,
     * а он у нас меняется от запроса к запросу — на них раздача отвечает
     * отказом там, где подаче всё равно.
     *
     * Годен этот ответ или нет, окончательно выяснится позже, когда подачу
     * спросят о первых кусках. Если не задастся — плеер сходит
     * за [androidVrPlayerResponse] и доиграет вторым путём.
     */
    if (Settings.delivery == Delivery.SABR && (Auth.isSignedIn() || WebAuth.isSignedIn())) {
        val forced = fetchTvPlayer(videoId)

        if (Json.text(Json.obj(forced, "streamingData"), "serverAbrStreamingUrl") != null) {
            Log.d { "[YouTube/Плеер] Подача SABR от TVHTML5 (${streamNote(forced)})" }

            setStreamUserAgent(Api.TV_USER_AGENT, sessionBinding())

            return forced
        }

        Log.d {
            "[YouTube/Плеер] TVHTML5 без подачи (${playabilityReason(forced)}) — " +
                "идём за готовыми адресами"
        }
    }

    return androidVrPlayerResponse(videoId, primary)
}

/**
 * Тот же ответ, но заведомо **без** подачи: ANDROID_VR и всё, что за ним.
 *
 * Нужен вторым заходом. [playerResponse] при настройке «подача SABR»
 * отдаёт ответ TV-клиента, а годен он или нет, выясняется позже — когда
 * подачу уже спросили о первых кусках. Отказ на этом шаге лечится
 * переходом к готовым адресам, и вот за ними сюда и приходят.
 */
fun Api.androidVrPlayerResponse(videoId: String?): JSONObject? {
    if (videoId.isNullOrEmpty()) {
        return null
    }

    synchronized(Api) { streamVideoId = videoId }

    return androidVrPlayerResponse(videoId, iosPlayerResponse(videoId))
}

/** Общая часть: ответ IOS-клиента уже на руках, второй раз его не просим. */
private fun Api.androidVrPlayerResponse(videoId: String, primary: JSONObject?): JSONObject? {
    /**
     * Сперва шлем Apple — он один играет дальше первой минуты.
     *
     * ANDROID_VR отвечает охотно и адреса даёт, но раздача обрывает его
     * сессию около шестидесятой секунды: подача перестаёт слать куски,
     * и просмотр встаёт. В журналах оригинала это «Подача не дала кусок 12
     * (время 61.4 с)» и такие же строки на 60.3 и 61.1 с — на разных
     * устройствах и роликах. Vision Pro этого предела не знает.
     */
    val vision = fetchVisionPlayer(videoId, null)

    if (playerHasStreams(vision)) {
        Log.d { "[YouTube/Плеер] Потоки от VISIONOS (${streamNote(vision)})" }

        setStreamUserAgent(Api.VISION_USER_AGENT, null)

        return vision
    }

    Log.d {
        "[YouTube/Плеер] VISIONOS без потоков (${playabilityReason(vision)}) — " +
            "идём к ANDROID_VR"
    }

    val json = fetchAndroidVrPlayer(videoId, null)

    if (playerHasStreams(json)) {
        setStreamUserAgent(Api.ANDROID_VR_USER_AGENT, synchronized(Api) { androidVrBinding })

        return json
    }

    Log.d {
        "[YouTube/Плеер] ANDROID_VR без потоков (${playabilityReason(json)}); " +
            "обновляем visitorData"
    }

    invalidateVisitorData()

    val retry = fetchAndroidVrPlayer(videoId, null)

    if (playerHasStreams(retry)) {
        Log.d { "[YouTube/Плеер] Повтор со свежим visitorData удался" }

        setStreamUserAgent(Api.ANDROID_VR_USER_AGENT, synchronized(Api) { androidVrBinding })

        return retry
    }

    Log.d { "[YouTube/Плеер] Повтор тоже без потоков (${playabilityReason(retry)})" }

    /** Ответ, годный лишь на крайний случай: склеенный поток без выбора. */
    var fallback: JSONObject? = null
    var fallbackAgent: String? = null

    /**
     * TV-клиент с токеном учётной записи.
     *
     * Стоит раньше WEB, потому что за ним настоящий вход, а не куки,
     * и потому что WEB давно раздаёт одну лишь SABR-подачу: дорожек
     * два десятка, адресов ноль.
     */
    if (Auth.isSignedIn() || WebAuth.isSignedIn()) {
        val tv = fetchTvPlayer(videoId)

        Log.d {
            "[YouTube/Плеер] TVHTML5 с учётной записью: ${playabilityReason(tv)} " +
                "(${streamNote(tv)})"
        }

        if (playerHasAdaptiveStreams(tv)) {
            Log.d { "[YouTube/Плеер] Потоки от TVHTML5" }

            setStreamUserAgent(Api.TV_USER_AGENT, sessionBinding())

            return tv
        }

        if (playerHasStreams(tv)) {
            Log.d { "[YouTube/Плеер] У TVHTML5 только склеенный поток — придержим" }

            fallback = tv
            fallbackAgent = Api.TV_USER_AGENT
        }

        /**
         * Тот же запрос, но от имени старой версии TV-клиента.
         *
         * Подачу через SABR раскатывают по версиям: свежий клиент получает
         * два десятка дорожек без единого адреса, тогда как версии
         * до раскатки отвечают по-старому — готовыми ссылками. Стоит это
         * одного запроса и делается лишь тогда, когда свежая версия
         * ответила одним SABR.
         */
        val legacy = fetchTvPlayer(videoId, Api.TV_LEGACY_VERSION, null)

        Log.d {
            "[YouTube/Плеер] TVHTML5 версии ${Api.TV_LEGACY_VERSION}: " +
                "${playabilityReason(legacy)} (${streamNote(legacy)})"
        }

        if (playerHasAdaptiveStreams(legacy)) {
            Log.d { "[YouTube/Плеер] Потоки от TVHTML5 старой версии" }

            setStreamUserAgent(Api.TV_USER_AGENT, sessionBinding())

            return legacy
        }

        if (fallback == null && playerHasStreams(legacy)) {
            fallback = legacy
            fallbackAgent = Api.TV_USER_AGENT
        }
    }

    /**
     * WEB под веб-сессией — уже после ANDROID_VR, а не до него.
     *
     * Поначалу он стоял первым: раз сессия снимает стену, пусть
     * и спрашивает. На живом ответе выяснилось, что снимать-то снимает,
     * а играть нечего — WEB присылает дорожки без адресов и ждёт подачи
     * через SABR. То есть каждый ролик начинался с запроса, который
     * заведомо ничего не даёт.
     */
    if (WebAuth.isSignedIn()) {
        val body = JSONObject()

        body.put("videoId", videoId)
        body.put("contentCheckOk", true)
        body.put("racyCheckOk", true)
        body.put(
            "playbackContext",
            JSONObject().put(
                "contentPlaybackContext",
                JSONObject().put("html5Preference", "HTML5_PREF_WANTS")
            )
        )

        val web = post("player", body, "WEB", false, 0.0)

        Log.d {
            "[YouTube/Плеер] WEB с веб-сессией: ${playabilityReason(web)} " +
                "(${streamNote(web)})"
        }

        if (playerHasAdaptiveStreams(web)) {
            Log.d { "[YouTube/Плеер] Потоки от WEB с веб-сессией" }

            setStreamUserAgent(Api.WEB_USER_AGENT, sessionBinding())

            return web
        }

        /**
         * Есть только склеенный поток — придержим его и пойдём дальше.
         *
         * Раньше цепочка на этом кончалась, и до ответа IOS-клиента,
         * добытого в самом начале, дело не доходило вовсе. А у WEB-адресов
         * есть своя беда: в них параметр `n`, который полагается
         * расшифровывать кодом из `base.js`, и раздача отбивает
         * нерасшифрованный отказом 403. У IOS такого нет.
         */
        if (playerHasStreams(web)) {
            Log.d { "[YouTube/Плеер] У WEB только склеенный поток — придержим" }

            fallback = web
            fallbackAgent = Api.WEB_USER_AGENT
        }

        /**
         * Отдельная строка про случай «ответ удачный, а играть нечего»:
         * иначе по журналу не отличить отказ от подачи через SABR.
         */
        val streaming = Json.obj(web, "streamingData")

        if (streaming != null) {
            Log.d {
                "[YouTube/Плеер] WEB ответил без адресов (дорожек " +
                    "${Json.array(streaming, "adaptiveFormats")?.length() ?: 0}, " +
                    "подача через SABR) — идём дальше"
            }
        } else {
            Log.d { "[YouTube/Плеер] WEB с веб-сессией без потоков (${playabilityReason(web)})" }
        }
    }

    /**
     * Последний ход — ответ IOS-клиента. Стену анти-бота он проходит чаще,
     * потому что и был первым запросом сеанса; в нём есть и обычные
     * дорожки, и `hlsManifestUrl`.
     */
    Log.d {
        "[YouTube/Плеер] IOS-клиент: ${playabilityReason(primary)} (${streamNote(primary)})"
    }

    if (playerHasStreams(primary)) {
        Log.d { "[YouTube/Плеер] Берём потоки у IOS-клиента" }

        /**
         * Привязки нет: IOS-клиент не просил токена и не слал
         * `visitorData`, так что его адресам приписывать нечего.
         */
        setStreamUserAgent(Api.IOS_USER_AGENT, null)

        return primary
    }

    /**
     * Тот же IOS-клиент, но с учётной записью.
     *
     * Гостя стена заворачивает через раз, а за токеном стоит настоящий
     * вход — с ним проходят и TVHTML5, и WEB. Отличие в том, что IOS
     * единственный отдаёт раздельные дорожки с готовыми адресами.
     */
    if (Auth.isSignedIn()) {
        val signedIn = iosPlayerResponse(videoId, true)

        Log.d {
            "[YouTube/Плеер] IOS с учётной записью: ${playabilityReason(signedIn)} " +
                "(${streamNote(signedIn)})"
        }

        if (playerHasStreams(signedIn)) {
            Log.d { "[YouTube/Плеер] Берём потоки у IOS с учётной записью" }

            setStreamUserAgent(Api.IOS_USER_AGENT, null)

            return signedIn
        }
    }

    if (fallback != null) {
        Log.d { "[YouTube/Плеер] Играем придержанный склеенный поток" }

        setStreamUserAgent(fallbackAgent, sessionBinding())

        return fallback
    }

    /**
     * И совсем последний ход — готовый HLS от IOS-клиента.
     *
     * Ради него в оригинале и шлётся iOS-овский User-Agent: «for best
     * chance of getting hlsManifestUrl». Разбирать там нечего — это
     * обычный плейлист, который ExoPlayer играет сам; качество выбирает
     * он же. Хуже, чем свой отбор дорожек, только тем, что выбор высоты
     * уходит из наших рук, — зато играет там, где ANDROID_VR упёрся
     * в анти-бота.
     *
     * Здесь, в отличие от iOS-версии, за него отвечает отдельный модуль
     * ExoPlayer (`exoplayer-hls`), и подключён он только ради этого хода.
     */
    if (Json.text(Json.obj(primary, "streamingData"), "hlsManifestUrl") != null) {
        Log.d { "[YouTube/Плеер] Потоков нет, но есть готовый HLS от IOS-клиента" }

        return primary
    }

    return retry ?: json
}

/**
 * `/player` под TV-клиентом с токеном учётной записи.
 *
 * Смысл его в том, что стену «подтвердите, что вы не бот» проходит
 * не всякий клиент, а тот, за кем стоит настоящий вход. Анонимные — IOS,
 * ANDROID_VR, WEB без кук — упираются в неё все до одного, и наш PO-токен
 * им не помогает: он выдан веб-BotGuard'ом и годится только клиентам
 * веб-семьи, к которой TV относится, а Android с его собственной
 * аттестацией — нет.
 *
 * `signatureTimestamp` обязателен: без него сервер отвечает «страницу надо
 * перезагрузить». Число добывается один раз и живёт в настройках.
 */
internal fun Api.fetchTvPlayer(
    videoId: String,
    version: String? = null,
    token: String? = null
): JSONObject? {
    versionOverride = version

    try {
        val context = JSONObject()

        context.put("html5Preference", "HTML5_PREF_WANTS")

        val sts = PlayerJs.signatureTimestamp()

        if (sts > 0) {
            context.put("signatureTimestamp", sts)
        }

        val playback = JSONObject().put("contentPlaybackContext", context)

        /**
         * Токен перезапроса, если подача его просила.
         *
         * Кладётся в `playbackContext` рядом с обычным содержимым, и по нему
         * сервер отдаёт свежие адрес подачи и настройки — то самое, чего
         * ему не хватало, когда он отвечал «обнови ответ».
         */
        if (!token.isNullOrEmpty()) {
            playback.put(
                "reloadPlaybackContext",
                JSONObject().put(
                    "reloadPlaybackParams", JSONObject().put("token", token)
                )
            )
        }

        val body = JSONObject()

        body.put("videoId", videoId)
        body.put("contentCheckOk", true)
        body.put("racyCheckOk", true)
        body.put("playbackContext", playback)

        val poToken = PoToken.tokenFor(sessionBinding())

        if (!poToken.isNullOrEmpty()) {
            body.put(
                "serviceIntegrityDimensions",
                JSONObject().put("poToken", poToken)
            )
        }

        return post("player", body, "TVHTML5", true, 0.0)
    } finally {
        versionOverride = null
    }
}

/**
 * Ответ `/player` под TV-клиентом, перезапрошенный по токену подачи.
 *
 * Подача иногда отвечает «твой ответ устарел» и присылает токен вместо
 * данных. Ответ с этим токеном несёт свежие адрес подачи и настройки.
 */
fun Api.tvPlayerResponse(videoId: String, reloadToken: String?): JSONObject? {
    if (reloadToken.isNullOrEmpty()) {
        return null
    }

    return fetchTvPlayer(videoId, null, reloadToken)
}

/**
 * Тот же перезапрос, но **тем клиентом, чей ответ мы обновляем**.
 *
 * Подача выдана под сессию клиента, и обновлять её надо у него же. Выбор
 * здесь повторяет [playerResponse] слово в слово, и это не лишняя
 * строгость: пока обновление спрашивали у TV-клиента всегда, у вошедших
 * всё сходилось, а у остальных приходил отказ в один килобайт — «в свежем
 * ответе нет адреса» — и просмотр вставал на месте, хотя лечился одним
 * запросом к ANDROID_VR.
 */
fun Api.refreshedPlayerResponse(videoId: String?, token: String?): JSONObject? {
    if (videoId.isNullOrEmpty()) {
        return null
    }

    if (Settings.delivery == Delivery.SABR && (Auth.isSignedIn() || WebAuth.isSignedIn())) {
        val tv = fetchTvPlayer(videoId, null, token)

        if (Json.text(Json.obj(tv, "streamingData"), "serverAbrStreamingUrl") != null) {
            return tv
        }

        Log.d {
            "[YouTube/Плеер] TVHTML5 обновиться не дал (${playabilityReason(tv)}) — " +
                "спросим ANDROID_VR"
        }
    }

    return fetchAndroidVrPlayer(videoId, token)
}

internal fun Api.fetchAndroidVrPlayer(videoId: String, token: String?): JSONObject? {
    val visitorData = sessionVisitorData(videoId)

    val client = JSONObject()

    client.put("clientName", "ANDROID_VR")
    client.put("clientVersion", Api.ANDROID_VR_VERSION)
    client.put("deviceMake", "Oculus")
    client.put("deviceModel", "Quest 3")
    client.put("androidSdkVersion", 32)
    client.put("osName", "Android")
    client.put("osVersion", "12L")
    client.put("platform", "MOBILE")
    client.put("hl", hl())
    client.put("gl", gl())

    if (visitorData.isNotEmpty()) {
        client.put("visitorData", visitorData)

        synchronized(Api) { androidVrBinding = visitorData }
    }

    val payload = JSONObject()

    payload.put("context", JSONObject().put("client", client))
    payload.put("videoId", videoId)
    payload.put("contentCheckOk", true)
    payload.put("racyCheckOk", true)

    if (!token.isNullOrEmpty()) {
        payload.put(
            "playbackContext",
            JSONObject().put(
                "reloadPlaybackContext",
                JSONObject().put(
                    "reloadPlaybackParams", JSONObject().put("token", token)
                )
            )
        )
    }

    /**
     * PO-токена здесь нет — и это не упущение.
     *
     * Наш токен чеканит веб-BotGuard, и годится он только клиентам
     * веб-семьи: WEB, MWEB, TVHTML5. У Android своя аттестация
     * (DroidGuard), запустить которую в браузере нечем, и веб-токен этот
     * клиент не признаёт: три прогона подряд с исправным токеном при трёх
     * разных привязках дали один и тот же `LOGIN_REQUIRED`.
     */
    val headers = HashMap<String, String>()

    headers["User-Agent"] = Api.ANDROID_VR_USER_AGENT
    headers["X-YouTube-Client-Name"] = "28"
    headers["X-YouTube-Client-Version"] = Api.ANDROID_VR_VERSION

    if (visitorData.isNotEmpty()) {
        headers["X-Goog-Visitor-Id"] = visitorData
    }

    return postPlayerPayload(payload, headers)
}

/**
 * Ответ `/player` от имени шлема Apple Vision Pro.
 *
 * Отличается от ANDROID_VR только тем, кем мы представляемся, — и этим
 * решает главную беду безымянного просмотра: сессия не кончается
 * на шестидесятой секунде. Ни PO-токена, ни входа не просит, адреса
 * в ответе готовые, подача в нём тоже есть.
 */
internal fun Api.fetchVisionPlayer(videoId: String, token: String?): JSONObject? {
    val visitorData = sessionVisitorData(videoId)

    val client = JSONObject()

    client.put("clientName", "VISIONOS")
    client.put("clientVersion", Api.VISION_VERSION)
    client.put("deviceMake", "Apple")
    client.put("deviceModel", "RealityDevice14,1")
    client.put("osName", "visionOS")
    client.put("osVersion", "1.0.2.21O209")
    client.put("userAgent", Api.VISION_USER_AGENT)
    client.put("hl", hl())
    client.put("gl", gl())

    if (visitorData.isNotEmpty()) {
        client.put("visitorData", visitorData)
    }

    val payload = JSONObject()

    payload.put("context", JSONObject().put("client", client))
    payload.put("videoId", videoId)
    payload.put("contentCheckOk", true)
    payload.put("racyCheckOk", true)

    if (!token.isNullOrEmpty()) {
        payload.put(
            "playbackContext",
            JSONObject().put(
                "reloadPlaybackContext",
                JSONObject().put(
                    "reloadPlaybackParams", JSONObject().put("token", token)
                )
            )
        )
    }

    val headers = HashMap<String, String>()

    headers["User-Agent"] = Api.VISION_USER_AGENT
    headers["X-YouTube-Client-Name"] = "101"
    headers["X-YouTube-Client-Version"] = Api.VISION_VERSION

    if (visitorData.isNotEmpty()) {
        headers["X-Goog-Visitor-Id"] = visitorData
    }

    return postPlayerPayload(payload, headers)
}

/**
 * POST на `/player` без ключа InnerTube и без нашего общего построителя
 * контекста: тело здесь собрано целиком вызывающим, потому что клиент
 * `/player` не совпадает ни с одним из тех, какими мы ходим за лентой.
 *
 * Куки не прикладываем: этим ходом идут IOS, ANDROID_VR и затравка
 * `visitorData` — клиенты, которым сеанс аккаунта не положен вовсе.
 * Именно они и превращали ответ в «подтвердите, что вы не бот».
 * Здесь это выходит само собой: у OkHttp своего хранилища куки нет,
 * и приложить их можно только руками.
 */
internal fun Api.postPlayerPayload(
    payload: JSONObject,
    headers: Map<String, String>
): JSONObject? {
    val builder = Http.request(
        "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    ) ?: return null

    builder.post(Http.jsonBody(Json.encode(payload)))
    builder.header("Content-Type", "application/json")
    builder.header("Accept", "application/json")
    builder.header("Accept-Language", hl())
    builder.header("Origin", "https://www.youtube.com")

    for (entry in headers) {
        builder.header(entry.key, entry.value)
    }

    val request: Request = builder.build()

    // Ответ не кешируем: адреса потоков подписаны на срок и к следующему
    // открытию ролика уже протухнут.
    val response = Http.send(request, 4 * 1024 * 1024, caching = false)

    if (!response.isSuccessful) {
        Log.d {
            "[YouTube/Плеер] /player: код ${response.statusCode}, " +
                (response.error?.message ?: "без ошибки")
        }

        return null
    }

    val parsed = Json.parse(response.body)

    captureSession(parsed)

    return parsed
}

/**
 * Адрес, каким нас видит Google по **свежему** ответу `/player`.
 *
 * Нужен, чтобы отличить отказ 403 из-за сменившегося выхода в сеть от всех
 * прочих: адрес раздачи подписан вместе с адресом просителя. Стоит один
 * небольшой запрос, поэтому зовётся только после отказа.
 */
fun Api.probeSeenIp(): String? {
    val videoId = synchronized(Api) { streamVideoId } ?: return null

    val json = fetchAndroidVrPlayer(videoId, null) ?: return null

    /**
     * Адрес лежит в служебной части ответа — там, где сервер описывает
     * условия воспроизведения. Имя поля у разных клиентов разное, поэтому
     * ищем по обоим.
     */
    return Json.findString("clientIpAddress", json, 20000)
        ?: Json.findString("visitorIp", json, 20000)
}

/**
 * Заставка объявленной трансляции — в ней час начала и слова сервера.
 */
private fun Api.offlineSlateIn(json: JSONObject?): JSONObject? =
    Json.findFirst("liveStreamOfflineSlateRenderer", json, 200000)

/**
 * Эфир объявлен, но ещё не начался.
 *
 * Сервер отвечает `LIVE_STREAM_OFFLINE` и кладёт рядом час начала.
 * Потоков при этом нет ни у одного клиента, и перебирать их бессмысленно:
 * десяток запросов и четыре секунды, после чего плеер всё равно покажет
 * пустоту.
 */
fun Api.isUpcomingBroadcast(json: JSONObject?): Boolean {
    if (json == null) {
        return false
    }

    return Json.string(Json.obj(json, "playabilityStatus"), "status", "") ==
        "LIVE_STREAM_OFFLINE"
}

/**
 * Что об ожидании говорит сам сервер.
 *
 * В заставке лежит готовая строка вроде «Трансляция начнётся 14 сентября
 * в 11:00» — на языке запроса и с правильным склонением. Когда она есть,
 * наша собственная надпись не нужна: своя считается из числа, а число
 * в ответе бывает не всегда.
 */
fun Api.offlineSlateTextIn(json: JSONObject?): String? {
    val slate = offlineSlateIn(json)

    val main = Json.renderedText(slate, "mainText")
    val under = Json.renderedText(slate, "subtitleText")

    if (!main.isNullOrEmpty() && !under.isNullOrEmpty()) {
        return "$main\n$under"
    }

    return if (!main.isNullOrEmpty()) main else under
}

/**
 * Час начала объявленной трансляции — в секундах эпохи; 0, если не назван.
 *
 * Лежит он у разных клиентов в разных местах: у одних числом в заставке,
 * у других строкой ISO 8601 в `microformat`. Разбираем оба.
 */
fun Api.scheduledStartIn(json: JSONObject?): Double {
    val slate = offlineSlateIn(json)

    Json.string(slate, "scheduledStartTime")?.toDoubleOrNull()?.let {
        if (it > 0) {
            return it
        }
    }

    val details = Json.obj(
        Json.obj(Json.obj(json, "microformat"), "playerMicroformatRenderer"),
        "liveBroadcastDetails"
    )

    val stamp = Json.string(details, "startTimestamp")

    if (stamp != null && stamp.length >= 19) {
        try {
            val shape = java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US
            )

            shape.timeZone = java.util.TimeZone.getTimeZone(
                "GMT" + stamp.substring(19)
            )

            val when0 = shape.parse(stamp.substring(0, 19))

            if (when0 != null) {
                return when0.time / 1000.0
            }
        } catch (error: Exception) {
            // Час не разобрался — обойдёмся словами сервера.
        }
    }

    return 0.0
}
