package ru.computershik.troubadour.player

import android.util.Base64
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.net.Api
import ru.computershik.troubadour.net.Http
import ru.computershik.troubadour.net.NSig
import ru.computershik.troubadour.net.mediaUserAgent
import ru.computershik.troubadour.net.streamClientInfo
import java.io.ByteArrayOutputStream

/** Дорожка так, как её называет подача: номер, время правки, метки. */
class SabrFormat(val itag: Int, val lastModified: Long) {
    var xtags: String? = null
}

/** Заголовок сегмента в потоке UMP. */
private class SabrHeader {
    var headerId: Int = 0
    var itag: Int = 0
    var lastModified: Long = 0
    var isInit: Boolean = false
    var sequence: Int = 0
    var startMs: Long = 0
    var durationMs: Long = 0
}

/**
 * Подача SABR — способ, которым YouTube отдаёт видео теперь.
 *
 * Порт `TRSabrStream` из TubeReplacer, но без сгенерированных классов
 * protobuf: сообщения собираются вручную через [Proto]. Номера полей взяты
 * из `.proto` там же, так что они не угаданы.
 *
 * Отличие от прежнего способа в том, что адресов у дорожек больше нет.
 * Вместо двух десятков ссылок, по которым можно ходить диапазонами байт,
 * сервер даёт один адрес и ждёт по нему **запрос**: какие дорожки нужны,
 * с какого места и что у нас уже есть. Отвечает он потоком UMP — чередой
 * частей, из которых складываются те же самые фрагменты fMP4.
 *
 * Просить приходится повторно: за один ответ приходит несколько секунд
 * видео, а не весь ролик.
 */
