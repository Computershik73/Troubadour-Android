package ru.computershik.troubadour.ui

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.model.VideoItem
import ru.computershik.troubadour.net.Api
import ru.computershik.troubadour.net.Auth
import ru.computershik.troubadour.net.ShortsPlayback
import ru.computershik.troubadour.net.rate
import ru.computershik.troubadour.net.setSubscribed
import ru.computershik.troubadour.net.shorts
import ru.computershik.troubadour.net.shortsPlayback
import ru.computershik.troubadour.player.PlayerEngine
import ru.computershik.troubadour.ui.Metrics.dp

/**
 * Листалка вертикальных роликов — порт `Shorts.xaml`.
 *
 * Кадр во весь экран, поверх него столбец кнопок справа (оценка,
 * комментарии, «поделиться») и подписи внизу. Свайп вверх — следующий,
 * вниз — предыдущий.
 *
 * Значки здесь **всегда из тёмного набора**, как и у пульта: в разметке
 * оригинала путь у столбца кнопок зашит `Assets/Dark/…`, потому что они
 * лежат поверх кадра.
 */
class ShortsView(context: Context) : FrameLayout(context) {

    private val surface = TextureView(context)

    /**
     * Соотношение сторон играющего ролика — ширина к высоте.
     *
     * До первого кадра неизвестно, и берётся привычное вертикальное
     * 9:16: почти все Shorts такие, а ошибка на первых кадрах не страшна,
     * размер уточнится, как только плеер назовёт свой.
     */
    private var frameAspect = 9f / 16f
    private val cover = RoundedImage(context)

    private val titleLabel = label(context, Fonts.semiBold, 15f, android.graphics.Color.WHITE, 2)
    private val authorLabel = label(context, Fonts.regular, 13f, 0xFFDDDDDD.toInt(), 1)

    /** Кружок автора в строке над названием. */
    private val shortAvatar = RoundedImage(context)

    private val likeIcon = ImageView(context)
    private val likeCount = label(context, Fonts.semiBold, 12f, android.graphics.Color.WHITE, 1)
    private val commentCount = label(context, Fonts.semiBold, 12f, android.graphics.Color.WHITE, 1)

    private val ring = LoadingRing(context)
    private val notice = label(context, Fonts.regular, 14f, android.graphics.Color.WHITE, 0)

    private val items = ArrayList<VideoItem>()

    private var at = 0
    private var sequence: String? = null
    private var busy = false

    /**
     * Ставит поверхность по соотношению сторон ролика, посередине.
     *
     * Зовётся после обычной раскладки: `TextureView` тянет содержимое
     * ровно по своим границам, поэтому вписывать кадр приходится
     * размером самой поверхности.
     */
    private fun placeSurface(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }

        val format = PlayerEngine.player?.videoFormat

        if (format != null && format.width > 0 && format.height > 0) {
            frameAspect = format.width.toFloat() / format.height.toFloat()
        }

        var frameWidth = width
        var frameHeight = Math.round(width / frameAspect)

        if (frameHeight > height) {
            frameHeight = height
            frameWidth = Math.round(height * frameAspect)
        }

        val left = (width - frameWidth) / 2
        val top = (height - frameHeight) / 2

        surface.layout(left, top, left + frameWidth, top + frameHeight)
        cover.layout(left, top, left + frameWidth, top + frameHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        placeSurface(right - left, bottom - top)
    }

    private var playback: ShortsPlayback? = null
    private var liked = false

    /** С какого ролика начинать — если листалку открыли из выдачи. */
    var seed: VideoItem? = null

