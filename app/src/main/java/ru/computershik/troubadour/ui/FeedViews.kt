package ru.computershik.troubadour.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.model.VideoItem
import ru.computershik.troubadour.ui.Metrics.dp

/**
 * Карточка ролика — порт `ItemTemplate` из Home.xaml.
 *
 * Раскладка оттуда же, число в число:
 *
 *     превью        во всю ширину карточки, 16:9, скругление 8,
 *                   подложка AppSurfaceAlt
 *     плашка        снизу справа, отступ 8, поля 6×2, скругление 4,
 *                   фон #CC000000, подпись 12 Medium белая
 *     отступ        12 до строки с автором
 *     кружок        36×36 слева, круглый
 *     отступ        10 до текста
 *     название      14 Medium, AppPrimaryText, одна строка с многоточием
 *     отступ        4
 *     метаданные    12 Regular, AppMutedText, одна строка с многоточием
 *
 * Кружок канала пропадает вместе со своей колонкой, если картинки нет:
 * в оригинале он тоже показывается не всегда, и название в таком случае
 * начинается от края.
 */
class VideoCard(context: Context) : TappableView(context) {

    private val thumb = RoundedImage(context)
    private val avatar = RoundedImage(context)
    private val badge = BadgeLabel(context)

    private val title = label(context, Fonts.medium, 14f, Theme.primaryText, 1)
    private val meta = label(context, Fonts.regular, 12f, Theme.mutedText, 1)

    /**
     * Полоска просмотра по нижнему краю превью — дорожка и заполненное.
     *
     * Простые крашеные виды: рисовать её в `onDraw` карточки значило бы
     * перерисовывать карточку целиком ради четырёх точек по низу кадра.
     */
    private val watchedTrack = View(context)
    private val watchedFill = View(context)

    private var watchedShare = 0.0

    private var item: VideoItem? = null

    /**
     * Ширина, под которую уже заказано превью.
     *
     * Ноль — не заказано вовсе. Нужна, чтобы не просить одно и то же
     * при каждой раскладке и чтобы переспросить после поворота, когда
     * карточка стала другой ширины.
     */
    private var thumbFor = 0

    /**
     * Скругление превью. В ленте это 8 (`CornerRadius="8"` в Home.xaml),
     * а в списке похожих на странице видео — 0: там у шаблона
     * `CornerRadius="0"`, углы прямые.
     */
    var thumbRadius: Float = Metrics.THUMB_RADIUS

    init {
        addView(thumb)

        // Полоска ложится поверх кадра, но под плашку длительности.
        addView(watchedTrack)
        addView(watchedFill)

        addView(badge)
        addView(avatar)
        addView(title)
        addView(meta)

        avatar.circular = true

        highlights = true

        onTap = {
            val card = item

            when {
                card == null -> Unit
                card.isPlaylist -> Nav.openPlaylist(card.playlistId, card.title)
                card.isShort -> Nav.openShort(card)
                else -> Nav.openVideo(
                    card.videoId, card.title, card.playlistId,
                    maxOf(0.0, card.resumeAt)
                )
            }
        }

        /**
         * Долгое нажатие открывает канал.
         *
         * Добавка сверх оригинала — там долгого нажатия у карточки нет
         * вовсе. Но кружок канала на карточке показывается не всегда
         * (настройка «показывать кружок» его убирает), а перейти к автору
         * прямо из ленты хочется; на телефоне долгое нажатие — привычный
         * для этого способ.
         */
        onHold = {
            val card = item

            if (card != null && !card.channelId.isNullOrEmpty()) {
                Nav.openChannel(card.channelId, card.channelTitle)
            }
        }
    }

