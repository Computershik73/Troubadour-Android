package ru.computershik.troubadour.net

import org.json.JSONObject
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.model.VideoItem

/**
 * Страница ролика: описание, автор, счётчики, похожие, токен комментариев,
 * очередь подборки.
 */
class VideoDetails {
    var title: String = ""
    var channelTitle: String? = null
    var channelId: String? = null
    var channelThumbnail: String? = null
    var subscribers: String? = null
    var views: String? = null
    var published: String? = null
    var likes: String? = null
    var description: String? = null
    var commentsToken: String? = null
    var commentsCount: String? = null

    var related: List<VideoItem> = emptyList()

    /** Очередь подборки — плейлиста или микса, открытого вместе с роликом. */
    var queue: List<VideoItem> = emptyList()
    var queueTitle: String? = null
    var queueIndex: Int = 0

    /** Лайк и подписка глазами учётной записи. */
    var liked: Boolean = false
    var disliked: Boolean = false
    var subscribed: Boolean = false
    var notifications: Int = Notifications.UNKNOWN

    /** Метка оценки — её сервер даёт вместе с кнопкой. */
    var likeParams: String? = null
    var dislikeParams: String? = null
    var removeLikeParams: String? = null
}

/** Одна запись в списке комментариев. */
class CommentItem {
    var author: String = ""
    var text: String = ""
    var published: String? = null
    var avatar: String? = null

    /** Метка продолжения ветки ответов и их число. */
    var replies: String? = null
    var replyCount: String? = null

    /** Метка, которой отвечают на этот комментарий. */
    var replyParams: String? = null

    /**
     * Метка правки. Приходит только у своих комментариев: сервер решает
     * это сам и чужому её не даёт. По её наличию и решается, показывать ли
     * «Изменить», — своего списка «чьё это» у нас нет, а гадать по имени
     * канала ненадёжно: имена повторяются.
     */
    var editParams: String? = null

    /**
     * Ответ ли это в раскрытой ветке.
     *
     * Ветка раскрывается прямо в списке, как в оригинале: строка
     * «Ответы (N)» заменяется самими ответами, а те рисуются с отступом.
     * Поле не приходит от сервера — его ставит список, когда вставляет
     * ответы.
     */
    var isReply: Boolean = false

    /**
     * Продолжение ветки — стоит у последнего привезённого ответа.
     *
     * Ветка приезжает страницами, как и сам список. Метку следующей
     * страницы держит хвостовой ответ: когда он показывается на экране,
     * список идёт за остальными. Так ветка догружается прокруткой,
     * а не обрывается на первой пачке.
     */
    var moreReplies: String? = null
}

/** Страница комментариев. */
class CommentsPage {
    var items: List<CommentItem> = emptyList()
    var continuation: String? = null

    /**
     * Метка «этому можно писать» — `createCommentParams`. Её отсутствие
     * означает, что писать не дают.
     */
    var createParams: String? = null

    /**
     * Закрытые комментарии — отдельный случай, а не «их просто нет».
     * Сервер об этом говорит сам и на языке человека.
     */
    var disabledMessage: String? = null
}

/** Оповещения о новых роликах канала — колокольчик у кнопки подписки. */
object Notifications {
    const val UNKNOWN = 0
    const val PERSONALIZED = 1
    const val ALL = 2
    const val NONE = 3
}

internal fun Api.postNext(body: JSONObject): JSONObject? =
    post("next", body, "WEB", false, 0.0)

fun Api.videoDetails(videoId: String): VideoDetails? = videoDetails(videoId, null)

/**
 * То же, но с подборкой: заполняются [VideoDetails.queue] и соседние поля.
 *
 * Без идентификатора подборки очереди в ответе не будет — сервер не знает,
 * из какого списка открыт ролик.
 */
