package ru.computershik.troubadour.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.net.Api
import ru.computershik.troubadour.net.Auth
import ru.computershik.troubadour.net.accountAvatarUrl
import ru.computershik.troubadour.ui.Metrics.dp

/**
 * Кнопка раздела: значок 24×24 и подпись 12 точек под ним.
 *
 * Порт Tabbar.xaml: `StackPanel Orientation="Vertical"` с `Image Width="24"
 * Height="24"` и `TextBlock FontSize="12"`, всё по центру. Подпись всегда
 * цвета `AppPrimaryTextBrush` — в оригинале она не тускнеет у невыбранных
 * разделов, различие несёт только значок (обычный и `_on`).
 */
class TabButton(context: Context) : TappableView(context) {

    private val icon = ImageView(context)
    private val label = label(context, Fonts.regular, 12f, Theme.primaryText, 1)

    /** Кружок аккаунта вместо значка — как `AccountAvatarEllipse` в оригинале. */
    private var avatar: RoundedImage? = null

    var iconName: String = ""
        set(value) {
            field = value

            applyTheme()
        }

    var title: String = ""
        set(value) {
            field = value

            label.text = value
        }

    var chosen: Boolean = false
        set(value) {
            field = value

            applyTheme()
        }

    init {
        // Подсветку не рисуем: в оригинале у кнопок панели прозрачный фон
        // и никакого состояния нажатия.
        highlights = false

        icon.scaleType = ImageView.ScaleType.FIT_CENTER

        label.gravity = android.view.Gravity.CENTER

        addView(icon)
        addView(label)
    }

    fun setAvatarUrl(url: String?) {
        if (url.isNullOrEmpty()) {
            avatar?.visibility = GONE
            icon.visibility = VISIBLE

            return
        }

        if (avatar == null) {
            val view = RoundedImage(context)

            view.circular = true
            view.placeholderColor = Theme.avatarPlaceholder

            avatar = view

            addView(view)

            requestLayout()
        }

        avatar?.visibility = VISIBLE
        icon.visibility = GONE

        ImageLoader.loadInto(avatar!!, url, 24f)
    }

    fun applyTheme() {
        label.setTextColor(Theme.primaryText)

        icon.setImageBitmap(
            Icons.icon(if (chosen) "${iconName}_on" else iconName)
        )

        avatar?.placeholderColor = Theme.avatarPlaceholder
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        /**
         * 24 точки значок, подпись сразу под ним; в оригинале между ними
         * `Margin="0,0,0,0"`, то есть отступа нет вовсе.
         */
        val labelHeight = dp(14f)
        val side = dp(24f)
        val total = side + labelHeight
        val top = (height - total) / 2

        icon.frame((width - side) / 2, top, side, side)

        avatar?.frame((width - side) / 2, top, side, side)

        label.frame(0, top + side, width, labelHeight)
    }
}

/**
 * Оболочка приложения: верхняя панель, нижняя панель и четыре раздела.
 *
 * Разделы — это виды внутри одной оболочки, а не отдельные экраны, и так же
 * было в оригинале: в UWP `Home.xaml` держит `Navbar` и `Tabbar` по краям,
 * а середину подменяет. Благодаря этому переключение вкладки не теряет уже
 * загруженную ленту.
 *
 * Числа из Navbar.xaml и Tabbar.xaml:
 *
 *     верхняя панель  56, логотип 32, отступ 16, значки 24
 *     нижняя панель   50 плюс полоса 2 над ней
 */
class Shell(context: Context) : ViewGroup(context) {

    private val navBar = View(context)
    private val wordmark = ImageView(context)

    private val searchButton = TappableView(context)
    private val searchIcon = ImageView(context)

    private val notificationsButton = TappableView(context)
    private val notificationsIcon = ImageView(context)

    private val tabBarDivider = View(context)
    private val tabBar = View(context)

    private val tabs = ArrayList<TabButton>()
    private val sections = ArrayList<Section>()

    private var chosen = 0

    /** Раздел оболочки: свой вид и своя загрузка. */
    abstract class Section(val context: Context) {

        val view: ViewGroup by lazy { build() }

        protected abstract fun build(): ViewGroup

        /** Раздел показался — самое время загрузиться, если ещё не грузился. */
        open fun appear() {}

        /**
         * Раздел ушёл с экрана.
         *
         * Нужен вкладке Shorts: она играет ролик, а уходя со вкладки,
         * человек ждёт тишины. Без этого ролик продолжал играть за
         * спрятанным разделом — слышно, но не видно.
         */
        open fun disappear() {}

