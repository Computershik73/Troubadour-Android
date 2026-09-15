package ru.computershik.troubadour.ui

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import androidx.core.content.FileProvider
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.locF
import ru.computershik.troubadour.net.Download
import ru.computershik.troubadour.net.Downloads
import ru.computershik.troubadour.ui.Metrics.dp
import java.io.File

/**
 * «Скачанное» — порт `YTDownloadsView`.
 *
 * Строка: превью 16:9 шириной 120, название в две строки, под ним доля
 * либо размер готового файла. Нажатие открывает файл системным плеером,
 * долгое — предлагает удалить.
 */
class DownloadsScreen(context: Context) : Screen(context) {

    private lateinit var header: ScreenHeader
    private lateinit var list: ListView
    private lateinit var status: StatusView

    private var items: List<Download> = emptyList()

    private val adapter = object : BaseAdapter() {

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? DownloadRow) ?: DownloadRow(context)

            row.bind(items[position])

            return row
        }
    }

    override fun build(root: FrameLayout) {
        val column = LinearLayout(context)

        column.orientation = LinearLayout.VERTICAL

        header = ScreenHeader(context, loc("Скачанные"))

        column.addView(header)

        list = ListView(context)

        list.adapter = adapter
        list.divider = null
        list.dividerHeight = 0
        list.setCacheColorHint(0)

        list.setOnItemClickListener { _, _, position, _ ->
            open(items[position])
        }

        list.setOnItemLongClickListener { _, _, position, _ ->
            confirmRemove(items[position])

            true
        }

        column.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        root.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        status = StatusView(context)

        root.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        refresh()

        Notify.on(Notify.DOWNLOADS, this) { refresh() }
    }

    private fun refresh() {
        /**
         * По одной строке на ролик, а не на качество.
         *
         * Три карточки с одинаковым названием и превью выглядят ошибкой,
         * а не выбором; качество спрашивается при открытии — так же,
         * как в iOS-версии.
         */
        items = Downloads.videos()

        adapter.notifyDataSetChanged()

        if (items.isEmpty()) {
            status.showMessage(loc("Ничего не скачано"))
        } else {
            status.hide()
        }
    }

    /**
     * Открывает файл системным плеером.
     *
     * Через FileProvider: с Android 7 приложение не имеет права
     * передавать наружу путь к своему файлу (`file://`) — только
     * временный адрес `content://`. В оригинале этой заботы нет вовсе:
     * там файл лежит в общей папке приложения, открытой наружу.
     */
    private fun open(item: Download) {
        /**
         * Открываем, спросив о качестве, если их несколько, — порт
         * `YTOpenDownload`.
         *
         * Один и тот же ролик держат скачанным по-разному, и решать
         * за человека, какое из качеств он хотел посмотреть, не стоит:
         * 1080p дома и 360p в дороге — разные намерения.
         */
        val good = Downloads.readyFor(item.videoId).filter { one ->
            ru.computershik.troubadour.net.PublicStore.exists(one.where())
        }

        if (good.isEmpty()) {
            Toast.show(context, loc("Файла больше нет"))

            return
        }

        if (good.size == 1) {
            openFile(good[0])

            return
        }

        DownloadedQualitySheet(context, good) { openFile(it) }.show()
    }

    private fun openFile(item: Download) {
        try {
            /**
             * Чем назвать файл наружу — зависит от того, где он лежит.
             *
             * Опубликованный через `MediaStore` известен своим
             * `content://`, и он же годится для показа. Обычный путь
             * с Android 7 передавать наружу нельзя — только временный
             * адрес через `FileProvider`. А до седьмой версии как раз
             * лучше отдать `file://`: старые проигрыватели с `content://`
             * теряются — самсунговский, к примеру, ищет такой адрес
             * в медиатеке и падает, не найдя.
             */
            val saved = item.uri

            val uri = if (!saved.isNullOrEmpty()) {
                android.net.Uri.parse(saved)
            } else {
                val file = File(item.file ?: return)

                if (!file.exists()) {
                    Toast.show(context, loc("Файла больше нет"))

                    return
                }

                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    FileProvider.getUriForFile(
                        context, "${context.packageName}.files", file
                    )
                } else {
                    android.net.Uri.fromFile(file)
                }
            }

            val intent = Intent(Intent.ACTION_VIEW)

            intent.setDataAndType(uri, "video/mp4")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            /**
             * Спрашиваем, чем смотреть, а не открываем молча.
             *
             * Своего проигрывателя для скачанного у нас нет, и выбор
             * тут не наш: на устройстве их обычно несколько, и человек
             * знает лучше. Так же поступает и iOS-версия — там открытие
             * идёт через системную панель «Открыть в…».
             */
            val chooser = Intent.createChooser(intent, loc("Чем смотреть"))

            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            context.startActivity(chooser)
        } catch (error: Exception) {
            Toast.show(context, loc("Нечем открыть"))
        }
    }

    private fun confirmRemove(item: Download) {
        /**
         * У недокачанного разговор другой: его не «убирают», его
         * отменяют. Слова разные не для красоты — за ними и дела
         * разные: отмена обрывает работу службы и убирает недокачанное,
         * а уборка трогает только готовый файл.
         */
        val running = item.state == Download.RUNNING || item.state == Download.QUEUED

        android.app.AlertDialog.Builder(context)
            .setTitle(item.title)
            .setMessage(
                if (running) loc("Отменить закачку?") else loc("Убрать скачанное?")
            )
            .setPositiveButton(
                if (running) loc("Отменить закачку") else loc("Убрать")
            ) { _, _ ->
                if (running) {
                    Downloads.cancel(item.videoId, item.height)
                } else {
                    Downloads.remove(item.videoId, item.height)
                }
            }
            .setNegativeButton(loc("Отмена"), null)
            .show()
    }

    override fun repaint() {
        super.repaint()

        header.repaint()

        adapter.notifyDataSetChanged()
    }
}

