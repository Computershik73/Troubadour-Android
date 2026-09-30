package ru.computershik.troubadour.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import ru.computershik.troubadour.net.Api
import ru.computershik.troubadour.net.playbackNonceFor
import ru.computershik.troubadour.player.PlaybackStats
import ru.computershik.troubadour.player.PlayerEngine
import ru.computershik.troubadour.ui.Metrics.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Окно «статистика для сисадминов» — то же, что показывает сайт по
 * правой кнопке на ролике: что играет, чем закодировано, как идёт
 * сеть и сколько набрано в буфер.
 *
 * Подписи нарочно не переводятся: на сайте они тоже английские при
 * любом языке, и по ним удобно сверяться с ним же. Обновляется раз
 * в секунду, пока видно.
 */
class StatsPanel(context: Context) : ViewGroup(context) {

    var onClose: (() -> Unit)? = null

    private class Row(val label: TextView, val value: TextView, val graph: Sparkline?)

    private val rows = ArrayList<Row>()

    private val close = TextView(context)

    private val speedGraph = Sparkline(context, 0xFF4DD0C4.toInt())
    private val activityGraph = Sparkline(context, Color.WHITE)
    private val bufferGraph = Sparkline(context, 0xFFF2B65B.toInt())

    private var lastBytes = -1L

