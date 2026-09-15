package ru.computershik.troubadour.ui

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.ImageView
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.locF
import ru.computershik.troubadour.model.VideoItem
import ru.computershik.troubadour.ui.Metrics.dp

/**
 * Карточка очереди — порт `applyQueue:` и `layoutQueueAt:width:`.
 *
 * Это `PlaylistQueuePanel` из Video.xaml: то, что идёт следом, когда ролик
 * открыт из подборки или из микса. Карточка та же, что у глав, — поля 12,
 * скругление 12, шапка 40 с названием и подписью «3 из 25», справа стрелка.
 *
 * Строка: превью 104×58 со скруглением 8, название 13 в две строки,
 * автор 12 под ним. У текущего ролика поверх превью кружок 30 с чёрной
 * заливкой и треугольником внутри.
 */
class QueueCard(context: Context) : ViewGroup(context) {

    private val card = PillView(context)

    private val header = TappableView(context)
    private val title = label(context, Fonts.bold, 14f, Theme.primaryText, 1)
    private val position = label(context, Fonts.regular, 12f, Theme.secondaryText, 1)
    private val chevron = ImageView(context)

    private val rows = ArrayList<QueueRow>()

    private var items: List<VideoItem> = emptyList()

    /** Свёрнута ли — по умолчанию да, как и главы. */
    private var collapsed = true

    var onPick: ((VideoItem) -> Unit)? = null

    init {
        card.cornerRadius = Metrics.dpf(12f)
        card.fillColor = Theme.surface

        addView(card)

        header.highlights = false
        header.onTap = {
            collapsed = !collapsed

            applyChevron()
            requestLayout()
        }

        header.addView(title)
        header.addView(position)
        header.addView(chevron)

        chevron.scaleType = ImageView.ScaleType.FIT_CENTER

        addView(header)

        applyChevron()

        visibility = GONE
    }

    private fun applyChevron() {
        chevron.setImageBitmap(Icons.icon("down_arrow"))

        chevron.rotation = if (collapsed) 0f else 180f
    }

    /**
     * @param videoId ролик, который идёт сейчас — по нему ищется номер.
     */
    fun bind(list: List<VideoItem>, name: String?, fallbackIndex: Int, videoId: String) {
        items = list

        for (row in rows) {
            removeView(row)
        }

        rows.clear()

        visibility = if (list.isEmpty()) GONE else VISIBLE

        if (list.isEmpty()) {
            return
        }

        title.text = if (name.isNullOrEmpty()) loc("Плейлист") else name

        /**
         * Номер берём по своему списку, а не из ответа.
         *
         * `currentIndex` сервера считает по той пачке, которую он
         * сейчас прислал; у микса это скользящее окно, и в нашем
         * накопленном списке тот же ролик стоит совсем на другом
         * месте. Свой счёт не разъезжается с тем, что человек видит.
         */
        var index = list.indexOfFirst { it.videoId == videoId }

        if (index < 0) {
            index = fallbackIndex
        }

        position.text = locF("%ld из %lu", index + 1, list.size)

        for (at in list.indices) {
            val row = QueueRow(context, list[at], at == index)

            row.onTap = { onPick?.invoke(list[at]) }

            rows.add(row)

            addView(row)
        }

        requestLayout()
    }

    fun applyTheme() {
        card.fillColor = Theme.surface

        title.setTextColor(Theme.primaryText)
        position.setTextColor(Theme.secondaryText)

        applyChevron()

        for (row in rows) {
            row.applyTheme()
        }
    }

    /** Высота строки: превью 58 плюс поля 8. */
    private fun rowHeight(): Int = dp(58f) + dp(8f)

