package ru.computershik.troubadour.net

import org.json.JSONObject
import ru.computershik.troubadour.Log
import java.util.Locale

/**
 * Лайк и подписка глазами учётной записи, а заодно всё остальное,
 * что TV-клиент говорит о ролике.
 */
class WatchState {
    var liked: Boolean = false
    var disliked: Boolean = false
    var subscribed: Boolean = false
    var notifications: Int = Notifications.UNKNOWN

    var likes: String? = null
    var comments: String? = null
    var commentsToken: String? = null

    var title: String? = null
    var channelTitle: String? = null
    var channelThumbnail: String? = null

    var likeParams: String? = null
    var dislikeParams: String? = null
    var removeLikeParams: String? = null

    /**
     * Похожие ролики — те, что подобраны **этому** зрителю.
     *
     * Обычная страница ролика берётся клиентом WEB без входа: подписи
     * к нему прикладываются только при входе через страницу, а при входе
     * по коду устройства сервер отвечает как гостю. Похожие тогда общие
     * для всех. Этот же запрос идёт от TV-клиента с ключом доступа,
     * то есть от имени зрителя, и подборка в нём своя.
     */
    var related: List<ru.computershik.troubadour.model.VideoItem> = emptyList()

    /** Пустое состояние отдавать наружу незачем — оно ничего не сообщает. */
    internal var filled = false
}

/**
 * Состояние ролика для учётной записи: лайк и подписка.
 *
 * Отдельным запросом и TV-клиентом, потому что учётную запись у нас
 * удостоверяет токен QR-кода, а принимает его в паре с собой именно
 * TVHTML5 — тот же клиент, которым берутся «Главная», подписки и история.
 *
 * Ответ TV-клиента к тому же проще разбирать: там прежние рендереры
 * с прямыми полями `subscribed` и `likeStatus`, тогда как веб давно
 * перешёл на модели представления, где состояние лежит в отдельном
 * хранилище сущностей и по самой кнопке не читается.
 *
 * null, если входа нет или сервер отказал, — тогда состояние остаётся тем,
 * что дал веб.
 */
