package ru.computershik.troubadour.net

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.model.VideoItem
import java.util.Locale

/**
 * Обращения к InnerTube — внутреннему API, которым пользуется сам YouTube.
 *
 * Порт запросов из `Config.cs`. Открытого API здесь нет и быть не может:
 * Data API v3 не отдаёт ни ленту «Главной», ни поток видео, ни комментарии
 * в том виде, в каком они нужны клиенту, а квоты делают его непригодным
 * для приложения. Поэтому запросы те же, что шлёт официальный клиент.
 *
 * **Клиент представляется по-разному в зависимости от того, что просит,**
 * и это не прихоть, а перенесённые находки UWP-версии:
 *
 *   TVHTML5  — всё, что требует входа: «Главная», подписки, история,
 *              уведомления, а также `/player`. Токен OAuth выдан именно
 *              этому клиенту, и другие с ним отвечают 400;
 *   WEB      — поиск и страница ролика, то есть всё, что работает
 *              и без входа;
 *   ANDROID  — запасной путь для `/player`, где нужны нешифрованные адреса
 *              потоков.
 *
 * Главное правило, выведенное в UWP-версии и записанное там прямо
 * в комментарии: **клиент в теле запроса обязан совпадать с клиентом
 * в заголовках.** Несовпадение — это и есть причина, по которой `/player`
 * отвечает «the page needs to be reloaded», а reel-запросы — 400.
 *
 * Разбит на несколько файлов ради читаемости: здесь общее — контексты,
 * заголовки, отправка и разбор; ленты, ролик, потоки, Shorts и аккаунт
 * лежат рядом расширениями этого же объекта.
 */
object Api {

    /**
     * Ключ InnerTube. Он не секретный: один и тот же у всех веб-клиентов
     * YouTube и лежит в разметке любой страницы. Взят из Config.cs.
     */
    const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

    /** Версии клиентов — те же, что в UWP-версии. */
    const val TV_VERSION = "7.20250209.19.00"
    const val TV_PLAYER_VERSION = "7.20260715.15.00"

    /**
     * Версия TV-клиента, какой она была до SABR.
     *
     * Подачу через SABR раскатывают по версиям клиента: свежие получают
     * дорожки без адресов вовсе, старые — обычные, с готовыми ссылками.
     * Второй заход этой версией стоит один запрос и делается лишь тогда,
     * когда свежая ответила одним SABR.
     */
    const val TV_LEGACY_VERSION = "7.20220918.10.00"

    const val WEB_VERSION = "2.20260430.08.00"
    const val ANDROID_VERSION = "19.09.37"

    /**
     * Версии клиентов, которыми ходят за Shorts, — из Config.cs.
     * Они там свои, отличные от общих: reel-запросы придирчивы к версии.
     */
    const val SHORTS_WEB_VERSION = "2.20260206.01.00"
    const val SHORTS_MWEB_VERSION = "2.20251222.01.00"
    const val SHORTS_ANDROID_VERSION = "20.10.38"

    const val TV_USER_AGENT =
        "Mozilla/5.0 (SMART-TV; LINUX; Tizen 5.0) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/5.0 TV Safari/537.36"

