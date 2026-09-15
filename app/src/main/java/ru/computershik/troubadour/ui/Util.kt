package ru.computershik.troubadour.ui

import android.os.Build
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.loc
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Работа в фоне.
 *
 * В UWP-версии это `async/await` поверх `HttpClient`, в iOS-версии —
 * `dispatch_async` на общей очереди. Здесь пул: все методы Api синхронные
 * и ждут ответа, поэтому потоки нужны настоящие, а не корутины поверх
 * одного — иначе десяток экранов, начавших грузиться разом, выстроился бы
 * в очередь.
 *
 * Размер пула не безграничен нарочно. Экранов, способных начать загрузку
 * одновременно, у нас пять-шесть, плюс превью со своим пулом; больше
 * восьми одновременных запросов к YouTube — это не быстрее, а медленнее:
 * они начинают мешать друг другу за канал, и первый ответ приходит позже.
 */
private val background: ThreadPoolExecutor =
    Executors.newFixedThreadPool(8) { runnable ->
        Thread(runnable, "troubadour-work").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    } as ThreadPoolExecutor

private val mainHandler = Handler(Looper.getMainLooper())

fun async(work: () -> Unit) {
    background.execute {
        try {
            work()
        } catch (error: Throwable) {
            /**
             * Исключение в фоновом потоке снимает всё приложение, и в отчёте
             * оно выглядит как падение на ровном месте: следа, откуда пришла
             * работа, там нет.
             *
             * Ловим и пишем. Экран, чья загрузка сорвалась, останется
             * с кольцом ожидания — это плохо, но лучше, чем снятое
             * приложение, и в журнале видно, что именно сорвалось.
             */
            Log.now { "[YouTube] Ошибка в фоне: ${error.javaClass.simpleName}: ${error.message}" }
        }
    }
}

/** Вернуться на главный поток. */
fun main(work: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        work()
        return
    }

    mainHandler.post(work)
}

/** Отложенная работа на главном потоке. */
fun mainAfter(millis: Long, work: () -> Unit) {
    mainHandler.postDelayed(work, millis)
}

fun cancelMain(work: Runnable) {
    mainHandler.removeCallbacks(work)
}

/**
 * Отменяемая фоновая задача. Заменяет `CancellationToken` UWP-версии:
 * у экрана есть номер поколения, и ответ, пришедший от прежней вкладки,
 * свою ленту уже не дописывает.
 */
class Generation {

    @Volatile
    var current: Int = 0
        private set

    /** Начинает новое поколение и возвращает его номер. */
    fun next(): Int {
        current += 1

        return current
    }

    /** Актуален ли ещё ответ этого поколения. */
    fun isCurrent(generation: Int): Boolean = generation == current
}

/**
 * Вид, принимающий нажатие.
 *
 * В UWP карточка — это `Button`, и всё её содержимое нажимается заодно
 * с ней. В UIKit пришлось заводить отдельный вид, потому что нажатие
 * забирает себе тот, на который попали.
 *
 * На Android правило третье: нажатие получает самый глубокий вид,
 * у которого включён `clickable`, а у остальных оно всплывает вверх
 * по дереву. То есть достаточно **не включать** его у содержимого —
 * что и делает [label]. Этот класс остаётся ради двух вещей, которых
 * даром не даётся: подсветки под пальцем и долгого нажатия.
 */
open class TappableView(context: Context) : FrameLayout(context) {

    var onTap: (() -> Unit)? = null

    /**
     * Долгое нажатие; при нём обычное не засчитывается.
     *
     * Заводится только тем, кому назначено: виды без него остаются
     * на голых касаниях, как были.
     */
    var onHold: (() -> Unit)? = null

    /** Подсветка под пальцем — `AppSurfaceHoverColor` из App.xaml. */
    var highlights: Boolean = false

    private var pressedNow = false
    private var held = false

    private val holdRunnable = Runnable {
        val action = onHold ?: return@Runnable

        held = true
        setHighlighted(false)
        action()
    }

    init {
        isClickable = true
        isFocusable = true
    }

    private fun setHighlighted(on: Boolean) {
        if (!highlights) {
            return
        }

        setBackgroundColor(if (on) Theme.surfaceHover else Color.TRANSPARENT)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedNow = true
                held = false

                setHighlighted(true)

                if (onHold != null) {
                    postDelayed(holdRunnable, HOLD_MILLIS)
                }

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pressedNow) {
                    return true
                }

                // Палец ушёл за пределы — нажатие отменяется, как в UIKit.
                if (!inside(event)) {
                    pressedNow = false

                    removeCallbacks(holdRunnable)
                    setHighlighted(false)
                }

                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                setHighlighted(false)

                val fire = pressedNow && !held

                pressedNow = false

                if (fire) {
                    onTap?.invoke()
                }

                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                setHighlighted(false)

                pressedNow = false

                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun inside(event: MotionEvent): Boolean {
        return event.x >= 0 && event.y >= 0 && event.x <= width && event.y <= height
    }

    companion object {
        /** Столько же, сколько у штатного долгого нажатия UIKit. */
        private const val HOLD_MILLIS = 500L
    }
}

/**
 * Кольцо ожидания.
 *
 * Порт `AndroidLoadingRing.xaml` — в UWP-версии это отдельный контрол
 * с дугой, крутящейся по кругу, потому что штатный `ProgressRing` там
 * выглядит иначе. Здесь то же самое: дуга в четверть окружности, оборот
 * за секунду, цвет `LoadingRingColor`.
 *
 * Системный `ProgressBar` не годится по той же причине, по какой не годился
 * там: у него свой рисунок, и он меняется от версии Android к версии.
 */