fun Api.videoDetails(videoId: String, playlistId: String?): VideoDetails? {
    val body = JSONObject()

    body.put("videoId", videoId)

    /**
     * Идентификатор подборки уходит в запрос вместе с роликом — иначе
     * очереди в ответе не будет вовсе. В оригинале страница ролика получает
     * `PlaylistId` при переходе и передаёт его дальше: без него микс
     * открывался бы одиноким роликом, о чём в `ParseTileRenderer` сказано
     * прямо.
     */
    if (!playlistId.isNullOrEmpty()) {
        body.put("playlistId", playlistId)
    }

    val json = postNext(body) ?: return null

    val result = VideoDetails()

    /**
     * Заголовок и счётчики лежат в `videoPrimaryInfoRenderer`, автор
     * и описание — в `videoSecondaryInfoRenderer`. Порт
     * `ExtractVideoInfoFromRenderer`, только поиск по дереву вместо ходьбы
     * по известному пути: форма ответа `next` у WEB-клиента меняется чаще,
     * чем хотелось бы.
     */
    val primary = Json.findFirst("videoPrimaryInfoRenderer", json, 6000)
    val secondary = Json.findFirst("videoSecondaryInfoRenderer", json, 6000)

    result.title = Json.renderedText(primary, "title") ?: ""

    result.views = Json.renderedText(
        Json.obj(Json.obj(primary, "viewCount"), "videoViewCountRenderer"),
        "viewCount"
    )

    result.published = Json.renderedText(primary, "dateText")

    /**
     * Автора ищем сперва там, где ему положено быть, а не нашлось —
     * по всему ответу.
     *
     * `videoSecondaryInfoRenderer` в ответе клиента WEB бывает не всегда,
     * и тогда прежний разбор молча оставлял и кружок, и название канала
     * пустыми, хотя название ролика и счётчик лайков брались из других
     * мест того же ответа и приходили исправно. Со стороны это и выглядит
     * как «иногда автор не прогружается».
     */
    var owner = Json.findFirst("videoOwnerRenderer", secondary, 2000)

    if (owner == null) {
        owner = Json.findFirst("videoOwnerRenderer", json, 200000)
    }

    result.channelTitle = Json.renderedText(owner, "title")
    result.subscribers = Json.renderedText(owner, "subscriberCountText")
    result.channelThumbnail = Json.thumbnail(owner, "thumbnail", 88)

    val browse = Json.findFirst("browseEndpoint", owner, 500)

    result.channelId = Json.text(browse, "browseId")

    var description = Json.renderedText(secondary, "attributedDescription")
        ?: Json.renderedText(secondary, "description")

    if (description == null) {
        // Новая форма: описание лежит в attributedDescription с полем content.
        description = Json.text(Json.obj(secondary, "attributedDescription"), "content")
    }

    result.description = description

    /**
     * Число лайков берётся из подписи кнопки. Отдельного числового поля
     * у InnerTube нет: сервер отдаёт уже готовую строку вроде «20 тыс.»,
     * и в оригинале показывается именно она.
     */
    val likeButton = Json.findFirst("segmentedLikeDislikeButtonViewModel", json, 6000)

    /**
     * Спускаемся именно по ветке лайка, а не первым попавшимся
     * переключателем.
     *
     * Под этой подложкой их два — лайк и дизлайк, — а обход дерева ходит
     * по ключам словаря. В оригинале порядок ключей у NSDictionary был
     * вовсе не определён, и на кнопке оказывалась подпись «Не нравится»
     * вместо счётчика. Здесь у JSONObject порядок вставки сохраняется,
     * но полагаться на это нельзя: сервер волен переставить поля местами.
     * Имя `likeButtonViewModel` двусмысленности не оставляет.
     */
    val likeBranch = Json.findFirst("likeButtonViewModel", likeButton, 1000)

    val likeToggle = Json.findFirst("toggleButtonViewModel", likeBranch ?: likeButton, 1000)
    val likeDefault = Json.obj(likeToggle, "defaultButtonViewModel")
    val likeInner = Json.findFirst("buttonViewModel", likeDefault ?: likeToggle, 500)

    var likes = Json.text(likeInner, "title")

    if (likes == null) {
        val old = Json.findFirst("toggleButtonRenderer", json, 6000)

        likes = Json.renderedText(old, "defaultText")
    }

    result.likes = likes

    // Похожие — обычные карточки, разбираются общим путём.
    result.related = VideoItem.parseFrom(Json.findFirst("secondaryResults", json, 6000))

    /**
     * Очередь подборки — список роликов плейлиста или микса, открытого
     * вместе с этим.
     *
     * Порт `PlaylistQueuePanel` из Video.xaml: в ответе `next` она лежит
     * отдельной веткой `playlist.playlist` и состоит
     * из `playlistPanelVideoRenderer`. Разбирать её надо именно оттуда,
     * а не общим ходом по всему ответу: те же рендереры встречаются
     * и в других местах, и в «похожие» попадала бы чужая очередь.
     */
    val queue = Json.obj(Json.findFirst("playlist", json, 6000), "playlist")

    if (queue != null) {
        val items = VideoItem.parseFrom(Json.array(queue, "contents"))

        if (items.isNotEmpty()) {
            result.queue = items
            result.queueTitle = Json.text(queue, "title")
                ?: Json.renderedText(queue, "titleText")
            result.queueIndex = Json.int(queue, "currentIndex")
        }
    }

    /**
     * Токен комментариев лежит в панели с идентификатором
     * `engagement-panel-comments-section` — порт `FindCommentsContinuation`.
     * Брать первый попавшийся токен нельзя: рядом лежит токен похожих.
     */
    for (panel in Json.findAll("engagementPanelSectionListRenderer", json, 6000)) {
        val identifier = Json.text(panel, "panelIdentifier")

        /**
         * Имя панели у веба и у TV-клиента разное:
         * `engagement-panel-comments-section` против `comment-item-section`.
         * Берём оба — ответы приходят и от того, и от другого.
         */
        if (identifier != "engagement-panel-comments-section" &&
            identifier != "comment-item-section"
        ) {
            continue
        }

        result.commentsToken = continuationIn(panel)

        /**
         * Сколько комментариев — здесь же, в шапке панели.
         *
         * Лежит оно не в «commentCount», как можно было бы подумать,
         * а в `contextualInfo` — строкой рядом с заголовком «Комментарии»,
         * той самой, что видна под роликом: «2,4 млн». Проверено на живом
         * ответе. Раньше счётчик брали только у TV-клиента, а тот отвечает
         * вошедшим, — оттого у вертикальных роликов без входа его не было
         * вовсе.
         */
        if (result.commentsCount == null) {
            val header = Json.findFirst("engagementPanelTitleHeaderRenderer", panel, 600)

            result.commentsCount = Json.renderedText(header, "contextualInfo")
        }

        break
    }

    /**
     * Лайк и подписка — отдельным запросом к TV-клиенту.
     *
     * Веб-ответ о них молчит, если браузерной сессии нет, а она у нас
     * не основной вход. Что скажет TV-клиент, то и показываем; не скажет
     * ничего — остаётся то, что нашлось в вебе.
     */
    watchState(videoId)?.let { state ->
        /**
         * Похожие берём со страницы ролика, а TV-ответ — только про запас.
         *
         * Прежде было наоборот, и вышло плохо: у TV-раскладки нет своей
         * ветки похожих, поэтому оттуда бралось всё, что похоже
         * на карточку, — а в ответе, кроме самого ролика, лежат ещё
         * и полки с общими советами для этой учётной записи. В итоге
         * к любому ролику показывался один и тот же набор, никак
         * не связанный с тем, что человек смотрит.
         *
         * У клиента WEB похожие лежат там, где им и положено, —
         * в `secondaryResults`, и они действительно про этот ролик.
         * Личными они становятся при входе в браузере: тогда запрос
         * идёт с куками сеанса. Вход по коду устройства их не даёт,
         * и подборка выходит общая — но общая **по теме**, а это
         * куда ближе к делу, чем чужие советы.
         */
        if (result.related.isEmpty() && state.related.isNotEmpty()) {
            result.related = state.related

            Log.d { "[YouTube/Ролик] Похожих на странице нет — взяли из TV-ответа" }
        }

        /**
         * Пропуск к комментариям берём у входа, если страница его не дала.
         *
         * Панель комментариев в ответе клиента WEB приходит не всегда:
         * у части роликов её попросту нет, и карточка под роликом
         * говорила «комментарии к этому видео отключены» там, где
         * их тысячи. TV-ответ пропуск даёт.
         */
        if (result.commentsToken.isNullOrEmpty() && !state.commentsToken.isNullOrEmpty()) {
            result.commentsToken = state.commentsToken
        }

        if (result.commentsCount.isNullOrEmpty() && !state.comments.isNullOrEmpty()) {
            result.commentsCount = state.comments
        }

        /**
         * Автора добираем у TV-клиента, если веб его не дал.
         *
         * Этот ответ всё равно уже на руках, и кружок с названием канала
         * в нём есть. Прежде они попросту выбрасывались: `WatchState` их
         * разбирал, а страница ролика не читала.
         */
        if (result.channelTitle.isNullOrEmpty() && !state.channelTitle.isNullOrEmpty()) {
            result.channelTitle = state.channelTitle
        }

        if (result.channelThumbnail.isNullOrEmpty() &&
            !state.channelThumbnail.isNullOrEmpty()
        ) {
            result.channelThumbnail = state.channelThumbnail
        }

        result.liked = state.liked
        result.disliked = state.disliked
        result.subscribed = state.subscribed
        result.notifications = state.notifications
        result.likeParams = state.likeParams
        result.dislikeParams = state.dislikeParams
        result.removeLikeParams = state.removeLikeParams
    }

    Log.d {
        "[YouTube/Ролик] Разобрано: название ${if (result.title.isNotEmpty()) "есть" else "нет"}, " +
            "лайки ${result.likes ?: "нет"}, " +
            "канал ${result.channelTitle ?: "нет"}, " +
            "кружок ${if (result.channelThumbnail != null) "есть" else "нет"}, " +
            "очередь ${result.queue.size}" +
            (if (secondary == null) " — videoSecondaryInfoRenderer в ответе нет" else "")
    }

    return result
}