fun Api.watchState(videoId: String?): WatchState? {
    if (videoId.isNullOrEmpty() || !Auth.isSignedIn()) {
        return null
    }

    val json = post(
        "next", JSONObject().put("videoId", videoId), "TVHTML5", true, 0.0
    )

    if (json == null) {
        Log.d { "[YouTube/Ролик] TV-клиент не сказал о лайке и подписке" }

        return null
    }

    val state = WatchState()

    var subscribe = Json.findFirst("subscribeButtonRenderer", json, 6000)

    if (subscribe == null) {
        // Та же запасная форма, что и у лайка: отдельная сущность.
        subscribe = Json.findFirst("subscriptionStateEntity", json, 6000)
    }

    if (subscribe != null) {
        state.subscribed = Json.bool(subscribe, "subscribed")
        state.filled = true

        val bell = notificationsIn(subscribe)

        if (bell != Notifications.UNKNOWN) {
            state.notifications = bell
        }
    }

    // Колокольчик приходит и отдельной сущностью — берём, если в кнопке
    // его не оказалось: у разных ответов он лежит по-разному.
    if (state.notifications == Notifications.UNKNOWN) {
        val entities = SubscriptionEntities()

        applySubscriptionEntities(entities, json)

        entities.notifications?.let { state.notifications = it }
    }

    /**
     * Лайк у TV-клиента лежит в `likeButtonRenderer` — прямо строкой
     * `likeStatus` со значением `LIKE`, `DISLIKE` или `INDIFFERENT`,
     * и рядом же готовая подпись счётчика.
     *
     * Искал я это сперва в `toggleButtonRenderer` по примете
     * `targetId: watch-like`. Примета в ответе есть, но лежит она не там —
     * оттого в журнале и стояло «лайк неизвестно».
     */
    val like = Json.findFirst("likeButtonRenderer", json, 6000)

    var status = Json.text(like, "likeStatus")

    if (status == null) {
        /**
         * Запасная форма: то же состояние приходит отдельной сущностью
         * в `frameworkUpdates`. Сервер шлёт обе, но полагаться на одну
         * только кнопку не стоит — её форма меняется чаще.
         */
        val entity = Json.findFirst("likeStatusEntity", json, 6000)

        status = Json.text(entity, "likeStatus")
    }

    if (status != null) {
        state.liked = status == "LIKE"

        /**
         * Дизлайк читается тем же полем, и это добавка сверх оригинала:
         * там сохранялся один лишь лайк. Значок `pl_dislike_on` в наборе
         * при этом есть, то есть нажатое состояние второй кнопки рисовать
         * задумано, — а брать его было неоткуда, кроме как из собственной
         * памяти о нажатии, которая переживает не всякий переход.
         */
        state.disliked = status == "DISLIKE"
        state.filled = true
    }

    Json.renderedText(like, "likeCountText")?.let {
        state.likes = it
        state.filled = true
    }

    /**
     * Число комментариев лежит в точке входа в их панель — готовой
     * строкой, как и всё остальное у TV-клиента.
     *
     * Имя рендерера — `commentsEntryPointRenderer`. Я искал его сперва
     * как `…HeaderRenderer`, и счётчик оттого не появлялся вовсе:
     * в ответе TV-клиента такого имени нет.
     */
    val entry = Json.findFirst("commentsEntryPointRenderer", json, 6000)
        ?: Json.findFirst("commentsEntryPointHeaderRenderer", json, 6000)

    Json.renderedText(entry, "commentCount")?.let {
        state.comments = it
        state.filled = true
    }

    /**
     * Название ролика. У вертикальных его больше взять неоткуда: лента
     * reel присылает одни идентификаторы, а в ответе `/player` подпись
     * лежит не всегда — у Shorts она нередко пуста.
     */
    val meta = Json.findFirst("videoMetadataRenderer", json, 6000)

    Json.renderedText(meta, "title")?.let {
        state.title = it
        state.filled = true
    }

    /**
     * Приметы для оценки — их выдаёт сам сервер.
     *
     * У каждого хода свой набор: поставить лайк, поставить дизлайк, снять
     * оценку. Лежат они в служебных эндпоинтах кнопки, и по тому, какое
     * поле заполнено, ход и опознаётся.
     */
    for (hit in Json.findAllOfAny(setOf("likeEndpoint", "dislikeEndpoint"), json, 6000)) {
        Json.text(hit.node, "likeParams")?.let { state.likeParams = it }
        Json.text(hit.node, "dislikeParams")?.let { state.dislikeParams = it }
        Json.text(hit.node, "removeLikeParams")?.let { state.removeLikeParams = it }
    }

    /**
     * Токен комментариев — оттуда же.
     *
     * У вертикальных роликов иначе выходила задержка на пустом месте:
     * лист комментариев шёл за ним отдельным запросом к веб-клиенту, ждал
     * несколько секунд и нередко возвращался ни с чем — у Shorts веб-ответ
     * панели комментариев не несёт. TV-ответ несёт, и мы его и так уже
     * запросили ради счётчиков.
     */
    for (panel in Json.findAll("engagementPanelSectionListRenderer", json, 6000)) {
        val identifier = Json.text(panel, "panelIdentifier")

        if (identifier != "comment-item-section" &&
            identifier != "engagement-panel-comments-section"
        ) {
            continue
        }

        continuationIn(panel)?.let {
            state.commentsToken = it
            state.filled = true
        }

        break
    }

    /**
     * Похожие из TV-ответа — только первая полка.
     *
     * Устройство ответа разобрано по снимку с планшета. Карточки лежат
     * в `contents.singleColumnWatchNextResults.pivot.sectionListRenderer`,
     * и полок там десять по три плитки. Первая — про этот ролик, дальше
     * идут общие советы учётной записи: у ролика про ремонт компьютера
     * это были девять полок подряд про игру RUST, которую смотрит
     * владелец.
     *
     * Прежде мы обходили ответ целиком и брали все тридцать. Оттого
     * к любому ролику показывалась почти одна и та же подборка,
     * не связанная с тем, что человек смотрит. Версия для Windows 10
     * Mobile делает так же — берёт первые шестнадцать подряд, — и у неё
     * та же беда, просто разбавленная: три верные карточки, дальше чужие.
     *
     * Свой же ролик из подборки убираем: он попадается как «сейчас
     * играет».
     */
    val pivot = Json.findFirst("pivot", json, 20000)

    val shelf = Json.objectAt(
        Json.array(Json.obj(pivot, "sectionListRenderer"), "contents"), 0
    )

    val suggested = ru.computershik.troubadour.model.VideoItem.parseFrom(shelf)
        .filter { it.videoId != videoId }

    if (suggested.isNotEmpty()) {
        state.related = suggested
        state.filled = true
    }

    val owner = Json.findFirst("videoOwnerRenderer", json, 6000)

    Json.renderedText(owner, "title")?.let {
        state.channelTitle = it
        state.filled = true
    }

    Json.thumbnail(owner, "thumbnail", 88)?.let {
        state.channelThumbnail = it
        state.filled = true
    }

    Log.d {
        "[YouTube/Ролик] TV-клиент о ролике: похожих ${state.related.size}, " +
            "лайк ${if (state.liked) "да" else "нет"}, " +
            "подписка ${if (state.subscribed) "да" else "нет"} " +
            "(${state.likes ?: "—"} / ${state.comments ?: "—"} комм.)"
    }

    return if (state.filled) state else null
}

