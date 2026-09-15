package ru.computershik.troubadour.player

import android.content.Context
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.ui.Fonts
import ru.computershik.troubadour.ui.Icons
import ru.computershik.troubadour.ui.Metrics
import ru.computershik.troubadour.ui.Metrics.dp
import ru.computershik.troubadour.ui.Nav
import ru.computershik.troubadour.ui.PillView
import ru.computershik.troubadour.ui.Screen
import ru.computershik.troubadour.ui.TappableView
import ru.computershik.troubadour.ui.Theme
import ru.computershik.troubadour.ui.frame
import ru.computershik.troubadour.ui.label

/**
 * Мини-плеер — порт `MiniPlayer.cs`.
 *
 * Сворачивание страницы ролика не останавливает воспроизведение: кадр
 * переезжает в маленькое окно в правом нижнем углу, а человек тем временем
 * ходит по лентам. Нажатие по окну возвращает страницу целиком, крестик
 * закрывает всё.
 *
 * Числа оттуда же: окно 220×124, отступ от краёв 10, кнопки 32×32
 * на подложке `#78000000`.
 *
 * Живёт окно поверх всего приложения и переживает переходы между экранами —
 * потому и сделано одиночкой, а не частью какого-то вида.
 *
 * **Переезд кадра здесь устроен иначе, чем в оригинале.** Там страница
 * отдавала окну сам `AVPlayerLayer`: перенести слой между видами дешевле,
 * чем создать новый, — новый начал бы с чёрного кадра и заново набирал бы
 * буфер. Здесь переносить нечего: поверхность живёт в окне, а плеер —
 * в [PlayerEngine], и переезд сводится к тому, чтобы сказать плееру новую
 * поверхность. Буфер при этом не теряется вовсе.
 */
object MiniPlayer {

    private const val WINDOW_WIDTH = 220f
    private const val WINDOW_HEIGHT = 124f
    private const val EDGE = 10f
    private const val BUTTON = 32f

    /**
     * Пределы размера при разведении пальцев.
     *
     * Меньше ста шестидесяти окно перестаёт быть окном — не разобрать
     * ни кадра, ни кнопок; больше `CompactWidth` из оригинала (360) оно
     * закрывает половину экрана телефона, и проще уже развернуть.
     */
    private const val MIN_WIDTH = 160f
    private const val MAX_WIDTH = 360f

    private var host: ViewGroup? = null
    private var window: MiniBox? = null

    private var playButton: TappableView? = null
    private var closeButton: TappableView? = null
    private lateinit var titleLabel: android.widget.TextView

    /** Ширина, заданная пальцами; ноль — окно нетронутого размера. */
    private var userWidth = 0f

    /** Куда окно передвинули; передвигали ли вообще. */
    private var originX = 0f
    private var originY = 0f
    private var moved = false
    private var surface: TextureView? = null

    /**
     * Окно: кадр во всю площадь, кнопки по верхним углам, название снизу.
     *
     * Раскладка руками числами оригинала (`place` в `YTMiniPlayer`):
     * кнопка 32 в углу `4,4`, закрытие зеркально справа, название
     * полосой 20 у нижнего края. Перетаскивание одним пальцем
     * и разведение двумя — там же, где в UWP окно `Popup` тоже
     * и таскают, и растягивают.
     */
    class MiniBox(context: Context) : FrameLayout(context) {

        /**
         * Касания по кнопкам окну не достаются.
         *
         * Кнопки лежат внутри окна, а перетаскивание слушает само окно;
         * без разбора нажатие по «✕» уходило бы в перетаскивание. То же
         * правило и в оригинале — `gestureRecognizer:shouldReceiveTouch:`.
         */
        private var dragging = false
        private var lastX = 0f
        private var lastY = 0f

        /** Сколько палец прошёл за нажатие — по этому отличают тычок. */
        private var travelled = 0f

        /**
         * Было ли за это касание больше одного пальца.
         *
         * Растягивая окно щипком, человек почти не сдвигает его —
         * пройденное остаётся меньше порога, — а разводит пальцы.
         * Отпустив их, он получал переход на страницу ролика, будто
         * ткнул. Пройденное тут не мерка: движение уходит в
         * распознаватель щипка, а не в перенос.
         */
        private var pinched = false

