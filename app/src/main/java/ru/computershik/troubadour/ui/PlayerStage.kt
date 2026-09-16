package ru.computershik.troubadour.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.player.PlayerEngine
import ru.computershik.troubadour.ui.Metrics.dp
import ru.computershik.troubadour.ui.Metrics.dpf

/**
 * Кадр с пультом — порт `CustomVideoPlayer.xaml`.
 *
 * Пульт свой, а не штатный, и причина та же, что в оригинале: готовый
 * не пускает внутрь себя. Там это был `MPMoviePlayerController`, здесь —
 * `PlayerControlView` из ExoPlayer, у которого своя разметка, свои цвета
 * и своё представление о том, где что стоит. Требование к порту
 * сформулировано жёстко — дизайн должен в точности соответствовать
 * оригиналу, — поэтому всё нарисовано: кнопка воспроизведения, полоса,
 * время, разворот, шестерёнка.
 *
 * Числа перенесены из XAML через iOS-порт и не пересчитывались:
 *
 *     кадр           подложка #0f0f0f — число, а не кисть темы:
 *                    кадр остаётся тёмным и в светлом оформлении
 *     кнопки         40×40, круг #66000000 при непрозрачности 0.8,
 *                    значок 20; отступ от края 8
 *     пауза          80×80, значок 48
 *     нижняя полоса  высота 80, картинка-затемнение
 *       верхний ряд  высота 40, отступ сверху 8
 *         время      подложка #80000000, скругление 15, поля 10×5,
 *                    подпись 14 SemiBold белая
 *         разворот   40×40, отступ справа 16
 *       нижний ряд   высота 20, отступы 16/16, снизу 8
 *         дорожка    высота 4, скругление 2, #666666
 *         пройдено   #f03, кружок 16 того же цвета
 *     название       только в развёрнутом виде, отступы 16,10,64;
 *                    16 SemiBold, автор 13 #DDDDDD
 */
class PlayerStage(context: Context) : ViewGroup(context) {

    // --- Цвета, заданные числом, а не темой -------------------------------

    /** Фон кадра. Тёмный всегда: картинка лежит на нём, а не на странице. */
    private val stageBackground = 0xFF0F0F0F.toInt()

    /** Круг под значком кнопки. */
    private val buttonCircle = 0x66000000

    /** Подложка часов. */
    private val timeBackground = 0x80000000.toInt()

    /** Дорожка полосы и пройденная часть. */
    private val trackColor = 0xFF666666.toInt()
    private val playedColor = 0xFFFF0033.toInt()

    // --- Виды -------------------------------------------------------------

    /**
     * Куда рисуется картинка.
     *
     * `TextureView`, а не `SurfaceView`, и выбор этот вынужденный.
     *
     * `SurfaceView` дешевле: он отдаёт свой слой прямо системному
     * композитору, минуя конвейер окна. Но слой этот — **отдельное
     * окно**, и его место с размером доходят до композитора только
     * штатным проходом отрисовки. Вся наша разметка идёт числами
     * оригинала и руками, и такое окно за ней не поспевало: кадр
     * на миг появлялся целиком, а потом возвращался в прежний
     * прямоугольник — от ролика оставалась верхняя полоса, а в окне
     * мини-плеера не оставалось ничего.
     *
     * `TextureView` — обычный вид: где положили, там и рисует. Платим
     * лишним проходом по точкам кадра, зато рамку он слушает.
     *
     * Оригиналу этот выбор не предлагался вовсе: `AVPlayerLayer` —
     * простой слой, поставил рамку — он там и есть.
     */
    val surface = TextureView(context)

    /**
     * Заслонка поверх картинки, пока не пришёл первый кадр нового ролика.
     * Того же цвета, что и подложка кадра, — на глаз её не видно.
     */
    private val blind = ImageView(context)

    /** Затемнение под нижней полосой — `player/bg.png` из оригинала. */
    private val scrim = ImageView(context)

    private val playButton = StageButton(context, "pl_play", 48f)
    private val rewindButton = StageButton(context, "pl_back", 20f)
    private val forwardButton = StageButton(context, "pl_skip", 20f)

    private val collapseButton = StageButton(context, "pl_collapse", 20f)
    private val settingsButton = StageButton(context, "pl_settings", 20f)
    private val fullscreenButton = StageButton(context, "pl_fullscreen", 20f)

    /** Цифры «10» на кнопках перемотки — как в оригинале. */
    private val rewindMark = label(context, Fonts.semiBold, 9f, Color.WHITE, 1)
    private val forwardMark = label(context, Fonts.semiBold, 9f, Color.WHITE, 1)

    private val timeLabel = label(context, Fonts.semiBold, 14f, Color.WHITE, 1)

    private val titleLabel = label(context, Fonts.semiBold, 16f, Color.WHITE, 1)
    private val authorLabel = label(context, Fonts.regular, 13f, 0xFFDDDDDD.toInt(), 1)

    private val progress = ProgressBarView(context)

    private val ring = LoadingRing(context)

    /** Окно «статистика для сисадминов»; спрятано, пока не попросят. */
    private val stats = StatsPanel(context)

    // --- Состояние --------------------------------------------------------

    /** Развёрнут ли кадр на весь экран: от этого зависит состав пульта. */
    /**
     * Сколько снизу и справа отдано системной полосе.
     *
     * В полноэкранном виде кадр уходит под полосу, и пульт надо держать
     * над ней, иначе нижний ряд оказывается под системными кнопками.
     *
     * Место отводится **всегда**, а не только пока полоса видна, и это
     * не расточительство. Полоса возвращается от касания — то есть ровно
     * тогда, когда человек тянется к пульту, — и ряд, подвинувшись
     * в этот же миг на полсотни точек, уходит из-под пальца. Со стороны
     * это выглядит так, будто полоса перемотки не отзывается на нажатия
     * вовсе. Постоянный отступ дороже на полсотни точек внизу, но ряд
     * стоит на месте, и попасть в него можно.
     */
    private val insetBottom: Int
        get() = if (fullscreen) navigationBarHeight() else 0

