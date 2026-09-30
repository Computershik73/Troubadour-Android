package ru.computershik.troubadour.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.R
import ru.computershik.troubadour.ui.ImageLoader
import ru.computershik.troubadour.ui.MainActivity
import ru.computershik.troubadour.ui.async
import ru.computershik.troubadour.ui.main

/**
 * Ролик на заблокированном экране и в шторке — порт `YTNowPlaying`.
 *
 * В оригинале это `MPNowPlayingInfoCenter` плюс `remoteControlReceived…`
 * у делегата приложения: система рисует карточку сама, надо только
 * сообщить ей название, автора, обложку и место в ролике.
 *
 * Здесь **всё сложнее, и не по нашей вине**. Android карточку сам
 * не рисует: её надо собрать уведомлением, а чтобы приложение не сняли
 * вместе с уходом в фон — держать службу переднего плана. В iOS для
 * того же довольно было строчки `audio` в `UIBackgroundModes`.
 *
 * Заодно служба решает и вторую задачу оригинала: там звук в фоне жил,
 * пока живо приложение, а система могла снять его за память — и
 * `applicationDidEnterBackground` отдавал картинки, чтобы этого
 * не случилось. Отдаём мы их и здесь, но служба переднего плана
 * снимается последней, а не первой.
 */
object NowPlaying {

    private const val CHANNEL = "playback"
    private const val NOTIFICATION = 1

    const val ACTION_TOGGLE = "ru.computershik.troubadour.TOGGLE"
    const val ACTION_STOP = "ru.computershik.troubadour.STOP"
    const val ACTION_BACK = "ru.computershik.troubadour.BACK"
    const val ACTION_FORWARD = "ru.computershik.troubadour.FORWARD"

    /** На сколько двигают ролик кнопки в шторке — как и в самом плеере. */
    private const val STEP_BACK = 5.0
    private const val STEP_FORWARD = 15.0

    @Volatile
    var title: String = ""

    @Volatile
    var author: String = ""

    @Volatile
    private var artwork: Bitmap? = null

    @Volatile
    private var running = false

    /**
     * Медиасеанс — по нему Android рисует плеер в шторке и на замке.
     *
     * Без него карточка была обычным беззвучным уведомлением в самом низу
     * шторки: без обложки, без полосы, без значка наверху, и на Android 13+
     * её попросту не замечали — «уведомления нет». С сеансом система
     * ставит ролик в свой плеер над уведомлениями и на экран блокировки,
     * а кнопки гарнитуры начинают управлять показом.
     *
     * Заводится с Android 5: раньше сеансу нужен приёмник кнопок в
     * манифесте, а старым системам хватает и самой карточки.
     */
    @Volatile
    private var session: MediaSessionCompat? = null

    /** Хозяин подписки на состояние плеера — пока карточка на экране. */
    private val sessionOwner = Any()

    private const val CUSTOM_BACK = "back"
    private const val CUSTOM_FORWARD = "forward"

