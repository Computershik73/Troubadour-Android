package ru.computershik.troubadour.net

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Скачанное на виду — папка «Загрузки/Troubadour».
 *
 * Прежде ролики лежали в личной папке приложения. Приложению так проще
 * всего: полный доступ, никаких разрешений, — но человеку оттуда не взять
 * ничего. Папка закрыта правами: её не видно ни файловым управляющим,
 * ни галереей, файл не скинуть на компьютер, а удаление приложения
 * уносит всё скачанное с собой.
 *
 * Теперь готовый файл переезжает в общие «Загрузки», в свою папку.
 * Дорог туда две, и выбирает между ними не наше желание, а версия
 * Android:
 *
 *   до Android 10 — обычный путь `/sdcard/Download/Troubadour`. Нужно
 *     разрешение на запись: до шестой версии его выдаёт установка,
 *     с шестой по девятую спрашивается на ходу;
 *
 *   с Android 10 — через `MediaStore`. Прямой путь туда закрыт, зато
 *     своя запись в общей коллекции «Загрузки» заводится **без всяких
 *     разрешений**: файл, который приложение само создало, оно вправе
 *     и писать, и читать, и удалять.
 *
 * Вторая дорога не умеет дописывать в середину, поэтому скачивание
 * и сборка идут в личной папке, а сюда переносится уже готовое.
 * На первой дороге переносить нечего: там сразу пишем куда надо.
 */
object PublicStore {

    /** Имя папки внутри «Загрузок». */
    const val NAME = "Troubadour"

    /** С Android 10 общая память закрыта прямым путём. */
    private val scoped: Boolean
        get() = Build.VERSION.SDK_INT >= 29

    /**
     * Разрешение на запись нужно не всем.
     *
     * До шестой версии его выдаёт установка, с десятой запись в свою
     * коллекцию идёт без разрешения вовсе. Спрашивать надо только между
     * этими границами.
     */
    private fun needsPermission(): Boolean =
        Build.VERSION.SDK_INT in 23..28

    fun allowed(): Boolean {
        if (scoped) {
            return true
        }

        if (!needsPermission()) {
            return true
        }

        val context = App.require()

        return try {
            context.checkSelfPermission(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (error: Throwable) {
            false
        }
    }

    /**
     * Спрашивает разрешение, если оно нужно и его ещё нет.
     *
     * Зовётся из листа выбора качества — до того, как закачка встала
     * в очередь: спрашивать посреди работы поздно, к тому времени файл
     * уже некуда класть.
     */
    fun ask(context: Context) {
        if (allowed()) {
            return
        }

        val activity = context as? android.app.Activity ?: return

        try {
            activity.requestPermissions(
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 2
            )
        } catch (error: Throwable) {
            Log.d { "[YouTube/Скачивание] Разрешение спросить не вышло: ${error.message}" }
        }
    }

    /** Папка «Загрузки/Troubadour» обычным путём; ничего — если она недоступна. */
    fun folder(): File? {
        if (scoped || !allowed()) {
            return null
        }

        val state = try {
            Environment.getExternalStorageState()
        } catch (error: Throwable) {
            return null
        }

        if (state != Environment.MEDIA_MOUNTED) {
            return null
        }

        val downloads = try {
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
        } catch (error: Throwable) {
            return null
        } ?: return null

        val folder = File(downloads, NAME)

        if (!folder.exists() && !folder.mkdirs()) {
            Log.d { "[YouTube/Скачивание] Папку ${folder.path} завести не вышло" }

            return null
        }

        return folder
    }

    /**
     * Кладёт готовый файл в общие «Загрузки» и отдаёт его адрес.
     *
     * Возвращается либо обычный путь (до Android 10), либо `content://`
     * из `MediaStore`. Пустое — значит не вышло, и тогда файл остаётся
     * там, где был: потерять скачанное из-за неудавшегося переезда
     * было бы худшим исходом.
     */
    fun publish(source: File, name: String): String? {
        if (!scoped) {
            val folder = folder() ?: return null

            val target = File(folder, name)

            if (source == target) {
                return target.absolutePath
            }

            if (!move(source, target)) {
                return null
            }

            scan(target)

            return target.absolutePath
        }

        val resolver = App.require().contentResolver

        val values = ContentValues()

        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        values.put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            Environment.DIRECTORY_DOWNLOADS + "/" + NAME
        )

        /**
         * Пока идёт перенос, запись помечена незавершённой: иначе
         * галерея и файловые управляющие покажут наполовину
         * скопированный ролик как готовый.
         */
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)

        return try {
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null

            resolver.openOutputStream(uri).use { out ->
                if (out == null) {
                    resolver.delete(uri, null, null)

                    return null
                }

                FileInputStream(source).use { input ->
                    val buffer = ByteArray(256 * 1024)

                    while (true) {
                        val read = input.read(buffer)

                        if (read <= 0) {
                            break
                        }

                        out.write(buffer, 0, read)
                    }
                }
            }

            val done = ContentValues()

            done.put(MediaStore.MediaColumns.IS_PENDING, 0)

            resolver.update(uri, done, null, null)

            source.delete()

            uri.toString()
        } catch (error: Exception) {
            Log.d { "[YouTube/Скачивание] В «Загрузки» не перенеслось: ${error.message}" }

            null
        }
    }

