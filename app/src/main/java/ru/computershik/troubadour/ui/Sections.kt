package ru.computershik.troubadour.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.net.Api
import ru.computershik.troubadour.net.Auth
import ru.computershik.troubadour.net.HomeCategory
import ru.computershik.troubadour.net.SearchKind
import ru.computershik.troubadour.net.homeCategories
import ru.computershik.troubadour.net.homeFeed
import ru.computershik.troubadour.net.liveFeed
import ru.computershik.troubadour.net.channelTab
import ru.computershik.troubadour.net.search
import ru.computershik.troubadour.net.subscriptions
import ru.computershik.troubadour.net.trendingQueries
import ru.computershik.troubadour.net.subscriptionsFeed
import ru.computershik.troubadour.ui.Metrics.dp

/**
 * «Главная» — лента рекомендаций с полосой категорий над ней.
 *
 * Лента у невошедшего не показывается вовсе, и это перенесено, а не
 * упущено: `FEwhat_to_watch` анонимному WEB-клиенту отвечает успешно,
 * но без роликов — рекомендовать ему некому. На её месте призыв поискать,
 * как `SuggestionsSection` в Home.xaml.
 */
class HomeSection(context: Context) : Shell.Section(context) {

    private lateinit var feed: FeedList
    private lateinit var chips: HorizontalScrollView
    private lateinit var chipRow: LinearLayout

    private lateinit var suggestions: View

    private var categories: List<HomeCategory> = emptyList()
    private var chosen = 0

    override fun build(): ViewGroup {
        val root = FrameLayout(context)

        feed = FeedList(context)

        feed.source = { token -> loadPage(token) }

        root.addView(
            feed,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        buildChips()

        feed.header = chips

        suggestions = buildSuggestions()

        root.addView(
            suggestions,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        return root
    }

    /**
     * Список готовых запросов: заголовок 15 SemiBold с полями 16×8,
     * строки высотой 48 — значок лупы 16 в колонке 28 и текст с отступа 60.
     */
    private fun buildSuggestions(): View {
        val scroll = android.widget.ScrollView(context)

        val column = LinearLayout(context)

        column.orientation = LinearLayout.VERTICAL

        val header = label(context, Fonts.semiBold, 15f, Theme.primaryText, 1)

        header.text = loc("Популярные запросы")
        header.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))

        column.addView(header)

        for (query in Api.trendingQueries()) {
            val row = TappableView(context)

            row.highlights = true

            val icon = android.widget.ImageView(context)

            icon.setImageBitmap(Icons.icon("search"))
            icon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER

            row.addView(
                icon,
                FrameLayout.LayoutParams(dp(16f), dp(16f), Gravity.CENTER_VERTICAL)
                    .apply { leftMargin = dp(28f) }
            )

            val text = label(context, Fonts.regular, 15f, Theme.primaryText, 1)

            text.text = query

            row.addView(
                text,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply { leftMargin = dp(60f) }
            )

            row.onTap = { Nav.push(SearchScreen(context, query)) }

            column.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)
                )
            )
        }

        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        scroll.setBackgroundColor(Theme.background)
        scroll.visibility = View.GONE

        return scroll
    }

    /**
     * Полоса «таблеток» — высота 48, сама таблетка 36 со скруглением 9,
     * поля по 14, подпись 14 SemiBold.
     */
    private fun buildChips() {
        chips = HorizontalScrollView(context)

        chips.isHorizontalScrollBarEnabled = false

        chipRow = LinearLayout(context)
        chipRow.orientation = LinearLayout.HORIZONTAL
        chipRow.gravity = Gravity.CENTER_VERTICAL

        chips.addView(
            chipRow,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(Metrics.CHIPS_BAR_HEIGHT)
            )
        )

        chips.layoutParams = android.widget.AbsListView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(Metrics.CHIPS_BAR_HEIGHT)
        )

        rebuildChips()
    }

    private fun rebuildChips() {
        categories = Api.homeCategories()

        chipRow.removeAllViews()

        for (index in categories.indices) {
            val chip = ChipView(context)

            chip.caption = categories[index].title
            chip.chosen = index == chosen

            chip.onTap = {
                chosen = index

                for (at in 0 until chipRow.childCount) {
                    (chipRow.getChildAt(at) as? ChipView)?.let {
                        it.chosen = at == index
                    }
                }

                feed.reload()
            }

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(Metrics.CHIP_HEIGHT)
            )

            // `x = YTFeedPadding + 8` у первой, дальше просвет 8 — как в оригинале.
            params.leftMargin = if (index == 0) dp(Metrics.FEED_PADDING) + dp(8f) else dp(8f)

            if (index == categories.size - 1) {
                params.rightMargin = dp(Metrics.FEED_PADDING) + dp(8f)
            }

            chipRow.addView(chip, params)
        }
    }

    /**
     * Выбранная таблетка выполняет **поиск**, а не листает ленту с `params`.
     *
     * Так в оригинале: `GetHomeCategoryVideosAsync` уходит
     * в `GetAnonymousSearchVideosAsync`. Запросы английские намеренно —
     * так выдача не зависит от языка приложения.
     */
    private fun loadPage(token: String?): FeedList.Page? {
        val category = categories.getOrNull(chosen)

        /**
         * «Сейчас в эфире» — свой раздел, отвечающий полками.
         *
         * Поиск тут не годится вовсе: по слову «live» приходит что
         * угодно, кроме идущих трансляций.
         */
        if (category != null && category.browse.isNotEmpty()) {
            val page = Api.liveFeed(token) ?: return null

            return FeedList.Page(page.items, page.continuation, page.groups)
        }

        if (category != null && category.query.isNotEmpty()) {
            val page = Api.search(category.query, token, SearchKind.VIDEOS) ?: return null

            return FeedList.Page(page.items, page.continuation)
        }

        val page = Api.homeFeed(token) ?: return null

        return FeedList.Page(page.items, page.continuation)
    }

    override fun appear() {
        /**
         * Невошедшему рекомендовать некому — вместо ленты список
         * готовых запросов, как `SuggestionsSection` в оригинале.
         *
         * `FEwhat_to_watch` анонимному клиенту отвечает успешно, но без
         * роликов; прежде я вместо этого молча открывал первую
         * категорию, и человек попадал не туда, куда шёл.
         */
        val suggesting = !Auth.isSignedIn() && chosen == 0

        suggestions.visibility = if (suggesting) View.VISIBLE else View.GONE
        feed.visibility = if (suggesting) View.GONE else View.VISIBLE

        if (suggesting) {
            return
        }

        feed.loadOnce()
    }

    override fun applyTheme() {
        feed.repaint()

        for (index in 0 until chipRow.childCount) {
            (chipRow.getChildAt(index) as? ChipView)?.applyState()
        }
    }

    override fun accountChanged() {
        feed.reload()
    }
}

