package ru.computershik.troubadour.net

import android.content.Context
import org.json.JSONObject
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.model.VideoItem
import ru.computershik.troubadour.ui.main
import java.util.Locale

/** Один канал учётной записи. */
class Account {
    var name: String = ""
    var handle: String? = null
    var avatar: String? = null
    var channelId: String? = null

    /**
     * Чем этот канал называть серверу. Сервер присылает личность канала
     * двумя способами разом — [page] и парой «профиль||владелец»
     * ([datasync]), — и какой из них ждёт обратно, по ответу не видно.
     */
    var page: String? = null
    var datasync: String? = null

    /** Тот, который сервер считает основным. */
    var primary: Boolean = false
}

/** Канал в списке подписок. */
class SubscribedChannel(
    val channelId: String,
    val title: String,
    val thumbnail: String?
)

/** История с разбивкой по дням. */
class HistoryPage(
    val items: List<VideoItem>,
    val continuation: String?,
    val groups: List<HistoryGroup>
)

class HistoryGroup(val title: String?, val items: List<VideoItem>)

/** Канал: шапка, разделы и первая страница роликов. */
class ChannelPage {
    var title: String? = null
    var handle: String? = null
    var subscribers: String? = null
    var avatar: String? = null
    var banner: String? = null
    var description: String? = null

    var items: List<VideoItem> = emptyList()
    var continuation: String? = null

    /**
     * Разделы канала. Метки к ним непрозрачные — вычислить их нельзя,
     * только взять из перечня, который сервер прислал вместе со страницей.
     */
    var sections: List<ChannelSection> = emptyList()

    var subscribed: Boolean? = null
    var notifications: Int = Notifications.UNKNOWN
}

class ChannelSection(val title: String, val params: String?)

/** Подборка — плейлист или микс. */
class PlaylistPage {
    var title: String? = null
    var channelTitle: String? = null
    var subtitle: String? = null
    var thumbnail: String? = null

    var items: List<VideoItem> = emptyList()
    var continuation: String? = null
}

private const val STORE = "troubadour"

/** Имя настройки: выбранный канал переживает перезапуск. */
private const val ACTIVE_PAGE_KEY = "YTActiveAccountPage"
private const val ACTIVE_DATASYNC_KEY = "YTActiveAccountDatasync"

private val store
    get() = App.require().getSharedPreferences(STORE, Context.MODE_PRIVATE)

/** Какой канал выбран человеком; null — какой выберет сервер. */
val Api.activeAccountPage: String?
    get() = store.getString(ACTIVE_PAGE_KEY, null)

/** Вторая примета выбранного канала; null, если её не сохраняли. */
val Api.activeAccountDatasync: String?
    get() = store.getString(ACTIVE_DATASYNC_KEY, null)

/**
 * Примета того, от чьего имени мы сейчас говорим.
 *
 * Заведена для тех, кто по оповещению о смене входа решает, стоит ли
 * перечитывать набранное. Сверяться с одним лишь «вошли ли» мало: при
 * смене канала вход как был, так и остался, — и лента, подобранная прежнему
 * каналу, оставалась висеть у нового. Строка меняется от всякой смены:
 * и входа с выходом, и канала. Сравнивать её можно только саму с собой —
 * что внутри, значения не имеет.
 */
fun Api.identityMark(): String {
    if (!Auth.isSignedIn()) {
        return "гость"
    }

    val page = activeAccountPage

    return if (!page.isNullOrEmpty()) page else "по умолчанию"
}

fun Api.setActiveAccountPage(page: String?, datasync: String? = null) {
    val editor = store.edit()

    if (!page.isNullOrEmpty()) {
        editor.putString(ACTIVE_PAGE_KEY, page)
    } else {
        editor.remove(ACTIVE_PAGE_KEY)
    }

    if (!datasync.isNullOrEmpty()) {
        editor.putString(ACTIVE_DATASYNC_KEY, datasync)
    } else {
        editor.remove(ACTIVE_DATASYNC_KEY)
    }

    editor.apply()

    forgetAccounts()

    Http.dropMemoryCache()

    Log.d {
        "[YouTube/Аккаунт] Выбран канал ${if (!page.isNullOrEmpty()) page else "по умолчанию"}"
    }

    main { Notify.post(Notify.ACCOUNT) }
}