    init {
        setBackgroundColor(0xFF000000.toInt())

        /**
         * Кадр вписывается целиком, а не растягивается по виду.
         *
         * `TextureView` тянет содержимое ровно по своим границам, и на
         * широком экране вертикальный ролик расплывался во всю ширину:
         * лица растянуты, края обрезаны. В iOS-версии слой положен
         * с `AVLayerVideoGravityResizeAspect`, то есть кадр вписан
         * целиком, с полями по бокам. Здесь то же самое делается
         * раскладкой: см. [placeSurface].
         */
        addView(
            surface,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        /**
         * Превью под кадром — пока поток не пошёл.
         *
         * У подачи медленный старт: перед первым кадром надо получить
         * заголовок дорожки и первые фрагменты, а это сотни килобайт
         * против двадцати у обычного DASH. У Shorts это заметнее всего,
         * и чёрный экран на две секунды выглядит поломкой.
         */
        cover.cornerRadius = 0f
        cover.placeholderColor = 0xFF000000.toInt()

        addView(
            cover, 0,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        addView(buildTexts())
        addView(buildButtons())

        addView(
            ring,
            LayoutParams(dp(36f), dp(36f), Gravity.CENTER)
        )

        notice.gravity = Gravity.CENTER
        notice.visibility = GONE

        addView(
            notice,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { leftMargin = dp(24f); rightMargin = dp(24f) }
        )
    }

    /**
     * Подписи — числами оригинала: `Margin="14,0,82,16"`.
     *
     * Названию отведено ровно 32 точки под две строки, и стояли они
     * в колонке «по содержимому», отчего вторая строка обрезалась:
     * колонка меряла себя по одной, а текст просил две. Здесь высота
     * задана прямо, как в `layoutSubviews` оригинала, а строка автора
     * идёт **над** названием отдельным рядом с кружком.
     */
    private fun buildTexts(): View {
        val holder = object : ViewGroup(context) {

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                setMeasuredDimension(
                    MeasureSpec.getSize(widthMeasureSpec),
                    MeasureSpec.getSize(heightMeasureSpec)
                )
            }

            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val width = r - l
                val height = b - t

                val side = dp(14f)
                val buttons = dp(82f)

                val textWidth = width - buttons - side

                val titleHeight = dp(32f)
                val bottom = height - dp(16f)

                titleLabel.frame(side, bottom - titleHeight, textWidth, titleHeight)

                // Строка автора над названием: кружок 30 в колонке 42.
                val rowHeight = dp(34f)
                val rowTop = bottom - titleHeight - dp(6f) - rowHeight

                val avatarSide = dp(30f)

                shortAvatar.frame(
                    side, rowTop + (rowHeight - avatarSide) / 2, avatarSide, avatarSide
                )

                authorLabel.frame(
                    side + dp(42f) + dp(8f), rowTop,
                    textWidth - dp(42f) - dp(8f), rowHeight
                )
            }
        }

        shortAvatar.circular = true
        shortAvatar.placeholderColor = 0x33FFFFFF

        holder.addView(shortAvatar)
        holder.addView(authorLabel)
        holder.addView(titleLabel)

        holder.layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        return holder
    }

    private fun buildButtons(): View {
        val column = LinearLayout(context)

        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL

        column.addView(button(likeIcon, likeCount, "pl_like") { rate() })

        val comments = ImageView(context)

        column.addView(button(comments, commentCount, "pl_comments") { openComments() })

        val share = ImageView(context)

        column.addView(button(share, null, "pl_send") { share() })

        column.layoutParams = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.RIGHT
        ).apply { rightMargin = dp(12f); bottomMargin = dp(24f) }

