package ru.computershik.troubadour.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.locF
import ru.computershik.troubadour.model.VideoItem
import ru.computershik.troubadour.net.Api
import ru.computershik.troubadour.net.Auth
import ru.computershik.troubadour.net.ChannelPage
import ru.computershik.troubadour.net.SearchKind
import ru.computershik.troubadour.net.channel
import ru.computershik.troubadour.net.subscriptions
import ru.computershik.troubadour.net.channelTab
import ru.computershik.troubadour.net.playlist
import ru.computershik.troubadour.net.search
import ru.computershik.troubadour.net.searchSuggestions
import ru.computershik.troubadour.net.setSubscribed
import ru.computershik.troubadour.net.subscriptionsFeed
import ru.computershik.troubadour.ui.Metrics.dp

/**
 * Шапка экрана: стрелка «назад» и заголовок.
 *
 * В оригинале такие экраны рисуют шапку сами — навигационная полоса
 * там невидима. Здесь то же самое, и по той же причине: своя шапка
 * красится темой приложения, а не системной.
 */
class ScreenHeader(context: Context, title: String) : LinearLayout(context) {

    private val label = label(context, Fonts.semiBold, 18f, Theme.primaryText, 1)
    private val icon = ImageView(context)

    var text: String
        get() = label.text.toString()
        set(value) {
            label.text = value
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        setBackgroundColor(Theme.background)

        val back = TappableView(context)

        icon.setImageBitmap(Icons.icon("pl_back"))
        icon.scaleType = ImageView.ScaleType.FIT_CENTER

        back.addView(
            icon,
            FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER)
        )

        back.onTap = { Nav.pop() }

        addView(back, LayoutParams(dp(48f), dp(48f)))

        label.text = title

        addView(
            label,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        /**
         * Высота как у верхней панели оболочки — 56, **плюс** строка
         * состояния: панель уходит под неё, и отступ этот занимает
         * место, а не отъедает его у содержимого.
         */
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(Metrics.NAV_BAR_HEIGHT) + statusBarHeight()
        )

        setPadding(0, statusBarHeight(), dp(16f), 0)
    }

    fun repaint() {
        setBackgroundColor(Theme.background)

        label.setTextColor(Theme.primaryText)

        icon.setImageBitmap(Icons.icon("pl_back"))
    }
}

/**
 * Канал: шапка с обложкой и кружком, полоса разделов, лента роликов.
 *
 * Разделы у каналов разные, и метки к ним непрозрачные — вычислить их
 * нельзя, только взять из перечня, который сервер прислал вместе
 * со страницей.
 */