private fun Api.pageIdIn(item: JSONObject): String? {
    Json.findString("pageId", item, 20000)?.let { return it }
    Json.findString("personaId", item, 20000)?.let { return it }

    return Json.findString("obfuscatedGaiaId", item, 20000)
}

private fun Api.accountsIn(json: JSONObject?): List<Account> {
    val accounts = ArrayList<Account>()

    for (item in Json.findAll("accountItem", json, 4000)) {
        val name = Json.renderedText(item, "accountName")

        if (name.isNullOrEmpty()) {
            continue
        }

        val account = Account()

        account.name = name
        account.handle = Json.renderedText(item, "channelHandle")
            ?: Json.renderedText(item, "accountByline")
        account.avatar = Json.thumbnail(item, "accountPhoto", 88)

        account.page = pageIdIn(item)
        account.datasync = Json.findString("datasyncIdToken", item, 20000)

        if (account.page != null) {
            Log.d {
                "[YouTube/Аккаунт] «$name»: личность ${account.page}" +
                    if (!account.datasync.isNullOrEmpty()) ", пара есть" else ""
            }
        } else {
            Log.d {
                "[YouTube/Аккаунт] У канала «$name» метки владельца нет — " +
                    "переключиться на него не выйдет"
            }
        }

        val browse = Json.findFirst("browseEndpoint", item, 600)
        val channelId = Json.text(browse, "browseId")

        if (channelId != null && channelId.startsWith("UC")) {
            account.channelId = channelId
        }

        account.primary = Json.obj(item, "accountByline") != null ||
            Json.bool(item, "isSelected")

        accounts.add(account)
    }

    return accounts
}

/**
 * `accountReadMask` — то самое поле, без которого сервер отдаёт
 * **только владельца записи**, даже если каналов у человека пять.
 *
 * Ответ при этом успешный, разбор проходит — просто остальных нет.
 * На этом была построена целая ложная теория про то, что токен телевизора
 * «видит» лишь один канал, и написан ненужный запасной путь через три
 * клиента. Правду показал перехват запроса настоящего телевизора.
 */
private fun accountReadMask(): JSONObject {
    val mask = JSONObject()

    for (key in listOf(
        "returnOwner", "returnBrandAccounts",
        "returnPersonaAccounts", "returnFamilyChildAccounts"
    )) {
        mask.put(key, true)
    }

    mask.put("returnFamilyMembersAccounts", false)

    return JSONObject().put("accountReadMask", mask)
}

private var accountsCache: List<Account>? = null
private var accountsCacheFor: String? = null

internal fun Api.forgetAccounts() {
    synchronized(Api) {
        accountsCache = null
        accountsCacheFor = null
    }
}

/**
 * Все каналы учётной записи — порт `ParseAccountInfoFromAccountsList`,
 * только там берётся один, а здесь весь список.
 */
fun Api.accountsList(): List<Account> {
    if (!Auth.isSignedIn()) {
        return emptyList()
    }

    synchronized(Api) {
        val mark = identityMark()

        accountsCache?.let {
            if (accountsCacheFor == mark) {
                return it
            }
        }

        val json = post("account/accounts_list", accountReadMask(), "TVHTML5", true, 900.0)

        val accounts = accountsIn(json)

        Log.d { "[YouTube/Аккаунт] Каналов в записи: ${accounts.size}" }

        if (accounts.isNotEmpty()) {
            accountsCache = accounts
            accountsCacheFor = mark
        }

        return accounts
    }
}

fun Api.currentAccount(): Account? {
    val accounts = accountsList()

    if (accounts.isEmpty()) {
        return null
    }

    val chosen = activeAccountPage

    if (!chosen.isNullOrEmpty()) {
        accounts.firstOrNull { it.page == chosen }?.let { return it }
    }

    return accounts.firstOrNull { it.primary } ?: accounts[0]
}

