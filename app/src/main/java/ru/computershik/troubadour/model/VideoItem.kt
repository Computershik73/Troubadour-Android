package ru.computershik.troubadour.model

import org.json.JSONObject
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.net.Json

/**
 * Карточка ролика в ленте.
 *
 * Порт `VideoCardItem` из UWP-версии — те же поля и та же сборка строки
 * метаданных. Отдельного класса под каждый вид рендерера нет намеренно:
 * `videoRenderer`, `gridVideoRenderer`, `compactVideoRenderer`,
 * `playlistVideoRenderer`, `lockupViewModel` и `reelItemRenderer` описывают
 * одно и то же разными словами, и разбор сводит их сюда.
 */
class VideoItem {

    var videoId: String? = null

    /**
     * Идентификатор подборки, если карточка — не ролик, а плейлист или микс.
     *
     * Миксы (`RD…`) и плейлисты (`PL…`, `LL…`, `UL…`, `OLA…`) приходят в тех
     * же списках и теми же рендерерами, что ролики, и раньше отсеивались
     * по длине идентификатора. Отсев был прав по сути — открывать их как
     * ролик нельзя, — но лишал ленту половины содержимого: в выдаче поиска
     * миксов заметно много, и на их месте зияла дыра.
     *
     * У таких карточек [videoId] пуст, а в [duration] лежит пометка, которую
     * прислал сервер: «Микс», «50 видео». Придумывать её не нужно и нельзя —
     * она уже размечена в ответе.
     */
    var playlistId: String? = null

    var title: String = ""
    var channelTitle: String? = null
    var channelId: String? = null
    var channelThumbnail: String? = null
    var thumbnail: String? = null

    /** Как пришло от сервера: «9:26», «LIVE» либо пусто у Shorts. */
    var duration: String? = null

    /** «1 тыс. просмотров» — уже готовой строкой, сервер сам её и склеивает. */
    var viewCount: String? = null

    /** «3 часа назад». */
    var published: String? = null

    /** Признак прямого эфира — плашка длительности у них другая. */
    var isLive: Boolean = false

    /**
     * Вертикальный ролик. Отмечается при разборе Shorts и нужен карточке:
     * у такого превью пропорция 9:16, и в место под 16:9 оно вписывалось
     * с обрезкой по бокам — от кадра оставалась узкая полоса посередине.
     */
    var isShort: Boolean = false

    /**
     * Пропуск на ленту Shorts, начинающуюся с этого ролика.
     *
     * Приходит вместе с карточкой и нужен, чтобы открыть листалку не
     * с начала, а отсюда: с ним запрос за лентой возвращает выбранный ролик
     * первым, а за ним — продолжение по вкусу сервера.
     */
    var shortsSequence: String? = null

    /** Карточка ведёт на подборку, а не на ролик. */
    val isPlaylist: Boolean
        get() = !playlistId.isNullOrEmpty() && videoId.isNullOrEmpty()

    /**
     * Вторая строка карточки: «автор • просмотры • давность».
     *
     * Склеивается здесь, а не в ячейке, ровно как `MetadataLine`
     * в UWP-версии: разделитель нужно ставить только между непустыми
     * кусками, иначе у ролика без счётчика просмотров строка начиналась бы
     * с висящей точки.
     */
    fun metadataLine(): String {
        val parts = ArrayList<String>(3)

        channelTitle?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        viewCount?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        published?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }

