package ru.computershik.troubadour.player

import android.os.SystemClock
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.TransferListener

/**
 * Счётчики сети для окна «статистика для сисадминов».
 *
 * Сюда стекаются байты с обоих путей — с подачи SABR и с готовых
 * адресов, — и по ним же считается скорость соединения. Считать её
 * по одному ответу нельзя: ответы подачи разные, от четырёх килобайт
 * до трёх мегабайт, и короткий даёт случайную цифру. Поэтому скорость
 * сглаживается, а совсем короткие переносы в неё не идут.
 */
object PlaybackStats {

    private val lock = Any()

    private var bytes = 0L

    /** Сглаженная скорость, кбит/с; 0, пока мерить не по чему. */
    private var kbps = 0.0

    /**
     * Один перенос: столько байт за столько миллисекунд.
     *
     * Скорость считается только по переносам не короче полусекунды
     * и не легче шестнадцати килобайт — по остальным она врёт.
     */
    fun noteTransfer(count: Long, elapsedMs: Long) {
        if (count <= 0) {
            return
        }

        synchronized(lock) {
            bytes += count

            if (elapsedMs >= 500 && count >= 16 * 1024) {
                // байт × 8 / мс = кбит/с
                val sample = count * 8.0 / elapsedMs

                kbps = if (kbps <= 0) sample else kbps * 0.7 + sample * 0.3
            }
        }
    }

    /**
     * Скорость по отдельно измеренному отрезку — без учёта в общем счёте.
     *
     * Подача мерит установившуюся часть тела ответа, а весь ответ
     * считает в общий счёт отдельно, через [noteTransfer] с нулём.
     */
    fun noteSpeed(count: Long, elapsedMs: Long) {
        if (count < 16 * 1024 || elapsedMs < 300) {
            return
        }

        synchronized(lock) {
            val sample = count * 8.0 / elapsedMs

            kbps = if (kbps <= 0) sample else kbps * 0.7 + sample * 0.3
        }
    }

    /** Всего принято байт за жизнь процесса. */
    fun totalBytes(): Long = synchronized(lock) { bytes }

    /** Скорость соединения, кбит/с. */
    fun speedKbps(): Long = synchronized(lock) { kbps.toLong() }

    /**
     * Слушатель переносов для готовых адресов: ExoPlayer сам сообщает,
     * сколько байт пришло по каждому запросу.
     */
    class Listener : TransferListener {

        private var startedAt = 0L
        private var counted = 0L

        override fun onTransferInitializing(
            source: DataSource, dataSpec: DataSpec, isNetwork: Boolean
        ) {
        }

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            startedAt = SystemClock.elapsedRealtime()
            counted = 0
        }

        override fun onBytesTransferred(
            source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int
        ) {
            counted += bytesTransferred

            synchronized(lock) { bytes += bytesTransferred }
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt

            synchronized(lock) {
                if (elapsed >= 500 && counted >= 16 * 1024) {
                    val sample = counted * 8.0 / elapsed

                    kbps = if (kbps <= 0) sample else kbps * 0.7 + sample * 0.3
                }
            }

            counted = 0
        }
    }
}