    private val clock = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US)

    init {
        setBackgroundColor(0xD9000000.toInt())

        isClickable = true

        for (name in listOf(
            "Video ID / sCPN", "Viewport / Frames", "Current / Optimal Res",
            "Volume / Normalized", "Codecs", "Color"
        )) {
            addRow(name, null)
        }

        addRow("Connection Speed", speedGraph)
        addRow("Network Activity", activityGraph)
        addRow("Buffer Health", bufferGraph)

        addRow("Mystery Text", null)
        addRow("Date", null)

        close.text = "[X]"
        close.typeface = Fonts.semiBold
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        close.setTextColor(Color.WHITE)
        close.gravity = Gravity.CENTER
        close.setOnClickListener { onClose?.invoke() }

        addView(close)
    }

    private fun addRow(name: String, graph: Sparkline?) {
        val label = TextView(context)

        label.text = name
        label.typeface = Fonts.semiBold
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        label.setTextColor(Color.WHITE)
        label.gravity = Gravity.END
        label.setSingleLine()

        val value = TextView(context)

        value.typeface = Fonts.regular
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        value.setTextColor(Color.WHITE)
        value.setSingleLine()
        value.ellipsize = TextUtils.TruncateAt.END
        value.gravity = if (graph != null) Gravity.END else Gravity.START

        addView(label)
        addView(value)

        if (graph != null) {
            addView(graph)
        }

        rows.add(Row(label, value, graph))
    }

    // --- Обновление -------------------------------------------------------

    private val ticker = object : Runnable {
        override fun run() {
            if (visibility != VISIBLE || !isAttachedToWindowCompat()) {
                return
            }

            update()

            postDelayed(this, 1000)
        }
    }

    private fun isAttachedToWindowCompat(): Boolean = windowToken != null

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)

        removeCallbacks(ticker)

        if (visibility == VISIBLE) {
            lastBytes = -1

            for (graph in listOf(speedGraph, activityGraph, bufferGraph)) {
                graph.clear()
            }

            post(ticker)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (visibility == VISIBLE) {
            post(ticker)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)

        super.onDetachedFromWindow()
    }

    private fun set(index: Int, text: String) {
        rows[index].value.text = text
    }

    private fun update() {
        val player = PlayerEngine.player
        val sabr = PlayerEngine.sabr
        val json = PlayerEngine.playerJson

        set(0, "${PlayerEngine.videoId ?: "—"} / ${Api.playbackNonceFor(PlayerEngine.videoId)}")

        // Окно: размер кадра в точках и плотность — как на сайте.
        val stage = parent as? PlayerStage
        val density = resources.displayMetrics.density
        val surfaceWidth = stage?.surface?.width ?: 0
        val surfaceHeight = stage?.surface?.height ?: 0

        val counters = player?.videoDecoderCounters

        counters?.ensureUpdated()

        val dropped = counters?.droppedBufferCount ?: 0
        val rendered = counters?.renderedOutputBufferCount ?: 0

        set(
            1,
            "${(surfaceWidth / density).toInt()}x${(surfaceHeight / density).toInt()}" +
                "*${String.format(Locale.US, "%.2f", density)} / $dropped dropped of $rendered"
        )

        val videoFormat = player?.videoFormat
        val audioFormat = player?.audioFormat

        val current = if (videoFormat != null && videoFormat.width > 0) {
            "${videoFormat.width}x${videoFormat.height}" +
                if (videoFormat.frameRate > 0) "@${videoFormat.frameRate.toInt()}" else ""
        } else {
            "—"
        }

        /**
         * «Оптимальное» — лучшее, что имеет смысл на **этом окне**,
         * а не вообще у ролика.
         *
         * Так это и понимает панель на сайте: она отвечает на вопрос,
         * выше какого разрешения показывать бессмысленно. Прежде мы
         * писали сюда наибольшее из доступных, и выходило «1920x1080»
         * на окне, где и 720 не поместится.
         */
        val fits = surfaceHeight

        val heights = PlayerEngine.heights

        val best = heights.filter { fits <= 0 || it <= fits }.maxOrNull()
            ?: heights.minOrNull() ?: 0

        val optimal = if (best > 0) "${best * 16 / 9}x$best" else "—"

        set(2, "$current / $optimal")

        val volume = ((player?.volume ?: 1f) * 100).toInt()
        val loudness = loudnessDb(json)

        set(
            3,
            "$volume% / $volume%" +
                if (loudness != null) {
                    " (content loudness ${String.format(Locale.US, "%.1f", loudness)}dB)"
                } else {
                    ""
                }
        )

        /**
         * Спрашиваем ту дорожку, что и вправду идёт.
         *
         * `playingItag` — это объявление сервера при заводке дорожки,
         * а он вправе объявить одно, а потом спуститься ниже, объявления
         * не повторив. Строка тогда выходит склеенной из двух разных:
         * размер настоящий, а кодек и кадровая частота — от дорожки,
         * которой давно нет.
         */
        val videoItag = sabr?.deliveredVideoItag?.takeIf { it > 0 }
            ?: sabr?.playingItag ?: 0
        val audioItag = sabr?.audioItag ?: 0

        set(
            4,
            codecName(videoFormat?.codecs, videoItag, json, true) + " / " +
                codecName(audioFormat?.codecs, audioItag, json, false)
        )

        set(5, colorName(json, videoItag, videoFormat?.height ?: 0))

        // Сеть: скорость сглаженная, деятельность — за прошедшую секунду.
        val speed = PlaybackStats.speedKbps()
        val total = PlaybackStats.totalBytes()

        val delta = if (lastBytes < 0) 0L else total - lastBytes

        lastBytes = total

        speedGraph.push(speed.toFloat())
        activityGraph.push(delta / 1024f)

        set(6, "$speed Kbps")
        set(7, "${delta / 1024} KB")

        val health = if (player != null) {
            maxOf(0.0, (player.bufferedPosition - player.currentPosition) / 1000.0)
        } else {
            0.0
        }

        bufferGraph.push(health.toFloat())

        set(8, String.format(Locale.US, "%.2f s", health))

        val state = when (player?.playbackState) {
            com.google.android.exoplayer2.Player.STATE_IDLE -> 1
            com.google.android.exoplayer2.Player.STATE_BUFFERING -> 2
            com.google.android.exoplayer2.Player.STATE_READY -> 3
            com.google.android.exoplayer2.Player.STATE_ENDED -> 4
            else -> 0
        }

        val mystery = StringBuilder()

        mystery.append(if (sabr != null) "SABR" else "DIRECT")

        if (sabr != null) {
            mystery.append(", rn:").append(sabr.requests)
            mystery.append(", itag:").append(videoItag).append('/').append(audioItag)
        }

        mystery.append(", s:").append(state)

        if (player != null) {
            mystery.append(
                String.format(
                    Locale.US, ", b:%.3f-%.3f",
                    player.currentPosition / 1000.0, player.bufferedPosition / 1000.0
                )
            )
        }

        mystery.append(", rate:").append(PlayerEngine.rate())
        mystery.append(", exo:").append(com.google.android.exoplayer2.ExoPlayerLibraryInfo.VERSION)

        set(9, mystery.toString())

        val now = Date()
        val zone = TimeZone.getDefault()
        val offset = zone.getOffset(now.time) / 60000
        val sign = if (offset < 0) "-" else "+"
        val hours = Math.abs(offset) / 60
        val minutes = Math.abs(offset) % 60

        set(
            10,
            clock.format(now) + String.format(Locale.US, " GMT%s%02d%02d (", sign, hours, minutes) +
                zone.getDisplayName(zone.inDaylightTime(now), TimeZone.LONG) + ")"
        )
    }

    /** Кодек по описанию дорожки из ответа `/player`, либо как назвал плеер. */
    private fun codecName(fromPlayer: String?, itag: Int, json: JSONObject?, video: Boolean): String {
        var name = fromPlayer

        if (itag > 0) {
            val format = formatIn(json, itag, 0, video)

            val mime = format?.optString("mimeType") ?: ""
            val quoted = mime.indexOf("codecs=\"")

            if (quoted >= 0) {
                val end = mime.indexOf('"', quoted + 8)

                if (end > quoted) {
                    name = mime.substring(quoted + 8, end)
                }
            }
        }

        if (name.isNullOrEmpty()) {
            return "—"
        }

        return if (itag > 0) "$name ($itag)" else name
    }

    /**
     * Цвет — из описания дорожки в ответе `/player`: там названы
     * первичные цвета и передаточная характеристика. Сайт показывает
     * ровно их.
     */
    private fun colorName(json: JSONObject?, itag: Int, height: Int): String {
        val format = formatIn(json, itag, height, true) ?: return "—"
        val info = format.optJSONObject("colorInfo")

        if (info == null) {
            // Обычные дорожки без описания — это bt709, как и на сайте.
            return "bt709 / bt709"
        }

        fun short(value: String?): String {
            if (value.isNullOrEmpty()) {
                return "—"
            }

            val cut = value.substringAfterLast('_')

            return cut.lowercase(Locale.US)
        }

        return short(info.optString("primaries")) + " / " +
            short(info.optString("transferCharacteristics"))
    }

    /** Описание дорожки в ответе `/player` — по номеру либо по высоте. */
    private fun formatIn(json: JSONObject?, itag: Int, height: Int, video: Boolean): JSONObject? {
        val streaming = json?.optJSONObject("streamingData") ?: return null

        for (key in listOf("adaptiveFormats", "formats")) {
            val list = streaming.optJSONArray(key) ?: continue

            for (index in 0 until list.length()) {
                val format = list.optJSONObject(index) ?: continue

                if (itag > 0) {
                    if (format.optInt("itag") == itag) {
                        return format
                    }
                } else if (height > 0 && video) {
                    val mime = format.optString("mimeType")

                    if (format.optInt("height") == height && mime.contains("avc1")) {
                        return format
                    }
                }
            }
        }

        return null
    }

    private fun loudnessDb(json: JSONObject?): Double? {
        val config = json?.optJSONObject("playerConfig")?.optJSONObject("audioConfig")
            ?: return null

        if (!config.has("loudnessDb")) {
            return null
        }

        return config.optDouble("loudnessDb")
    }

    // --- Размеры ----------------------------------------------------------

    private val pad get() = dp(8f)
    private val rowHeight get() = dp(16f)
    private val labelWidth get() = dp(128f)
    private val gap get() = dp(8f)
    private val graphValueWidth get() = dp(80f)
    /** Крестик рисуется мелким, а нажимается крупным: палец не стилус. */
    private val closeWidth get() = dp(44f)
    private val closeHeight get() = dp(36f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val width = minOf(available, dp(480f))

        val valueWidth = width - pad * 2 - labelWidth - gap

        for (row in rows) {
            row.label.measure(
                MeasureSpec.makeMeasureSpec(labelWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
            )

            val forValue = if (row.graph != null) graphValueWidth else valueWidth

            row.value.measure(
                MeasureSpec.makeMeasureSpec(forValue, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
            )

            row.graph?.measure(
                MeasureSpec.makeMeasureSpec(valueWidth - graphValueWidth - gap, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeight - dp(2f), MeasureSpec.EXACTLY)
            )
        }

        close.measure(
            MeasureSpec.makeMeasureSpec(closeWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(closeHeight, MeasureSpec.EXACTLY)
        )

        setMeasuredDimension(width, pad * 2 + rowHeight * rows.size)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l

        var top = pad

        for (row in rows) {
            row.label.frame(pad, top, labelWidth, rowHeight)

            val valueLeft = pad + labelWidth + gap

            if (row.graph != null) {
                val graphWidth = row.graph.measuredWidth

                row.graph.frame(valueLeft, top + dp(1f), graphWidth, rowHeight - dp(2f))
                row.value.frame(valueLeft + graphWidth + gap, top, graphValueWidth, rowHeight)
            } else {
                row.value.frame(valueLeft, top, width - valueLeft - pad, rowHeight)
            }

            top += rowHeight
        }

        close.frame(width - closeWidth, 0, closeWidth, closeHeight)
    }
}

/**
 * Полоска истории — столбики за последнюю минуту, справа свежее.
 * Высота столбика — доля от наибольшего значения в окне.
 */
class Sparkline(context: Context, color: Int) : View(context) {

    private val values = FloatArray(60)
    private var count = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paint.color = color
        setBackgroundColor(0xFF000000.toInt())
    }

    fun push(value: Float) {
        if (count < values.size) {
            values[count++] = value
        } else {
            System.arraycopy(values, 1, values, 0, values.size - 1)
            values[values.size - 1] = value
        }

        invalidate()
    }

    fun clear() {
        count = 0

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (count == 0) {
            return
        }

        var top = 0f

        for (index in 0 until count) {
            if (values[index] > top) {
                top = values[index]
            }
        }

        if (top <= 0f) {
            return
        }

        val width = width.toFloat()
        val height = height.toFloat()
        val step = width / values.size

        for (index in 0 until count) {
            val x = width - (count - index) * step
            val h = height * values[index] / top

            canvas.drawRect(x, height - h, x + step - 1f, height, paint)
        }
    }
}