    /**
     * Показывает карточку и удерживает приложение в фоне.
     *
     * @param thumbnail превью **ролика**. Прежде сюда отдавали значок
     *   канала: в шторке вместо кадра висел кружок с аватаркой, и о том,
     *   что играет, он не говорил ничего.
     */
    fun show(title: String, author: String, thumbnail: String?) {
        this.title = title
        this.author = author

        val context = App.require()

        if (!running) {
            running = true

            val intent = Intent(context, PlaybackService::class.java)

            /**
             * С Android 8 службу переднего плана нельзя просто запустить
             * из фона — её надо объявить именно такой. На всём, что старше,
             * годится обычный запуск.
             */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        update()

        if (!thumbnail.isNullOrEmpty()) {
            async {
                val picture = ImageLoader.fetch(thumbnail, 320f)

                main {
                    artwork = picture

                    update()
                }
            }
        }
    }

    /** Убирает карточку и отпускает фон. */
    fun hide() {
        if (!running) {
            return
        }

        running = false
        artwork = null

        Notify.offAll(sessionOwner)

        session?.let {
            try {
                it.isActive = false
                it.release()
            } catch (error: Throwable) {
            }
        }

        session = null

        val context = App.require()

        context.stopService(Intent(context, PlaybackService::class.java))
    }

    /** Перерисовывает карточку — при паузе, продолжении, смене ролика. */
    fun update() {
        if (!running) {
            return
        }

        val context = App.require()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return

        updateSession(context)

        try {
            manager.notify(NOTIFICATION, build(context))
        } catch (error: Throwable) {
            Log.d { "[YouTube/Фон] Карточка не обновилась: ${error.message}" }
        }
    }

    /**
     * Собирает уведомление.
     *
     * Кнопка одна — «играть/пауза». В оригинале их тоже одна: система
     * рисует остальные сама из того, какие команды принял
     * `MPRemoteCommandCenter`, а принимал он ровно play, pause и toggle.
     */
    internal fun build(context: Context): Notification {
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags()
        )

        val toggle = PendingIntent.getService(
            context, 1,
            Intent(context, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            pendingFlags()
        )

        val stop = PendingIntent.getService(
            context, 2,
            Intent(context, PlaybackService::class.java).setAction(ACTION_STOP),
            pendingFlags()
        )

        val back = PendingIntent.getService(
            context, 3,
            Intent(context, PlaybackService::class.java).setAction(ACTION_BACK),
            pendingFlags()
        )

        val forward = PendingIntent.getService(
            context, 4,
            Intent(context, PlaybackService::class.java).setAction(ACTION_FORWARD),
            pendingFlags()
        )

        // Доигравший ролик — уже не «играет», хоть плеер и держит `playWhenReady`.
        val playing = PlayerEngine.holdsScreen

        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(author)
            .setContentIntent(open)
            .setDeleteIntent(stop)
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_rew,
                ru.computershik.troubadour.loc("Назад"),
                back
            )
            .addAction(
                if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (playing) ru.computershik.troubadour.loc("Пауза")
                else ru.computershik.troubadour.loc("Играть"),
                toggle
            )
            .addAction(
                android.R.drawable.ic_media_ff,
                ru.computershik.troubadour.loc("Вперёд"),
                forward
            )

        /**
         * Вид карточки — как у проигрывателей: превью справа, три
         * кнопки под подписью. Своего сеанса `MediaSession` не заводим:
         * он нужен ради кнопок на гарнитуре и замке, а здесь довольно
         * самой карточки.
         */
        val style = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
            .setShowCancelButton(true)
            .setCancelButtonIntent(stop)

        session?.let { style.setMediaSession(it.sessionToken) }

        builder.setStyle(style)

        artwork?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    private fun ensureSession(context: Context): MediaSessionCompat? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return null
        }

        session?.let { return it }

        val created = try {
            MediaSessionCompat(context, "Troubadour")
        } catch (error: Throwable) {
            Log.d { "[YouTube/Фон] Медиасеанс не завёлся: ${error.message}" }

            return null
        }

        created.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                // Доигравший ролик кнопка «играть» начинает сначала.
                val length = PlayerEngine.duration()

                if (length > 0 && PlayerEngine.position() >= length - 0.5) {
                    PlayerEngine.seekTo(0.0)
                }