/**
 * Метки ответа и правки из поддерева одного комментария.
 *
 * Имена полей берутся по всему поддереву, а не по известному пути: форма
 * этой поверхности меняется чаще, чем имена, — так же добывается и метка
 * написания комментария. Что найдётся, то и кладём; чего нет, того у нас
 * и не будет — сервер не даёт метку правки чужому комментарию, и по её
 * отсутствию как раз и видно, что он чужой.
 */
private class CommentMarks {
    var key: String? = null
    var token: String? = null
    var reply: String? = null
    var edit: String? = null
}

private fun collectCommentMarks(node: JSONObject, entry: CommentMarks) {
    Json.findString("createReplyParams", node, 20000)?.let { entry.reply = it }

    val edit = Json.findString("updateCommentParams", node, 20000)
        ?: Json.findString("updateReplyParams", node, 20000)

    if (!edit.isNullOrEmpty()) {
        entry.edit = edit
    }
}

/**
 * Комментарии по токену.
 *
 * Потолок обхода поднят с семи тысяч до общего. Страница комментариев —
 * четверть мегабайта, и словарей в ней много больше семи тысяч: обход
 * упирался в потолок, не дойдя до конца. Терялись и последние комментарии,
 * и ветки ответов — они лежат в дереве позже самих записей.
 */
