package ru.computershik.troubadour.net

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.player.Mp4Writer
import ru.computershik.troubadour.player.Sabr
import ru.computershik.troubadour.player.Streams
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/** Одна запись очереди скачивания. */
class Download {

    var videoId: String = ""
    var title: String = ""
    var channelTitle: String = ""
    var thumbnail: String? = null

    /** Выбранная ступень; 0 — что дадут. */
    var height: Int = 0

    /**
     * Названная человеком звуковая дорожка; пусто — по настройке.
     *
     * Заполняется только при «спрашивать каждый раз»: во всех прочих
     * ладах дорожку называет правило, и называет её в миг скачивания,
     * когда ответ `/player` уже на руках.
     */
    var audioTrack: String? = null

    /** Сколько байт уже взято и сколько всего — для доли. */
    var received: Long = 0
    var total: Long = 0

    /** `queued`, `running`, `done`, `failed`. */
    var state: String = QUEUED

    var error: String? = null

    /** Готовый файл — обычным путём. */
    var file: String? = null

    /**
     * Он же, если лежит в общей памяти через `MediaStore`.
     *
     * С Android 10 прямого пути к «Загрузкам» нет, и опубликованный
     * ролик известен только по своему `content://`. Держим оба поля:
     * на старых устройствах путь, на новых — адрес.
     */
    var uri: String? = null

    /**
     * Имя файла — из ролика **и качества**.
     *
     * Порт `filePath` из iOS-версии. Иначе второе скачивание того же
     * ролика в другом качестве писало бы поверх первого: имя-то одно.
     * У склеенного потока своей ступени нет, поэтому у него своё слово.
     */
    fun fileName(): String {
        val mark = if (height > 0) height.toString() else "ready"

        return "${videoId}_$mark.mp4"
    }

    /** Имя для недокачанного куска: рядом с готовым, но своё у каждой ступени. */
    fun tempName(kind: String): String {
        val mark = if (height > 0) height.toString() else "ready"

        return "${videoId}_$mark.$kind.tmp"
    }

    /**
     * Ключ записи: ролик и качество вместе, а не один ролик.
     *
     * Один и тот же ролик держат и в 360p «для дороги», и в 1080p
     * «для дома»; складывать их в одну запись значило бы, что второе
     * скачивание молча затирает первое.
     */
    fun key(): String = "$videoId#$height"

    companion object {
        const val QUEUED = "queued"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
    }

    fun toJson(): JSONObject {
        val json = JSONObject()

        json.put("videoId", videoId)
        json.put("title", title)
        json.put("channelTitle", channelTitle)
        json.put("thumbnail", thumbnail)
        json.put("height", height)
        json.put("received", received)
        json.put("total", total)
        json.put("state", state)
        json.put("error", error)
        json.put("file", file)
        json.put("uri", uri)

        return json
    }

    fun fromJson(json: JSONObject) {
        videoId = Json.string(json, "videoId", "") ?: ""
        title = Json.string(json, "title", "") ?: ""
        channelTitle = Json.string(json, "channelTitle", "") ?: ""
        thumbnail = Json.text(json, "thumbnail")
        height = Json.int(json, "height")
        received = Json.long(json, "received")
        total = Json.long(json, "total")
        state = Json.string(json, "state", QUEUED) ?: QUEUED
        error = Json.text(json, "error")
        file = Json.text(json, "file")
        uri = Json.text(json, "uri")
    }

    /** Где ролик лежит: адрес в общей памяти либо путь. */
    fun where(): String? = if (!uri.isNullOrEmpty()) uri else file
}

/**
 * Очередь скачиваний — порт `YTDownloads`.
 *
 * Список живёт в настройках, файлы — в личной папке приложения. Скачивает
 * не она сама, а служба переднего плана ([DownloadService]): иначе система
 * снимает загрузку вместе со свёрнутым приложением, и очередь из пяти
 * роликов не доживает до конца.
 */
object Downloads {