    const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    /**
     * Клиент, у которого спрашиваются потоки, — шлем Oculus Quest.
     *
     * Перенесено из `Video.xaml.cs` дословно: имя, версия, номер клиента
     * для заголовка и User-Agent. Менять здесь нечего — набор подобран так,
     * что сервер отдаёт готовые подписанные адреса.
     */
    const val ANDROID_VR_VERSION = "1.65.10"
    const val ANDROID_VR_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
            "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    /**
     * Клиент шлема Apple Vision Pro.
     *
     * Единственный из безымянных, кому раздача отдаёт видео **дальше первой
     * минуты**. Остальные — ANDROID_VR, ANDROID, IOS — обрываются между
     * 60-й и 70-й секундой: подача перестаёт давать куски, а готовые адреса
     * отвечают отказом. Замерено не нами: тем же упирается Opaline, и там
     * это записано с числами (59904 мс и 69888 мс, проверка 18 августа
     * 2026 года).
     *
     * В журналах оригинала это ровно те же строки: «Подача не дала кусок 12
     * (время 61.4 с)» — на трёх разных устройствах и роликах. Выглядело
     * как поломка перемотки, а на деле сессия просто кончалась.
     */
    const val VISION_VERSION = "1.02"
    const val VISION_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    /**
     * Клиент первичного `/player` — из `BuildPlayerPayload`. Там рядом
     * стоит пояснение: «Match get_url.py exactly: IOS client →
     * streamingData.hlsManifestUrl».
     */
    const val IOS_VERSION = "20.49.6"
    const val IOS_USER_AGENT =
        "com.google.ios.youtube/19.16.3 (iPhone16,2; U; CPU iOS 18_0 like Mac OS X)"

    /** Версия WEB-клиента, которым добывается свежий `visitorData`. */
    const val VISITOR_SEED_VERSION = "2.20260626.01.00"

    /** Запасной `visitorData` — из Config.cs, там он записан в процентном виде. */
    const val FALLBACK_VISITOR_DATA =
        "CgtjTS00dGRYTXhBOCif8OnOBjIoCgJQTBIiEh4SHAsMDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicgSA%3D%3D"

    /** Ленты держим в памяти пару минут — возврат назад не перезапрашивает их. */
    const val FEED_TTL = 120.0

    /**
     * Версия, которой представиться вместо обычной, — на время одного
     * запроса.
     *
     * Заведено ради одного случая: повтора `/player` под старой версией
     * TV-клиента. Версия обязана совпасть в теле и в заголовках, а собирает
     * их [post] внутри себя, поэтому подмена делается здесь, а не передаётся
     * насквозь через полдюжины вызовов.
     */
    @Volatile
    internal var versionOverride: String? = null

    /**
     * `visitorData` текущего сеанса. Выдаётся сервером и живёт
     * до перезапуска либо до того, как его пометят: тогда он сбрасывается
     * и добывается заново.
     */
    @Volatile
    internal var sessionVisitorData: String? = null

    /**
     * Признак учётной записи, выданный сервером. Приходит
     * в `responseContext` ответов вошедшего и служит привязкой
     * для PO-токена: у аккаунта доказательство привязано к нему,
     * а не к посетителю.
     */
    @Volatile
    internal var sessionDatasyncId: String? = null

    /**
     * Имя, под которым надо ходить за кусками текущего ролика.
     *
     * Меняется вслед за тем, чей ответ `/player` в итоге пригодился: адрес
     * подписан под клиента (`c=…` записан прямо в нём), и раздача сверяет,
     * тем ли клиентом за ним пришли.
     */
    @Volatile
    internal var streamUserAgent: String? = null

    /** Кто добыл нынешние адреса потоков — им же представляемся подаче. */
    @Volatile
    internal var streamClient: String? = null

    /**
     * Привязка PO-токена, годная для адресов текущего ролика, — либо null,
     * если клиент, добывший их, токеном не пользовался вовсе.
     *
     * Привязка у каждого клиента своя: WEB ходит с куками, и его
     * удостоверяет признак учётной записи; ANDROID_VR ходит гостем, и его
     * удостоверяет `visitorData` из того же запроса; IOS не просит токена
     * и не шлёт `visitorData` — ему приписывать нечего. Чужой `pot`
     * в адресе хуже, чем никакого: раздача сверяет его с тем сеансом,
     * которым добыта ссылка, и отвечает отказом 403.
     */
    @Volatile
    internal var streamBindingValue: String? = null

    /** `visitorData`, с которым уходил последний запрос ANDROID_VR. */
    @Volatile
    internal var androidVrBinding: String? = null