/** Кружок аккаунта для нижней панели; null, если не вошли. */
fun Api.accountAvatarUrl(): String? {
    if (!Auth.isSignedIn()) {
        return null
    }

    return currentAccount()?.avatar
}

/** Профиль вошедшего: имя, собачка, кружок, канал. */
fun Api.accountProfile(): Account? {
    if (!Auth.isSignedIn()) {
        return null
    }

    return currentAccount()
}

/** Подписки: каналы, на которые подписан вошедший. */
fun Api.subscriptions(): List<SubscribedChannel>? {
    if (!Auth.isSignedIn()) {
        return null
    }

    val json = post(
        "browse", JSONObject().put("browseId", "FEchannels"),
        "TVHTML5", true, 900.0
    ) ?: return null

    val channels = ArrayList<SubscribedChannel>()
    val seen = HashSet<String>()

    for (tile in Json.findAll("tileRenderer", json, 200000)) {
        if (Json.text(tile, "contentType") != "TILE_CONTENT_TYPE_CHANNEL") {
            continue
        }

        val channelId = Json.text(tile, "contentId") ?: continue

        if (!seen.add(channelId)) {
            continue
        }

        val metadata = Json.obj(Json.obj(tile, "metadata"), "tileMetadataRenderer")
        val header = Json.obj(Json.obj(tile, "header"), "tileHeaderRenderer")

        channels.add(
            SubscribedChannel(
                channelId,
                Json.renderedText(metadata, "title") ?: "",
                Json.thumbnail(header, "thumbnail", 88)
            )
        )
    }

    Log.d { "[YouTube/Подписки] каналов найдено: ${channels.size}" }

    return channels
}

/** Лента подписок. */
fun Api.subscriptionsFeed(continuation: String?): Api.Feed? {
    if (!Auth.isSignedIn()) {
        return null
    }

    val body = JSONObject()

    if (!continuation.isNullOrEmpty()) {
        body.put("continuation", continuation)
    } else {
        body.put("browseId", "FEsubscriptions")
    }

    return feedFrom(post("browse", body, "TVHTML5", true, 0.0))
}

/** История просмотра — плоским списком. */
fun Api.history(continuation: String?): Api.Feed? {
    if (!Auth.isSignedIn()) {
        return null
    }

    val body = JSONObject()

    if (!continuation.isNullOrEmpty()) {
        body.put("continuation", continuation)
    } else {
        body.put("browseId", "FEhistory")
    }

    return feedFrom(post("browse", body, "TVHTML5", true, 0.0))
}

/**
 * Заголовок дня в истории — «Сегодня», «На прошлой неделе».
 *
 * Их ставит сам сервер, и их может не быть — тогда всё приходит одной
 * безымянной пачкой. Отсеиваем те заголовки, что днями не являются:
 * названия самого раздела и подполок.
 */
private fun Api.historyDayTitleIn(node: JSONObject): String? {
    var title = Json.renderedText(node, "title")

    if (title.isNullOrEmpty()) {
        for (name in listOf(
            "itemSectionHeaderRenderer", "shelfHeaderRenderer",
            "headerRenderer", "richShelfHeaderRenderer"
        )) {
            val header = Json.findFirst(name, node, 400)

            title = Json.renderedText(header, "title")

            if (!title.isNullOrEmpty()) {
                break
            }
        }
    }

    if (title.isNullOrEmpty()) {
        return null
    }

    val notDays = listOf(
        "история", "history", "видео", "videos", "shorts",
        "музыка", "music", "поиск", "search"
    )

    val lowered = title.lowercase(Locale.getDefault())

    if (notDays.any { it == lowered }) {
        return null
    }

    return title
}