    private const val STORE = "troubadour"
    private const val KEY = "YTDownloads"

    private val items = ArrayList<Download>()

    private var restored = false

    private val store
        get() = App.require().getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /**
     * Где идёт работа.
     *
     * До Android 10 это сразу «Загрузки/Troubadour»: туда можно писать
     * обычным путём, и готовый файл никуда не переносится — он там
     * и собирается. С Android 10 прямого пути нет, и качаем мы в личную
     * папку, а в «Загрузки» ролик переезжает уже готовым ([PublicStore]).
     *
     * Личная папка остаётся и запасным ходом: не дали разрешения,
     * не вставлена карта — скачивание всё равно должно работать.
     */
    fun folder(): File {
        PublicStore.folder()?.let { return it }

        val directory = File(App.require().filesDir, "downloads")

        if (!directory.exists()) {
            directory.mkdirs()
        }

        return directory
    }

    /** Личная папка — та, где всегда можно писать. */
    private fun ownFolder(): File {
        val directory = File(App.require().filesDir, "downloads")

        if (!directory.exists()) {
            directory.mkdirs()
        }

        return directory
    }

    @Synchronized
    fun all(): List<Download> {
        restore()

        return ArrayList(items)
    }

    @Synchronized
    private fun restore() {
        if (restored) {
            return
        }

        restored = true

        val text = store.getString(KEY, null) ?: return

        val array = Json.parseAny(text) as? JSONArray ?: return

        for (index in 0 until array.length()) {
            val node = array.opt(index) as? JSONObject ?: continue

            val item = Download()

            item.fromJson(node)

            /**
             * Прерванное скачивание возвращается в очередь.
             *
             * Приложение могли снять посреди загрузки; пометка «идёт»
             * пережила бы это и осталась навсегда, а файл так и не
             * дописался бы.
             */
            if (item.state == Download.RUNNING) {
                item.state = Download.QUEUED
            }

            items.add(item)
        }

        sweep()
    }

    /**
     * Убирает файлы, за которыми не стоит ни одной записи.
     *
     * Имя файла теперь несёт и качество (`ролик_720.mp4`), а прежде было
     * одно на ролик. От той поры на устройстве остаются файлы, о которых
     * приложение уже не знает: показать их оно не может, а место они
     * занимают — у одного ролика это бывают сотни мегабайт. Заодно
     * подметаются огрызки закачек, оборванных вместе с приложением.
     */
    private fun sweep() {
        val known = HashSet<String>()

        for (item in items) {
            known.add(item.fileName())

            item.file?.let { known.add(File(it).name) }

            // Недокачанное живой очереди трогать нельзя — она к нему вернётся.
            if (item.state != Download.DONE) {
                known.add(item.tempName("v"))
                known.add(item.tempName("a"))
            }
        }

        val files = folder().listFiles() ?: return

        for (file in files) {
            if (known.contains(file.name)) {
                continue
            }

            /**
             * Метём только своё.
             *
             * Рабочая папка теперь общая — «Загрузки/Troubadour», —
             * и человек вправе положить туда что угодно. Под уборку
             * идут лишь имена нашего вида: ролик, ступень, `mp4` или
             * огрызок закачки.
             */
            if (!OURS.matcher(file.name).matches()) {
                continue
            }

            val size = file.length()

            if (file.delete()) {
                Log.d {
                    "[YouTube/Скачивание] Ничей файл ${file.name} убран, " +
                        "${size / 1024 / 1024} МБ"
                }
            }
        }
    }

    @Synchronized
    fun save() {
        val array = JSONArray()

        for (item in items) {
            array.put(item.toJson())
        }

        store.edit().putString(KEY, array.toString()).apply()

        Notify.post(Notify.DOWNLOADS)
    }

    /** Запись о ролике **в этом качестве** либо ничего. */
    @Synchronized
    fun find(videoId: String, height: Int): Download? {
        restore()

        return items.firstOrNull { it.videoId == videoId && it.height == height }
    }