    /** Ролик, чьи адреса играются сейчас, — нужен для проверки выхода в сеть. */
    @Volatile
    internal var streamVideoId: String? = null

    // --- Язык -------------------------------------------------------------

    /**
     * Насколько пришлось поступиться локалью, чтобы сервер начал отвечать.
     *
     * 0 — своё и язык, и регион; 1 — свой язык, регион `US`; 2 — `en`/`US`.
     *
     * Ступени именно в таком порядке, и это не формальность. У пользователя
     * из Киргизии `browse` и поиск отвечали отказом при `hl=ru`, `gl=KG` —
     * то есть с языком, который YouTube знает наверняка. Значит, дело было
     * в регионе, а не в языке. Прежняя правка меняла разом и то и другое,
     * и выдача становилась английской — о чём он тут же и написал.
     *
     * Поэтому сначала уступаем только регион, и лишь если и это
     * не помогло — язык. Что именно спасло, видно по журналу: строка
     * со ступенью пишется при каждом переходе.
     *
     * Записка о переносе советует сделать лесенку **своей у каждой точки
     * входа и обратимой**: общая на весь сеанс однажды спустилась
     * до `hl=en gl=US` из-за неудач по посторонней причине, и дальше весь
     * сеанс приложение говорило по-английски. Здесь она осталась общей,
     * но корень той беды вылечен в другом месте — `onBehalfOfUser` больше
     * не уходит с неподписанными запросами, а именно его 401 и спускал
     * лесенку. Ступень при этом пишется в журнал, так что повторение
     * будет видно сразу.
     */
    @Volatile
    private var localeRelax = 0

    /**
     * Чем называть выбранный канал: `pageId` или парой «профиль||владелец».
     *
     * Сервер присылает обе приметы разом и не говорит, какую ждёт обратно.
     * Шлём первую; получив 401, переходим на вторую и дальше держимся её —
     * так же, как с уступками локали. Обратно не возвращаемся: сеанс один
     * и учётная запись одна, метаться незачем.
     */
    @Volatile
    private var useDatasyncMark = false

    private fun switchIdentityForm(): Boolean {
        synchronized(this) {
            if (useDatasyncMark) {
                return false
            }

            if (activeAccountPage.isNullOrEmpty() || activeAccountDatasync.isNullOrEmpty()) {
                return false
            }

            useDatasyncMark = true
        }

        Log.d {
            "[YouTube/Аккаунт] `pageId` сервер не принял — переходим " +
                "на пару «профиль||владелец»"
        }

        // Ответы, снятые до перехода, сняты чужой приметой.
        Http.dropMemoryCache()

        return true
    }

    /** Чем сейчас называем выбранный канал; пусто — каналом не назвались. */
    internal fun behalfMark(): String? {
        synchronized(this) {
            if (useDatasyncMark) {
                val pair = activeAccountDatasync

                if (!pair.isNullOrEmpty()) {
                    return pair
                }
            }
        }

        return activeAccountPage
    }

    /**
     * Открыт наружу ради поиска: тот на непонятную локаль отвечает
     * не отказом, а пустотой, и уступать ступень приходится ему самому.
     */
    internal fun relaxLocaleForSearch(): Boolean = relaxLocale()

    private fun relaxLocale(): Boolean {
        val wasHl = hl()
        val wasGl = gl()

        synchronized(this) {
            if (localeRelax >= 2) {
                return false
            }

            localeRelax++
        }

        Log.d {
            "[YouTube/API] Уступаем локаль (ступень $localeRelax): " +
                "было hl=$wasHl gl=$wasGl, стало hl=${hl()} gl=${gl()}"
        }

        return true
    }

