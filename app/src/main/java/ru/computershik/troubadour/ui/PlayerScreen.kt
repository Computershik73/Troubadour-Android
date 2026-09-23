package ru.computershik.troubadour.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
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
import ru.computershik.troubadour.net.liveChat
import ru.computershik.troubadour.net.liveChatFilters
import ru.computershik.troubadour.net.Dislikes
import android.widget.HorizontalScrollView
import ru.computershik.troubadour.Counts
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

    /** Число дизлайков — справа от значка, как счётчик у лайка. */
    private lateinit var dislikeLabel: TextView

    /**
     * «Поделиться» и «Сохранить» — полями, а не местными: их надо
     * перекрашивать при смене темы. Прежде «Поделиться» заводилась
     * местной и так и оставалась в цветах той темы, при которой
     * страницу собрали.
     */
    private lateinit var shareIcon: ImageView
    private lateinit var shareLabel: TextView

    private lateinit var saveButton: View
    private lateinit var saveIcon: ImageView
    private lateinit var saveLabel: TextView

    /** Лежит ли ролик хоть в одном плейлисте — по этому красится значок. */
    private var savedSomewhere = false

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

    /**
     * Чат трансляции: метка следующей страницы и последние записи.
     *
     * Записи копятся здесь, а не в панели: пока человек смотрит, карточка
     * уже собрала последние полсотни, и открывать разговор с пустого
     * места незачем.
     */
    private var chatToken: String? = null
    private var chatItems = ArrayList<ru.computershik.troubadour.net.ChatItem>()
    private var chatFilters: List<ru.computershik.troubadour.net.ChatFilter>? = null

    private val chatTick = Runnable { pollLiveChat() }

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

        /**
         * Отступ под строку состояния.
         *
         * С Android 5 окно рисуется под ней — она прозрачная, и высоту
         * её панели отмеряют сами. Всем прочим экранам этот отступ даёт
         * их шапка (`ScreenHeader`), а у страницы ролика шапки нет:
         * сверху сразу кадр, и он уезжал под часы и значки.
         *
         * В развёрнутом виде отступ снимается: там кадру отдан весь
         * экран, а строки состояния нет вовсе.
         */
        holder.setPadding(0, statusBarHeight(), 0, 0)

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

        /**
         * Строку субтитров двигают пальцем, и место запоминается.
         *
         * Держим это на самой строке, а не на кадре: касание, начатое
         * на ней, кадру уже не достаётся, и пульт от такого движения
         * не мигает.
         */
        subtitleLabel.setOnTouchListener(object : View.OnTouchListener {

            private var fromX = 0f
            private var fromY = 0f
            private var moved = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        fromX = event.rawX
                        fromY = event.rawY
                        moved = false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val box = stage.width
                        val tall = stage.height

                        if (box <= 0 || tall <= 0) {
                            return true
                        }

                        val shiftX = event.rawX - fromX
                        val shiftY = event.rawY - fromY

                        if (!moved &&
                            kotlin.math.abs(shiftX) < dp(4f) &&
                            kotlin.math.abs(shiftY) < dp(4f)
                        ) {
                            return true
                        }

                        moved = true

                        fromX = event.rawX
                        fromY = event.rawY

                        Settings.subtitlePlaceX += shiftX / box
                        Settings.subtitlePlace += shiftY / tall

                        placeSubtitle()
                    }
                }

                return true
            }
        })

        // Свои дети у кадра кончились — дальше идёт наше.
        stage.sealOwnChildren()

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

        /**
         * Значок и число — рядом, с тем же отступом 6, что у лайка.
         *
         * Число прячется, пока его нет: пустая подпись оставила бы после
         * значка лишние шесть точек, и подложка оценки вышла бы кривой.
         */
        val dislikeRow = LinearLayout(context)

        dislikeRow.orientation = LinearLayout.HORIZONTAL
        dislikeRow.gravity = Gravity.CENTER_VERTICAL

        dislikeRow.addView(dislikeIcon, LinearLayout.LayoutParams(dp(20f), dp(20f)))

        dislikeLabel = label(context, Fonts.regular, 14f, Theme.primaryText, 1)
        dislikeLabel.visibility = View.GONE

        val dislikeCountParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dislikeCountParams.leftMargin = dp(6f)

        dislikeRow.addView(dislikeLabel, dislikeCountParams)

        dislike.addView(
            dislikeRow,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
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

        shareIcon = ImageView(context)

        shareIcon.setImageBitmap(Icons.icon("share"))
        shareIcon.scaleType = ImageView.ScaleType.FIT_CENTER

        shareRow.addView(shareIcon, LinearLayout.LayoutParams(dp(20f), dp(20f)))

        shareLabel = label(context, Fonts.regular, 14f, Theme.primaryText, 1)

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
         * «Сохранить» — между «Поделиться» и «Скачать», как в оригинале.
         *
         * Видна только вошедшему: плейлисты бывают лишь у учётной записи,
         * и безымянному кнопка предлагала бы то, чего сделать нельзя.
         */
        val save = TappableView(context)

        save.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))

        val saveRow = LinearLayout(context)

        saveRow.orientation = LinearLayout.HORIZONTAL
        saveRow.gravity = Gravity.CENTER_VERTICAL

        saveIcon = ImageView(context)
        saveIcon.scaleType = ImageView.ScaleType.FIT_CENTER

        saveRow.addView(saveIcon, LinearLayout.LayoutParams(dp(20f), dp(20f)))

        saveLabel = label(context, Fonts.regular, 14f, Theme.primaryText, 1)

        saveLabel.text = loc("Сохранить")

        val saveParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        saveParams.leftMargin = dp(6f)

        saveRow.addView(saveLabel, saveParams)

        save.addView(saveRow)
        save.onTap = { openSaveSheet() }

        saveButton = save

        row.addView(
            save,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        applySaveButton()

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

        /**
         * Ряд прокручивается вбок — так он устроен и в оригинале
         * (`VideoActionsScrollViewer`).
         *
         * Прежде он стоял неподвижно в полях 16 и на узком экране
         * `LinearLayout` молча ужимал последних детей: с «Сохранить»
         * и процентами скачивания кнопки уезжали бы за край или
         * обрезались. Поля теперь внутри прокрутки — ряд начинается
         * с отступа 16, а уезжает до самого края экрана.
         */
        row.setPadding(dp(16f), 0, dp(16f), 0)

        val scroller = HorizontalScrollView(context)

        scroller.isHorizontalScrollBarEnabled = false
        scroller.overScrollMode = View.OVER_SCROLL_NEVER

        scroller.addView(
            row,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        scroller.layoutParams = marginParams(0, dp(2f), 0, dp(16f))

        applyDownloadState()

        return scroller
    }

    /** Кнопка «Сохранить»: видна ли и каким значком. */
    private fun applySaveButton() {
        saveButton.visibility = if (Auth.isSignedIn()) View.VISIBLE else View.GONE

        saveIcon.setImageBitmap(Icons.icon(if (savedSomewhere) "pl_save_on" else "pl_save"))

        saveLabel.setTextColor(Theme.primaryText)
    }

    private fun openSaveSheet() {
        if (!Auth.isSignedIn()) {
            Toast.show(context, loc("Войдите в аккаунт"))

            return
        }

        val forVideo = videoId

        SavePlaylistSheet(context, forVideo) { saved ->
            // Лист мог пережить переход к другому ролику — тогда отметка чужая.
            if (forVideo == videoId) {
                savedSomewhere = saved

                applySaveButton()
            }
        }.show()
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

        /**
         * Прежний ролик бросаем сразу, а не когда доедет новый.
         *
         * `open` только помечает поколение и уходит за потоком в фон,
         * а в плеере всё это время лежит прежний ролик — и продолжает
         * играть. Переключая трек в плейлисте или в миксе, человек ещё
         * секунду-другую слышал прошлый: кадр уже закрыт заслонкой,
         * а звук идёт. В iOS-версии на этом месте стоит `teardownPlayer`,
         * то есть плеер сносится тут же.
         *
         * Поверхность после этого возвращаем: `dropCurrent` отбирает её
         * у плеера, а новый поток, если плеер не пересобирался, сам
         * её не заберёт — и картинке негде было бы появиться.
         */
        PlayerEngine.dropCurrent()
        PlayerEngine.attach(stage.surface)

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

        loadDislikes()

        /**
         * Отметку «сохранено» сбрасываем: узнать её можно только списком
         * плейлистов, а спрашивать его ради значка на каждом ролике —
         * лишний запрос. Лист, когда его откроют, покрасит значок сам.
         */
        savedSomewhere = false

        applySaveButton()

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

        applyConversation(page)

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

        // Развёрнутому кадру строка состояния места не оставляет.
        holder.setPadding(0, if (fullscreen) 0 else statusBarHeight(), 0, 0)

        view.requestLayout()
    }

    /** Свернуть в окно, не останавливая воспроизведения. */
    private fun collapse() {
        MiniPlayer.show(
            this, videoId, titleText, details?.channelTitle ?: "",
            stage.lastFrame()
        )

        Nav.pop()
    }

    /** Снимок кадра, чтобы окошко и страница менялись без черноты. */
    fun lastFrame(): android.graphics.Bitmap? = stage.lastFrame()

    fun holdFrame(shot: android.graphics.Bitmap?) {
        stage.holdFrame(shot)
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

    /**
     * Число дизлайков — отдельным запросом, после страницы.
     *
     * Ждать его перед показом незачем: оно стороннее и не главное,
     * а сервис бывает и медленным. Пришло — дописываем; ролик за это
     * время сменился — выбрасываем.
     */
    private fun loadDislikes() {
        dislikeLabel.text = ""
        dislikeLabel.visibility = View.GONE

        if (!Settings.showsDislikes) {
            return
        }

        val forVideo = videoId

        async {
            val count = Dislikes.count(forVideo)

            main {
                if (forVideo != videoId || count == null) {
                    return@main
                }

                dislikeLabel.text = Counts.compact(count)
                dislikeLabel.visibility = View.VISIBLE
            }
        }
    }

    private fun applyRating() {
        likeIcon.setImageBitmap(Icons.icon(if (liked) "pl_like_on" else "pl_like"))
        dislikeIcon.setImageBitmap(
            Icons.icon(if (disliked) "pl_dislike_on" else "pl_dislike")
        )

        likeLabel.setTextColor(Theme.primaryText)
        dislikeLabel.setTextColor(Theme.primaryText)
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

    /**
     * Что стоит под роликом — комментарии или чат трансляции.
     *
     * У эфира комментариев обычно нет вовсе, и там прежде висела надпись
     * «Комментарии к этому видео отключены»: место пустовало, хотя живой
     * разговор шёл рядом. Метка чата приходит тем же ответом, что и всё
     * описание, так что лишнего запроса не нужно.
     */
    private fun applyConversation(page: ru.computershik.troubadour.net.VideoDetails) {
        stopLiveChat()

        chatFilters = null

        // Обычный ролик — заголовок прежний; ниже его сменит чат, если он есть.
        commentsCard.setTitle(loc("Комментарии"))

        chatToken = page.liveChatToken

        if (!chatToken.isNullOrEmpty()) {
            chatItems = ArrayList()

            /**
             * Метки фильтров добываем сразу, пока человек смотрит: за ними
             * идёт отдельный заход на страницу чата, и делать его в тот миг,
             * когда панель открывают, значит заставить ждать.
             */
            val forVideo = videoId

            async {
                val filters = Api.liveChatFilters(forVideo)

                main {
                    if (forVideo == videoId) {
                        chatFilters = filters
                    }
                }
            }

            // На месте комментариев теперь чат — и называется он так же.
            commentsCard.setTitle(loc("Чат"))

            commentsCard.showNotice(loc("Чат трансляции загружается…"))

            pollLiveChat()

            /**
             * Комментарии при живом чате не спрашиваем вовсе.
             *
             * Карточка одна, и класть в неё то и другое разом значит
             * получить мигание: чат обновляется каждые десять секунд
             * и затирал бы комментарий, а тот — свежее сообщение.
             * У трансляции разговор идёт в чате, и карточка его.
             */
            return
        }

        loadFirstComment(page.commentsToken)
    }

    // --- Чат трансляции ----------------------------------------------------

    /**
     * Останавливает опрос и забывает набранное.
     *
     * Зовётся на каждой загрузке страницы и при уходе с неё: таймер,
     * забытый от прошлого ролика, стучался бы в чужой чат и переписывал
     * карточку поверх нового ролика.
     */
    private fun stopLiveChat() {
        clock.removeCallbacks(chatTick)

        chatToken = null
        chatItems = ArrayList()
    }

    /**
     * Берёт очередную страницу чата и показывает свежее сообщение.
     *
     * Опрос идёт с той задержкой, которую называет сам сервер (обычно
     * десять секунд): чаще он всё равно ничего не отдаст. Метка каждый
     * раз новая, старая после ответа не годится.
     */
    private fun pollLiveChat() {
        val token = chatToken

        if (token.isNullOrEmpty()) {
            return
        }

        async {
            val page = Api.liveChat(token)

            main { applyLiveChat(page, token) }
        }
    }

    private fun applyLiveChat(
        page: ru.computershik.troubadour.net.ChatPage?,
        asked: String
    ) {
        // Пока ходили в сеть, страница могла смениться — ответ уже не наш.
        if (asked != chatToken) {
            return
        }

        if (page == null) {
            /**
             * Молчание сервера чат не кончает: у трансляции бывают
             * и пустые ответы. Пробуем снова с той же меткой.
             */
            scheduleLiveChatAfter(10_000)

            return
        }

        page.continuation?.takeIf { it.isNotEmpty() }?.let { chatToken = it }

        if (page.items.isNotEmpty()) {
            chatItems.addAll(page.items)

            // Держим полсотни последних: панели этого хватает, памяти — тем более.
            while (chatItems.size > 50) {
                chatItems.removeAt(0)
            }

            chatItems.lastOrNull()?.let { commentsCard.bindChat(it) }
        } else if (chatItems.isEmpty()) {
            commentsCard.showNotice(loc("В чате пока тихо"))
        }

        scheduleLiveChatAfter(page.waitMillis)
    }

    private fun scheduleLiveChatAfter(millis: Long) {
        clock.removeCallbacks(chatTick)

        clock.postDelayed(chatTick, maxOf(2000L, millis))
    }

    private fun openComments() {
        /**
         * Панелью поверх страницы, а не отдельной страницей: ролик
         * остаётся виден и играет, как в iOS-версии и в оригинале.
         */
        /**
         * У трансляции на этом месте чат, и открывается он же.
         *
         * Комментариев у эфира обычно нет вовсе, так что выбор простой:
         * есть метка чата — показываем разговор, нет — прежние комментарии.
         */
        val chat = chatToken

        if (!chat.isNullOrEmpty()) {
            CommentsSheet(context, videoId, null).showLiveChat(
                chat, chatItems, chatFilters, details?.views
            )

            return
        }

        CommentsSheet(context, videoId, details?.commentsToken).show()
    }

    // --- Ход воспроизведения ----------------------------------------------

    private fun subscribeToEvents() {
        // Заводится при каждом показе — прежние снимаем, чтобы не двоить.
        Notify.offAll(this)

        Notify.on(PlayerEngine.PROGRESS, this) { tick() }

        Notify.on(PlayerEngine.STATE, this) {
            stage.applyState()

            NowPlaying.update()

            val state = it as? Int

            if (state == com.google.android.exoplayer2.Player.STATE_ENDED) {
                playNextInQueue()
            }

            stage.setBusy(state == com.google.android.exoplayer2.Player.STATE_BUFFERING)

            (context as? MainActivity)?.applyKeepAwake()
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

        val fresh = subtitleLabel.text?.toString() != cue.text

        subtitleLabel.text = cue.text
        subtitleLabel.visibility = View.VISIBLE

        /**
         * Строка сменилась — её размер станет известен только после
         * раскладки, а место считается от него. Поэтому ставим дважды:
         * сейчас по прежнему размеру и ещё раз, когда новый измерят.
         */
        placeSubtitle()

        if (fresh) {
            subtitleLabel.post { placeSubtitle() }
        }
    }

    /**
     * Ставит строку субтитров по запомненному месту.
     *
     * Место хранится долями от кадра, а не точками: при повороте строка
     * должна остаться там же по смыслу, а не уехать за край. Доли
     * указывают на **середину** строки, поэтому и вычитаем половину её
     * размера — иначе перетаскивание уводило бы строку рывком на пол-её
     * ширины.
     */
    private fun placeSubtitle() {
        val params = subtitleLabel.layoutParams as? ViewGroup.MarginLayoutParams ?: return

        val box = stage.width
        val tall = stage.height

        if (box <= 0 || tall <= 0) {
            return
        }

        // Шире девяти десятых кадра строку не пускаем — пусть переносится.
        val cap = (box * 0.9).toInt()

        if (subtitleLabel.maxWidth != cap) {
            subtitleLabel.maxWidth = cap
        }

        val left = (box * Settings.subtitlePlaceX).toInt() - subtitleLabel.width / 2
        val top = (tall * Settings.subtitlePlace).toInt() - subtitleLabel.height / 2

        params.leftMargin = left.coerceIn(0, maxOf(0, box - subtitleLabel.width))
        params.topMargin = top.coerceIn(0, maxOf(0, tall - subtitleLabel.height))

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

    /** Развёрнут ли кадр — меню спрашивает, показывать ли подгон. */
    fun isFullscreen(): Boolean = fullscreen

    fun fillsScreen(): Boolean = stage.fillsScreen

    /**
     * Переключает подгон кадра: поля или обрезанные края.
     *
     * Увеличение при этом сбрасывается — иначе одно нажатие меняло бы
     * сразу две вещи, и понять, отчего кадр стал другим, было бы нельзя.
     */
    fun toggleFillsScreen() {
        stage.resetZoom()

        stage.fillsScreen = !stage.fillsScreen

        Log.d {
            "[YouTube/Плеер] Кадр " + if (stage.fillsScreen) {
                "растянут по экрану — поля убраны, края обрезаны"
            } else {
                "вписан целиком — поля вернулись"
            }
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
        /**
         * Подписки заводятся здесь, а не при сборке, и заводятся заново
         * при каждом показе.
         *
         * Сворачивание в окошко уводит страницу из стопки через
         * `Nav.pop()`, а тот зовёт `destroy()` — и `Notify.offAll`
         * снимает с неё все подписки разом. Возвращается та же страница
         * (окошко держит её у себя), собирается она один раз, и подписки
         * прежде не восстанавливались никогда: часы стояли, а значок
         * «играет/пауза» не менялся, хотя ролик шёл.
         */
        subscribeToEvents()

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

        // И чат тоже: опрашивать его некому и некуда показывать.
        stopLiveChat()

        /**
         * Флаг снимаем не наотмашь, а по делу.
         *
         * Уходя в окошко, страница разбирается, а ролик продолжает
         * играть — и экран после этого гас посреди просмотра. Спрашиваем
         * у плеера, а не у того, кто уходит.
         */
        (context as? MainActivity)?.applyKeepAwake()

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

        shareIcon.setImageBitmap(Icons.icon("share"))
        shareLabel.setTextColor(Theme.primaryText)

        applySaveButton()
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