    /** Все качества этого ролика — от меньшего к большему. */
    @Synchronized
    fun itemsFor(videoId: String): List<Download> {
        restore()

        return items.filter { it.videoId == videoId }.sortedBy { it.height }
    }

    /** Скачанные качества этого ролика — только готовые. */
    fun readyFor(videoId: String): List<Download> =
        itemsFor(videoId).filter { it.state == Download.DONE }

    /** Есть ли у ролика хоть одно скачанное качество. */
    fun haveAny(videoId: String): Boolean = readyFor(videoId).isNotEmpty()

    /** Сколько всего занимают все качества этого ролика. */
    fun bytesFor(videoId: String): Long =
        itemsFor(videoId).fold(0L) { sum, item -> sum + item.received }

    /**
     * По одной записи на ролик — для списков. Порт `videos`.
     *
     * Качества у одного ролика соседствуют по существу, но в списке они
     * не разные ролики, а один и тот же: три карточки с одинаковым
     * названием и превью выглядят ошибкой, а не выбором. Поэтому список
     * показывает ролик, а качество спрашивается при открытии.
     *
     * Представителем берётся самое высокое из готовых, а если готовых
     * нет — самое дальнее по загрузке.
     */
    @Synchronized
    fun videos(): List<Download> {
        restore()

        val one = ArrayList<Download>()
        val seen = HashSet<String>()

        for (item in items) {
            if (!seen.add(item.videoId)) {
                continue
            }

            var best: Download? = null

            for (other in items.filter { it.videoId == item.videoId }) {
                val chosen = best

                if (chosen == null) {
                    best = other

                    continue
                }

                val otherDone = other.state == Download.DONE
                val chosenDone = chosen.state == Download.DONE

                if (otherDone != chosenDone) {
                    if (otherDone) {
                        best = other
                    }

                    continue
                }

                best = if (chosenDone) {
                    if (other.height > chosen.height) other else chosen
                } else {
                    if (share(other) > share(chosen)) other else chosen
                }
            }

            best?.let { one.add(it) }
        }

        return one
    }

    private fun share(item: Download): Float =
        if (item.total > 0) item.received.toFloat() / item.total else 0f

    /**
     * Что просили отменить.
     *
     * Идущую закачку нельзя просто выбросить из списка: она живёт
     * в потоке службы и о списке не знает. Поэтому имя откладывается
     * сюда, а цикл закачки сам замечает его между кусками и уходит,
     * прибрав за собой.
     */
    private val cancelling = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Наши имена: `<ролик>_<ступень>.mp4` и огрызки закачек. */
    private val OURS = java.util.regex.Pattern.compile(
        "^[A-Za-z0-9_-]{6,20}_(\\d+|ready)\\.(mp4|v\\.tmp|a\\.tmp)$"
    )

    /** Просили ли отменить эту закачку. */
    fun isCancelled(item: Download): Boolean = cancelling.contains(item.key())

    /**
     * Отменить закачку.
     *
     * Идущую помечаем: её оборвёт сам цикл, он же удалит недокачанное.
     * Ждущую в очереди убираем сразу — обрывать в ней нечего.
     */
    @Synchronized
    fun cancel(videoId: String, height: Int) {
        restore()

        val item = items.firstOrNull {
            it.videoId == videoId && it.height == height
        } ?: return

        Log.d { "[YouTube/Скачивание] Отмена: $videoId ${height}p (${item.state})" }

        if (item.state == Download.RUNNING) {
            cancelling.add(item.key())

            return
        }

        remove(videoId, height)
    }

    @Synchronized
    fun enqueue(item: Download) {
        restore()

        // Прежняя отмена этого же ролика новой закачке не помеха.
        cancelling.remove(item.key())

        // Заменяем только ту же ступень: прочие качества живут своей жизнью.
        items.removeAll { it.videoId == item.videoId && it.height == item.height }
        items.add(0, item)

        save()

        DownloadService.wake()
    }