        /** Сменилась тема. */
        open fun applyTheme() {}

        /** Сменился вход или канал — перечитать своё. */
        open fun accountChanged() {}
    }

    init {
        addView(navBar)

        wordmark.scaleType = ImageView.ScaleType.FIT_CENTER
        searchIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        notificationsIcon.scaleType = ImageView.ScaleType.FIT_CENTER

        addView(wordmark)

        searchButton.highlights = false
        searchButton.addView(searchIcon)
        searchButton.onTap = { Nav.push(SearchScreen(context)) }

        addView(searchButton)

        notificationsButton.highlights = false
        notificationsButton.addView(notificationsIcon)
        notificationsButton.onTap = { Nav.push(NotificationsScreen(context)) }

        addView(notificationsButton)

        addView(tabBarDivider)
        addView(tabBar)

        val icons = listOf("tab_home", "tab_shorts", "tab_subs", "tab_you")
        val titles = listOf(loc("Главная"), "Shorts", loc("Подписки"), loc("Вы"))

        for (index in icons.indices) {
            val button = TabButton(context)

            button.iconName = icons[index]
            button.title = titles[index]

            button.onTap = { selectTab(index) }

            tabs.add(button)
            addView(button)
        }

        sections.add(HomeSection(context))
        sections.add(ShortsSection(context))
        sections.add(SubscriptionsSection(context))
        sections.add(MineSection(context))

        for (section in sections) {
            addView(section.view)

            section.view.visibility = GONE
        }

        applyTheme()
        selectTab(0)

        Notify.on(Notify.THEME, this) { applyTheme() }
        Notify.on(Notify.SETTINGS, this) { requestLayout() }

        Notify.on(Notify.ACCOUNT, this) {
            refreshAvatar()

            for (section in sections) {
                section.accountChanged()
            }
        }

        Notify.on(Nav.SELECT_TAB, this) { value ->
            (value as? Int)?.let { selectTab(it) }
        }

        refreshAvatar()
    }

    /**
     * Вкладка Shorts уходит из панели, если их прячут.
     *
     * Одним переключателем, а не пятью: вертикальные ролики попадаются
     * не только на своей вкладке. Тому, кто их не смотрит, приходилось бы
     * обходить каждое место по очереди — а половину из них он
     * и не подозревает.
     */
    private fun tabShown(index: Int): Boolean =
        !(index == 1 && Settings.hidesShorts)

    fun selectTab(index: Int) {
        if (index < 0 || index >= sections.size || !tabShown(index)) {
            return
        }

        val previous = chosen

        chosen = index

        if (previous != index && previous >= 0 && previous < sections.size) {
            sections[previous].disappear()
        }

        /**
         * Уходя на Shorts, убираем окошко: плеер один на всё
         * приложение, и листалка сейчас же заберёт его себе.
         * Окошко осталось бы застывшим кадром.
         */
        if (index == 1) {
            Nav.closeMiniPlayer()
        }

        for (at in sections.indices) {
            sections[at].view.visibility = if (at == index) VISIBLE else GONE
            tabs[at].chosen = at == index
        }

        /**
         * Верхняя панель есть не у всех разделов.
         *
         * В оригинале она принадлежит `Home.xaml`; у Shorts кадр идёт
         * во весь экран, а у «Моё» своя строка с лупой и шестерёнкой
         * внутри самого раздела — поэтому общая панель там прячется,
         * и раздел занимает освободившееся место.
         */
        navBar.visibility = if (index == 0 || index == 2) VISIBLE else GONE

        wordmark.visibility = navBar.visibility
        searchButton.visibility = navBar.visibility
        notificationsButton.visibility =
            if (navBar.visibility == VISIBLE && Auth.isSignedIn()) VISIBLE else GONE

        sections[index].appear()

        requestLayout()
    }

    private fun refreshAvatar() {
        val you = tabs.getOrNull(3) ?: return

        if (!Auth.isSignedIn()) {
            you.setAvatarUrl(null)

            return
        }

        async {
            val url = Api.accountAvatarUrl()

            main { you.setAvatarUrl(url) }
        }
    }