/**
 * Оценка ролика — порт `SetVideoRatingAsync`.
 *
 * [action]: `like`, `dislike` либо `none` (снять оценку).
 */
fun Api.rate(videoId: String?, action: String, params: String?): Boolean {
    if (videoId.isNullOrEmpty() || !Auth.isSignedIn()) {
        return false
    }

    val endpoint = when (action) {
        "like" -> "like/like"
        "dislike" -> "like/dislike"
        else -> "like/removelike"
    }

    val body = JSONObject()

    body.put("target", JSONObject().put("videoId", videoId))

    if (!params.isNullOrEmpty()) {
        body.put("params", params)
    }

    /**
     * TVHTML5 идёт первым, MWEB и WEB — запасными.
     *
     * Порядок в оригинале UWP обратный, и он там не выведен, а унаследован
     * от TubeReplacer вместе с формой запроса. Между тем учётную запись
     * у нас удостоверяет токен QR-кода, а принимает его в паре с собой
     * именно TV-клиент: с веб-семейством эта пара проходит не везде —
     * `browse` отвечает на неё отказом 400, и `next` тоже отвечал.
     *
     * Что TV-клиент оценивать умеет, видно по его же ответу: сервер
     * присылает ему готовые `likeEndpoint` и `dislikeEndpoint` с целью
     * и приметами. Их мы сюда и передаём.
     */
    for (client in listOf("TVHTML5", "MWEB", "WEB")) {
        val json = post(endpoint, body, client, true, 0.0)

        if (json != null) {
            Log.d { "[YouTube/Оценка] $endpoint для $videoId: принята ($client)" }

            return true
        }

        Log.d { "[YouTube/Оценка] $endpoint для $videoId: отказ от $client" }
    }

    return false
}

/**
 * Подписка на канал и отказ от неё — порт `SetChannelSubscriptionAsync`.
 *
 * Постоянные `params` из оригинала: `DefaultSubscribeParams`
 * и `DefaultUnsubscribeParams`. Сервер принимает их для любого канала —
 * это не подпись, а пометка о том, откуда нажали.
 */
fun Api.setSubscribed(subscribed: Boolean, channelId: String?): Boolean {
    if (channelId.isNullOrEmpty() || !Auth.isSignedIn()) {
        return false
    }

    val body = JSONObject()

    body.put("channelIds", org.json.JSONArray().put(channelId))
    body.put("params", if (subscribed) "CgIIAxgA" else "CgIIAxgB")

    val json = post(
        if (subscribed) "subscription/subscribe" else "subscription/unsubscribe",
        body, "TVHTML5", true, 0.0
    )

    Log.d {
        "[YouTube/Канал] ${if (subscribed) "Подписка" else "Отписка"} на $channelId: " +
            if (json != null) "удалась" else "отказ"
    }

    return json != null
}