    @Synchronized
    fun remove(videoId: String, height: Int) {
        restore()

        val item = items.firstOrNull { it.videoId == videoId && it.height == height }

        item?.where()?.let { where ->
            try {
                PublicStore.forget(where)
            } catch (ignored: Exception) {
                // Файла могло не быть — тогда и удалять нечего.
            }
        }

        items.removeAll { it.videoId == videoId && it.height == height }

        save()
    }

    /**
     * Переносит в «Загрузки» то, что скачано прежним порядком.
     *
     * До этой правки всё лежало в личной папке приложения, невидимое
     * ни файловым управляющим, ни галерее. Оставить его там значило бы
     * поделить скачанное надвое: старое не найти, новое на виду.
     *
     * Идёт отдельным потоком: перенос — это чтение и запись сотен
     * мегабайт, и делать это там, откуда рисуется список, нельзя.
     */
    fun migrate() {
        Thread {
            val own = ownFolder().absolutePath

            for (item in all()) {
                if (item.state != Download.DONE) {
                    continue
                }

                val path = item.file ?: continue

                if (!item.uri.isNullOrEmpty() || !path.startsWith(own)) {
                    continue
                }

                val file = File(path)

                if (!file.exists()) {
                    continue
                }

                val where = PublicStore.publish(file, file.name) ?: continue

                synchronized(this) {
                    if (where.startsWith("content://")) {
                        item.uri = where
                        item.file = null
                    } else {
                        item.file = where
                    }

                    save()
                }

                Log.d { "[YouTube/Скачивание] ${file.name} переехал в «Загрузки»" }
            }
        }.start()
    }

    @Synchronized
    fun next(): Download? {
        restore()

        return items.firstOrNull { it.state == Download.QUEUED }
    }

    /**
     * Скачивает один ролик. Зовётся из службы, с её потока.
     *
     * Путей три, и порядок между ними — не мелочь.
     *
     * Первыми идут раздельные дорожки с адресами: они дают ровно ту
     * ступень, которую человек выбрал, и берутся диапазонами байт.
     * Нет адресов — значит, ролик отдают подачей; поднимаем её отдельно
     * от плеера и складываем фрагменты. И только когда нет ни того
     * ни другого — склеенный поток.
     *
     * Прежде склеенный стоял вторым, и до подачи дело не доходило
     * никогда: склеенный есть почти у каждого ролика. Человек выбирал
     * 720p, а получал молча 360p — единственную ступень, которую
     * YouTube нынче кладёт в `formats`.
     */
    fun run(item: Download): Boolean {
        if (isCancelled(item)) {
            return dropCancelled(item)
        }

        item.state = Download.RUNNING
        item.received = 0
        item.total = 0

        save()

        val player = Api.playerResponse(item.videoId)

        if (player == null) {
            return fail(item, loc("Не удалось получить поток"))
        }

        var target = File(folder(), item.fileName())

        val formats = Streams.formatsFrom(player)

        val video = Streams.chooseVideo(formats, item.height)
        val audio = Streams.chooseAudio(formats, null)

        if (video != null && audio != null) {
            item.total = video.contentLength + audio.contentLength

            val videoFile = File(folder(), item.tempName("v"))
            val audioFile = File(folder(), item.tempName("a"))

            if (!fetch(video.url, videoFile, item) || !fetch(audio.url, audioFile, item)) {
                videoFile.delete()
                audioFile.delete()

                if (isCancelled(item)) {
                    return dropCancelled(item)
                }

                return fail(item, loc("Дорожка не скачалась"))
            }

            val assembled = assemble(videoFile, audioFile, target, item)

            videoFile.delete()
            audioFile.delete()

            if (!assembled) {
                if (isCancelled(item)) {
                    return dropCancelled(item)
                }

                return fail(item, loc("Дорожки не свелись"))
            }

            return finish(item, target)
        }

        /**
         * Раздельных дорожек с адресами не дали — значит, ролик отдают
         * подачей. Её и берём: она умеет ту же ступень, что и плеер.
         *
         * Оригинал в этом случае поднимает подачу отдельно от плеера
         * (`detachedSabrFor`) и складывает фрагменты своим писателем MP4.
         * Здесь тот же ход, только складывает их системный сводчик.
         */
        /**
         * Какую озвучку брать — по настройке «язык звука при скачивании».
         *
         * Названная человеком (при ладе «спрашивать каждый раз») старше
         * правила: он уже ответил на тот же вопрос.
         */
        val wantedTrack = item.audioTrack?.takeIf { it.isNotEmpty() }
            ?: Streams.trackIdForMode(Settings.downloadAudioLanguage, player)

        val sabr = Streams.detachedSabrFor(player, item.height, wantedTrack)

        if (sabr != null) {
            return fromSabr(item, sabr, target)
        }

        Log.d { "[YouTube/Скачивание] Подачи нет — остаётся склеенный поток" }

        val progressive = Streams.progressiveUrlIn(player)

        if (!progressive.isNullOrEmpty()) {
            /**
             * Склеенный — не та ступень, которую просили, и запись
             * об этом должна говорить правду: подписано в списке будет
             * то качество, которое и вправду лежит в файле.
             */
            val got = Streams.progressiveHeight

            if (got > 0 && got != item.height) {
                Log.d {
                    "[YouTube/Скачивание] Просили ${item.height}p, " +
                        "склеенный даёт ${got}p"
                }

                /**
                 * Эта ступень у ролика могла быть скачана и раньше —
                 * тогда запись о ней одна, и она сейчас переписывается.
                 * Без этого рядом оказались бы две записи об одном
                 * и том же файле, и уборка одной уносила бы файл
                 * у другой.
                 */
                itemsFor(item.videoId)
                    .firstOrNull { it !== item && it.height == got }
                    ?.let { remove(it.videoId, it.height) }

                item.height = got

                target = File(folder(), item.fileName())
            }

            item.total = 0

            if (!fetch(progressive, target, item)) {
                if (isCancelled(item)) {
                    target.delete()

                    return dropCancelled(item)
                }

                return fail(item, loc("Не скачалось"))
            }

            return finish(item, target)
        }

        return fail(
            item,
            loc("YouTube не дал ни одной дорожки, которую можно было бы забрать целиком.")
        )
    }