/** История с разбивкой по дням. */
fun Api.historyPage(continuation: String?): HistoryPage? {
    val web = WebAuth.isSignedIn()

    if (!web && !Auth.isSignedIn()) {
        return null
    }

    val body = JSONObject()

    if (!continuation.isNullOrEmpty()) {
        body.put("continuation", continuation)
    } else {
        body.put("browseId", "FEhistory")
    }

    var json = if (web) post("browse", body, "WEB", false, 0.0) else null

    if (json == null) {
        json = post("browse", body, "TVHTML5", true, 0.0)
    }

    if (json == null) {
        return null
    }

    val feed = feedFrom(json) ?: return null

    val groups = ArrayList<HistoryGroup>()
    val seen = HashSet<String>()

    val names = setOf("shelfRenderer", "richShelfRenderer", "itemSectionRenderer")

    for (hit in Json.findAllOfAny(names, json, 200000)) {
        val title = historyDayTitleIn(hit.node)

        if (title.isNullOrEmpty()) {
            continue
        }

        val items = ArrayList<VideoItem>()

        for (item in VideoItem.parseFrom(hit.node)) {
            val key = item.videoId?.takeIf { it.isNotEmpty() } ?: item.title

            if (key.isEmpty() || !seen.add(key)) {
                continue
            }

            items.add(item)
        }

        if (items.isEmpty()) {
            continue
        }

        groups.add(HistoryGroup(title, items))
    }

    if (groups.isEmpty() && feed.items.isNotEmpty()) {
        groups.add(HistoryGroup(null, feed.items))
    }

    Log.d {
        "[YouTube/История] дней ${groups.size}, роликов ${feed.items.size}, " +
            "продолжение ${if (feed.continuation != null) "есть" else "нет"}"
    }

    return HistoryPage(feed.items, feed.continuation, groups)
}

/**
 * Свои плейлисты — то, что показано полосой на вкладке «Моё».
 *
 * Запрос тот же, что в `GetMyPlaylistsViaInnertubeAsync`:
 * `FEplaylist_aggregation` у TV-клиента.
 */
fun Api.myPlaylists(): List<VideoItem>? {
    if (!Auth.isSignedIn()) {
        return null
    }

    val json = post(
        "browse", JSONObject().put("browseId", "FEplaylist_aggregation"),
        "TVHTML5", true, 900.0
    )

    return playlistsIn(json)
}

/**
 * Подборки в любом ответе — порт `ParsePlaylistCards`.
 *
 * В [VideoItem.duration] лежит пометка, которую прислал сервер
 * («Микс», «50 видео»).
 */
fun Api.playlistsIn(json: Any?): List<VideoItem> {
    if (json == null) {
        return emptyList()
    }

    val playlists = ArrayList<VideoItem>()
    val seen = HashSet<String>()

    val names = setOf(
        "tileRenderer", "playlistRenderer", "gridPlaylistRenderer", "lockupViewModel"
    )

    for (hit in Json.findAllOfAny(names, json, 200000)) {
        val node = hit.node
        val isTile = hit.name == "tileRenderer"

        var playlistId = if (isTile) {
            Json.text(node, "contentId")
        } else {
            Json.text(node, "playlistId")
        }

        if (playlistId == null) {
            playlistId = Json.text(node, "contentId")
        }

        if (playlistId != null && playlistId.startsWith("VL")) {
            playlistId = playlistId.substring(2)
        }

        val contentType = Json.text(node, "contentType") ?: ""

        val looksLikePlaylist = playlistId != null && (
            playlistId.startsWith("PL") || playlistId.startsWith("RD") ||
                playlistId.startsWith("UL") || playlistId.startsWith("OLA") ||
                contentType.contains("PLAYLIST")
            )

        if (playlistId.isNullOrEmpty() || !looksLikePlaylist || !seen.add(playlistId)) {
            continue
        }

        val item = VideoItem()

        item.playlistId = playlistId

        if (isTile) {
            val metadata = Json.obj(Json.obj(node, "metadata"), "tileMetadataRenderer")

            item.title = Json.renderedText(metadata, "title") ?: ""

            val lines = Json.array(metadata, "lines")
            val parts = ArrayList<String>()

            if (lines != null) {
                for (index in 0 until lines.length()) {
                    val text = VideoItem.tileLineText(Json.objectAt(lines, index), 0)

                    if (!text.isNullOrEmpty()) {
                        parts.add(text)
                    }
                }
            }

            item.channelTitle = parts.joinToString(" • ")

            val header = Json.obj(Json.obj(node, "header"), "tileHeaderRenderer")
            val badge = Json.findFirst("thumbnailOverlayTimeStatusRenderer", header, 400)

            item.duration = Json.renderedText(badge, "text")
            item.thumbnail = Json.thumbnail(header, "thumbnail", 480)
        } else {
            item.title = Json.renderedText(node, "title") ?: ""
            item.duration = Json.renderedText(node, "videoCountShortText")
                ?: Json.renderedText(node, "videoCountText")

            item.thumbnail = Json.thumbnail(node, "thumbnail", 480)

            if (item.thumbnail == null) {
                val image = Json.findFirst("image", node, 400)

                item.thumbnail = Json.thumbnail(image, "sources", 480)
            }
        }

        if (item.title.isEmpty()) {
            item.title = loc("Плейлист")
        }

        playlists.add(item)
    }

    Log.d { "[YouTube/Плейлисты] подборок: ${playlists.size}" }

    return playlists
}