/** Подписка и колокольчик, вынутые из `frameworkUpdates`. */
class SubscriptionEntities {
    var subscribed: Boolean? = null
    var notifications: Int? = null
}

/**
 * Подписка и колокольчик из `frameworkUpdates`.
 *
 * У нынешней кнопки (`subscribeButtonViewModel`) внутри лежат **оба**
 * её вида разом — «Подписаться» с `subscribed: false` и «Вы подписаны»
 * с `subscribed: true`, — потому что это заготовки, а не состояние.
 * Кто из них сейчас на экране, сказано отдельно: сущностью
 * `subscriptionStateEntity` в `frameworkUpdates`. Прежний разбор брал
 * первое попавшееся `subscribeState`, то есть всегда заготовку
 * «Подписаться», и оттого показывал «не подписан» даже подписанному.
 *
 * Колокольчик там же, соседней сущностью, готовым значением:
 * `SUBSCRIPTION_NOTIFICATION_STATE_ALL` и подобными.
 */
internal fun Api.applySubscriptionEntities(result: SubscriptionEntities, json: Any?) {
    /**
     * Ищем по точному пути, а не обходом дерева.
     *
     * Ответ канала — под полмегабайта JSON, и обход в нём упирается
     * в потолок посещённых узлов раньше, чем доберётся
     * до `frameworkUpdates`. А путь к сущностям известен.
     */
    val root = json as? JSONObject

    val mutations = Json.array(
        Json.obj(Json.obj(root, "frameworkUpdates"), "entityBatchUpdate"),
        "mutations"
    )

    var entity: JSONObject? = null
    var bell: JSONObject? = null

    if (mutations != null) {
        for (index in 0 until mutations.length()) {
            val payload = Json.obj(mutations.opt(index) as? JSONObject, "payload")

            if (entity == null) {
                entity = Json.obj(payload, "subscriptionStateEntity")
            }

            if (bell == null) {
                bell = Json.obj(payload, "subscriptionNotificationStateEntity")
            }
        }
    }

    if (entity == null) {
        entity = Json.findFirst("subscriptionStateEntity", json, 8000)
    }

    if (entity != null) {
        result.subscribed = Json.bool(entity, "subscribed")
    }

    if (bell == null) {
        bell = Json.findFirst("subscriptionNotificationStateEntity", json, 8000)
    }

    val state = Json.text(bell, "state")?.uppercase(Locale.US) ?: return

    val picked = when {
        state.contains("ALL") -> Notifications.ALL
        state.contains("NONE") || state.contains("OFF") -> Notifications.NONE
        state.contains("DEFAULT") || state.contains("OCCASIONAL") ||
            state.contains("PERSONALIZED") -> Notifications.PERSONALIZED
        else -> Notifications.UNKNOWN
    }

    if (picked != Notifications.UNKNOWN) {
        result.notifications = picked
    }
}

/**
 * Нынешнее предпочтение оповещений из кнопки подписки — порт
 * `ParseNotificationStateText`.
 *
 * У кнопки лежит перечень состояний со своими значками и подписями,
 * а рядом — `currentStateId` того, которое выбрано. Ни того ни другого
 * может не оказаться; тогда узнаём по подписи, и она бывает на языке
 * человека — оттого и русские слова в разборе.
 */
internal fun Api.notificationsIn(subscribe: JSONObject?): Int {
    val toggle = Json.findFirst(
        "subscriptionNotificationToggleButtonRenderer", subscribe, 2000
    ) ?: return Notifications.UNKNOWN

    val current = Json.text(toggle, "currentStateId")
    val states = Json.array(toggle, "states") ?: return Notifications.UNKNOWN

    for (index in 0 until states.length()) {
        val node = states.opt(index) as? JSONObject ?: continue

        val identifier = Json.string(node, "stateId")

        if (current != null && identifier != current) {
            continue
        }

        val icon = Json.text(Json.findFirst("icon", node, 200), "iconType")

        val label = (Json.renderedText(node, "state")
            ?: Json.renderedText(node, "tooltip"))?.uppercase(Locale.US)

        val mark = if (!icon.isNullOrEmpty()) icon.uppercase(Locale.US) else label ?: continue

        if (mark.contains("OFF") || mark.contains("NONE") ||
            mark.contains("ОТКЛ") || mark.contains("НЕТ")
        ) {
            return Notifications.NONE
        }

        if (mark.contains("ACTIVE") || mark.contains("ALL") || mark.contains("ВСЕ")) {
            return Notifications.ALL
        }

        return Notifications.PERSONALIZED
    }

    return Notifications.UNKNOWN
}