        return parts.joinToString(" • ")
    }

    companion object {

        /**
         * Имена рендереров, за которыми стоит ролик. Порт `VideoRendererMarkers`.
         *
         * `reelItemRenderer` и `shortsLockupViewModel` в списке **намеренно
         * отсутствуют**, хотя в оригинале UWP они есть. Там этот набор общий
         * на все поверхности, а Shorts разложены по своим — у поиска
         * отдельные вкладки «Видео», Shorts, «Плейлисты» и «Каналы». Здесь же
         * разбор один на всех, и с ними в обычную выдачу попадали вертикальные
         * карточки без длительности: в ответе поиска их два с половиной десятка.
         *
         * Раздел Shorts разбирает их сам — своим списком имён, [parseShortsFrom].
         */
        private val VIDEO_RENDERERS = setOf(
            "videoRenderer",
            "gridVideoRenderer",
            "compactVideoRenderer",
            "playlistVideoRenderer",
            "playlistPanelVideoRenderer",
            "lockupViewModel",
            "tileRenderer"
        )

        private val SHORTS_RENDERERS = setOf(
            "reelItemRenderer",
            "shortsLockupViewModel"
        )

        /**
         * Идентификатор подборки узнаётся по началу — так же, как
         * в UWP-версии (`ParsePlaylistLockupViewModel`, где проверяются
         * PL/VL/LL/WL). Сюда добавлены миксы `RD…`, которых в ленте и поиске
         * больше всего, и `UL…` с `OLA…`, которыми размечены подборки
         * YouTube Music.
         */
        private val PLAYLIST_PREFIXES = listOf(
            "RD", "PL", "VL", "LL", "WL", "UL", "OLA", "FL"
        )

        private fun looksLikePlaylistId(identifier: String?): Boolean {
            if (identifier == null || identifier.length < 10) {
                return false
            }

            return PLAYLIST_PREFIXES.any { identifier.startsWith(it) }
        }

        /**
         * Текст пункта строки метаданных плитки TV-клиента.
         *
         * Порт `ExtractTileLineItemText`: строка — это `lineRenderer`
         * со списком `items`, у каждого внутри `lineItemRenderer.text`.
         * Отрицательный номер отсчитывается с конца — так в оригинале,
         * потому что число пунктов гуляет: последний всегда давность,
         * третий с конца — просмотры.
         *
         * Наружу вынесен потому, что плитками описаны не только ролики,
         * но и подборки, а разбирают их разные места.
         */
        @JvmStatic
        fun tileLineText(line: JSONObject?, index: Int): String? {
            val items = Json.array(Json.obj(line, "lineRenderer"), "items") ?: return null

            val count = items.length()
            val resolved = if (index < 0) count + index else index

            if (resolved < 0 || resolved >= count) {
                return null
            }

            val item = Json.objectAt(items, resolved)

            return Json.renderedText(Json.obj(item, "lineItemRenderer"), "text")
        }

        /**
         * Превью у `lockupViewModel` — по известному пути, а не поиском
         * по имени.
         *
         * Ключ `image` в такой карточке не один: свои есть у значка «ещё»,
         * у листа с действиями, у наложений. Общий поиск брал первый
         * попавшийся, а порядок ключей в разборе JSON не обещан — у подборок
         * в выдаче поиска он и брал не то, и превью не показывалось вовсе.
         *
         * Ролик держит его в `contentImage.thumbnailViewModel`, подборка —
         * в `contentImage.collectionThumbnailViewModel.primaryThumbnail`
         * (стопка обложек, первая из которых и есть превью).
         */
        private fun lockupThumbnail(renderer: JSONObject, minWidth: Int): String? {
            val content = Json.obj(renderer, "contentImage") ?: return null

            var view = Json.obj(content, "thumbnailViewModel")

            if (view == null) {
                val stack = Json.obj(content, "collectionThumbnailViewModel")

                view = Json.obj(Json.obj(stack, "primaryThumbnail"), "thumbnailViewModel")
            }

            if (view == null) {
                return null
            }

            return Json.thumbnail(Json.obj(view, "image"), "sources", minWidth)
        }

        /** Сколько строк метаданных у карточки: по ним видно, что в них лежит. */
        private fun lockupMetadataRows(renderer: JSONObject): Int {
            val holder = Json.findFirst("contentMetadataViewModel", renderer, 600)

            return Json.array(holder, "metadataRows")?.length() ?: 0
        }

        /**
         * В какой строке метаданных лежит имя канала — и какое оно.
         *
         * Считать строки для этого нельзя, и это выяснилось дважды. На ленте
         * строк две (автор, затем числа), на странице канала одна — и в ней
         * не автор, а просмотры. Отсюда взялось правило «автор есть, только
         * если строк две». В истории же строка **одна, и в ней как раз
         * автор**: даты просмотренному ролику YouTube не показывает.
         * По прежнему правилу автор там терялся целиком, а в просмотры
         * попадало его же имя.
         *
         * Поэтому смотрим не на число строк, а на то, что в них: **имя канала
         * ведёт на канал.** У пункта с автором внутри лежит переход
         * `browseEndpoint` на `UC…`; у просмотров и давности ссылки нет
         * никакой. Признак не зависит ни от языка, ни от числа строк.
         *
         * Возвращает номер строки и найденное имя; номер -1 — не нашлось.
         */
        private fun lockupAuthorRow(renderer: JSONObject): Pair<Int, String?> {
            val holder = Json.findFirst("contentMetadataViewModel", renderer, 600)
            val rows = Json.array(holder, "metadataRows") ?: return -1 to null

            for (row in 0 until rows.length()) {
                val parts = Json.array(Json.objectAt(rows, row), "metadataParts") ?: continue

                for (index in 0 until parts.length()) {
                    val part = Json.objectAt(parts, index) ?: continue

                    val browse = Json.findFirst("browseEndpoint", part, 200)
                    val browseId = Json.text(browse, "browseId") ?: continue

                    if (!browseId.startsWith("UC")) {
                        continue
                    }

                    val text = Json.renderedText(part, "text")

                    if (text.isNullOrEmpty()) {
                        continue
                    }

                    return row to text
                }
            }

            return -1 to null
        }

        private fun lockupMetadataPart(renderer: JSONObject, row: Int, part: Int): String? {
            val holder = Json.findFirst("contentMetadataViewModel", renderer, 600)
            val rows = Json.array(holder, "metadataRows") ?: return null

            if (row < 0 || row >= rows.length()) {
                return null
            }

            val parts = Json.array(Json.objectAt(rows, row), "metadataParts") ?: return null

            if (part < 0 || part >= parts.length()) {
                return null
            }

            return Json.renderedText(Json.objectAt(parts, part), "text")
        }

        /**
         * Плитка TV-клиента — порт `ParseTileRenderer`.
         *
         * Разбирается отдельно, а не общим ходом, потому что общего
         * с остальными рендерерами у неё почти ничего: ни `title`,
         * ни `lengthText`, ни `thumbnail` на своих местах нет. Именно этим
         * и объяснялась «Главная» из пустых карточек: лента у вошедшего
         * приходит от TV-клиента, а он присылает только плитки.
         *
         * Плитка ведёт либо на ролик (`watchEndpoint`), либо на микс
         * (`watchPlaylistEndpoint`) — и во втором случае в ней **тоже есть**
         * идентификатор ролика, с которого микс начинается. Карточка без него
         * не карточка: в оригинале такая плитка отбрасывается.
         */
        private fun fromTile(tile: JSONObject): VideoItem? {
            val onSelect = Json.obj(tile, "onSelectCommand")

            val watch = Json.obj(onSelect, "watchEndpoint")
                ?: Json.obj(onSelect, "watchPlaylistEndpoint")

            val videoId = Json.text(watch, "videoId")

            if (videoId == null || videoId.length != 11) {
                return null
            }

            val item = VideoItem()

            item.videoId = videoId

            // Плитка TV-клиента ведёт на Shorts тем же `reelWatchEndpoint`.
            if (Json.findFirst("reelWatchEndpoint", tile, 400) != null) {
                item.isShort = true
            }

            /**
             * Идентификатор подборки сохраняется, но карточка остаётся
             * карточкой ролика: у микса он нужен, чтобы очередь пережила
             * нажатие. `WL` и `LL` отбрасываются — это «Посмотреть позже»
             * и «Понравившиеся», личные списки, а не подборка
             * (`IsReservedPersonalPlaylistId`).
             */
            val playlistId = Json.text(watch, "playlistId")

            if (playlistId != null &&
                !playlistId.startsWith("WL") && !playlistId.startsWith("LL")
            ) {
                item.playlistId = playlistId
            }

            val metadata = Json.obj(Json.obj(tile, "metadata"), "tileMetadataRenderer")

            item.title = Json.renderedText(metadata, "title") ?: ""

            val lines = Json.array(metadata, "lines")

            // lines[0] — автор; lines[1] — просмотры и давность, отсчитываемые
            // с конца, потому что число пунктов в ней разное.
            if (lines != null && lines.length() > 0) {
                item.channelTitle = tileLineText(Json.objectAt(lines, 0), 0)
            }

            if (lines != null && lines.length() > 1) {
                val second = Json.objectAt(lines, 1)

                item.published = tileLineText(second, -1)
                item.viewCount = tileLineText(second, -3)
            }

            val header = Json.obj(Json.obj(tile, "header"), "tileHeaderRenderer")
            val badge = Json.findFirst("thumbnailOverlayTimeStatusRenderer", header, 400)

            item.duration = Json.renderedText(badge, "text")

            if (Json.text(badge, "style") == "LIVE") {
                item.isLive = true
            }

            val browse = Json.findFirst("browseEndpoint", tile, 400)
            val browseId = Json.text(browse, "browseId")

            if (browseId != null && browseId.startsWith("UC")) {
                item.channelId = browseId
            }

            /**
             * Превью собирается из идентификатора, а не берётся из ответа, —
             * так же поступает `BuildMqThumbnailUrl` в оригинале.
             *
             * В iOS-версии для этого была вторая причина: у плитки картинка
             * приходит в WebP, который ImageIO до iOS 14 не читает. Здесь
             * WebP умеет декодировать сама система с Android 4.0, но адрес
             * у плитки к тому же подписанный и недолговечный, — поэтому
             * поведение то же.
             */
            item.thumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

            item.channelThumbnail =
                Json.thumbnail(tile, "channelThumbnailSupportedRenderers", 88)
                    ?: Json.thumbnail(tile, "channelThumbnail", 88)

            return item
        }

        /** Разбор одного рендерера в карточку; null, если это не ролик. */
        @JvmStatic
        fun fromRenderer(renderer: JSONObject?): VideoItem? {
            if (renderer == null) {
                return null
            }

            var videoId = Json.text(renderer, "videoId")

            // `lockupViewModel` — новая форма: идентификатор лежит в contentId.
            if (videoId == null) {
                videoId = Json.text(renderer, "contentId")
            }

            // `tileRenderer` прячет его в onSelectCommand → watchEndpoint.
            if (videoId == null) {
                val watch = Json.findFirst("watchEndpoint", renderer, 400)

                videoId = Json.text(watch, "videoId")
            }

            /**
             * Идентификатор ролика — ровно одиннадцать знаков; это не догадка,
             * а формат: одиннадцать символов из base64url, других длин
             * не бывает.
             *
             * Всё, что длиннее, — подборка: `lockupViewModel` описывает миксы
             * и плейлисты теми же словами, что ролики, и кладёт в `contentId`
             * то `PLp9rb04py…`, то `RDQM…`. Раньше такие карточки просто
             * выбрасывались — иначе превью запрашивалось по несуществующему
             * адресу `i.ytimg.com/vi/PL…/hqdefault.jpg`, а нажатие открывало
             * пустой плеер. Теперь они остаются, но помечены как подборки
             * и открываются своим экраном.
             */
            var playlistId: String? = null

            if (videoId == null || videoId.length != 11) {
                var candidate = Json.text(renderer, "playlistId")

                if (candidate == null) {
                    val watch = Json.findFirst("watchEndpoint", renderer, 400)

                    candidate = Json.text(watch, "playlistId")
                }

                if (candidate == null) {
                    candidate = videoId
                }

                /**
                 * `VL` — это приставка, которой размечен запрос страницы
                 * плейлиста (`browseId = "VL" + playlistId`), а не сам
                 * идентификатор. Снимаем её, иначе в запрос ушло бы «VLVLPL…».
                 */
                if (candidate != null && candidate.startsWith("VL")) {
                    candidate = candidate.substring(2)
                }

                if (!looksLikePlaylistId(candidate)) {
                    return null
                }

                playlistId = candidate
                videoId = null
            }

            val item = VideoItem()

            item.videoId = videoId
            item.playlistId = playlistId

            /**
             * Вертикальный ли это ролик — по тому, куда он ведёт.
             *
             * Признак надёжнее прочих: у Shorts переход описан
             * `reelWatchEndpoint`, а не `watchEndpoint`, и так у всех
             * поверхностей разом. Судить по отсутствию длительности нельзя —
             * её нет и у прямых эфиров; по пропорции превью тоже: она
             * приходит не всегда.
             *
             * Помечаем даже там, где Shorts не прячут: карточке этот признак
             * нужен и сам по себе.
             */
            if (Json.findFirst("reelWatchEndpoint", renderer, 400) != null) {
                item.isShort = true
            }

            // Название: у разных рендереров оно то в `title`, то в `headline`,
            // то внутри `metadata` у новых view-model.
            var title = Json.renderedText(renderer, "title")

            if (title == null) title = Json.renderedText(renderer, "headline")
            if (title == null) title = Json.text(renderer, "title")

            if (title == null) {
                val meta = Json.findFirst("lockupMetadataViewModel", renderer, 400)

                title = Json.renderedText(meta, "title")
            }

            item.title = title ?: ""

            /**
             * Автор. `longBylineText` предпочтительнее `shortBylineText`:
             * второй у части рендереров содержит не имя канала, а число
             * просмотров. Порядок тот же, что в `ParseCompactVideoRenderer`
             * UWP-версии.
             */
            var channel = Json.renderedText(renderer, "longBylineText")

            if (channel == null) channel = Json.renderedText(renderer, "shortBylineText")
            if (channel == null) channel = Json.renderedText(renderer, "ownerText")

            /**
             * Новая разметка: автора узнаём по ссылке на канал, а не
             * по счёту строк. Подробности — над [lockupAuthorRow].
             */
            val (authorRow, linked) = lockupAuthorRow(renderer)

            if (channel == null && !linked.isNullOrEmpty()) {
                channel = linked
            }

            item.channelTitle = channel

            // Идентификатор канала — в navigationEndpoint у имени автора.
            val browse = Json.findFirst("browseEndpoint", renderer, 400)
            val browseId = Json.text(browse, "browseId")

            if (browseId != null && browseId.startsWith("UC")) {
                item.channelId = browseId
            }

            item.thumbnail = Json.thumbnail(renderer, "thumbnail", 480)

            if (item.thumbnail == null) {
                item.thumbnail = lockupThumbnail(renderer, 480)
            }

            if (item.thumbnail == null) {
                // У новых view-model превью лежит глубже, но всё так же
                // под ключом `sources` — общий поиск его находит.
                val image = Json.findFirst("image", renderer, 400)

                item.thumbnail = Json.thumbnail(image, "sources", 480)
            }

            if (item.thumbnail == null && videoId != null) {
                // Последний ход: у i.ytimg.com превью лежит по предсказуемому
                // адресу. Это не догадка — так же поступала UWP-версия.
                // Для подборки такого адреса нет: у неё превью только своё.
                item.thumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            }

            item.channelThumbnail =
                Json.thumbnail(renderer, "channelThumbnailSupportedRenderers", 88)
                    ?: Json.thumbnail(renderer, "channelThumbnail", 88)

            item.duration = Json.renderedText(renderer, "lengthText")
                ?: Json.renderedText(renderer, "thumbnailOverlayTimeStatusRenderer")

            /**
             * Эфир узнаётся по значку, а не по отсутствию длительности:
             * у Shorts длительности тоже нет, а эфиром они не являются.
             */
            val badge = Json.findFirst("thumbnailOverlayTimeStatusRenderer", renderer, 400)

            if (Json.text(badge, "style") == "LIVE") {
                item.isLive = true
                item.duration = "LIVE"
            }

            if (item.duration == null) {
                item.duration = Json.renderedText(badge, "text")
            }

            // У новых view-model длительность — в значке поверх превью.
            if (item.duration == null && playlistId == null) {
                val overlay = Json.findFirst("thumbnailBadgeViewModel", renderer, 600)

                item.duration = Json.text(overlay, "text")
            }

            /**
             * Пометка подборки — «Микс», «50 видео» — стоит на месте
             * длительности.
             *
             * Её не нужно собирать самому: сервер присылает её готовой
             * строкой. В новой разметке это `thumbnailBadgeViewModel.text`,
             * в старой — нижняя полоса превью
             * `thumbnailOverlayBottomPanelRenderer.text` либо
             * `videoCountShortText` у `playlistRenderer`.
             */
            if (item.duration.isNullOrEmpty() && playlistId != null) {
                val mark = Json.findFirst("thumbnailBadgeViewModel", renderer, 600)

                item.duration = Json.text(mark, "text")

                if (item.duration == null) {
                    val panel = Json.findFirst(
                        "thumbnailOverlayBottomPanelRenderer", renderer, 600
                    )

                    item.duration = Json.renderedText(panel, "text")
                }

                if (item.duration == null) {
                    item.duration = Json.renderedText(renderer, "videoCountShortText")
                }

                if (item.duration == null) {
                    item.duration = Json.renderedText(renderer, "videoCountText")
                }
            }

            item.viewCount = Json.renderedText(renderer, "shortViewCountText")
                ?: Json.renderedText(renderer, "viewCountText")

            /**
             * Просмотры и давность — в последней строке, **кроме той,
             * где автор**.
             *
             * Оговорка не лишняя: в истории строка всего одна и занята
             * автором. Прежний разбор брал последнюю строку всегда,
             * и в просмотры попадало имя канала — то же самое, что уже стоит
             * автором.
             */
            var last = lockupMetadataRows(renderer) - 1

            if (last == authorRow) {
                last -= 1
            }

            if (last >= 0 && item.viewCount == null) {
                item.viewCount = lockupMetadataPart(renderer, last, 0)
            }

            item.published = Json.renderedText(renderer, "publishedTimeText")

            if (item.published == null && last >= 0) {
                item.published = lockupMetadataPart(renderer, last, 1)
            }

            return item
        }

        /**
         * Собирает все карточки из ответа InnerTube, какой бы ни была
         * его форма.
         *
         * Ищет по всему дереву известные имена рендереров — так же поступала
         * UWP-версия (`VideoRendererMarkers`), и по той же причине: ответ
         * у одного и того же экрана меняет форму от клиента к клиенту
         * и от недели к неделе, а список роликов в нём всё равно узнаётся
         * по имени узла.
         *
         * Обход **один на все имена сразу**. Раньше здесь был поиск в цикле
         * по именам, и каждый проход отсчитывал свой потолок узлов с нуля —
         * то есть дальше первых пяти тысяч не заглядывал ни один. На ленте
         * подписок это резало выдачу до первой полки: ответ TV-клиента
         * больше мегабайта, ролики в нём разложены по полке на канал,
         * и в ленту попадал один канал.
         *
         * Потолок поднят, потому что теперь он один на всё: обойти дерево
         * целиком дешевле, чем семь раз обойти его начало. Обход идёт
         * в фоне, так что даже на слабом устройстве это не задерживает экран.
         */
        @JvmStatic
        fun parseFrom(tree: Any?): List<VideoItem> {
            val items = ArrayList<VideoItem>()
            val seen = HashSet<String>()

            for (hit in Json.findAllOfAny(VIDEO_RENDERERS, tree, 200000)) {
                // Плитка разбирается своим ходом: общего с остальными
                // рендерерами у неё нет ничего, кроме того, что за ней тоже
                // стоит ролик.
                val item = if (hit.name == "tileRenderer") {
                    fromTile(hit.node)
                } else {
                    fromRenderer(hit.node)
                } ?: continue

                /**
                 * Отсев вертикальных — здесь, и это выбор места, а не удобство.
                 *
                 * [parseFrom] — единственная дверь, через которую ролики
                 * попадают во **все** обычные ленты: «Главную», подписки,
                 * канал, подборки, историю и похожие. Отсеивать по местам
                 * значило бы завести пять одинаковых проверок и забыть
                 * шестую — ту, которую заведут позже. Здесь забыть нельзя.
                 *
                 * Разбор модели за настройкой обычно не ходит, и это
                 * исключение оправдано ровно тем же: полнота тут важнее
                 * чистоты слоя.
                 */
                if (item.isShort && Settings.hidesShorts) {
                    continue
                }

                // Один и тот же ролик приходит и как `videoRenderer`,
                // и внутри соседнего блока: без этого лента шла бы с повторами.
                val key = item.videoId?.takeIf { it.isNotEmpty() } ?: item.playlistId

                if (key == null || !seen.add(key)) {
                    continue
                }

                items.add(item)
            }

            return items
        }

        /**
         * Только вертикальные ролики — `reelItemRenderer`
         * и `shortsLockupViewModel`.
         *
         * Общий разбор их намеренно не берёт: в обычную выдачу они попадали
         * бы карточками без длительности. А вот для вкладки Shorts в поиске
         * нужны ровно они.
         */
        @JvmStatic
        fun parseShortsFrom(tree: Any?): List<VideoItem> {
            val items = ArrayList<VideoItem>()
            val seen = HashSet<String>()

            for (hit in Json.findAllOfAny(SHORTS_RENDERERS, tree, 200000)) {
                val node = hit.node
                val item = fromRenderer(node) ?: VideoItem()

                /**
                 * У новой модели `shortsLockupViewModel` идентификатор лежит
                 * не там, где у прочих рендереров, — он в `reelWatchEndpoint`
                 * внутри команды нажатия. Общий разбор его не находит,
                 * поэтому достаём отдельно.
                 */
                val endpoint = Json.findFirst("reelWatchEndpoint", node, 800)

                if (item.videoId?.length != 11) {
                    item.videoId = Json.text(endpoint, "videoId")
                }

                val videoId = item.videoId

                if (videoId == null || videoId.length != 11) {
                    continue
                }

                /**
                 * Пропуск на ленту, начинающуюся с этого ролика.
                 *
                 * Сервер кладёт его в ту же команду нажатия: с ним запрос
                 * `reel_watch_sequence` возвращает не случайную ленту, а ту,
                 * что начинается отсюда, — ровно так листалка и открывается
                 * из выдачи поиска в оригинале.
                 */
                item.shortsSequence = Json.text(endpoint, "sequenceParams")

                /**
                 * Подписи у той же модели лежат в `overlayMetadata`: название
                 * в `primaryText`, просмотры в `secondaryText`. Общий разбор
                 * ищет их там, где они у обычных карточек, и не находит ничего.
                 *
                 * Добирались они прежде только у карточек без идентификатора —
                 * то есть у тех, что и так разбирались наполовину. А стоило
                 * идентификатору найтись, как всё прочее оставалось пустым:
                 * в выдаче поиска по Shorts это выглядело как ряд картинок
                 * без единой надписи.
                 */
                if (item.title.isEmpty() || item.viewCount.isNullOrEmpty()) {
                    val overlay = Json.findFirst("overlayMetadata", node, 800)

                    if (item.title.isEmpty()) {
                        item.title = Json.renderedText(overlay, "primaryText") ?: ""
                    }

                    if (item.viewCount.isNullOrEmpty()) {
                        item.viewCount = Json.renderedText(overlay, "secondaryText")
                    }

                    /**
                     * Если разметку опять переложат — ищем те же поля где
                     * угодно внутри карточки. Имена у них редкие, спутать
                     * не с чем, а обёртка вокруг них за последний год
                     * менялась дважды.
                     */
                    if (item.title.isEmpty()) {
                        item.title = Json.renderedValue(
                            Json.findFirst("primaryText", node, 800)
                        ) ?: ""
                    }

                    if (item.viewCount.isNullOrEmpty()) {
                        item.viewCount = Json.renderedValue(
                            Json.findFirst("secondaryText", node, 800)
                        )
                    }
                }

                if (item.title.isEmpty()) {
                    item.title = Json.renderedText(node, "headline") ?: "Shorts"
                }

                if (item.thumbnail.isNullOrEmpty()) {
                    item.thumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                }

                if (!seen.add(videoId)) {
                    continue
                }

                item.isShort = true

                items.add(item)
            }

            return items
        }

        /**
         * Только каналы — `channelRenderer` из вкладки «Каналы» в поиске.
         *
         * Общий разбор их не берёт и брать не должен: у канала нет ни ролика,
         * ни подборки, и карточка ему нужна другая. Поля заполняются
         * по смыслу: [title] — имя канала, [channelTitle] — собачка,
         * [viewCount] — число подписчиков, [published] — строка описания,
         * [thumbnail] — кружок.
         */
        @JvmStatic
        fun parseChannelsFrom(tree: Any?): List<VideoItem> {
            val items = ArrayList<VideoItem>()
            val seen = HashSet<String>()

            for (node in Json.findAll("channelRenderer", tree, 200000)) {
                val channelId = Json.text(node, "channelId") ?: continue

                if (!seen.add(channelId)) {
                    continue
                }

                val item = VideoItem()

                item.channelId = channelId
                item.title = Json.renderedText(node, "title") ?: ""
                item.thumbnail = Json.thumbnail(node, "thumbnail", 176)
                item.channelThumbnail = item.thumbnail

                /**
                 * Имена полей у канала переставлены местами, и это не описка:
                 * число подписчиков сервер кладёт в `videoCountText`,
                 * а собачку — в `subscriberCountText`. Так и приходит,
                 * проверено по ответу.
                 */
                item.viewCount = Json.renderedText(node, "videoCountText")
                item.channelTitle = Json.renderedText(node, "subscriberCountText")
                item.published = Json.renderedText(node, "descriptionSnippet")

                items.add(item)
            }

            return items
        }
    }
}