                PlayerEngine.play()
                update()
            }

            override fun onPause() {
                PlayerEngine.pause()
                update()
            }

            override fun onSeekTo(pos: Long) {
                PlayerEngine.seekTo(maxOf(0.0, pos / 1000.0))
                update()
            }

            override fun onRewind() {
                PlayerEngine.seekTo(maxOf(0.0, PlayerEngine.position() - STEP_BACK))
                update()
            }

            override fun onFastForward() {
                PlayerEngine.seekTo(PlayerEngine.position() + STEP_FORWARD)
                update()
            }

            override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
                when (action) {
                    CUSTOM_BACK -> onRewind()
                    CUSTOM_FORWARD -> onFastForward()
                }
            }

            override fun onStop() {
                PlayerEngine.pause()
                hide()
            }
        })

        created.isActive = true

        session = created

        // Пауза, продолжение, конец ролика — карточка следует за плеером сама.
        Notify.on(PlayerEngine.STATE, sessionOwner) { update() }

        Log.d { "[YouTube/Фон] Медиасеанс заведён" }

        return created
    }

    /** Что играет и где мы сейчас — для плеера в шторке и на замке. */
    private fun updateSession(context: Context) {
        val current = ensureSession(context) ?: return

        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, author)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, author)

        val length = (PlayerEngine.duration() * 1000).toLong()

        if (length > 0) {
            meta.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, length)
        }

        artwork?.let {
            meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            meta.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
        }

        val playing = PlayerEngine.holdsScreen

        /**
         * «Назад» и «вперёд» — своими действиями, а не переходом
         * к соседнему ролику: плеер системы рисует на месте перехода
         * значки «предыдущий» и «следующий», а кнопки у нас двигают
         * ролик на несколько секунд.
         */
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_STOP
            )
            .addCustomAction(
                CUSTOM_BACK, ru.computershik.troubadour.loc("Назад"),
                android.R.drawable.ic_media_rew
            )
            .addCustomAction(
                CUSTOM_FORWARD, ru.computershik.troubadour.loc("Вперёд"),
                android.R.drawable.ic_media_ff
            )
            .setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING
                else PlaybackStateCompat.STATE_PAUSED,
                (PlayerEngine.position() * 1000).toLong(),
                if (playing) 1f else 0f
            )

        try {
            current.setMetadata(meta.build())
            current.setPlaybackState(state.build())
        } catch (error: Throwable) {
            Log.d { "[YouTube/Фон] Медиасеанс не обновился: ${error.message}" }
        }
    }

    /** На сколько отступает кнопка «назад» в шторке. */
    fun stepBack(): Double = STEP_BACK

    /** На сколько продвигает кнопка «вперёд». */
    fun stepForward(): Double = STEP_FORWARD

    private fun pendingFlags(): Int {
        /**
         * С Android 12 намерение обязано объявить, меняемо оно или нет.
         * Без этого система отказывается его создавать — и приложение
         * падает прямо при показе карточки.
         */
        return if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return

        if (manager.getNotificationChannel(CHANNEL) != null) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL,
            ru.computershik.troubadour.loc("Воспроизведение"),
            NotificationManager.IMPORTANCE_LOW
        )

        channel.setShowBadge(false)
        channel.enableVibration(false)
        channel.setSound(null, null)

        manager.createNotificationChannel(channel)
    }
}

/**
 * Служба, которая держит приложение живым, пока идёт звук.
 *
 * Ничего не воспроизводит: сам плеер живёт в [PlayerEngine], а служба
 * только объявляет системе, что происходящее важно. Ровно то же самое
 * делала строчка `audio` в `UIBackgroundModes` оригинала — только там
 * она была строчкой, а здесь это класс, манифест и разрешение.
 */
class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            NowPlaying.ACTION_TOGGLE -> {
                PlayerEngine.togglePlay()

                NowPlaying.update()
            }

            NowPlaying.ACTION_STOP -> {
                PlayerEngine.pause()

                NowPlaying.hide()
            }

            NowPlaying.ACTION_BACK -> {
                PlayerEngine.seekTo(maxOf(0.0, PlayerEngine.position() - NowPlaying.stepBack()))

                NowPlaying.update()
            }

            NowPlaying.ACTION_FORWARD -> {
                PlayerEngine.seekTo(PlayerEngine.position() + NowPlaying.stepForward())

                NowPlaying.update()
            }
        }

        startForeground(1, NowPlaying.build(this))

        /**
         * `START_NOT_STICKY`: если систему всё же прижмёт и она нас
         * снимет, поднимать себя заново незачем — воспроизведения уже
         * не будет, а пустая карточка в шторке хуже её отсутствия.
         */
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        stopForeground(true)
    }
}