    /**
     * Скачивание подачей: фрагменты собираются в два файла, потом сводятся.
     *
     * Просить приходится повторно — за один ответ приходит несколько
     * секунд видео, а не весь ролик.
     */
    private fun fromSabr(item: Download, sabr: Sabr, target: File): Boolean {
        val videoFile = File(folder(), item.tempName("v"))
        val audioFile = File(folder(), item.tempName("a"))

        try {
            FileOutputStream(videoFile).use { videoOut ->
                FileOutputStream(audioFile).use { audioOut ->
                    sabr.videoInit?.let { videoOut.write(it) }
                    sabr.audioInit?.let { audioOut.write(it) }

                    var wroteVideo = 0
                    var wroteAudio = 0

                    var idle = 0

                    while (idle < 3) {
                        if (isCancelled(item)) {
                            break
                        }

                        var progress = false

                        for (sequence in sabr.videoSequences()) {
                            if (sequence <= wroteVideo) {
                                continue
                            }

                            sabr.videoSegment(sequence)?.let {
                                videoOut.write(it)

                                item.received += it.size
                                wroteVideo = sequence

                                progress = true
                            }
                        }

                        for (sequence in sabr.audioSequences()) {
                            if (sequence <= wroteAudio) {
                                continue
                            }

                            sabr.audioSegment(sequence)?.let {
                                audioOut.write(it)

                                item.received += it.size
                                wroteAudio = sequence

                                progress = true
                            }
                        }

                        if (progress) {
                            idle = 0

                            save()
                        } else {
                            idle++
                        }

                        val at = sabr.startForSequence(
                            wroteVideo + 1,
                            if (sabr.videoSegmentCount > 0 && sabr.duration > 0) {
                                sabr.duration / sabr.videoSegmentCount
                            } else {
                                5.0
                            }
                        )

                        /**
                         * Сколько всего выйдет — считаем на ходу.
                         *
                         * У подачи размера дорожки нет: сервер отдаёт её
                         * кусками и общего числа байт не называет.
                         * Поэтому меряем по времени: сколько секунд уже
                         * записано и сколько весит эта запись — отсюда
                         * и вес всего ролика. Оценка сходится с первых
                         * же кусков.
                         *
                         * Без неё доля оставалась нулевой, полоса стояла
                         * на месте, и скачивание длиной в час выглядело
                         * зависшим — а оно шло.
                         */
                        if (at > 5.0 && sabr.duration > 0) {
                            item.total = (item.received / at * sabr.duration).toLong()
                        }

                        if (sabr.duration > 0 && at >= sabr.duration) {
                            break
                        }

                        /** След в журнале — иначе о долгой работе сказать нечего. */
                        if (wroteVideo % 20 == 0 && progress) {
                            Log.d {
                                "[YouTube/Скачивание] ${item.videoId}: " +
                                    "${at.toInt()} из ${sabr.duration.toInt()} с, " +
                                    "${item.received / (1024 * 1024)} МБ"
                            }
                        }

                        sabr.forgetBefore(0.0)

                        if (!sabr.requestMoreFrom(at)) {
                            idle++
                        }
                    }
                }
            }

            if (!assemble(videoFile, audioFile, target, item)) {
                if (isCancelled(item)) {
                    return dropCancelled(item)
                }

                return fail(item, loc("Дорожки не свелись"))
            }

            return finish(item, target)
        } catch (error: Exception) {
            Log.d { "[YouTube/Скачивание] Подачей не вышло: ${error.message}" }

            return fail(item, loc("Не скачалось"))
        } finally {
            videoFile.delete()
            audioFile.delete()
        }
    }