// --- Канал ----------------------------------------------------------------

/**
 * Канал, раздел задан меткой `params` из ответа самого канала.
 *
 * Разделы у каналов разные, и метки к ним непрозрачные — вычислить их
 * нельзя, только взять из перечня, который сервер прислал вместе
 * со страницей ([ChannelPage.sections]).
 */
fun Api.channel(channelId: String?, params: String?): ChannelPage? {
    if (channelId.isNullOrEmpty()) {
        return null
    }

    val body = JSONObject().put("browseId", channelId)

    if (!params.isNullOrEmpty()) {
        body.put("params", params)
    }

    return channelFrom(body)
}

/** Канал, раздел задан именем: `videos`, `shorts`, `playlists`, … */
fun Api.channelTab(channelId: String?, tab: String?): ChannelPage? {
    if (channelId.isNullOrEmpty()) {
        return null
    }

    val body = JSONObject().put("browseId", channelId)

    when (tab) {
        "videos" -> body.put("params", "EgZ2aWRlb3PyBgQKAjoA")
        "shorts" -> body.put("params", "EgZzaG9ydHPyBgUKA5oBAA%3D%3D")
        "playlists" -> body.put("params", "EglwbGF5bGlzdHPyBgQKAkIA")
        "featured" -> body.put("params", "EghmZWF0dXJlZPIGBAoCMgA%3D")
        "streams" -> body.put("params", "EgdzdHJlYW1z8gYECgJ6AA%3D%3D")
        "posts" -> body.put("params", "EgVwb3N0c_IGBAoCSgA%3D")
    }

    return channelFrom(body)
}

private fun Api.imageIn(tree: Any?, key: String, minWidth: Int): String? {
    val node = Json.findFirst(key, tree, 20000) ?: return null

    return Json.thumbnail(node, "thumbnails", minWidth)
        ?: Json.thumbnail(node, "sources", minWidth)
}