    private fun cardHeight(): Int {
        val headerHeight = dp(40f)

        var listHeight = 0

        if (!collapsed) {
            listHeight = rowHeight() * rows.size

            if (rows.isNotEmpty()) {
                listHeight += dp(10f)
            }
        }

        return dp(12f) + headerHeight + listHeight + dp(12f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)

        setMeasuredDimension(width, if (items.isEmpty()) 0 else cardHeight())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (items.isEmpty()) {
            return
        }

        val width = r - l

        val margin = dp(16f)
        val content = width - margin * 2
        val inner = content - dp(24f)

        val headerHeight = dp(40f)

        card.frame(margin, 0, content, cardHeight())

        header.frame(margin + dp(12f), dp(12f), inner, headerHeight)

        title.frame(0, dp(2f), inner - dp(26f), dp(18f))
        position.frame(0, dp(22f), inner - dp(26f), dp(16f))

        chevron.frame(
            inner - dp(18f), (headerHeight - dp(18f)) / 2, dp(18f), dp(18f)
        )

        var rowY = dp(12f) + headerHeight + dp(10f)

        for (row in rows) {
            if (collapsed) {
                row.visibility = GONE

                continue
            }

            row.visibility = VISIBLE

            row.frame(margin + dp(12f) + dp(4f), rowY, inner - dp(8f), rowHeight())

            rowY += rowHeight()
        }
    }
}

/** Строка очереди: превью 104×58, название и автор рядом. */
class QueueRow(context: Context, item: VideoItem, private val playing: Boolean) :
    TappableView(context) {

    private val thumb = RoundedImage(context)

    private val name = label(context, Fonts.regular, 13f, Theme.primaryText, 2)
    private val author = label(context, Fonts.regular, 12f, Theme.secondaryText, 1)

    /** Кружок «сейчас играет» — только у текущего ролика. */
    private val marker = PillView(context)
    private val play = ImageView(context)

    init {
        highlights = false

        thumb.cornerRadius = Metrics.dpf(8f)
        thumb.placeholderColor = Theme.surfaceAlt

        addView(thumb)

        name.text = item.title
        author.text = item.channelTitle ?: ""

        addView(name)
        addView(author)

        marker.cornerRadius = Metrics.dpf(15f)
        marker.fillColor = Color.argb(204, 0, 0, 0)

        /**
         * Треугольник — наш значок, а не знак «▶».
         *
         * В разметке оригинала стоит именно этот знак, но у U+25B6
         * есть эмодзи-начертание, и система выбирает его: вместо
         * тонкого белого треугольника выходит цветной со своим полем.
         * Значок `pl_play` рисует ровно то, что нужно.
         */
        play.setImageBitmap(Icons.darkIcon("pl_play"))
        play.scaleType = ImageView.ScaleType.FIT_CENTER

        marker.visibility = if (playing) VISIBLE else GONE
        play.visibility = marker.visibility

        addView(marker)
        addView(play)

        ImageLoader.loadInto(thumb, item.thumbnail, 104f)
    }

    fun applyTheme() {
        thumb.placeholderColor = Theme.surfaceAlt

        name.setTextColor(Theme.primaryText)
        author.setTextColor(Theme.secondaryText)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l

        val thumbWidth = dp(104f)
        val thumbHeight = dp(58f)

        thumb.frame(0, dp(4f), thumbWidth, thumbHeight)

        val textLeft = thumbWidth + dp(10f)
        val textWidth = width - textLeft

        name.frame(textLeft, dp(4f), textWidth, dp(34f))
        author.frame(textLeft, dp(41f), textWidth, dp(16f))

        if (!playing) {
            return
        }

        val side = dp(30f)

        val circleLeft = (thumbWidth - side) / 2
        val circleTop = dp(4f) + (thumbHeight - side) / 2

        marker.frame(circleLeft, circleTop, side, side)

        /**
         * Треугольник 15 внутри кружка 30, сдвинут вправо на точку:
         * `Margin="2,0,0,0"` в оригинале — поправка на то, что
         * зрительный центр треугольника левее геометрического.
         */
        play.frame(
            circleLeft + (side - dp(15f)) / 2 + dp(1f),
            circleTop + (side - dp(15f)) / 2,
            dp(15f), dp(15f)
        )
    }
}
