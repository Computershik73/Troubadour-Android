package ru.computershik.troubadour.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.TextView
import ru.computershik.troubadour.safeText

/**
 * Подпись, которая не падает от битой строки.
 *
 * Главный присмотр стоит у входа — при разборе ответа сервера, — но текст
 * попадает в подпись и не оттуда: из настроек, из сохранённого имени,
 * из чужой строки в будущем коде. Проверка здесь обычному тексту ничего
 * не стоит и закрывает эти пути разом.
 *
 * На Android битая пара не роняет отрисовку так, как роняла CoreText,
 * но результат её всё равно негодный: половина суррогата рисуется тофу,
 * а при передаче такой строки наружу — в «Поделиться», в имя файла —
 * ломается уже принимающая сторона.
 */
@SuppressLint("ViewConstructor")
open class SafeTextView(context: Context) : TextView(context) {

    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(safeText(text?.toString()), type)
    }
}

/**
 * Подпись с обрезкой по краю и заданным числом строк.
 *
 * @param lines 0 — без ограничения.
 */
@SuppressLint("SetTextI18n")
fun label(
    context: Context,
    typeface: Typeface,
    sizePoints: Float,
    color: Int,
    lines: Int
): TextView {
    val view = SafeTextView(context)

    view.typeface = typeface
    view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, sizePoints)
    view.setTextColor(color)
    view.includeFontPadding = false

    /**
     * Текст стоит посередине отведённой ему рамки, а не по её верху.
     *
     * `UILabel` в оригинале центрирует по вертикали сам, `TextView` —
     * прижимает к верху. Разница незаметна, пока рамка ровно по тексту,
     * и вылезает всюду, где она выше: строки листа, подписи вкладок,
     * заголовки полос, плашка времени. Кому нужно иначе, тот ставит
     * `gravity` после — своё значение перекроет это.
     */
    view.gravity = Gravity.CENTER_VERTICAL

    if (lines > 0) {
        view.maxLines = lines
        view.ellipsize = TextUtils.TruncateAt.END
    }

    // Подписи ничего не принимают: нажатие должно доставаться карточке,
    // внутри которой они лежат. У TextView флаг и так снят, но у нас он
    // проставлен везде явно — чтобы правило было видно в коде.
    view.isClickable = false
    view.isFocusable = false

    // Отступов у TextView по умолчанию нет, а вот фон есть у некоторых тем —
    // снимаем, чтобы подпись была ровно подписью.
    view.setBackgroundColor(Color.TRANSPARENT)
    view.setPadding(0, 0, 0, 0)

    return view
}

/**
 * Плашка длительности поверх превью.
 *
 * Порт шаблона из Home.xaml: `Background="#CC000000"`, `CornerRadius="4"`,
 * `Padding="6,2"`, подпись 12 точек начертанием Medium, белая.
 *
 * Своим рисованием, а не фоном-drawable: у карточки поля 6×2, а у строки
 * истории 4×1 (Me.xaml), и заводить два ресурса ради разницы в два пикселя
 * незачем.
 */