    /**
     * Забирает дорожку целиком, дозапрашивая остаток. Порт возобновляемой
     * загрузки из `YTDownloads`.
     *
     * Длинный поток YouTube одним заходом не отдаёт: соединение рвётся
     * на середине — на пробах это выходило мегабайтах на тридцати.
     * Прежде такой обрыв считался отказом, файл выбрасывался, и ролик
     * подлиннее не скачивался никогда, сколько ни начинай заново.
     *
     * Поэтому берём по кускам: сколько дали — то дописываем, а за
     * остатком идём снова, с `Range` от места обрыва. Пустой заход
     * подряд трижды — вот тогда и вправду отказ.
     */
    private fun fetch(url: String?, target: File, item: Download): Boolean {
        if (url.isNullOrEmpty()) {
            return false
        }

        /**
         * Сколько уже было записано **до** этого вызова.
         *
         * Дорожек бывает две, и звук идёт следом за видео: доля должна
         * продолжаться с достигнутого, а не начинаться заново.
         */
        val base = item.received

        var have = if (target.exists()) target.length() else 0L
        var total = 0L
        var empty = 0

        while (true) {
            if (isCancelled(item)) {
                return false
            }

            val builder = Http.request(url) ?: return false

            builder.header("User-Agent", Api.mediaUserAgent())

            if (have > 0) {
                builder.header("Range", "bytes=$have-")
            }

            var got = 0L

            /**
             * Сервер вправе не понять `Range` и прислать всё сначала.
             * Дописывать такое к прежнему — значит склеить два начала
             * в один негодный файл; тогда начинаем заново.
             */
            var fromStart = false

            try {
                FileOutputStream(target, have > 0).use { out ->
                    val response = Http.stream(
                        builder.build(),
                        { headers ->
                            if (have > 0 && headers.statusCode == 200) {
                                fromStart = true
                            }

                            if (total <= 0 && headers.expectedLength > 0) {
                                total = have + headers.expectedLength

                                if (item.total <= 0) {
                                    /**
                                     * Уже лежащее на диске входит и в долю,
                                     * и в общий вес: продолженная загрузка
                                     * иначе показывала бы вес остатка,
                                     * а не ролика.
                                     */
                                    item.total = base + total
                                }
                            }
                        }
                    ) { buffer, length ->
                        if (fromStart) {
                            return@stream false
                        }

                        out.write(buffer, 0, length)

                        got += length
                        item.received = base + have + got

                        /**
                         * Долю обновляем не на каждый кусок, а раз
                         * в четверть мегабайта: запись в настройки
                         * и оповещение на каждые шестьдесят четыре
                         * килобайта — это сотни обращений к диску
                         * на один ролик.
                         */
                        if (item.received % (256 * 1024) < length) {
                            save()
                        }

                        // Просили отменить — обрываем поток на этом же куске.
                        !isCancelled(item)
                    }

                    if (fromStart) {
                        Log.d {
                            "[YouTube/Скачивание] ${target.name}: сервер отдал " +
                                "всё сначала — начинаем заново"
                        }
                    } else if (!response.isSuccessful && got == 0L) {
                        Log.d {
                            "[YouTube/Скачивание] ${target.name}: " +
                                "код ${response.statusCode}" +
                                (response.error?.message?.let { " ($it)" } ?: "")
                        }
                    }
                }
            } catch (error: Exception) {
                Log.d { "[YouTube/Скачивание] ${target.name}: ${error.message}" }

                return false
            }

            if (fromStart) {
                target.delete()

                have = 0
                total = 0
                item.received = base

                continue
            }

            have += got
            item.received = base + have

            if (isCancelled(item)) {
                return false
            }

            if (total > 0 && have >= total) {
                return true
            }

            if (got <= 0) {
                empty++

                if (empty >= 3) {
                    Log.d {
                        "[YouTube/Скачивание] ${target.name}: остаток не идёт, " +
                            "взято ${have / 1024 / 1024} МБ"
                    }

                    return false
                }

                continue
            }

            empty = 0

            /**
             * Длины сервер не назвал, а поток кончился без обрыва —
             * значит, это и был весь файл.
             */
            if (total <= 0) {
                return true
            }

            Log.d {
                "[YouTube/Скачивание] ${target.name}: докачиваем с " +
                    "${have / 1024 / 1024} из ${total / 1024 / 1024} МБ"
            }
        }
    }

