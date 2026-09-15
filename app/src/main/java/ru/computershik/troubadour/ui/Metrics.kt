package ru.computershik.troubadour.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log

/**
 * Шрифты, значки, отступы.
 *
 * Числа перенесены из XAML UWP-версии как есть — через iOS-порт, где они
 * уже один раз пережили смену платформы без пересчёта. В UWP размеры заданы
 * в эффективных пикселях, в UIKit — в точках, в Android — в независимых
 * точках, и все три единицы означают одно и то же: примерно 1/160 дюйма.
 * Пересчитывать по дороге снова ничего не пришлось.
 *
 * Где число взято, сказано в комментарии у каждого — по имени файла
 * и элемента, чтобы при расхождении было куда посмотреть.
 */
object Metrics {

    // --- Перевод точек в пиксели -----------------------------------------

    private val density: Float
        get() = App.require().resources.displayMetrics.density

    /** Точки XAML → пиксели этого экрана, округлённые. */
    fun dp(points: Float): Int = Math.round(points * density)

    fun dp(points: Int): Int = Math.round(points * density)

    /** То же без округления — там, где число идёт в рисование. */
    fun dpf(points: Float): Float = points * density

    /** Обратно: пиксели → точки. Нужно при разборе размеров экрана. */
    fun points(pixels: Int): Float = pixels / density

    /** Размер шрифта в пикселях. У нас он тоже задан в точках, не в sp. */
    fun sp(points: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, points, App.require().resources.displayMetrics
    )

    // --- Размеры ----------------------------------------------------------

    /**
     * Высота нижней панели без полосы над ней — `RowDefinition Height="50"`
     * в Tabbar.xaml. Полоса над ней — отдельные 2 точки, `Height="2"`.
     */
    const val TAB_BAR_HEIGHT = 50f
    const val TAB_BAR_DIVIDER = 2f

    /** Высота верхней панели — `Height="56"` в Navbar.xaml. */
    const val NAV_BAR_HEIGHT = 56f

    /** Полоса «таблеток» категорий — `Height="48"` в Home.xaml. */
    const val CHIPS_BAR_HEIGHT = 48f

    /** Сама «таблетка»: высота 36, скругление 9, отступы по 14 (Home.xaml). */
    const val CHIP_HEIGHT = 36f
    const val CHIP_RADIUS = 9f
    const val CHIP_PADDING = 14f

    /** Отступы ленты карточек — `Padding="8,8,8,16"` в Home.xaml. */
    const val FEED_PADDING = 8f

    /** Между карточками — `Margin="0,0,0,16"` у шаблона карточки. */
    const val CARD_SPACING = 16f

    /** Скругление превью — `CornerRadius="8"` у подложки карточки. */
    const val THUMB_RADIUS = 8f

    /** Кружок канала в карточке — `Width="36" Height="36"`. */
    const val CARD_AVATAR = 36f

    /**
     * Желаемая ширина карточки. В UWP разбиение по колонкам делает
     * `ItemsWrapGrid ItemWidth="360" MaximumRowsOrColumns="3"`.
     */
    const val CARD_WIDTH = 360f

    /**
     * Сколько карточек в ряду при такой ширине списка (в точках).
     *
     * `ItemsWrapGrid ItemWidth="360" MaximumRowsOrColumns="3"` из Home.xaml:
     * сколько карточек шириной 360 помещается, столько и колонок, но
     * не больше трёх.
     *
     * Ширина здесь — уже за вычетом отступов списка, поэтому считать нужно
     * от неё, а не от экрана: на телефоне 320 точек за вычетом 8+8 остаётся
     * 304, и это честно одна колонка.
     */
    fun columnsForWidth(width: Float): Int {
        var columns = Math.floor((width / CARD_WIDTH).toDouble()).toInt()

        /**
         * На широком экране считаем не по 360, а по 300 точек.
         *
         * Число 360 из оригинала выведено для экранов Windows 10 Mobile —
         * от 480 до 720 точек, — и там три колонки набирались только на самых
         * широких. Планшет в это правило не укладывается вовсе: буквальное
         * правило даёт две колонки, карточка выходит в полтысячи точек
         * шириной, и на экран помещается ровно две штуки.
         *
         * Потолок в три колонки остаётся: `MaximumRowsOrColumns="3"`.
         */
        if (width >= 800) {
            columns = Math.floor((width / 300.0)).toInt()
        }

        return columns.coerceIn(1, 3)
    }

    // --- Текст ------------------------------------------------------------