/**
 * Продолжение ветки ответов.
 *
 * У самого списка метка следующей страницы лежит в `continuationEndpoint`,
 * и общий разбор берёт её оттуда. У ветки — нет: там это **кнопка**
 * «Показать ещё ответы», и токен спрятан в её команде,
 * `button.buttonRenderer.command.continuationCommand`. Общий разбор такой
 * формы не знал, ветка выглядела исчерпанной на первой пачке, и у
 * комментария с двадцатью шестью ответами их показывалось восемь.
 *
 * Спрашивается это **только** как запасной путь, когда обычной метки
 * в ответе нет. Иначе кнопка ветки, попавшаяся в общем списке раньше
 * конца списка, увела бы страницы всего перечня в чужую ветку.
 */
private fun Api.repliesContinuationIn(json: Any?): String? {
    for (item in Json.findAll("continuationItemRenderer", json, 200000)) {
        val button = Json.obj(Json.obj(item, "button"), "buttonRenderer")
        val command = Json.obj(Json.obj(button, "command"), "continuationCommand")

        Json.text(command, "token")?.let { return it }
    }

    return null
}

/**
 * Метка следующей страницы **перечня** комментариев.
 *
 * Общий разбор берёт первый попавшийся `continuationItemRenderer` во всём
 * дереве, и на второй странице это оказывался не конец списка,
 * а ветка ответов: у каждой ветки такой же узел, и стоит он раньше — сразу
 * при своём комментарии. Перечень уходил в чужую ветку, и её ответы
 * вставали в него как обычные записи.
 *
 * Поэтому метку берём там, где она и лежит: последним звеном
 * привезённого куска — `continuationItems` у `appendContinuationItemsAction`
 * либо у `reloadContinuationItemsCommand`. Ветки внутри этого куска —
 * не последние, и спутать их с концом списка больше нечем.
 */
