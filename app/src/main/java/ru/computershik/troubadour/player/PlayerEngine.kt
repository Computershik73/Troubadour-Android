package ru.computershik.troubadour.player

import android.view.TextureView
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultAllocator
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSourceFactory
import org.json.JSONObject
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Delivery
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.net.Api
import ru.computershik.troubadour.net.Http
import ru.computershik.troubadour.net.Json
import ru.computershik.troubadour.net.PlayerJs
import ru.computershik.troubadour.net.WebAuth
import ru.computershik.troubadour.net.androidVrPlayerResponse
import ru.computershik.troubadour.net.iosPlayerResponse
import ru.computershik.troubadour.net.isBotGate
import ru.computershik.troubadour.net.isUpcomingBroadcast
import ru.computershik.troubadour.net.offlineSlateTextIn
import ru.computershik.troubadour.net.scheduledStartIn
import ru.computershik.troubadour.net.mediaUserAgent
import ru.computershik.troubadour.net.notePendingWatchPosition
import ru.computershik.troubadour.net.playerResponse
import ru.computershik.troubadour.net.reportWatched
import ru.computershik.troubadour.net.setStreamUserAgent
import ru.computershik.troubadour.ui.async
import ru.computershik.troubadour.ui.main
import ru.computershik.troubadour.ui.mainAfter

/**
 * Воспроизведение: выбор потока, сам плеер и всё, что вокруг него.
 *
 * Вынесено из экрана нарочно. В оригинале `YTPlayerViewController` — это
 * семь тысяч строк, где разметка, пульт, меню, комментарии и сама
 * механика воспроизведения лежат вперемешку; причина понятна — там всё
 * это и правда один экран. Но переживает воспроизведение больше, чем
 * экран: свёрнутый в окно ролик играет, когда страницы уже нет в стопке,
 * а на Android к этому добавляется поворот, который экран пересоздаёт.
 *
 * Поэтому здесь живёт то, что не должно умирать вместе с видом, а сам
 * вид — в `PlayerScreen`.
 */
object PlayerEngine {

    /** Что случилось — экран об этом узнаёт оповещением. */
    const val STATE = "player-state"
    const val PROGRESS = "player-progress"
    const val FAILED = "player-failed"
    const val BOT_GATE = "player-gate"
    const val QUALITY = "player-quality"

    /** Пришёл первый кадр нового ролика — прежний «отпечаток» ушёл. */
    const val FIRST_FRAME = "player-first-frame"

    /** Стала известна пропорция кадра — подгонка её перечитывает. */
    const val VIDEO_SIZE = "player-video-size"

    /**
     * Трансляция объявлена, но ещё не началась.
     *
     * Значением идёт пара: час начала в секундах эпохи (ноль — не назван)
     * и слова сервера из заставки (может не быть).
     */
    const val UPCOMING = "player-upcoming"

    var player: SimpleExoPlayer? = null
        private set

    private var trackSelector: DefaultTrackSelector? = null

    /** Ролик, который играет сейчас. */
    var videoId: String? = null
        private set

    var playlistId: String? = null
        private set

    /** Ответ `/player` — из него берут субтитры, раскадровку и сигналы. */
    var playerJson: JSONObject? = null

    /**
     * Откуда брать адреса сигналов просмотра.
     *
     * Первый ответ `/player` — от имени выбранного канала. Запасные пути
     * (iOS для эфира, ANDROID_VR для готовых адресов) подменяют
     * [playerJson], а их сигналы подписаны на другого клиента и не на тот
     * канал: на iOS-версии просмотр тогда уходил мимо истории второго
     * канала.
     */
    private var trackingJson: JSONObject? = null
        private set

    /** Готовые дорожки; пусто — играем подачей. */
    var formats: List<Format> = emptyList()
        private set

    /** Ступени качества для меню. */
    var heights: List<Int> = emptyList()
        private set

    /** Подача, если играем ею. */
    var sabr: Sabr? = null
        private set

    /** Потолок качества и выбранная озвучка. */
    private var maxHeight = 0

    /**
     * Ступень, которую заказал человек; ноль — «Авто».
     *
     * Читается наружу нарочно: выбранное и играющее — разные вещи,
     * и меню обязано показывать оба. На подаче ступень назначает сервер,
     * и, попросив 1080p, легко смотреть 720p.
     */
    var pickedHeight = 0
        private set
    private var audioTrack: String? = null

    /** Ступень, которую собрали для проигрывания. */
    var readyHeight = 0
        private set

    /** Номер поколения: ответ от прежнего ролика свою ленту не дописывает. */
    private val generation = ru.computershik.troubadour.ui.Generation()

    /** Куда рисовать кадр. */
    private var surface: TextureView? = null

    /**
     * Отношение ширины кадра к его высоте, как его называет сам поток.
     *
     * Шестнадцать к девяти — не догадка, а разумное начало: пока дорожка
     * не разобрана, ставить что-то другое не из чего.
     */
    var videoRatio: Float = 16f / 9f
        private set

    // --- Сеть -------------------------------------------------------------

    /**
     * Источник данных для готовых адресов.
     *
     * Ходит **нашим** OkHttp — значит, с нашим TLS и нашими корнями.
     * Системный стек тут не годится ровно по той же причине, по какой
     * в оригинале не годился стек `AVPlayer`: на 4.1 нужных корней
     * в системе нет.
     *
     * User-Agent берётся у того клиента, чьим ответом добыты ссылки:
     * они подписаны под него (`c=ANDROID_VR` прямо в адресе), и раздача
     * сверяет, тем ли клиентом за ними пришли.
     */
    private fun httpFactory(): DataSource.Factory =
        OkHttpDataSourceFactory(Http.client, Api.mediaUserAgent(), PlaybackStats.Listener())

    // --- Жизнь плеера -----------------------------------------------------