/**
 * Метка для `notification/modify_channel_preference` — порт
 * `BuildNotificationPreferenceParams`.
 *
 * Это не подпись и не догадка, а собранный вручную protobuf, тот же байт
 * в байт: поле 1 — номер канала строкой, поле 2 — вложенное сообщение
 * с кодом состояния, дальше две постоянные величины. Кодов три:
 * 1 — по интересам, 2 — все, 3 — никаких.
 *
 * base64 здесь системный, в отличие от оригинала: там свой пришлось
 * писать руками, потому что `base64EncodedStringWithOptions:` появился
 * только в iOS 7 при нижней границе 5.1. В Android `android.util.Base64`
 * есть с API 8.
 */
private fun notificationParams(state: Int, channelId: String): String? {
    val identifier = channelId.toByteArray(Charsets.UTF_8)

    if (identifier.isEmpty() || identifier.size > 127) {
        return null
    }

    val code: Byte = when (state) {
        Notifications.ALL -> 2
        Notifications.NONE -> 3
        else -> 1
    }

    val blob = java.io.ByteArrayOutputStream()

    blob.write(0x0A)
    blob.write(identifier.size)
    blob.write(identifier)

    blob.write(byteArrayOf(0x12, 0x02, 0x08, code, 0x18, 0x00, 0x20, 0x04))

    val text = android.util.Base64.encodeToString(
        blob.toByteArray(), android.util.Base64.NO_WRAP
    )

    return Http.encodeParameter(text)
}

/**
 * Меняет предпочтение оповещений — порт
 * `ModifyChannelNotificationPreferenceAsync`.
 */
fun Api.setNotifications(state: Int, channelId: String?): Boolean {
    if (channelId.isNullOrEmpty() || !Auth.isSignedIn()) {
        return false
    }

    val params = notificationParams(state, channelId) ?: return false

    val json = post(
        "notification/modify_channel_preference",
        JSONObject().put("params", params),
        "TVHTML5", true, 0.0
    )

    Log.d {
        "[YouTube/Канал] Оповещения $state для $channelId: " +
            if (json != null) "приняты" else "отказ"
    }

    return json != null
}

/** Метка воспроизведения — шестнадцать знаков, как у TV-клиента. */
internal fun Api.playbackNonce(): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val random = java.security.SecureRandom()

    val nonce = StringBuilder(16)

    for (index in 0 until 16) {
        nonce.append(alphabet[random.nextInt(alphabet.length)])
    }

    return nonce.toString()
}

/** Один служебный сигнал; отказ не беда, историю он не ломает. */
private fun Api.pingStats(url: String) {
    val builder = Http.request(url) ?: return

    val token = Auth.accessToken()

    if (token.isNotEmpty()) {
        builder.header("Authorization", "Bearer $token")
    }

    builder.header("User-Agent", Api.TV_USER_AGENT)
    builder.header("Referer", "https://www.youtube.com/tv")

    val answer = Http.send(builder.build(), 4096, caching = false)

    Log.d { "[YouTube/История] Сигнал: код ${answer.statusCode}" }
}

/**
 * Отмечает ролик просмотренным — то есть кладёт его в историю.
 *
 * Отдельного запроса «добавить в историю» у InnerTube нет вовсе: YouTube
 * считает просмотр по старым служебным сигналам, тем же, что шлёт
 * youtube.com/tv. Адреса для них приходят в самом ответе `/player`, уже
 * подписанные сервером, — поэтому ответ и передаётся сюда целиком,
 * а не запрашивается заново.
 *
 * Сигналы уходят от имени учётной записи: этим просмотр к ней
 * и приписывается. Без входа не делается ничего.
 */
fun Api.reportWatched(playerResponse: JSONObject?, position: Double) {
    reportWatched(playerResponse, position, -1.0, 0.0, false)
}