    fun applyTheme() {
        // Набор значков меняется целиком — прежние остались бы чужого цвета.
        Icons.drop()

        setBackgroundColor(Theme.background)

        navBar.setBackgroundColor(Theme.background)
        tabBar.setBackgroundColor(Theme.background)
        tabBarDivider.setBackgroundColor(Theme.divider)

        wordmark.setImageBitmap(Icons.icon("ytlogo"))
        searchIcon.setImageBitmap(Icons.icon("search"))
        notificationsIcon.setImageBitmap(Icons.icon("notifications"))

        for (button in tabs) {
            button.applyTheme()
        }

        for (section in sections) {
            section.applyTheme()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        /**
         * Отступ под строку состояния.
         *
         * Верхняя панель уходит под неё, как и в оригинале: там это
         * делал `YTUseFullScreenLayout`, здесь — прозрачная строка
         * состояния с Android 5 и отступ, отмеряемый по её высоте.
         */
        val top = statusBarHeight()

        // Спрятанная панель места не занимает, но рамку сохраняет.
        val navHeight = if (navBar.visibility == GONE) 0 else dp(Metrics.NAV_BAR_HEIGHT)

        navBar.frame(0, top, width, dp(Metrics.NAV_BAR_HEIGHT))

        // Словесный знак: высота 32, отступ слева 16, по центру полосы.
        val logo = wordmark.drawable

        val logoHeight = dp(32f)
        val logoWidth = if (logo != null && logo.intrinsicHeight > 0) {
            logo.intrinsicWidth * logoHeight / logo.intrinsicHeight
        } else {
            dp(107f)
        }

        wordmark.frame(dp(16f), top + (navHeight - logoHeight) / 2, logoWidth, logoHeight)

        /**
         * Справа: лупа у самого края (отступ 16), колокольчик левее неё.
         * Область нажатия шире значка — 24 точки значка плюс отступы 8,
         * как `Padding="8"` у кнопок в оригинале.
         */
        val buttonSide = dp(40f)
        val iconSide = dp(24f)
        val pad = dp(8f)

        var right = width - dp(16f) - buttonSide
        val buttonTop = top + (navHeight - buttonSide) / 2

        searchButton.frame(right, buttonTop, buttonSide, buttonSide)
        searchIcon.frame(pad, pad, iconSide, iconSide)

        right -= buttonSide + dp(4f)

        notificationsButton.frame(right, buttonTop, buttonSide, buttonSide)
        notificationsIcon.frame(pad, pad, iconSide, iconSide)

        // Нижняя панель: 50 и полоса 2 над ней.
        val tabHeight = dp(Metrics.TAB_BAR_HEIGHT)
        val dividerHeight = dp(Metrics.TAB_BAR_DIVIDER)

        val tabTop = height - tabHeight

        tabBarDivider.frame(0, tabTop - dividerHeight, width, dividerHeight)
        tabBar.frame(0, tabTop, width, tabHeight)

        var shown = 0

        for (index in tabs.indices) {
            if (tabShown(index)) {
                shown++
            }
        }

        if (shown == 0) {
            shown = 1
        }

        val tabWidth = width / shown

        var at = 0

        for (index in tabs.indices) {
            val button = tabs[index]

            if (!tabShown(index)) {
                button.visibility = GONE

                continue
            }

            button.visibility = VISIBLE

            button.frame(at * tabWidth, tabTop, tabWidth, tabHeight)

            at++
        }

        // Середина — между панелями.
        val contentTop = top + navHeight
        val contentBottom = tabTop - dividerHeight

        for (section in sections) {
            section.view.frame(0, contentTop, width, contentBottom - contentTop)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        setMeasuredDimension(width, height)

        val navHeight = if (navBar.visibility == GONE) 0 else dp(Metrics.NAV_BAR_HEIGHT)

        val contentHeight = height - statusBarHeight() - navHeight -
            dp(Metrics.TAB_BAR_HEIGHT) - dp(Metrics.TAB_BAR_DIVIDER)

        for (section in sections) {
            section.view.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxOf(0, contentHeight), MeasureSpec.EXACTLY)
            )
        }

        for (button in tabs) {
            button.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(dp(Metrics.TAB_BAR_HEIGHT), MeasureSpec.EXACTLY)
            )
        }

        val buttonSide = dp(40f)

        for (button in listOf(searchButton, notificationsButton)) {
            button.measure(
                MeasureSpec.makeMeasureSpec(buttonSide, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(buttonSide, MeasureSpec.EXACTLY)
            )
        }

        measureChild(wordmark, widthMeasureSpec, heightMeasureSpec)
    }
}
