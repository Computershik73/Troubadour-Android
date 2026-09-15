package ru.computershik.troubadour.ui

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.net.Http
import ru.computershik.troubadour.ui.Metrics.dp

/** На сколько надо оттянуть список, чтобы обновление засчиталось. */
private const val THRESHOLD = 64f

/**
 * «Потяните, чтобы обновить» — порт `YTRefreshHeader`.
 *
 * Шапка живёт над содержимым списка и появляется, только когда список
 * оттянули: кольцо 24 и подпись 12 приглушённым под ним.
 *
 * Штатный `SwipeRefreshLayout` тут не годится: он из `androidx.swiperefresh`,
 * рисует свой кружок в материальном духе и своей темы не знает. Требование
 * к порту — вид в точности как в оригинале, а там это кольцо и подпись.
 */
class RefreshHeader(context: Context) : ViewGroup(context) {

    private val ring = LoadingRing(context)
    private val label = label(context, Fonts.regular, 12f, Theme.mutedText, 1)

    private var refreshing = false

    /** Что делать, когда попросили обновить. */
    var onRefresh: (() -> Unit)? = null

    init {
        // Шапка ничего не принимает: тянут сам список, а не её.
        isClickable = false
        isFocusable = false

        label.gravity = Gravity.CENTER
        label.text = loc("Потяните, чтобы обновить")

        addView(ring)
        addView(label)

        visibility = GONE
    }

    fun isRefreshing(): Boolean = refreshing

    /** Сколько оттянули, в пикселях; ноль и меньше — не оттянули. */
    fun followPull(pulled: Int) {
        if (refreshing) {
            return
        }

        visibility = if (pulled > 0) VISIBLE else GONE

        label.text = if (pulled >= dp(THRESHOLD)) {
            loc("Отпустите, чтобы обновить")
        } else {
            loc("Потяните, чтобы обновить")
        }
    }

    /** Палец отпустили. Возвращает true, если обновление началось. */
    fun release(pulled: Int): Boolean {
        if (refreshing || pulled < dp(THRESHOLD)) {
            visibility = GONE

            return false
        }

        refreshing = true

        visibility = VISIBLE

        /**
         * Оттянули список — значит, просят сходить в сеть. Кеш при этом
         * надо снять, иначе просьба ничего не значит.
         *
         * Ленты держатся в памяти две минуты — ради переходов между
         * разделами, чтобы мегабайт не выкачивался заново на каждый
         * возврат. Но обновление по жесту попадало в тот же кеш
         * и получало **тот же самый ответ**: колечко крутилось, список
         * не менялся и даже не мигал. Со стороны это неотличимо
         * от «жест не работает».
         *
         * Снимаем весь кеш, а не одну запись: какой именно запрос
         * повторится, шапка не знает и знать не должна, а жест редкий
         * и намеренный — лишняя пара запросов дешевле недоумения.
         */
        Http.dropMemoryCache()

        label.text = loc("Обновляем…")

        ring.color = Theme.loadingRing
        ring.start()

        onRefresh?.invoke()

        return true
    }

    /** Обновление кончилось — прячемся. */
    fun done() {
        refreshing = false

        ring.stop()

        visibility = GONE

        label.text = loc("Потяните, чтобы обновить")
    }

    fun applyTheme() {
        label.setTextColor(Theme.mutedText)

        ring.color = Theme.loadingRing
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(THRESHOLD))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        ring.frame(width / 2 - dp(12f), height / 2 - dp(20f), dp(24f), dp(24f))

        label.frame(0, height / 2 + dp(8f), width, dp(16f))
    }
}
