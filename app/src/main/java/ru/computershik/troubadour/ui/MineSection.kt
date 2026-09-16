package ru.computershik.troubadour.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.model.VideoItem
import ru.computershik.troubadour.net.Account
import ru.computershik.troubadour.net.Api
import ru.computershik.troubadour.net.Auth
import ru.computershik.troubadour.net.Download
import ru.computershik.troubadour.net.Downloads
import ru.computershik.troubadour.net.accountProfile
import ru.computershik.troubadour.net.historyPage
import ru.computershik.troubadour.net.myPlaylists
import ru.computershik.troubadour.ui.Metrics.dp

/** Строка сверху раздела: `YTMeBar` из оригинала. */
private const val ME_BAR = 44f

/** Кружок профиля: `YTMeAvatar`. */
private const val ME_AVATAR = 60f

/** Плитка полосы: `YTHistoryCard` шириной и `YTHistoryThumb` превью. */
private const val HISTORY_CARD = 160f
private const val HISTORY_THUMB = 90f

/** Высота полосы: превью плюс 57 под подписи. */
private const val STRIP_EXTRA = 57f

/**
 * «Моё» — порт `YTMeView`.
 *
 * Своя строка сверху: лупа и шестерёнка, прижатые вправо. Ниже профиль,
 * под ним три полосы, едущие вбок, — история, свои плейлисты, скачанное.
 * Общей верхней панели у раздела нет: оболочка её прячет, потому что
 * шестерёнка живёт здесь, а не там.
 *
 * Невошедшему раздел отдаётся входу целиком, вместе со строкой лупы
 * и шестерёнки: в оригинале вход — другая страница, и своей строки
 * у него нет.
 */
class MineSection(context: Context) : Shell.Section(context) {

    private lateinit var root: FrameLayout
    private lateinit var page: ScrollView
    private lateinit var body: MeBody

    private val login = LoginPanel(context)

    private var loaded = false

    override fun build(): ViewGroup {
        root = FrameLayout(context)

        body = MeBody(context)

        page = ScrollView(context)

        page.addView(
            body,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            page,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            login.body,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        /**
         * Вошли — раздел перечитывает себя сам.
         *
         * Оболочка об этом узнает своим чередом; здесь важно убрать
         * вход с глаз сразу, иначе он останется поверх профиля.
         */
        login.onDone = {
            loaded = false

            appear()
        }

        return root
    }

    override fun appear() {
        val signedIn = Auth.isSignedIn()

        page.visibility = if (signedIn) View.VISIBLE else View.GONE
        login.body.visibility = if (signedIn) View.GONE else View.VISIBLE

        if (!signedIn) {
            login.activate()

            return
        }

        login.deactivate()

        body.applyProfile()

        if (loaded) {
            return
        }

        loaded = true

        body.loadShelves()
    }

    override fun applyTheme() {
        root.setBackgroundColor(Theme.background)

        body.applyTheme()
        login.body.repaint()
    }

    override fun accountChanged() {
        loaded = false

        body.clear()

        appear()
    }
}

/**
 * Содержимое вкладки, размеченное руками.
 *
 * Порядок и числа — из `layoutSubviews` оригинала: строка сверху с полями
 * `0,10,0,24`, профиль с полем `16,6,16,16`, заголовки полос `16,0,16,8`
 * и по 20 под каждой полосой.
 */
class MeBody(context: Context) : ViewGroup(context) {

    private val searchButton = TappableView(context)
    private val searchIcon = ImageView(context)

    private val settingsButton = TappableView(context)
    private val settingsIcon = ImageView(context)

    private val avatar = RoundedImage(context)

    private val name = label(context, Fonts.semiBold, 20f, Theme.primaryText, 1)
    private val handle = label(context, Fonts.regular, 12f, Theme.secondaryText, 1)

    private val profileTouch = TappableView(context)

    private val historyHeader = TappableView(context)
    private val historyTitle = label(context, Fonts.semiBold, 18f, Theme.primaryText, 1)

    private val historyStrip = HorizontalScrollView(context)
    private val historyRow = TileRow(context)

    private val playlistsTitle = label(context, Fonts.semiBold, 18f, Theme.primaryText, 1)