class ChannelScreen(
    context: Context,
    private val channelId: String,
    private var title: String
) : Screen(context) {

    private lateinit var header: ScreenHeader
    private lateinit var feed: FeedList

    private var page: ChannelPage? = null
    private var params: String? = null

    companion object {
        /** Метка нашего, дописанного раздела — сервер таких не присылает. */
        private const val ABOUT = "troubadour-about"
    }

    override fun build(root: FrameLayout) {
        val column = LinearLayout(context)

        column.orientation = LinearLayout.VERTICAL

        header = ScreenHeader(context, title)

        column.addView(header)

        feed = FeedList(context)

        feed.source = { token -> loadPage(token) }

        feed.emptyText = "У канала пока нет роликов" // подпись: "У канала пока нет роликов"

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

    private fun loadPage(token: String?): FeedList.Page? {
        /**
         * В «О канале» списка нет вовсе — только описание. Продолжения
         * тоже нет: дописывать нечего.
         */
        if (params == ABOUT) {
            val about = page?.description ?: ""

            main {
                if (about.isEmpty()) {
                    feed.showMessage(loc("Автор ничего о себе не написал"))
                } else {
                    feed.showText(about)
                }
            }

            return FeedList.Page(emptyList(), null)
        }

        if (!token.isNullOrEmpty()) {
            val more = Api.browseContinuation(token) ?: return null

            return FeedList.Page(more.items, more.continuation)
        }

        val fresh = if (params.isNullOrEmpty()) {
            Api.channelTab(channelId, "videos")
        } else {
            Api.channel(channelId, params)
        } ?: return null

        page = fresh

        main {
            fresh.title?.let {
                title = it

                header.text = it
            }

            buildHeader(fresh)
        }

        return FeedList.Page(fresh.items, fresh.continuation)
    }

    /**
     * Шапка канала: обложка, кружок 176, имя, собачка и подписчики,
     * кнопка подписки, полоса разделов.
     */
    private fun buildHeader(fresh: ChannelPage) {
        val column = LinearLayout(context)

        column.orientation = LinearLayout.VERTICAL

        if (!fresh.banner.isNullOrEmpty()) {
            val banner = RoundedImage(context)

            banner.cornerRadius = 0f
            banner.placeholderColor = Theme.surfaceAlt

            ImageLoader.loadInto(banner, fresh.banner, Metrics.points(feed.width))

            /**
             * Обложка пропорцией примерно два к одному — в оригинале
             * у неё жёсткая высота 176 при ширине телефона. Переносим
             * пропорцию, а не число: на планшете жёсткие 176 превратили бы
             * обложку в узкую полосу.
             */
            column.addView(
                banner,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    minOf(feed.width * 176 / 360, dp(280f))
                )
            )
        }

        val row = LinearLayout(context)

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val avatar = RoundedImage(context)

        avatar.circular = true
        avatar.placeholderColor = Theme.avatarPlaceholder

        ImageLoader.loadInto(avatar, fresh.avatar, 88f)

        row.addView(avatar, LinearLayout.LayoutParams(dp(88f), dp(88f)))

        val names = LinearLayout(context)

        names.orientation = LinearLayout.VERTICAL

        val name = label(context, Fonts.bold, 20f, Theme.primaryText, 1)
        val about = label(context, Fonts.regular, 13f, Theme.secondaryText, 2)

        name.text = fresh.title ?: title

        about.text = listOfNotNull(
            fresh.handle?.takeIf { it.isNotEmpty() },
            fresh.subscribers?.takeIf { it.isNotEmpty() }
        ).joinToString(" • ")

        names.addView(name)
        names.addView(about)

        val nameParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )

        nameParams.leftMargin = dp(12f)

        row.addView(names, nameParams)

        column.addView(row, headerParams(dp(16f), dp(12f), dp(16f), dp(12f)))

        buildSubscribe(fresh)?.let {
            column.addView(it, headerParams(dp(16f), 0, dp(16f), dp(12f)))
        }

        if (fresh.sections.isNotEmpty()) {
            column.addView(buildSections(fresh))
        }

        column.layoutParams = android.widget.AbsListView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        feed.header = column
    }

    private fun headerParams(
        left: Int, top: Int, right: Int, bottom: Int
    ): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        params.setMargins(left, top, right, bottom)

        return params
    }

    private fun buildSubscribe(fresh: ChannelPage): View? {
        val subscribed = fresh.subscribed ?: return null

        val button = PillButton(context)

        val pill = button.pill
        val text = button.title

        button.setPadding(dp(14f), dp(7f), dp(14f), dp(7f))

        var now = subscribed

        fun paint() {
            text.text = if (now) loc("Вы подписаны") else loc("Подписаться")

            pill.fillColor = if (now) Theme.surface else Theme.primaryActionBackground

            text.setTextColor(
                if (now) Theme.primaryText else Theme.primaryActionForeground
            )
        }

        paint()

        button.onTap = {
            val want = !now

            now = want

            paint()

            async {
                if (!Api.setSubscribed(want, channelId)) {
                    main {
                        now = !want

                        paint()

                        Toast.show(context, loc("Не получилось"))
                    }
                } else {
                    Notify.post(Notify.SUBSCRIPTIONS)
                }
            }
        }

        return button
    }

    private fun buildSections(fresh: ChannelPage): View {
        val scroll = HorizontalScrollView(context)

        scroll.isHorizontalScrollBarEnabled = false

        val row = LinearLayout(context)

        row.orientation = LinearLayout.HORIZONTAL

        /**
         * «О канале» дописываем сами: раздела с таким именем сервер
         * не присылает, хотя в UWP-версии он есть — там его так же
         * собирают из описания.
         */
        val sections = fresh.sections +
            ru.computershik.troubadour.net.ChannelSection(loc("О канале"), ABOUT)

        for (section in sections) {
            val chip = ChipView(context)

            chip.caption = section.title
            chip.chosen = section.params == params

            chip.onTap = {
                params = section.params

                feed.reload()
            }

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(Metrics.CHIP_HEIGHT)
            )

            params.leftMargin = dp(6f)

            row.addView(chip, params)
        }

        scroll.addView(
            row,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(Metrics.CHIPS_BAR_HEIGHT)
            )
        )

        return scroll
    }

    override fun repaint() {
        super.repaint()

        header.repaint()
        feed.repaint()
    }
}