    private fun ensurePlayer(): SimpleExoPlayer {
        player?.let { return it }

        val context = App.require()

        val selector = DefaultTrackSelector(context)

        /**
         * Буфер урезан против стандартного.
         *
         * По умолчанию ExoPlayer набирает до пятидесяти секунд вперёд.
         * На 1080p это под два десятка мегабайт в памяти, а у устройства,
         * ради которого всё затевалось, её четверть гигабайта на всё.
         * Оригинал держал двадцать четыре фрагмента видео и шестнадцать
         * звука — примерно те же две минуты, но там фрагменты выбрасывались
         * по мере просмотра.
         */
        val load = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 16 * 1024))

            /**
             * Начинаем играть с половины секунды набранного.
             *
             * Стояло полторы, и вместе с подачей это выходило долго:
             * фрагмент видео у неё около пяти секунд, звука около
             * десяти, и приходят они целиком. Пока набиралось полторы
             * секунды **обеих** дорожек, успевало прийти несколько
             * фрагментов — полоса набранного заметно уползала вперёд,
             * а картинка всё не начиналась.
             *
             * Полсекунды хватает: меньше одного фрагмента не придёт
             * никогда, так что порог упирается в первый же кусок.
             */
            .setBufferDurationsMs(10000, 30000, 500, 1500)

            /**
             * Потолок памяти, а не цель.
             *
             * Восемь мегабайт против прежних шестнадцати: на 1080p это
             * около сорока секунд, и держать больше незачем — подача
             * всё равно отдаёт куски по просьбе, а не потоком.
             */
            .setTargetBufferBytes(8 * 1024 * 1024)

            /**
             * Мерка буфера — время, а не байты.
             *
             * Восемь мегабайт остаются потолком памяти, но решает, грузить
             * ли дальше, набранное время. Иначе выходило так: после прыжка
             * плеер пересобирает источник, а память из-под прежнего ролика
             * ещё не отдана — восемь мегабайт заняты. Порог считался
             * достигнутым, загрузка не начиналась, буфер оставался пуст,
             * и плеер объявлял «стою и не гружу». Человек видел вечную
             * загрузку после перемотки.
             */
            .setPrioritizeTimeOverSizeThresholds(true)
            .createDefaultLoadControl()

        /**
         * Программное декодирование запрещено намеренно.
         *
         * `EXTENSION_RENDERER_MODE_OFF` оставляет только аппаратные
         * декодеры. Программный на слабом устройстве не играет, а ползёт,
         * и человеку это выглядит поломкой; лучше честно отказать
         * и предложить ступень пониже — так же, как оригинал предупреждал
         * о превышении потолка вместо того, чтобы молча дать звук
         * без картинки.
         */
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

            /**
             * Не завёлся один декодер — пробуем следующий.
             *
             * Изготовительский декодер иногда отказывается заводиться
             * на дорожке, которую сам же объявил себе по силам. В журнале
             * с устройства пользователя это выглядело так:
             *
             *     Ошибка: 1 — MediaCodecVideoRenderer error, format=…
             *     avc1.64001F, [1280, 720], format_supported=YES
             *     (Decoder failed: OMX.qcom.video.decoder.avc)
             *
             * Заметьте `format_supported=YES`: дорожка по всем признакам
             * подходит, отказал сам декодер. Без этого признака плеер
             * на такое сдаётся сразу и показывает ошибку во весь экран,
             * хотя рядом лежит программный декодер, который сыграл бы.
             */
            .setEnableDecoderFallback(true)

        val built = SimpleExoPlayer.Builder(context, renderers)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .build()

        /**
         * Звук объявляется как кино.
         *
         * То же, что делала `AVAudioSessionCategoryPlayback` вместе
         * с `AVAudioSessionModeMoviePlayback` в оригинале: ролик не глохнет
         * от бокового переключателя «без звука» и продолжает играть
         * в фоне, а система знает, что это кино, а не сигнал.
         *
         * Второй довод — фокус: с ним система сама приглушит нас, когда
         * придёт звонок, и вернёт громкость после. В оригинале это делала
         * звуковая сессия.
         */
        built.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(com.google.android.exoplayer2.C.CONTENT_TYPE_MOVIE)
                .setUsage(com.google.android.exoplayer2.C.USAGE_MEDIA)
                .build(),
            true
        )

        built.addListener(Listener())

        /**
         * Первый кадр — отдельным оповещением.
         *
         * `SurfaceView` держит последний нарисованный кадр, пока
         * не получит новый: при переходе к другому ролику на нём
         * оставался «отпечаток» прежнего — иногда на несколько секунд,
         * пока набирался буфер. Поверхность на это время прячется,
         * а возвращается вот по этому оповещению.
         */
        built.addVideoListener(object : com.google.android.exoplayer2.video.VideoListener {

            override fun onRenderedFirstFrame() {
                Notify.post(FIRST_FRAME)
            }

            /**
             * Размер кадра нужен подгонке: по нему кадр вписывается
             * в отведённое место и по нему же считается величина,
             * при которой полосы исчезают.
             *
             * Пропорцию берём с поправкой на неквадратный пиксель —
             * `pixelWidthHeightRatio`: у части дорожек он не единица,
             * и без него кадр вышел бы приплюснутым.
             */
            override fun onVideoSizeChanged(
                width: Int,
                height: Int,
                rotation: Int,
                pixelRatio: Float
            ) {
                if (width <= 0 || height <= 0) {
                    return
                }

                val ratio = width * (if (pixelRatio > 0) pixelRatio else 1f) / height

                videoRatio = if (rotation == 90 || rotation == 270) 1f / ratio else ratio

                Notify.post(VIDEO_SIZE, videoRatio)
            }
        })

        player = built
        trackSelector = selector

        surface?.let { built.setVideoTextureView(it) }

        return built
    }

    /**
     * Бросает то, что играло, вместе с кадром.
     *
     * Нужно там, где следом откроют **другой** ролик: [open] только
     * помечает поколение и уходит за потоком в фон, а в плеере всё это
     * время лежит прежний ролик. Отдай ему поверхность — он тут же
     * нарисует в неё прежний кадр и объявит «первый кадр», от которого
     * не отличить настоящий.
     *
     * Смене ступени и озвучки это не нужно: там ролик тот же, и гасить
     * его кадр ради перезапуска незачем, — поэтому зовётся отдельно,
     * а не изнутри [open].
     */
    fun dropCurrent() {
        val ready = player ?: return

        ready.stop(true)
        ready.clearVideoSurface()

        surface = null
    }

    /** Куда плеер рисует сейчас; null — никуда. */
    val attachedSurface: TextureView?
        get() = surface

    fun attach(view: TextureView?) {
        surface = view

        val ready = player ?: return

        if (view != null) {
            ready.setVideoTextureView(view)
        } else {
            ready.clearVideoSurface()
        }
    }

    // --- Загрузка ---------------------------------------------------------

    /**
     * Открывает ролик: спрашивает потоки и начинает играть.
     *
     * Порядок перенесён из `load` оригинала слово в слово, включая
     * все запасные пути — их там пять, и каждый появился после того,
     * как предыдущий однажды подвёл.
     */
    fun open(videoId: String, playlistId: String?, startAt: Double = 0.0) {
        this.videoId = videoId
        this.playlistId = playlistId

        // Засекаем, чтобы по журналу было видно, где уходят секунды.
        openedAt = System.currentTimeMillis()
        played = false

        val mark = generation.next()

        sabr = null
        formats = emptyList()
        heights = emptyList()
        playerJson = null
        trackingJson = null
        pendingNotedAt = 0L
        readyHeight = 0
        seekedTo = if (startAt > 0) startAt else -1.0

        startWatchReports()
        seekedAt = android.os.SystemClock.uptimeMillis()

        async { loadStreams(videoId, mark, startAt) }
    }

    private fun loadStreams(videoId: String, mark: Int, startAt: Double) {
        var player = Api.playerResponse(videoId)

        if (!generation.isCurrent(mark)) {
            return
        }

        playerJson = player

        // Адреса сигналов просмотра — из этого ответа, что бы ни было дальше.
        if (Json.obj(player, "playbackTracking") != null) {
            trackingJson = player
        }

        var ready = Streams.formatsFrom(player)

        val about = Json.obj(player, "videoDetails")

        val live = Json.bool(about, "isLive") || Json.bool(about, "isLiveNow")

        /**
         * Эфир играется плейлистом, и решается это до всего остального.
         *
         * У идущей трансляции нет ни конца, ни готового файла: сервер
         * дописывает её на ходу. Дорожки с адресами в ответе есть,
         * но играть их как обычные нельзя — плеер дочитывает до края
         * записанного и встаёт («Playback stuck buffering and not
         * loading»). Ровно это и видели: эфиры не игрались вовсе.
         *
         * Плейлист HLS сервер отдаёт не всякому клиенту: телевизору —
         * нет, iPhone — да. Раньше мы спрашивали только то, что уже
         * пришло, а пришло оно от телевизора, — и оставались ни с чем.
         */
        if (live) {
            Log.d {
                val keys = ArrayList<String>()

                Json.obj(player, "streamingData")?.keys()?.forEach { keys.add(it) }

                "[YouTube/Плеер] Эфир, в streamingData: " +
                    (if (keys.isEmpty()) "пусто" else keys.joinToString(", "))
            }

            var manifest = Json.text(Json.obj(player, "streamingData"), "hlsManifestUrl")

            if (manifest.isNullOrEmpty()) {
                Log.d { "[YouTube/Плеер] Эфир без плейлиста — спрашиваем iOS-клиент" }

                val ios = Api.iosPlayerResponse(videoId)

                val fromIos = Json.text(
                    Json.obj(ios, "streamingData"), "hlsManifestUrl"
                )

                if (!fromIos.isNullOrEmpty() && ios != null) {
                    manifest = fromIos
                    player = ios
                    playerJson = ios
                } else {
                    val about = Json.obj(ios, "playabilityStatus")

                    Log.d {
                        val streaming = Json.obj(ios, "streamingData")

                        val keys = ArrayList<String>()

                        streaming?.keys()?.forEach { keys.add(it) }

                        "[YouTube/Плеер] iOS: в streamingData — " +
                            (if (keys.isEmpty()) "пусто" else keys.joinToString(", "))
                    }

                    Log.d {
                        "[YouTube/Плеер] iOS-клиент: " + (
                            if (ios == null) {
                                "ответа нет"
                            } else {
                                "${Json.text(about, "status") ?: "?"}: " +
                                    "${Json.text(about, "reason") ?: "без причины"}"
                            }
                        )
                    }
                }
            }

            if (!generation.isCurrent(mark)) {
                return
            }

            if (!manifest.isNullOrEmpty()) {
                Log.d { "[YouTube/Плеер] Эфир: играем готовым плейлистом" }

                /**
                 * За кусками плейлиста идём тем же клиентом, что его
                 * выдал: адреса внутри подписаны под него.
                 */
                Api.setStreamUserAgent(Api.IOS_USER_AGENT, null)

                formats = emptyList()
                heights = emptyList()

                main { startHls(manifest, mark) }

                return
            }

            Log.d { "[YouTube/Плеер] Эфир, а плейлиста нет ни у кого — играем как обычное" }

            Log.d {
                val list = Streams.formatsFrom(player).take(4).joinToString("; ") { one ->
                    "itag ${one.itag} url=${if (one.url.isNullOrEmpty()) "нет" else "есть"} " +
                        "init=${one.initialRangeStart ?: "—"}..${one.initialRangeEnd ?: "—"} " +
                        "index=${one.indexRangeStart ?: "—"}..${one.indexRangeEnd ?: "—"}"
                }

                "[YouTube/Плеер] Эфир, дорожки: $list"
            }
        }

        val wantsSabr = Settings.delivery == Delivery.SABR

        val sabrOffered = Json.text(
            Json.obj(player, "streamingData"), "serverAbrStreamingUrl"
        ) != null

        if (ready.isNotEmpty() && wantsSabr && sabrOffered) {
            Log.d {
                "[YouTube/Плеер] Готовых адресов ${ready.size}, но настройка просит подачу"
            }
        }

        // --- Подача -------------------------------------------------------

        if (ready.isEmpty() || (wantsSabr && sabrOffered)) {
            if (!generation.isCurrent(mark)) {
                return
            }

            val stream = Streams.sabrFor(
                player ?: return, maxHeight, audioTrack, pickedHeight > 0
            )

            if (!generation.isCurrent(mark)) {
                return
            }

            if (stream != null) {
                Log.d { "[YouTube/Плеер] Играем через подачу SABR" }

                sabr = stream
                formats = emptyList()
                heights = Streams.sabrHeights()

                /**
                 * Эфир открываем у живого края, а не с нуля.
                 *
                 * Разметка трансляции ведётся от начала вещания, и внутри
                 * кусков время абсолютное: у канала, идущего третью
                 * неделю, это миллион секунд с лишним. Просить источник
                 * начать «с нуля» бессмысленно — такого куска нет
                 * и не будет, и плеер встаёт навсегда.
                 */
                val from = if (stream.liveMode && stream.liveStartSeconds > 0) {
                    /**
                     * Отступаем от живого края на несколько кусков.
                     *
                     * У самого края запаса нет и быть не может: куска,
                     * который снимут через секунду, ещё не существует,
                     * и любая заминка сети или сервера тут же становится
                     * рывком — плеер играет ровно то, что успело прийти.
                     *
                     * Так делают все живые плееры, и в правилах HLS это
                     * прямо предписано: начинать не ближе трёх кусков
                     * к краю. Пятнадцать секунд назад — это три куска
                     * по пять; сервер их отдаёт сразу, они и становятся
                     * запасом.
                     */
                    val behind = maxOf(0.0, stream.liveStartSeconds - LIVE_BEHIND)

                    stream.rewindTo(behind)

                    /**
                     * Ждём, пока запас доедет, и лишь потом заводим плеер.
                     *
                     * Иначе выходит гонка: плеер уже играет, а куски,
                     * которые мы отступили назад просить, ещё в пути.
                     * Через пять секунд он объявляет затор, восстановление
                     * дёргает показ вперёд — и так по кругу, пока
                     * трансляция не уедет от нас совсем.
                     */
                    for (wait in 0 until 8) {
                        if (stream.videoSequences().any { one ->
                                val start = stream.videoSegmentStart(one)

                                start > 0 && start <= behind + 0.5
                            }
                        ) {
                            break
                        }

                        stream.requestMoreFrom(behind)
                    }

                    behind
                } else {
                    startAt
                }

                main { start(SabrSource.build(stream, from), mark, from) }

                return
            }

            if (wantsSabr && ready.isEmpty()) {
                Log.d { "[YouTube/Плеер] Подача не задалась — берём готовые адреса" }

                /**
                 * Сборку плеера забываем: у неё своя метка подписи
                 * и своя расшифровка `n`, а YouTube выкатывает новую
                 * когда захочет — случалось и трижды за день.
                 */
                PlayerJs.forgetPlayerId()

                val plain = Api.androidVrPlayerResponse(videoId)
                val plainFormats = Streams.formatsFrom(plain)

                if (plainFormats.isNotEmpty()) {
                    player = plain
                    ready = plainFormats

                    playerJson = plain
                }
            } else if (wantsSabr) {
                Log.d {
                    "[YouTube/Плеер] Подача не задалась — играем готовыми " +
                        "адресами, они уже есть"
                }
            }
        }

        // --- Готовых дорожек нет ------------------------------------------

        if (ready.isEmpty()) {
            val progressive = Streams.progressiveUrlIn(player)

            if (!progressive.isNullOrEmpty()) {
                if (!generation.isCurrent(mark)) {
                    return
                }

                Log.d { "[YouTube/Плеер] Играем склеенный поток" }

                formats = emptyList()
                heights = emptyList()

                main { startProgressive(progressive, mark, startAt) }

                return
            }

            val manifest = Json.text(Json.obj(player, "streamingData"), "hlsManifestUrl")

            if (!manifest.isNullOrEmpty()) {
                Log.d { "[YouTube/Плеер] Играем готовый HLS" }

                formats = emptyList()
                heights = emptyList()

                main { startHls(manifest, mark) }

                return
            }

            /**
             * Ждём по признаку, а не по найденному часу.
             *
             * Час начала лежит у разных клиентов в разных местах, и когда
             * его не нашлось, человек видел «Не удалось получить поток» —
             * будто приложение сломалось, хотя трансляция просто ещё
             * не началась. Признак же однозначен: `LIVE_STREAM_OFFLINE`.
             * Нет часа — покажем то, что сказал сам сервер, а нет и
             * этого — хотя бы честное «ещё не началась».
             */
            val scheduled = Api.scheduledStartIn(player)

            if (scheduled > 0 || Api.isUpcomingBroadcast(player)) {
                val said = Api.offlineSlateTextIn(player)

                main {
                    Notify.post(UPCOMING, Pair(scheduled, said))
                }

                return
            }

            val gate = Api.isBotGate(player)

            main {
                if (gate) {
                    Notify.post(BOT_GATE, WebAuth.isSignedIn())
                } else {
                    Notify.post(FAILED, ru.computershik.troubadour.loc("Не удалось получить поток"))
                }
            }

            return
        }

        // --- Раздельные дорожки -------------------------------------------

        formats = ready
        heights = Streams.heightsIn(ready)

        val video = Streams.chooseVideo(ready, maxHeight)
        val audio = Streams.chooseAudio(ready, audioTrack)

        readyHeight = video?.qualityTier() ?: 0

        if (video == null) {
            Log.d {
                "[YouTube/Плеер] Подходящей дорожки нет: потолок ${maxHeight}p, " +
                    "дорожек ${ready.size}"
            }

            val progressive = Streams.progressiveUrlIn(player)

            if (!progressive.isNullOrEmpty()) {
                if (!generation.isCurrent(mark)) {
                    return
                }

                Log.d { "[YouTube/Плеер] Играем склеенный поток" }

                formats = emptyList()
                heights = emptyList()

                main { startProgressive(progressive, mark, startAt) }

                return
            }

            main {
                Notify.post(FAILED, ru.computershik.troubadour.loc("Подходящей дорожки нет"))
            }

            return
        }

        if (audio == null) {
            Log.d { "[YouTube/Плеер] Звуковой дорожки нет" }
        }

        Log.d {
            "[YouTube/Плеер] Выбрано: видео itag ${video.itag} (${video.height}p), " +
                "звук itag ${audio?.itag ?: 0}"
        }

        if (!generation.isCurrent(mark)) {
            return
        }

        val source = SabrSource.build(httpFactory(), video.url, audio?.url)

        main {
            if (source == null) {
                Notify.post(FAILED, ru.computershik.troubadour.loc("Поток не собрался"))

                return@main
            }

            start(source, mark, startAt)
        }
    }

    private fun startHls(url: String, mark: Int) {
        /**
         * Готовый плейлист играет сам ExoPlayer — своим модулем HLS.
         *
         * Ради этого одного хода в сборку и включён `exoplayer-hls`.
         * Разбирать там нечего, качеством распоряжается плеер; хуже,
         * чем своим отбором дорожек, только тем, что выбор высоты уходит
         * из наших рук, — зато играет там, где остальное упёрлось
         * в анти-бота.
         */
        val source = com.google.android.exoplayer2.source.hls.HlsMediaSource
            .Factory(httpFactory())
            .createMediaSource(android.net.Uri.parse(url))

        start(source, mark, 0.0)
    }

    private fun startProgressive(url: String, mark: Int, startAt: Double) {
        val source = com.google.android.exoplayer2.source.ProgressiveMediaSource
            .Factory(httpFactory())
            .createMediaSource(android.net.Uri.parse(url))

        start(source, mark, startAt)
    }

    private fun start(source: MediaSource, mark: Int, startAt: Double) {
        if (!generation.isCurrent(mark)) {
            return
        }

        val ready = ensurePlayer()

        ready.setMediaSource(source)
        ready.prepare()

        /**
         * Эфиру начальное место задаём, и это важнее, чем кажется.
         *
         * Плеер начинает ленту с нуля, а метки времени внутри кусков
         * трансляции идут от начала вещания — у канала, идущего третью
         * неделю, это полтора миллиарда миллисекунд. Разница между
         * «набрано» и «играем» получается астрономическая, плеер
         * считает буфер переполненным и перестаёт грузить — и тут же,
         * не имея ни кадра, объявляет «стою и не гружу». Отсюда были
         * все пересборки: каждая начинала то же самое заново.
         *
         * Сказав ему, откуда мы начинаем, приводим обе мерки к одной
         * точке отсчёта.
         *
         * У записи на подаче прыгать по-прежнему незачем: там начало
         * задаёт сам источник, и лента у него от нуля.
         */
        if (startAt > 0 && (sabr == null || sabr?.liveMode == true)) {
            ready.seekTo((startAt * 1000).toLong())
        }

        ready.playWhenReady = true

        startTicker()

        Notify.post(STATE)
    }

    // --- Перемотка --------------------------------------------------------

    /**
     * Прыжок по ролику.
     *
     * У готовых адресов это обычный `seekTo`. У подачи байтового поиска
     * нет вовсе: фрагменты приходят по мере просьбы, и смещения у них
     * не существует. Поэтому источник **пересобирается** с новым началом —
     * ровно то же самое делал оригинал, переписывая плейлист HLS.
     */
    fun seekTo(seconds: Double) {
        val ready = player ?: return

        // Место теперь это — до тех пор, пока плеер его не подхватит.
        seekedTo = seconds
        seekedAt = android.os.SystemClock.uptimeMillis()

        val stream = sabr

        if (stream == null) {
            ready.seekTo((seconds * 1000).toLong())

            return
        }

        /**
         * Вперёд по набранному прыгаем без пересборки.
         *
         * Пересборка выбрасывает всё набранное и заводит обе дорожки
         * заново — это секунды. Если же цель уже лежит в буфере (а при
         * шаге вперёд на пятнадцать секунд она там почти всегда), плееру
         * довольно обычного прыжка: он идёт внутри памяти и стоит
         * миллисекунд.
         *
         * Назад так нельзя: пройденное ExoPlayer из буфера выбрасывает,
         * и позади нас пусто.
         */
        val at = ready.currentPosition / 1000.0
        val filled = ready.bufferedPosition / 1000.0

        if (seconds > at && seconds <= filled - 0.5) {
            Log.d {
                "[YouTube/Плеер] Прыжок на ${seconds.toInt()} с — внутри набранного " +
                    "(до ${filled.toInt()} с)"
            }

            ready.seekTo((seconds * 1000).toLong())

            return
        }

        Log.d { "[YouTube/Плеер] Прыжок на ${seconds.toInt()} с — пересобираем подачу" }

        /**
         * Отложенное сведение от прежнего прыжка отменяем.
         *
         * Оно метит в старое место и в старый источник; сработав уже
         * после того, как источник заменён, оно просит плеер прыгнуть
         * туда, где по новой ленте ничего нет. Плеер отвечает на это
         * «Unexpected runtime error» — ровно то, что появилось в журнале
         * на втором прыжке подряд.
         */
        alignTo = -1.0

        /**
         * Перечень набранного сбрасывается вместе с прыжком.
         *
         * Сервер шлёт только то, чего у нас, по его сведениям, ещё нет,
         * а сведения эти мы сами ему и сообщаем. Не сбросив их, на просьбу
         * прислать кусок с начала он ответит пустотой — совершенно
         * правомерно.
         */
        stream.rewindTo(seconds)

        /**
         * Начальное место плееру **не** задаём, хотя соблазн есть.
         *
         * Задать его — значит попросить прыжок по ленте, а лента здесь
         * не ищется: длина потока неизвестна, разметки для поиска нет.
         * Плеер на такую просьбу перезаряжает источник целиком, прыжок
         * растягивается на секунды, а место так и остаётся у начала —
         * и часы, которым велено показывать цель, пока плеер её не
         * достигнет, замирают на ней навсегда.
         *
         * Начало задаётся тем, какие куски мы отдадим, а не просьбой
         * к плееру.
         */
        ready.setMediaSource(SabrSource.build(stream, seconds))
        ready.prepare()

        // Свести дорожки к заказанному мигу — но позже, по готовности буфера.
        alignTo = seconds

        /**
         * Смещения ленте не нужно, и это стоило дорого.
         *
         * Считалось, что пересобранный источник начинается с нуля, а по
         * ролику мы стоим на `seconds`, — и `seconds` прибавлялись
         * к времени плеера. На деле фрагменты fMP4, которые шлёт подача,
         * несут в себе **своё** время (`baseMediaDecodeTime`), и лента
         * у плеера начинается сразу с него. Прибавка ложилась поверх
         * настоящего времени, и после прыжка на 342-ю секунду часы
         * показывали 684-ю — ровно вдвое.
         *
         * Отсюда же тянулось и остальное: проверка «доехали ли до цели»
         * не сходилась никогда, потому что место убегало вдвое быстрее
         * цели, — и часы оставались приколоты к цели, а пропуск вставок
         * искал их не там.
         */
        ready.playWhenReady = true
    }

    /**
     * Куда сводить дорожки, когда наберётся буфер; −1, когда сводить нечего.
     */
    private var alignTo = -1.0

    /**
     * Подрезает начало, когда данные уже в буфере.
     *
     * На iOS это делал свой ремуксер: границы задавало видео, а звук
     * резался **по кадрам** — «сегменту достаются все фрагменты, которые
     * с ним пересекаются, а лишнее отсекается уже по отдельным кадрам».
     * Здесь контейнер собирает ExoPlayer, и резать нам нечего: куски
     * отдаются целиком, а они у дорожек разной длины — видео по пять
     * секунд, звук почти по десять. Начинается лента с самого раннего
     * куска, и до первого кадра видео проходит несколько секунд.
     *
     * Подрезать умеет сам плеер, и просить его об этом надо **после**
     * того, как данные пришли. Просьба до того — то, на чём я обжёгся:
     * прыгать по ленте, которой ещё нет, он не может, перезаряжает
     * источник целиком, и выходит долгая перемотка с замершими часами.
     * По готовности же прыжок идёт внутри набранного и стоит миллисекунд.
     */
    private fun alignAfterSeek() {
        val target = alignTo

        if (target < 0) {
            return
        }

        // Снимаем до прыжка: он сам вызовет смену состояния, и мы вернёмся сюда.
        alignTo = -1.0

        val ready = player ?: return

        val at = ready.currentPosition / 1000.0

        // Уже там — тревожить нечего.
        if (Math.abs(at - target) < 0.5) {
            return
        }

        /**
         * Сводим только внутри набранного.
         *
         * Прыжок за край буфера плеер исполнить не может — лента
         * не ищется, — и полезет перезаряжать источник: долго, да ещё
         * и с пустым местом там, где мы уже играли.
         */
        val filled = ready.bufferedPosition / 1000.0

        if (target > filled) {
            Log.d {
                "[YouTube/Плеер] Сводить рано: набрано до ${filled.toInt()} с, " +
                    "нужно ${target.toInt()} с"
            }

            return
        }

        Log.d {
            "[YouTube/Плеер] Сводим дорожки: ${at.toInt()} → ${target.toInt()} с"
        }

        try {
            ready.seekTo((target * 1000).toLong())
        } catch (error: Throwable) {
            // Не свелось — ролик всё равно играет, только начало вразнобой.
            Log.d { "[YouTube/Плеер] Свести не вышло: ${error.message}" }
        }
    }

    /**
     * Куда прыгнули последним; −1, когда плеер это место уже подхватил.
     *
     * Пересобранный источник отвечает нулём, пока не придут первые кадры,
     * — секунду-полторы. Всё это время место у плеера спрашивать нельзя:
     * кнопка «назад на 5» считала от нуля, получала −5, обрезала до нуля
     * и отправляла ролик в начало. В журнале это выглядело как три
     * «Прыжка на 0 с» подряд после трёх быстрых нажатий, а на экране —
     * как ролик, начавшийся сызнова. Часы по той же причине показывали
     * цель, до которой место не доходило, и замирали.
     */
    private var seekedTo = -1.0

    /** Когда место было заказано — чтобы держать его не дольше срока. */
    private var seekedAt = 0L

    /** Где мы по ролику, секунды. */
    fun position(): Double {
        val ready = player ?: return 0.0

        val at = ready.currentPosition / 1000.0
        val wanted = seekedTo

        if (wanted >= 0) {
            /**
             * Заказанное место держится, пока плеер его не подхватит,
             * но не дольше срока — и вот почему срок обязателен.
             *
             * Попадание при перемотке идёт по границам отрезков, и плеер
             * сплошь и рядом встаёт **раньше** заказа. Тогда условие
             * «подошёл ближе секунды» не размыкалось вовсе, и часы
             * стояли на цели, пока воспроизведение само до неё не
             * доползёт, — со стороны это выглядело как замерший ползунок
             * при том, что ролик спокойно играет. Срок в две с половиной
             * секунды покрывает пересборку источника, ради которой
             * удержание и заводилось.
             */
            val held = android.os.SystemClock.uptimeMillis() - seekedAt

            if (at < wanted - 1.0 && held < HOLD_MS) {
                return wanted
            }

            seekedTo = -1.0
        }

        return at
    }

    /** Сколько длится ролик, секунды. */
    fun duration(): Double {
        sabr?.let {
            if (it.duration > 0) {
                return it.duration
            }
        }

        val ready = player ?: return 0.0

        val known = ready.duration

        if (known == com.google.android.exoplayer2.C.TIME_UNSET) {
            return Streams.lengthIn(playerJson)
        }

        return known / 1000.0
    }

    /** Докуда набрано, секунды. */
    fun buffered(): Double {
        sabr?.let { return it.bufferedSeconds() }

        val ready = player ?: return 0.0

        return ready.bufferedPosition / 1000.0
    }

    // --- Управление -------------------------------------------------------

    val isPlaying: Boolean
        get() = player?.playWhenReady == true

    /**
     * Идёт ли показ прямо сейчас — в том смысле, в каком это важно
     * экрану: пока идёт, гасить его нельзя.
     *
     * Одного `playWhenReady` мало. Он остаётся поднятым и после конца
     * ролика, и у плеера, который ещё ничего не открыл, — а держать
     * экран разбуженным ради доигравшего ролика незачем. Поэтому
     * спрашиваем ещё и состояние: годятся только «играю» и «набираю».
     */
    val holdsScreen: Boolean
        get() {
            val ready = player ?: return false

            if (!ready.playWhenReady) {
                return false
            }

            val state = ready.playbackState

            return state == Player.STATE_READY || state == Player.STATE_BUFFERING
        }

    /**
     * Ждёт ли плеер сейчас данных.
     *
     * Спрашивается тогда, когда о смене состояния уже не узнать: страницу
     * могли закрыть посреди набора и открыть заново, когда ролик давно
     * пошёл. Оповещение о состоянии к тому времени прозвучало и второй
     * раз не прозвучит, а кольцо ожидания на странице так и осталось бы
     * крутиться.
     */
    val isBuffering: Boolean
        get() = player?.playbackState == Player.STATE_BUFFERING

    fun togglePlay() {
        val ready = player ?: return

        ready.playWhenReady = !ready.playWhenReady

        Notify.post(STATE)
    }

    fun pause() {
        player?.playWhenReady = false

        Notify.post(STATE)
    }

    fun play() {
        player?.playWhenReady = true

        Notify.post(STATE)
    }

    /**
     * Скорость воспроизведения.
     *
     * Здесь стоит вспомнить, чем это оборачивалось в оригинале. `AVPlayer`
     * объявлял `canPlayFastForward = NO` для собранного нами потока
     * и **не просто не разгонялся, а останавливался**: `setRate:2.0`
     * выбирал ближайшее, что умеет, и ближайшим оказывался ноль. Кнопка
     * воспроизведения переставала отвечать, и выглядело это поломкой
     * на ровном месте.
     *
     * Отсюда правило, вынесенное в записку о переносе: **проверять сразу
     * после установки**. Строка «заказано 2.00, плеер показывает 1.00»
     * решила вопрос, на который до того ушло полдня догадок. Здесь
     * `PlaybackParameters` спрашиваются обратно ровно затем же.
     */
    fun setRate(rate: Float) {
        val ready = player ?: return

        ready.setPlaybackParameters(PlaybackParameters(rate))

        val got = ready.playbackParameters.speed

        Log.d { "[YouTube/Плеер] Скорость: заказано $rate, плеер показывает $got" }
    }

    fun rate(): Float = player?.playbackParameters?.speed ?: 1.0f

    // --- Качество и озвучка -----------------------------------------------

    /**
     * Ступень, которая играет прямо сейчас.
     *
     * На подаче качество назначаем не мы: мы лишь перечисляем, что нам
     * подходит. Поэтому спрашивать надо у ответа, а не у своих пожеланий —
     * иначе меню показывает желаемое.
     */
    fun playingHeight(): Int {
        if (sabr != null) {
            val fromSabr = Streams.sabrPlayingHeight()

            if (fromSabr > 0) {
                return fromSabr
            }
        }

        val format = player?.videoFormat

        if (format != null && format.height > 0) {
            return Streams.canonicalTier(minOf(format.width, format.height))
        }

        return readyHeight
    }

    /** Меняет ступень: ролик перезапускается с того же места. */
    fun pickHeight(height: Int) {
        val id = videoId ?: return
        val at = position()

        pickedHeight = height
        maxHeight = height

        Log.d { "[YouTube/Плеер] Ступень ${height}p — перезапуск с ${at.toInt()} с" }

        open(id, playlistId, at)

        Notify.post(QUALITY)
    }

    /** Меняет озвучку: тем же способом, что и ступень. */
    fun pickAudioTrack(identifier: String?) {
        val id = videoId ?: return
        val at = position()

        audioTrack = identifier

        open(id, playlistId, at)
    }

    /** Озвучки — у подачи свои, у готовых дорожек свои. */
    fun audioTracks(): List<AudioTrack> {
        if (sabr != null) {
            return Streams.sabrAudioTracks()
        }

        return Streams.audioTracksIn(formats)
    }

    // --- Ход воспроизведения ----------------------------------------------

    private var ticking = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) {
                return
            }

            Notify.post(PROGRESS)

            reportIfDue()
            notePendingPosition()
            forgetOldSegments()

            mainAfter(250) { run() }
        }
    }

    private fun startTicker() {
        if (ticking) {
            return
        }

        ticking = true

        main { ticker.run() }
    }

    fun stopTicker() {
        ticking = false
    }

    /**
     * Запись просмотра ведётся отрезками, пока ролик идёт.
     *
     * Отдельного запроса «добавить в историю» у InnerTube нет вовсе:
     * YouTube считает просмотр по служебным сигналам, адреса для которых
     * лежат в самом ответе `/player`. Настоящий TV-клиент шлёт их
     * непрерывно: первые три отметки через десять секунд, дальше через
     * сорок, и конец каждого отрезка становится началом следующего.
     * Реже нельзя: оборвись показ между отметками, потерянным окажется
     * весь промежуток.
     */
    private var reportedAt = 0L
    private var watchSegmentFrom = -1.0
    private var watchSegmentAt = 0L
    private var watchPings = 0

    private fun reportIfDue() {
        val now = System.currentTimeMillis()

        // Первые три отрезка — по десять секунд, дальше по сорок.
        val every = if (watchPings < 3) 10000L else 40000L

        if (reportedAt > 0 && now - reportedAt < every) {
            return
        }

        reportedAt = now
        watchPings += 1

        sendWatchSegment(false)
    }

    private var pendingNotedAt = 0L

    /** Раз в пять секунд — место показа в незакрытую запись просмотра. */
    private fun notePendingPosition() {
        val now = System.currentTimeMillis()

        if (now - pendingNotedAt < 5000L) {
            return
        }

        pendingNotedAt = now

        val id = videoId ?: return
        val at = position()

        if (at > 0) {
            async { Api.notePendingWatchPosition(at, id) }
        }
    }

    /** Отрезок от прошлой отметки до нынешнего места показа. */
    private fun sendWatchSegment(final: Boolean) {
        val json = trackingJson ?: playerJson ?: return

        var at = position()

        if (at <= 0) {
            at = maxOf(watchSegmentFrom, 0.0)
        }

        val now = System.currentTimeMillis()

        val spent = if (watchSegmentAt > 0) {
            maxOf(0.0, (now - watchSegmentAt) / 1000.0)
        } else {
            0.0
        }

        val from = watchSegmentFrom

        // Отрезок короче полусекунды не отмечаем — кроме последнего.
        if (!final && from >= 0 && at <= from + 0.5) {
            return
        }

        watchSegmentFrom = at
        watchSegmentAt = now

        async { Api.reportWatched(json, at, from, spent, final) }
    }

    /** Новый показ — запись начинается сначала. */
    private fun startWatchReports() {
        reportedAt = 0
        watchSegmentFrom = -1.0
        watchSegmentAt = System.currentTimeMillis()
        watchPings = 0
    }

    /**
     * Выбрасывает фрагменты, которые уже проиграны.
     *
     * Держатся они в памяти, пока их не выбросят, а весят мегабайтами:
     * на 1080p — по два с половиной на каждые пять с половиной секунд.
     * За минуту просмотра набирается столько, что устройству с четвертью
     * гигабайта памяти становится нечем дышать.
     */
    private fun forgetOldSegments() {
        val stream = sabr ?: return

        // Десять секунд назад — запас на перемотку пальцем назад.
        stream.forgetBefore(position() - 10.0)
    }

    // --- Сторож зависания -------------------------------------------------

    /**
     * Плеер встал, а мы не знаем почему.
     *
     * В оригинале сторож нужен был потому, что `AVPlayer` умел замереть
     * молча: ни ошибки, ни смены состояния. Здесь ExoPlayer об этом
     * сообщает сам сменой на `STATE_BUFFERING`, и сторож остался ради
     * другого — подачи. Она может перестать давать куски, не сказав
     * ни слова: ровно так кончалась сессия ANDROID_VR на шестидесятой
     * секунде.
     */
    private var stalledSince = 0L

    private fun watchStall(state: Int) {
        if (state != Player.STATE_BUFFERING) {
            stalledSince = 0

            return
        }

        if (stalledSince == 0L) {
            stalledSince = System.currentTimeMillis()

            return
        }

        if (System.currentTimeMillis() - stalledSince < 15000) {
            return
        }

        stalledSince = 0

        val stream = sabr ?: return

        Log.d { "[YouTube/Плеер] Пятнадцать секунд без движения — обновляем подачу" }

        async {
            if (Streams.renewSabr(stream)) {
                main { seekTo(position()) }
            }
        }
    }

    // --- Слушатель --------------------------------------------------------

    private class Listener : Player.EventListener {

        override fun onPlaybackStateChanged(state: Int) {
            watchStall(state)

            if (state == Player.STATE_READY) {
                if (!played) {
                    played = true

                    Log.d {
                        "[YouTube/Плеер] Первый кадр через " +
                            "${System.currentTimeMillis() - openedAt} мс после открытия"
                    }
                }

                // Пошло — прежние заторы больше не в счёт.
                recoveries = 0
                descents = 0

                alignAfterSeek()
            }

            Notify.post(STATE, state)

            if (state == Player.STATE_ENDED) {
                // Ролик доигран — закрываем запись просмотра последним отрезком.
                sendWatchSegment(true)

                Notify.post(STATE, state)
            }
        }

        override fun onPlayerError(error: ExoPlaybackException) {
            Log.now {
                "[YouTube/Плеер] Ошибка: ${error.type} — ${error.message}" +
                    (error.cause?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")
            }

            /**
             * Отказ 403 посреди просмотра — почти всегда сменившийся
             * выход в сеть.
             *
             * Адрес раздачи подписан вместе с адресом просителя, и через
             * VPN он у нас меняется от запроса к запросу. Отличить это
             * от прочих бед можно только спросив, каким нас видит Google
             * сейчас, — что и делает `probeSeenIp`. Стоит это одного
             * небольшого запроса, поэтому зовётся только после отказа.
             */
            /**
             * Затор при перемотке лечится сам — не показывая отказа.
             *
             * `Playback stuck buffering and not loading` означает, что
             * плеер остался с пустым буфером и без загрузки. При быстрой
             * перемотке по подаче это случается: источник успевает
             * кончиться раньше, чем придут первые куски. Ролик при этом
             * цел, адреса целы, сеть цела — и показывать во весь экран
             * «Не удалось получить поток» здесь не за что.
             *
             * Поэтому один раз пробуем собрать источник заново с того же
             * места. Не вышло второй раз подряд — тогда уже отказ:
             * бесконечно перезаряжаться хуже, чем честно сказать.
             */
            /**
             * Декодер не осилил дорожку — спускаемся ступенью ниже.
             *
             * Так это выглядело у пользователя: планшет объявляет потолок
             * 1080p, ролик предлагает 1080p при шестидесяти кадрах,
             * а декодер отвечает `NO_EXCEEDS_CAPABILITIES`. Плеер
             * показывал «Не удалось получить поток», человек жал
             * «Повторить» — и всё повторялось с той же ступени, по кругу.
             *
             * Причину мы чиним отдельно, потолком для шестидесяти кадров,
             * но правило нужно и само по себе: декодеры отказывают
             * и по другим поводам, а ступенью ниже почти всегда играет.
             */
            if (error.type == ExoPlaybackException.TYPE_RENDERER && stepDown()) {
                return
            }

            if (error.type == ExoPlaybackException.TYPE_UNEXPECTED &&
                sabr != null && recoveries < RECOVERIES
            ) {
                recoveries++

                /**
                 * У эфира возвращаемся к живому краю, а не к тому месту,
                 * где встали.
                 *
                 * Пока плеер стоял, трансляция ушла вперёд, и просить
                 * прежнее место бесполезно: сервер прошлое не повторяет,
                 * а мы получаем пустые ответы и встаём снова — тот же
                 * затор по кругу, раз в полторы секунды.
                 */
                val live = sabr?.takeIf { it.liveMode }

                val at = if (live != null) {
                    live.liveEdgeSeconds()
                } else if (seekedTo >= 0) {
                    seekedTo
                } else {
                    position()
                }

                /**
                 * Затор у самого конца ролика — это не беда, а конец.
                 *
                 * Прыжок в последний фрагмент оставляет плееру секунду-две
                 * картинки и столько же звука. Этого ему мало, чтобы
                 * начать: он объявляет «стою и не гружу», мы пересобираем
                 * источник с того же места, где снова полторы секунды, —
                 * и так восемь раз подряд, пока человек не увидит
                 * «Не удалось получить поток». В журнале это выглядело
                 * как ровный круг: прыжок на 199 с из 201, привязка
                 * к последнему фрагменту, ошибка через четверть секунды.
                 *
                 * Ролик, домотанный до конца, и должен кончиться —
                 * поэтому объявляем его доигранным, как при обычном
                 * завершении: сработает автопереход или кнопка «Смотреть
                 * снова», а не окно с отказом.
                 *
                 * Одну пересборку всё же пробуем: у короткого ролика
                 * «за три секунды до конца» — это середина, и обрывать
                 * его сразу было бы неверно.
                 */
                val total = duration()

                if (recoveries > 1 && total > 0 && at >= total - TAIL) {
                    Log.now {
                        "[YouTube/Плеер] Затор на ${at.toInt()} с при длине " +
                            "${total.toInt()} с — считаем ролик доигранным"
                    }

                    recoveries = 0

                    Notify.post(STATE, Player.STATE_ENDED)

                    return
                }

                /**
                 * Пересобираем не сразу, а выждав.
                 *
                 * Затор случается сейчас же после прыжка: обе дорожки
                 * ещё только встают на новое место, буфер пуст, и плеер
                 * объявляет «стою и не гружу». Прежде мы кидались
                 * пересобирать в тот же миг — три раза за полсекунды, —
                 * и каждая пересборка заставала то же самое, только
                 * с начала. Отсюда и «Не удалось получить поток» на
                 * ровном месте.
                 *
                 * Полторы секунды — время одного ответа подачи. За него
                 * дорожки успевают привязаться, и пересобранный источник
                 * находит куски уже в памяти.
                 */
                Log.now {
                    "[YouTube/Плеер] Затор — пересоберём с ${at.toInt()} с " +
                        "через ${RECOVERY_WAIT / 1000.0} с (попытка $recoveries)"
                }

                mainAfter(RECOVERY_WAIT) {
                    if (videoId != null) {
                        seekTo(at)
                    }
                }

                return
            }

            recoveries = 0

            Notify.post(FAILED, error.message)
        }
    }

    /**
     * Сколько раз подряд лечим затор, не спрашивая человека, и сколько
     * ждём перед каждой попыткой.
     *
     * Восемь попыток по полторы секунды — это двенадцать секунд
     * терпения. Меньше не хватало: после прыжка подача отвечает
     * за секунду-две, а прежние две попытки подряд укладывались
     * в полсекунды, то есть не давали ей ни одного ответа.
     *
     * Терпение здесь дешёвое: подача жива, куски приходят, и очередная
     * пересборка почти всегда попадает в готовое. Дорого другое —
     * показать человеку «не удалось получить поток» там, где надо было
     * просто подождать.
     */
    /**
     * Хвост ролика, на котором затор означает конец, а не беду.
     *
     * Три секунды — это меньше одного фрагмента видео (у подачи они
     * от трёх до семи секунд), то есть попасть сюда можно только
     * прыжком в самый конец.
     */
    private const val TAIL = 3.0

    /**
     * Насколько отступать от живого края эфира, с.
     *
     * Пятнадцати не хватило: пока поднимается показ, запас проедается,
     * и дальше мы идём вплотную к краю — просим кусок, которого ещё
     * не сняли, ждём, и показ отстаёт от живого времени. Тридцать
     * секунд — шесть кусков; столько же держит у себя и веб-плеер
     * YouTube на обычной задержке.
     */
    private const val LIVE_BEHIND = 30.0

    private const val RECOVERIES = 8

    private const val RECOVERY_WAIT = 1500L

    /** Сколько раз подряд лечили затор, не спрашивая человека. */
    private var recoveries = 0

    /** Сколько раз подряд спускались из-за отказа декодера. */
    private var descents = 0

    /**
     * Спуск на ступень ниже нынешней — после отказа декодера.
     *
     * false, если спускаться некуда: ниже ступеней нет либо мы уже
     * спускались дважды и дело, стало быть, не в ступени.
     */
    private fun stepDown(): Boolean {
        if (descents >= 2) {
            return false
        }

        val playing = playingHeight()

        val below = heights.filter { it in 1 until playing }.maxOrNull() ?: return false

        descents++

        Log.now {
            "[YouTube/Плеер] Декодер не взял ${playing}p — спускаемся к ${below}p"
        }

        main { pickHeight(below) }

        return true
    }

    /** Когда открыли ролик и пошёл ли он — для замера в журнале. */
    private var openedAt = 0L
    private var played = false

    // --- Уход -------------------------------------------------------------

    /** Отпускает плеер целиком. Зовётся, когда ролик закрыт насовсем. */
    fun release() {
        stopTicker()

        // Уходим с ролика — закрываем запись последним отрезком.
        sendWatchSegment(true)

        player?.release()
        player = null

        trackSelector = null
        sabr = null
        formats = emptyList()
        heights = emptyList()
        playerJson = null
        trackingJson = null
        videoId = null
        seekedTo = -1.0
        reportedAt = 0
    }
}

/** Дольше этого срока заказанное место не держится, мс. */
private const val HOLD_MS = 2500L