class BadgeLabel(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    /** У карточки 6×2, у строки истории — 4×1 (Me.xaml). */
    var padHorizontal = Metrics.dpf(6f)
    var padVertical = Metrics.dpf(2f)

    var radius = Metrics.dpf(4f)

    var text: String = ""
        set(value) {
            field = safeText(value)
            requestLayout()
            invalidate()
        }

    init {
        textPaint.typeface = Fonts.medium
        textPaint.textSize = Metrics.sp(12f)
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER

        isClickable = false
        isFocusable = false
    }

    fun setTextSize(points: Float) {
        textPaint.textSize = Metrics.sp(points)
        requestLayout()
    }

    /** Размер под текущий текст; пустой текст даёт нулевой размер. */
    fun badgeWidth(): Int {
        if (text.isEmpty()) {
            return 0
        }

        return Math.ceil((textPaint.measureText(text) + padHorizontal * 2).toDouble()).toInt()
    }

    fun badgeHeight(): Int {
        if (text.isEmpty()) {
            return 0
        }

        val metrics = textPaint.fontMetrics

        return Math.ceil((metrics.descent - metrics.ascent + padVertical * 2).toDouble()).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(badgeWidth(), widthMeasureSpec),
            resolveSize(badgeHeight(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty() || width <= 0 || height <= 0) {
            return
        }

        // Плашка одинакова в обеих темах: она лежит поверх кадра, а не
        // поверх страницы, и её фон задан числом (#CC000000), а не кистью темы.
        paint.color = Theme.BADGE

        box.set(0f, 0f, width.toFloat(), height.toFloat())

        canvas.drawRoundRect(box, radius, radius, paint)

        val metrics = textPaint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f

        canvas.drawText(text, width / 2f, baseline, textPaint)
    }
}

/**
 * Прямоугольник со скруглением и заливкой — «таблетка» категории, кнопка
 * «Подписаться», подложка блока комментариев.
 *
 * Своим рисованием, а не `GradientDrawable`: тот пришлось бы заводить
 * на каждое сочетание цвета и радиуса, а меняются они у нас на лету —
 * выбранная таблетка перекрашивается, кнопка подписки меняет и цвет,
 * и ширину.
 *
 * В оригинале причина была ещё резче: `layer.cornerRadius` заставлял
 * систему рисовать слой отдельным проходом, и на iPhone 4 в прокручиваемом
 * списке это было заметно. Здесь `drawRoundRect` дешевле любого drawable.
 */
class PillView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    var fillColor: Int = Theme.surface
        set(value) {
            field = value
            invalidate()
        }

    var cornerRadius: Float = Metrics.dpf(Metrics.CHIP_RADIUS)
        set(value) {
            field = value
            invalidate()
        }

    /** Обводка вместо заливки — у неподсвеченной кнопки «Подписаться». */
    var strokeColor: Int = Color.TRANSPARENT
        set(value) {
            field = value
            invalidate()
        }

    var strokeWidth: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    init {
        // Только рисует — нажатие принимает то, внутри чего он лежит.
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) {
            return
        }

        // Скругление не больше половины меньшей стороны: у кнопок вроде
        // «Подписаться» радиус задаётся заведомо большим числом, чтобы
        // получились полукруглые торцы, и без этой поправки дуги наложились
        // бы друг на друга.
        val radius = minOf(cornerRadius, minOf(width, height) / 2f)

        box.set(0f, 0f, width.toFloat(), height.toFloat())

        if (Color.alpha(fillColor) != 0) {
            paint.style = Paint.Style.FILL
            paint.color = fillColor

            canvas.drawRoundRect(box, radius, radius, paint)
        }

        if (strokeWidth > 0 && Color.alpha(strokeColor) != 0) {
            paint.style = Paint.Style.STROKE
            paint.color = strokeColor
            paint.strokeWidth = strokeWidth

            val half = strokeWidth / 2

            box.set(half, half, width - half, height - half)

            canvas.drawRoundRect(box, radius, radius, paint)
        }
    }
}

/**
 * Тонкая черта — разделитель между строками и полоса над нижней панелью.
 *
 * Толщина в один пиксель, а не в одну точку: на плотном экране точка — это
 * три пикселя, и разделитель выглядел бы жирной чертой. В XAML у него
 * `Height="1"` при плотности единица, то есть ровно пиксель.
 */
class HairlineView(context: Context) : View(context) {

    var color: Int = Theme.divider
        set(value) {
            field = value
            setBackgroundColor(value)
        }

    init {
        setBackgroundColor(color)
        isClickable = false
        isFocusable = false
    }

    companion object {
        /** Один пиксель, но не меньше единицы. */
        fun thickness(): Int = 1
    }
}

/**
 * Положить вид в рамку — то же, что `setFrame:` в UIKit.
 *
 * **Мера обязательна, и это не формальность.** В UIKit рамка сама себе
 * закон: назначил — вид её и займёт. В Android вид сперва меряют, потом
 * кладут, и `TextView` строит разметку текста именно в момент меры.
 * Положить его в рамку, отличную от измеренной, — значит нарисовать
 * текст мимо: подпись вкладки, измеренная на пятьдесят точек и уложенная
 * в четырнадцать, не показалась вовсе, а «Нет подключения к интернету»
 * в узкой колонке показалось как «Нет».
 *
 * Поэтому всякая ручная раскладка в этом проекте идёт через эту функцию,
 * а не через голый `layout`.
 */