    /**
     * Всё, что зависит от темы, назначается здесь, а не в конструкторе.
     *
     * Ячейки живут в пуле переработки и смену темы переживают:
     * перерисовка их не пересоздаёт, а перепривязывает, и взятый однажды
     * цвет так и остался бы прежним. Правило перенесено из оригинала
     * дословно и держится по всему проекту.
     */
    fun bind(card: VideoItem?) {
        item = card

        if (card == null) {
            return
        }

        title.setTextColor(Theme.primaryText)
        meta.setTextColor(Theme.mutedText)

        thumb.cornerRadius = Metrics.dpf(thumbRadius)
        thumb.placeholderColor = Theme.surfaceAlt
        avatar.placeholderColor = Theme.avatarPlaceholder

        title.text = card.title
        meta.text = card.metadataLine()

        badge.text = card.duration ?: ""
        badge.visibility = if (badge.text.isEmpty()) GONE else VISIBLE

        /**
         * У эфира и у подборки полоски нет.
         *
         * У эфира её нечему мерить — конца у него не назначено; у
         * подборки доля относилась бы к одному ролику из многих.
         */
        watchedShare = if (card.isLive || !card.playlistId.isNullOrEmpty()) {
            0.0
        } else {
            maxOf(0.0, card.watchedShare)
        }

        val showsBar = watchedShare > 0

        watchedTrack.visibility = if (showsBar) VISIBLE else GONE
        watchedFill.visibility = if (showsBar) VISIBLE else GONE

        watchedTrack.setBackgroundColor(0x47FFFFFF)
        watchedFill.setBackgroundColor(Theme.BRAND_RED)

        val showsAvatar = Settings.showsChannelIcons && !card.channelThumbnail.isNullOrEmpty()

        avatar.visibility = if (showsAvatar) VISIBLE else GONE

        /**
         * Превью просим не здесь, а когда станет известна ширина.
         *
         * `bind` у карточек похожих зовётся до раскладки: строка ещё
         * не в дереве, `width` равен нулю, и подбор ступени по нулевой
         * ширине выдавал самую мелкую — `default.jpg` 120×90. Оттого
         * на странице ролика превью были мутными, а в ленте, где
         * карточки переиспользуются уже с шириной, — нормальными.
         */
        thumbFor = 0

        loadThumbIfNeeded()

        if (showsAvatar) {
            ImageLoader.loadInto(avatar, card.channelThumbnail, Metrics.CARD_AVATAR)
        } else {
            avatar.setImage(null)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)

        setMeasuredDimension(width, heightForWidth(width, item))
    }

    /** Заказ превью под нынешнюю ширину карточки. */
    private fun loadThumbIfNeeded() {
        val card = item ?: return

        if (width <= 0 || thumbFor == width) {
            return
        }

        thumbFor = width

        ImageLoader.loadInto(thumb, card.thumbnail, Metrics.points(width))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l

        loadThumbIfNeeded()

        val thumbHeight = thumbHeight(width, item)

        thumb.frame(0, 0, width, thumbHeight)

        // Плашка — снизу справа превью, отступ 8.
        val badgeWidth = badge.badgeWidth()
        val badgeHeight = badge.badgeHeight()

        badge.frame(
            width - dp(8f) - badgeWidth, thumbHeight - dp(8f) - badgeHeight,
            badgeWidth, badgeHeight
        )

        /**
         * Полоска просмотра — по нижнему краю кадра, в четыре точки,
         * как в оригинале. Скруглению превью она не мешает: радиус
         * небольшой, и полоска в него вписывается.
         */
        if (watchedTrack.visibility == VISIBLE) {
            val bar = dp(4f)
            val line = thumbHeight - bar

            watchedTrack.frame(0, line, width, bar)
            watchedFill.frame(0, line, (width * watchedShare).toInt(), bar)
        }

        // Отступ 12 до строки с автором.
        val rowTop = thumbHeight + dp(12f)

        var textLeft = 0

        if (avatar.visibility == VISIBLE) {
            val side = dp(Metrics.CARD_AVATAR)

            avatar.frame(0, rowTop, side, side)

            // Отступ 10 до текста.
            textLeft = side + dp(10f)
        }

        val titleHeight = Metrics.lineHeight(Fonts.medium, 14f)
        val metaHeight = Metrics.lineHeight(Fonts.regular, 12f)

        title.frame(textLeft, rowTop, width - textLeft, titleHeight)

        // Отступ 4.
        meta.frame(
            textLeft, rowTop + titleHeight + dp(4f), width - textLeft, metaHeight
        )
    }

    companion object {

        /**
         * Высота превью.
         *
         * У вертикального ролика пропорция другая — 9:16, — и вписывать
         * его в место под 16:9 нельзя: от кадра осталась бы узкая полоса
         * посередине. Оригинал завёл для этого признак `isShort`
         * на карточке; здесь он же.
         *
         * Совсем во всю высоту вертикальное превью тоже не даём: карточка
         * вышла бы вдвое выше экрана. Ограничиваем полутора ширинами —
         * так же, как это выглядит в ленте самого YouTube.
         */
        @JvmStatic
        fun thumbHeight(width: Int, item: VideoItem?): Int {
            if (item?.isShort == true) {
                return minOf(width * 16 / 9, (width * 1.5f).toInt())
            }

            return width * 9 / 16
        }

        /** Высота карточки при такой ширине — нужна списку до создания вида. */
        @JvmStatic
        fun heightForWidth(width: Int, item: VideoItem?): Int {
            return thumbHeight(width, item) + dp(12f) +
                Metrics.lineHeight(Fonts.medium, 14f) + dp(4f) +
                Metrics.lineHeight(Fonts.regular, 12f)
        }
    }
}