private fun Api.listContinuationIn(json: Any?): String? {
    val actions = ArrayList<JSONObject>()

    actions.addAll(Json.findAll("appendContinuationItemsAction", json, 200000))
    actions.addAll(Json.findAll("reloadContinuationItemsCommand", json, 200000))

    for (action in actions) {
        val items = Json.array(action, "continuationItems") ?: continue

        val tail = Json.objectAt(items, items.length() - 1) ?: continue

        val renderer = Json.obj(tail, "continuationItemRenderer") ?: continue

        val endpoint = Json.obj(renderer, "continuationEndpoint")
        val command = Json.obj(endpoint, "continuationCommand")

        Json.text(command, "token")?.let { return it }
    }

    return null
}

/**
 * Старые формы метки — их кладёт TV-клиент.
 *
 * `nextContinuationData` и `reloadContinuationData`; последняя приезжает
 * в панели комментариев у вертикальных роликов. `continuationItemRenderer`
 * здесь намеренно не спрашивается — этим занят `listContinuationIn`,
 * и только он умеет отличить конец списка от ветки.
 */
private fun Api.legacyContinuationIn(json: Any?): String? {
    val next = Json.findFirst("nextContinuationData", json, 200000)

    Json.text(next, "continuation")?.let { return it }

    val reload = Json.findFirst("reloadContinuationData", json, 200000)

    return Json.text(reload, "continuation")
}