/** Строка списка скачанного. */
private class DownloadRow(context: Context) : LinearLayout(context) {

    private val thumb = RoundedImage(context)

    private val title = label(context, Fonts.medium, 14f, Theme.primaryText, 2)
    private val note = label(context, Fonts.regular, 12f, Theme.mutedText, 1)

    init {
        orientation = HORIZONTAL

        setPadding(dp(16f), dp(8f), dp(16f), dp(8f))

        thumb.cornerRadius = Metrics.dpf(Metrics.THUMB_RADIUS)
        thumb.placeholderColor = Theme.surfaceAlt

        addView(thumb, LayoutParams(dp(120f), dp(120f * 9 / 16)))

        val column = LinearLayout(context)

        column.orientation = VERTICAL

        column.addView(title)
        column.addView(note)

        val params = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        params.leftMargin = dp(12f)

        addView(column, params)
    }

    fun bind(item: Download) {
        title.setTextColor(Theme.primaryText)
        note.setTextColor(Theme.mutedText)

        thumb.placeholderColor = Theme.surfaceAlt

        title.text = item.title

        ImageLoader.loadInto(thumb, item.thumbnail, 120f)

        note.text = when (item.state) {
            /**
             * Объём — по всем качествам ролика, а не по одному.
             *
             * Строка стоит за ролик целиком, и место на устройстве он
             * занимает всеми своими качествами разом. Показывать долю
             * от одного значило бы обещать, что уборка освободит
             * меньше, чем освободит на деле.
             */
            Download.DONE -> {
                val ready = Downloads.readyFor(item.videoId)

                val heights = ready
                    .sortedByDescending { it.height }
                    .joinToString(", ") { qualityTitle(it.height) }

                val bytes = Downloads.bytesFor(item.videoId)

                if (ready.size > 1) {
                    "$heights • ${sizeText(bytes)}"
                } else {
                    sizeText(if (item.total > 0) item.total else item.received)
                }
            }

            Download.FAILED -> item.error ?: loc("Не скачалось")

            Download.RUNNING -> if (item.total > 0) {
                locF(
                    "%@ • %.0f%% из %@",
                    sizeText(item.received),
                    item.received * 100.0 / item.total,
                    sizeText(item.total)
                )
            } else {
                sizeText(item.received)
            }

            else -> loc("В очереди")
        }
    }

}

/** Размер человеческими единицами — строки те же, что в оригинале. */
fun sizeText(bytes: Long): String {
    val value = bytes.toDouble()

    return when {
        value >= 1024.0 * 1024 * 1024 -> locF("%.2f ГБ", value / 1024 / 1024 / 1024)
        value >= 1024.0 * 1024 -> locF("%.1f МБ", value / 1024 / 1024)
        value >= 1024.0 -> locF("%.0f КБ", value / 1024)
        else -> locF("%.0f Б", value)
    }
}

/**
 * Подпись качества — порт `titleForHeight:`.
 *
 * У склеенного потока своей ступени нет: качество ему выбирает YouTube,
 * а не человек, — поэтому у нуля своё слово.
 */
fun qualityTitle(height: Int): String =
    if (height > 0) "${height}p" else loc("Готовое")