/**
 * «Подписки» — лента `FEsubscriptions` у TV-клиента с токеном.
 *
 * Невошедшего отправляем на «Моё», где живёт вход: так же поступает
 * и оригинал.
 */
class SubscriptionsSection(context: Context) : Shell.Section(context) {

    private lateinit var feed: FeedList
    private lateinit var status: StatusView
    private lateinit var strip: ChannelStrip

    /** Какой канал выбран в полосе; пусто — все подписки. */
    private var channelFilter = ""

    private var channelsLoaded = false

    override fun build(): ViewGroup {
        val root = FrameLayout(context)

        feed = FeedList(context)

        /**
         * Выбран канал — показываем его ролики, как `LoadChannel`
         * в оригинале; не выбран — общую ленту подписок.
         */
        feed.source = { token ->
            val filter = channelFilter

            if (filter.isNotEmpty()) {
                val page = Api.channelTab(filter, "videos")

                if (page == null) null else FeedList.Page(page.items, page.continuation)
            } else {
                val page = Api.subscriptionsFeed(token)

                if (page == null) null else FeedList.Page(page.items, page.continuation)
            }
        }

        strip = ChannelStrip(context)

        strip.onPick = { channelId ->
            channelFilter = channelId

            feed.reload()
        }

        feed.header = strip

        root.addView(
            feed,
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
            )
        )

        return root
    }

    override fun appear() {
        if (!Auth.isSignedIn()) {
            feed.visibility = View.GONE

            status.showOffline(
                loc("Войдите в аккаунт"),
                loc("Подписки и их свежие ролики появятся здесь после входа"),
                loc("Войти")
            ) {
                Nav.selectTab(3)
            }

            return
        }

        feed.visibility = View.VISIBLE

        status.hide()

        feed.loadOnce()

        loadChannels()
    }

    /**
     * Список каналов для полосы — своим заходом.
     *
     * Он не меняется от ролика к ролику, поэтому спрашивается один раз
     * за вход, а не при каждом показе раздела.
     */
    private fun loadChannels() {
        if (channelsLoaded) {
            return
        }

        channelsLoaded = true

        async {
            val channels = Api.subscriptions()

            main { strip.bind(channels ?: emptyList()) }
        }
    }

    override fun applyTheme() {
        feed.repaint()

        strip.applyTheme()
    }

    override fun accountChanged() {
        channelsLoaded = false
        channelFilter = ""

        feed.reload()

        appear()
    }
}
