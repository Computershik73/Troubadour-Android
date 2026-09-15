package ru.computershik.troubadour.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.AudioLanguage
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.locF
import ru.computershik.troubadour.model.VideoItem
import ru.computershik.troubadour.net.Api
import ru.computershik.troubadour.net.Auth
import ru.computershik.troubadour.net.Download
import ru.computershik.troubadour.net.Downloads
import ru.computershik.troubadour.net.Notifications
import ru.computershik.troubadour.net.VideoDetails
import ru.computershik.troubadour.net.comments
import ru.computershik.troubadour.net.rate
import ru.computershik.troubadour.net.setNotifications
import ru.computershik.troubadour.net.setSubscribed
import ru.computershik.troubadour.net.videoDetails
import ru.computershik.troubadour.player.MiniPlayer
import ru.computershik.troubadour.player.NowPlaying
import ru.computershik.troubadour.player.PlayerEngine
import ru.computershik.troubadour.player.SponsorBlock
import ru.computershik.troubadour.player.SponsorSegment
import ru.computershik.troubadour.player.Storyboard
import ru.computershik.troubadour.player.SubtitleCue
import ru.computershik.troubadour.player.SubtitleTrack
import ru.computershik.troubadour.player.Subtitles
import ru.computershik.troubadour.ui.Metrics.dp

/**
 * Страница ролика — порт `Video.xaml` вместе с `CustomVideoPlayer`.
 *
 * Сверху кадр 16:9 со своим пультом ([PlayerStage]), под ним название,
 * строка канала с кнопкой подписки, ряд кнопок (оценка, «Поделиться»)
 * и комментарии.
 *
 * Числа из XAML, через iOS-порт, без пересчёта:
 *
 *     название       отступы 16×12, 18 Bold, перенос по словам
 *     строка канала  отступы 16,0,16,16; кружок 40, текст с отступом 12,
 *                    имя 15 Medium, подписчики 12 secondary
 *     «Подписаться»  скругление 18, PrimaryAction, поля 14×7, 13 SemiBold
 *     ряд действий   отступы 16,2,16,16
 *       оценка       одна подложка AppSurface, скругление 18; поля 16/8
 *                    и 8/16, значки 20, счётчик 14 с отступом 6, между
 *                    кнопками черта 0.75×18 (#F1F1F1, 47%)
 *       «Поделиться» поля 16×8, значок player/send.png, текст 14
 *     комментарий    отступы 16,0,16,16; поля 12, AppSurface, скругление 12
 */