    private val playlistsStrip = HorizontalScrollView(context)
    private val playlistsRow = TileRow(context)

    private val downloadsHeader = TappableView(context)
    private val downloadsTitle = label(context, Fonts.semiBold, 18f, Theme.primaryText, 1)

    private val downloadsStrip = HorizontalScrollView(context)
    private val downloadsRow = TileRow(context)

    init {
        searchIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        settingsIcon.scaleType = ImageView.ScaleType.FIT_CENTER

        searchButton.addView(searchIcon)
        searchButton.onTap = { Nav.push(SearchScreen(context)) }

        settingsButton.addView(settingsIcon)
        settingsButton.onTap = { Nav.push(SettingsScreen(context)) }

        addView(searchButton)
        addView(settingsButton)

        avatar.circular = true
        avatar.placeholderColor = Theme.avatarPlaceholder

        addView(avatar)
        addView(name)
        addView(handle)

        /**
         * Накладка выбора канала накрывает кружок вместе с именем.
         *
         * У одной записи Google бывает и личный канал, и бренд-каналы,
         * и детский; какой считать своим, сервер решает сам — и решает
         * не всегда так, как ждёт человек.
         */
        profileTouch.onTap = { AccountSheet(context).show() }

        addView(profileTouch)

        historyTitle.text = loc("История  ›")

        historyHeader.addView(historyTitle)
        historyHeader.onTap = { Nav.push(HistoryScreen(context)) }

        addView(historyHeader)

        addStrip(historyStrip, historyRow)

        playlistsTitle.text = loc("Плейлисты")

        addView(playlistsTitle)

        addStrip(playlistsStrip, playlistsRow)

        downloadsTitle.text = loc("Скачанные  ›")

        downloadsHeader.addView(downloadsTitle)
        downloadsHeader.onTap = { Nav.push(DownloadsScreen(context)) }

        addView(downloadsHeader)

        /**
         * Скачанное открывается файлом, а не сетевой страницей.
         *
         * Плитка тут та же, что у истории, и по нажатию она уходила
         * на страницу ролика — то есть качала заново уже скачанное.
         */
        downloadsRow.onPick = { item ->
            Nav.push(DownloadsScreen(context, item.videoId))
        }

        addStrip(downloadsStrip, downloadsRow)

        playlistsTitle.visibility = GONE
        playlistsStrip.visibility = GONE

        downloadsHeader.visibility = GONE
        downloadsStrip.visibility = GONE
    }