    /**
     * Сводит две дорожки в один MP4 — своим писателем.
     *
     * Порт `YTMp4Writer` разбирает фрагменты и пишет обычный MP4 сам,
     * не полагаясь ни на `MediaMuxer` (его нет до API 18), ни на
     * `MediaExtractor` (он должен прочитать фрагментированный файл,
     * чего разборщики тех лет толком не умеют). Одна дорога на всех
     * версиях — и проверить её можно на самом старом устройстве.
     *
     * Системный сводчик остаётся про запас: если свой писатель почему-то
     * отказал, а `MediaMuxer` есть, попробуем и его. В журнале видно,
     * кто из двоих собрал файл.
     */
    private fun assemble(
        videoFile: File,
        audioFile: File,
        target: File,
        item: Download
    ): Boolean {
        val ours = Mp4Writer.writeTo(target, videoFile, audioFile) { !isCancelled(item) }

        if (ours) {
            return true
        }

        if (isCancelled(item) ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2
        ) {
            return false
        }

        Log.d { "[YouTube/Сборка] Своим писателем не вышло — пробуем системный сводчик" }

        return mux(videoFile, audioFile, target)
    }

    /**
     * Сводит две дорожки в один MP4.
     *
     * **Здесь Android избавил от целого файла оригинала.** Там пришлось
     * написать свой писатель MP4 (`YTMp4Writer`, 965 строк): собрать
     * `moov` с описанием обоих кодеков, разложить сэмплы по таблицам
     * `stts`, `stsc`, `stsz`, `stco` и посчитать смещения — потому что
     * на iOS 5 сводить нечем, `AVAssetWriter` там принимает только
     * распакованные сэмплы.
     *
     * `MediaMuxer` делает это сам. Появился он в API 18, и на 16–17 его
     * нет — там скачивается склеенный поток, тот самый, что играет
     * без разбора. Хуже качеством, но целиком рабочий; заводить ради двух
     * версий свой писатель значило бы переносить те самые девятьсот
     * строк, от которых платформа как раз и избавляет.
     */
    private fun mux(videoFile: File, audioFile: File, target: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return false
        }

