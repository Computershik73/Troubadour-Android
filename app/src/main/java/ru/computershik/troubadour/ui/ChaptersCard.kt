package ru.computershik.troubadour.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.player.Chapter
import ru.computershik.troubadour.ui.Metrics.dp

/**
 * Карточка глав — порт `layoutChaptersAt:width:`.
 *
 * Отступы 16 по краям страницы, поля 12 внутри, скругление 12. Шапка
 * высотой 40: название «Главы» 14 Bold, под ним 12 приглушённым — какая
 * глава идёт сейчас, справа стрелка. Список раскрывается нажатием
 * по шапке и **свёрнут по умолчанию**, как `_chaptersCollapsed = YES`
 * в оригинале: у длинного ролика глав бывает три десятка, и развёрнутый
 * список отодвинул бы всё остальное за край экрана.
 *
 * Строка: метка времени в плашке 52×20 со скруглением 4, название рядом
 * в две строки, высота — по названию, но не меньше 20, плюс поля 16.
 */
class ChaptersCard(context: Context) : ViewGroup(context) {

    private val card = PillView(context)

    private val header = TappableView(context)
    private val title = label(context, Fonts.bold, 14f, Theme.primaryText, 1)
    private val now = label(context, Fonts.regular, 12f, Theme.secondaryText, 1)
    private val chevron = ImageView(context)

    private val rows = ArrayList<ChapterRow>()

    private var chapters: List<Chapter> = emptyList()

    /** Свёрнута ли — по умолчанию да. */
    private var collapsed = true

    /** Куда прыгать по нажатию на главу. */
    var onPick: ((Double) -> Unit)? = null

    init {
        card.cornerRadius = Metrics.dpf(12f)
        card.fillColor = Theme.surface

        addView(card)

        title.text = loc("Главы")

        header.highlights = false
        header.onTap = {
            collapsed = !collapsed

            applyChevron()
            requestLayout()
        }

        header.addView(title)
        header.addView(now)
        header.addView(chevron)

        chevron.scaleType = ImageView.ScaleType.FIT_CENTER

        addView(header)

        applyChevron()

        visibility = GONE
    }

    private fun applyChevron() {
        chevron.setImageBitmap(Icons.icon("down_arrow"))

        // Свёрнутая — стрелка вниз, развёрнутая — вверх.
        chevron.rotation = if (collapsed) 0f else 180f
    }

    fun bind(list: List<Chapter>) {
        chapters = list

        for (row in rows) {
            removeView(row)
        }

        rows.clear()

        visibility = if (list.isEmpty()) GONE else VISIBLE

        if (list.isEmpty()) {
            return
        }

        for (chapter in list) {
            val row = ChapterRow(context, chapter)

            row.onTap = { onPick?.invoke(chapter.start) }

            rows.add(row)

            addView(row)
        }

        requestLayout()
    }

    /** Какая глава идёт сейчас — подписью в шапке. */
    fun showNow(name: String) {
        now.text = name
    }

    fun applyTheme() {
        card.fillColor = Theme.surface

        title.setTextColor(Theme.primaryText)
        now.setTextColor(Theme.secondaryText)

        applyChevron()

        for (row in rows) {
            row.applyTheme()
        }
    }

    private fun rowHeights(width: Int): List<Int> {
        val inner = width - dp(16f) * 2 - dp(24f)
        val textWidth = inner - dp(8f) - (dp(52f) + dp(10f))

        return rows.map { row ->
            maxOf(
                Metrics.textHeight(row.name(), Fonts.regular, 14f, textWidth, 2),
                dp(20f)
            ) + dp(16f)
        }
    }

    private fun cardHeight(width: Int): Int {
        val headerHeight = dp(40f)

        var listHeight = 0

        if (!collapsed) {
            listHeight = rowHeights(width).sum()

            if (rows.isNotEmpty()) {
                listHeight += dp(10f)
            }
        }

        return dp(12f) + headerHeight + listHeight + dp(12f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)

        setMeasuredDimension(
            width, if (chapters.isEmpty()) 0 else cardHeight(width)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (chapters.isEmpty()) {
            return
        }

        val width = r - l

        val margin = dp(16f)
        val content = width - margin * 2
        val inner = content - dp(24f)

        val headerHeight = dp(40f)

        card.frame(margin, 0, content, cardHeight(width))

        header.frame(margin + dp(12f), dp(12f), inner, headerHeight)

        title.frame(0, dp(2f), inner - dp(26f), dp(18f))
        now.frame(0, dp(22f), inner - dp(26f), dp(16f))

        chevron.frame(
            inner - dp(18f), (headerHeight - dp(18f)) / 2, dp(18f), dp(18f)
        )

        var rowY = dp(12f) + headerHeight + dp(10f)

        val heights = rowHeights(width)

        for (index in rows.indices) {
            val row = rows[index]

            if (collapsed) {
                row.visibility = GONE

                continue
            }

            row.visibility = VISIBLE

            row.frame(margin + dp(12f) + dp(4f), rowY, inner - dp(8f), heights[index])

            rowY += heights[index]
        }
    }
}

/** Строка главы: плашка времени 52×20 и название рядом. */
class ChapterRow(context: Context, private val chapter: Chapter) :
    TappableView(context) {

    private val stamp = PillView(context)
    private val time = label(context, Fonts.regular, 12f, Theme.secondaryText, 1)
    private val name = label(context, Fonts.regular, 14f, Theme.primaryText, 2)

    init {
        highlights = true

        stamp.cornerRadius = Metrics.dpf(4f)
        stamp.fillColor = Theme.surfaceHover

        time.gravity = Gravity.CENTER
        time.text = clock(chapter.start)

        name.text = chapter.title

        addView(stamp)
        addView(time)
        addView(name)
    }

    fun name(): String = chapter.title

    fun applyTheme() {
        stamp.fillColor = Theme.surfaceHover

        time.setTextColor(Theme.secondaryText)
        name.setTextColor(Theme.primaryText)
    }

    private fun clock(seconds: Double): String {
        val whole = seconds.toInt()

        val hours = whole / 3600
        val minutes = (whole % 3600) / 60
        val rest = whole % 60

        if (hours > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, rest)
        }

        return String.format(java.util.Locale.US, "%d:%02d", minutes, rest)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        val stampWidth = dp(52f)
        val textLeft = stampWidth + dp(10f)

        val stampTop = (height - dp(20f)) / 2

        stamp.frame(0, stampTop, stampWidth, dp(20f))
        time.frame(0, stampTop, stampWidth, dp(20f))

        name.frame(textLeft, dp(8f), width - textLeft, maxOf(0, height - dp(16f)))
    }
}