    private fun addStrip(strip: HorizontalScrollView, row: TileRow) {
        strip.isHorizontalScrollBarEnabled = false

        strip.addView(
            row,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        addView(strip)
    }

    fun clear() {
        historyRow.fill(emptyList())
        playlistsRow.fill(emptyList())
        downloadsRow.fill(emptyList())

        playlistsTitle.visibility = GONE
        playlistsStrip.visibility = GONE

        downloadsHeader.visibility = GONE
        downloadsStrip.visibility = GONE
    }

    fun applyProfile() {
        name.text = loc("Без имени")
        handle.text = ""

        async {
            val profile: Account? = Api.accountProfile()

            main {
                name.text = profile?.name ?: loc("Без имени")
                handle.text = profile?.handle ?: ""

                ImageLoader.loadInto(avatar, profile?.avatar, ME_AVATAR)
            }
        }
    }

    fun loadShelves() {
        async {
            val history = Api.historyPage(null)

            main {
                historyRow.fill(history?.items?.take(24) ?: emptyList())

                requestLayout()
            }
        }

        async {
            val playlists = Api.myPlaylists()

            main {
                val items = playlists ?: emptyList()

                playlistsRow.fill(items.take(24))

                // Полки без содержимого в оригинале нет вовсе — ни заголовка,
                // ни пустого места под ним.
                val shows = items.isNotEmpty()

                playlistsTitle.visibility = if (shows) VISIBLE else GONE
                playlistsStrip.visibility = playlistsTitle.visibility

                requestLayout()
            }
        }

        async {
            val all = Downloads.all()

            /**
             * Плитками показываем готовое, а раздел открываем, как только
             * в очереди что-то есть.
             *
             * Прежде раздел ждал первого скачанного файла — и человек,
             * начав закачку, не имел куда пойти посмотреть на неё
             * и отменить. Полоса в шторке была единственным следом.
             */
            val ready = Downloads.videos().filter { it.state == Download.DONE }

            main {
                downloadsRow.fill(ready.map { asItem(it) })

                val shows = all.isNotEmpty()

                downloadsHeader.visibility = if (shows) VISIBLE else GONE
                downloadsStrip.visibility = downloadsHeader.visibility

                requestLayout()
            }
        }
    }

    /** Скачанное показывается теми же плитками, что история. */
    private fun asItem(download: Download): VideoItem {
        val item = VideoItem()

        item.videoId = download.videoId
        item.title = download.title
        item.channelTitle = download.channelTitle
        item.thumbnail = download.thumbnail

        return item
    }

    fun applyTheme() {
        setBackgroundColor(Theme.background)

        searchIcon.setImageBitmap(Icons.icon("search"))
        settingsIcon.setImageBitmap(Icons.icon("pl_settings"))

        name.setTextColor(Theme.primaryText)
        handle.setTextColor(Theme.secondaryText)

        historyTitle.setTextColor(Theme.primaryText)
        playlistsTitle.setTextColor(Theme.primaryText)
        downloadsTitle.setTextColor(Theme.primaryText)

        avatar.placeholderColor = Theme.avatarPlaceholder

        historyRow.applyTheme()
        playlistsRow.applyTheme()
        downloadsRow.applyTheme()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)

        setMeasuredDimension(width, place(width, false))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        place(r - l, true)
    }

    /**
     * Одна раскладка на оба захода.
     *
     * Высота содержимого нужна ещё при обмере — иначе прокрутка не знает,
     * докуда ехать, — а числа в обоих случаях те же самые.
     */
    private fun place(width: Int, apply: Boolean): Int {
        // `Padding="0,10,0,24"` у содержимого.
        var y = dp(10f)

        val bar = dp(ME_BAR)
        val side = dp(38f)

        val right = width - dp(16f)

        if (apply) {
            settingsButton.frame(right - side, y + (bar - side) / 2, side, side)
            settingsIcon.frame(dp(7f), dp(7f), dp(24f), dp(24f))

            searchButton.frame(
                right - side - dp(6f) - side, y + (bar - side) / 2, side, side
            )
            searchIcon.frame(dp(8f), dp(8f), dp(22f), dp(22f))
        }

        y += bar + dp(4f)

        // Профиль: `Margin="16,6,16,16"`.
        y += dp(6f)

        val avatarSide = dp(ME_AVATAR)

        val textLeft = dp(16f) + avatarSide + dp(12f)
        val textWidth = maxOf(0, width - textLeft - dp(16f))

        val nameHeight = Metrics.lineHeight(Fonts.semiBold, 20f)
        val handleHeight = Metrics.lineHeight(Fonts.regular, 12f)

        val block = nameHeight + dp(3f) + handleHeight

        if (apply) {
            avatar.frame(dp(16f), y, avatarSide, avatarSide)

            profileTouch.frame(dp(16f), y, width - dp(32f), avatarSide)

            name.frame(textLeft, y + (avatarSide - block) / 2, textWidth, nameHeight)
            handle.frame(
                textLeft, y + (avatarSide - block) / 2 + nameHeight + dp(3f),
                textWidth, handleHeight
            )
        }

        y += avatarSide + dp(16f)

        val headerHeight = Metrics.lineHeight(Fonts.semiBold, 18f)
        val stripHeight = dp(HISTORY_THUMB + STRIP_EXTRA)

        // «История»: `Margin="16,0,16,8"`.
        if (apply) {
            historyHeader.frame(dp(16f), y, width - dp(32f), headerHeight)
            historyTitle.frame(0, 0, width - dp(32f), headerHeight)
        }

        y += headerHeight + dp(8f)

        if (apply) {
            historyStrip.frame(0, y, width, stripHeight)
        }

        // `Margin="0,0,0,20"` под полосой.
        y += stripHeight + dp(20f)

        if (playlistsTitle.visibility != GONE) {
            if (apply) {
                playlistsTitle.frame(dp(16f), y, width - dp(32f), headerHeight)
            }

            y += headerHeight + dp(8f)

            if (apply) {
                playlistsStrip.frame(0, y, width, stripHeight)
            }

            y += stripHeight + dp(20f)
        }

        /**
         * «Скачанные» — третьей полосой, под плейлистами.
         *
         * Заголовок здесь не подпись, а кнопка: по нему открывается весь
         * список. Поэтому под ним накладка на всю ширину, как у истории,
         * а не голая надпись, как у плейлистов.
         */
        if (downloadsHeader.visibility != GONE) {
            if (apply) {
                downloadsHeader.frame(dp(16f), y, width - dp(32f), headerHeight)
                downloadsTitle.frame(0, 0, width - dp(32f), headerHeight)
            }

            y += headerHeight + dp(8f)

            if (apply) {
                downloadsStrip.frame(0, y, width, stripHeight)
            }

            y += stripHeight + dp(20f)
        }

        return y + dp(24f)
    }
}

/** Ряд плиток внутри полосы: шаг `160 + 16`, отступ слева 16. */
class TileRow(context: Context) : ViewGroup(context) {