    fun hl(): String {
        synchronized(this) {
            if (localeRelax >= 2) {
                return "en"
            }
        }

        // Выбранный в настройках язык главнее системного — как
        // `GetSavedLanguage` в UWP-версии.
        val chosen = Settings.language

        if (chosen.isNotEmpty()) {
            return chosen
        }

        /**
         * Здесь Android заметно проще iOS-версии.
         *
         * Там приложение, установленное мимо App Store, не видело общих
         * настроек системы вовсе: и `AppleLanguages`, и `AppleKeyboards`
         * читались как отсутствующие, отчего `preferredLanguages` отдавал
         * `en` на русском iPad. Пришлось читать `.GlobalPreferences.plist`
         * файлом и перекладывать ключи к себе — до `UIApplicationMain`,
         * потому что позже UIKit их уже не перечитывает.
         *
         * Здесь язык системы просто спрашивается.
         */
        return Locale.getDefault().language.ifEmpty { "en" }
    }

    fun gl(): String {
        synchronized(this) {
            if (localeRelax >= 1) {
                return "US"
            }
        }

        val country = Locale.getDefault().country

        return if (country.length == 2) country else "US"
    }

    // --- Контексты клиентов -----------------------------------------------

    /**
     * Описание клиента в теле запроса.
     *
     * Клиент здесь обязан совпадать с тем, что уйдёт в заголовках
     * `X-YouTube-Client-Name` и `-Version`: несовпадение — известная
     * причина отказов, о ней прямо сказано в комментарии UWP-версии.
     */
    fun clientContext(client: String): JSONObject {
        val context = JSONObject()

        context.put("clientName", client)
        context.put("hl", hl())
        context.put("gl", gl())

        when (client) {
            "TVHTML5" -> {
                context.put("clientVersion", versionOverride ?: TV_VERSION)
                context.put("platform", "TV")
                context.put("deviceMake", "Samsung")
                context.put("deviceModel", "SmartTV")
                context.put("osName", "Tizen")
                context.put("osVersion", "5.0")
            }

            "MWEB" -> {
                context.put("clientVersion", SHORTS_MWEB_VERSION)
                context.put("platform", "MOBILE")
            }

            "ANDROID" -> {
                context.put("clientVersion", ANDROID_VERSION)
                context.put("platform", "MOBILE")
                context.put("osName", "Android")
                context.put("osVersion", "11")
                context.put("androidSdkVersion", 30)
                context.put("deviceMake", "Google")
                context.put("deviceModel", "Pixel 5")
            }

            else -> context.put("clientVersion", WEB_VERSION)
        }

        return context
    }

    /** Номер клиента для заголовка: TV — 85, WEB — 1, ANDROID — 3. */
    fun clientNumber(client: String): String = when (client) {
        "TVHTML5" -> "85"
        "ANDROID" -> "3"
        "MWEB" -> "2"
        else -> "1"
    }

    fun clientVersion(client: String): String {
        versionOverride?.takeIf { it.isNotEmpty() }?.let { return it }

        return when (client) {
            "TVHTML5" -> TV_VERSION
            "ANDROID" -> ANDROID_VERSION
            "MWEB" -> SHORTS_MWEB_VERSION
            else -> WEB_VERSION
        }
    }

    fun userAgent(client: String): String = when (client) {
        "TVHTML5" -> TV_USER_AGENT
        "ANDROID" -> "com.google.android.youtube/$ANDROID_VERSION (Linux; U; Android 11) gzip"
        else -> WEB_USER_AGENT
    }

    // --- Запрос -----------------------------------------------------------

    private var localeLogged = false