    private val insetRight: Int
        get() = if (fullscreen) navigationBarWidth() else 0

    var fullscreen = false
        set(value) {
            field = value

            titleLabel.visibility = if (value) VISIBLE else GONE
            authorLabel.visibility = if (value) VISIBLE else GONE
            collapseButton.visibility = if (value) GONE else VISIBLE

            fullscreenButton.icon = if (value) "pl_exit_fullscreen" else "pl_fullscreen"

            requestLayout()
        }

    /**
     * Кадр растянут по экрану — полос по краям нет, края обрезаны.
     *
     * Вторая из двух укладок; первая вписывает кадр целиком. Сюда
     * приводит защёлкивание при увеличении двумя пальцами.
     */
    var fillsScreen = false
        set(value) {
            field = value

            requestLayout()
        }

    /**
     * Свободное увеличение кадра двумя пальцами и его сдвиг.
     *
     * Единица — обычная величина; ниже неё не опускаемся, под кадром
     * чернота, а не страница.
     */
    private var zoomScale = 1f
    private var zoomShift = android.graphics.PointF(0f, 0f)

    private var zoomFrom = 1f
    private var zoomAnchor = android.graphics.PointF(0f, 0f)
    private var zoomUsed = false

    /** Сообщить человеку одной строкой — заводится снаружи. */
    var onNotice: ((String) -> Unit)? = null

    /**
     * Величина, при которой полосы исчезают.
     *
     * Это отношение сторон кадра к сторонам экрана — что у лежачего
     * ролика на высоком экране, что у стоячего на широком. Единица
     * означает, что полос нет вовсе и защёлкивать нечего.
     */
    private fun fillRatio(): Float {
        val box = width.toFloat()
        val tall = height.toFloat()

        if (box <= 0 || tall <= 0) {
            return 1f
        }

        val video = PlayerEngine.videoRatio
        val screen = box / tall

        if (video <= 0) {
            return 1f
        }

        return maxOf(video / screen, screen / video)
    }

    /**
     * Пора ли защёлкивать подгон.
     *
     * Порог — восемь сотых: разница, которую глаз уже не отличает
     * от точного совпадения, но которой хватает, чтобы не сработать
     * случайно по дороге к настоящему увеличению. Полосы шириной меньше
     * сотой доли экрана не в счёт — там защёлкивать нечего.
     */
    private fun shouldSnapToFill(scale: Float): Boolean {
        if (fillsScreen) {
            return false
        }

        val ratio = fillRatio()

        return ratio > 1.01f && kotlin.math.abs(scale - ratio) < 0.08f
    }

    /**
     * Сдвиг, укладывающийся в границы.
     *
     * За край кадр не пускаем вовсе, а не подтягиваем потом: подтянутый
     * кадр дёргается под пальцем, а не пущенный просто упирается.
     */
    private fun settledShift(): android.graphics.PointF {
        if (zoomScale <= 1f) {
            return android.graphics.PointF(0f, 0f)
        }

        val limitX = width * (zoomScale - 1f) / 2f
        val limitY = height * (zoomScale - 1f) / 2f

        return android.graphics.PointF(
            zoomShift.x.coerceIn(-limitX, limitX),
            zoomShift.y.coerceIn(-limitY, limitY)
        )
    }

    private fun applyZoom() {
        surface.scaleX = zoomScale
        surface.scaleY = zoomScale
        surface.translationX = zoomShift.x
        surface.translationY = zoomShift.y
    }

    /** Вернуть кадр к обычной величине — при выходе, смене ролика, подгоне. */
    fun resetZoom() {
        zoomScale = 1f
        zoomShift.set(0f, 0f)

        applyZoom()
    }

    /** Показан ли пульт. Прячется сам через несколько секунд. */
    var controlsVisible = true
        private set

    /** Показано ли окно статистики. */
    val statsVisible: Boolean
        get() = stats.visibility == VISIBLE

    fun showStats(on: Boolean) {
        stats.visibility = if (on) VISIBLE else GONE
    }

    var onPlayPause: (() -> Unit)? = null
    var onSeek: ((Double) -> Unit)? = null
    var onScrubbing: ((Double) -> Unit)? = null
    var onFullscreen: (() -> Unit)? = null
    var onCollapse: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null
    var onSkip: ((Double) -> Unit)? = null

    /** Палец на полосе; ход воспроизведения тогда не указ. */
    private var scrubbing = false

    /** Прыжок заказан, но плеер ещё отвечает прежним временем. */
    private var awaiting = false

    /** Когда прыжок был заказан — чтобы ждать его не вечно. */
    private var awaitingAt = 0L

    private var seekTarget = 0.0

    /** Длительность помним отдельно: подпись рисуется и без тика. */
    private var length = 0.0

    /**
     * Потолок высоты кадра, когда её не задали снаружи; ноль — без
     * потолка. Ставит страница: только она знает высоту экрана.
     */
    var heightCap = 0