/** Подборка — плейлист или микс. Шапка проще, чем у канала. */
class PlaylistScreen(
    context: Context,
    private val playlistId: String,
    private var title: String
) : Screen(context) {

    private lateinit var header: ScreenHeader
    private lateinit var feed: FeedList

    override fun build(root: FrameLayout) {
        val column = LinearLayout(context)

        column.orientation = LinearLayout.VERTICAL

        header = ScreenHeader(context, title)

        column.addView(header)

        feed = FeedList(context)

        feed.source = { token ->
            if (!token.isNullOrEmpty()) {
                val more = Api.browseContinuation(token)

                if (more == null) null else FeedList.Page(more.items, more.continuation)
            } else {
                val page = Api.playlist(playlistId)

                if (page == null) {
                    null
                } else {
                    main {
                        page.title?.let {
                            title = it

                            header.text = it
                        }
                    }

                    FeedList.Page(page.items, page.continuation)
                }
            }
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

/**
 * Строка списка под полем ввода — прежний запрос либо подсказка сервера.
 *
 * Числа из `Searching.xaml`: высота 52, значок слева, текст с отступа 56,
 * крестик 48 у правого края. Крестик стоит только у истории: подсказку
 * сервера забывать неоткуда.
 */
private class HintRow(context: Context) : TappableView(context) {

    private val glyph = ImageView(context)
    private val text = label(context, Fonts.regular, 15f, Theme.primaryText, 1)
    private val forget = TappableView(context)
    private val cross = label(context, Fonts.regular, 18f, Theme.mutedText, 1)

    private var value = ""
    private var history = false

    /** Кого забыть — сообщается запросом, а не местом в списке. */
    var onForget: ((String) -> Unit)? = null

    /** По какому запросу нажали.

     * Строка ловит нажатие сама, а не через `setOnItemClickListener`
     * списка. Тот молчал: внутри строки есть своя нажимаемая часть —
     * крестик, — и список такую строку нажимаемой уже не считает.
     * Со стороны это выглядело так, будто прошлые запросы неживые.
     */
    var onPick: ((String) -> Unit)? = null

    init {
        highlights = true

        onTap = { onPick?.invoke(value) }

        glyph.scaleType = ImageView.ScaleType.FIT_CENTER

        cross.text = "×"
        cross.gravity = Gravity.CENTER

        forget.addView(
            cross,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        forget.onTap = { onForget?.invoke(value) }

        addView(glyph)
        addView(text)
        addView(forget)
    }

    fun bind(query: String, fromHistory: Boolean) {
        value = query
        history = fromHistory

        text.text = query
        text.setTextColor(Theme.primaryText)

        cross.setTextColor(Theme.mutedText)

        // История помечена значком повтора, подсказка — лупой.
        glyph.setImageBitmap(Icons.icon(if (fromHistory) "pl_replay" else "search"))

        forget.visibility = if (fromHistory) VISIBLE else GONE

        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = dp(52f)

        setMeasuredDimension(width, height)

        val right = if (history) dp(48f) else dp(12f)

        glyph.measure(
            MeasureSpec.makeMeasureSpec(dp(20f), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(20f), MeasureSpec.EXACTLY)
        )

        text.measure(
            MeasureSpec.makeMeasureSpec(maxOf(0, width - dp(56f) - right), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )

        forget.measure(
            MeasureSpec.makeMeasureSpec(dp(48f), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        glyph.frame(dp(18f), (height - dp(20f)) / 2, dp(20f), dp(20f))

        val right = if (history) dp(48f) else dp(12f)

        text.frame(dp(56f), 0, maxOf(0, width - dp(56f) - right), height)

        forget.frame(width - dp(48f), 0, dp(48f), height)
    }
}

/**
 * Поиск: поле ввода, подсказки и выдача четырёх видов.
 *
 * Таблетка Shorts пропадает вместе с настройкой «убрать Shorts» — так же,
 * как вкладка в нижней панели.
 */
class SearchScreen(context: Context, private val initial: String = "") : Screen(context) {

    private lateinit var field: EditText
    private lateinit var feed: FeedList
    private lateinit var suggestions: ListView
    private lateinit var kindRow: LinearLayout
    private lateinit var kindsBar: HorizontalScrollView

    private var query = ""
    private var kind = SearchKind.VIDEOS

    /** Подсказки сервера — то, что показывается при непустом поле. */
    private val hints = ArrayList<String>()

    /** Прежние запросы — то, что показывается при пустом. */
    private val history = ArrayList<String>()

    /**
     * Что сейчас в списке: история либо ответ сервера.
     *
     * Мерка та же, что в оригинале, — пусто ли поле. Пока набирают,
     * история ни к чему: подсказки сервера её и так содержат.
     */
    private fun showingHistory(): Boolean = field.text.isNullOrEmpty()

    private fun rowCount(): Int = if (showingHistory()) history.size else hints.size

    private fun rowAt(index: Int): String {
        val source = if (showingHistory()) history else hints

        return if (index in source.indices) source[index] else ""
    }

    /** Текст в поле ставим мы сами — подсказки тогда не спрашиваются. */
    private var settingText = false

    override fun build(root: FrameLayout) {
        val column = LinearLayout(context)

        column.orientation = LinearLayout.VERTICAL

        column.addView(buildBar())
        column.addView(buildKinds())

        feed = FeedList(context)

        feed.source = { token ->
            if (query.isEmpty()) {
                FeedList.Page(emptyList(), null)
            } else {
                val page = Api.search(query, token, kind)

                if (page == null) null else FeedList.Page(page.items, page.continuation)
            }
        }

        feed.emptyText = "Ничего не нашлось" // подпись: "Ничего не нашлось"

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

        buildSuggestions(root)

        // Запрос могли задать снаружи — из готовых запросов на главной.
        if (initial.isNotEmpty()) {
            submit(initial)
        }
    }

    /**
     * Строка ввода — числа из `viewWillLayoutSubviews` оригинала.
     *
     * Стрелка 40×40 в углу `4,8`, поле следом: `48,10`, шириной
     * во всё оставшееся минус 64, высотой 36. Поле стоит на подложке
     * `AppDividerBrush` со скруглением 5 — так оно выглядит
     * в горизонтальной раскладке Navbar.xaml.
     */
    private fun buildBar(): View {
        val back = TappableView(context)

        val chevron = label(context, Fonts.regular, 24f, Theme.primaryText, 1)

        chevron.text = "‹"
        chevron.gravity = Gravity.CENTER

        back.addView(
            chevron,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        back.onTap = { Nav.pop() }

        field = EditText(context)

        field.setSingleLine()
        field.hint = loc("Поиск")
        field.typeface = Fonts.regular
        field.textSize = 14f
        field.setTextColor(Theme.primaryText)
        field.setHintTextColor(Theme.mutedText)
        field.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH

        val plate = android.graphics.drawable.GradientDrawable()

        plate.setColor(Theme.divider)
        plate.cornerRadius = Metrics.dpf(5f)

        field.background = plate

        // Текст вплотную к краю подложки читается плохо — отступ,
        // как `Padding="12,0"` у поля в оригинале.
        field.setPadding(dp(12f), 0, dp(12f), 0)

        field.setOnEditorActionListener { _, _, _ ->
            submit(field.text.toString())

            true
        }

        field.addTextChangedListener(object : TextWatcher {

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

            override fun afterTextChanged(s: Editable?) {
                /**
                 * Правка своей же рукой подсказок не просит.
                 *
                 * `submit` ставит текст в поле сам — при выборе готового
                 * запроса с главной или подсказки из списка. Наблюдатель
                 * не отличает это от набора и уходил спрашивать подсказки
                 * заново; ответ приходил уже после того, как список
                 * спрятали, и открывал его обратно **поверх выдачи**.
                 */
                if (settingText) {
                    return
                }

                askSuggestions(s?.toString() ?: "")
            }
        })

        val bar = object : ViewGroup(context) {

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                setMeasuredDimension(
                    MeasureSpec.getSize(widthMeasureSpec),
                    statusBarHeight() + dp(Metrics.NAV_BAR_HEIGHT)
                )
            }

            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val top = statusBarHeight()

                back.frame(dp(4f), top + dp(8f), dp(40f), dp(40f))

                field.frame(dp(48f), top + dp(10f), (r - l) - dp(64f), dp(36f))
            }
        }

        bar.setBackgroundColor(Theme.background)

        bar.addView(back)
        bar.addView(field)

        return bar
    }

    /**
     * Полоса видов искомого.
     *
     * Стоит под строкой ввода и только при показанной выдаче: пока
     * набирают запрос, под строкой список подсказок, и фильтровать
     * ещё нечего.
     */
    private fun buildKinds(): View {
        kindRow = LinearLayout(context)

        kindRow.orientation = LinearLayout.HORIZONTAL
        kindRow.gravity = Gravity.CENTER_VERTICAL

        // `x = YTFeedPadding + 8` у первой таблетки, дальше просвет 8.
        kindRow.setPadding(dp(Metrics.FEED_PADDING) + dp(8f), 0, dp(8f), 0)

        val kinds = ArrayList<Pair<Int, String>>()

        kinds.add(SearchKind.VIDEOS to loc("Видео"))

        if (!Settings.hidesShorts) {
            kinds.add(SearchKind.SHORTS to "Shorts")
        }

        kinds.add(SearchKind.CHANNELS to loc("Каналы"))
        kinds.add(SearchKind.PLAYLISTS to loc("Плейлисты"))

        for ((value, name) in kinds) {
            val chip = ChipView(context)

            chip.caption = name
            chip.chosen = value == kind

            chip.onTap = {
                kind = value

                for (index in 0 until kindRow.childCount) {
                    (kindRow.getChildAt(index) as? ChipView)?.let {
                        it.chosen = it.caption == name
                    }
                }

                /**
                 * Вертикальным колонок вдвое больше, каналам — одна:
                 * так же делит выдачу `applyColumns` оригинала.
                 */
                feed.columnFactor = if (value == SearchKind.SHORTS) 2 else 1

                if (query.isNotEmpty()) {
                    feed.reload()
                }
            }

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(Metrics.CHIP_HEIGHT)
            )

            params.rightMargin = dp(8f)

            kindRow.addView(chip, params)
        }

        kindsBar = HorizontalScrollView(context)

        kindsBar.isHorizontalScrollBarEnabled = false
        kindsBar.visibility = View.GONE

        kindsBar.addView(
            kindRow,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        kindsBar.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(Metrics.CHIPS_BAR_HEIGHT)
        )

        return kindsBar
    }

    private fun buildSuggestions(root: FrameLayout) {
        suggestions = ListView(context)

        suggestions.setCacheColorHint(0)
        suggestions.setBackgroundColor(Theme.background)
        suggestions.divider = null
        suggestions.visibility = View.GONE

        val adapter = object : android.widget.BaseAdapter() {

            override fun getCount(): Int = rowCount()

            override fun getItem(position: Int): Any = rowAt(position)

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
                val row = (convert as? HintRow) ?: HintRow(context)

                row.bind(rowAt(position), showingHistory())

                row.onForget = { text ->
                    Settings.forgetSearch(text)

                    history.remove(text)

                    refreshHints()
                }

                row.onPick = { text -> submit(text) }

                return row
            }
        }

        suggestions.adapter = adapter

        root.addView(
            suggestions,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { topMargin = dp(Metrics.NAV_BAR_HEIGHT) + statusBarHeight() }
        )

        this.suggestionsAdapter = adapter
    }

    private var suggestionsAdapter: android.widget.BaseAdapter? = null

    /**
     * Перерисовать список и решить, показывать ли его.
     *
     * Список виден, пока в нём есть строки: при пустом поле это история,
     * при непустом — подсказки. Полоса видов искомого под строкой ввода
     * тем временем прячется: пока выбирают запрос, фильтровать нечего.
     */
    private fun refreshHints() {
        suggestionsAdapter?.notifyDataSetChanged()

        val empty = rowCount() == 0

        suggestions.visibility = if (empty) View.GONE else View.VISIBLE

        kindsBar.visibility = if (empty && query.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun askSuggestions(text: String) {
        if (text.isEmpty()) {
            hints.clear()

            // Поле опустело — на его место возвращается история.
            reloadHistory()

            return
        }

        async {
            val found = Api.searchSuggestions(text)

            main {
                if (field.text.toString() != text) {
                    return@main
                }

                hints.clear()
                hints.addAll(found)

                refreshHints()
            }
        }
    }

    /** Перечитывает историю из настроек и показывает её. */
    private fun reloadHistory() {
        history.clear()
        history.addAll(Settings.searchHistory)

        refreshHints()
    }

    private fun submit(text: String) {
        if (text.isEmpty()) {
            return
        }

        query = text

        Settings.rememberSearch(text)

        settingText = true

        field.setText(text)
        field.setSelection(field.text?.length ?: 0)

        settingText = false

        kindsBar.visibility = View.VISIBLE

        hints.clear()

        suggestionsAdapter?.notifyDataSetChanged()

        suggestions.visibility = View.GONE

        // Клавиатуру убираем: выдачу надо видеть целиком.
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager

        manager?.hideSoftInputFromWindow(field.windowToken, 0)

        feed.reload()
    }

    override fun appear() {
        field.requestFocus()

        /**
         * История перечитывается при каждом появлении: её мог пополнить
         * поиск с другого экрана, да и забытое где-то ещё должно исчезнуть
         * и здесь.
         */
        if (showingHistory()) {
            reloadHistory()
        }
    }

    override fun repaint() {
        super.repaint()

        feed.repaint()

        field.setTextColor(Theme.primaryText)
        field.setHintTextColor(Theme.mutedText)
    }
}

/**
 * Уведомления.
 *
 * Своего узла у них нет: `/notification/get_notification_menu` не даёт
 * ничего пригодного, и оригинал его тоже не зовёт — экран берёт ленту
 * подписок, ровно как `GetNotificationsAsync`.
 */
class NotificationsScreen(context: Context) : Screen(context) {

    private lateinit var header: ScreenHeader
    private lateinit var page: ScrollView
    private lateinit var column: LinearLayout
    private lateinit var status: StatusView

    override fun build(root: FrameLayout) {
        val holder = LinearLayout(context)

        holder.orientation = LinearLayout.VERTICAL

        header = ScreenHeader(context, loc("Уведомления"))

        holder.addView(header)

        column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL

        page = ScrollView(context)

        page.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        holder.addView(
            page,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        root.addView(
            holder,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        status = StatusView(context)

        root.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { topMargin = dp(Metrics.NAV_BAR_HEIGHT) + statusBarHeight() }
        )

        load()
    }

    /**
     * Кружки авторов подставляются из списка подписок по имени канала,
     * ровно как в `GetNotificationUploadsFallbackAsync`: у самой ленты
     * их нет.
     */
    private fun load() {
        if (!Auth.isSignedIn()) {
            status.showOffline(
                loc("Войдите в аккаунт"),
                loc("Здесь появятся новые ролики каналов, на которые вы подписаны"),
                loc("Войти")
            ) {
                Nav.pop()
                Nav.selectTab(3)
            }

            return
        }

        status.showBusy()

        async {
            val feed = Api.subscriptionsFeed(null)
            val channels = Api.subscriptions()

            val avatars = HashMap<String, String>()

            for (channel in channels ?: emptyList()) {
                val thumbnail = channel.thumbnail

                if (channel.title.isNotEmpty() && !thumbnail.isNullOrEmpty()) {
                    if (!avatars.containsKey(channel.title)) {
                        avatars[channel.title] = thumbnail
                    }
                }
            }

            main { show(feed?.items ?: emptyList(), avatars) }
        }
    }

    private fun show(items: List<VideoItem>, avatars: Map<String, String>) {
        column.removeAllViews()

        var shown = 0

        for (item in items) {
            if (item.isPlaylist) {
                continue
            }

            val row = NotificationRow(context)

            val author = item.channelTitle?.takeIf { it.isNotEmpty() } ?: "YouTube"

            val message = if (item.title.isNotEmpty()) {
                locF("Загрузил(а) видео «%@»", item.title)
            } else {
                loc("Загрузил(а) видео")
            }

            row.bind(author, message, loc("Из подписок"), avatars[author], item)
            row.applyTheme()

            column.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(NOTE_ROW)
                )
            )

            shown++
        }

        if (shown == 0) {
            status.showMessage(loc("Уведомлений нет"))

            return
        }

        status.hide()
    }

    override fun repaint() {
        super.repaint()

        header.repaint()

        view.setBackgroundColor(Theme.background)

        for (index in 0 until column.childCount) {
            (column.getChildAt(index) as? NotificationRow)?.applyTheme()
        }
    }
}

/** Числа строки уведомления из `YTNotificationsView`. */
private const val NOTE_ROW = 68f
private const val NOTE_AVATAR_COLUMN = 52f
private const val NOTE_AVATAR = 38f
private const val NOTE_THUMB_COLUMN = 104f
private const val NOTE_THUMB_WIDTH = 96f
private const val NOTE_THUMB_HEIGHT = 54f

/**
 * Строка уведомления — порт `YTNotificationRow`.
 *
 * Кружок автора в столбце 52, дальше три подписи — имя 15 SemiBold,
 * сообщение 13 в две строки, время 12 приглушённым, — а справа превью
 * 96×54 со скруглением 6. Лентой карточек это было по недосмотру:
 * источник у экрана и правда лента подписок, но вид у него свой.
 */
class NotificationRow(context: Context) : TappableView(context) {

    private val avatar = RoundedImage(context)

    private val author = label(context, Fonts.semiBold, 15f, Theme.primaryText, 1)
    private val message = label(context, Fonts.regular, 13f, Theme.primaryText, 2)
    private val time = label(context, Fonts.regular, 12f, Theme.secondaryText, 1)

    private val thumb = RoundedImage(context)

    private var item: VideoItem? = null

    init {
        highlights = true

        avatar.circular = true
        avatar.placeholderColor = Theme.avatarPlaceholder

        thumb.cornerRadius = Metrics.dpf(6f)

        addView(avatar)
        addView(author)
        addView(message)
        addView(time)
        addView(thumb)

        onTap = {
            val chosen = item

            if (chosen != null) {
                Nav.openVideo(chosen.videoId, chosen.title)
            }
        }
    }

    fun bind(
        name: String,
        text: String,
        moment: String,
        avatarUrl: String?,
        video: VideoItem
    ) {
        item = video

        author.text = name
        message.text = text
        time.text = moment

        ImageLoader.loadInto(avatar, avatarUrl, NOTE_AVATAR)
        ImageLoader.loadInto(thumb, video.thumbnail, NOTE_THUMB_WIDTH)
    }

    fun applyTheme() {
        author.setTextColor(Theme.primaryText)
        message.setTextColor(Theme.primaryText)
        time.setTextColor(Theme.secondaryText)

        avatar.placeholderColor = Theme.avatarPlaceholder
        thumb.placeholderColor = Theme.surfaceAlt
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(NOTE_ROW))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l

        avatar.frame(
            dp(10f) + (dp(NOTE_AVATAR_COLUMN) - dp(NOTE_AVATAR)) / 2, dp(2f),
            dp(NOTE_AVATAR), dp(NOTE_AVATAR)
        )

        val left = dp(10f) + dp(NOTE_AVATAR_COLUMN) + dp(8f)
        val right = width - dp(16f) - dp(NOTE_THUMB_COLUMN)

        val textWidth = maxOf(0, right - left - dp(10f))

        author.frame(left, 0, textWidth, dp(20f))
        message.frame(left, dp(21f), textWidth, dp(34f))
        time.frame(left, dp(57f), textWidth, dp(16f))

        thumb.frame(
            width - dp(16f) - dp(NOTE_THUMB_WIDTH), 0,
            dp(NOTE_THUMB_WIDTH), dp(NOTE_THUMB_HEIGHT)
        )
    }
}