    /**
     * POST к `youtubei/v1/<endpoint>`.
     *
     * @param authorize слать ли `Authorization`. Слать его нужно
     *   не всегда: с WEB-клиентом токен, выданный TV-клиенту, приводит
     *   к 400 — та же находка, что записана в UWP-версии.
     */
    fun post(
        endpoint: String,
        body: JSONObject,
        client: String,
        authorize: Boolean,
        ttl: Double = 0.0
    ): JSONObject? {
        val payload = JSONObject(body.toString())

        val context = JSONObject()
        context.put("client", clientContext(client))

        /**
         * От чьего имени говорим, если у учётной записи каналов несколько.
         *
         * У одной записи Google бывает и личный канал, и бренд-каналы,
         * и детский. Какой из них считать своим, сервер решает сам,
         * и решает не всегда так, как ждёт человек: у одного из наших он
         * выбрал канал YouTube Kids. Выбранный человеком канал передаётся
         * в `onBehalfOfUser` — так же поступает и сам TV-клиент, когда
         * в нём переключают профиль.
         *
         * **Только если запрос вообще кем-то подписан.**
         *
         * «Я от имени такого-то», сказанное без предъявления себя, — это
         * не просьба, а бессмыслица, и сервер отвечает на неё дословно:
         * `Request is missing required authentication credential`. Поиск
         * у нас уходит анонимным веб-клиентом, и стоило выбрать канал —
         * как поиск переставал работать целиком, отвечая 401 на всё.
         *
         * Хуже того, отказ этот тянул за собой ещё две беды: наш переход
         * на вторую примету канала (она тут ни при чём) и уступку локали
         * до `hl=en gl=US` на весь сеанс. Обе лечатся здесь же, в корне.
         */
        val webClient = client != "TVHTML5" && client != "ANDROID"

        var toldWho = if (webClient) !authorize && WebAuth.isSignedIn() else false

        if (authorize && Auth.accessToken().isNotEmpty()) {
            toldWho = true
        }

        val behalf = if (toldWho) behalfMark() else null

        if (!behalf.isNullOrEmpty()) {
            context.put("user", JSONObject().put("onBehalfOfUser", behalf))
        }

        payload.put("context", context)

        val url = "https://www.youtube.com/youtubei/v1/$endpoint" +
            "?key=$INNERTUBE_KEY&prettyPrint=false"

        val builder = Http.request(url) ?: return null

        builder.post(Http.jsonBody(Json.encode(payload)))
        builder.header("Content-Type", "application/json")
        builder.header("User-Agent", userAgent(client))
        builder.header("X-YouTube-Client-Name", clientNumber(client))
        builder.header("X-YouTube-Client-Version", clientVersion(client))

        /**
         * `Accept-Language` шлём, `Origin` — нет.
         *
         * Ровно этот набор у `PostTvBrowseAsync` в оригинале: там
         * заголовков четыре — токен, User-Agent, пара `X-YouTube-Client-*`
         * и язык. `Origin` был отсебятиной: браузерный заголовок в запросе
         * от клиента, который браузером не является.
         */
        builder.header("Accept-Language", "${hl()},${hl()};q=0.9")

        if (authorize) {
            val token = Auth.accessToken()

            if (token.isNotEmpty()) {
                builder.header("Authorization", "Bearer $token")
            }
        }

        /**
         * Веб-сессия подписывает то, что просит WEB-клиент.
         *
         * Порт `ApplyAuthenticatedHeaders`: куки уходят сами, а к ним
         * добавляется подпись `SAPISIDHASH`, номер аккаунта и origin.
         * TV-клиента это не касается — у него свой токен, и два способа
         * представиться разом сервер не принимает.
         */
        var signedWithSession = false

        if (webClient && !authorize && WebAuth.isSignedIn()) {
            val origin = "https://www.youtube.com"
            val signature = WebAuth.authorizationForOrigin(origin)

            if (signature != null) {
                builder.header("Authorization", signature)
                builder.header("X-Goog-AuthUser", "0")
                builder.header("Origin", origin)
                builder.header("X-Origin", origin)

                signedWithSession = true
            }
        }

        /**
         * Куки уходят только с тем запросом, который мы **сами** подписали.
         *
         * В оригинале хранилище куки было общим с UIWebView, и
         * NSURLConnection по умолчанию прикладывал их ко всему, что идёт
         * на youtube.com. После входа это вышло боком: запрос ANDROID_VR —
         * клиента заведомо анонимного, ходящего с одним `visitorData`, —
         * начал уносить с собой полный сеанс аккаунта на две с половиной
         * тысячи байт, но без подписи, которая к такому сеансу полагается.
         * Для Google это запрос, наполовину вошедший, и отвечает он ровно
         * тем, что мы и видели: «Войдите в аккаунт, чтобы подтвердить,
         * что вы не бот».
         *
         * Здесь та же развилка решается проще и надёжнее: у OkHttp своего
         * хранилища куки нет вовсе (`CookieJar.NO_COOKIES` по умолчанию),
         * и куки WebView сами собой к запросам не прикладываются. Значит,
         * их надо приложить руками — и ровно туда, куда нужно.
         */
        if (signedWithSession) {
            WebAuth.cookieHeader("https://www.youtube.com")?.let {
                builder.header("Cookie", it)
            }
        }

        /**
         * Локаль — один раз за сеанс, зато всегда.
         *
         * По ней разбирались две поломки подряд, и обе начинались с догадки
         * «а какой у него язык?» — при том что ответ есть у приложения
         * с первой секунды.
         */
        synchronized(this) {
            if (!localeLogged) {
                localeLogged = true

                Log.d { "[YouTube/API] Локаль запросов: hl=${hl()}, gl=${gl()}" }
            }
        }

        /**
         * Строка перед отправкой, а не только после отказа: без неё
         * по журналу не отличить «запрос ушёл и не вернулся» от «до запроса
         * не дошло».
         *
         * Чем представились — частью строки. Раньше отмечалась только
         * веб-сессия, и заход веб-клиента с токеном телевизора выглядел
         * в журнале точно так же, как заход анонимный: `→ next (WEB)`.
         */
        val how = when {
            webClient && !authorize && WebAuth.isSignedIn() -> ", веб-сессия"
            authorize && Auth.accessToken().isNotEmpty() -> ", токен"
            else -> ""
        }

        /**
         * У `browse` дописываем, за чем именно пошли.
         *
         * Этим узлом берутся и «Главная», и подписки, и история,
         * и плейлисты, и каналы — в журнале они выглядели одинаково,
         * и четыре подряд `→ browse` было не разобрать.
         */
        var what = ""

        if (endpoint == "browse") {
            var browseId = Json.text(payload, "browseId")

            if (browseId.isNullOrEmpty() && payload.has("continuation")) {
                browseId = "продолжение"
            }

            if (!browseId.isNullOrEmpty()) {
                what = " $browseId"
            }
        }

        Log.d { "[YouTube/API] → $endpoint$what ($client$how)" }

        // Потолок на тело: ответ «Главной» — это несколько мегабайт JSON,
        // и без ограничения одна сорвавшаяся страница могла бы вычерпать
        // память.
        val request: Request = builder.build()

        val response = if (ttl > 0) {
            Http.sendCached(request, 12 * 1024 * 1024, ttl)
        } else {
            Http.send(request, 12 * 1024 * 1024)
        }

        if (!response.isSuccessful) {
            val error = response.error

            Log.d {
                "[YouTube/API] $endpoint ($client): код ${response.statusCode}, " +
                    "${error?.javaClass?.simpleName ?: "без ошибки"} — ${error?.message ?: ""}"
            }

            /**
             * Слова сервера — тоже в журнал.
             *
             * Тело отказа прежде выбрасывалось, и от 401 оставалось одно
             * число. А Google в этом теле пишет ровно то, чего не хватило:
             * «Request had invalid authentication credentials» — это одно,
             * а «Request is missing required authentication credential» —
             * совсем другое, и лечатся они по-разному.
             */
            val said = response.text

            if (said.isNotEmpty()) {
                Log.d {
                    "[YouTube/API] $endpoint ответил: " +
                        if (said.length > 500) said.substring(0, 500) else said
                }
            }

            /**
             * «Неверный запрос» при живой связи — повод усомниться
             * в локали.
             *
             * Запрос собран нами одинаково для всех, а отказ приходит
             * не всем: у пользователя из Киргизии `browse` отвечал 400,
             * а поиск — пустотой, при том что `next` тем же клиентом
             * работал. Ни списка языков, ни списка регионов, которые
             * InnerTube принимает, нигде нет, поэтому проверяем опытом:
             * уступаем ступень локали и повторяем.
             */
            if (response.statusCode == 400 && relaxLocale()) {
                Log.d { "[YouTube/API] Повторяем $endpoint ($client)" }

                return post(endpoint, body, client, authorize, ttl)
            }

            /**
             * 401 при выбранном канале — сервер не принял того, чем мы
             * этот канал назвали. Пробуем вторую примету и дальше
             * держимся того, что подошло.
             */
            if (response.statusCode == 401 && !behalf.isNullOrEmpty() && switchIdentityForm()) {
                Log.d {
                    "[YouTube/API] Повторяем $endpoint ($client) с другой приметой канала"
                }

                return post(endpoint, body, client, authorize, ttl)
            }

            return null
        }

        Log.d {
            "[YouTube/API] ← $endpoint ($client): ${(response.body?.size ?: 0) / 1024} КБ"
        }

        val parsed = Json.parse(response.body)

        // Метки сеанса — `visitorData` и признак учётной записи — приходят
        // в любом ответе; ловим их здесь, а не в каждом месте по отдельности.
        captureSession(parsed)

        return parsed
    }