    init {
        setBackgroundColor(stageBackground)

        addView(surface)

        blind.setBackgroundColor(stageBackground)
        blind.scaleType = ImageView.ScaleType.FIT_CENTER
        blind.visibility = GONE

        addView(blind)

        scrim.setImageBitmap(Icons.darkIcon("pl_scrim"))
        scrim.scaleType = ImageView.ScaleType.FIT_XY

        addView(scrim)
        addView(progress)

        addView(playButton)
        addView(rewindButton)
        addView(forwardButton)
        addView(collapseButton)
        addView(settingsButton)
        addView(fullscreenButton)

        addView(rewindMark)
        addView(forwardMark)
        addView(timeLabel)
        addView(titleLabel)
        addView(authorLabel)
        addView(ring)

        stats.visibility = GONE
        stats.onClose = { showStats(false) }

        addView(stats)

        /**
         * Назад на 5, вперёд на 15 — не поровну, и это не описка.
         *
         * Числа взяты у оригинала (`stageMark:@"5"` и `@"15"`): назад
         * возвращаются, чтобы переслушать оброненное слово, а вперёд
         * перескакивают заставку, и шаги эти разной длины.
         */
        rewindMark.text = "5"
        forwardMark.text = "15"

        rewindMark.gravity = android.view.Gravity.CENTER
        forwardMark.gravity = android.view.Gravity.CENTER

        timeLabel.setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
        timeLabel.gravity = android.view.Gravity.CENTER

        playButton.onTap = { onPlayPause?.invoke() }
        rewindButton.onTap = { onSkip?.invoke(-5.0) }
        forwardButton.onTap = { onSkip?.invoke(15.0) }
        collapseButton.onTap = { onCollapse?.invoke() }
        settingsButton.onTap = { onSettings?.invoke() }
        fullscreenButton.onTap = { onFullscreen?.invoke() }

        /**
         * Перемотка живёт на двух флагах, и одного не хватало.
         *
         * `scrubbing` — палец на полосе: ход воспроизведения тогда
         * не указ вовсе, место ведёт палец. `awaiting` — прыжок заказан,
         * но плеер до последнего отвечает прежним временем, и показывать
         * надо цель, а не его ответ.
         *
         * Прежде был только первый, да и тот прикрывал одну полосу:
         * бегунок шёл за пальцем, а часы рядом показывали, где идёт
         * ролик, и после отпускания оба прыгали назад, пока плеер
         * не догонит.
         */
        progress.onScrubbing = { target ->
            scrubbing = true
            seekTarget = target

            showTime(target)

            onScrubbing?.invoke(target)
        }

        progress.onScrubCancel = {
            scrubbing = false
        }

        progress.onSeek = { target ->
            scrubbing = false

            awaiting = true
            seekTarget = target
            awaitingAt = android.os.SystemClock.uptimeMillis()

            showTime(target)

            onSeek?.invoke(target)
        }

        titleLabel.visibility = GONE
        authorLabel.visibility = GONE

        ring.visibility = GONE
    }

    // --- Показ и скрытие --------------------------------------------------

    private val hider = Runnable { hideControls() }

    /**
     * Пока палец на экране, пульт не прячется.
     *
     * Скрытие назначается на три секунды вперёд с того мига, как пульт
     * показали. Перемотка занимает больше: человек ведёт бегунок, а пульт
     * из-под пальца исчезает вместе с полосой, и перемотка обрывается.
     * Любое касание отодвигает срок заново.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (controlsVisible) {
            scheduleHide()
        }

        return super.dispatchTouchEvent(event)
    }

    fun showControls() {
        controlsVisible = true

        for (view in controls()) {
            view.visibility = VISIBLE
        }

        applyState()
        scheduleHide()
    }

    /**
     * Пульт спрятался.
     *
     * Страница ролика по этому прячет и системную полосу: пока пульт
     * на экране, полоса нужна вместе с ним, а без пульта она только
     * закрывает кадр.
     */
    var onControlsHidden: (() -> Unit)? = null

    fun hideControls() {
        if (!controlsVisible) {
            return
        }

        controlsVisible = false

        for (view in controls()) {
            view.visibility = GONE
        }

        onControlsHidden?.invoke()
    }

    /**
     * Прячем через три секунды — столько же, сколько в оригинале.
     *
     * На паузе не прячем: человек остановил ролик, чтобы посмотреть,
     * и убирать у него из-под руки кнопку было бы странно.
     */
    private fun scheduleHide() {
        removeCallbacks(hider)

        if (!PlayerEngine.isPlaying) {
            return
        }

        postDelayed(hider, 3000)
    }

    private fun controls(): List<View> = listOf(
        scrim, progress, playButton, rewindButton, forwardButton,
        settingsButton, fullscreenButton, timeLabel, rewindMark, forwardMark
    ) + if (fullscreen) {
        listOf(titleLabel, authorLabel)
    } else {
        listOf(collapseButton)
    }

    /** Значок кнопки под нынешнее состояние. */
    /**
     * Закрыть картинку до первого кадра нового ролика.
     *
     * `SurfaceView` держит последний нарисованный кадр, пока не получит
     * новый, и при переходе к другому ролику на нём оставался
     * «отпечаток» прежнего.
     *
     * Закрываем **заслонкой поверх**, а не прячем сам вид. Спрятанный
     * `SurfaceView` уничтожает свою поверхность, плееру становится
     * некуда рисовать — и первого кадра он не пришлёт уже никогда:
     * заслонка, которую снимают по первому кадру, не снималась бы
     * вовсе. На странице ролика от этого пропадала картинка целиком,
     * хотя в мини-плеере, у которого своя поверхность, всё шло.
     */
    fun clearFrame() {
        blind.setImageBitmap(null)

        blind.visibility = VISIBLE
    }

