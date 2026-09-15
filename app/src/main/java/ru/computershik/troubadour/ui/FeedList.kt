package ru.computershik.troubadour.ui

import android.content.Context
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.model.VideoItem
import ru.computershik.troubadour.ui.Metrics.dp

/**
 * Лента карточек с подгрузкой страниц — общая для всех разделов.
 *
 * В оригинале этой общей части нет: «Главная», подписки, канал, подборка
 * и поиск строят список каждая у себя, и оттого одинаковый код лежит
 * в пяти местах. Здесь он один, и это не вольность: разделы всё равно
 * отличаются только тем, откуда берут страницу, а всё прочее — колонки,
 * отступы, порог подгрузки, кольцо ожидания, «нет подключения» — у них
 * совпадает до точки.
 *
 * Отступы ленты — `Padding="8,8,8,16"` из Home.xaml, между карточками 16.
 */
class FeedList(context: Context) : FrameLayout(context) {

    /** Откуда брать страницу. Возвращает карточки и токен следующей. */
    class Page(val items: List<VideoItem>, val continuation: String?)

    /** «Потяните, чтобы обновить» — над списком. */
    private val refresh = RefreshHeader(context)

    /**
     * Насколько список оттянут пальцем сверх верхнего края.
     *
     * `ListView` сам такого не считает — он просто упирается, — поэтому
     * тянем счёт по касаниям: сколько палец прошёл вниз, пока первая
     * карточка уже стояла у самого верха.
     */
    private var pullFrom = -1f
    private var pulled = 0

    /**
     * Что сказать, когда ответ пришёл, но роликов в нём нет.
     *
     * Пусто бывает по-разному: у поиска это «ничего не нашлось»,
     * у канала — «у канала пока нет роликов», у истории — «смотреть
     * пока нечего». Одной надписью на всех тут не обойтись.
     */
    var emptyText: String = "Ничего не нашлось" // подпись: "Ничего не нашлось"

    /** Сплошной текст вместо списка — заводится по надобности. */
    private var about: android.widget.TextView? = null
    private var aboutHost: android.view.View? = null

    /** Кто грузит. Зовётся из фона. */
    var source: ((String?) -> Page?)? = null

    /** Что положить над лентой — полоса категорий, шапка канала. */
    var header: View? = null
        set(value) {
            field = value

            adapter.notifyDataSetChanged()
        }

    /** Скругление превью: в ленте 8, в списке похожих 0. */
    var thumbRadius: Float = Metrics.THUMB_RADIUS

    private val list = ListView(context)
    private val status = StatusView(context)

    private val rows = ArrayList<List<VideoItem>>()
    private val items = ArrayList<VideoItem>()

    private val pager = Pager()
    private val generation = Generation()

    private var columns = 1

    /**
     * Во сколько раз дробить колонки.
     *
     * Вертикальным роликам колонок дают вдвое больше, но не меньше двух:
     * `columns = MAX(2, columns * 2)` в `applyColumns` оригинала. Карточка
     * у них 9:16, и во всю ширину она заняла бы полтора экрана.
     */
    var columnFactor = 1
    private var loaded = false