private fun Api.channelFrom(body: JSONObject): ChannelPage? {
    val signedIn = WebAuth.isSignedIn()

    val json = post("browse", body, "WEB", false, if (signedIn) 0.0 else Api.FEED_TTL)
        ?: return null

    val feed = feedFrom(json)

    val result = ChannelPage()

    result.items = feed?.items ?: emptyList()
    result.continuation = feed?.continuation

    val top = Json.obj(json, "header")

    val header = Json.obj(top, "c4TabbedHeaderRenderer")
        ?: Json.obj(top, "pageHeaderRenderer")
        ?: Json.findFirst("c4TabbedHeaderRenderer", json, 4000)
        ?: Json.findFirst("pageHeaderRenderer", json, 4000)

    result.title = Json.renderedText(header, "title") ?: Json.text(header, "pageTitle")
    result.handle = Json.renderedText(header, "channelHandleText")
    result.subscribers = Json.renderedText(header, "subscriberCountText")

    val metadata = Json.obj(Json.obj(json, "metadata"), "channelMetadataRenderer")
        ?: Json.findFirst("channelMetadataRenderer", json, 4000)

    var avatar = Json.thumbnail(metadata, "avatar", 176)

    if (avatar == null) {
        val decorated = Json.findFirst("decoratedAvatarViewModel", json, 8000)
        val inner = Json.findFirst("avatarViewModel", decorated, 600)

        avatar = Json.thumbnail(Json.obj(inner, "image"), "sources", 176)
    }

    if (avatar == null) {
        avatar = imageIn(json, "avatar", 176)
    }

    result.avatar = avatar

    val pageHeader = Json.findFirst("pageHeaderViewModel", json, 4000)

    /**
     * Подложка канала ищется по имени своего вида, а не по пути.
     *
     * Путь `pageHeaderViewModel.banner.imageBannerViewModel` держался
     * на том, что шапка найдётся первой. На живом ответе она не нашлась
     * вовсе — обход упирался в потолок в четыре тысячи узлов, — хотя
     * сама подложка в ответе была: проверка показала и `banner`,
     * и `imageBannerViewModel` среди ключей. Оттого баннера у каналов
     * не бывало никогда.
     *
     * Имя вида однозначно, и искать по нему надёжнее пути: у ответа
     * меняется обрамление, а имена держатся.
     */
    var bannerNode = Json.obj(Json.obj(pageHeader, "banner"), "imageBannerViewModel")

    if (bannerNode == null) {
        bannerNode = Json.findFirst("imageBannerViewModel", json, 200000)
    }

    result.banner = Json.thumbnail(Json.obj(bannerNode, "image"), "sources", 1024)
        ?: imageIn(json, "banner", 1024)

    Log.d {
        "[YouTube/Канал] Шапка: кружок ${if (avatar != null) "есть" else "нет"}, " +
            "подложка ${if (result.banner != null) "есть" else "нет"}"
    }


    result.description = Json.text(metadata, "description")

    if (result.title == null) {
        result.title = Json.text(metadata, "title")
    }

    if (result.handle == null) {
        val vanity = Json.text(metadata, "vanityChannelUrl")
        val mark = vanity?.indexOf("/@") ?: -1

        if (vanity != null && mark >= 0) {
            var tail = vanity.substring(mark + 2)

            tail = try {
                java.net.URLDecoder.decode(tail, "UTF-8")
            } catch (error: Exception) {
                tail
            }

            result.handle = "@$tail"
        }
    }

    if (result.subscribers == null) {
        val rows = Json.array(
            Json.findFirst("contentMetadataViewModel", pageHeader, 2000), "metadataRows"
        )

        if (rows != null) {
            for (rowIndex in 0 until rows.length()) {
                val parts = Json.array(Json.objectAt(rows, rowIndex), "metadataParts")
                    ?: continue

                for (partIndex in 0 until parts.length()) {
                    val part = Json.objectAt(parts, partIndex) ?: continue
                    val text = Json.text(Json.obj(part, "text"), "content") ?: continue

                    if (text.contains("одпис") || text.contains("ubscrib")) {
                        result.subscribers = text
                    } else if (text.startsWith("@") && result.handle == null) {
                        result.handle = text
                    }
                }
            }
        }
    }

    val sections = ArrayList<ChannelSection>()

    val columns = Json.obj(Json.obj(json, "contents"), "twoColumnBrowseResultsRenderer")
        ?: Json.findFirst("twoColumnBrowseResultsRenderer", json, 4000)

    val tabs = Json.array(columns, "tabs")

    if (tabs != null) {
        for (index in 0 until tabs.length()) {
            val entry = Json.objectAt(tabs, index) ?: continue

            val renderer = Json.obj(entry, "tabRenderer")
                ?: Json.obj(entry, "expandableTabRenderer")

            val sectionTitle = Json.text(renderer, "title") ?: continue

            // «Поиск» — не раздел, а поле ввода: показывать в полосе нечего.
            if (sectionTitle == loc("Поиск") || sectionTitle == "Search") {
                continue
            }

            val endpoint = Json.obj(Json.obj(renderer, "endpoint"), "browseEndpoint")

            sections.add(ChannelSection(sectionTitle, Json.text(endpoint, "params")))
        }
    }

    result.sections = sections

    val entities = SubscriptionEntities()

    applySubscriptionEntities(entities, json)

    result.subscribed = entities.subscribed
    entities.notifications?.let { result.notifications = it }

    /**
     * Веб-ответ о подписке молчит, если браузерной сессии нет. Тогда
     * спрашиваем отдельно у TV-клиента с токеном — того самого, которым
     * и оформляется подписка.
     */
    if (result.subscribed == null && Auth.isSignedIn()) {
        val channelId = Json.text(body, "browseId")

        val state = post(
            "browse", JSONObject().put("browseId", channelId), "TVHTML5", true, 0.0
        )

        if (state != null) {
            val fresh = SubscriptionEntities()

            applySubscriptionEntities(fresh, state)

            result.subscribed = fresh.subscribed
            fresh.notifications?.let { result.notifications = it }

            val button = Json.findFirst("subscribeButtonRenderer", state, 8000)

            if (button != null && result.subscribed == null) {
                result.subscribed = Json.bool(button, "subscribed")

                val bell = notificationsIn(button)

                if (bell != Notifications.UNKNOWN) {
                    result.notifications = bell
                }
            }
        }
    }

    if (result.subscribed == null) {
        // Старая форма кнопки: признак лежит прямо в ней.
        val subscribe = Json.findFirst("subscribeButtonRenderer", json, 6000)

        if (subscribe != null) {
            result.subscribed = Json.bool(subscribe, "subscribed")

            val bell = notificationsIn(subscribe)

            if (bell != Notifications.UNKNOWN) {
                result.notifications = bell
            }
        }
    }

    Log.d {
        "[YouTube/Канал] Разделов: ${sections.size}, подписка: " +
            when (result.subscribed) {
                true -> "да"
                false -> "нет"
                null -> "сервер не сказал"
            }
    }

    return result
}