    /**
     * Заслонка с последним кадром — на время пересадки поверхности.
     *
     * Уходя в окошко и возвращаясь, вид снимают с окна, а `TextureView`
     * при этом теряет свою поверхность: новая заводится не раньше
     * следующей раскладки, и до первого кадра на её месте чернота.
     * Снимок последнего кадра эту черноту закрывает, и переход выглядит
     * так, будто картинка никуда не девалась.
     *
     * Снимок берётся у самого вида и стоит недорого: кадр уже лежит
     * в его текстуре, читать его заново неоткуда.
     */
    fun holdFrame(shot: android.graphics.Bitmap?) {
        blind.setImageBitmap(shot)

        blind.visibility = VISIBLE

        /**
         * Снимок держим не дольше двух с половиной секунд.
         *
         * Снимается он по первому кадру, и тот приходит: смена
         * поверхности у ExoPlayer первый кадр объявляет заново.
         * Но застывшая картинка поверх идущего ролика — худшее,
         * чем можно кончить, и подстраховка тут дешевле уверенности.
         */
        blind.removeCallbacks(dropHold)
        blind.postDelayed(dropHold, 2500)
    }

    private val dropHold = Runnable {
        if (blind.drawable != null) {
            showFrame()
        }
    }

    /**
     * Снимок того, что сейчас на кадре.
     *
     * Берётся у самого вида и стоит недорого: кадр уже лежит в его
     * текстуре. Пусто, если поверхность ещё не заведена.
     */
    fun lastFrame(): android.graphics.Bitmap? = try {
        if (surface.isAvailable) surface.bitmap else null
    } catch (error: Throwable) {
        null
    }

    /** Кадр пришёл — убираем заслонку. */
    fun showFrame() {
        blind.removeCallbacks(dropHold)

        blind.visibility = GONE

        blind.setImageBitmap(null)
    }

    fun applyState() {
        /**
         * Экран не гаснет, пока идёт ролик.
         *
         * Флаг ставится на сам кадр, а не на окно: он тогда живёт
         * ровно столько, сколько вид на экране, и не зависит от того,
         * в каком порядке пришли оповещения и кто последним трогал
         * флаги окна.
         */
        keepScreenOn = PlayerEngine.isPlaying

        playButton.icon = when {
            PlayerEngine.isPlaying -> "pl_pause"
            atEnd -> "pl_replay"
            else -> "pl_play"
        }

        scheduleHide()
    }

    private var atEnd = false

    fun setBusy(busy: Boolean) {
        if (busy) {
            ring.color = Color.WHITE
            ring.start()
        } else {
            ring.stop()
        }

        // Пока кольцо крутится, кнопки нет: нажимать всё равно нечего.
        playButton.visibility = if (busy || !controlsVisible) GONE else VISIBLE
    }

    fun setTitle(title: String, author: String) {
        titleLabel.text = title
        authorLabel.text = author
    }

    /** Ход воспроизведения: где мы, докуда набрано, сколько всего. */
    fun setProgress(position: Double, buffered: Double, duration: Double) {
        length = duration

        // Пока палец на полосе, ход воспроизведения не показываем вовсе.
        if (scrubbing) {
            return
        }

        var at = position

        if (awaiting) {
            /**
             * Прыжок засчитан, когда плеер подошёл к цели ближе секунды.
             * До того показываем цель: иначе после отпускания и полоса,
             * и часы откатываются назад, пока он догоняет.
             *
             * Но ждать этого вечно нельзя. Попадание идёт по границам
             * отрезков, и плеер нередко встаёт дальше секунды от заказа —
             * тогда условие не размыкалось совсем, и ползунок замирал
             * на цели, хотя ролик спокойно играл. По сроку отпускаем
             * в любом случае: пусть лучше место дрогнет один раз, чем
             * часы врут неограниченно долго.
             */
            val waited = android.os.SystemClock.uptimeMillis() - awaitingAt

            if (Math.abs(position - seekTarget) < 1.0 || waited > WAIT_MS) {
                awaiting = false
            } else {
                at = seekTarget
            }
        }

        atEnd = duration > 0 && at >= duration - 0.5

        progress.set(at, buffered, duration)

        showTime(at)

        requestLayout()
    }

    /**
     * Показать место, куда собираемся прыгнуть, не прыгая туда сейчас.
     *
     * Нужно кнопкам перемотки: нажатия копятся, а прыжок идёт один
     * и позже, — но полоса и часы обязаны отвечать на каждое нажатие
     * сразу, иначе непонятно, засчиталось ли оно.
     */
    fun previewSeek(target: Double) {
        awaiting = true
        seekTarget = target
        awaitingAt = android.os.SystemClock.uptimeMillis()

        progress.set(target, progress.buffered(), length)

        showTime(target)
    }

    /**
     * Идёт ли перемотка — палец на полосе или прыжок ещё не доехал.
     *
     * Спрашивает страница: пропускать вставки и подставлять субтитры
     * посреди перемотки нельзя, иначе прыжок отменяет сам себя.
     */
    fun isSeeking(): Boolean = scrubbing || awaiting

    /**
     * Подпись под кадром: «12:34 / 45:67 · Название главы».
     *
     * Точка-разделитель и порядок — из `BuildTimeDisplayText` оригинала;
     * глава дописывается только тогда, когда она есть.
     */
    private fun showTime(position: Double) {
        var text = "${clock(position)} / ${clock(length)}"

        val chapter = chapterAt?.invoke(position) ?: ""

        if (chapter.isNotEmpty()) {
            text = "$text · $chapter"
        }

        timeLabel.text = text

        requestLayout()
    }

    /** Вставки SponsorBlock на полосе — зелёным. */
    fun setMarks(marks: List<Pair<Double, Double>>) {
        progress.marks = marks
    }

    /** Начала глав — тонкими просветами в дорожке. */
    fun setChapters(starts: List<Double>) {
        progress.chapters = starts
    }