    private val tiles = ArrayList<HistoryTile>()

    /** Чем отзываться на нажатие; пусто — обычным переходом. */
    var onPick: ((VideoItem) -> Unit)? = null

    fun fill(items: List<VideoItem>) {
        while (tiles.size < items.size) {
            val tile = HistoryTile(context)

            tiles.add(tile)

            addView(tile)
        }

        for (index in tiles.indices) {
            val tile = tiles[index]

            if (index < items.size) {
                tile.visibility = VISIBLE
                tile.onPick = onPick
                tile.bind(items[index])
            } else {
                tile.visibility = GONE
            }
        }

        requestLayout()
    }

    fun applyTheme() {
        for (tile in tiles) {
            tile.applyTheme()
        }
    }

    private fun shown(): Int = tiles.count { it.visibility != GONE }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            dp(16f) + (dp(HISTORY_CARD) + dp(16f)) * shown(),
            dp(HISTORY_THUMB + STRIP_EXTRA)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val card = dp(HISTORY_CARD)
        val step = card + dp(16f)

        var at = 0

        for (tile in tiles) {
            if (tile.visibility == GONE) {
                continue
            }

            tile.frame(dp(16f) + step * at, 0, card, dp(HISTORY_THUMB + STRIP_EXTRA))

            at++
        }
    }
}

/**
 * Плитка полосы — порт `YTHistoryTile`.
 *
 * Превью 160×90 со скруглением карточек ленты, плашка длительности
 * в правом нижнем углу, название 13 в две строки и подпись 11 под ним.
 */
class HistoryTile(context: Context) : TappableView(context) {

    private val thumb = RoundedImage(context)

    private val badge = BadgeLabel(context)

    private val title = label(context, Fonts.regular, 13f, Theme.primaryText, 2)
    private val subtitle = label(context, Fonts.regular, 11f, Theme.secondaryText, 1)

    /** Полоска просмотра по нижнему краю кадра — та же, что у карточек ленты. */
    private val watchedTrack = View(context)
    private val watchedFill = View(context)

    private var watchedShare = 0.0

    /**
     * Своё дело по нажатию — вместо обычного перехода на страницу.
     *
     * Нужно полосе скачанного: там за плиткой лежит готовый файл,
     * и открывать вместо него сетевую страницу — значит качать заново
     * то, что уже скачано, да ещё и в дороге без сети.
     */
    var onPick: ((VideoItem) -> Unit)? = null

    private var item: VideoItem? = null