/**
 * Отрезок просмотра: отсюда, досюда, столько прошло.
 *
 * Настоящий TV-клиент не отмечает ролик одной точкой — он ведёт
 * непрерывную запись. В дампе телевизора пятнадцать обращений
 * к `watchtime` за сессию: первые три через десять секунд, дальше через
 * сорок, и в каждом `st` равен `et` предыдущего. Так сервер складывает
 * из отрезков всю дорожку просмотра, а не одну отметку у нулевой
 * секунды — и от неё же потом считается доля просмотренного, та самая,
 * что рисуется полоской на карточке.
 *
 * [from] меньше нуля означает «отрезка нет» — начало показа.
 */
fun Api.reportWatched(
    playerResponse: JSONObject?,
    position: Double,
    from: Double,
    elapsed: Double,
    final: Boolean
) {
    if (!Auth.isSignedIn()) {
        return
    }

    val tracking = Json.obj(playerResponse, "playbackTracking")

    val playback = Json.text(Json.obj(tracking, "videostatsPlaybackUrl"), "baseUrl")
    val watchtime = Json.text(Json.obj(tracking, "videostatsWatchtimeUrl"), "baseUrl")

    if (playback.isNullOrEmpty() && watchtime.isNullOrEmpty()) {
        Log.d { "[YouTube/История] Адресов для сигналов нет — просмотр не отмечен" }

        return
    }

    val details = Json.obj(playerResponse, "videoDetails")

    val length = Json.text(details, "lengthSeconds")?.toDoubleOrNull() ?: 0.0
    val videoId = Json.text(details, "videoId")

    /**
     * Общая часть запроса — то, чем клиент представляется. Значения взяты
     * у TV-клиента: сигналы должны выглядеть так же, как от него, иначе
     * просмотр не засчитывается.
     */
    val common = "&cpn=${playbackNonce()}&ver=2&fs=0&volume=100&muted=0&state=playing" +
        "&c=TVHTML5&cver=${clientVersion("TVHTML5")}&cplayer=UNIPLAYER&cmodel=SmartTV" +
        "&cos=Tizen&cosver=5.0&cplatform=TV&ctheme=CLASSIC&hl=${hl()}&cr=${gl()}"

    val at = maxOf(position, 0.0)
    val opening = from < 0

    /**
     * Сигнал `playback` — только при начале показа: он открывает запись,
     * и повторять его на каждом отрезке незачем.
     */
    if (opening && !playback.isNullOrEmpty()) {
        pingStats(String.format(Locale.US, "%s%s&cmt=%.3f", playback, common, at))
    }

    if (!watchtime.isNullOrEmpty()) {
        /**
         * Именно этот сигнал и заводит запись в истории: он сообщает,
         * какой отрезок посмотрели. Нулевой отрезок не считается, поэтому
         * у самого начала берётся секунда.
         */
        val begin = if (opening) 0.0 else maxOf(from, 0.0)

        var end = maxOf(at, begin)

        if (opening && end <= begin) {
            end = begin + 1.0
        }

        val spent = if (opening) end else maxOf(elapsed, 0.0)

        val url = StringBuilder(
            String.format(
                Locale.US, "%s%s&cmt=%.3f&st=%.3f&et=%.3f&rt=%.3f",
                watchtime, common, at, begin, end, spent
            )
        )

        if (length > 0) {
            url.append(String.format(Locale.US, "&len=%.3f", length))
        }

        /**
         * `final=1` у последнего отрезка.
         *
         * В дампе его нет, и это не довод против: дамп снят с эфира,
         * который не кончается, — там все обращения идут со
         * `state=playing` и без признака конца. Признак этот у сигналов
         * YouTube означает «запись закрыта, больше по этому показу
         * ничего не будет».
         */
        if (final) {
            url.append("&final=1")
        }

        pingStats(url.toString())
    }

    Log.d {
        "[YouTube/История] $videoId: отрезок ${(if (opening) 0.0 else maxOf(from, 0.0)).toInt()}…" +
            "${at.toInt()} с, показ на ${at.toInt()} с" +
            (if (final) ", запись закрыта" else "")
    }
}