        var muxer: MediaMuxer? = null

        val extractors = ArrayList<MediaExtractor>()

        try {
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val tracks = ArrayList<Pair<MediaExtractor, Int>>()

            for (file in listOf(videoFile, audioFile)) {
                if (!file.exists() || file.length() == 0L) {
                    continue
                }

                val extractor = MediaExtractor()

                extractor.setDataSource(file.absolutePath)

                extractors.add(extractor)

                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                    if (!mime.startsWith("video/") && !mime.startsWith("audio/")) {
                        continue
                    }

                    extractor.selectTrack(index)

                    tracks.add(extractor to muxer.addTrack(format))

                    break
                }
            }

            if (tracks.isEmpty()) {
                return false
            }

            muxer.start()

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()

            for ((extractor, track) in tracks) {
                while (true) {
                    info.offset = 0
                    info.size = extractor.readSampleData(buffer, 0)

                    if (info.size < 0) {
                        break
                    }

                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags

                    muxer.writeSampleData(track, buffer, info)

                    extractor.advance()
                }
            }

            muxer.stop()

            return true
        } catch (error: Throwable) {
            Log.d { "[YouTube/Скачивание] Свести не вышло: ${error.message}" }

            return false
        } finally {
            for (extractor in extractors) {
                try {
                    extractor.release()
                } catch (ignored: Exception) {
                    // Уже отпущен — не беда.
                }
            }

            try {
                muxer?.release()
            } catch (ignored: Exception) {
                // То же самое.
            }
        }
    }

    private fun finish(item: Download, target: File): Boolean {
        val size = target.length()

        /**
         * Готовое переезжает в общие «Загрузки».
         *
         * На старых устройствах оно уже там — [PublicStore.publish]
         * вернёт тот же путь и только скажет о файле медиатеке.
         * С Android 10 файл переносится из личной папки, и от него
         * остаётся `content://`.
         *
         * Не вышло — не беда: ролик остаётся там, где собрался. Потерять
         * скачанное из-за неудавшегося переезда было бы худшим исходом.
         */
        val where = PublicStore.publish(target, target.name)

        item.state = Download.DONE
        item.received = size
        item.total = size

        if (where != null && where.startsWith("content://")) {
            item.uri = where
            item.file = null
        } else {
            item.uri = null
            item.file = where ?: target.absolutePath
        }

        save()

        Log.d {
            "[YouTube/Скачивание] ${item.videoId}: готово, ${size / 1024} КБ, " +
                (item.where() ?: "?")
        }

        return true
    }

    /**
     * Отменённое убираем начисто: и запись очереди, и недокачанное.
     *
     * Отказом это не считается — человек сам так решил, и красная
     * строка «не скачалось» была бы здесь неправдой.
     */
    private fun dropCancelled(item: Download): Boolean {
        cancelling.remove(item.key())

        for (name in listOf(
            item.tempName("v"), item.tempName("a"), item.fileName()
        )) {
            try {
                File(folder(), name).delete()
            } catch (ignored: Exception) {
                // Файла могло не быть — тогда и удалять нечего.
            }
        }

        remove(item.videoId, item.height)

        Log.d { "[YouTube/Скачивание] ${item.videoId}: отменено, убрано за собой" }

        return false
    }

    private fun fail(item: Download, reason: String): Boolean {
        item.state = Download.FAILED
        item.error = reason

        save()

        Log.d { "[YouTube/Скачивание] ${item.videoId}: ${reason}" }

        return false
    }
}