    /**
     * Кто скажет, какая глава идёт в этот миг.
     *
     * Ставит страница: главы разбирает она, а часам нужно лишь название.
     */
    var chapterAt: ((Double) -> String)? = null

    private fun clock(seconds: Double): String {
        if (seconds.isNaN() || seconds < 0) {
            return "0:00"
        }

        val whole = seconds.toInt()

        val hours = whole / 3600
        val minutes = (whole % 3600) / 60
        val rest = whole % 60

        if (hours > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, rest)
        }

        return String.format(java.util.Locale.US, "%d:%02d", minutes, rest)
    }

    // --- Касания ----------------------------------------------------------

    /**
     * Увеличение двумя пальцами — только в развёрнутом виде.
     *
     * В окне кадр стоит в потоке страницы, и растить его некуда:
     * под ним лежит описание, а не чернота.
     */
    private val pinch = android.view.ScaleGestureDetector(
        context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScaleBegin(
                detector: android.view.ScaleGestureDetector
            ): Boolean {
                zoomUsed = false
                pinchSpan = 1f

                zoomAnchor.set(detector.focusX, detector.focusY)

                return true
            }

            override fun onScale(
                detector: android.view.ScaleGestureDetector
            ): Boolean {
                if (zoomUsed) {
                    return true
                }

                pinchSpan *= detector.scaleFactor

                /**
                 * В окне щипок не увеличивает кадр, а разворачивает его.
                 *
                 * Растить кадр в окне некуда: под ним страница, а не
                 * чернота. Зато сам жест здесь уместен — так же, как
                 * в iOS-версии, где из окна в полный экран и обратно
                 * ведёт та же лестница.
                 *
                 * Пороги несимметричны нарочно: разводят пальцы
                 * размашисто, а сводят скупо — пальцы упираются друг
                 * в друга. Полуторный размах наружу и три четверти
                 * внутрь примерно равны по усилию.
                 */
                if (!fullscreen) {
                    if (pinchSpan > 1.5f) {
                        zoomUsed = true

                        onFullscreen?.invoke()
                    }

                    return true
                }

                /**
                 * `ScaleGestureDetector` отдаёт множитель **шага**, а не
                 * всего жеста, — в отличие от `UIPinchGestureRecognizer`,
                 * откуда правило перенесено. Поэтому копим сами, умножая
                 * нынешнюю величину на шаг.
                 */
                val wanted = zoomScale * detector.scaleFactor

                // Ниже единицы кадр не уменьшаем: под ним чернота.
                val scale = wanted.coerceIn(1f, 6f)

                zoomShift.set(
                    zoomShift.x + detector.focusX - zoomAnchor.x,
                    zoomShift.y + detector.focusY - zoomAnchor.y
                )

                zoomAnchor.set(detector.focusX, detector.focusY)
                zoomScale = scale

                // За край кадр не пускаем вовсе, а не подтягиваем потом.
                zoomShift = settledShift()

                applyZoom()

                /**
                 * Подошли близко к величине, при которой полосы исчезают, —
                 * защёлкиваем её.
                 *
                 * Руками поймать эту величину нельзя: промах в пару
                 * процентов оставляет то щель по краю, то лишнюю обрезку.
                 * А промахнуться легко — кадр при этом выглядит почти
                 * правильно, и человек так и смотрит с полоской в палец
                 * шириной.
                 */
                if (shouldSnapToFill(scale)) {
                    zoomUsed = true

                    resetZoom()

                    fillsScreen = true

                    onNotice?.invoke(ru.computershik.troubadour.loc("Полосы убраны"))

                    return true
                }

                /**
                 * Свели пальцы, а уменьшать уже некуда — значит просят
                 * выйти.
                 *
                 * Сперва возвращаем полосы, и лишь потом выходим
                 * из полного экрана: иначе одно сведение меняло бы сразу
                 * две вещи, и вернуть только одну из них было бы нельзя.
                 */
                if (scale <= 1f && pinchSpan < 0.75f) {
                    zoomUsed = true

                    resetZoom()

                    if (fillsScreen) {
                        fillsScreen = false
                    } else {
                        onFullscreen?.invoke()
                    }
                }

                return true
            }
        }
    )

    /**
     * Во сколько развели пальцы с начала жеста.
     *
     * `ScaleGestureDetector` отдаёт множитель шага, а не всего движения,
     * — размах приходится копить самим.
     */
    private var pinchSpan = 1f

    /** Откуда ведут увеличенный кадр одним пальцем. */
    private var dragFrom: android.graphics.PointF? = null
    private var dragged = false

    /** Откуда началась протяжка — для жестов полного экрана. */
    private val swipeFrom = android.graphics.PointF(0f, 0f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)

        if (pinch.isInProgress) {
            dragFrom = null

            return true
        }

        /**
         * Увеличенный кадр возят одним пальцем.
         *
         * Пока кадр обычной величины, возить нечего, и палец остаётся
         * тем, чем был, — показать или спрятать пульт.
         */
        if (fullscreen && zoomScale > 1f) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragFrom = android.graphics.PointF(event.x, event.y)
                    dragged = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val from = dragFrom

                    if (from != null) {
                        zoomShift.set(
                            zoomShift.x + event.x - from.x,
                            zoomShift.y + event.y - from.y
                        )

                        from.set(event.x, event.y)

                        zoomShift = settledShift()

                        applyZoom()

                        dragged = true
                    }

                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragFrom = null

                    // Возили кадр — значит пульт не трогаем.
                    if (dragged) {
                        dragged = false

                        return true
                    }
                }
            }
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            swipeFrom.set(event.x, event.y)
        }

        if (event.actionMasked != MotionEvent.ACTION_UP) {
            return true
        }

        /**
         * Протяжка вверх разворачивает кадр, вниз — возвращает в окно.
         *
         * Порог — восьмая доля высоты кадра и заведомо больше сдвига
         * вбок: дрожь пальца в такое не укладывается, а намеренное
         * движение укладывается с запасом. Увеличенный кадр этого жеста
         * не знает: там та же протяжка возит картинку.
         */
        val shiftY = event.y - swipeFrom.y
        val shiftX = event.x - swipeFrom.x

        val enough = height / 8f

        if (zoomScale <= 1f &&
            kotlin.math.abs(shiftY) > enough &&
            kotlin.math.abs(shiftY) > kotlin.math.abs(shiftX)
        ) {
            if (shiftY < 0 && !fullscreen) {
                onFullscreen?.invoke()

                return true
            }

            if (shiftY > 0 && fullscreen) {
                onFullscreen?.invoke()

                return true
            }
        }

        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
        }

        return true
    }

    // --- Раскладка --------------------------------------------------------

    /** Свои дети кончились — всё, что дальше, пришло снаружи. */
    fun sealOwnChildren() {
        ownChildren = childCount
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)

        /**
         * Кадр 16:9, если высота не задана снаружи.
         *
         * В шаблонах оригинала высота задана числом (196 у карточки),
         * что при ширине телефона и есть 16:9. Переносим не число,
         * а пропорцию: тогда кадр одинаково выглядит и на 360 точках,
         * и на планшете.
         *
         * В развёрнутом виде высоту задаёт тот, кто нас положил, —
         * там кадр во весь экран.
         */
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else if (heightCap > 0) {
            /**
             * Больше половины экрана кадр не занимает.
             *
             * Пропорция 16:9 от ширины хороша стоя, а лёжа она съедала
             * почти весь экран: на планшете в альбомном положении
             * от страницы под кадром не оставалось ничего. Ограничение
             * из `viewWillLayoutSubviews` оригинала — там ровно
             * `MIN(pageWidth * 9 / 16, height / 2)`.
             */
            minOf(width * 9 / 16, heightCap)
        } else {
            width * 9 / 16
        }

        setMeasuredDimension(width, height)

        measureExactly(
            surface, minOf(width, height * 16 / 9), minOf(height, width * 9 / 16)
        )

        measureExactly(scrim, width, dp(80f))

        val side = dp(40f)
        val big = dp(80f)

        measureExactly(playButton, big, big)

        for (view in listOf(
            rewindButton, forwardButton, collapseButton,
            settingsButton, fullscreenButton
        )) {
            measureExactly(view, side, side)
        }

        measureExactly(rewindMark, side, side)
        measureExactly(forwardMark, side, side)

        measureGuests(width, height)

        timeLabel.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(dp(30f), MeasureSpec.EXACTLY)
        )

        measureExactly(progress, width - dp(32f), dp(SCRUB_TOUCH))

        val textWidth = width - dp(16f) - dp(64f)

        measureExactly(titleLabel, textWidth, dp(22f))
        measureExactly(authorLabel, textWidth, dp(18f))

        measureExactly(ring, dp(36f), dp(36f))

        stats.measure(
            MeasureSpec.makeMeasureSpec(width - dp(16f), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
        )
    }

    /**
     * Виды, добавленные снаружи, — например строка субтитров.
     *
     * Кадр укладывает своих детей поимённо, а не подряд, и о чужих
     * не знает вовсе: добавленный снаружи вид не меряется и не кладётся,
     * остаётся нулевого размера и не виден никогда. Ровно это и случилось
     * с субтитрами: дорожка грузилась, реплики находились, а на экране
     * не было ничего.
     *
     * Своих детей кадр заводит при создании; всё, что появилось после,
     * считаем чужим и укладываем по полям — так же, как это сделал бы
     * `FrameLayout`, чьи `LayoutParams` им и назначены.
     */
    private var ownChildren = 0

    private fun guestChildren(): List<View> {
        if (childCount <= ownChildren) {
            return emptyList()
        }

        return (ownChildren until childCount).map { getChildAt(it) }
    }

    private fun measureGuests(width: Int, height: Int) {
        for (view in guestChildren()) {
            if (view.visibility == GONE) {
                continue
            }

            val params = view.layoutParams as? MarginLayoutParams

            val sides = (params?.leftMargin ?: 0) + (params?.rightMargin ?: 0)

            view.measure(
                MeasureSpec.makeMeasureSpec(maxOf(0, width - sides), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
            )
        }
    }

    private fun layoutGuests(width: Int) {
        for (view in guestChildren()) {
            if (view.visibility == GONE) {
                continue
            }

            val params = view.layoutParams as? MarginLayoutParams

            val left = params?.leftMargin ?: 0
            val top = params?.topMargin ?: 0

            // Шире отведённого не растягиваем: строка стоит по центру.
            val span = minOf(view.measuredWidth, maxOf(0, width - left))

            view.layout(left, top, left + span, top + view.measuredHeight)
        }
    }

    private fun measureExactly(view: View, width: Int, height: Int) {
        view.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        /**
         * Картинка вписывается в кадр, а не растягивается по нему.
         *
         * В обычном виде кадр и сам 16:9, и разницы нет. Лёжа экран
         * шире, и картинка во всю ширину выходила бы приплюснутой.
         * Вписываем по меньшей стороне и ставим по центру; чёрные поля
         * по краям тут не изъян, а единственный честный способ.
         *
         * Пропорция берётся у самой дорожки, а не назначается: стоячий
         * ролик 9:16 в место под 16:9 вписывался бы узкой полосой
         * посередине.
         *
         * При [fillsScreen] правило обратное: кадр покрывает место
         * целиком по **большей** стороне, а лишнее уходит за края.
         * Полос тогда нет, но и края обрезаны — это выбор человека,
         * а не наша догадка.
         */
        val ratio = PlayerEngine.videoRatio.takeIf { it > 0 } ?: (16f / 9f)

        val frameHeight: Int
        val frameWidth: Int

        if (fillsScreen) {
            frameHeight = maxOf(height, (width / ratio).toInt())
            frameWidth = maxOf(width, (height * ratio).toInt())
        } else {
            frameHeight = minOf(height, (width / ratio).toInt())
            frameWidth = minOf(width, (height * ratio).toInt())
        }

        surface.frame(
            (width - frameWidth) / 2, (height - frameHeight) / 2,
            frameWidth, frameHeight
        )

        blind.frame(0, 0, width, height)

        layoutGuests(width)

        // Нижняя полоса — высота 80.
        /**
         * Пульт стоит над системной полосой, а не под ней. Пока она
         * спрятана, отступ нулевой, и ряд лежит у самого края.
         */
        val bottom = height - insetBottom
        val right = width - insetRight

        val barTop = bottom - dp(80f)

        scrim.frame(0, barTop, right, bottom - barTop)

        val side = dp(40f)
        val big = dp(80f)
        val edge = dp(8f)

        // Пауза 80×80 посередине.
        playButton.frame((width - big) / 2, (height - big) / 2, big, big)

        ring.frame((width - dp(36f)) / 2, (height - dp(36f)) / 2, dp(36f), dp(36f))

        // Статистика — в левом верхнем углу, под рядом кнопок, чтобы их не закрывать.
        stats.frame(dp(8f), dp(8f) + dp(40f) + dp(4f), stats.measuredWidth, stats.measuredHeight)

        /**
         * Перемотка — по бокам от паузы, на расстоянии в её ширину.
         *
         * В оригинале они стоят там же и появились позже самого пульта:
         * в UWP-версии их нет вовсе, а на телефоне без них неудобно.
         */
        val gap = big
        val armY = (height - side) / 2

        val rewindLeft = (width - big) / 2 - gap
        val forwardLeft = (width + big) / 2 + gap - side

        rewindButton.frame(rewindLeft, armY, side, side)
        forwardButton.frame(forwardLeft, armY, side, side)

        // Число — понизу кнопки, под значком, полоской высотой 10.
        val markTop = armY + side - dp(13f)
        val markHeight = dp(10f)

        rewindMark.frame(rewindLeft, markTop, side, markHeight)
        forwardMark.frame(forwardLeft, markTop, side, markHeight)

        // Кнопки по краям — отступ 8.
        collapseButton.frame(edge, edge, side, side)

        settingsButton.frame(right - edge - side, edge, side, side)

        // Верхний ряд нижней полосы: высота 40, отступ сверху 8.
        val rowTop = barTop + edge
        val timeWidth = timeLabel.measuredWidth

        val timeHeight = timeLabel.measuredHeight

        timeLabel.frame(dp(16f), rowTop + (side - timeHeight) / 2, timeWidth, timeHeight)

        // Разворот — 40×40, отступ справа 16.
        fullscreenButton.frame(right - dp(16f) - side, rowTop, side, side)

        /**
         * Полоса перемотки рисуется тонкой, а нажимается широкой.
         *
         * Сама черта — четыре точки, бегунок шестнадцать, и по замыслу
         * ряд занимает двадцать точек снизу. Попасть в такую пальцем
         * трудно: на планшете с плотностью единица это два с половиной
         * миллиметра. Поэтому вид делается выше, а черта в нём остаётся
         * там же — она рисуется по середине высоты. Низ вида упирается
         * в тот же край, что и раньше, так что наружу ничего не вылезает.
         */
        val progressTop = bottom - edge - dp(SCRUB_TOUCH - 8f)

        progress.frame(dp(16f), progressTop, right - dp(32f), dp(SCRUB_TOUCH))

        // Название — только в развёрнутом виде, отступы 16,10,64.
        if (fullscreen) {
            val textLeft = dp(16f)
            val textTop = dp(10f)

            val textWidth = width - dp(64f) - textLeft

            titleLabel.frame(textLeft, textTop, textWidth, titleLabel.measuredHeight)

            authorLabel.frame(
                textLeft, titleLabel.bottom, textWidth, authorLabel.measuredHeight
            )
        }
    }

    /**
     * Кнопка пульта: круг #66000000 при непрозрачности 0.8 и значок поверх.
     *
     * Значок всегда из тёмного набора, какая бы тема ни стояла, — в XAML
     * у пульта путь зашит как `Assets/Dark/…`, а не выбирается темой.
     * Причина простая: значки лежат поверх кадра, а кадр тёмный всегда.
     */
    private class StageButton(
        context: Context,
        name: String,
        private val iconSide: Float
    ) : TappableView(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val image = ImageView(context)

        var icon: String = name
            set(value) {
                field = value

                image.setImageBitmap(Icons.darkIcon(value))
            }

        init {
            setWillNotDraw(false)

            paint.color = 0x66000000
            alpha = 0.8f

            image.setImageBitmap(Icons.darkIcon(name))
            image.scaleType = ImageView.ScaleType.FIT_CENTER

            addView(
                image,
                LayoutParams(
                    Metrics.dp(iconSide), Metrics.dp(iconSide),
                    android.view.Gravity.CENTER
                )
            )
        }

        override fun onDraw(canvas: Canvas) {
            val radius = minOf(width, height) / 2f

            canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        }
    }
}

