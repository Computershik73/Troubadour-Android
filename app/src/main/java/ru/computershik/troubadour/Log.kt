package ru.computershik.troubadour

import java.io.FileOutputStream
import android.util.Log as Android
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Журнал приложения.
 *
 * `android.util.Log` пишет в logcat, а не в файл: на устройстве его видно
 * только через adb или сторонние средства, и на приставке без отладки по
 * USB — вовсе никак. Для отладки на живом железе этого мало: чтобы прислать
 * лог, его сначала надо откуда-то достать.
 *
 * Поэтому всё, что уходит в logcat, попадает ещё и в файл: `files/troubadour.log`.
 * Он отдаётся наружу с экрана «О программе» через FileProvider — то же самое,
 * что `UIFileSharingEnabled` в оригинале, только кнопкой.
 *
 * Готовая сборка молчит совсем: `BuildConfig.LOG` в релизе равен `false`,
 * и каждый вызов сводится к проверке константы, которую R8 выбрасывает
 * вместе с телом. Разница не в опрятности — строка журнала стоит склейки
 * формата, а иные из них зовутся на каждый кусок потока.
 *
 * Отсюда правило для тех, кто пишет новый код: **аргументы записи не должны
 * ничего делать.** Всё, что там позвано, в готовой сборке не случится.
 * Поэтому и подпись такая: сообщение приходит замыканием, а не строкой.
 */
object Log {

    private const val TAG = "Troubadour"

    /**
     * Потолок на размер журнала.
     *
     * Приложение пишет немало — каждый запрос к InnerTube, каждая порция
     * превью, — и без потолка файл рос бы неограниченно. По достижении
     * предела он откладывается в сторону и начинается новый: так под рукой
     * всегда есть не меньше полумегабайта истории, а больше мегабайта файлы
     * не занимают.
     */
    private const val LIMIT = 512L * 1024

    /** Очередь одна на всё: писать в файл из нескольких потоков разом нельзя. */
    private val queue = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "troubadour-log").apply { isDaemon = true }
    }

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Куда писать. Задаётся один раз при запуске — до первой записи. */
    @Volatile
    private var folder: File? = null

    fun attach(directory: File) {
        folder = directory
    }

    /**
     * Обрыв записывается **всегда**, и в готовой сборке тоже.
     *
     * Обычные записи в ней выброшены целиком — и правильно: строка
     * журнала стоит склейки формата, а иные из них зовутся на каждый
     * кусок потока. Но обрыв — это одна запись за всё время работы,
     * и без неё падение на устройстве человека остаётся немым: он
     * говорит «уронило», и разбирать нечего.
     *
     * Пишется мимо очереди и мимо `BuildConfig.LOG`: процесс уже
     * уходит, отложенная запись до диска не дойдёт.
     */
    fun attachCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val directory = folder

                if (directory != null) {
                    val target = File(directory, "troubadour.log")

                    val trace = java.io.StringWriter()

                    error.printStackTrace(java.io.PrintWriter(trace))

                    val line = "${clock.format(Date())} " +
                        "[YouTube] ОБРЫВ в потоке «${thread.name}»\n$trace\n"

                    FileOutputStream(target, true).use { handle ->
                        handle.write(line.toByteArray(Charsets.UTF_8))
                    }
                }
            } catch (ignored: Throwable) {
                // Записать не вышло — ничего не поделать, процесс уходит.
            }

            previous?.uncaughtException(thread, error)
        }
    }

    fun path(): String {
        val directory = folder ?: return ""

        return File(directory, "troubadour.log").absolutePath
    }

    fun file(): File? = folder?.let { File(it, "troubadour.log") }

    fun clear() {
        val directory = folder ?: return

        queue.execute {
            File(directory, "troubadour.log").delete()
            File(directory, "troubadour-prev.log").delete()
        }
    }

    /**
     * Обычная запись — отложенная: строка уходит в очередь и пишется потом.
     * Для журнала это верно, иначе каждый вызов ждал бы диск.
     */
    inline fun d(message: () -> String) {
        if (BuildConfig.LOG) {
            write(message())
        }
    }

    /**
     * То же, но строка ложится в файл **до** возврата из вызова.
     *
     * При поиске падения отложенная запись губительна: всё, что стояло
     * в очереди в миг обрыва, пропадает, и по журналу выходит, будто
     * приложение умерло раньше, чем на самом деле. Там, где нужна именно
     * последняя строка, зовут эту.
     */
    inline fun now(message: () -> String) {
        if (BuildConfig.LOG) {
            writeNow(message())
        }
    }

    fun write(message: String) {
        Android.i(TAG, message)
        queue.execute { append(message) }
    }

    fun writeNow(message: String) {
        Android.i(TAG, message)

        val done = queue.submit { append(message) }

        try {
            done.get()
        } catch (ignored: Exception) {
            // Ждать было нечего — процесс всё равно уходит.
        }
    }

    /** Само письмо в файл. Зовётся только с очереди журнала. */
    private fun append(message: String) {
        val directory = folder ?: return

        try {
            val target = File(directory, "troubadour.log")
            val line = "${clock.format(Date())} $message\n"

            /**
             * Дописывание, а не «встать в конец и писать».
             *
             * В журнал пишут два процесса — наш и тот, где живёт решатель
             * PO-токена. При отдельных «узнать длину» и «записать» они
             * могут узнать одну и ту же длину и затереть строку друг
             * друга. Открытие на дописывание перекладывает это на ядро:
             * оно ставит смещение в конец неделимо с самой записью.
             */
            FileOutputStream(target, true).use { handle ->
                handle.write(line.toByteArray(Charsets.UTF_8))
            }

            if (target.length() < LIMIT) {
                return
            }

            // Предел достигнут: нынешний файл становится предыдущим.
            val previous = File(directory, "troubadour-prev.log")

            previous.delete()
            target.renameTo(previous)
        } catch (ignored: Exception) {
            // Журнал, уронивший приложение, — худшее, что можно придумать.
        }
    }
}