class Sabr(
    private var url: String,
    private var config: ByteArray?,
    val videoId: String,
    private val poToken: String?
) {

    /**
     * Замок разговора с подачей.
     *
     * Источников два, потоков загрузки у них тоже два, а подача одна
     * и состояние у неё общее — перечень набранного, счётчики кусков,
     * сам поток ответа. Двое сразу её ломают.
     */
    private val talk = Any()

    /** Миг прыжка; −1, когда обе дорожки на него встали. */
    private var anchorMs = -1L

    private var videoAnchored = false
    private var audioAnchored = false

    private var anchorStartSeconds = -1.0

    /** Сколько фрагментов подача отдала за всё время. */
    private var delivered = 0L

    /**
     * Чей источник дорожки открыт у плеера сейчас; null — закрыт.
     *
     * Закрытая дорожка не в счёт при согласовании просьб: тянуть живую
     * к мёртвой значит остановить и её.
     *
     * Хранится не признак, а сам источник, и это не придирка. При
     * перемотке плеер сперва заводит новый источник, а старый закрывает
     * следом; признак от такого закрытия гас у живого источника, и его
     * нужды переставали учитываться.
     */
    private var videoOwner: Any? = null
    private var audioOwner: Any? = null

    private var requestNumber = 0

    private var fixed = false
    private var redirected = false

    /** Сколько запросов подряд подача отбила отказом. */
    private var failures = 0

    /** Просил ли сервер обновить ответ `/player` — хоть с токеном, хоть без. */
    @Volatile
    var needsReload = false
        private set

    private var reloadTokenValue: String? = null

    /**
     * Токен для перезапроса ответа `/player`, если сервер его просил.
     *
     * Подача отвечает «твой ответ устарел» и присылает токен вместо
     * данных. С ним надо сходить в `/player` заново — тогда придут свежие
     * адрес подачи и настройки, и запрос можно повторить.
     */
    val reloadToken: String?
        get() = if (needsReload) reloadTokenValue else null

    private var gotVideo: SabrFormat? = null
    private var gotAudio: SabrFormat? = null

    private var firstVideoSeq = 0
    private var firstAudioSeq = 0
    private var lastVideoSeq = 0
    private var lastAudioSeq = 0

    private var rangeStartMs = 0L
    private var videoFilledMs = 0L
    private var audioFilledMs = 0L

    private var playbackCookie: ByteArray? = null
    private var backoffMs = 0L

    private var video: SabrFormat? = null
    private var audio: SabrFormat? = null

    private var allVideo: List<SabrFormat> = emptyList()
    private var allAudio: List<SabrFormat> = emptyList()

    /** Заголовок дорожки — `moov` с описанием кодека; null, если не пришёл. */
    var videoInit: ByteArray? = null
        private set

    var audioInit: ByteArray? = null
        private set

    /**
     * Номер дорожки, которой принадлежит нынешний [videoInit].
     *
     * Сервер меняет дорожку сам, когда сеть проседает, и присылает новый
     * заголовок с другими SPS/PPS. По этому номеру видно, что заголовок
     * сменился, — и разбирать его надо заново, иначе кадры новой дорожки
     * поедут через старое описание кодека.
     */
    var videoInitItag = 0
        private set

    private var audioInitItag = 0

    /**
     * Принять заголовки дорожек, добытые не подачей.
     *
     * У эфира их в ответе подачи нет вовсе — они берутся нулевым куском
     * обычного адреса дорожки, и класть их надо сюда: дальше всё идёт
     * как обычно, кусками подачи.
     */
    fun adoptInit(video: ByteArray, videoItag: Int, audio: ByteArray?, audioItag: Int) {
        videoInit = video
        videoInitItag = videoItag

        if (audio != null) {
            audioInit = audio
            audioInitItag = audioItag
        }
    }

    /** Номер звуковой дорожки, чей заголовок пришёл; 0, пока не пришёл. */
    val audioItag: Int
        get() = audioInitItag

    /** Сколько запросов ушло подаче за всё время. */
    val requests: Int
        get() = requestNumber

    /**
     * Замок хранилища фрагментов.
     *
     * Отдельный от [talk], и это важно. Через [talk] идёт разговор
     * с сервером, а он длится секундами; главный поток, зайдя сюда
     * за уборкой старого, встал бы на всё это время вместе с картинкой.
     * Этот же замок держат считанные микросекунды — на одну запись
     * в отображение.
     *
     * Без него приложение падало: главный поток обходил хранилище
     * в `forgetBefore`, а поток загрузки в этот же миг клал туда
     * пришедший кусок. На 1080p это ловилось за минуту —
     * `ConcurrentModificationException` в `forget`, — а на дорожках
     * полегче тот же обход просто успевал закончиться первым.
     */
    private val vault = Any()

    private val videoSegments = HashMap<Int, ByteArray>()
    private val audioSegments = HashMap<Int, ByteArray>()

    private val videoTimes = HashMap<Int, LongArray>()
    private val audioTimes = HashMap<Int, LongArray>()

    /** Сколько всего фрагментов в дорожке; 0, пока сервер не сказал. */
    var videoSegmentCount = 0
        private set

    /**
     * Номер видеодорожки, которую сервер выбрал **сам**.
     *
     * На подаче качество назначаем не мы: мы лишь перечисляем, что нам
     * подходит. Поэтому спрашивать, что играет, надо у ответа, а не у своих
     * пожеланий — иначе меню качества показывает желаемое.
     */
    var playingItag = 0

    /**
     * Номера дорожек, куски которых сервер и вправду прислал.
     *
     * У эфира заголовков дорожек в ответе нет вовсе, и узнать, что
     * именно нам шлют, можно только по кускам. По этим номерам потом
     * добираются недостающие заголовки.
     */
    var deliveredVideoItag = 0
        private set

    /**
     * Идёт ли трансляция прямо сейчас.
     *
     * У эфира своя повадка: заголовок дорожки лежит внутри каждого куска,
     * длительности у кусков нет, а разметка времени ведётся от начала
     * вещания — у иного канала это двадцатые сутки. Всё это учитывается
     * ниже, и знать об этом надо заранее.
     */
    var liveMode = false

    /** Время первого пришедшего куска — с него и начинается показ. */
    var liveStartSeconds = 0.0
        private set
    /**
     * Докуда снят эфир по словам сервера: номер куска и его время.
     *
     * Сервер сообщает это в каждом ответе, а мы прежде пропускали мимо.
     * Без этих чисел источник просил кусок, которого ещё нет, получал
     * пустоту и просил снова — за две с половиной минуты набегало
     * два десятка пустых заходов; а на третьем таком заходе он считал
     * кусок потерянным и перешагивал через него, отчего картинка
     * коротко дёргалась.
     */
    var liveHeadSequence = 0
        private set

    var liveHeadSeconds = 0.0
        private set

    /** Когда пришёл последний кусок — по часам устройства, мс. */
    @Volatile
    var lastDeliveryAt = 0L
        private set

    var deliveredAudioItag = 0
        private set

    /**
     * Ступень, которую мы просим, — в точках по короткой стороне.
     *
     * Уходит в состояние клиента двумя полями сразу: привычной ступенью
     * и размером окна. Оба сервер читает как «сколько человеку нужно»,
     * и назвать меньше названного — значит попросить меньше.
     */
    var wantedHeight = 0

    /** Длительность ролика в секундах; 0, пока сервер не сказал. */
    var duration = 0.0
        private set

    private val openHeaders = HashMap<Int, SabrHeader>()
    private val openBodies = HashMap<Int, ByteArrayOutputStream>()

    private val seen = StringBuilder()

    /** Хвост прошлого ответа, не разобранный до конца. */
    private var tail = ByteArray(0)

    companion object {

        /** Насколько далеко позади живого края эфиру позволено просить, мс. */
        private const val LIVE_REACH_MS = 40_000L



        /**
         * Байты из записи base64url.
         *
         * И настройки подачи, и PO-токен приходят именно в ней: вместо `+`
         * и `/` там `-` и `_`, а хвост из знаков `=` опущен. Обычный разбор
         * base64 на таком возвращает пустоту — молча, и поле уходит пустым.
         * Сервер отвечает на это `sabr.malformed_config`, и по коду ответа
         * догадаться не о чем.
         */
        @JvmStatic
        fun dataFromBase64Url(source: String?): ByteArray? {
            if (source.isNullOrEmpty()) {
                return null
            }

            return try {
                Base64.decode(source, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            } catch (error: Exception) {
                null
            }
        }
    }

    /**
     * Вживляет свежие адрес подачи и настройки — те, что пришли
     * из `/player`, взятого заново по токену.
     *
     * Набранное остаётся: ролик тот же. Сбрасывается только то, что
     * принадлежало прежней сессии, — печенье воспроизведения и пометка
     * о правке `n` в адресе.
     */
    fun adopt(abrUrl: String?, freshConfig: ByteArray?) {
        if (abrUrl.isNullOrEmpty() || freshConfig == null || freshConfig.isEmpty()) {
            return
        }

        url = abrUrl
        config = freshConfig

        fixed = false
        failures = 0
        playbackCookie = null

        needsReload = false
        reloadTokenValue = null
    }

    /**
     * Все дорожки, какие есть у ролика, — их список уходит в запрос
     * предпочтениями.
     *
     * Выбирает сервер, а не мы: в рабочем образце перечислены **все**
     * видео- и звуковые дорожки разом, и это оказалось важнее, чем кажется.
     * Пока называли по одной, сервер то отвечал «звук не выбран», то
     * присылал медиа без заголовков дорожек.
     */
    fun setAvailable(video: List<SabrFormat>, audio: List<SabrFormat>) {
        allVideo = video
        allAudio = audio
    }

    private fun xtagsFor(itag: Int, lastModified: Long): String? {
        for (list in listOf(allVideo, allAudio)) {
            for (format in list) {
                if (format.itag == itag && format.lastModified == lastModified) {
                    return format.xtags
                }
            }
        }

        return null
    }

    private fun isVideoItag(itag: Int): Boolean = allVideo.any { it.itag == itag }

    private fun tierForScreen(edge: Int): Int {
        val tiers = intArrayOf(144, 240, 360, 480, 720, 1080)

        var best = tiers.last()
        var closest = Int.MAX_VALUE

        for (tier in tiers) {
            val distance = Math.abs(tier - edge)

            if (distance < closest) {
                closest = distance
                best = tier
            }
        }

        return best
    }

    // --- Сборка запроса ---------------------------------------------------

    private fun formatId(format: SabrFormat): ProtoWriter {
        val writer = ProtoWriter()

        writer.putVarint(format.itag.toLong(), 1)
        writer.putVarint(format.lastModified, 2)
        writer.putString(format.xtags, 3)

        return writer
    }

    private fun streamerContext(): ProtoWriter {
        val info = ProtoWriter()

        val who = Api.streamClientInfo()

        who.make?.let { info.putString(it, 12) }
        who.model?.let { info.putString(it, 13) }

        info.putVarint(who.number.toLong(), 16)
        info.putString(who.version, 17)
        info.putString(who.osName, 18)
        info.putString(who.osVersion, 19)

        info.putString("en-US", 21)
        info.putString("US", 22)
        info.putVarint(1920, 37)
        info.putVarint(1080, 38)
        info.putVarint(1, 41)
        info.putVarint(2, 46)
        info.putVarint(1920, 55)
        info.putVarint(1080, 56)
        info.putFloat(1.0f, 65)

        val context = ProtoWriter()

        context.putMessage(info, 1)

        dataFromBase64Url(poToken)?.let { context.putData(it, 2) }

        playbackCookie?.let { context.putData(it, 3) }

        return context
    }

    private fun clientState(startMs: Long): ProtoWriter {
        val state = ProtoWriter()

        val metrics = App.require().resources.displayMetrics

        var side = maxOf(metrics.widthPixels, metrics.heightPixels)
        var edge = minOf(metrics.widthPixels, metrics.heightPixels)

        var wanted = wantedHeight

        if (wanted > 0) {
            edge = maxOf(edge, wanted)
            side = maxOf(side, wanted * 16 / 9)
        } else {
            wanted = tierForScreen(edge)
        }

        state.putVarint(maxOf(1, side).toLong(), 18)
        state.putVarint(maxOf(1, edge).toLong(), 19)

        state.putVarint(wanted.toLong(), 21)

        state.putBool(false, 22)

        state.putVarint(maxOf(0L, startMs), 28)

        // Видно (1) и играет (0) — то же, что в образце.
        state.putVarint(1, 34)
        state.putFloat(1.0f, 35)
        state.putVarint(0, 40)
        state.putVarint(0, 44)

        /**
         * Ровный звук включён, VP9 не нужен вовсе.
         *
         * В оригинале причина была такая: «его не декодирует ни одно
         * устройство из нашего списка». Здесь список открытый, и VP9
         * многие устройства как раз декодируют, — но дорожки мы всё равно
         * отбираем по `avc1`, а обещать серверу то, чего не возьмём,
         * значит получить это вместо нужного.
         */
        state.putBool(true, 46)
        state.putBool(false, 58)
        state.putBool(false, 76)

        return state
    }

    /**
     * Что набрано у дорожки **на самом деле**: первый лежащий в памяти
     * кусок и сплошной ряд за ним. Номера первого и последнего в ряду,
     * начало первого и конец последнего, мс.
     *
     * Прежде перечень строился по крайним номерам — «от первого
     * пришедшего до последнего пришедшего», — и это скрывало дыры.
     * Сервер, пропустив кусок, получал от нас сообщение, что кусок
     * у нас есть, и не присылал его уже никогда: источник дважды просил
     * впустую и шагал через дыру. Ряд же, оборванный на дыре, честен:
     * всё, что за ней, сервер вправе прислать заново, и первым пришлёт
     * именно недостающее.
     */
    /**
     * Ряд набранного, от которого считается «докуда просить дальше».
     *
     * У записи это **первый** ряд: дыра позади — тоже нужда, её всегда
     * можно закрыть, ролик никуда не денется. У эфира наоборот — важен
     * **последний**, ближний к живому краю: первый остаётся в прошлом,
     * и просьба, отмеренная от него, целит в уже набранное. Сервер
     * на такую просьбу отвечает пустотой, и показ встаёт, хотя
     * трансляция идёт.
     */
    private fun heldRange(isVideo: Boolean): LongArray? =
        if (liveMode) heldRuns(isVideo).lastOrNull() else heldRuns(isVideo).firstOrNull()

    /**
     * Все сплошные ряды, что лежат в памяти, по порядку. Обычно ряд
     * один; после пропуска их два — до дыры и после. Серверу называются
     * оба: тогда он не присылает заново то, что за дырой, а закрыв её,
     * продолжает с того, чего у нас ещё нет.
     */
    /**
     * Обычная длина куска этой дорожки, мс.
     *
     * Нужна там, где длина последнего куска ещё не известна: у эфира
     * сервер её не присылает вовсе, а вычисляется она по расстоянию
     * до следующего куска — которого в этот миг ещё нет.
     *
     * Берём по уже известным: у видео это около пяти секунд, у звука
     * около десяти. Ничего не известно — отвечаем привычными числами,
     * они всё равно вернее нуля.
     */
    private fun typicalSpanMs(isVideo: Boolean): Long {
        var sum = 0L
        var count = 0

        synchronized(vault) {
            for (pair in (if (isVideo) videoTimes else audioTimes).values) {
                if (pair.size >= 2 && pair[1] > 0) {
                    sum += pair[1]
                    count++
                }
            }
        }

        if (count > 0) {
            return sum / count
        }

        return if (isVideo) 5000L else 10000L
    }

    private fun heldRuns(isVideo: Boolean): List<LongArray> {
        val runs = ArrayList<LongArray>()

        val keys: List<Int>
        val times: Map<Int, LongArray>

        synchronized(vault) {
            val storage = if (isVideo) videoSegments else audioSegments

            if (storage.isEmpty()) {
                return runs
            }

            keys = storage.keys.sorted()
            times = HashMap(if (isVideo) videoTimes else audioTimes)
        }

        var first = keys[0]
        var last = first

        fun close() {
            val head = times[first]
            val tail = times[last]

            if (head != null && tail != null) {
                /**
                 * Серверу сообщаем **только то, что есть**.
                 *
                 * Соблазн дописать сюда предполагаемую длину последнего
                 * куска — у эфира она приходит с опозданием — велик,
                 * и я на него поддался: ряд из одного пятисекундного
                 * куска обещал двадцать секунд. Сервер, поверив, не
                 * прислал ничего: по его счёту у нас всё уже есть.
                 * Завышать перечень так же вредно, как занижать, — только
                 * ошибка выходит тише.
                 *
                 * Где именно кончается набранное, важно ещё и тому, кто
                 * решает, с какого мига просить дальше; там оценка
                 * уместна, и она сделана отдельно, в `earliestNeedMs`.
                 * Пятым числом отдаём начало последнего куска — по нему
                 * и видно, известна ли его длина.
                 */
                val span = if (tail.size >= 2) tail[1] else 0L

                runs.add(
                    longArrayOf(
                        first.toLong(), last.toLong(), head[0], tail[0] + span, tail[0]
                    )
                )
            }
        }

        for (index in 1 until keys.size) {
            if (keys[index] != last + 1) {
                close()

                first = keys[index]
            }

            last = keys[index]
        }

        close()

        return runs
    }

    private fun putBufferedRange(request: ProtoWriter, format: SabrFormat?, isVideo: Boolean) {
        if (format == null) {
            return
        }

        for (held in heldRuns(isVideo)) {
            val range = ProtoWriter()

            range.putMessage(formatId(format), 1)
            range.putVarint(held[2], 2)
            range.putVarint(maxOf(0L, held[3] - held[2]), 3)
            range.putVarint(held[0], 4)
            range.putVarint(held[1], 5)

            request.putMessage(range, 3)
        }
    }

    private fun requestBodyFrom(startMs: Long): ByteArray {
        val request = ProtoWriter()

        request.putMessage(clientState(startMs), 1)

        request.putData(config, 5)

        putBufferedRange(request, gotVideo, true)
        putBufferedRange(request, gotAudio, false)

        for (format in allAudio) {
            request.putMessage(formatId(format), 16)
        }

        for (format in allVideo) {
            request.putMessage(formatId(format), 17)
        }

        request.putMessage(streamerContext(), 19)

        return request.data()
    }

    // --- Разбор ответа ----------------------------------------------------

    private fun parseHeader(chunk: UmpChunk): SabrHeader {
        val header = SabrHeader()

        var range: ProtoReader? = null

        val reader = chunk.reader()

        while (reader.next()) {
            when (reader.field) {
                1 -> header.headerId = reader.takeVarint().toInt()
                3 -> header.itag = reader.takeVarint().toInt()
                4 -> header.lastModified = reader.takeVarint()
                8 -> header.isInit = reader.takeVarint() != 0L
                9 -> header.sequence = reader.takeVarint().toInt()
                11 -> header.startMs = reader.takeVarint()
                12 -> header.durationMs = reader.takeVarint()
                15 -> range = reader.takeMessage()
            }
        }

        if (header.durationMs <= 0 && range != null) {
            applyTimeRange(range, header)
        }

        if (!header.isInit && header.durationMs <= 0) {
            Log.d { "[YouTube/Подача] Заголовок без времени (${chunk.length} байт)" }
        }

        return header
    }

    private fun applyTimeRange(reader: ProtoReader, header: SabrHeader) {
        var start = 0L
        var length = 0L
        var scale = 0L

        while (reader.next()) {
            when (reader.field) {
                1 -> start = reader.takeVarint()
                2 -> length = reader.takeVarint()
                3 -> scale = reader.takeVarint()
            }
        }

        if (scale <= 0) {
            return
        }

        header.startMs = start * 1000 / scale
        header.durationMs = length * 1000 / scale
    }

    /**
     * Сведения об эфире: номер и время последнего снятого куска.
     *
     * Время приходит в собственной шкале сервера — обычно микросекунды,
     * делитель назван рядом; приводим к своим миллисекундам.
     */
    private fun parseLiveHead(chunk: UmpChunk) {
        val reader = chunk.reader()

        var sequence = 0
        var time = 0L
        var scale = 0L

        while (reader.next()) {
            when (reader.field) {
                3 -> sequence = reader.takeVarint().toInt()
                12 -> time = reader.takeVarint()
                13 -> scale = reader.takeVarint()
            }
        }

        /**
         * Номер здесь — свой, серверный, с нашими номерами кусков он
         * не совпадает (у нас 346602, у него 355238 на том же месте).
         * Полезно только время: по нему и видно, снят ли уже кусок,
         * который мы собираемся просить.
         */
        if (sequence > 0) {
            liveHeadSequence = sequence
        }

        if (time > 0 && scale > 0) {
            val fresh = time.toDouble() / scale

            if (liveHeadSeconds <= 0) {
                Log.d { "[YouTube/Подача] Эфир: край на ${fresh.toInt()} с" }
            }

            liveHeadSeconds = fresh
        }
    }

    private fun parseFormatInit(chunk: UmpChunk) {
        val reader = chunk.reader()

        var itag = 0
        var count = 0
        var durationMs = 0L

        while (reader.next()) {
            when (reader.field) {
                2 -> {
                    val inner = reader.takeMessage()

                    while (inner != null && inner.next()) {
                        if (inner.field == 1) {
                            itag = inner.takeVarint().toInt()
                        }
                    }
                }

                3 -> durationMs = reader.takeVarint()
                4 -> count = reader.takeVarint().toInt()
            }
        }

        if (!isVideoItag(itag)) {
            return
        }

        if (count > 0) {
            videoSegmentCount = count
        }

        if (durationMs > 0) {
            duration = durationMs / 1000.0
        }

        // Какую дорожку сервер выбрал на самом деле — это и покажет меню.
        playingItag = itag

        Log.d {
            "[YouTube/Подача] Дорожка $itag: фрагментов $count, ${duration.toInt()} с"
        }
    }

    private fun nameOfPart(type: Int): String = when (type) {
        UmpPart.MEDIA_HEADER -> "заголовок"
        UmpPart.MEDIA -> "кусок"
        UmpPart.MEDIA_END -> "конец"
        UmpPart.NEXT_REQUEST_POLICY -> "правила"
        UmpPart.FORMAT_INITIALIZATION -> "сведения"
        UmpPart.REDIRECT -> "переезд"
        UmpPart.ERROR -> "отказ"
        UmpPart.PROTECTION_STATUS -> "подлинность"
        UmpPart.RELOAD -> "перезапрос"
        UmpPart.START_POLICY -> "начало"
        UmpPart.REQUEST_ID -> "метка"
        UmpPart.CANCEL_POLICY -> "отмена"
        else -> "#$type"
    }

    private fun handlePart(chunk: UmpChunk) {
        if (ru.computershik.troubadour.BuildConfig.LOG) {
            if (seen.isNotEmpty()) {
                seen.append(' ')
            }

            seen.append(nameOfPart(chunk.type)).append(':').append(chunk.length)
        }

        when (chunk.type) {
            UmpPart.MEDIA_HEADER -> {
                val header = parseHeader(chunk)

                openHeaders[header.headerId] = header
                openBodies[header.headerId] = ByteArrayOutputStream(64 * 1024)
            }

            UmpPart.MEDIA -> {
                if (chunk.length < 1) {
                    return
                }

                val headerId = chunk.body[chunk.at].toInt() and 0xFF

                openBodies[headerId]?.write(
                    chunk.body, chunk.at + 1, chunk.length - 1
                )
            }

            UmpPart.MEDIA_END -> {
                if (chunk.length < 1) {
                    return
                }

                val key = chunk.body[chunk.at].toInt() and 0xFF

                val header = openHeaders.remove(key)
                val body = openBodies.remove(key)?.toByteArray()

                if (header == null || body == null || body.isEmpty()) {
                    return
                }

                finishSegment(header, body)
            }

            UmpPart.FORMAT_INITIALIZATION -> parseFormatInit(chunk)

            UmpPart.LIVE_HEAD -> parseLiveHead(chunk)

            UmpPart.NEXT_REQUEST_POLICY -> {
                val reader = chunk.reader()

                while (reader.next()) {
                    when (reader.field) {
                        7 -> playbackCookie = reader.takeData()
                        4 -> backoffMs = reader.takeVarint()
                    }
                }
            }

            UmpPart.REDIRECT -> {
                val reader = chunk.reader()

                while (reader.next()) {
                    if (reader.field == 1) {
                        val moved = reader.takeString()

                        if (!moved.isNullOrEmpty()) {
                            val was = url

                            url = moved
                            redirected = true

                            /**
                             * Адрес переезда берём **как есть**.
                             *
                             * Сервер возвращает в нём наш же `n` — уже
                             * расшифрованный, — и второй проход превращает
                             * его в мусор: `c_Sjv… → 6pZiV…` первым
                             * запросом, и тут же `6pZiV… → o4cXF…` перед
                             * переездом, а следом отказ 403. Проверено
                             * на iOS-версии живым прогоном.
                             */
                            Log.d {
                                "[YouTube/Подача] Переезд: ${hostOf(was)} → ${hostOf(url)}, " +
                                    "`n` " + if (paramIn(was, "n") == paramIn(url, "n")) {
                                        "тот же"
                                    } else {
                                        "другой"
                                    }
                            }
                        }
                    }
                }
            }

            UmpPart.PROTECTION_STATUS -> {
                val reader = chunk.reader()

                while (reader.next()) {
                    if (reader.field == 1) {
                        val status = reader.takeVarint()

                        if (status != 1L) {
                            Log.d {
                                "[YouTube/Подача] Требуется подтверждение " +
                                    "подлинности (состояние $status)"
                            }
                        }
                    }
                }
            }

            UmpPart.RELOAD -> {
                val outer = chunk.reader()

                while (outer.next()) {
                    if (outer.field != 1) {
                        continue
                    }

                    val inner = outer.takeMessage() ?: continue

                    while (inner.next()) {
                        if (inner.field == 1) {
                            reloadTokenValue = inner.takeString()
                        }
                    }
                }

                Log.d {
                    "[YouTube/Подача] Сервер просит обновить ответ /player (токен " +
                        (reloadTokenValue?.let { "${it.length} знаков" } ?: "не найден") + ")"
                }

                needsReload = true
            }

            UmpPart.ERROR -> {
                var reason: String? = null

                val reader = chunk.reader()

                while (reader.next()) {
                    if (reader.field == 1) {
                        reason = reader.takeString()
                    }
                }

                Log.d { "[YouTube/Подача] Отказ: ${reason ?: "без объяснения"}" }
            }
        }
    }

    /**
     * Где в куске начинается `moof` — и начинается ли.
     *
     * Идём по боксам верхнего уровня: у каждого сперва длина, потом имя.
     * Возвращаем 0, если `moof` первый же, то есть головы нет.
     */
    private fun mediaStartIn(body: ByteArray): Int {
        var at = 0

        while (at + 8 <= body.size) {
            val size = ((body[at].toLong() and 0xFF) shl 24) or
                ((body[at + 1].toLong() and 0xFF) shl 16) or
                ((body[at + 2].toLong() and 0xFF) shl 8) or
                (body[at + 3].toLong() and 0xFF)

            val name = String(body, at + 4, 4, Charsets.ISO_8859_1)

            if (name == "moof") {
                return at
            }

            if (size < 8 || at + size > body.size) {
                return -1
            }

            at += size.toInt()
        }

        return -1
    }

    private fun finishSegment(header: SabrHeader, rawBody: ByteArray) {
        val isVideo = isVideoItag(header.itag)

        lastDeliveryAt = System.currentTimeMillis()

        if (isVideo) {
            deliveredVideoItag = header.itag
        } else {
            deliveredAudioItag = header.itag
        }

        var body = rawBody

        /**
         * У эфира заголовок дорожки лежит **внутри каждого куска**.
         *
         * Обычный ролик сервер начинает отдельным заголовком (`ftyp`
         * и `moov`), помеченным как начальный, и дальше шлёт голые
         * куски. У идущей трансляции такого заголовка нет вовсе:
         * каждый кусок самодостаточен и несёт описание кодека в себе —
         * ведь подключиться к эфиру можно в любую секунду.
         *
         * Прежде мы ждали отдельного заголовка, не получали его
         * и объявляли подачу неподнявшейся: эфиры не игрались вовсе.
         * Теперь голову отрезаем сами: первую запоминаем как заголовок
         * дорожки, у остальных отбрасываем. Плееру нужен один `moov`
         * на дорожку, а не по одному на каждый кусок.
         */
        if (!header.isInit && body.size > 8 &&
            String(body, 4, 4, Charsets.ISO_8859_1) != "moof"
        ) {
            val at = mediaStartIn(body)

            if (at > 0) {
                if (isVideo && videoInit == null) {
                    videoInit = body.copyOfRange(0, at)
                    videoInitItag = header.itag

                    Log.d {
                        "[YouTube/Подача] Эфир: заголовок дорожки ${header.itag} " +
                            "взят из куска ($at б)"
                    }
                } else if (!isVideo && audioInit == null) {
                    audioInit = body.copyOfRange(0, at)
                    audioInitItag = header.itag

                    Log.d {
                        "[YouTube/Подача] Эфир: заголовок звука ${header.itag} " +
                            "взят из куска ($at б)"
                    }
                }

                body = body.copyOfRange(at, body.size)
            }
        }

        if (header.isInit) {
            if (isVideo) {
                videoInit = body
                videoInitItag = header.itag
            } else {
                audioInit = body
                audioInitItag = header.itag
            }

            return
        }

        val known: Boolean

        synchronized(vault) {
            val segments = if (isVideo) videoSegments else audioSegments
            val times = if (isVideo) videoTimes else audioTimes

            /**
             * Прежде ли нам этот кусок известен — по этому и судим
             * о продвижении. Иначе повторная присылка того же самого
             * читается как «дело идёт», и источник ходит по кругу.
             */
            known = segments.containsKey(header.sequence)

            segments[header.sequence] = body

            capStorage(segments, if (isVideo) 24 else 16)

            if (liveMode && liveStartSeconds <= 0 && header.startMs > 0 && isVideo) {
                liveStartSeconds = header.startMs / 1000.0

                Log.d { "[YouTube/Подача] Эфир: живой край на ${header.startMs / 1000} с" }
            }

            times[header.sequence] = longArrayOf(header.startMs, header.durationMs)

            /**
             * У эфира куски приходят без длительности.
             *
             * Обычный ролик сервер размечает: начало и длина каждого
             * куска. У трансляции длины нет — и источник, подбирая кусок
             * под нужный миг, не находил ни одного: пустая длина никакой
             * миг не накрывает. Плеер ждал данных и вставал.
             *
             * Длину берём по соседям: расстояние между началами соседних
             * кусков и есть длина первого из них. Заодно дописываем её
             * предыдущему, у которого она была неизвестна.
             */
            if (liveMode && header.durationMs <= 0) {
                val before = times[header.sequence - 1]

                if (before != null && before[0] > 0) {
                    val gap = header.startMs - before[0]

                    /**
                     * Оценке нужен потолок.
                     *
                     * Номера кусков у эфира изредка идут с пропусками,
                     * и «предыдущий» оказывается не на пять секунд
                     * раньше, а на тридцать. Такая оценка потом уходит
                     * серверу как «у нас набрано тридцать секунд» —
                     * и он придерживает то, чего нам не хватает.
                     * Больше двух с половиной обычных длин не берём.
                     */
                    val cap = maxOf(15000L, typicalSpanMs(isVideo) * 5 / 2)

                    if (gap in 100..cap) {
                        before[1] = gap

                        times[header.sequence] = longArrayOf(header.startMs, gap)
                    }
                }
            }
        }

        val got = SabrFormat(header.itag, header.lastModified)

        got.xtags = xtagsFor(header.itag, header.lastModified)

        /**
         * Счётчик пришедшего: по нему видно, дала подача что-нибудь
         * **новое** или пересказала уже известное. Считать пересказ
         * за дело нельзя: у конца ролика сервер отвечает последними
         * кусками на любую просьбу, и источник крутился бы вечно.
         */
        if (!known) {
            delivered++
        }

        if (isVideo) {
            gotVideo = got
            lastVideoSeq = maxOf(lastVideoSeq, header.sequence)
            videoFilledMs = maxOf(videoFilledMs, header.startMs + header.durationMs)

            if (firstVideoSeq == 0 || header.sequence < firstVideoSeq) {
                firstVideoSeq = header.sequence
            }
        } else {
            gotAudio = got
            lastAudioSeq = maxOf(lastAudioSeq, header.sequence)
            audioFilledMs = maxOf(audioFilledMs, header.startMs + header.durationMs)

            if (firstAudioSeq == 0 || header.sequence < firstAudioSeq) {
                firstAudioSeq = header.sequence
            }
        }

        Log.d {
            "[YouTube/Подача] Фрагмент ${if (isVideo) "видео" else "звука"} " +
                "№${header.sequence}: ${body.size / 1024} КБ (itag ${header.itag}), " +
                "${header.startMs / 1000.0} + ${header.durationMs / 1000.0} с"
        }
    }

    // --- Запрос -----------------------------------------------------------

    /**
     * Просит подачу отдать видео и звук начиная с [startMs].
     *
     * Блокирующий — звать с фоновой очереди. false, если сервер отказал
     * или ответил невнятно; что именно случилось, пишется в журнал.
     */
    fun request(video: SabrFormat?, audio: SabrFormat?, startMs: Long): Boolean {
        for (attempt in 0 until 3) {
            redirected = false

            if (send(video, audio, startMs)) {
                return true
            }

            if (!redirected) {
                return false
            }
        }

        Log.d { "[YouTube/Подача] Слишком много переездов" }

        return false
    }

    private fun send(video: SabrFormat?, audio: SabrFormat?, startMs: Long): Boolean {
        this.video = video
        this.audio = audio

        /**
         * Прыжком считается только настоящий разрыв.
         *
         * Мерка была одна — по видео, — и это оказалось разорительно.
         * Фрагмент звука почти вдвое длиннее видеокадрового: набрав
         * первые десять секунд звука против пяти секунд видео, источник
         * звука просил следующий кусок с девятой секунды, а девятая
         * была «дальше видео плюс две» — и подача чистила **оба**
         * хранилища. Звук, только что пришедший, стирался прежде, чем
         * плеер успевал его прочесть, и ролик играл немым; после
         * перемотки тем же порядком пропадало и видео, оставляя
         * один кадр.
         *
         * Поэтому смотрим по обеим дорожкам: разрыв есть, только если
         * просят дальше того, что набрано у **обеих**.
         */
        val filledMs = maxOf(videoFilledMs, audioFilledMs)

        /**
         * У эфира вперёд заглядывают дальше — и это не прыжок.
         *
         * Просьба о следующем куске у трансляции законно уходит за конец
         * набранного: куска ещё нет, его снимают. С меркой в две секунды
         * такая просьба читалась как прыжок, и подача **сбрасывала всё
         * набранное** — по журналу это случалось едва ли не через раз,
         * и запас не мог накопиться в принципе. Три куска (пятнадцать
         * секунд) — та мера, дальше которой обычная просьба не уходит.
         */
        val ahead = if (liveMode) 15000L else 2000L

        if (startMs + 2000 < rangeStartMs || startMs > filledMs + ahead) {
            synchronized(vault) {
                videoSegments.clear()
                audioSegments.clear()
                videoTimes.clear()
                audioTimes.clear()
            }

            resetCounters(startMs)

            Log.d {
                "[YouTube/Подача] Прыжок на ${startMs / 1000} с — набранное сброшено"
            }
        }

        if (!fixed) {
            val before = url

            url = NSig.fixUrl(url)
            fixed = true

            /**
             * Одна строка, чтобы не гадать о причине отказа 403.
             *
             * Оригинал прямо говорит: без починки `n` подача отвечает
             * пустым 403. Но починка молчалива — если расшифровку добыть
             * не удалось, `fixUrl` возвращает адрес как был, и по журналу
             * это неотличимо от случая, когда чинить было нечего.
             */
            Log.d {
                val had = url.contains("&n=") || url.contains("?n=")

                "[YouTube/Подача] Адрес: `n` " +
                    (if (had) "есть" else "нет") + ", починка " +
                    if (url == before) "ничего не изменила" else "сработала"
            }
        }

        val address = "$url&rn=$requestNumber"

        Log.d {
            fun describe(isVideo: Boolean): String {
                val held = heldRange(isVideo) ?: return "нет"

                return "${held[0]}..${held[1]} до ${held[3] / 1000.0} с"
            }

            "[YouTube/Подача] Просьба $requestNumber: с ${startMs / 1000.0} с; " +
                "видео ${describe(true)}, звук ${describe(false)}"
        }

        requestNumber++

        val builder = Http.request(address) ?: return false

        builder.post(
            okhttp3.RequestBody.create(
                okhttp3.MediaType.parse("application/x-protobuf"),
                requestBodyFrom(startMs)
            )
        )

        builder.header("Content-Type", "application/x-protobuf")
        builder.header("Accept", "application/vnd.yt-ump")

        /**
         * Сжатие снимаем: тело и так уже сжатое видео, а разбор идёт
         * потоком — распаковщик посередине только задержал бы первые части.
         */
        builder.header("Accept-Encoding", "identity")
        builder.header("User-Agent", Api.mediaUserAgent())

        seen.setLength(0)

        var received = 0

        val startedAt = android.os.SystemClock.elapsedRealtime()

        /**
         * Ответ читается **потоком**, а не целиком.
         *
         * В оригинале он собирался в память и разбирался после: ответы
         * там короткие, по несколько секунд видео. Но конца у ответа
         * может и не быть минутами, а первые части — заголовки дорожек —
         * приходят сразу, и ждать ради них хвоста незачем.
         *
         * Хвост, не разобранный до конца, склеивается со следующим куском:
         * часть могла прийти не целиком.
         */
        val collected = ByteArrayOutputStream(256 * 1024)

        val response = Http.stream(builder.build(), null) { buffer, length ->
            collected.write(buffer, 0, length)

            received += length

            val data = collected.toByteArray()

            val left = Ump.read(data, data.size) { chunk -> handlePart(chunk) }

            collected.reset()

            if (left > 0) {
                collected.write(data, data.size - left, left)
            }

            true
        }

        PlaybackStats.noteTransfer(
            received.toLong(),
            android.os.SystemClock.elapsedRealtime() - startedAt
        )

        if (!response.isSuccessful || received == 0) {
            /**
             * Наш же обрыв за отказ не считаем.
             *
             * Загрузку прерывают мы сами — сменой ступени, перемоткой,
             * закрытием ролика, — и поток загрузки получает обрыв.
             * Считать это отказом сервера значит идти за свежим ответом
             * `/player` на ровном месте, посреди пересборки, где он
             * к тому же и не выйдет: в журнале это «код 0, interrupted»
             * следом за сменой качества.
             */
            val ours = Thread.currentThread().isInterrupted ||
                response.error is java.io.InterruptedIOException

            if (ours) {
                Log.d {
                    "[YouTube/Подача] Запрос ${requestNumber - 1} оборван нами — не в счёт"
                }

                return false
            }

            failures++

            Log.d {
                "[YouTube/Подача] Запрос ${requestNumber - 1} не удался: " +
                    "код ${response.statusCode}, узел ${hostOf(url)}, отказ подряд $failures"
            }

            /**
             * Отказ два раза подряд — сессия подачи кончилась.
             *
             * Так это выглядит: играли сорок минут, сервер прислал
             * «переезд» на другой узел, и следующий же запрос получил
             * пустой 403 — и все дальнейшие тоже. Просить не о чем:
             * этот адрес мёртв, а нового он не даст.
             *
             * Просьбы обновиться сервер при этом **не присылает**, и
             * до сих пор мы такое не лечили ничем: оба источника
             * отсчитывали свои три попытки и объявляли конец потока.
             * Плеер честно считал, что ролик кончился, — картинка
             * вставала, а часы шли дальше.
             *
             * Лечится тем же, чем и просьба сервера: сходить в `/player`
             * заново и получить свежие адрес и настройки. Токена у нас
             * тут нет, и обновление спросит по-обычному.
             */
            if (failures >= 2 && !needsReload) {
                needsReload = true
                reloadTokenValue = null

                Log.d {
                    "[YouTube/Подача] Подача мертва — просим свежий ответ /player"
                }
            }

            return false
        }

        failures = 0

        Log.d { "[YouTube/Подача] Части ответа: ${seen.ifEmpty { "пусто" }}" }

        Log.d {
            "[YouTube/Подача] Ответ ${received / 1024} КБ: видео " +
                "${synchronized(vault) { videoSegments.size }} фрагментов, звук " +
                "${synchronized(vault) { audioSegments.size }}, " +
                "заголовки ${if (videoInit != null) "есть" else "нет"}/" +
                "${if (audioInit != null) "есть" else "нет"}" +
                if (collected.size() > 0) ", хвост не разобран" else ""
        }

        return synchronized(vault) { videoSegments.isNotEmpty() } || videoInit != null
    }

    /** Узел адреса — для журнала: целиком он длиной в килобайт. */
    private fun hostOf(address: String): String {
        val start = address.indexOf("://")

        if (start < 0) {
            return "?"
        }

        val from = start + 3
        val stop = address.indexOf('/', from)

        return if (stop < 0) address.substring(from) else address.substring(from, stop)
    }

    /** Значение параметра в адресе; null, если его там нет. */
    private fun paramIn(address: String, name: String): String? {
        val marker = address.indexOf("&$name=").let {
            if (it >= 0) it + 1 else {
                val first = address.indexOf("?$name=")

                if (first >= 0) first + 1 else return null
            }
        }

        val tail = address.substring(marker + name.length + 1)
        val stop = tail.indexOf('&')

        return if (stop < 0) tail else tail.substring(0, stop)
    }

    /**
     * Просит продолжение — то, что идёт за уже полученным.
     *
     * Сервер помнит отданное по печенью воспроизведения, поэтому просить
     * ничего не надо: достаточно повторить запрос.
     */
    /**
     * Просит подачу прислать ещё, начиная с этого мига.
     *
     * Разговор с подачей идёт **по одному**. Источников два — видео
     * и звук, — у каждого свой поток загрузки, а подача и её перечень
     * набранного одни на обоих. Пока они ходили сюда вразнобой, каждый
     * запрос сдвигал общий указатель под ногами у соседа: в журнале
     * это выглядело чередой «Прыжок на 1228 с», «на 1238», «на 1258»
     * и запросами, падающими с кодом 0.
     */
    fun requestMoreFrom(playerTime: Double): Boolean = synchronized(talk) {
        /**
         * Считаем пришедшее, а не размер хранилища.
         *
         * По размеру выходила ложь: старые куски вытесняются по мере
         * набора, и на пришедший фрагмент размер оставался прежним —
         * источник объявлял «подача не дала», хотя дала.
         */
        val before = delivered

        var timeMs = (playerTime * 1000).toLong()

        /**
         * Сразу после прыжка обе дорожки обязаны встать на один миг.
         *
         * Иначе выходит так: звук успевает подхватить 1218-ю секунду
         * и уезжает дальше, тряся общий указатель, а видео, придя следом,
         * находит в памяти уже только куски с 1281-й — и привязывается
         * к ним. Полминуты расхождения между дорожками плеер сводит
         * ошибкой «Source error».
         *
         * Поэтому до тех пор, пока обе не получили что-то от места
         * прыжка, любая просьба ведёт туда же.
         */
        if (anchorMs >= 0) {
            timeMs = anchorMs
        } else {
            /**
             * Просьба не вправе убегать вперёд соседней дорожки.
             *
             * Миг, который мы называем серверу, — это «где сейчас
             * плеер», и всё, что раньше него, сервер считает пройденным
             * и не присылает. Куски у дорожек разной длины: звук по
             * десять секунд, видео по пять. Набрав звук до 79,9 с при
             * видео до 75,7, источник звука просил «с 79,9» — и сервер
             * начинал видео с куска, накрывающего 79,9, а кусок 75,7–79,4
             * пропускал. Перечень набранного потом сообщал сплошной
             * диапазон, и дыра не закрывалась уже никогда: источник видео
             * дважды просил его впустую и шагал дальше. Это и были
             * «картинка встаёт на несколько секунд, звук идёт, потом
             * картинка догоняет» — раз в минуту на 720p, по журналу
             * с GT-N8000 девять дыр за девять минут.
             *
             * Поэтому просим не позже того места, до которого набрана
             * **каждая** из дорожек, чей источник открыт у плеера.
             * Мерить живость иначе нельзя: отставание в секундах врёт —
             * звук честно уходит вперёд видео на двадцать с лишним
             * секунд, столько ему даёт буфер; молчание тоже врёт —
             * дорожка, которую кормят просьбы соседней, сама не просит.
             */
            var need = earliestNeedMs()

            /**
             * У эфира просим только вперёд.
             *
             * Правило «просить не позже самой отстающей дорожки» писано
             * для записи: там дыру всегда можно закрыть, ролик никуда
             * не денется. У трансляции выходит наоборот. Куски приходят
             * вразнобой, сплошной ряд обрывается, просьба уезжает
             * в прошлое — и сервер шлёт уже виденное. На пробе так уходила
             * **половина** всего трафика: девятнадцать повторов
             * на тридцать восемь кусков, просьбы на восемнадцать минут
             * назад и провалы до двадцати пяти секунд. На глаз это
             * и были рывки.
             *
             * Поэтому отступать позволено недалеко — на полминуты
             * от самого свежего набранного мига. Этого хватает, чтобы
             * закрыть настоящую дыру у края, и мало, чтобы уехать
             * от эфира в прошлое.
             */
            /**
             * Бессмысленное прошлое отсекаем.
             *
             * Когда кусок теряется, отмерка от его соседей изредка даёт
             * время далеко позади — в журнале попадались просьбы
             * на сорок минут назад. У эфира там давно ничего нет, а сброс
             * набранного из-за такой просьбы обходится дорого.
             */
            if (liveMode && anchorMs < 0) {
                val edge = newestHeldMs()

                if (edge > 0 && timeMs < edge - LIVE_REACH_MS) {
                    Log.d {
                        "[YouTube/Подача] Эфир: просьба с ${timeMs / 1000} с " +
                            "слишком далеко позади — берём ${(edge - LIVE_REACH_MS) / 1000} с"
                    }

                    timeMs = edge - LIVE_REACH_MS
                }
            }

            if (need >= 0 && need < timeMs) {
                if (timeMs - need > 500) {
                    Log.d {
                        "[YouTube/Подача] Просьба с ${timeMs / 1000.0} с отодвинута " +
                            "к ${need / 1000.0} с — там ещё не набрано"
                    }
                }

                timeMs = need
            }
        }

        /**
         * У эфира просьба живёт в узком окне у живого края.
         *
         * Сюда сходятся два разных промаха, и оба видны по журналу.
         * Первый: правило «просить не позже самой отстающей дорожки»
         * заставляло топтаться на одном мгновении, и сервер слал уже
         * присланное — до половины всего трафика, куски приходили
         * «задом наперёд». Второй: когда кусок терялся, отмерка от его
         * соседей уводила просьбу на девятьсот секунд в прошлое —
         * туда, где у эфира давно ничего нет.
         *
         * Правило одно: не раньше, чем за двенадцать секунд до конца
         * набранного. Настоящую дыру у края это закрыть позволяет,
         * а уехать от эфира — нет.
         */
        send(video, audio, timeMs)

        return delivered > before
    }

    /**
     * Докуда набрана самая отстающая из играющих дорожек, мс; −1, если
     * набранного ещё нет.
     */
    /**
     * Живой край: докуда набрана трансляция прямо сейчас, с.
     *
     * Нужен восстановлению после затора. Возвращаться к тому месту,
     * с которого показ начинался, у эфира бессмысленно: пока мы стояли,
     * край ушёл вперёд, а прошлое сервер повторять не станет.
     */
    fun liveEdgeSeconds(): Double {
        /**
         * Возвращаем **начало** самого свежего куска, а не его конец.
         *
         * Конец — это миг, которого ни один кусок ещё не накрывает:
         * источник, открытый там, честно отвечает «фрагмент неизвестен»
         * и ждёт, а плеер за это время объявляет затор. Начало же лежит
         * внутри набранного, и источник встаёт на него сразу.
         */
        var newest = -1L

        synchronized(vault) {
            for (pair in videoTimes.values) {
                if (pair.isNotEmpty()) {
                    newest = maxOf(newest, pair[0])
                }
            }
        }

        return if (newest > 0) newest / 1000.0 else liveStartSeconds
    }

    /** Самый свежий миг, до которого что-то набрано, мс; −1, если пусто. */
    private fun newestHeldMs(): Long {
        var newest = -1L

        synchronized(vault) {
            for (pair in videoTimes.values) {
                if (pair.size >= 2) {
                    newest = maxOf(newest, pair[0] + pair[1])
                }
            }
        }

        return newest
    }

    /**
     * Докуда просить дальше по этой дорожке, мс.
     *
     * Конец сплошного ряда — а если длина последнего куска ещё не
     * известна (так бывает у эфира: её вычисляют по следующему куску),
     * то его начало плюс обычная длина. Иначе выходит просьба о том,
     * что уже набрано, и сервер правомерно отвечает пустотой.
     */
    private fun needAfter(isVideo: Boolean): Long {
        val held = heldRange(isVideo) ?: return -1L

        val end = held[3]

        if (held.size >= 5 && end <= held[4]) {
            return held[4] + typicalSpanMs(isVideo)
        }

        return end
    }

    private fun earliestNeedMs(): Long {
        var need = -1L

        // Конец сплошного ряда, а не последнего пришедшего: дыра — тоже нужда.
        if (videoOwner != null) {
            val video = needAfter(true)

            if (video >= 0) {
                need = video
            }
        }

        if (audioOwner != null && audioInit != null) {
            val audio = needAfter(false)

            if (audio >= 0) {
                need = if (need < 0) audio else minOf(need, audio)
            }
        }

        return need
    }

    /**
     * Источник сообщает, что встал на нужный кусок.
     *
     * Пока обе дорожки не встали, все просьбы ведут к месту прыжка.
     * Прежде это решалось по содержимому хранилища — «есть ли кусок,
     * кончающийся позже цели», — и решалось неверно: у видео такой
     * кусок находился сразу, потому что сервер прислал куски **дальше**
     * нужного места. Якорь снимался, каждый источник снова уезжал
     * по-своему, и после прыжка на 198-ю секунду звук вставал на 189-ю,
     * а видео на 205-ю. Шестнадцать секунд врозь — это и есть «картинка
     * замерла, звук идёт».
     *
     * Спрашивать надо у самих источников: встал — сказал.
     */
    fun anchored(isVideo: Boolean, startSeconds: Double) = synchronized(talk) {
        /**
         * Первая вставшая дорожка задаёт место второй.
         *
         * Куски у дорожек разной длины — видео по пять секунд, звук почти
         * по десять, — и накрыть один и тот же миг обеими ровно нельзя.
         * Если каждая метит в заказанный миг, они расходятся до семи
         * секунд: при прыжке на 908-ю видео вставало на 905-ю, а звук
         * на 898-ю, и эти семь секунд звук шёл поверх застывшего кадра.
         *
         * Поэтому вторая метит не в заказанное место, а в то, куда
         * встала первая: дальше половины своего куска ей тогда не уйти.
         */
        if (anchorStartSeconds < 0) {
            anchorStartSeconds = startSeconds
        }

        if (isVideo) {
            videoAnchored = true
        } else {
            audioAnchored = true
        }

        if (videoAnchored && audioAnchored) {
            anchorMs = -1
            anchorStartSeconds = -1.0
        }
    }

    /** Источник дорожки открылся у плеера либо закрылся. */
    fun sourceOpen(isVideo: Boolean, owner: Any, open: Boolean) = synchronized(talk) {
        if (open) {
            if (isVideo) {
                videoOwner = owner
            } else {
                audioOwner = owner
            }

            return@synchronized
        }

        // Закрытие засчитываем только от того, кто и открывал.
        if (isVideo) {
            if (videoOwner === owner) {
                videoOwner = null
            }
        } else {
            if (audioOwner === owner) {
                audioOwner = null
            }
        }
    }

    /** Куда встала первая дорожка после прыжка; −1, если ещё никто. */
    fun anchorStart(): Double = synchronized(talk) { anchorStartSeconds }

    // --- Забранное --------------------------------------------------------

    fun videoSegment(sequence: Int): ByteArray? = synchronized(vault) { videoSegments[sequence] }

    fun audioSegment(sequence: Int): ByteArray? = synchronized(vault) { audioSegments[sequence] }

    private fun timeIn(times: Map<Int, LongArray>, sequence: Int, part: Int): Double {
        val pair = synchronized(vault) { times[sequence] } ?: return 0.0

        if (pair.size <= part) {
            return 0.0
        }

        return pair[part] / 1000.0
    }

    fun videoSegmentStart(sequence: Int): Double = timeIn(videoTimes, sequence, 0)

    fun videoSegmentDuration(sequence: Int): Double = timeIn(videoTimes, sequence, 1)

    fun audioSegmentStart(sequence: Int): Double = timeIn(audioTimes, sequence, 0)

    fun audioSegmentDuration(sequence: Int): Double = timeIn(audioTimes, sequence, 1)

    /**
     * Номера фрагментов, лежащих сейчас в памяти, по возрастанию.
     *
     * Спрашивать надо именно хранилище, а не счётчик последнего пришедшего.
     * Счётчик ведёт перечень набранного для сервера и сбрасывается вместе
     * с ним — при повторе ролика и при перемотке, — а фрагменты в памяти
     * остаются.
     */
    fun audioSequences(): List<Int> = synchronized(vault) { audioSegments.keys.sorted() }

    fun videoSequences(): List<Int> = synchronized(vault) { videoSegments.keys.sorted() }

    /**
     * Сколько сервер просит подождать перед следующим запросом, в секундах.
     *
     * Просьбу эту нужно исполнять: сразу после прыжка по ролику сервер
     * отвечает пустотой — только правила да перечень дорожек, — и повтор
     * через десятую долю секунды получает ту же пустоту.
     *
     * Потолок — полторы секунды: дольше ждать нет смысла, у плеера свой
     * сторож, а сервер обычно просит десятые доли.
     */
    fun backoff(): Double = minOf(1.5, maxOf(0.0, backoffMs / 1000.0))

    private fun resetCounters(startMs: Long) {
        firstVideoSeq = 0
        firstAudioSeq = 0
        lastVideoSeq = 0
        lastAudioSeq = 0
        videoFilledMs = startMs
        audioFilledMs = startMs
        rangeStartMs = startMs
    }

    /**
     * Забывает, что уже набрано, и начинает счёт заново с указанного мига.
     *
     * Нужно, когда кусок понадобился второй раз — при повторе ролика или
     * возврате в начало. Сервер шлёт только то, чего у нас, по его
     * сведениям, ещё нет, а сведения эти мы сами ему и сообщаем перечнем
     * набранного. Байты фрагментов к тому времени давно выброшены, перечень
     * же остаётся, и на просьбу прислать первый кусок сервер отвечает
     * пустотой — совершенно правомерно.
     */
    fun rewindTo(seconds: Double) = synchronized(talk) {
        resetCounters((seconds * 1000).toLong())

        // Обе дорожки поведём к этому мигу, пока обе на него не встанут.
        anchorMs = (seconds * 1000).toLong()

        videoAnchored = false
        audioAnchored = false

        anchorStartSeconds = -1.0

        Log.d { "[YouTube/Подача] Возврат на ${seconds.toInt()} с — перечень очищен" }
    }

    /**
     * С какого времени просить кусок с этим номером.
     *
     * Номера кусков в подаче сплошные, а вот длины у них разные: у одного
     * ролика встречаются и три секунды, и почти семь. Считать время как
     * «номер умножить на среднюю длину» нельзя — ошибка накапливается,
     * и мы просим сервер начать оттуда, где всё уже набрано. Он отвечает
     * пустотой, а кусок так и не приходит.
     *
     * Поэтому отмеряем от конца последнего известного куска перед этим,
     * и лишь когда ничего не известно — по средней длине.
     */
    fun startForSequence(sequence: Int, average: Double, isVideo: Boolean = true): Double {
        val known = if (isVideo) videoSegmentStart(sequence) else audioSegmentStart(sequence)

        if (known > 0 || sequence == 1) {
            return known
        }

        for (back in 1..8) {
            val n = sequence - back

            if (n < 1) {
                break
            }

            /**
             * Дорожку спрашиваем свою.
             *
             * Прежде здесь всегда стояло видео, и для звука выходил
             * промах: у видео фрагмент около пяти секунд, у звука почти
             * десять, и «кусок 7» звука отмерялся по видеоразметке —
             * тридцатая секунда вместо шестидесятой. Сервер отвечал
             * пустотой, а звук пропадал.
             */
            val start = if (isVideo) videoSegmentStart(n) else audioSegmentStart(n)
            val length = if (isVideo) videoSegmentDuration(n) else audioSegmentDuration(n)

            if (length > 0) {
                return start + length + (back - 1) * average
            }
        }

        return (sequence - 1) * average
    }

    /** Зовётся только под [vault]. */
    private fun capStorage(storage: HashMap<Int, ByteArray>, keep: Int) {
        if (storage.size <= keep) {
            return
        }

        val keys = storage.keys.sorted()

        for (index in 0 until (keys.size - keep)) {
            storage.remove(keys[index])
        }
    }

    /**
     * Забывает всё, что раньше указанного времени.
     *
     * Фрагменты держатся в памяти, пока их не выбросят, а весят они
     * мегабайтами: на 1080p — по два с половиной на каждые пять с половиной
     * секунд. За минуту просмотра набирается столько, что устройству
     * с четвертью гигабайта памяти становится нечем дышать.
     *
     * Времена фрагментов при этом остаются: они занимают десятки байт,
     * а по ним строится перечень набранного для сервера.
     */
    fun forgetBefore(seconds: Double) {
        synchronized(vault) {
            forget(videoSegments, videoTimes, seconds)
            forget(audioSegments, audioTimes, seconds)
        }
    }

    /** Зовётся только под [vault]. */
    private fun forget(
        storage: HashMap<Int, ByteArray>,
        times: Map<Int, LongArray>,
        seconds: Double
    ) {
        val gone = ArrayList<Int>()

        for (key in storage.keys) {
            val pair = times[key] ?: continue

            if (pair.size < 2) {
                continue
            }

            val end = (pair[0] + pair[1]) / 1000.0

            if (end < seconds) {
                gone.add(key)
            }
        }

        for (key in gone) {
            storage.remove(key)
        }
    }

    /** Докуда набрано видео по времени, секунды. */
    fun bufferedSeconds(): Double = videoFilledMs / 1000.0
}
