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
import androidx.core.app.NotificationCompat
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log
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

        val playing = PlayerEngine.isPlaying

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
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(stop)
        )

        artwork?.let { builder.setLargeIcon(it) }

        return builder.build()
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