fun Api.comments(token: String?, replies: Boolean = false): CommentsPage? {
    if (token.isNullOrEmpty()) {
        return null
    }

    val json = postNext(JSONObject().put("continuation", token)) ?: return null

    val comments = ArrayList<CommentItem>()

    val payloads = Json.findAll("commentEntityPayload", json, 200000)

    /**
     * Ветки ответов: у какого комментария за ними идти.
     *
     * Сам текст ответов в ответе не лежит — там только метка продолжения
     * в `commentThreadRenderer.replies`. Связать её с записью нужно
     * по опознавательному ключу, и вот тут была загвоздка: **у
     * `commentViewModel` поля `commentId` нет вовсе**. Прежний разбор
     * спрашивал несуществующее поле, всегда получал пустоту, и веток
     * не было ни у одного комментария.
     *
     * Опознавательный ключ ветки — `toolbarStateKey`. Он же лежит
     * и в записи с текстом (`properties.toolbarStateKey`), и совпадает
     * с ней слово в слово — проверено на живом ответе: двадцать веток
     * из двадцати.
     *
     * Сам `commentViewModel` при этом завёрнут дважды: внешний ключ держит
     * объект с таким же именем внутри. Поэтому ищем не первый попавшийся,
     * а тот, у которого ключ действительно есть.
     *
     * В UWP-версии веток нет вовсе; это добавка сверх оригинала.
     */
    val threads = ArrayList<CommentMarks>()
    val marks = ArrayList<CommentMarks>()

    for (thread in Json.findAll("commentThreadRenderer", json, 200000)) {
        var key: String? = null

        for (view in Json.findAll("commentViewModel", thread, 600)) {
            key = Json.text(view, "toolbarStateKey")

            if (!key.isNullOrEmpty()) {
                break
            }
        }

        /**
         * Ветки бывают не у всех, а ответить и поправить можно и там, где
         * их нет. Поэтому метки собираются по всем `commentThreadRenderer`,
         * включая те, у которых продолжения нет, а в список веток
         * попадают только те, у кого есть токен.
         */
        val entry = CommentMarks()

        entry.key = key
        entry.token = continuationIn(Json.obj(thread, "replies"))

        collectCommentMarks(thread, entry)

        marks.add(entry)

        if (!entry.token.isNullOrEmpty()) {
            threads.add(entry)
        }
    }

    for (payload in payloads) {
        val author = Json.obj(payload, "author")
        val properties = Json.obj(payload, "properties")

        val text = Json.text(Json.obj(properties, "content"), "content")

        if (text.isNullOrEmpty()) {
            continue
        }

        var name = Json.string(author, "displayName", "") ?: ""

        // Имя приходит то с собачкой, то без — приводим к одному виду,
        // как в оригинале.
        if (name.isNotEmpty() && !name.startsWith("@")) {
            name = "@$name"
        }

        val comment = CommentItem()

        comment.author = name
        comment.text = text
        comment.published = Json.text(properties, "publishedTime")

        /**
         * Кружок автора комментария лежит не там, где у прочих карточек,
         * и порядок поиска здесь взят из `ParseCommentEntityPayload`.
         *
         * Первым делом `author.avatarThumbnailUrl` — готовая ссылка
         * на тот самый кружок 88 точек. Именно её мы и упускали: разбор
         * начинался сразу с `avatar.image.sources[]`, а в ответах, где
         * `avatar` пуст или отдан отдельной записью, оттуда брать нечего —
         * кружков не было вовсе.
         *
         * Запасной ход — `sources[]`, и там берётся **самый крупный**
         * снимок, а не первый подходящий: порядок в массиве не обещан.
         */
        var avatar = Json.text(author, "avatarThumbnailUrl")

        if (avatar.isNullOrEmpty()) {
            val avatarNode = Json.obj(payload, "avatar")
            val image = Json.obj(avatarNode, "image")
            val sources = Json.array(image, "sources")

            var best = -1

            if (sources != null) {
                for (index in 0 until sources.length()) {
                    val source = sources.opt(index) as? JSONObject ?: continue
                    val url = Json.text(source, "url") ?: continue

                    val area = Json.int(source, "width") * Json.int(source, "height")

                    if (avatar.isNullOrEmpty() || area >= best) {
                        avatar = url
                        best = area
                    }
                }
            }

            if (avatar.isNullOrEmpty()) {
                // Совсем старая форма, на случай если сервер к ней вернётся.
                avatar = Json.thumbnail(avatarNode, "image", 88)
            }
        }

        comment.avatar = avatar

        /**
         * Ветка: сколько ответов и по какой метке за ними идти. Счётчик
         * лежит в `toolbar` — там же, где число оценок.
         */
        val identifier = Json.text(properties, "toolbarStateKey")

        var token = threads.firstOrNull {
            !identifier.isNullOrEmpty() && it.key == identifier
        }?.token

        /**
         * Не нашлось по ключу — берём по месту.
         *
         * Ветки и записи сервер присылает в одном порядке, поэтому n-я
         * ветка принадлежит n-й записи. Это запасной ход на случай, если
         * упаковка ключа опять сменится: пусть лучше ветка окажется
         * не у того комментария, чем не окажется вовсе.
         */
        if (token.isNullOrEmpty() && comments.size < threads.size) {
            token = threads[comments.size].token
        }

        if (!token.isNullOrEmpty()) {
            comment.replies = token
            comment.replyCount = Json.text(Json.obj(payload, "toolbar"), "replyCount")
        }

        /**
         * Метки ответа и правки — тем же способом, что и ветка:
         * по `toolbarStateKey`, а не нашлось — по месту в списке.
         */
        var mark = marks.firstOrNull {
            !identifier.isNullOrEmpty() && it.key == identifier
        }

        if (mark == null && comments.size < marks.size) {
            mark = marks[comments.size]
        }

        comment.replyParams = mark?.reply
        comment.editParams = mark?.edit

        comments.add(comment)

        // Столько же, сколько брала UWP-версия: дальше начинается лишняя
        // работа по замеру текста, а прокрутка всё равно доберётся
        // до следующей страницы.
        if (comments.size >= 80) {
            break
        }
    }

    val result = CommentsPage()

    result.items = comments
    /**
     * Кнопку «Показать ещё ответы» спрашиваем **только** у страницы ветки.
     *
     * В перечне комментариев такая кнопка тоже есть — своя у каждой
     * ветки, — и стоит она в дереве раньше конца списка. Взятая там,
     * она уводила страницы всего перечня в чужую ветку: список добирал
     * себя ответами, а те вставали в него как обычные записи.
     */
    result.continuation = listContinuationIn(json)
        ?: (if (replies) repliesContinuationIn(json) else null)
        ?: legacyContinuationIn(json)

    /**
     * Сколько записей получили метки — строкой в журнал.
     *
     * Метки эти добываются поиском по имени поля, а форма поверхности
     * меняется; молчаливый ноль означал бы «ответить нельзя никому»,
     * и отличить его от «поле переименовали» было бы нечем.
     */
    Log.d {
        val canReply = comments.count { !it.replyParams.isNullOrEmpty() }
        val canEdit = comments.count { !it.editParams.isNullOrEmpty() }

        "[YouTube/Комментарий] Записей ${comments.size}, ответить можно " +
            "$canReply, поправить своих $canEdit"
    }

    /**
     * Метка «этому можно писать» — оттуда же, из этой самой страницы.
     *
     * Отдельного запроса за ней нет: `createCommentParams` кладётся
     * сервером в поле ввода над списком, и раз список мы уже привезли,
     * то и метка приехала вместе с ним. Её отсутствие — это ответ «писать
     * нельзя»: так бывает и у ролика с закрытыми комментариями, и у любого,
     * когда мы пришли без учётной записи.
     */
    result.createParams = createParamsIn(json)

    if (result.createParams == null) {
        // Молчаливое отсутствие метки увело в сторону на целый заход:
        // по журналу было не отличить «нельзя писать» от «нас не узнали».
        reportComposerShapeIn(json)
    }

    /**
     * Закрытые комментарии — отдельный случай, а не «их просто нет».
     *
     * Сервер об этом говорит сам и на языке человека: вместо списка
     * приезжает `messageRenderer` с надписью «Комментарии отключены».
     * Свою придумывать незачем — у сервера она и точнее, и переведена.
     *
     * Признак берём только при пустом списке: тот же рендерер попадается
     * и рядом с непустой лентой — например, с пометкой о сортировке.
     */
    if (comments.isEmpty()) {
        val said = refusalTextIn(json)

        if (!said.isNullOrEmpty()) {
            result.disabledMessage = said

            Log.d { "[YouTube/Комментарий] Сервер о списке: $said" }
        }
    }

    return result
}