fun View.frame(left: Int, top: Int, width: Int, height: Int) {
    val exactWidth = maxOf(0, width)
    val exactHeight = maxOf(0, height)

    measure(
        View.MeasureSpec.makeMeasureSpec(exactWidth, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(exactHeight, View.MeasureSpec.EXACTLY)
    )

    layout(left, top, left + exactWidth, top + exactHeight)
}

/** Гравитация по языку: справа налево у арабского и фарси. */
fun textGravity(): Int =
    if (ru.computershik.troubadour.Strings.isRightToLeft()) Gravity.RIGHT else Gravity.LEFT

/**
 * Кнопка-таблетка: заливка со скруглением и подпись по центру.
 *
 * Заведена после первой же сборки на устройстве, и вот почему. Собирать её
 * из `FrameLayout` с двумя детьми — заливкой `MATCH_PARENT` и подписью
 * `WRAP_CONTENT` — нельзя: `FrameLayout` меряет себя по детям с `WRAP_CONTENT`,
 * а заливка к ним не относится, и при `WRAP_CONTENT` у самого кадра выходит
 * вырожденный случай. На экране это «Войти» во весь экран и полоса оценки
 * во всю строку, вытеснившая соседей.
 *
 * Здесь размер считает подпись, а заливка растягивается под него.
 */
open class PillButton(context: Context) : TappableView(context) {

    val pill = PillView(context)

    val title: TextView = label(context, Fonts.semiBold, 13f, Theme.primaryActionForeground, 1)

    /** Значок слева от подписи; по умолчанию его нет. */
    var icon: android.widget.ImageView? = null
        private set

    init {
        addView(
            pill,
            LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        addView(
            title,
            LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        pill.cornerRadius = Metrics.dpf(18f)
    }

    fun withIcon(name: String, side: Float = 20f) {
        if (icon != null) {
            return
        }

        val image = android.widget.ImageView(context)

        image.setImageBitmap(Icons.icon(name))
        image.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER

        addView(
            image,
            LayoutParams(Metrics.dp(side), Metrics.dp(side), Gravity.CENTER_VERTICAL)
        )

        icon = image
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        title.measure(free, free)

        val gap = if (icon != null) Metrics.dp(6f) else 0
        val iconWidth = icon?.let { Metrics.dp(20f) } ?: 0

        var width = paddingLeft + iconWidth + gap + title.measuredWidth + paddingRight
        var height = paddingTop + maxOf(title.measuredHeight, iconWidth) + paddingBottom

        /**
         * Внешнюю меру уважаем: кнопка может стоять и в колонке шириной
         * во весь экран, и в строке, где ей отведено ровно своё.
         */
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            width = MeasureSpec.getSize(widthMeasureSpec)
        }

        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            height = MeasureSpec.getSize(heightMeasureSpec)
        }

        setMeasuredDimension(width, height)

        val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val exactHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)

        pill.measure(exactWidth, exactHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        pill.layout(0, 0, width, height)

        val image = icon

        var left = paddingLeft

        if (image != null) {
            val side = Metrics.dp(20f)

            image.layout(left, (height - side) / 2, left + side, (height + side) / 2)

            left += side + Metrics.dp(6f)
        }

        val titleWidth = minOf(title.measuredWidth, width - left - paddingRight)
        val titleHeight = title.measuredHeight

        val start = if (image == null) (width - titleWidth) / 2 else left

        title.layout(
            start, (height - titleHeight) / 2,
            start + titleWidth, (height + titleHeight) / 2
        )
    }
}

/**
 * Кнопка подписки со страницы ролика — порт `_subscribeFill`.
 *
 * У подписанного в неё встают колокольчик 22 и стрелка 16 с отступом 6,
 * и кнопка обжимает их ширину: `подпись + 28 + (22 + 6 + 16 + 6)`.
 * Высота — строка подписи плюс 14. Нажатие у подписанного открывает
 * оповещения (отписка там же, отдельной строкой), у остальных подписывает.
 */
class SubscribeButton(context: Context) : TappableView(context) {

    val pill = PillView(context)

    val title: TextView = label(context, Fonts.semiBold, 13f, Theme.primaryActionForeground, 1)

    private val bell = android.widget.ImageView(context)
    private val chevron = android.widget.ImageView(context)

    var subscribed: Boolean = false
        set(value) {
            field = value

            bell.visibility = if (value) VISIBLE else GONE
            chevron.visibility = bell.visibility

            requestLayout()
        }

    init {
        addView(pill)

        title.gravity = Gravity.CENTER

        addView(title)

        bell.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        chevron.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER

        chevron.setImageBitmap(Icons.icon("down_arrow"))

        bell.visibility = GONE
        chevron.visibility = GONE

        addView(bell)
        addView(chevron)

        pill.cornerRadius = Metrics.dpf(18f)
    }

    /** Значок колокольчика меняется вместе с выбранным родом оповещений. */
    fun setBellIcon(name: String) {
        bell.setImageBitmap(Icons.icon(name))
    }

    private fun bellBlock(): Int =
        if (subscribed) Metrics.dp(22f + 6f + 16f + 6f) else 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val text = Metrics.textWidth(title.text.toString(), Fonts.semiBold, 13f)

        val width = text + Metrics.dp(28f) + bellBlock()
        val height = Metrics.lineHeight(Fonts.semiBold, 13f) + Metrics.dp(14f)

        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        pill.frame(0, 0, width, height)

        val block = bellBlock()

        val textWidth = width - block

        title.frame(0, 0, textWidth, height)

        if (!subscribed) {
            return
        }

        // Подпись слева, за ней колокольчик и стрелка.
        val bellLeft = textWidth - Metrics.dp(8f)

        bell.frame(
            bellLeft, (height - Metrics.dp(22f)) / 2, Metrics.dp(22f), Metrics.dp(22f)
        )

        chevron.frame(
            bellLeft + Metrics.dp(22f) + Metrics.dp(6f),
            (height - Metrics.dp(16f)) / 2, Metrics.dp(16f), Metrics.dp(16f)
        )
    }
}