/**
 * Ряд карточек.
 *
 * Список — обычный `ListView`, а не сетка: колонки собираются вручную,
 * один ряд держит столько карточек, сколько их помещается по ширине.
 * `ItemsWrapGrid` в оригинале делал ровно это, и в iOS-версии по той же
 * причине стояла `UITableView`, а не `UICollectionView`.
 *
 * `RecyclerView` здесь не годится дважды: он живёт в AndroidX новее того,
 * что работает на API 16, и его `GridLayoutManager` всё равно не умеет
 * то, что нужно, — колонки разной высоты у вертикальных и обычных роликов.
 */
class FeedRow(context: Context) : ViewGroup(context) {

    private val cards = ArrayList<VideoCard>()

    private var columns = 1
    private var spacing = dp(Metrics.CARD_SPACING)

    /** Ставит ряд карточек; лишние прячутся, а не пересоздаются. */
    fun bind(items: List<VideoItem>, columns: Int, thumbRadius: Float = Metrics.THUMB_RADIUS) {
        this.columns = maxOf(1, columns)

        while (cards.size < items.size) {
            val card = VideoCard(context)

            cards.add(card)
            addView(card)
        }

        for (index in cards.indices) {
            val card = cards[index]

            if (index < items.size) {
                card.visibility = VISIBLE
                card.thumbRadius = thumbRadius

                card.bind(items[index])
            } else {
                card.visibility = GONE
                card.bind(null)
            }
        }

        requestLayout()
    }

    /** Ширина одной колонки при такой ширине ряда. */
    private fun columnWidth(width: Int): Int =
        (width - spacing * (columns - 1)) / columns

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val column = columnWidth(width)

        var height = 0

        for (card in cards) {
            if (card.visibility == GONE) {
                continue
            }

            card.measure(
                MeasureSpec.makeMeasureSpec(column, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )

            height = maxOf(height, card.measuredHeight)
        }

        setMeasuredDimension(width, height + spacing)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val column = columnWidth(r - l)

        var at = 0
        var index = 0

        for (card in cards) {
            if (card.visibility == GONE) {
                continue
            }

            card.frame(at, 0, column, card.measuredHeight)

            at += column + spacing
            index++

            if (index >= columns) {
                break
            }
        }
    }
}

/**
 * «Таблетка» категории над лентой — порт `CategoryChipButtonStyle`:
 * высота 36, скругление 9, поля по 14, подпись 14 SemiBold.
 *
 * Выбранная заливается `PrimaryActionBackground` с подписью
 * `PrimaryActionForeground` — в тёмной теме это белая таблетка с чёрным
 * текстом, ровно как на снимке экрана оригинала.
 */
class ChipView(context: Context) : PillButton(context) {

    /** Подпись таблетки. Имя не `title`: так зовётся сама подпись у предка. */
    var caption: String = ""
        set(value) {
            field = value

            title.text = value

            requestLayout()
        }

    var chosen: Boolean = false
        set(value) {
            field = value

            applyState()
        }

    /** Заливка рисуется самой таблеткой — см. пояснение у `onDraw`. */
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val box = android.graphics.RectF()

    init {
        /**
         * Подложку от предка убираем и рисуем сами.
         *
         * У предка она отдельным видом под подписью, и на устройстве
         * выходило так: замер таблетки верный — 156×108 при полях 42, —
         * а закрашивался только прямоугольник размером с текст, без полей
         * и без скругления. Между верной мерой и рисованием оставался
         * посредник, и вина была его. Посредника больше нет: заливка —
         * это два поля и один `drawRoundRect` по собственным границам,
         * ошибиться тут негде.
         */
        pill.visibility = GONE

        setWillNotDraw(false)

        setPadding(dp(Metrics.CHIP_PADDING), 0, dp(Metrics.CHIP_PADDING), 0)

        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
        title.gravity = Gravity.CENTER

        applyState()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (width <= 0 || height <= 0) {
            return
        }

        val radius = Metrics.dpf(Metrics.CHIP_RADIUS)

        box.set(0f, 0f, width.toFloat(), height.toFloat())

        paint.style = android.graphics.Paint.Style.FILL
        paint.color = if (chosen) Theme.primaryActionBackground else Theme.surface

        canvas.drawRoundRect(box, radius, radius, paint)
    }