    private val measurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Высота текста в заданной ширине, но не больше maxLines строк.
     *
     * Здесь, в отличие от оригинала, замер честный: `StaticLayout` считает
     * ровно то, что потом нарисует. В iOS-версии на 5.1 доступен был только
     * `sizeWithFont:constrainedToSize:`, и там пришлось держать запас
     * в точку — иначе высота двух строк оказывалась на доли точки больше,
     * чем `lineHeight × 2`, в потолок помещалась одна, и подпись, которой
     * разрешено две строки, получала рамку в одну.
     *
     * @param width ширина в пикселях, [maxLines] 0 — без ограничения.
     * @return высота в пикселях.
     */
    @Suppress("DEPRECATION")
    fun textHeight(
        text: String?,
        typeface: Typeface,
        sizePoints: Float,
        width: Int,
        maxLines: Int
    ): Int {
        if (text.isNullOrEmpty() || width <= 0) {
            return 0
        }

        synchronized(measurePaint) {
            measurePaint.typeface = typeface
            measurePaint.textSize = sp(sizePoints)

            val layout = StaticLayout(
                text, measurePaint, width,
                Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false
            )

            val lines = if (maxLines > 0) minOf(layout.lineCount, maxLines) else layout.lineCount

            if (lines <= 0) {
                return 0
            }

            return layout.getLineBottom(lines - 1) - layout.getLineTop(0)
        }
    }

    /** Высота одной строки этим начертанием — нужна для расчёта рамок. */
    fun lineHeight(typeface: Typeface, sizePoints: Float): Int {
        synchronized(measurePaint) {
            measurePaint.typeface = typeface
            measurePaint.textSize = sp(sizePoints)

            val metrics = measurePaint.fontMetricsInt

            return metrics.descent - metrics.ascent
        }
    }

    /** Ширина строки этим начертанием, в пикселях. */
    fun textWidth(text: String?, typeface: Typeface, sizePoints: Float): Int {
        if (text.isNullOrEmpty()) {
            return 0
        }

        synchronized(measurePaint) {
            measurePaint.typeface = typeface
            measurePaint.textSize = sp(sizePoints)

            return Math.ceil(measurePaint.measureText(text).toDouble()).toInt()
        }
    }

    /**
     * Обрезает слишком длинный текст, добавляя многоточие.
     *
     * Нужно там, где число строк не ограничено, — в описании ролика
     * и в комментариях. В оригинале это спасало от снятия по таймауту:
     * замер такого текста шёл через CoreText, и на A4 тридцать три тысячи
     * знаков комментариев стоили секунд. `StaticLayout` быстрее, но не
     * настолько, чтобы про потолок забыть.
     *
     * Граница отодвигается к началу ближайшего целого символа:
     * `substring` считает в кодовых единицах UTF-16, а эмодзи занимают две.
     * Резать между ними нельзя — получается половина пары, и строка
     * перестаёт быть правильным UTF-16.
     */
    fun clampText(text: String?, limit: Int): String {
        if (text == null) {
            return ""
        }

        if (text.length <= limit) {
            return text
        }

        var cut = limit

        // Отодвигаемся назад, пока не встанем на границу символа.
        while (cut > 0 && Character.isLowSurrogate(text[cut])) {
            cut--
        }

        return text.substring(0, cut) + "…"
    }

    /** Многоточие по краю в готовой ширине — для однострочных подписей. */
    fun ellipsize(text: String?, typeface: Typeface, sizePoints: Float, width: Int): String {
        if (text.isNullOrEmpty() || width <= 0) {
            return text ?: ""
        }

        synchronized(measurePaint) {
            measurePaint.typeface = typeface
            measurePaint.textSize = sp(sizePoints)

            return TextUtils.ellipsize(
                text, measurePaint, width.toFloat(), TextUtils.TruncateAt.END
            ).toString()
        }
    }
}

/**
 * Начертания Roboto.
 *
 * В UWP шрифт назначен всему тексту разом стилями в App.xaml, поэтому
 * насыщенность у каждой подписи стоит своя: `FontWeight="Medium"` у названий
 * роликов, `SemiBold` у заголовков, ничего (то есть Regular) у подписей.
 *
 * Файл в UWP один и вариативный: начертание получается положением на оси
 * `wght`. Android до 8.0 такого не понимает — ровно та же беда, что была
 * в iOS-версии, — поэтому берутся четыре готовых файла, выпеченных
 * `tools/make-fonts.sh` оригинала: 400 Regular, 500 Medium, 600 SemiBold,
 * 700 Bold.
 *
 * Робото в самой системе есть с Android 4.0, но только Regular, Medium
 * и Bold, а SemiBold нет вовсе — и системный Roboto на 4.1 старше того,
 * что лежит в UWP-версии, на десять лет. Поэтому файлы едут в связку,
 * как и там.
 *
 * Если шрифт почему-либо не прочитался, вместо него берётся системный той же
 * насыщенности: чужой шрифт лучше пустого экрана.
 */
object Fonts {

    private val cache = HashMap<String, Typeface>()

    val regular: Typeface get() = load("Roboto-Regular", Typeface.NORMAL)
    val medium: Typeface get() = load("Roboto-Medium", Typeface.NORMAL)
    val semiBold: Typeface get() = load("Roboto-SemiBold", Typeface.BOLD)
    val bold: Typeface get() = load("Roboto-Bold", Typeface.BOLD)

    private fun load(name: String, fallbackStyle: Int): Typeface {
        synchronized(cache) {
            cache[name]?.let { return it }

            val typeface = try {
                Typeface.createFromAsset(App.require().assets, "fonts/$name.ttf")
            } catch (error: Exception) {
                Log.d { "[YouTube/Шрифты] Не прочитан $name, беру системный" }
                Typeface.defaultFromStyle(fallbackStyle)
            }

            cache[name] = typeface

            return typeface
        }
    }
}