class PlayerScreen(
    context: Context,
    private var videoId: String,
    private var titleText: String,
    private val playlistId: String?,
    /**
     * С какой секунды начать — по слову сервера с той карточки, откуда
     * пришли. Ноль значит «сначала»; он же остаётся после перехода
     * к следующему ролику в подборке.
     */
    private var startAt: Double = 0.0
) : Screen(context) {

    private lateinit var stage: PlayerStage
    private lateinit var page: ScrollView
    private lateinit var column: LinearLayout

    private lateinit var titleLabel: TextView
    private lateinit var statsLabel: TextView

    private lateinit var avatar: RoundedImage
    private lateinit var channelLabel: TextView
    private lateinit var subscribersLabel: TextView

    private lateinit var subscribeButton: SubscribeButton

    private lateinit var likeLabel: TextView
    private lateinit var likeIcon: ImageView
    private lateinit var dislikeIcon: ImageView

    private lateinit var downloadIcon: ImageView
    private lateinit var downloadLabel: TextView

    private lateinit var subtitleLabel: TextView

    private lateinit var commentsCard: CommentsCard

    /** Первая страница комментариев — придержана для списка. */
    private var commentsPage: ru.computershik.troubadour.net.CommentsPage? = null
    private lateinit var queueCard: QueueCard
    private lateinit var chaptersCard: ChaptersCard
    /** Левая колонка: кадр и страница под ним. */
    private lateinit var holder: LinearLayout

    private lateinit var relatedTitle: TextView
    private lateinit var related: LinearLayout
    private lateinit var busy: LoadingRing
    private lateinit var status: StatusView

    private var details: VideoDetails? = null

    private var liked = false
    private var disliked = false
    private var subscribed = false
    private var notifications = Notifications.UNKNOWN

    private var sponsorSegments: List<SponsorSegment> = emptyList()
    private var subtitleTracks: List<SubtitleTrack> = emptyList()
    private var subtitleCues: List<SubtitleCue> = emptyList()
    private var subtitleTrack: SubtitleTrack? = null
    private var storyboard: Storyboard? = null

    /** Описание ролика — показывается листом по нажатию на название. */
    private var descriptionText: String = ""

    /** Главы ролика — из временных меток в описании. */
    private var chapters: List<ru.computershik.troubadour.player.Chapter> = emptyList()

    /** До какой вставки уже прыгали — чтобы не прыгать по кругу. */
    private var lastSkippedTo = -1.0

    /**
     * Сколько ждать после нажатия кнопки перемотки, прежде чем прыгать.
     *
     * Треть секунды: за неё успевает прийти второе нажатие, и при этом
     * одиночное не выглядит запоздалым — полоса и часы отвечают сразу,
     * а ждёт только сам прыжок.
     */
    private val SKIP_PAUSE = 350L

    private var fullscreen = false

    private lateinit var side: ScrollView
    private lateinit var sideColumn: LinearLayout
    private lateinit var columnDivider: HairlineView

    /** Нынешняя раскладка: две колонки или одна. */
    private var split = false

    /** Похожие держим у себя: при смене раскладки их перекладывают заново. */
    private var relatedItems: List<VideoItem> = emptyList()

    /** Спрашивали ли уже про озвучку у этого ролика. */
    private var askedAudioTrack = false

    /**
     * Очередь подборки — та, что показана карточкой.
     *
     * Держим её отдельно от карточки: по ней ищется следующий ролик,
     * когда нынешний доиграл, а спрашивать об этом вид, который её
     * рисует, значило бы заставить его отвечать не о своём деле.
     */
    private var queueItems: List<VideoItem> = emptyList()

    /**
     * С какого ролика уже перешли по концу.
     *
     * `STATE_ENDED` приходит не один раз — плеер рассылает его и сам,
     * и вслед за закрытием записи просмотра. Без этой отметки второй
     * приход уводил бы на ролик через один: первый переход уже сменил
     * [videoId], и следующим в очереди оказывался бы уже другой.
     */
    private var advancedFrom: String? = null

    // --- Ожидание объявленной трансляции ---------------------------------

    /**
     * Ожидание держится на одном повторяющемся ходе в пять секунд: он
     * переписывает надпись с оставшимся временем и, когда срок подошёл,
     * заново просит поток. Ни отдельной нити, ни ожидания в сети здесь
     * нет — приложение всё это время живёт обычной жизнью, и список,
     * и описание ролика остаются на месте.
     */
    private val clock = android.os.Handler(android.os.Looper.getMainLooper())

    private var broadcastAt = 0.0
    private var broadcastSaid: String? = null
    private var broadcastTriedAt = 0.0
    private var broadcastWaiting = false

    private val broadcastTick = object : Runnable {

        override fun run() {
            if (!broadcastWaiting) {
                return
            }

            showBroadcastWait()

            val now = System.currentTimeMillis() / 1000.0

            /**
             * Пробовать начинаем не в назначенную секунду, а через
             * полминуты после неё: у YouTube трансляция поднимается
             * не мгновенно, и ранние попытки лишь тратят запросы.
             * Дальше — раз в полминуты, пока не выйдет.
             */
            val ready = broadcastAt <= 0 || now >= broadcastAt + 30

            if (ready && now - broadcastTriedAt >= 30) {
                broadcastTriedAt = now

                Log.d { "[YouTube/Плеер] Пробуем поднять объявленную трансляцию" }

                PlayerEngine.open(videoId, playlistId)
            }

            clock.postDelayed(this, 5000)
        }
    }

    private fun awaitBroadcast(scheduled: Double, said: String?) {
        broadcastAt = scheduled
        broadcastSaid = said
        broadcastTriedAt = System.currentTimeMillis() / 1000.0
        broadcastWaiting = true

        stage.setBusy(false)

        showBroadcastWait()

        clock.removeCallbacks(broadcastTick)
        clock.postDelayed(broadcastTick, 5000)

        Log.d {
            "[YouTube/Плеер] Трансляция " + (
                if (scheduled > 0) {
                    "назначена на " + java.util.Date((scheduled * 1000).toLong())
                } else {
                    "ещё не началась, час начала в ответе не назван"
                }
                ) + " — ждём"
        }
    }

    private fun stopBroadcastWait() {
        broadcastWaiting = false

        clock.removeCallbacks(broadcastTick)
    }

    /** Надпись об ожидании — тем подробнее, чем ближе срок. */
    private fun showBroadcastWait() {
        /** Часа не знаем — говорим словами сервера, а не молчим. */
        if (broadcastAt <= 0) {
            status.showMessage(
                broadcastSaid?.takeIf { it.isNotEmpty() }
                    ?: loc("Трансляция ещё не началась — ждём…")
            )

            return
        }

        val left = broadcastAt - System.currentTimeMillis() / 1000.0

        if (left <= 0) {
            status.showMessage(loc("Ждём начала трансляции…"))

            return
        }

        if (left < 60) {
            status.showMessage(loc("Трансляция вот-вот начнётся"))

            return
        }

        if (left < 3600) {
            status.showMessage(
                ru.computershik.troubadour.locF(
                    "Трансляция начнётся через %ld мин", (left / 60).toLong()
                )
            )

            return
        }

        /**
         * Дальше часа — со днём, иначе одно время вводит в заблуждение.
         *
         * «Начнётся в 17:30» у трансляции, до которой двенадцать часов,
         * читается как «сегодня вечером», а она может быть и завтра.
         */
        val when0 = java.util.Date((broadcastAt * 1000).toLong())

        val today = java.util.Calendar.getInstance()
        val day = java.util.Calendar.getInstance()

        day.time = when0

        val sameDay =
            today.get(java.util.Calendar.YEAR) == day.get(java.util.Calendar.YEAR) &&
                today.get(java.util.Calendar.DAY_OF_YEAR) ==
                day.get(java.util.Calendar.DAY_OF_YEAR)

        val shape = if (sameDay) {
            android.text.format.DateFormat.getTimeFormat(context)
        } else {
            android.text.format.DateFormat.getDateFormat(context)
        }

        status.showMessage(
            ru.computershik.troubadour.locF(
                "Трансляция начнётся %@",
                if (sameDay) {
                    shape.format(when0)
                } else {
                    shape.format(when0) + " " +
                        android.text.format.DateFormat.getTimeFormat(context)
                            .format(when0)
                }
            )
        )
    }

    companion object {
        /**
         * Доля левой колонки — то же число, что в оригинале и в Трубаче.
         *
         * На 1024 точках это 635 под кадр с описанием и 389 под список
         * справа: карточке похожего хватает, а кадру 635 точек дают 357
         * высоты — ровно столько, чтобы под ним осталось место названию
         * и кнопкам.
         */
        private const val SPLIT_LEFT_SHARE = 0.62f

        /**
         * Потолок правой колонки.
         *
         * Доля хороша на четыре к трём: у iPad 1024 точки дают справа
         * 389, и карточка похожего там ровно к месту. У планшета
         * шестнадцать к десяти экран шире — 1280 точек, — и та же доля
         * отдаёт списку 486. Список от этого не становится полезнее:
         * карточки просто раздуваются, а кадру ширины не хватает.
         *
         * Поэтому доля — не правило, а потолок: правая колонка не шире
         * четырёхсот точек, всё лишнее достаётся кадру. На iPad ничего
         * не меняется (389 меньше потолка), на широком планшете кадр
         * получает ещё восемьдесят точек.
         */
        private const val SPLIT_SIDE_MAX = 400f

        /**
         * Сколько высоты отдаём кадру в двух колонках.
         *
         * Обычный потолок — половина экрана: пропорция 16:9 от ширины
         * хороша стоя, а лёжа съедала бы весь экран. В двух колонках
         * кадр и так занимает лишь часть ширины, и половина высоты для
         * него мала: на планшете шестнадцать к десяти левая колонка
         * шириной 879 просит 494 высоты, а потолок даёт 400. Кадр тогда
         * встаёт посередине колонки с чёрными полями по бокам — и список
         * справа кажется больше кадра, хотя он и уже.
         *
         * У iPad этого не видно: там колонка 635 просит 357, а половина
         * от 768 — 384, и потолок не мешает. Мы поднимаем его настолько,
         * чтобы не мешал и здесь, оставляя под названием и кнопками
         * без малого треть экрана.
         */
        private const val SPLIT_STAGE_SHARE = 0.7f
    }

    /**
     * Раздельная раскладка — только планшет, только лёжа и только
     * не в развёрнутом кадре.
     *
     * «Планшет» здесь — наименьшая сторона от 600 точек: у Android нет
     * признака рода устройства, каким в iOS служит `userInterfaceIdiom`,
     * и 600 — та самая граница, по которой система сама раздаёт
     * ресурсы планшетам.
     */
    /**
     * Потолок высоты кадра — половина экрана.
     *
     * Считается **до** первого обмера, а не во время него, и это
     * существенно. Поверхность узнаёт свой размер по укладке; если
     * потолок появляется посреди прохода, кадр успевает получить
     * поверхность прежней, большей высоты, а потом ужимается — и картинка
     * рисуется в старый размер, показывая лишь свою верхнюю часть.
     */
    private fun applyHeightCap() {
        val metrics = context.resources.displayMetrics

        /**
         * Раскладку спрашиваем у размеров экрана, а не у поля `split`:
         * потолок ставится **до** обмера, а поле меняется во время него,
         * и на повороте оно ещё хранит прежнее значение.
         */
        stage.heightCap = if (wantsSplit(metrics.widthPixels, metrics.heightPixels)) {
            (metrics.heightPixels * SPLIT_STAGE_SHARE).toInt()
        } else {
            metrics.heightPixels / 2
        }
    }

    private fun wantsSplit(width: Int, height: Int): Boolean {
        if (fullscreen) {
            return false
        }

        if (context.resources.configuration.smallestScreenWidthDp < 600) {
            return false
        }

        return width > height
    }

    /**
     * Переезд очереди, глав и похожих между страницей и правой колонкой.
     *
     * Зовётся только когда раскладка **сменилась**, а не каждый проход:
     * перекладывать виды туда-сюда на каждом обмере незачем.
     */
    private fun applySplit(want: Boolean) {
        /**
         * Переезжать нечему, пока страница не собрана.
         *
         * Обмер вправе случиться раньше `buildPage`, и карточек тогда
         * ещё нет вовсе — обращение к ним уронило бы приложение
         * на самом открытии страницы.
         */
        if (!::queueCard.isInitialized || !::related.isInitialized) {
            return
        }

        split = want

        val host = if (want) sideColumn else column

        for (view in listOf<View>(queueCard, chaptersCard, relatedTitle, related)) {
            (view.parent as? ViewGroup)?.removeView(view)

            /**
             * Поля задаются заново — и это не мелочь.
             *
             * При переносе вида в другого родителя прежние параметры
             * укладки отбрасываются, а новые по умолчанию идут без
             * полей. Оттого в двух колонках карточки похожих упирались
             * в самый край экрана: слева поле было, справа — ни точки.
             *
             * Шестнадцать — то же поле страницы, что и в iOS-версии
             * (`YTPageMargin`), и то же, что у заголовка над списком.
             */
            /**
             * Карточки очереди и глав отступают сами: плашку они рисуют
             * с полем 16 внутри своей же ширины. Дай им поле снаружи —
             * получится тридцать два, и в правой колонке они окажутся
             * заметно уже похожих. Поэтому поле снаружи только тем,
             * у кого своего нет.
             */
            val ownMargin = view === queueCard || view === chaptersCard

            val side = if (ownMargin) 0 else dp(16f)

            host.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = side
                    rightMargin = side
                    bottomMargin = dp(16f)
                }
            )
        }

        side.visibility = if (want) View.VISIBLE else View.GONE
        columnDivider.visibility = side.visibility

        Log.d {
            "[YouTube/Плеер] Раскладка: " + if (want) "две колонки" else "одна"
        }

        /**
         * Похожие перекладываем заново: колонок в новой раскладке
         * другое число, а оно считается по ширине колонки.
         */
        if (relatedItems.isNotEmpty()) {
            related.post { applyRelated(relatedItems) }
        }
    }

    /** Две колонки с чертой между ними; на телефоне — одна во всю ширину. */
    private inner class SplitBox(context: Context) : ViewGroup(context) {

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)

            setMeasuredDimension(width, height)

            val want = wantsSplit(width, height)

            if (want != split) {
                applySplit(want)
            }

            val pageWidth = if (want) pageWidthFor(width) else width

            holder.measure(
                MeasureSpec.makeMeasureSpec(pageWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )

            if (!want) {
                return
            }

            val line = HairlineView.thickness()

            columnDivider.measure(
                MeasureSpec.makeMeasureSpec(line, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )

            side.measure(
                MeasureSpec.makeMeasureSpec(
                    maxOf(0, width - pageWidth - line), MeasureSpec.EXACTLY
                ),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }

        /**
         * Сколько ширины под страницу с кадром.
         *
         * Считается от правой колонки, а не от левой: её ширина —
         * то, что нужно ограничить, а кадру достаётся всё остальное.
         */
        private fun pageWidthFor(width: Int): Int {
            val line = HairlineView.thickness()

            val byShare = width - (width * SPLIT_LEFT_SHARE).toInt() - line

            val side = minOf(byShare, dp(SPLIT_SIDE_MAX))

            return maxOf(0, width - side - line)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            val height = b - t

            val pageWidth = if (split) pageWidthFor(width) else width

            holder.frame(0, 0, pageWidth, height)

            if (!split) {
                return
            }

            val line = HairlineView.thickness()

            columnDivider.frame(pageWidth, 0, line, height)

            side.frame(pageWidth + line, 0, maxOf(0, width - pageWidth - line), height)
        }
    }

    override fun build(root: FrameLayout) {
        holder = LinearLayout(context)

        holder.orientation = LinearLayout.VERTICAL

        stage = PlayerStage(context)

        stage.onPlayPause = { togglePlay() }
        stage.onSeek = { PlayerEngine.seekTo(it) }
        stage.onSkip = { skipBy(it) }
        stage.onFullscreen = { toggleFullscreen() }

        stage.onNotice = { text -> Toast.show(context, text) }
        stage.onCollapse = { collapse() }
        stage.onSettings = { openMenu() }

        /**
         * Пульт ушёл — уходит и системная полоса. Пока он на экране,
         * полоса остаётся: до Android 4.4 её возвращает каждое касание,
         * и, спрятав её сразу, мы отняли бы у человека следующее нажатие.
         */
        stage.onControlsHidden = {
            if (fullscreen) {
                (context as? MainActivity)?.applyImmersive(true)
            }
        }

        holder.addView(
            stage,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        page = ScrollView(context)

        column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL

        page.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        holder.addView(
            page,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        /**
         * Планшет лёжа делится на две колонки — порт `applySplit:`.
         *
         * Слева кадр, название, канал, действия и комментарии; справа
         * очередь, главы и похожие. На телефоне и стоя колонка одна,
         * и правая прячется целиком.
         */
        sideColumn = LinearLayout(context)
        sideColumn.orientation = LinearLayout.VERTICAL

        side = ScrollView(context)

        side.addView(
            sideColumn,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        side.visibility = View.GONE

        columnDivider = HairlineView(context)
        columnDivider.visibility = View.GONE

        val box = SplitBox(context)

        box.addView(holder)
        box.addView(columnDivider)
        box.addView(side)

        root.addView(
            box,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        applyHeightCap()

        buildPage()

        /**
         * Субтитры лежат **поверх кадра**, а не в странице.
         *
         * И двигаются пальцем: место запоминается долей от высоты кадра,
         * чтобы при повороте строка осталась там же по смыслу, а не уехала
         * за край.
         */
        subtitleLabel = label(context, Fonts.medium, 15f, Color.WHITE, 0)

        subtitleLabel.setBackgroundColor(0x99000000.toInt())
        subtitleLabel.setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        subtitleLabel.gravity = Gravity.CENTER
        subtitleLabel.visibility = View.GONE

        stage.addView(
            subtitleLabel,
            FrameLayoutParamsForSubtitles()
        )

        status = StatusView(context)

        root.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        busy = LoadingRing(context)

        subscribeToEvents()

        load()
    }

    private fun FrameLayoutParamsForSubtitles(): FrameLayout.LayoutParams {
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP
        )

        return params
    }

    // --- Страница ---------------------------------------------------------

    private fun buildPage() {
        // Название: отступы 16×12, 18 Bold, перенос по словам.
        titleLabel = label(context, Fonts.bold, 18f, Theme.primaryText, 0)

        titleLabel.text = titleText

        /**
         * Нажатие по названию открывает описание.
         *
         * Отдельного места под описание на странице нет ни в оригинале,
         * ни здесь: оно показывается листом снизу (`openDescription`).
         */
        titleLabel.isClickable = true
        titleLabel.setOnClickListener { openDescription() }

        column.addView(
            titleLabel,
            marginParams(dp(16f), dp(12f), dp(16f), dp(4f))
        )

        // Просмотры и дата — той же колонкой.
        statsLabel = label(context, Fonts.regular, 13f, Theme.mutedText, 1)

        column.addView(
            statsLabel,
            marginParams(dp(16f), 0, dp(16f), dp(12f))
        )

        column.addView(buildChannelRow())
        column.addView(buildActionRow())

        /**
         * Комментарии — карточкой с первым из них, а не кнопкой.
         *
         * Кнопка была моей выдумкой: в оригинале под действиями стоит
         * карточка, где показан один комментарий, а весь список
         * открывается нажатием по ней.
         */
        commentsCard = CommentsCard(context)

        commentsCard.onOpen = { openComments() }

        column.addView(
            commentsCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16f) }
        )

        /**
         * Очередь — над главами: она про то, что будет дальше,
         * и человеку важнее.
         */
        queueCard = QueueCard(context)

        queueCard.onPick = { item ->
            openQueueItem(item)
        }

        column.addView(
            queueCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16f) }
        )

        /**
         * Карточка глав — сразу под действиями, как в оригинале.
         *
         * Свёрнута по умолчанию: у длинного ролика глав бывает три
         * десятка, и развёрнутый список отодвинул бы похожие за край.
         */
        chaptersCard = ChaptersCard(context)

        chaptersCard.onPick = { at -> PlayerEngine.seekTo(at) }

        column.addView(
            chaptersCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16f) }
        )

        relatedTitle = label(context, Fonts.bold, 14f, Theme.primaryText, 1)

        relatedTitle.text = loc("Похожие видео")

        column.addView(
            relatedTitle,
            marginParams(dp(16f), 0, dp(16f), dp(8f))
        )

        // Похожие — обычные карточки, отступ как у ленты.
        related = LinearLayout(context)
        related.orientation = LinearLayout.VERTICAL

        column.addView(
            related,
            marginParams(dp(16f), dp(8f), dp(16f), dp(16f))
        )
    }

    private fun marginParams(
        left: Int, top: Int, right: Int, bottom: Int
    ): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        params.setMargins(left, top, right, bottom)

        return params
    }

    /**
     * Строка канала: отступы 16,0,16,16; кружок 40, текст с отступом 12,
     * имя 15 Medium, подписчики 12 secondary, кнопка справа.
     */
    private fun buildChannelRow(): View {
        val row = LinearLayout(context)

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        avatar = RoundedImage(context)
        avatar.circular = true
        avatar.placeholderColor = Theme.avatarPlaceholder

        row.addView(avatar, LinearLayout.LayoutParams(dp(40f), dp(40f)))

        val names = LinearLayout(context)

        names.orientation = LinearLayout.VERTICAL

        channelLabel = label(context, Fonts.medium, 15f, Theme.primaryText, 1)
        subscribersLabel = label(context, Fonts.regular, 12f, Theme.secondaryText, 1)

        names.addView(channelLabel)
        names.addView(subscribersLabel)

        val namesParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        namesParams.leftMargin = dp(12f)

        row.addView(names, namesParams)

        /**
         * «Подписаться»: скругление 18, PrimaryAction, 13 SemiBold.
         *
         * У подписанного нажатие открывает оповещения, а не отписывает:
         * отписка стоит там же, последней строкой панели. Так в оригинале,
         * и так одно неверное касание не рвёт подписку.
         */
        subscribeButton = SubscribeButton(context)

        subscribeButton.onTap = {
            if (subscribed) openBellMenu() else toggleSubscription()
        }

        row.addView(
            subscribeButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // Нажатие по кружку и имени ведёт на канал.
        val open = View.OnClickListener {
            Nav.openChannel(details?.channelId, details?.channelTitle)
        }

        avatar.isClickable = true
        avatar.setOnClickListener(open)
        names.isClickable = true
        names.setOnClickListener(open)

        val params = marginParams(dp(16f), 0, dp(16f), dp(16f))

        row.layoutParams = params

        return row
    }

    /**
     * Ряд действий: отступы 16,2,16,16.
     *
     * Оценка — одна подложка `AppSurface` со скруглением 18, внутри две
     * кнопки, между ними черта 0.75×18 цветом `#F1F1F1` при 47%.
     */
    private fun buildActionRow(): View {
        val row = LinearLayout(context)

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        /**
         * Подложка оценки — фон самого ряда кнопок, а не отдельный вид
         * под ним.
         *
         * Прежде под кнопки клался `PillView` с `MATCH_PARENT` внутри
         * `FrameLayout`. Такой `FrameLayout` при `WRAP_CONTENT` меряет
         * себя по самому широкому ребёнку, а ребёнок с `MATCH_PARENT`
         * занимает всю доступную ширину: подложка растягивалась на строку
         * целиком и выдавливала за край «Поделиться» и «Скачать».
         * На устройстве от ряда действий оставалась одна оценка.
         *
         * Скруглённый фон такой беды не знает: он не ребёнок и на меру
         * не влияет.
         */
        val inner = LinearLayout(context)

        inner.orientation = LinearLayout.HORIZONTAL
        inner.gravity = Gravity.CENTER_VERTICAL

        // Лайк: поля 16/8, значок 20, счётчик 14 с отступом 6.
        val like = TappableView(context)

        like.setPadding(dp(16f), dp(8f), dp(8f), dp(8f))
        like.highlights = false

        val likeRow = LinearLayout(context)

        likeRow.orientation = LinearLayout.HORIZONTAL
        likeRow.gravity = Gravity.CENTER_VERTICAL

        likeIcon = ImageView(context)
        likeIcon.scaleType = ImageView.ScaleType.FIT_CENTER

        likeLabel = label(context, Fonts.regular, 14f, Theme.primaryText, 1)

        likeRow.addView(likeIcon, LinearLayout.LayoutParams(dp(20f), dp(20f)))

        val countParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        countParams.leftMargin = dp(6f)

        likeRow.addView(likeLabel, countParams)

        like.addView(likeRow)
        like.onTap = { rate("like") }

        inner.addView(like)

        // Черта 0.75×18, #F1F1F1 при 47%.
        val divider = View(context)

        divider.setBackgroundColor(0xF1F1F1.toInt() or (0x78 shl 24))

        inner.addView(
            divider,
            LinearLayout.LayoutParams(maxOf(1, dp(0.75f)), dp(18f))
        )

        // Дизлайк: поля 8/16.
        val dislike = TappableView(context)

        dislike.setPadding(dp(8f), dp(8f), dp(16f), dp(8f))
        dislike.highlights = false

        dislikeIcon = ImageView(context)
        dislikeIcon.scaleType = ImageView.ScaleType.FIT_CENTER

        dislike.addView(
            dislikeIcon,
            FrameLayout.LayoutParams(dp(20f), dp(20f), Gravity.CENTER)
        )

        dislike.onTap = { rate("dislike") }

        inner.addView(dislike)

        ratePillBackground.setColor(Theme.surface)
        ratePillBackground.cornerRadius = Metrics.dpf(18f)

        @Suppress("DEPRECATION")
        inner.setBackgroundDrawable(ratePillBackground)

        row.addView(
            inner,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // «Поделиться»: поля 16×8, значок и текст 14.
        val share = TappableView(context)

        share.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))

        val shareRow = LinearLayout(context)

        shareRow.orientation = LinearLayout.HORIZONTAL
        shareRow.gravity = Gravity.CENTER_VERTICAL

        val shareIcon = ImageView(context)

        shareIcon.setImageBitmap(Icons.icon("share"))
        shareIcon.scaleType = ImageView.ScaleType.FIT_CENTER

        shareRow.addView(shareIcon, LinearLayout.LayoutParams(dp(20f), dp(20f)))

        val shareLabel = label(context, Fonts.regular, 14f, Theme.primaryText, 1)

        shareLabel.text = loc("Поделиться")

        val shareParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        shareParams.leftMargin = dp(6f)

        shareRow.addView(shareLabel, shareParams)

        share.addView(shareRow)
        share.onTap = { shareVideo() }

        row.addView(
            share,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        /**
         * «Скачать» — значок, а при загрузке ещё и проценты.
         *
         * Слова у неё нет нарочно: ряд и без того еле помещается
         * на узком экране, а стрелка вниз понятна без подписи. Проценты
         * же появляются лишь тогда, когда есть что показывать.
         */
        val download = TappableView(context)

        download.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))

        val downloadRow = LinearLayout(context)

        downloadRow.orientation = LinearLayout.HORIZONTAL
        downloadRow.gravity = Gravity.CENTER_VERTICAL

        downloadIcon = ImageView(context)
        downloadIcon.scaleType = ImageView.ScaleType.FIT_CENTER

        downloadRow.addView(downloadIcon, LinearLayout.LayoutParams(dp(20f), dp(20f)))

        downloadLabel = label(context, Fonts.regular, 14f, Theme.primaryText, 1)

        val downloadParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        downloadParams.leftMargin = dp(6f)

        downloadRow.addView(downloadLabel, downloadParams)

        download.addView(downloadRow)
        download.onTap = { downloadTapped() }

        row.addView(
            download,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        row.layoutParams = marginParams(dp(16f), dp(2f), dp(16f), dp(16f))

        applyDownloadState()

        return row
    }

    /**
     * Значок и проценты у кнопки скачивания.
     *
     * Скачанное помечается цветом, как нажатый лайк. Готового значка
     * для этого в наборе нет — красим сам; галочка тут не нужна, цвет
     * и есть ответ, а она растянула бы кнопку.
     */
    /** Скруглённая подложка под оценкой — красится вместе с темой. */
    private val ratePillBackground = android.graphics.drawable.GradientDrawable()

    private fun applyDownloadState() {
        /**
         * Красим значок, если готово **хоть одно** качество: кнопка
         * одна на ролик, а качеств у него может быть несколько.
         */
        val done = Downloads.haveAny(videoId)

        /**
         * Проценты — от той ступени, что качается сейчас. Их может идти
         * только одна: очередь берёт записи по одной.
         */
        val item = Downloads.itemsFor(videoId).firstOrNull {
            it.state == Download.RUNNING || it.state == Download.QUEUED
        }

        downloadIcon.setImageBitmap(Icons.icon("pl_download"))

        downloadIcon.colorFilter = if (done) {
            android.graphics.PorterDuffColorFilter(
                Theme.ACCENT_BLUE, android.graphics.PorterDuff.Mode.SRC_IN
            )
        } else {
            null
        }

        downloadLabel.text = when {
            item == null || done -> ""

            item.total > 0 ->
                "${(item.received * 100 / item.total)}%"

            else -> ""
        }

        downloadLabel.visibility =
            if (downloadLabel.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Качество спрашиваем всегда, а не запоминаем.
     *
     * Выбор здесь не настройка, а решение про этот ролик: часовой
     * в 1080p — это гигабайт, короткий — десяток мегабайт, и разумное
     * качество у них разное. Меню же стоит одного нажатия.
     *
     * Скачанное убирается повторным выбором того же качества — меню
     * открывается и тогда, потому что взять тот же ролик в другом
     * качестве не менее нужно.
     */
    private fun downloadTapped() {
        DownloadQualitySheet(context, videoId, titleText, details).show()
    }

    // --- Загрузка ---------------------------------------------------------

    /**
     * Переход к соседнему ролику подборки — порт `openQueueItem:`
     * из iOS-версии.
     *
     * Страница остаётся та же, меняется только ролик. Прежде здесь
     * открывалась новая страница, и вместе с ней приезжал новый ответ
     * сервера: у микса это скользящее окно вокруг выбранного ролика,
     * поэтому выбранный оказывался первым, а список — другим. Со стороны
     * это и выглядело как «список каждый раз перезагружается».
     *
     * Сам список после перехода не пересобирается: `JamQueue` отдаёт
     * накопленный, и в нём просто переезжает отметка «сейчас играет».
     */
    private fun openQueueItem(item: VideoItem) {
        val id = item.videoId ?: return

        if (id == videoId) {
            return
        }

        videoId = id
        titleText = item.title ?: ""
        startAt = maxOf(0.0, item.resumeAt)

        Log.d { "[YouTube/Очередь] Переходим к $id в подборке" }

        // Состояние прежнего ролика не должно пережить переход.
        details = null
        commentsPage = null
        sponsorSegments = emptyList()
        subtitleTracks = emptyList()
        subtitleCues = emptyList()
        subtitleTrack = null
        storyboard = null
        descriptionText = ""
        chapters = emptyList()
        lastSkippedTo = -1.0
        relatedItems = emptyList()

        titleLabel.text = titleText

        // Страница начинается сверху: прежняя прокрутка была о другом ролике.
        page.scrollTo(0, 0)

        load()
    }

    /**
     * Следующий ролик очереди, когда нынешний доиграл.
     *
     * Только внутри подборки: одиночный ролик ни во что не переходит —
     * в этом приложении нет «автовоспроизведения похожих», и подсовывать
     * человеку что попало незачем.
     *
     * У микса очередь к этому времени уже дописана: она растёт, когда
     * играет последний в ней ролик, — то есть как раз сейчас. Без этого
     * перехода микс не рос вовсе: дорасти до конца можно было только
     * тыкая в последнюю плитку руками.
     */
    private fun playNextInQueue() {
        if (!Settings.autoplayNextInQueue || queueItems.isEmpty()) {
            return
        }

        if (advancedFrom == videoId) {
            return
        }

        advancedFrom = videoId

        val place = queueItems.indexOfFirst { it.videoId == videoId }

        if (place < 0 || place + 1 >= queueItems.size) {
            Log.d { "[YouTube/Очередь] Ролик доиграл, следующего нет" }

            return
        }

        val next = queueItems[place + 1]

        Log.d { "[YouTube/Очередь] Ролик доиграл, включаем следующий: ${next.title}" }

        openQueueItem(next)
    }

    /**
     * «Спрашивать каждый раз» — открываем список дорожек, когда их
     * несколько.
     *
     * Вопрос задаётся после пуска, а не до: до пуска у нас ещё нет
     * ответа `/player`, а значит и перечня дорожек, — пришлось бы
     * задерживать показ ради лишнего запроса у каждого ролика, в том
     * числе одноязычного. Выбранная дорожка подхватывается на ходу, тем
     * же путём, что и выбор из меню вручную.
     */
    private fun askAudioTrackIfAsked() {
        if (askedAudioTrack ||
            Settings.playbackAudioLanguage != AudioLanguage.ASK
        ) {
            return
        }

        if (PlayerEngine.audioTracks().size < 2) {
            return
        }

        askedAudioTrack = true

        AudioMenu(context).show()
    }

    private fun load() {
        askedAudioTrack = false
        advancedFrom = null

        stopBroadcastWait()

        // Новый ролик — своя пропорция; подгон прежнего к нему не относится.
        stage.resetZoom()

        stage.fillsScreen = false

        stage.setBusy(true)

        // Прежний кадр прячем: иначе он висит «отпечатком» до первого нового.
        stage.clearFrame()

        PlayerEngine.open(videoId, playlistId, startAt)

        /**
         * Место продолжения — одноразовое.
         *
         * Оно верно ровно для того открытия, с которого пришли; перезапуск
         * того же ролика кнопкой «Перезагрузить видео» должен начинать
         * сначала, а не возвращать в ту же точку.
         */
        startAt = 0.0

        /**
         * Вставки SponsorBlock спрашиваются отдельным заходом.
         *
         * Служба посторонняя и отвечает не мгновенно; ждать её ради
         * начала просмотра незачем — метки на полосе появятся, когда
         * приедут.
         */
        async {
            val segments = SponsorBlock.segmentsFor(videoId)

            main {
                sponsorSegments = segments

                Log.d {
                    "[YouTube/SponsorBlock] Плееру передано вставок: ${segments.size}"
                }

                applyMarks()
            }
        }

        async {
            val page = Api.videoDetails(videoId, playlistId)

            main { applyDetails(page) }
        }
    }

    private fun applyDetails(page: VideoDetails?) {
        details = page

        if (page == null) {
            return
        }

        titleText = page.title.ifEmpty { titleText }

        titleLabel.text = titleText

        statsLabel.text = listOfNotNull(
            page.views?.takeIf { it.isNotEmpty() },
            page.published?.takeIf { it.isNotEmpty() }
        ).joinToString(" • ")

        channelLabel.text = page.channelTitle ?: ""
        subscribersLabel.text = page.subscribers ?: ""

        likeLabel.text = page.likes ?: ""

        liked = page.liked
        disliked = page.disliked
        subscribed = page.subscribed
        notifications = page.notifications

        /**
         * Главы разбираются здесь же: отдельного списка сервер
         * не присылает, он и сам собирает их из описания.
         */
        descriptionText = page.description ?: ""

        chapters = ru.computershik.troubadour.player.Chapters.parse(page.description)

        stage.chapterAt = { at ->
            ru.computershik.troubadour.player.Chapters.titleAt(chapters, at)
        }

        queueItems = JamQueue.merge(playlistId, videoId, page.queue)

        queueCard.bind(
            queueItems,
            page.queueTitle,
            page.queueIndex,
            videoId
        )

        loadFirstComment(page.commentsToken)

        chaptersCard.bind(chapters)

        applyMarks()

        applyRating()
        applySubscription()

        ImageLoader.loadInto(avatar, page.channelThumbnail, 40f)

        stage.setTitle(titleText, page.channelTitle ?: "")

        /**
         * В шторку — кадр ролика, а не значок канала: по кружку
         * с аватаркой не понять, что играет.
         */
        NowPlaying.show(
            titleText, page.channelTitle ?: "",
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        )

        applyRelated(page.related)

        /**
         * Субтитры и раскадровка — из ответа `/player`, который уже
         * привёз плеер. Второй раз его не спрашиваем.
         */
        async {
            val json = PlayerEngine.playerJson

            val tracks = Subtitles.tracksIn(json)
            val board = Storyboard.parse(Storyboard.specIn(json))

            main {
                subtitleTracks = tracks
                storyboard = board
            }
        }
    }

    private fun applyRelated(items: List<VideoItem>) {
        relatedItems = items

        related.removeAllViews()

        /**
         * Ширину берём у той колонки, в которой карточки лежат, а не
         * у всего экрана.
         *
         * На планшете лёжа похожие переезжают в правую колонку, и мерка
         * по экрану давала три столбца вместо одного: карточки выходили
         * втрое мельче, чем на iPad, и подписи под ними жались.
         */
        val host = if (split) side else page

        val available = if (host.width > 0) host.width else view.width

        val width = Metrics.points(available - dp(16f))
        val columns = Metrics.columnsForWidth(width)

        var index = 0

        while (index < items.size) {
            val row = FeedRow(context)

            row.bind(items.subList(index, minOf(index + columns, items.size)), columns)

            related.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            index += columns
        }
    }

    // --- Пульт ------------------------------------------------------------

    private fun togglePlay() {
        PlayerEngine.togglePlay()

        stage.applyState()

        NowPlaying.update()
    }

    /** Куда собираемся прыгнуть кнопками; −1, когда копить нечего. */
    private var skipTarget = -1.0

    private val skipRunnable = Runnable {
        val target = skipTarget

        skipTarget = -1.0

        if (target >= 0) {
            PlayerEngine.seekTo(target)
        }
    }

    /**
     * Перемотка кнопками — нажатия копятся, прыжок один.
     *
     * Каждый прыжок по подаче пересобирает её целиком: набранное
     * выбрасывается, дорожки заводятся заново. Пять нажатий за две
     * секунды — пять таких пересборок подряд, и плеер не выдерживал:
     * «Playback stuck buffering and not loading». Ползунок такой беды
     * не знал ровно потому, что за всё протягивание прыжок один.
     *
     * Теперь так же и у кнопок: место набирается сразу и показывается
     * сразу, а прыжок уходит, когда нажатия кончились. Считаем от уже
     * набранного места, а не от того, где идёт ролик, — иначе пять
     * нажатий по пять секунд дали бы пять, а не двадцать пять.
     */
    private fun skipBy(seconds: Double) {
        val base = if (skipTarget >= 0) skipTarget else PlayerEngine.position()

        val target = (base + seconds)
            .coerceIn(0.0, maxOf(0.0, PlayerEngine.duration()))

        skipTarget = target

        // Полоса и часы отвечают на каждое нажатие, а не на последнее.
        stage.previewSeek(target)

        view.removeCallbacks(skipRunnable)
        view.postDelayed(skipRunnable, SKIP_PAUSE)
    }

    private fun toggleFullscreen() {
        setFullscreen(!fullscreen, lockOrientation = true)
    }

    /**
     * @param lockOrientation держать ли экран в альбомном положении.
     *
     * По кнопке — держать: человек попросил развернуть кадр, и поворот
     * телефона обратно не должен это отменять. По повороту — нет:
     * положение уже альбомное, а запрет помешал бы вернуться обратно
     * тем же движением.
     */
    private fun setFullscreen(want: Boolean, lockOrientation: Boolean) {
        if (want == fullscreen) {
            return
        }

        fullscreen = want

        /**
         * Из развёрнутого вида выходим к обычному кадру.
         *
         * Увеличение и растяжка — свойства полного экрана: в окне кадр
         * стоит в потоке страницы, и увеличенный он налезал бы
         * на описание.
         */
        if (!fullscreen) {
            stage.resetZoom()

            stage.fillsScreen = false
        }

        stage.fullscreen = fullscreen

        val activity = context as? MainActivity

        activity?.applyImmersive(fullscreen)

        if (lockOrientation) {
            activity?.requestedOrientation = if (fullscreen) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } else {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        page.visibility = if (fullscreen) View.GONE else View.VISIBLE

        /**
         * В развёрнутом виде кадру отдаётся весь экран, и сказать об этом
         * надо явно.
         *
         * Кадр лежит в колонке с высотой «по содержимому», и без страницы
         * под ним колонка спрашивала у него ту же высоту, что и в обычном
         * виде, — а он отвечал по пропорции 16:9 от **ширины**. В альбомном
         * положении ширина больше высоты экрана, и кадр выходил выше его:
         * нижняя полоса вместе с часами, ползунком и кнопкой выхода
         * оказывалась за нижним краем.
         */
        (stage.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.height = if (fullscreen) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }

            stage.layoutParams = params
        }

        view.requestLayout()
    }

    /** Свернуть в окно, не останавливая воспроизведения. */
    private fun collapse() {
        MiniPlayer.show(
            this, videoId, titleText, details?.channelTitle ?: ""
        )

        Nav.pop()
    }

    private fun openDescription() {
        if (descriptionText.isEmpty()) {
            Toast.show(context, loc("Описания у ролика нет"))

            return
        }

        DescriptionSheet(context, statsLabel.text.toString(), descriptionText).show()
    }

    private fun openMenu() {
        PlayerMenu(context, this).show()
    }

    /** Окно «статистика для сисадминов» поверх кадра — показать либо убрать. */
    fun toggleStats() {
        stage.showStats(!stage.statsVisible)
    }

    // --- Действия ---------------------------------------------------------

    private fun rate(want: String) {
        if (!Auth.isSignedIn()) {
            Toast.show(context, loc("Войдите в аккаунт"))

            return
        }

        val page = details

        /**
         * Нажатие по уже поставленной оценке снимает её — так же,
         * как у самого YouTube.
         */
        val action = when {
            want == "like" && liked -> "none"
            want == "dislike" && disliked -> "none"
            else -> want
        }

        val params = when (action) {
            "like" -> page?.likeParams
            "dislike" -> page?.dislikeParams
            else -> page?.removeLikeParams
        }

        // Показываем сразу, не дожидаясь ответа: отказ вернёт как было.
        val wasLiked = liked
        val wasDisliked = disliked

        liked = action == "like"
        disliked = action == "dislike"

        applyRating()

        async {
            val done = Api.rate(videoId, action, params)

            if (!done) {
                main {
                    liked = wasLiked
                    disliked = wasDisliked

                    applyRating()

                    Toast.show(context, loc("Не получилось"))
                }
            }
        }
    }

    private fun applyRating() {
        likeIcon.setImageBitmap(Icons.icon(if (liked) "pl_like_on" else "pl_like"))
        dislikeIcon.setImageBitmap(
            Icons.icon(if (disliked) "pl_dislike_on" else "pl_dislike")
        )

        likeLabel.setTextColor(Theme.primaryText)
    }

    private fun toggleSubscription() {
        if (!Auth.isSignedIn()) {
            Toast.show(context, loc("Войдите в аккаунт"))

            return
        }

        val channel = details?.channelId ?: return

        val want = !subscribed

        subscribed = want

        applySubscription()

        async {
            val done = Api.setSubscribed(want, channel)

            if (!done) {
                main {
                    subscribed = !want

                    applySubscription()

                    Toast.show(context, loc("Не получилось"))
                }
            } else {
                Notify.post(Notify.SUBSCRIPTIONS)
            }
        }
    }

    /**
     * Панель колокольчика — четыре строки: все оповещения, по интересам,
     * никаких и отписаться.
     */
    private fun openBellMenu() {
        val channel = details?.channelId ?: return

        BellSheet(
            context,
            notifications,
            { picked ->
                if (picked != notifications) {
                    notifications = picked

                    applySubscription()

                    async { Api.setNotifications(picked, channel) }
                }
            },
            {
                subscribed = false

                applySubscription()

                async {
                    if (Api.setSubscribed(false, channel)) {
                        Notify.post(Notify.SUBSCRIPTIONS)
                    } else {
                        main {
                            subscribed = true

                            applySubscription()

                            Toast.show(context, loc("Не получилось"))
                        }
                    }
                }
            }
        ).show()
    }

    private fun applySubscription() {
        subscribeButton.subscribed = subscribed

        subscribeButton.title.text =
            if (subscribed) loc("Вы подписаны") else loc("Подписаться")

        // Значок колокольчика меняется вместе с выбранным родом оповещений.
        subscribeButton.setBellIcon(
            when (notifications) {
                Notifications.ALL -> "notifications_all"
                Notifications.NONE -> "notifications_none"
                else -> "notifications"
            }
        )

        subscribeButton.pill.fillColor = if (subscribed) {
            Theme.surface
        } else {
            Theme.primaryActionBackground
        }

        subscribeButton.title.setTextColor(
            if (subscribed) Theme.primaryText else Theme.primaryActionForeground
        )
    }

    private fun shareVideo() {
        val intent = Intent(Intent.ACTION_SEND)

        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, "https://youtu.be/$videoId")
        intent.putExtra(Intent.EXTRA_SUBJECT, titleText)

        context.startActivity(Intent.createChooser(intent, loc("Поделиться")))
    }

    /**
     * Первый комментарий — своим заходом.
     *
     * Он стоит отдельного запроса, а страница должна показаться раньше;
     * поэтому карточка появляется позже прочего. Ответ придерживаем:
     * та же страница нужна и списку, и брать её дважды подряд — это
     * четверть мегабайта и секунды ожидания на первом открытии.
     */
    private fun loadFirstComment(token: String?) {
        /**
         * Панели комментариев у ролика нет вовсе — значит, они закрыты.
         *
         * Прежде карточка просто не появлялась, и выглядело это как
         * «ещё грузится»: человек ждал у пустого места, которое ничем
         * не кончалось. Своих слов у сервера в этом случае нет —
         * поэтому надпись наша.
         */
        if (token.isNullOrEmpty()) {
            commentsCard.showNotice(loc("Комментарии к этому видео отключены"))

            return
        }

        async {
            val page = Api.comments(token)

            main {
                if (token != details?.commentsToken) {
                    return@main
                }

                commentsPage = page

                val first = page?.items?.firstOrNull()

                if (first == null) {
                    commentsCard.showNotice(loc("Комментариев нет"))
                } else {
                    commentsCard.bind(first)
                }
            }
        }
    }

    private fun openComments() {
        /**
         * Панелью поверх страницы, а не отдельной страницей: ролик
         * остаётся виден и играет, как в iOS-версии и в оригинале.
         */
        CommentsSheet(context, videoId, details?.commentsToken).show()
    }

    // --- Ход воспроизведения ----------------------------------------------

    private fun subscribeToEvents() {
        Notify.on(PlayerEngine.PROGRESS, this) { tick() }

        Notify.on(PlayerEngine.STATE, this) {
            stage.applyState()

            NowPlaying.update()

            val state = it as? Int

            if (state == com.google.android.exoplayer2.Player.STATE_ENDED) {
                playNextInQueue()
            }

            stage.setBusy(state == com.google.android.exoplayer2.Player.STATE_BUFFERING)

            (context as? MainActivity)?.keepAwake(PlayerEngine.isPlaying)
        }

        Notify.on(PlayerEngine.FAILED, this) { reason ->
            stage.setBusy(false)

            status.showOffline(
                loc("Не удалось получить поток"),
                reason as? String ?: "",
                loc("Повторить")
            ) {
                status.hide()

                load()
            }
        }

        Notify.on(PlayerEngine.BOT_GATE, this) { web ->
            stage.setBusy(false)

            val signedIn = web as? Boolean ?: false

            status.showOffline(
                loc("YouTube просит подтвердить, что вы не робот"),
                if (signedIn) {
                    ""
                } else {
                    loc(
                        "YouTube упёрся в проверку. Входа по QR-коду ей мало — " +
                            "нужен ещё вход в браузере: кнопкой ниже либо в настройках " +
                            "приложения, строка «Вход в браузере»"
                    )
                },
                if (signedIn) loc("Пройти проверку") else loc("Войти в браузере")
            ) {
                Nav.push(WebScreen(context, login = !signedIn, videoId = videoId) {
                    retryAfterChallenge()
                })
            }
        }

        /** Стала известна пропорция кадра — переложить его по ней. */
        Notify.on(PlayerEngine.VIDEO_SIZE, this) { stage.requestLayout() }

        Notify.on(PlayerEngine.UPCOMING, this) { about ->
            @Suppress("UNCHECKED_CAST")
            val pair = about as? Pair<Double, String?>

            awaitBroadcast(pair?.first ?: 0.0, pair?.second)
        }

        Notify.on(PlayerEngine.FIRST_FRAME, this) {
            /** Трансляция поднялась — ожидание кончилось. */
            stopBroadcastWait()

            status.hide()

            stage.showFrame()

            askAudioTrackIfAsked()
        }

        /**
         * Поворот сам разворачивает кадр на весь экран.
         *
         * Прежде поворот лишь **разрешался** — экран становился
         * альбомным, а строка состояния и кнопки навигации оставались,
         * и полноэкранный режим приходилось дожимать кнопкой. Так же
         * поступает и оригинал: `orientationChanged` сравнивает
         * положение с нынешним видом и сам зовёт `fullscreenTapped`.
         */
        /**
         * Системная полоса вернулась — значит, человек коснулся кадра,
         * а касание это забрала система. Показываем пульт вместо него.
         */
        Notify.on(Notify.SYSTEM_BARS, this) {
            if (fullscreen) {
                stage.showControls()
            }
        }

        Notify.on(Notify.ORIENTATION, this) { value ->
            if (!Settings.autoFullscreenInLandscape) {
                return@on
            }

            val landscape =
                value == android.content.res.Configuration.ORIENTATION_LANDSCAPE

            setFullscreen(landscape, lockOrientation = false)
        }

        // Экран повернули — половина у него теперь другая.
        Notify.on(Notify.ORIENTATION, this) { applyHeightCap() }

        Notify.on(Notify.THEME, this) { repaint() }

        // Проценты у кнопки идут за ходом скачивания.
        Notify.on(Notify.DOWNLOADS, this) { applyDownloadState() }
    }

    private fun retryAfterChallenge() {
        status.hide()

        load()
    }

    private fun tick() {
        val at = PlayerEngine.position()
        val length = PlayerEngine.duration()

        stage.setProgress(at, PlayerEngine.buffered(), length)

        // Подпись под заголовком глав идёт за ходом ролика.
        if (chapters.isNotEmpty()) {
            chaptersCard.showNow(
                ru.computershik.troubadour.player.Chapters.titleAt(chapters, at)
            )
        }

        // Посреди перемотки пропускать вставки нельзя: прыжок отменит сам себя.
        if (stage.isSeeking()) {
            return
        }

        skipSponsorAt(at)
        showSubtitlesAt(at)
    }

    /**
     * Пропуск оплаченной вставки.
     *
     * Прыгаем не в конец вставки ровно, а на полсекунды дальше: сервер
     * размечает границы приблизительно, и прыжок точно в конец нередко
     * возвращает нас обратно внутрь того же куска.
     *
     * `lastSkippedTo` не даёт ходить по кругу: после прыжка мы окажемся
     * внутри времени той же вставки, если её конец размечен с запасом.
     */
    private fun skipSponsorAt(now: Double) {
        if (!Settings.usesSponsorBlock) {
            return
        }

        for (segment in sponsorSegments) {
            if (now < segment.start || now >= segment.end) {
                continue
            }

            if (lastSkippedTo == segment.end) {
                return
            }

            lastSkippedTo = segment.end

            Log.d {
                "[YouTube/SponsorBlock] Пропускаем ${segment.category}: " +
                    "${segment.start.toInt()} → ${segment.end.toInt()} с"
            }

            PlayerEngine.seekTo(segment.end + 0.5)

            Toast.show(context, loc("Реклама пропущена"))

            return
        }
    }

    /** Переложить строку субтитров сразу — после правки сдвига. */
    fun refreshSubtitles() {
        showSubtitlesAt(PlayerEngine.position())
    }

    private fun showSubtitlesAt(now: Double) {
        if (subtitleCues.isEmpty()) {
            subtitleLabel.visibility = View.GONE

            return
        }

        val cue = Subtitles.cueAt(subtitleCues, now, Settings.subtitleOffset)

        if (cue == null) {
            subtitleLabel.visibility = View.GONE

            return
        }

        subtitleLabel.text = cue.text
        subtitleLabel.visibility = View.VISIBLE

        val params = subtitleLabel.layoutParams as? FrameLayout.LayoutParams ?: return

        params.topMargin = (stage.height * Settings.subtitlePlace).toInt() -
            subtitleLabel.height / 2

        subtitleLabel.layoutParams = params
    }

    /** Выбор дорожки субтитров — зовётся из меню. */
    fun pickSubtitles(track: SubtitleTrack?) {
        subtitleTrack = track

        if (track == null) {
            subtitleCues = emptyList()

            subtitleLabel.visibility = View.GONE

            return
        }

        async {
            val cues = Subtitles.cuesFor(track)

            main { subtitleCues = cues }
        }
    }

    fun tracks(): List<SubtitleTrack> = subtitleTracks

    fun currentSubtitleTrack(): SubtitleTrack? = subtitleTrack

    private fun applyMarks() {
        stage.setMarks(sponsorSegments.map { it.start to it.end })

        stage.setChapters(ru.computershik.troubadour.player.Chapters.starts(chapters))
    }

    // --- Жизнь ------------------------------------------------------------

    override fun appear() {
        PlayerEngine.attach(stage.surface)

        stage.applyState()

        /**
         * Кольцо ожидания сверяем с плеером, а не помним своё.
         *
         * Оповещение о состоянии звучит один раз, в миг смены. Уйдя со
         * страницы посреди набора и вернувшись, когда ролик уже пошёл,
         * мы этот миг пропускаем — и кольцо, поднятое при загрузке,
         * крутится поверх идущего ролика без конца. Спрашиваем прямо.
         */
        stage.setBusy(PlayerEngine.isBuffering)
    }

    override fun disappear() {
        /**
         * Уходя, не останавливаем: страница могла уйти и в мини-плеер,
         * и просто под другой экран. Останавливать надо только тогда,
         * когда ролик закрыт насовсем, — это [destroy].
         */
    }

    override fun destroy() {
        super.destroy()

        // Ожидание трансляции экран не переживает: ждать больше некому.
        stopBroadcastWait()

        (context as? MainActivity)?.keepAwake(false)

        if (MiniPlayer.videoId != videoId) {
            PlayerEngine.release()

            NowPlaying.hide()
        }
    }

    override fun repaint() {
        super.repaint()

        titleLabel.setTextColor(Theme.primaryText)
        statsLabel.setTextColor(Theme.mutedText)
        channelLabel.setTextColor(Theme.primaryText)
        subscribersLabel.setTextColor(Theme.secondaryText)

        avatar.placeholderColor = Theme.avatarPlaceholder

        commentsCard.applyTheme()
        queueCard.applyTheme()
        chaptersCard.applyTheme()

        applyRating()
        applySubscription()
    }

    override fun handleBack(): Boolean {
        if (fullscreen) {
            toggleFullscreen()

            return true
        }

        /**
         * «Назад» со страницы ролика сворачивает его в окно, а не закрывает.
         *
         * Так же и в оригинале: закрыть насовсем можно крестиком в окне.
         * Ролик, который играет, не должен исчезать от привычного жеста
         * возврата.
         */
        if (PlayerEngine.isPlaying) {
            collapse()

            return true
        }

        return false
    }

    override fun allowsLandscape(): Boolean = fullscreen ||
        Settings.autoFullscreenInLandscape
}