    /**
     * Перекрасить под текущую тему.
     *
     * Отдельным методом, а не внутри установки подписи, потому что полоса
     * категорий переживает смену темы целиком: таблетки не пересоздаются,
     * и цвет, взятый однажды, так и остался бы прежним.
     */
    fun applyState() {
        title.setTextColor(
            if (chosen) Theme.primaryActionForeground else Theme.primaryText
        )

        invalidate()
    }

    /** Ширина под текущую подпись, но не уже сорока восьми точек. */
    fun widthForTitle(): Int = maxOf(
        // `MinWidth="48"` из `CategoryChipButtonStyle`.
        dp(48f),
        Metrics.textWidth(caption, Fonts.semiBold, 14f) + dp(Metrics.CHIP_PADDING) * 2
    )

    /**
     * Высота таблетки задана числом — 36 из Home.xaml, — а ширина считается
     * по подписи. Внешнюю меру не слушаем вовсе: полоса выше самой
     * таблетки, и растянуться на всю её высоту таблетка не должна.
     *
     * Размер ставится здесь, а не передаётся предку заданными мерами:
     * так между «сколько мы просили» и «сколько вышло» не остаётся
     * никого, кто мог бы вмешаться.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = widthForTitle()
        val height = dp(Metrics.CHIP_HEIGHT)

        setMeasuredDimension(width, height)

        title.measure(
            MeasureSpec.makeMeasureSpec(
                maxOf(0, width - paddingLeft - paddingRight), MeasureSpec.EXACTLY
            ),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        title.frame(
            paddingLeft, 0, maxOf(0, width - paddingLeft - paddingRight), height
        )

        /**
         * Сообщаем **уложенное**, а не отмеренное.
         *
         * Прошлый замер печатался при обмере и говорил, что всё верно,
         * — а на экране выходило иное. Значит, спрашивать надо после
         * укладки: сколько места таблетка получила на самом деле.
         */
        if (!told) {
            told = true

            Log.d {
                "[YouTube/Лента] Таблетка «$caption»: уложена ${width}×$height, " +
                    "отмерено ${measuredWidth}×$measuredHeight, " +
                    "хотела ${widthForTitle()}×${dp(Metrics.CHIP_HEIGHT)}, " +
                    "поля ${paddingLeft}/${paddingRight}, " +
                    "подпись ${title.width}×${title.height}"
            }
        }
    }

    companion object {
        /** Замер сообщается один раз за запуск — журналу хватит одной строки. */
        private var told = false
    }
}

/**
 * Заголовок полки в ленте — «Live Now», «Recent Live Streams».
 *
 * Размер тот же, что у заголовков на «Моём» (18 SemiBold): полка
 * и там, и здесь означает одно и то же — часть ленты со своим именем,
 * и разнобой в кеглях читался бы как разница по смыслу.
 */
class ShelfHeadView(context: Context) : android.widget.FrameLayout(context) {

    private val text = label(context, Fonts.semiBold, 18f, Theme.primaryText, 1)

    var caption: String = ""
        set(value) {
            field = value

            text.text = value
        }

    init {
        addView(
            text,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setPadding(dp(8f), dp(12f), dp(8f), dp(6f))
    }

    fun repaint() {
        text.setTextColor(Theme.primaryText)
    }
}

/**
 * «Показать ещё» под полкой.
 *
 * Полка листается своим токеном, отдельно от ленты, и кнопка — весь
 * доступный для этого ход: телевизор возит полку вбок пальцем, а у нас
 * плитки лежат рядами и возить нечего.
 */
class ShelfMoreView(context: Context) : TappableView(context) {

    private val text = label(context, Fonts.medium, 14f, Theme.ACCENT_BLUE, 1)

    var caption: String = ""
        set(value) {
            field = value

            text.text = value
        }

    init {
        highlights = true

        text.gravity = Gravity.CENTER

        addView(
            text,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            dp(44f)
        )

        measureChildren(widthMeasureSpec, heightMeasureSpec)
    }

    fun repaint() {
        text.setTextColor(Theme.ACCENT_BLUE)
    }
}