    // --- Продолжение ------------------------------------------------------

    /**
     * Токен следующей страницы.
     *
     * Лежит он в `continuationItemRenderer` в конце списка. Ищем по всему
     * дереву — так же поступала UWP-версия (`FindContinuationTokenInObject`),
     * потому что глубина, на которой он окажется, зависит от рендерера.
     *
     * Потолок обхода общий, а не семь тысяч узлов, и это исправление.
     * Токен стоит **в конце** списка — то есть дальше всего от начала
     * обхода, — а ответ истории на пару сотен килобайт семи тысяч узлов
     * не укладывается. Обход упирался в потолок, не дойдя до конца, и список
     * выглядел исчерпанным: первая пачка приезжала, продолжения не было
     * никогда. Ровно та же беда уже случалась с лентой подписок
     * и с комментариями — там потолок подняли, здесь забыли.
     */
    fun continuationIn(tree: Any?): String? {
        val item = Json.findFirst("continuationItemRenderer", tree, 200000)

        if (item != null) {
            val endpoint = Json.obj(item, "continuationEndpoint")
            val command = Json.obj(endpoint, "continuationCommand")

            Json.text(command, "token")?.let { return it }
        }

        // Старая форма, которую до сих пор отдаёт TV-клиент.
        val next = Json.findFirst("nextContinuationData", tree, 200000)

        Json.text(next, "continuation")?.let { return it }

        /**
         * Третья форма — `reloadContinuationData`.
         *
         * Именно её TV-клиент кладёт в панель комментариев, и ни одна
         * из двух прежних её не находила: комментарии у вертикальных
         * роликов открывались пустым списком, хотя ответ был исправный.
         */
        val reload = Json.findFirst("reloadContinuationData", tree, 200000)

        return Json.text(reload, "continuation")
    }