/**
 * Полоса воспроизведения.
 *
 * Нарисована обычными видами, а не системным `SeekBar`, и это перенесённое
 * решение: в оригинале у неё свой шаблон — дорожка, пройденная часть,
 * круглый бегунок, — и штатный ползунок пришлось бы перекрашивать
 * картинками, а рисунок у него всё равно другой.
 *
 *     дорожка   высота 4, скругление 2, #666666
 *     пройдено  #f03, кружок 16 того же цвета
 */
class ProgressBarView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    private var position = 0.0
    private var buffered = 0.0
    private var duration = 0.0

    /** Вставки SponsorBlock: начало и конец в секундах. */
    var marks: List<Pair<Double, Double>> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Начала глав в секундах — рисуются просветами в дорожке. */
    var chapters: List<Double> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var onSeek: ((Double) -> Unit)? = null
    var onScrubbing: ((Double) -> Unit)? = null

    /** Касание отменили — вести полосу больше некому. */
    var onScrubCancel: (() -> Unit)? = null

    private var dragging = false

    init {
        isClickable = true
    }

    fun buffered(): Double = buffered

    fun set(position: Double, buffered: Double, duration: Double) {
        if (dragging) {
            return
        }

        this.position = position
        this.buffered = buffered
        this.duration = duration

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0) {
            return
        }

        val trackHeight = dpf(4f)
        val radius = dpf(2f)
        val knob = dpf(16f)

        val middle = height / 2f
        val left = knob / 2
        val right = width - knob / 2
        val span = right - left

        // Дорожка.
        paint.color = 0xFF666666.toInt()

        box.set(left, middle - trackHeight / 2, right, middle + trackHeight / 2)

        canvas.drawRoundRect(box, radius, radius, paint)

        // Набранное — той же дорожкой, но светлее.
        if (duration > 0 && buffered > 0) {
            paint.color = 0xFF999999.toInt()

            val end = left + span * (buffered / duration).coerceIn(0.0, 1.0).toFloat()

            box.set(left, middle - trackHeight / 2, end, middle + trackHeight / 2)

            canvas.drawRoundRect(box, radius, radius, paint)
        }

        val share = if (duration > 0) (position / duration).coerceIn(0.0, 1.0) else 0.0
        val at = left + span * share.toFloat()

        // Пройденное.
        paint.color = 0xFFFF0033.toInt()

        box.set(left, middle - trackHeight / 2, at, middle + trackHeight / 2)

        canvas.drawRoundRect(box, radius, radius, paint)

        /**
         * Метки глав и вставок — поверх дорожки.
         *
         * Тонкими белыми чёрточками, как у самого YouTube: главы делят
         * полосу, вставки SponsorBlock помечают то, что будет пропущено.
         */
        if (duration > 0) {
            paint.color = 0xCCFFFFFF.toInt()

            for (mark in marks) {
                val from = left + span * (mark.first / duration).coerceIn(0.0, 1.0).toFloat()
                val to = left + span * (mark.second / duration).coerceIn(0.0, 1.0).toFloat()

                box.set(
                    from, middle - trackHeight / 2,
                    maxOf(to, from + dpf(1.5f)), middle + trackHeight / 2
                )

                canvas.drawRect(box, paint)
            }
        }

        /**
         * Деления глав — просветы цвета подложки в самой дорожке.
         *
         * Так их рисует и YouTube: не черта поверх, а разрыв, отчего
         * полоса читается как несколько отрезков подряд.
         */
        if (duration > 0 && chapters.isNotEmpty()) {
            paint.color = 0xFF0F0F0F.toInt()

            for (start in chapters) {
                if (start <= 0) {
                    continue
                }

                val at = left + span * (start / duration).coerceIn(0.0, 1.0).toFloat()

                box.set(
                    at - dpf(1f), middle - trackHeight / 2,
                    at + dpf(1f), middle + trackHeight / 2
                )

                canvas.drawRect(box, paint)
            }
        }

        // Бегунок.
        paint.color = 0xFFFF0033.toInt()

        canvas.drawCircle(at, middle, knob / 2, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val knob = dpf(16f)
        val left = knob / 2
        val span = width - knob

        if (span <= 0 || duration <= 0) {
            return false
        }

        val share = ((event.x - left) / span).coerceIn(0f, 1f)
        val target = duration * share

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true

                parent?.requestDisallowInterceptTouchEvent(true)

                position = target

                invalidate()
                onScrubbing?.invoke(target)
            }

            MotionEvent.ACTION_MOVE -> {
                position = target

                invalidate()
                onScrubbing?.invoke(target)
            }

            MotionEvent.ACTION_UP -> {
                dragging = false

                parent?.requestDisallowInterceptTouchEvent(false)

                onSeek?.invoke(target)
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false

                parent?.requestDisallowInterceptTouchEvent(false)

                /**
                 * Об отмене надо сказать вслух.
                 *
                 * Прежде здесь просто снимался свой признак, а тот, что
                 * ведёт полосу снаружи, оставался поднятым навсегда: ход
                 * воспроизведения переставал доходить и до полосы, и до
                 * часов. Длительность у полосы застывала прежней, и
                 * следующий прыжок отмерялся по ней — так и выходило
                 * «40:40 из 25:10», место за концом ролика.
                 *
                 * Касание отменяет система, когда палец уводят в прокрутку,
                 * так что случай это будничный, а не редкий.
                 */
                onScrubCancel?.invoke()
            }
        }

        return true
    }
}

/**
 * Высота полосы перемотки — той, что ловит палец, а не той, что видна.
 *
 * Видно четыре точки черты и бегунок шестнадцать; ловит она тридцать
 * шесть. Черта рисуется по середине высоты, поэтому на вид ничего
 * не меняется.
 */
private const val SCRUB_TOUCH = 36f

/** Дольше этого срока прыжок не ждём, мс. */
private const val WAIT_MS = 2500L