// --- Подборка -------------------------------------------------------------

/**
 * Плейлист или микс.
 *
 * Миксы («джемы» в терминах UWP-версии) — это подборки, которые YouTube
 * собирает на лету и продолжает бесконечно; они живут не как сохранённый
 * плейлист, а в контексте просмотра, и запрашиваются по голому
 * идентификатору. Обычным плейлистам, наоборот, нужна приставка `VL`.
 */
fun Api.playlist(playlistId: String?): PlaylistPage? {
    var identifier = playlistId ?: return null

    if (identifier.startsWith("VL")) {
        identifier = identifier.substring(2)
    }

    if (identifier.isEmpty()) {
        return null
    }

    val isMix = identifier.startsWith("RD")
    val browseId = if (isMix) identifier else "VL$identifier"

    val signedIn = Auth.isSignedIn()

    val json = post(
        "browse", JSONObject().put("browseId", browseId),
        if (signedIn) "TVHTML5" else "WEB", signedIn, Api.FEED_TTL
    ) ?: return null

    val feed = feedFrom(json)

    val result = PlaylistPage()

    result.items = feed?.items ?: emptyList()
    result.continuation = feed?.continuation

    val header = Json.findFirst("playlistHeaderRenderer", json, 6000)
        ?: Json.findFirst("pageHeaderRenderer", json, 6000)

    var title = Json.renderedText(header, "title")

    if (title == null) {
        val metadata = Json.findFirst("playlistMetadataRenderer", json, 6000)

        title = Json.text(metadata, "title")
    }

    result.title = title ?: Json.text(header, "pageTitle")
    result.channelTitle = Json.renderedText(header, "ownerText")
    result.subtitle = Json.renderedText(header, "numVideosText")
        ?: Json.renderedText(header, "videoCountText")
    result.thumbnail = Json.thumbnail(header, "playlistHeaderBanner", 480)

    return result
}