    /**
     * Собирает имена всех рендереров и view-model в ответе.
     *
     * Нужно ровно тогда, когда ответ полон, а разобрать из него нечего:
     * значит, полка в нём незнакомая. Имя полки — это и есть ответ
     * на вопрос, что дописать в разбор, и добывается он отсюда, а не
     * перехватом трафика.
     */
    private fun collectRendererNames(node: Any?, counts: HashMap<String, Int>, depth: Int) {
        if (depth > 40) {
            return
        }

        if (node is JSONObject) {
            val keys = node.keys()

            while (keys.hasNext()) {
                val key = keys.next()

                if (key.endsWith("Renderer") || key.endsWith("ViewModel")) {
                    counts[key] = (counts[key] ?: 0) + 1
                }

                collectRendererNames(node.opt(key), counts, depth + 1)
            }

            return
        }

        if (node is JSONArray) {
            for (index in 0 until node.length()) {
                collectRendererNames(node.opt(index), counts, depth + 1)
            }
        }
    }

    /** Лента: список карточек и токен следующей страницы. */
    class Feed(val items: List<VideoItem>, val continuation: String?)

    /** Общий вид ответа со списком роликов. */
    fun feedFrom(json: JSONObject?): Feed? {
        if (json == null) {
            return null
        }

        val items = VideoItem.parseFrom(json)

        if (items.isEmpty()) {
            describeEmpty(json)
        }

        return Feed(items, continuationIn(json))
    }