class LoadingRing(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    private var spinning = false
    private var startedAt = 0L

    var color: Int = Theme.loadingRing
        set(value) {
            field = value
            invalidate()
        }

    /** Толщина дуги — `StrokeThickness="3"` в разметке оригинала. */
    var thickness: Float = Metrics.dpf(3f)

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        isClickable = false
        isFocusable = false
    }

    fun start() {
        if (spinning) {
            return
        }

        spinning = true
        startedAt = SystemClock.uptimeMillis()

        visibility = VISIBLE
        invalidate()
    }

    fun stop() {
        spinning = false
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        if (!spinning || width <= 0 || height <= 0) {
            return
        }

        paint.color = color
        paint.strokeWidth = thickness

        val inset = thickness / 2 + 1
        val side = minOf(width, height).toFloat()

        val left = (width - side) / 2 + inset
        val top = (height - side) / 2 + inset

        box.set(left, top, left + side - inset * 2, top + side - inset * 2)

        // Оборот за секунду — `Duration="0:0:1"` у анимации оригинала.
        val elapsed = (SystemClock.uptimeMillis() - startedAt) % 1000L
        val angle = elapsed / 1000f * 360f

        // Дуга в четверть окружности.
        canvas.drawArc(box, angle - 90f, 90f, false, paint)

        // Следующий кадр. Своим приглашением, а не аниматором: аниматоры
        // появились в API 11 и на слабом устройстве стоят дороже, чем один
        // вызов invalidate.
        postInvalidateOnAnimationCompat()
    }

    private fun postInvalidateOnAnimationCompat() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
            postInvalidateOnAnimation()
        } else {
            postInvalidateDelayed(16)
        }
    }
}

/**
 * Подгрузка следующих страниц списка при прокрутке.
 *
 * У InnerTube страницы размечены не номерами, а «токенами продолжения»:
 * в конце списка лежит `continuationItemRenderer` с непрозрачной строкой,
 * которую надо отправить обратно тем же запросом. Собрать её на клиенте
 * нельзя — она подписана сервером.
 *
 * Здесь только учёт: сам токен, признак занятости и порог прокрутки. Ходит
 * в сеть и разбирает ответ по-прежнему сам экран — списки разные, и
 * складывать в ленту им нужно разное.
 *
 * Занятость нужна не для порядка, а по существу: прокрутка сообщается
 * на каждый кадр, и без неё одна и та же страница запрашивалась бы
 * десятками. Поэтому [claim], отвечая «пора», сразу и занимает себя —
 * проверка и захват одним действием.
 */
class Pager {

    /** Токен следующей страницы; пусто — страниц больше нет. */
    var token: String = ""

    private var busy = false

    /** Есть ли что грузить. */
    val hasMore: Boolean get() = token.isNotEmpty()

    /** Новый список: прежние страницы забываются. */
    fun reset() {
        token = ""
        busy = false
    }

    /**
     * Пора ли грузить, и если пора — занять себя.
     *
     * Порог — два экрана до конца. В оригинале столько же: страница
     * приезжает не мгновенно, и запас в один экран на медленной сети
     * кончается раньше, чем приходит ответ.
     */
    fun claim(visibleEnd: Int, total: Int, perScreen: Int): Boolean {
        if (busy || !hasMore) {
            return false
        }

        if (total <= 0) {
            return false
        }

        if (visibleEnd < total - perScreen * 2) {
            return false
        }

        busy = true

        return true
    }

    /** Загрузка кончилась — можно просить следующую. */
    fun finish() {
        busy = false
    }
}

/**
 * Сколько отмерить сверху под строку состояния — **в нашей раскладке**.
 *
 * С Android 5 окно рисуется под прозрачной строкой состояния
 * (`SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` в [ru.computershik.troubadour.ui.MainActivity]),
 * как это делал `YTUseFullScreenLayout` в оригинале, — и высоту её
 * панели отмеряют сами.
 *
 * До Android 5 окно под строку не заходит: система сдвигает его вниз
 * сама. Отмерить ещё раз — значит получить двойной отступ, а на планшете
 * с нижней системной полосой ещё и пустую полосу ни от чего: там
 * `status_bar_height` объявлен, но сверху ничего не занимает.
 *
 * Число берётся у системы каждый раз: строка состояния бывает вдвое
 * выше во время звонка.
 */
/**
 * Сколько занимает системная полоса снизу, точек.
 *
 * В полноэкранном виде окно уходит под неё (`LAYOUT_HIDE_NAVIGATION`),
 * и, пока полоса видна, она закрывает нижнюю часть кадра вместе
 * с пультом. На планшете полоса внизу всегда; на телефоне в лежачем
 * виде она обычно сбоку, и тогда снизу занимать нечего.
 */
fun navigationBarHeight(): Int {
    val resources = App.require().resources

    val metrics = resources.displayMetrics

    val shortest = minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density

    val landscape = metrics.widthPixels > metrics.heightPixels

    // У телефона в лежачем виде полоса сбоку, а не снизу.
    if (shortest < 600 && landscape) {
        return 0
    }

    val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")

    if (id <= 0) {
        return 0
    }

    return resources.getDimensionPixelSize(id)
}

/** То же сбоку: у телефона в лежачем виде полоса стоит справа. */
fun navigationBarWidth(): Int {
    val resources = App.require().resources

    val metrics = resources.displayMetrics

    val shortest = minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density

    val landscape = metrics.widthPixels > metrics.heightPixels

    if (shortest >= 600 || !landscape) {
        return 0
    }

    val id = resources.getIdentifier("navigation_bar_width", "dimen", "android")

    if (id <= 0) {
        return 0
    }

    return resources.getDimensionPixelSize(id)
}

fun statusBarHeight(): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
        return 0
    }

    val resources = App.require().resources
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")

    if (id <= 0) {
        return 0
    }

    return resources.getDimensionPixelSize(id)
}