    init {
        highlights = false

        // Скругление то же, что у карточек ленты: `CornerRadius="8"`
        // в оригинале задан один на все превью.
        thumb.cornerRadius = Metrics.dpf(Metrics.THUMB_RADIUS)

        addView(thumb)

        addView(watchedTrack)
        addView(watchedFill)

        // Плашка тут мельче, чем у карточки ленты: `Padding="4,1"` и 10 кегль.
        badge.padHorizontal = Metrics.dpf(4f)
        badge.padVertical = Metrics.dpf(1f)

        badge.setTextSize(10f)

        addView(badge)
        addView(title)
        addView(subtitle)

        onTap = {
            val chosen = item

            if (chosen != null) {
                val own = onPick

                if (own != null) {
                    own(chosen)
                } else if (chosen.isPlaylist) {
                    Nav.openPlaylist(chosen.playlistId, chosen.title)
                } else {
                    Nav.openVideo(
                        chosen.videoId, chosen.title, null,
                        maxOf(0.0, chosen.resumeAt)
                    )
                }
            }
        }
    }

    fun bind(item: VideoItem) {
        this.item = item

        applyTheme()

        title.text = item.title

        /**
         * Вторая строка — «автор • давность». У подборки автора нет,
         * там остаётся одна давность.
         */
        val parts = ArrayList<String>(2)

        item.channelTitle?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        item.published?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }

        subtitle.text = parts.joinToString(" • ")

        badge.text = item.duration ?: ""

        /**
         * Плашка есть у обеих полос: у ролика в ней длительность,
         * у подборки — число роликов.
         */
        badge.visibility = if (item.duration.isNullOrEmpty()) GONE else VISIBLE

        watchedShare = if (item.isLive || item.isPlaylist) {
            0.0
        } else {
            maxOf(0.0, item.watchedShare)
        }

        val showsBar = watchedShare > 0

        watchedTrack.visibility = if (showsBar) VISIBLE else GONE
        watchedFill.visibility = if (showsBar) VISIBLE else GONE

        watchedTrack.setBackgroundColor(0x47FFFFFF)
        watchedFill.setBackgroundColor(Theme.BRAND_RED)

        ImageLoader.loadInto(thumb, item.thumbnail, HISTORY_CARD)

        requestLayout()
    }

    fun applyTheme() {
        thumb.placeholderColor = Theme.surfaceAlt

        title.setTextColor(Theme.primaryText)
        subtitle.setTextColor(Theme.secondaryText)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(HISTORY_CARD), dp(HISTORY_THUMB + STRIP_EXTRA))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val card = dp(HISTORY_CARD)
        val thumbHeight = dp(HISTORY_THUMB)

        thumb.frame(0, 0, card, thumbHeight)

        // Плашка: `Margin="0,0,4,4"`.
        val width = badge.badgeWidth()
        val height = badge.badgeHeight()

        badge.frame(card - width - dp(4f), thumbHeight - height - dp(4f), width, height)

        if (watchedTrack.visibility == VISIBLE) {
            val bar = dp(4f)
            val line = thumbHeight - bar

            watchedTrack.frame(0, line, card, bar)
            watchedFill.frame(0, line, (card * watchedShare).toInt(), bar)
        }

        // `Margin="0,6,0,0"` у названия и `0,3,0,0` у подписи под ним.
        title.frame(0, thumbHeight + dp(6f), card, dp(34f))
        subtitle.frame(0, thumbHeight + dp(6f) + dp(34f) + dp(3f), card, dp(14f))
    }
}

/**
 * История просмотра с разбивкой по дням.
 *
 * Заголовки дней («Сегодня», «На прошлой неделе») ставит сам сервер,
 * и их может не быть — тогда всё приходит одной безымянной пачкой.
 */
class HistoryScreen(context: Context) : Screen(context) {

    private lateinit var header: ScreenHeader
    private lateinit var feed: FeedList

    override fun build(root: FrameLayout) {
        val column = LinearLayout(context)

        column.orientation = LinearLayout.VERTICAL

        header = ScreenHeader(context, loc("История"))

        column.addView(header)

        feed = FeedList(context)

        feed.emptyText = "Смотреть пока нечего" // подпись: "Смотреть пока нечего"

        feed.source = { token ->
            val page = Api.historyPage(token)

            if (page == null) null else FeedList.Page(page.items, page.continuation)
        }

        column.addView(
            feed,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        root.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        feed.loadOnce()
    }

    override fun repaint() {
        super.repaint()

        header.repaint()
        feed.repaint()
    }
}
