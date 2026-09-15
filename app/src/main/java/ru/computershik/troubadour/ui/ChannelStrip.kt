package ru.computershik.troubadour.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.net.SubscribedChannel
import ru.computershik.troubadour.ui.Metrics.dp

/** Числа полосы каналов из `YTSubscriptionsView`. */
private const val STRIP_HEIGHT = 96f
private const val STRIP_TOP = 10f
private const val STRIP_BOTTOM = 16f
private const val CHANNEL_WIDTH = 72f
private const val CHANNEL_GAP = 10f
private const val CHANNEL_AVATAR = 56f

/**
 * Полоса каналов над лентой подписок — порт полосы из `YTSubscriptionsView`.
 *
 * Первой плиткой идёт «Все», дальше сами каналы: кружок 56 по центру
 * колонки шириной 72, под ним имя в две строки. Выбранный канал
 * выделяется насыщенностью подписи — своей заливки у плитки в оригинале
 * нет, а отличать открытый канал от прочих надо.
 *
 * Полоса не показывается вовсе, если канал один: показывать «Все» в
 * одиночестве незачем.
 */
class ChannelStrip(context: Context) : android.widget.HorizontalScrollView(context) {

    private val row = StripRow(context)

    /** Какой канал выбран; пусто — «Все». */
    private var picked = ""

    var onPick: ((String) -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false

        addView(
            row,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        visibility = GONE
    }

    fun bind(channels: List<SubscribedChannel>) {
        scrollX = 0

        row.fill(channels)

        // Одна плитка — это только «Все»: показывать полосу незачем.
        visibility = if (channels.isEmpty()) GONE else VISIBLE

        row.onPick = { channelId ->
            if (channelId != picked) {
                picked = channelId

                row.markPicked(picked)

                onPick?.invoke(channelId)
            }
        }

        row.markPicked(picked)
    }

    fun applyTheme() {
        row.applyTheme()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(
                dp(STRIP_TOP + STRIP_HEIGHT + STRIP_BOTTOM), MeasureSpec.EXACTLY
            )
        )
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)

        loadVisible()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)

        loadVisible()
    }

    private fun loadVisible() {
        if (width <= 0) {
            return
        }

        row.loadVisible(scrollX, scrollX + width)
    }
}

/** Ряд плиток: шаг `72 + 10`, отступ слева 16, полоса с полем 10 сверху. */
class StripRow(context: Context) : ViewGroup(context) {

    private val tiles = ArrayList<ChannelTile>()

    var onPick: ((String) -> Unit)? = null

    fun fill(channels: List<SubscribedChannel>) {
        for (tile in tiles) {
            removeView(tile)
        }

        tiles.clear()

        if (channels.isEmpty()) {
            requestLayout()

            return
        }

        // Первой плиткой — «Все»: у неё нет ни канала, ни кружка.
        addTile(null)

        for (channel in channels) {
            addTile(channel)
        }

        requestLayout()
    }

    private fun addTile(channel: SubscribedChannel?) {
        val tile = ChannelTile(context, channel)

        tile.onTap = { onPick?.invoke(channel?.channelId ?: "") }

        tiles.add(tile)

        addView(tile)
    }

    /** Кружки берутся только у тех плиток, что попали в окно. */
    fun loadVisible(from: Int, to: Int) {
        val step = dp(CHANNEL_WIDTH) + dp(CHANNEL_GAP)

        if (step <= 0) {
            return
        }

        val first = maxOf(0, (from - dp(16f)) / step - 1)
        val last = minOf(tiles.size - 1, (to - dp(16f)) / step + 1)

        for (index in first..last) {
            tiles[index].loadAvatarIfNeeded()
        }
    }

    fun markPicked(channelId: String) {
        for (tile in tiles) {
            tile.picked = tile.channelId() == channelId
        }
    }

    fun applyTheme() {
        for (tile in tiles) {
            tile.applyTheme()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val step = dp(CHANNEL_WIDTH) + dp(CHANNEL_GAP)

        setMeasuredDimension(
            dp(16f) + step * tiles.size,
            dp(STRIP_TOP + STRIP_HEIGHT + STRIP_BOTTOM)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val step = dp(CHANNEL_WIDTH) + dp(CHANNEL_GAP)

        for (index in tiles.indices) {
            tiles[index].frame(
                dp(16f) + step * index, dp(STRIP_TOP),
                dp(CHANNEL_WIDTH), dp(STRIP_HEIGHT)
            )
        }
    }
}

/** Плитка канала: кружок 56 по центру и имя под ним в две строки. */
class ChannelTile(context: Context, private val channel: SubscribedChannel?) :
    TappableView(context) {

    private val avatar = RoundedImage(context)

    /**
     * Значок вместо кружка у плитки «Все».
     *
     * Вписан в кружок с полем: во всю ширину он выглядел бы не значком,
     * а картинкой канала.
     */
    private val mark = ImageView(context)

    private val title = label(context, Fonts.regular, 12f, Theme.secondaryText, 2)

    var picked: Boolean = false
        set(value) {
            field = value

            applyPicked()
        }

    init {
        highlights = false

        avatar.circular = true
        avatar.placeholderColor = Theme.avatarPlaceholder

        addView(avatar)

        mark.scaleType = ImageView.ScaleType.FIT_CENTER
        mark.setImageBitmap(Icons.icon("tab_subs"))
        mark.visibility = if (channel == null) VISIBLE else GONE

        addView(mark)

        title.gravity = Gravity.CENTER_HORIZONTAL

        title.text = channel?.title ?: loc("Все")

        addView(title)

        applyPicked()
    }

    /** Загружена ли уже аватарка — второй раз просить незачем. */
    private var loaded = false

    /**
     * Берёт кружок канала — только когда плитка на виду.
     *
     * Прежде кружок заказывался прямо в разборе плитки, то есть сразу
     * для всех. У человека с девятью с половиной сотнями подписок это
     * девятьсот пятьдесят пять картинок разом: поток загрузки съедал
     * всю память, и приложение падало насмерть — `Fatal signal 11
     * at 0xdeadbaad` в потоке `troubadour-imag`, без единой строки
     * в нашем журнале. В iOS-версии так и сделано: `loadAvatarIfNeeded`
     * зовёт полоса при прокрутке.
     */
    fun loadAvatarIfNeeded() {
        if (loaded || channel == null || channel.thumbnail.isNullOrEmpty()) {
            return
        }

        loaded = true

        ImageLoader.loadInto(avatar, channel.thumbnail, CHANNEL_AVATAR)
    }

    fun channelId(): String = channel?.channelId ?: ""

    /**
     * Выбранный канал выделяется насыщенностью подписи: своей заливки
     * у плитки в оригинале нет, а отличать открытый надо.
     */
    private fun applyPicked() {
        title.typeface = if (picked) Fonts.semiBold else Fonts.regular

        title.setTextColor(if (picked) Theme.primaryText else Theme.secondaryText)
    }

    fun applyTheme() {
        avatar.placeholderColor = Theme.avatarPlaceholder

        mark.setImageBitmap(Icons.icon("tab_subs"))

        applyPicked()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l

        val side = dp(CHANNEL_AVATAR)

        avatar.frame((width - side) / 2, 0, side, side)

        val markSide = Math.round(side * 0.5f)

        mark.frame((width - markSide) / 2, (side - markSide) / 2, markSide, markSide)

        title.frame(0, side + dp(8f), width, dp(28f))
    }
}