    /**
     * Переносит файл, а не копирует, — когда это возможно.
     *
     * Личная папка и общая память лежат на разных разделах, и `renameTo`
     * между ними не работает; тогда остаётся переливать. Копия при этом
     * требует места под второй такой же файл, поэтому сперва всё же
     * пробуем переименовать: внутри одного раздела это мгновенно
     * и без места.
     */
    private fun move(source: File, target: File): Boolean {
        if (source.renameTo(target)) {
            return true
        }

        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(256 * 1024)

                    while (true) {
                        val read = input.read(buffer)

                        if (read <= 0) {
                            break
                        }

                        out.write(buffer, 0, read)
                    }
                }
            }

            source.delete()

            true
        } catch (error: Exception) {
            Log.d { "[YouTube/Скачивание] Перенос не удался: ${error.message}" }

            target.delete()

            false
        }
    }

    /**
     * Говорит системе о новом файле.
     *
     * Без этого до Android 10 файл лежит на месте, но ни в галерее,
     * ни в «Моих файлах» его нет: они смотрят в медиатеку, а она о нём
     * не знает, пока не обойдёт карту заново — а это бывает раз в сутки
     * либо после перезагрузки.
     */
    private fun scan(file: File) {
        try {
            android.media.MediaScannerConnection.scanFile(
                App.require(), arrayOf(file.absolutePath), arrayOf("video/mp4"), null
            )
        } catch (error: Throwable) {
            // Не отозвалась — файл от этого никуда не денется.
        }
    }

    /** Убирает опубликованное: запись `MediaStore` либо файл с пересчётом. */
    fun forget(location: String?) {
        if (location.isNullOrEmpty()) {
            return
        }

        if (!location.startsWith("content://")) {
            val file = File(location)

            if (file.delete()) {
                scan(file)
            }

            return
        }

        try {
            App.require().contentResolver.delete(Uri.parse(location), null, null)
        } catch (error: Exception) {
            Log.d { "[YouTube/Скачивание] Запись не убралась: ${error.message}" }
        }
    }

    /** Есть ли ещё то, на что указывает адрес. */
    fun exists(location: String?): Boolean {
        if (location.isNullOrEmpty()) {
            return false
        }

        if (!location.startsWith("content://")) {
            return File(location).exists()
        }

        return try {
            App.require().contentResolver
                .openFileDescriptor(Uri.parse(location), "r")?.use { true } ?: false
        } catch (error: Exception) {
            false
        }
    }
}
