package ru.computershik.troubadour.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.ui.MainActivity

/**
 * Служба скачивания.
 *
 * Загрузка продолжается, когда приложение свёрнуто, и это не удобство,
 * а необходимость: без службы переднего плана система снимает работу
 * вместе с приложением, и очередь из пяти роликов не доживает до конца.
 *
 * В оригинале службы нет вовсе — там загрузка живёт, пока живёт
 * приложение, и «фоновое» скачивание кончается вместе с уходом в фон.
 * Это одно из немногих мест, где Android требует больше кода, но даёт
 * больше пользы.
 */
class DownloadService : Service() {

    companion object {

        private const val CHANNEL = "downloads"
        private const val NOTIFICATION = 2

        /**
         * Отмена приходит той же службе, отдельным поводом.
         *
         * Уведомление службы переднего плана система не даёт смахнуть,
         * пока служба жива, — и это правильно: работа-то идёт. Значит,
         * оборвать её должно само уведомление, кнопкой.
         */
        const val ACTION_CANCEL = "ru.computershik.troubadour.download.cancel"

        const val EXTRA_VIDEO = "videoId"

        const val EXTRA_HEIGHT = "height"

        @Volatile
        private var running = false

        /** Разбудить службу — зовётся, когда в очередь что-то положили. */
        fun wake() {
            if (running) {
                return
            }

            val context = App.require()

            val intent = Intent(context, DownloadService::class.java)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: Throwable) {
                /**
                 * С Android 12 запуск службы переднего плана из фона
                 * запрещён вовсе. Очередь при этом не пропадает: она
                 * лежит в настройках и разберётся, когда приложение
                 * откроют.
                 */
                Log.d { "[YouTube/Скачивание] Службу не поднять: ${error.message}" }
            }
        }
    }

    private var worker: Thread? = null

    /** Что качается прямо сейчас — нужно кнопке отмены в уведомлении. */
    @Volatile
    private var current: String = ""

    /** И в каком качестве: ключ записи — ролик и ступень вместе. */
    @Volatile
    private var currentHeight: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, build(0, 0, "", current, currentHeight))

        if (intent?.action == ACTION_CANCEL) {
            val videoId = intent.getStringExtra(EXTRA_VIDEO)

            if (!videoId.isNullOrEmpty()) {
                Downloads.cancel(videoId, intent.getIntExtra(EXTRA_HEIGHT, 0))
            }

            /**
             * Службу при этом не трогаем: цикл сам заметит отмену
             * и, если больше делать нечего, закроется как обычно.
             */
            return START_NOT_STICKY
        }

        if (worker == null) {
            running = true

            worker = Thread({ drain() }, "troubadour-downloads").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1

                start()
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Разбирает очередь по одному.
     *
     * По одному, а не разом: две загрузки на медленной сети идут вдвое
     * дольше каждая, а память под буферы уходит в обе стороны.
     */
    private fun drain() {
        while (true) {
            val item = Downloads.next() ?: break

            Log.d { "[YouTube/Скачивание] Берём ${item.videoId} (${item.height}p)" }

            current = item.videoId
            currentHeight = item.height

            update(item.received, item.total, item.title)

            Downloads.run(item)

            current = ""
            currentHeight = 0
        }

        running = false
        worker = null

        stopForeground(true)
        stopSelf()
    }

    private fun update(received: Long, total: Long, title: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return

        try {
            manager.notify(
                NOTIFICATION, build(received, total, title, current, currentHeight)
            )
        } catch (ignored: Throwable) {
            // Уведомление могли запретить — работа от этого не встаёт.
        }
    }

    private fun build(
        received: Long,
        total: Long,
        title: String,
        videoId: String,
        height: Int
    ): Notification {
        ensureChannel()

        /**
         * Нажатие ведёт к списку скачанного.
         *
         * Кнопку «Отмена» Android до пятой версии рисует лишь
         * в развёрнутом уведомлении, а развёрнутым в шторке бывает
         * только верхнее из них — на устройстве с десятком уведомлений
         * до неё не добраться. Список же открыт всегда: там у строки
         * долгое нажатие и предлагает отменить.
         */
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setAction(MainActivity.ACTION_DOWNLOADS),
            if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        /**
         * Повод отмены — свой, `ACTION_CANCEL`, и приходит он этой же
         * службе: она помечает закачку отменённой, а обрывает её уже
         * сам цикл, между кусками, прибрав недокачанное.
         */
        val stop = if (videoId.isEmpty()) {
            null
        } else {
            PendingIntent.getService(
                this, 1,
                Intent(this, DownloadService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_VIDEO, videoId)
                    .putExtra(EXTRA_HEIGHT, height),
                if (Build.VERSION.SDK_INT >= 31) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(loc("Скачивание"))
            .setContentText(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            /**
             * Развёрнутый вид — ради кнопки: на пятой версии и позже
             * она живёт только в нём.
             */
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))

        if (stop != null) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                              loc("Отмена"), stop)
        }

        /**
         * Доля показывается только когда известен общий объём.
         *
         * У склеенного потока сервер длины не называет вовсе, и полоса
         * с неизвестным концом честнее той, что дошла до сотни
         * и откатилась.
         */
        val part = if (total > 0) ((received * 100) / total).toInt() else 0

        if (total > 0) {
            builder.setProgress(100, part, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return

        if (manager.getNotificationChannel(CHANNEL) != null) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL, loc("Скачивание"), NotificationManager.IMPORTANCE_LOW
        )

        channel.setShowBadge(false)
        channel.enableVibration(false)
        channel.setSound(null, null)

        manager.createNotificationChannel(channel)
    }
}