    private val adapter = object : BaseAdapter() {

        override fun getCount(): Int = rows.size + (if (header != null) 1 else 0)

        override fun getItem(position: Int): Any = position

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int =
            if (header != null && position == 0) 0 else 1

        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            val top = header

            if (top != null && position == 0) {
                return top
            }

            val index = if (top != null) position - 1 else position

            val row = (convert as? FeedRow) ?: FeedRow(context)

            row.bind(rows.getOrElse(index) { emptyList() }, columns, thumbRadius)

            return row
        }
    }

    init {
        list.adapter = adapter
        list.divider = null
        list.dividerHeight = 0

        /**
         * Список не рисует полосу прокрутки поверх содержимого и не гасит
         * фон при прокрутке.
         *
         * `setCacheColorHint(0)` — то, без чего на старых версиях список
         * с прозрачным фоном чернеет во время прокрутки. Беда известная
         * и лечится ровно этим.
         */
        list.setCacheColorHint(0)
        list.isVerticalScrollBarEnabled = true

        list.setPadding(dp(8f), dp(8f), dp(8f), dp(16f))
        list.clipToPadding = false

        addView(
            list,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        addView(
            status,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        /**
         * Шапка обновления лежит **над** списком, а не в нём.
         *
         * В оригинале она стоит над содержимым с отрицательным
         * смещением; здесь проще положить её поверх у верхнего края
         * и показывать, пока список оттянут, — на глаз это одно и то же.
         */
        addView(
            refresh,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        refresh.onRefresh = { reload() }

        list.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {

            override fun onScrollStateChanged(view: android.widget.AbsListView, state: Int) {}

            override fun onScroll(
                view: android.widget.AbsListView,
                first: Int,
                visible: Int,
                total: Int
            ) {
                /**
                 * Порог — два экрана до конца. Страница приезжает
                 * не мгновенно, и запас в один экран на медленной сети
                 * кончается раньше, чем приходит ответ.
                 */
                if (pager.claim(first + visible, total, maxOf(1, visible))) {
                    loadPage(pager.token)
                }
            }
        })
    }

    /** Начать заново: прежние страницы забываются. */
    fun reload() {
        generation.next()

        pager.reset()

        rows.clear()
        items.clear()

        adapter.notifyDataSetChanged()

        loaded = false

        hideText()

        // При обновлении по жесту кольцо уже крутится в шапке.
        if (!refresh.isRefreshing()) {
            status.showBusy()
        }

        loadPage(null)
    }

    /** Загрузить, если ещё не грузились. Зовётся при показе раздела. */
    fun loadOnce() {
        if (loaded) {
            return
        }

        reload()
    }

    private fun loadPage(token: String?) {
        val fetch = source ?: return
        val mark = generation.current

        async {
            val page = fetch(token)

            main {
                if (!generation.isCurrent(mark)) {
                    return@main
                }

                pager.finish()

                // Обновление кончилось — шапку убираем в любом случае.
                refresh.done()

                if (page == null) {
                    status.hide()

                    if (items.isEmpty()) {
                        /**
                         * Пустой успешный ответ и отсутствие ответа —
                         * это два разных случая, и путать их в сообщениях
                         * нельзя. Здесь именно второй: сети не было.
                         */
                        status.showNoConnection { reload() }
                    }

                    return@main
                }

                loaded = true

                pager.token = page.continuation ?: ""

                append(page.items)

                status.hide()

                if (items.isEmpty()) {
                    status.showMessage(loc(emptyText))
                }
            }
        }
    }

    private fun append(fresh: List<VideoItem>) {
        if (fresh.isEmpty()) {
            adapter.notifyDataSetChanged()

            return
        }

        items.addAll(fresh)

        rebuildRows()
    }

    /**
     * Пересобирает ряды под нынешнюю ширину.
     *
     * Число колонок считается так же, как `ItemsWrapGrid ItemWidth="360"
     * MaximumRowsOrColumns="3"`: сколько карточек шириной 360 помещается,
     * столько и колонок, но не больше трёх.
     */
    private fun rebuildRows() {
        val width = Metrics.points(maxOf(0, this.width - dp(16f)))

        columns = if (columnFactor > 1) {
            maxOf(columnFactor, Metrics.columnsForWidth(width) * columnFactor)
        } else {
            Metrics.columnsForWidth(width)
        }

        rows.clear()

        var index = 0

        while (index < items.size) {
            rows.add(items.subList(index, minOf(index + columns, items.size)))

            index += columns
        }

        adapter.notifyDataSetChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (w != oldw && items.isNotEmpty()) {
            rebuildRows()
        }
    }

    /** Перекрасить под текущую тему. */
    /**
     * Считаем оттяжку сами.
     *
     * `ListView` не сообщает, насколько его тянут сверх края: он просто
     * упирается. Поэтому смотрим касания — сколько палец прошёл вниз,
     * пока первая карточка уже стояла у самого верха. Событие
     * не перехватываем: список должен листаться как обычно, наше дело
     * только подсмотреть.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val atTop = list.childCount == 0 ||
            (list.firstVisiblePosition == 0 && list.getChildAt(0).top >= list.paddingTop)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pullFrom = if (atTop) event.y else -1f
                pulled = 0
            }

            MotionEvent.ACTION_MOVE -> {
                if (pullFrom >= 0 && atTop) {
                    pulled = maxOf(0, (event.y - pullFrom).toInt())

                    refresh.followPull(pulled)
                } else {
                    pullFrom = if (atTop) event.y else -1f
                    pulled = 0
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                refresh.release(pulled)

                pullFrom = -1f
                pulled = 0
            }
        }

        return super.dispatchTouchEvent(event)
    }

    /** Сообщение вместо списка — «здесь пусто» и подобное. */
    fun showMessage(text: String) {
        status.showMessage(text)
    }

    /**
     * Сплошной текст вместо списка — раздел «О канале».
     *
     * Своей ленты у него нет: сервер отдаёт одно описание, и показывать
     * его карточками нечем.
     */
    fun showText(body: String) {
        val view = about ?: label(context, Fonts.regular, 14f, Theme.primaryText, 0).also {
            it.setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            it.gravity = android.view.Gravity.TOP

            val scroll = android.widget.ScrollView(context)

            scroll.addView(
                it,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                scroll,
                LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            aboutHost = scroll
            about = it
        }

        view.text = body

        aboutHost?.visibility = VISIBLE

        status.hide()

        list.visibility = GONE
    }

    /** Убрать сплошной текст и вернуть список. */
    private fun hideText() {
        aboutHost?.visibility = GONE

        list.visibility = VISIBLE
    }

    fun repaint() {
        refresh.applyTheme()

        setBackgroundColor(Theme.background)

        adapter.notifyDataSetChanged()
    }
}