        private val zoom = android.view.ScaleGestureDetector(
            context,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {

                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    pinched = true

                    resizeBy(detector.scaleFactor)

                    return true
                }
            }
        )

        override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean {
            // Кнопкам не мешаем: перехватываем только начавшееся движение.
            return event.actionMasked == android.view.MotionEvent.ACTION_MOVE ||
                event.pointerCount > 1
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            zoom.onTouchEvent(event)

            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    travelled = 0f
                    pinched = false

                    lastX = event.rawX
                    lastY = event.rawY
                }

                /**
                 * Второй палец — значит, окно растягивают, а не носят.
                 * Перенос на это время прекращаем, иначе окно дёргается
                 * вслед за средней точкой.
                 */
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    dragging = false
                    pinched = true
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    if (dragging && event.pointerCount == 1) {
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY

                        travelled += Math.abs(dx) + Math.abs(dy)

                        moveBy(dx, dy)

                        lastX = event.rawX
                        lastY = event.rawY
                    }
                }

                android.view.MotionEvent.ACTION_UP -> {
                    dragging = false

                    /**
                     * Тычок по окну возвращает страницу, протяжка — нет.
                     * Порог в касание пальца: без него всякое движение
                     * заканчивалось бы разворотом окна обратно.
                     */
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

                    if (!pinched && travelled < slop) {
                        restore()
                    }
                }

                android.view.MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    pinched = false
                }
            }

            return true
        }

        /**
         * Своей раскладки у окна нет — детей укладывает `FrameLayout`.
         *
         * Была: кадр, кнопки и подпись расставлялись руками. От этого
         * в окне пропала картинка — поверхность `SurfaceView` живёт
         * в системном композиторе и за ручной укладкой не поспевает.
         * Размер и место окна задаются его собственными `LayoutParams`,
         * а внутри всё стоит по гравитации и отступам.
         */
    }

    /**
     * Сама страница свёрнутого ролика — её же и возвращают по нажатию.
     *
     * Окно держит её у себя, а не отпускает: собранная страница знает
     * и свой плейлист, и место в нём, и разобранное описание. Открыв
     * вместо неё новую, всё это пришлось бы добывать заново, а плейлист
     * и вовсе терялся — о нём знала только прежняя страница.
     */
    private var owner: Screen? = null

    /** Идентификатор свёрнутого ролика; null, если окна нет. */
    var videoId: String? = null
        private set

    /** Свёрнут ли сейчас какой-нибудь ролик. */
    val isActive: Boolean
        get() = window != null

    val isPlaying: Boolean
        get() = PlayerEngine.isPlaying

    fun togglePlay() {
        PlayerEngine.togglePlay()

        applyState()
    }

    /** Куда класть окно. Ставит `MainActivity` при запуске. */
    fun attach(parent: ViewGroup) {
        host = parent
    }

    /**
     * Сворачивает: забирает поверхность себе и показывает окно.
     */
    /** Заслонка с последним кадром, пока новая поверхность не ожила. */
    private var still: ImageView? = null

    fun show(
        screen: Screen,
        videoId: String,
        title: String,
        author: String,
        shot: android.graphics.Bitmap? = null
    ) {
        val parent = host ?: return

        if (window != null) {
            close()
        }

        this.owner = screen
        this.videoId = videoId

        val context = parent.context

        val box = MiniBox(context)

        box.setBackgroundColor(0xFF000000.toInt())

        val view = TextureView(context)

        box.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        surface = view

        /**
         * Снимок кадра поверх поверхности — до первого своего кадра.
         *
         * Новый `TextureView` заводит поверхность не раньше следующей
         * раскладки, и до первого кадра на её месте чернота. Снимок,
         * принесённый со страницы, её закрывает, и сворачивание
         * выглядит переносом картинки, а не её пропажей.
         */
        val cover = ImageView(context)

        cover.scaleType = ImageView.ScaleType.FIT_CENTER
        cover.setImageBitmap(shot)

        cover.visibility = if (shot != null) View.VISIBLE else View.GONE

        box.addView(
            cover,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        still = cover

        Notify.on(PlayerEngine.FIRST_FRAME, this) {
            still?.visibility = View.GONE
            still?.setImageBitmap(null)
        }

        /**
         * Кнопки стоят по верхним углам, а не посреди картинки.
         *
         * В оригинале `playPause` лежит в углу `4,4`, а закрытие —
         * зеркально справа; посередине окна не стоит ничего, потому что
         * там сам ролик. Большая кнопка по центру закрывала его собой,
         * и это выглядело как несходящая заслонка.
         */
        playButton = makeButton(context, "pl_pause") { togglePlay() }
        closeButton = makeClose(context) { close() }

        titleLabel = label(context, Fonts.regular, 11f, android.graphics.Color.WHITE, 1)

        titleLabel.text = title
        titleLabel.setBackgroundColor(0x8C000000.toInt())
        titleLabel.setPadding(dp(6f), 0, dp(6f), 0)

        box.addView(
            titleLabel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20f), Gravity.BOTTOM
            )
        )

        // Кнопка 32 в углу `4,4`, закрытие зеркально справа — как в оригинале.
        box.addView(
            playButton,
            FrameLayout.LayoutParams(
                dp(BUTTON), dp(BUTTON), Gravity.TOP or Gravity.LEFT
            ).apply { leftMargin = dp(4f); topMargin = dp(4f) }
        )

        box.addView(
            closeButton,
            FrameLayout.LayoutParams(
                dp(BUTTON), dp(BUTTON), Gravity.TOP or Gravity.RIGHT
            ).apply { rightMargin = dp(4f); topMargin = dp(4f) }
        )

        /**
         * Тень — не украшение: окно висит поверх лент, и без неё
         * теряется на тёмной карточке. До API 21 её нет — рисовать
         * тень руками ради этого не стоит.
         */
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            box.elevation = dp(6f).toFloat()
        }

        parent.addView(
            box,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        window = box

        // Размер и место окно получает само — родитель их не задаёт.
        box.post { place() }

        // Новое окно встаёт у угла, как в первый раз.
        moved = false

        // Плеер рисует туда же, куда рисовал, — просто в другой вид.
        PlayerEngine.attach(view)

        NowPlaying.show(title, author, "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")

        applyState()

        Notify.post(Notify.MINIPLAYER)
    }

    private fun makeButton(
        context: Context,
        icon: String,
        action: () -> Unit
    ): TappableView {
        val button = TappableView(context)

        val pill = PillView(context)

        pill.fillColor = 0x78000000
        pill.cornerRadius = dp(BUTTON) / 2f

        button.addView(
            pill,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val image = ImageView(context)

        image.setImageBitmap(Icons.darkIcon(icon))
        image.scaleType = ImageView.ScaleType.FIT_CENTER

        button.addView(
            image,
            FrameLayout.LayoutParams(dp(18f), dp(18f), Gravity.CENTER)
        )

        button.onTap = action
        button.highlights = false

        button.tag = icon

        return button
    }

    /**
     * Закрытие — знаком «✕», а не значком.
     *
     * Так же в оригинале: готового крестика в наборе нет, и там он
     * нарисован подписью 15 кегля.
     */
    private fun makeClose(context: Context, action: () -> Unit): TappableView {
        val button = TappableView(context)

        val pill = PillView(context)

        pill.fillColor = 0x78000000
        pill.cornerRadius = dp(BUTTON) / 2f

        button.addView(
            pill,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val mark = label(context, Fonts.regular, 15f, android.graphics.Color.WHITE, 1)

        mark.text = "✕"
        mark.gravity = Gravity.CENTER

        button.addView(
            mark,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        button.onTap = action
        button.highlights = false

        return button
    }

    /** Значок кнопки под нынешнее состояние. */
    private fun applyState() {
        val button = playButton ?: return

        val name = if (PlayerEngine.isPlaying) "pl_pause" else "pl_play"

        (button.getChildAt(1) as? ImageView)?.setImageBitmap(Icons.darkIcon(name))

        NowPlaying.update()
    }

    /** Нынешняя ширина окна в пикселях. */
    private fun currentWidth(): Int =
        if (userWidth > 0) userWidth.toInt() else dp(WINDOW_WIDTH)

    /**
     * Разведение пальцев меняет ширину, высота идёт за ней по пропорции.
     *
     * Множитель приходит шаговый — за весь жест он бы накопился, и окно
     * улетало бы в предел с первого же движения.
     */
    private fun resizeBy(scale: Float) {
        val want = currentWidth() * scale

        userWidth = want.coerceIn(dp(MIN_WIDTH).toFloat(), dp(MAX_WIDTH).toFloat())

        /**
         * Растёт окно от того угла, за который его держат: вправо
         * и вниз оно упёрлось бы в край. Оставляем на месте правый
         * нижний угол — как в оригинале.
         */
        val box = window ?: return

        if (moved) {
            val height = userWidth * WINDOW_HEIGHT / WINDOW_WIDTH

            originX = box.right - userWidth
            originY = box.bottom - height
        }

        place()
    }

    private fun moveBy(dx: Float, dy: Float) {
        val box = window ?: return

        if (!moved) {
            moved = true

            originX = box.left.toFloat()
            originY = box.top.toFloat()
        }

        originX += dx
        originY += dy

        place()
    }

    /**
     * Ставит окно на место: у правого нижнего угла, пока его не двигали,
     * и туда, куда передвинули, — после. За край не пускаем ни при
     * перетаскивании, ни при росте.
     */
    private fun place() {
        val parent = host ?: return
        val box = window ?: return

        val width = currentWidth()
        val height = width * WINDOW_HEIGHT.toInt() / WINDOW_WIDTH.toInt()

        val edge = dp(EDGE)

        var left: Int
        var top: Int

        if (moved) {
            left = originX.toInt()
            top = originY.toInt()
        } else {
            left = parent.width - width - edge

            /**
             * Отступ снизу — над нижней панелью, а не над краем экрана.
             * Панель никуда не девается, и окно у самого края наполовину
             * пряталось бы за ней.
             */
            top = parent.height - height - edge -
                dp(Metrics.TAB_BAR_HEIGHT) - dp(Metrics.TAB_BAR_DIVIDER)
        }

        left = left.coerceIn(edge, maxOf(edge, parent.width - width - edge))
        top = top.coerceIn(edge, maxOf(edge, parent.height - height - edge))

        originX = left.toFloat()
        originY = top.toFloat()

        val params = box.layoutParams as? FrameLayout.LayoutParams ?: return

        params.width = width
        params.height = height
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.leftMargin = left
        params.topMargin = top

        box.layoutParams = params
    }

    /** Возвращает страницу целиком, ничего не останавливая. */
    fun restore() {
        val screen = owner ?: return

        /**
         * Снимок отдаём странице до того, как окошко разберут: у неё
         * поверхность тоже заводится заново, и без снимка там та же
         * чернота, только на весь кадр.
         */
        val shot = try {
            surface?.takeIf { it.isAvailable }?.bitmap
        } catch (error: Throwable) {
            null
        }

        hideWindow()

        (screen as? ru.computershik.troubadour.ui.PlayerScreen)?.holdFrame(shot)

        if (Nav.contains(screen)) {
            Nav.popTo(screen)
        } else {
            Nav.push(screen)
        }

        owner = null
        videoId = null

        Notify.post(Notify.MINIPLAYER)
    }

    /**
     * Нажата «назад» при открытом окне.
     *
     * Окно само по себе назад не реагирует: оно не экран, а надстройка
     * над ним. false здесь означает «решай сам» — и решает стопка.
     */
    fun handleBack(): Boolean = false

    private fun hideWindow() {
        val parent = host
        val box = window ?: return

        PlayerEngine.attach(null)

        // Подписка на первый кадр живёт вместе с окном.
        Notify.offAll(this)

        parent?.removeView(box)

        still = null
        window = null
        surface = null
    }

    /** Открыто ли окно сейчас. */
    fun isOpen(): Boolean = window != null

    /** Закрывает окно и всё, что за ним: плеер, подачу, сигналы. */
    fun close() {
        val screen = owner

        hideWindow()

        owner = null
        videoId = null

        screen?.destroy()

        PlayerEngine.release()

        NowPlaying.hide()

        Notify.post(Notify.MINIPLAYER)
    }
}