/**
 * Значки из набора UWP.
 *
 * В UWP они лежат двумя наборами, `Dark` и `Light`, и различаются цветом
 * глифа; выбор набора делает `ThemeAsset.Path`. Здесь то же самое делает
 * [icon] — по имени без суффикса: `search`, `tab_home`, `pl_pause`.
 *
 * Файлы взяты у iOS-порта, где они уже уменьшены до нужных размеров:
 * в оригинале значки лежат по 400–512 точек в стороне, потому что UWP
 * масштабирует их сам под плотность экрана. Здесь под плотность файл
 * выбирает система — @1x лёг в drawable-mdpi, @2x в xhdpi, @3x в xxhdpi, —
 * и держать в памяти полтысячи точек ради значка в 24 не приходится.
 */
object Icons {

    private val cache = HashMap<String, Bitmap?>()

    /** Сбрасывает кеш — при смене темы набор меняется целиком. */
    fun drop() {
        synchronized(cache) {
            cache.clear()
        }
    }

    /** Значок под текущую тему. */
    fun icon(name: String): Bitmap? {
        if (name.isEmpty()) {
            return null
        }

        return image(name + if (Theme.isDark) "_dark" else "_light")
    }

    /**
     * Значок **всегда** из тёмного набора, какая бы тема ни стояла.
     *
     * В оригинале это записано прямо в разметке: у пульта плеера и у столбца
     * кнопок Shorts путь зашит как `Assets/Dark/…`, а не выбирается темой.
     * Причина простая — эти значки лежат поверх кадра, а кадр тёмный всегда.
     * Светлый набор на нём попросту не виден.
     */
    fun darkIcon(name: String): Bitmap? {
        if (name.isEmpty()) {
            return null
        }

        return image(name + "_dark")
    }

    /** Картинка, одинаковая в обеих темах: заглушки, «нет сети». */
    fun image(name: String): Bitmap? {
        if (name.isEmpty()) {
            return null
        }

        synchronized(cache) {
            if (cache.containsKey(name)) {
                return cache[name]
            }

            val bitmap = read(name)

            if (bitmap == null) {
                Log.d { "[YouTube/Значки] Не найден значок $name" }
            }

            cache[name] = bitmap

            return bitmap
        }
    }

    /**
     * Ищет ресурс по имени.
     *
     * `getIdentifier` — рефлексия по таблице ресурсов, и она стоит заметно
     * дороже прямого `R.drawable.…`. Но набор выбирается темой в момент
     * обращения, имена собираются из частей, и списка из двух с половиной
     * сотен констант ради этого заводить незачем: результат всё равно
     * оседает в кеше, и второй раз до поиска дело не доходит.
     *
     * Расплата — ужимальщик ресурсов, который таких обращений не видит.
     * Поэтому `shrinkResources` в сборке выключён; так и записано
     * в `build.gradle`.
     */
    private fun read(name: String): Bitmap? {
        val context: Context = App.context ?: return null

        return try {
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)

            if (id == 0) {
                return null
            }

            val options = BitmapFactory.Options()

            // Значки одноцветные с прозрачностью; 8888 нужен ради краёв.
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            BitmapFactory.decodeResource(context.resources, id, options)
        } catch (error: Throwable) {
            null
        }
    }

    /**
     * Тот же значок, но перекрашенный.
     *
     * Нужен там, где состояние помечается цветом, а второго значка в наборе
     * нет: у лайка есть `pl_like_on`, а у стрелки скачивания — нет. Рисуем
     * исходный как трафарет и заливаем цветом.
     *
     * Значки у нас одноцветные с прозрачным фоном, поэтому исходная картинка
     * годится трафаретом как есть: заливка ложится ровно туда, где были
     * непрозрачные точки, и края остаются мягкими.
     */
    fun tinted(source: Bitmap?, color: Int): Bitmap? {
        if (source == null || source.width <= 0 || source.height <= 0) {
            return source
        }

        val painted = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(painted)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

        canvas.drawBitmap(source, 0f, 0f, paint)

        return painted
    }

    /** Кеш перекрашенных: цвет темы у значка меняется редко, а зовут его часто. */
    private val painted = HashMap<String, Bitmap?>()

    fun tinted(name: String, color: Int): Bitmap? {
        val key = "$name#${Integer.toHexString(color)}"

        synchronized(painted) {
            if (painted.containsKey(key)) {
                return painted[key]
            }

            val result = tinted(image(name), color)

            if (painted.size > 200) {
                painted.clear()
            }

            painted[key] = result

            return result
        }
    }
}

/** Цвет с изменённой непрозрачностью — то же, что `colorWithAlphaComponent:`. */
fun Int.withAlpha(alpha: Float): Int {
    val value = (alpha.coerceIn(0f, 1f) * 255).toInt()

    return Color.argb(value, Color.red(this), Color.green(this), Color.blue(this))
}