    /**
     * Ответ есть, роликов нет — почти всегда это стена согласия либо
     * региональное ограничение. Одного этого мало: снаружи «пустая
     * выдача» и «сервер отказал» выглядят одинаково, а лечатся по-разному.
     * Поэтому печатаем и то, из чего ответ состоит.
     *
     * Сам ответ в журнал не кладём: в `responseContext` едут метки сеанса,
     * а журналом делятся.
     */
    private fun describeEmpty(json: JSONObject) {
        if (!ru.computershik.troubadour.BuildConfig.LOG) {
            return
        }

        val note = StringBuilder()
        val keys = json.keys()

        while (keys.hasNext()) {
            if (note.isNotEmpty()) {
                note.append(", ")
            }

            note.append(keys.next())
        }

        Json.obj(json, "error")?.let { error ->
            note.append(
                "; ошибка ${Json.int(error, "code")} " +
                    "${Json.string(error, "status", "?")} " +
                    "(${Json.string(error, "message", "?")})"
            )
        }

        for (alert in Json.findAll("alertRenderer", json, 2000)) {
            val text = Json.renderedText(alert, "text")

            if (!text.isNullOrEmpty()) {
                note.append("; окно: $text")
            }
        }

        Log.d { "[YouTube/API] Ответ разобран, но роликов в нём нет — поля: $note" }

        /**
         * И перечень полок — если ответ не пуст.
         *
         * «Полей много, роликов нет» значит, что в ответе лежит что-то,
         * чего разбор не знает. Печатаем имена рендереров по убыванию
         * числа — самое частое и есть то, что мы пропускаем.
         */
        if (Json.obj(json, "contents") == null) {
            return
        }

        val counts = HashMap<String, Int>()

        collectRendererNames(json, counts, 0)

        val shelves = counts.entries
            .sortedByDescending { it.value }
            .take(12)
            .joinToString(", ") { "${it.key}×${it.value}" }

        Log.d { "[YouTube/API] Что в ответе лежит: $shelves" }
    }

    /** Продолжение любого списка по токену. */
    fun browseContinuation(continuation: String?): Feed? {
        if (continuation.isNullOrEmpty()) {
            return null
        }

        val body = JSONObject().put("continuation", continuation)

        /**
         * Веб-клиент без токена — и это не упущение, а область применения.
         *
         * Правило «продолжение ведёт тот, кто начал» здесь соблюдено тем,
         * что этим методом листаются только те списки, которые начал WEB:
         * канал и подборка. У «Главной», подписок и истории продолжение
         * своё, в их же методах, и уходит оно тем клиентом, что отдал
         * первую страницу.
         *
         * Нарушить это правило — значит получить подмену посреди ленты:
         * продолжение приходит от другого клиента и содержит уже не ваши
         * ролики. Снаружи это выглядит как «лента после пары страниц
         * становится случайной».
         */
        return feedFrom(post("browse", body, "WEB", false, 0.0))
    }
}
