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
import ru.computershik.troubadour.Counts
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.model.VideoItem
import ru.computershik.troubadour.net.Api
import ru.computershik.troubadour.net.Auth
import ru.computershik.troubadour.net.Dislikes
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
    private val dislikeIcon = ImageView(context)
    private val likeCount = label(context, Fonts.semiBold, 12f, android.graphics.Color.WHITE, 1)
    private val dislikeCount = label(context, Fonts.semiBold, 12f, android.graphics.Color.WHITE, 1)
    private val commentCount = label(context, Fonts.semiBold, 12f, android.graphics.Color.WHITE, 1)

    private val ring = LoadingRing(context)
    private val notice = label(context, Fonts.regular, 14f, android.graphics.Color.WHITE, 0)

    private val items = ArrayList<VideoItem>()

    private var at = 0
    private var sequence: String? = null
    private var busy = false

    /**
     * Показывается ли листалка прямо сейчас.
     *
     * Поток за роликом едет своим ходом, и ответ приходит когда придёт —
     * в том числе когда человек уже ушёл на другую вкладку. Прежде такой
     * ответ всё равно запускал воспроизведение, и ролик начинал играть
     * из-под чужого экрана.
     */
    private var running = false

    /**
     * Ролик, о конце которого уже отчитались.
     *
     * `STATE_ENDED` приходит не один раз — плеер рассылает его и сам,
     * и вслед за закрытием записи просмотра. Без отметки второй приход
     * перелистывал бы ещё на один ролик вперёд.
     */
    private var finishedId: String? = null

    /**
     * Всё содержимое страницы в одном виде — чтобы двигать его целиком.
     *
     * Подложка (чернота) остаётся у самой листалки: снимок содержимого
     * должен быть прозрачным там, где нет ни подписей, ни кнопок, иначе
     * он закрыл бы собой кадр уходящей страницы.
     */
    private val content = FrameLayout(context)

    /**
     * Уходящая страница — снимком, двумя слоями.
     *
     * `TextureView` в холст не рисуется вовсе: он живёт отдельным слоем,
     * и `draw()` оставляет на его месте пустоту. Поэтому кадр снимается
     * у него самого (`getBitmap`), а подписи и кнопки — обычной
     * отрисовкой поверх.
     */
    private val ghost = FrameLayout(context)
    private val ghostFrame = ImageView(context)
    private val ghostChrome = ImageView(context)

    /** Идёт ли сейчас переход — на это время касания не в счёт. */
    private var sliding = false

    /**
     * Превью соседнего ролика — то, что видно в просвете под пальцем.
     *
     * Без него за уходящей страницей чернота, и тяга читается как
     * «экран поехал», а не «следующий ролик подходит». Настоящей
     * страницы у соседа нет — её содержимое приедет только при переходе,
     * — но превью у нас на руках с самой ленты.
     */
    private val peek = RoundedImage(context)
    private var peekFor = -1

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

        /**
         * Пропорцию спрашиваем у плеера, а он берёт её у самого потока
         * и с поправкой на неквадратный пиксель.
         */
        if (PlayerEngine.videoRatio > 0) {
            frameAspect = PlayerEngine.videoRatio
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

    /**
     * Пропорция кадра становится известна не сразу — переспрашиваем
     * раскладку, когда её назовут.
     *
     * Прежде [placeSurface] звалась только из раскладки, а новую после
     * смены ролика никто не просил: поверхность держала пропорцию
     * прошлого ролика (а у первого — вертикальную по умолчанию),
     * и кадр, снятый иначе, выходил сплющенным. Помогал разве что
     * поворот экрана — он раскладку и вызывал.
     *
     * Подписка живёт, пока вид в окне: листалка бывает и вкладкой,
     * и отдельным экраном, и у второй каждый раз своя.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        Notify.on(PlayerEngine.VIDEO_SIZE, this) { requestLayout() }

        /**
         * Пошёл новый ролик — показываем кадр и убираем превью.
         *
         * Сверять что-либо больше не нужно: прошлый ролик к этому
         * времени брошен, и посторонних объявлений о первом кадре
         * взяться неоткуда. Прежняя сверка «идёт ли воспроизведение»
         * заодно и запаздывала — настоящий первый кадр иногда приходит
         * чуть раньше, чем плеер признаётся, что играет, и картинка
         * появлялась позже звука.
         */
        Notify.on(PlayerEngine.FIRST_FRAME, this) {
            if (running) {
                revealFrame()
            }
        }

        /**
         * Слушаем, только пока листалка на виду.
         *
         * Вид остаётся в окне и тогда, когда поверх него открыт другой
         * экран: стопка лежит **над** оболочкой, а не вместо неё, и
         * вкладка под ней никуда не девается. Без этой оговорки листалка
         * ловила конец чужого ролика — того, что играл на странице
         * сверху, — и заводила свой следующий Shorts в чужую поверхность.
         */
        Notify.on(PlayerEngine.STATE, this) {
            if (!running) {
                return@on
            }

            if (it as? Int == com.google.android.exoplayer2.Player.STATE_ENDED) {
                playbackFinished()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        Notify.offAll(this)
    }

    private var playback: ShortsPlayback? = null
    private var liked = false
    private var disliked = false

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
            content,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        content.addView(
            surface,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        /**
         * Превью **поверх** кадра — пока поток не пошёл.
         *
         * У подачи медленный старт: перед первым кадром надо получить
         * заголовок дорожки и первые фрагменты, а это сотни килобайт
         * против двадцати у обычного DASH. У Shorts это заметнее всего,
         * и чёрный экран на две секунды выглядит поломкой.
         *
         * Прежде превью лежало **под** кадром и работало лишь потому,
         * что пустая поверхность прозрачна. При смене ролика она уже
         * не пуста — в ней кадр прошлого, — и превью не было видно
         * вовсе. Прятать саму поверхность нельзя: `TextureView` заводит
         * её только когда его рисуют, и у спрятанного плееру некуда
         * выводить. Замер это и показал: звук шёл на 1,3 с, а первый
         * кадр объявлялся на 4,5 — ровно тогда, когда срок безопасности
         * возвращал поверхность на виду.
         */
        cover.cornerRadius = 0f
        cover.placeholderColor = 0xFF000000.toInt()

        content.addView(
            cover,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        content.addView(buildTexts())
        content.addView(buildButtons())

        content.addView(
            ring,
            LayoutParams(dp(36f), dp(36f), Gravity.CENTER)
        )

        notice.gravity = Gravity.CENTER
        notice.visibility = GONE

        content.addView(
            notice,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { leftMargin = dp(24f); rightMargin = dp(24f) }
        )

        peek.cornerRadius = 0f
        peek.placeholderColor = 0xFF000000.toInt()
        peek.visibility = GONE

        addView(
            peek,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        ghostFrame.scaleType = ImageView.ScaleType.FIT_CENTER

        ghost.addView(
            ghostFrame,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        ghost.addView(
            ghostChrome,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        ghost.visibility = GONE

        addView(
            ghost,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
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

        column.addView(button(likeIcon, likeCount, "pl_like") { rate("like") })

        /**
         * Дизлайк — вторым, как в iOS-версии и в оригинале: лайк, дизлайк,
         * комментарии, «Поделиться». Здесь его не было вовсе, и поставить
         * дизлайк у Shorts можно было только открыв ролик страницей.
         */
        column.addView(button(dislikeIcon, dislikeCount, "pl_dislike") { rate("dislike") })

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

            /**
             * Ширина подписи — по тексту, и это обязательно указывать.
             *
             * Без параметров вертикальный `LinearLayout` даёт ребёнку
             * `MATCH_PARENT`, а такой ребёнок в ширину колонки не идёт:
             * колонка брала её от значка, тридцать две точки, и уже по ней
             * растягивала подпись. «4,4 тыс.» в тридцать две точки не
             * влезает — отсюда многоточие под лайком и комментариями.
             */
            column.addView(
                count,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        holder.addView(column)
        holder.setPadding(dp(8f), dp(10f), dp(8f), dp(10f))

        holder.onTap = action

        return holder
    }

    // --- Лента ------------------------------------------------------------

    fun start() {
        running = true

        if (items.isNotEmpty()) {
            resume()

            return
        }

        seed?.let {
            items.add(it)

            sequence = it.shortsSequence
        }

        loadMore(true)
    }

    /**
     * Вернулись к листалке — забираем плеер обратно.
     *
     * Прежде возвращение не делало ничего: лента уже набрана, и `start`
     * выходил сразу. Поверхность при этом оставалась у того, кто забрал
     * её последним, — у страницы ролика или у окошка, — и наш ролик
     * рисовался туда.
     */
    private fun resume() {
        val id = items.getOrNull(at)?.videoId ?: return

        if (PlayerEngine.videoId == id) {
            PlayerEngine.attach(surface)
            PlayerEngine.play()

            return
        }

        // В плеере чужой ролик — поднимаем свой заново.
        show(at)
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

        /**
         * Прошлый ролик прерываем сразу, а не когда доедет новый.
         *
         * Поток за новым едет секунду-другую, и всё это время
         * поверхность показывала прошлый — идущий, со звуком, — а поверх
         * него крутилось кольцо. Со стороны это выглядело так, будто
         * листание не сработало.
         *
         * Кадр прячем заодно: превью лежит **под** поверхностью, и пока
         * та на виду, его не видно вовсе. Вернём, когда придёт первый
         * кадр нового ролика.
         */
        /**
         * Прошлый ролик бросаем целиком — и звук, и кадр.
         *
         * Одной паузы мало: в плеере он остаётся лежать, пока не приедет
         * поток нового, и, получив поверхность, плеер рисует в неё
         * прежний кадр. Отсюда и брался чужой кадр между превью и новым
         * роликом.
         */
        PlayerEngine.dropCurrent()

        /**
         * Превью держим до первого кадра, но не дольше нескольких секунд:
         * не пришёл — значит что-то не так, и застывшая картинка поверх
         * идущего ролика хуже, чем чёрный кадр.
         */
        removeCallbacks(revealLate)
        postDelayed(revealLate, 6000)

        titleLabel.text = item.title
        authorLabel.text = item.channelTitle ?: ""

        likeCount.text = ""
        commentCount.text = ""

        showDislikes(null)

        /**
         * Прежнее превью стираем, а не ждём, пока его затрёт новое.
         *
         * Картинка едет из сети, и до её приезда вид держит прошлую —
         * а она от прошлого ролика. Со стороны это тот же чужой кадр,
         * только превью вместо кадра.
         */
        cover.setImage(null)

        cover.visibility = VISIBLE

        ImageLoader.loadInto(cover, item.thumbnail, Metrics.points(width))

        ring.color = android.graphics.Color.WHITE
        ring.start()

        notice.visibility = GONE

        val videoId = item.videoId ?: return

        async {
            val ready = Api.shortsPlayback(videoId)

            main {
                /**
                 * Ушли с листалки, пока поток ехал, — не начинаем.
                 *
                 * Ответ мог опоздать и на смену ролика, и на уход
                 * со вкладки; первое ловит сверка с [at], второе —
                 * [running].
                 */
                if (at != index || !running) {
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
                disliked = ready.disliked

                ready.title?.let { titleLabel.text = it }
                ready.channelTitle?.let { authorLabel.text = it }

                ImageLoader.loadInto(shortAvatar, ready.channelThumbnail, 30f)

                likeCount.text = ready.likes ?: ""
                commentCount.text = ready.comments ?: ""

                loadDislikes(videoId, index)

                applyRating()

                /**
                 * Превью держим до первого кадра.
                 *
                 * Прежде его убирали здесь же, до запуска, — и в просвете
                 * до первого кадра виднелся прошлый ролик.
                 */
                PlayerEngine.attach(surface)
                PlayerEngine.open(videoId, null)
            }
        }

        // Догружаем ленту заранее — за три ролика до конца.
        if (index >= items.size - 3) {
            loadMore(false)
        }
    }

    /**
     * Переход к соседнему ролику — со сдвигом страниц, а не подменой.
     *
     * В iOS-версии листалка это `UIScrollView` с постраничной прокруткой:
     * каждая страница там своя, с собственным превью, и переход выходит
     * сам собой. Здесь страница одна, и содержимое у неё меняется
     * на месте, — поэтому уходящая снимается снимком и уезжает, пока
     * новая въезжает с той стороны, откуда её ждут.
     *
     * Снимок берётся двумя слоями: кадр у самой поверхности
     * (`TextureView` в холст не рисуется), подписи и кнопки — обычной
     * отрисовкой поверх.
     */
    private fun advance(index: Int) {
        if (index < 0 || index >= items.size || index == at) {
            return
        }

        val box = height

        if (box <= 0 || sliding) {
            show(index)

            return
        }

        val upwards = index > at

        ghostFrame.setImageBitmap(frameSnapshot())
        ghostChrome.setImageBitmap(chromeSnapshot())

        hidePeek()

        val from = content.translationY

        ghost.translationY = from
        ghost.visibility = VISIBLE

        show(index)

        /**
         * Новая страница встаёт вплотную к уходящей, а не к краю экрана.
         *
         * Палец мог увести ту на треть хода; начни новая от целого
         * экрана — между ними зияла бы щель в эту самую треть.
         */
        content.translationY = from + if (upwards) box.toFloat() else -box.toFloat()

        sliding = true

        content.animate()
            .translationY(0f)
            .setDuration(SLIDE_MS)
            .withEndAction { sliding = false }
            .start()

        ghost.animate()
            .translationY(if (upwards) -box.toFloat() else box.toFloat())
            .setDuration(SLIDE_MS)
            .withEndAction {
                ghost.visibility = GONE

                ghostFrame.setImageBitmap(null)
                ghostChrome.setImageBitmap(null)
            }
            .start()
    }

    /**
     * Ставит превью соседа в просвет по нынешней тяге.
     *
     * Сосед берётся по направлению: тянут вверх — следующий, вниз —
     * прежний. За краем ленты соседа нет, и просвет остаётся чёрным:
     * там и правда ничего нет.
     */
    private fun peekAt(shift: Float) {
        val box = height

        if (box <= 0 || shift == 0f) {
            hidePeek()

            return
        }

        val index = if (shift < 0) at + 1 else at - 1

        if (index < 0 || index >= items.size) {
            hidePeek()

            return
        }

        if (peekFor != index) {
            peekFor = index

            ImageLoader.loadInto(peek, items[index].thumbnail, Metrics.points(width))
        }

        peek.translationY = shift + if (shift < 0) box.toFloat() else -box.toFloat()
        peek.visibility = VISIBLE
    }

    private fun hidePeek() {
        peek.visibility = GONE
        peekFor = -1
    }

    /** Показывает кадр и убирает превью. */
    private fun revealFrame() {
        removeCallbacks(revealLate)

        cover.visibility = GONE
    }

    private val revealLate = Runnable {
        if (cover.visibility == VISIBLE) {
            revealFrame()
        }
    }

    /** Кадр уходящей страницы — у самой поверхности. */
    private fun frameSnapshot(): android.graphics.Bitmap? = try {
        if (surface.isAvailable) surface.bitmap else null
    } catch (error: Throwable) {
        null
    }

    /**
     * Подписи и кнопки уходящей страницы.
     *
     * Подложка у содержимого прозрачная, поэтому на месте кадра
     * в снимке остаётся пустота — сквозь неё виден нижний слой.
     */
    private fun chromeSnapshot(): android.graphics.Bitmap? {
        if (content.width <= 0 || content.height <= 0) {
            return null
        }

        return try {
            val shot = android.graphics.Bitmap.createBitmap(
                content.width, content.height, android.graphics.Bitmap.Config.ARGB_8888
            )

            content.draw(android.graphics.Canvas(shot))

            shot
        } catch (error: Throwable) {
            // Памяти не хватило — обойдёмся одним кадром.
            null
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

    /**
     * Страница идёт за пальцем, а не прыгает по отпусканию.
     *
     * В оригинале это делает постраничная прокрутка: содержимое едет
     * вместе с рукой, и по отпусканию либо доезжает до соседа, либо
     * возвращается на место. Здесь то же самое — сдвигом самого
     * содержимого.
     *
     * За край ленты не пускаем дальше четверти хода: тянуть в пустоту
     * можно, но она должна пружинить, иначе непонятно, что дальше
     * ничего нет.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (sliding) {
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = event.y

            MotionEvent.ACTION_MOVE -> {
                var shift = event.y - downY

                val edge = (shift < 0 && at + 1 >= items.size) ||
                    (shift > 0 && at <= 0)

                if (edge) {
                    shift /= 4f
                }

                content.translationY = shift

                peekAt(shift)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val shift = event.y - downY

                val threshold = height / 6f

                val wanted = when {
                    shift < -threshold && at + 1 < items.size -> at + 1
                    shift > threshold && at > 0 -> at - 1
                    else -> -1
                }

                if (wanted >= 0) {
                    /**
                     * Уходящая страница продолжает путь оттуда, где её
                     * оставил палец: [advance] снимает её нынешним
                     * сдвигом и доводит до края.
                     */
                    advance(wanted)

                    return true
                }

                if (kotlin.math.abs(shift) < dp(8f)) {
                    content.translationY = 0f

                    hidePeek()

                    PlayerEngine.togglePlay()

                    return true
                }

                // Не дотянули — возвращаем на место.
                sliding = true

                content.animate()
                    .translationY(0f)
                    .setDuration(SLIDE_MS)
                    .withEndAction {
                        sliding = false

                        hidePeek()
                    }
                    .start()

                peek.animate()
                    .translationY(
                        if (peek.translationY < 0) -height.toFloat() else height.toFloat()
                    )
                    .setDuration(SLIDE_MS)
                    .start()
            }
        }

        return true
    }

    // --- Действия ---------------------------------------------------------

    /**
     * Оценка: `like` или `dislike`; нажатие по уже поставленной снимает её.
     *
     * Лайк и дизлайк взаимно исключают друг друга, как у самого YouTube
     * и на странице ролика: поставив одно, снимаем другое.
     */
    private fun rate(want: String) {
        if (!Auth.isSignedIn()) {
            Toast.show(context, loc("Войдите в аккаунт"))

            return
        }

        val videoId = items.getOrNull(at)?.videoId ?: return

        val action = when {
            want == "like" && liked -> "none"
            want == "dislike" && disliked -> "none"
            else -> want
        }

        // Показываем сразу, не дожидаясь ответа: отказ вернёт как было.
        val wasLiked = liked
        val wasDisliked = disliked

        liked = action == "like"
        disliked = action == "dislike"

        applyRating()

        async {
            if (!Api.rate(videoId, action, null)) {
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
        likeIcon.setImageBitmap(Icons.darkIcon(if (liked) "pl_like_on" else "pl_like"))
        dislikeIcon.setImageBitmap(
            Icons.darkIcon(if (disliked) "pl_dislike_on" else "pl_dislike")
        )
    }

    /**
     * Число дизлайков под значком — или ничего.
     *
     * Пустая подпись всё равно занимает строку, и значок стоял бы
     * не по центру своей ячейки, а выше, над пустотой.
     */
    private fun showDislikes(count: Long?) {
        if (count == null) {
            dislikeCount.text = ""
            dislikeCount.visibility = View.GONE
        } else {
            dislikeCount.text = Counts.compact(count)
            dislikeCount.visibility = View.VISIBLE
        }
    }

    private fun loadDislikes(videoId: String, index: Int) {
        if (!Settings.showsDislikes) {
            return
        }

        async {
            val count = Dislikes.count(videoId)

            main {
                // Пролистнули дальше — число уже чужое.
                if (at != index || items.getOrNull(at)?.videoId != videoId) {
                    return@main
                }

                showDislikes(count)
            }
        }
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

    /**
     * Ролик доиграл: повторяем либо идём дальше — по настройке.
     *
     * Прежде это никто не звал вовсе: доигравший Shorts просто
     * останавливался на последнем кадре, и тумблер «Следующий Shorts»
     * в настройках не значил ничего.
     */
    fun playbackFinished() {
        if (!running) {
            return
        }

        val playing = items.getOrNull(at)?.videoId

        if (playing != null && playing == finishedId) {
            return
        }

        finishedId = playing

        /**
         * Дошли до хвоста — просим продолжение.
         *
         * Без этого повтор становится единственным ходом: лента не
         * растёт, листать некуда, и ролик идёт по кругу.
         */
        if (at + 1 >= items.size) {
            loadMore(false)
        }

        if (Settings.autoplayNextShort && at + 1 < items.size) {
            advance(at + 1)

            return
        }

        PlayerEngine.seekTo(0.0)
        PlayerEngine.play()
    }

    companion object {

        /** Сколько длится переход между роликами, мс. */
        private const val SLIDE_MS = 220L
    }

    /**
     * Уходим с листалки — замолкаем.
     *
     * Плеер глушим **только свой**. Он один на всё приложение, и когда
     * листалка уступает место, на нём уже может идти чужой ролик:
     * оповещение о смене стопки приходит и тогда, когда страницу ролика
     * открыли с другой вкладки. Глуши мы его наотмашь — открытый ролик
     * замолкал бы в тот же миг.
     */
    fun stop() {
        running = false

        val mine = items.getOrNull(at)?.videoId

        if (mine != null && PlayerEngine.videoId == mine) {
            PlayerEngine.pause()
            PlayerEngine.attach(null)
        }
    }
}

/** Вкладка Shorts в нижней панели. */
class ShortsSection(context: Context) : Shell.Section(context) {

    private lateinit var shorts: ShortsView

    private lateinit var root: FrameLayout

    override fun build(): ViewGroup {
        root = FrameLayout(context)

        shorts = ShortsView(context)

        root.addView(
            shorts,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Над вкладкой открыли экран или закрыли — наша очередь сменилась.
        Notify.on(Nav.NAV, this) { syncWithStack() }

        return root
    }

    /**
     * Листалка своя, только когда над ней ничего не открыто.
     *
     * Стопка экранов лежит **над** оболочкой, и вкладка под ней остаётся
     * в окне: ни `disappear`, ни `appear` ей при этом не приходит.
     * Поэтому смотрим на саму стопку — и по её пустоте решаем, наша
     * сейчас очередь или нет.
     */
    private fun syncWithStack() {
        val mine = root.visibility == View.VISIBLE && Nav.top() == null

        if (mine) {
            /**
             * Окошко мини-плеера на Shorts не место: плеер один на всё
             * приложение, и листалка сейчас же заберёт его себе.
             * Окошко осталось бы застывшим кадром с чужим названием.
             */
            Nav.closeMiniPlayer()

            shorts.start()
        } else {
            shorts.stop()
        }
    }

    override fun appear() {
        syncWithStack()
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

    /**
     * Поверх листалки открыли экран — она замолкает и отдаёт плеер.
     *
     * Без этого она продолжала слушать плеер из-под чужой страницы
     * и заводила следующий Shorts, когда там кончался свой ролик.
     */
    override fun disappear() {
        shorts.stop()
    }

    override fun appear() {
        Nav.closeMiniPlayer()

        shorts.start()
    }

    override fun destroy() {
        super.destroy()

        shorts.stop()

        PlayerEngine.release()
    }
}