        return column
    }

    private fun button(
        icon: ImageView,
        count: android.widget.TextView?,
        name: String,
        action: () -> Unit
    ): View {
        val holder = TappableView(context)

        holder.highlights = false

        val column = LinearLayout(context)

        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL

        icon.setImageBitmap(Icons.darkIcon(name))
        icon.scaleType = ImageView.ScaleType.FIT_CENTER

        column.addView(icon, LinearLayout.LayoutParams(dp(32f), dp(32f)))

        if (count != null) {
            count.gravity = Gravity.CENTER

            column.addView(count)
        }

        holder.addView(column)
        holder.setPadding(dp(8f), dp(10f), dp(8f), dp(10f))

        holder.onTap = action

        return holder
    }

    // --- Лента ------------------------------------------------------------

    fun start() {
        if (items.isNotEmpty()) {
            return
        }

        seed?.let {
            items.add(it)

            sequence = it.shortsSequence
        }

        loadMore(true)
    }

    private fun loadMore(first: Boolean) {
        if (busy) {
            return
        }

        busy = true

        val token = sequence

        async {
            val page = Api.shorts(token)

            main {
                busy = false

                if (page == null) {
                    if (items.isEmpty()) {
                        showNotice(loc("Shorts не загрузились"))
                    }

                    return@main
                }

                sequence = page.sequence

                val seen = items.mapNotNull { it.videoId }.toHashSet()

                for (item in page.items) {
                    if (item.videoId != null && seen.add(item.videoId!!)) {
                        items.add(item)
                    }
                }

                if (first) {
                    show(0)
                }
            }
        }
    }

    private fun show(index: Int) {
        if (index < 0 || index >= items.size) {
            return
        }

        at = index

        val item = items[index]

        titleLabel.text = item.title
        authorLabel.text = item.channelTitle ?: ""

        likeCount.text = ""
        commentCount.text = ""

        cover.visibility = VISIBLE

        ImageLoader.loadInto(cover, item.thumbnail, Metrics.points(width))

        ring.color = android.graphics.Color.WHITE
        ring.start()

        notice.visibility = GONE

        val videoId = item.videoId ?: return

        async {
            val ready = Api.shortsPlayback(videoId)

            main {
                if (at != index) {
                    return@main
                }

                ring.stop()

                if (ready == null) {
                    showNotice(loc("Поток не пришёл"))

                    return@main
                }

                if (ready.botGate) {
                    showNotice(loc("YouTube просит подтвердить, что вы не робот"))

                    return@main
                }

                playback = ready
                liked = ready.liked

                ready.title?.let { titleLabel.text = it }
                ready.channelTitle?.let { authorLabel.text = it }

                ImageLoader.loadInto(shortAvatar, ready.channelThumbnail, 30f)

                likeCount.text = ready.likes ?: ""
                commentCount.text = ready.comments ?: ""

                applyRating()

                cover.visibility = GONE

                PlayerEngine.attach(surface)
                PlayerEngine.open(videoId, null)
            }
        }

        // Догружаем ленту заранее — за три ролика до конца.
        if (index >= items.size - 3) {
            loadMore(false)
        }
    }

    private fun showNotice(text: String) {
        ring.stop()

        notice.text = text
        notice.visibility = VISIBLE
    }

    // --- Свайпы -----------------------------------------------------------

    private var downY = 0f

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        event.actionMasked == MotionEvent.ACTION_MOVE

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = event.y

            MotionEvent.ACTION_UP -> {
                val shift = event.y - downY

                val threshold = height / 6f

                when {
                    shift < -threshold -> show(at + 1)
                    shift > threshold -> show(at - 1)
                    else -> PlayerEngine.togglePlay()
                }
            }
        }

        return true
    }

    // --- Действия ---------------------------------------------------------

    private fun rate() {
        if (!Auth.isSignedIn()) {
            Toast.show(context, loc("Войдите в аккаунт"))

            return
        }

        val videoId = items.getOrNull(at)?.videoId ?: return

        val want = if (liked) "none" else "like"

        liked = !liked

        applyRating()

        async {
            if (!Api.rate(videoId, want, null)) {
                main {
                    liked = !liked

                    applyRating()
                }
            }
        }
    }

    private fun applyRating() {
        likeIcon.setImageBitmap(Icons.darkIcon(if (liked) "pl_like_on" else "pl_like"))
    }

    private fun openComments() {
        val item = items.getOrNull(at) ?: return

        CommentsSheet(
            context, item.videoId ?: return, playback?.commentsToken
        ).show()
    }

    private fun share() {
        val videoId = items.getOrNull(at)?.videoId ?: return

        val intent = android.content.Intent(android.content.Intent.ACTION_SEND)

        intent.type = "text/plain"
        intent.putExtra(
            android.content.Intent.EXTRA_TEXT,
            "https://youtube.com/shorts/$videoId"
        )

        context.startActivity(
            android.content.Intent.createChooser(intent, loc("Поделиться"))
        )
    }

    /** Ролик доиграл: повторяем либо идём дальше — по настройке. */
    fun playbackFinished() {
        if (Settings.autoplayNextShort) {
            show(at + 1)
        } else {
            PlayerEngine.seekTo(0.0)
        }
    }

    fun stop() {
        PlayerEngine.pause()
        PlayerEngine.attach(null)
    }
}

/** Вкладка Shorts в нижней панели. */
class ShortsSection(context: Context) : Shell.Section(context) {

    private lateinit var shorts: ShortsView

    override fun build(): ViewGroup {
        val root = FrameLayout(context)

        shorts = ShortsView(context)

        root.addView(
            shorts,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        return root
    }

    override fun appear() {
        shorts.start()
    }

    /**
     * Ушли со вкладки — ролик замолкает.
     *
     * Плеер здесь общий на всё приложение, поэтому мало снять
     * с него поверхность: без остановки звук продолжал идти
     * из-под другой вкладки.
     */
    override fun disappear() {
        shorts.stop()
    }
}

/**
 * Листалка, открытая из выдачи или ленты, — со своей стопкой.
 *
 * Карточка передаётся целиком: в ней и пропуск на ленту вокруг ролика,
 * и подписи с превью, которые листалка покажет, пока лента едет.
 */
class ShortsScreen(context: Context, private val item: VideoItem) : Screen(context) {

    private lateinit var shorts: ShortsView

    override fun build(root: FrameLayout) {
        shorts = ShortsView(context)

        shorts.seed = item

        root.addView(
            shorts,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        shorts.start()
    }

    override fun destroy() {
        super.destroy()

        shorts.stop()

        PlayerEngine.release()
    }
}
