package ru.computershik.troubadour.player

import ru.computershik.troubadour.Log

/**
 * Разбор фрагментированного MP4 — того, чем YouTube раздаёт дорожки DASH.
 *
 * Порт `YTMp4` из iOS-версии, а тот — `DashDemuxer.cs` из версии для
 * Windows 10 Mobile в части чтения боксов. Дорожка состоит из трёх частей:
 *
 *     init      ftyp + moov — описание кодека: SPS/PPS у видео,
 *               AudioSpecificConfig у звука, шкала времени;
 *     sidx      карта фрагментов: у каждого длина в байтах и длительность;
 *     фрагменты moof + mdat, идут подряд. `moof` говорит, где и какой
 *               длины каждый сэмпл внутри `mdat`.
 *
 * Здесь читается только то, что нужно сборщику ([Mp4Writer]): описание
 * кодека и таблица сэмплов. Карта фрагментов не читается вовсе — она
 * нужна тому, кто прыгает в середину, а сборщик идёт подряд.
 *
 * **Зачем это на Android.** Плееру разбор не нужен: фрагменты понимает
 * ExoPlayer. Но скачанное надо отдать наружу обычным MP4, а системный
 * сводчик, во-первых, есть не везде, во-вторых, сам должен прочитать
 * фрагментированный файл — чего разборщик старых версий толком не умеет.
 */

/** Что вычитано из `moov` — описание одной дорожки. */
class TrackInit {

    /** Тиков в секунду у этой дорожки (`mdhd`). */
    var timescale: Int = 0

    /** Идентификатор дорожки (`tkhd`) — им помечены фрагменты. */
    var trackId: Int = 0

    /** Наборы параметров из `avcC`; в MP4 они лежат отдельно от кадров. */
    var sps: List<ByteArray> = emptyList()
    var pps: List<ByteArray> = emptyList()

    /**
     * Сколько байт занимает длина NALU в `mdat`.
     *
     * Берётся из `avcC` и бывает не только четвёркой — предполагать её
     * нельзя.
     */
    var nalLengthSize: Int = 4

    var width: Int = 0
    var height: Int = 0

    /** Из AudioSpecificConfig в `esds`. */
    var audioObjectType: Int = 0
    var samplingFrequencyIndex: Int = 0
    var channelConfig: Int = 0

    val isVideo: Boolean
        get() = sps.isNotEmpty()
}

/** Один сэмпл внутри `mdat`: где лежит, сколько длится и ключевой ли он. */
class Mp4Sample {

    var offset: Int = 0
    var size: Int = 0
    var duration: Int = 0

    /**
     * Сдвиг времени показа относительно времени декодирования.
     *
     * У кадров с обратным предсказанием порядок показа не совпадает
     * с порядком в потоке, и без этого сдвига картинка шла бы рывками.
     * Знаковый: в первой версии `trun` он бывает отрицательным.
     */
    var compositionOffset: Int = 0

    var isSync: Boolean = false
}

/** Разобранный `moof`: список сэмплов и время начала фрагмента. */
class Mp4Fragment {

    var baseMediaDecodeTime: Long = 0

    /** Смещение начала данных от начала `moof`. */
    var dataOffset: Int = 0

    var samples: List<Mp4Sample> = emptyList()
}

object Mp4 {

    /**
     * Все числа в MP4 — со старшим байтом впереди, каким бы ни было
     * устройство. Читаем побайтно: границы боксов выравниванию
     * не подчиняются.
     */
    fun read16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    fun read32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or
            ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or
            (bytes[at + 3].toLong() and 0xFF)

    fun read64(bytes: ByteArray, at: Int): Long =
        (read32(bytes, at) shl 32) or read32(bytes, at + 4)

    private fun tagAt(bytes: ByteArray, at: Int): String {
        val letters = CharArray(4)

        for (index in 0 until 4) {
            letters[index] = (bytes[at + index].toInt() and 0xFF).toChar()
        }

        return String(letters)
    }

    /**
     * Находит бокс с таким именем среди прямых потомков области.
     *
     * Отдаёт пару «смещение тела, длина тела» — смещение от начала всего
     * массива, а не от начала области. Ищем перебором, а не по заранее
     * известному пути: порядок боксов внутри контейнера стандартом
     * не закреплён, и у разных дорожек он разный.
     */
    fun findBox(bytes: ByteArray, at: Int, length: Int, name: String): IntArray? {
        var cursor = at

        val end = at + length

        while (cursor + 8 <= end) {
            val declared = read32(bytes, cursor)

            var header = 8

            // Длина 1 означает, что настоящая лежит следом восемью байтами;
            // длина 0 — «до конца области».
            var size = declared

            if (declared == 1L) {
                if (cursor + 16 > end) {
                    return null
                }

                size = read64(bytes, cursor + 8)
                header = 16
            } else if (declared == 0L) {
                size = (end - cursor).toLong()
            }

            if (size < header || cursor + size > end) {
                return null
            }

            if (tagAt(bytes, cursor + 4) == name) {
                return intArrayOf(cursor + header, (size - header).toInt())
            }

            cursor += size.toInt()
        }

        return null
    }

    /** То же, но по цепочке вложенных имён: «moov», «trak», «mdia»… */
    fun findPath(bytes: ByteArray, at: Int, length: Int, vararg path: String): IntArray? {
        var offset = at
        var size = length

        for (name in path) {
            val found = findBox(bytes, offset, size, name) ?: return null

            offset = found[0]
            size = found[1]
        }

        return intArrayOf(offset, size)
    }

    /** Разбирает init-сегмент (`ftyp` + `moov`). */
    fun parseInit(data: ByteArray): TrackInit? {
        if (data.size < 16) {
            return null
        }

        val trak = findPath(data, 0, data.size, "moov", "trak")

        if (trak == null) {
            Log.d { "[YouTube/MP4] В init нет moov/trak" }

            return null
        }

        val track = TrackInit()

        // tkhd: версия в первом байте, дальше идентификатор дорожки.
        findBox(data, trak[0], trak[1], "tkhd")?.let { tkhd ->
            // Поля до идентификатора: версия с флагами (4), затем два
            // времени — по 4 байта в версии 0 и по 8 в версии 1.
            val idAt = if (data[tkhd[0]].toInt() == 1) 4 + 16 else 4 + 8

            if (tkhd[1] >= idAt + 4) {
                track.trackId = read32(data, tkhd[0] + idAt).toInt()
            }
        }

        findPath(data, trak[0], trak[1], "mdia", "mdhd")?.let { mdhd ->
            val scaleAt = if (data[mdhd[0]].toInt() == 1) 4 + 16 else 4 + 8

            if (mdhd[1] >= scaleAt + 4) {
                track.timescale = read32(data, mdhd[0] + scaleAt).toInt()
            }
        }

        val stsd = findPath(data, trak[0], trak[1], "mdia", "minf", "stbl", "stsd")

        if (stsd == null) {
            Log.d { "[YouTube/MP4] В init нет stsd" }

            return null
        }

        // stsd: версия с флагами (4), число записей (4), дальше записи.
        if (stsd[1] < 8) {
            return null
        }

        val entry = stsd[0] + 8
        val room = stsd[1] - 8

        if (room < 8) {
            return null
        }

        var size = read32(data, entry).toInt()

        if (size > room) {
            size = room
        }

        when (tagAt(data, entry + 4)) {
            "avc1", "avc3" -> parseVideoEntry(data, entry, size, track)

            "mp4a" -> parseAudioEntry(data, entry, size, track)

            else -> {
                Log.d { "[YouTube/MP4] Неизвестный род дорожки: ${tagAt(data, entry + 4)}" }

                return null
            }
        }

        return track
    }

    /**
     * `avc1` — визуальная запись. Её заголовок постоянной длины (78 байт
     * вместе с общими восемью), а за ним вложенные боксы, среди которых
     * нужен `avcC`.
     */
    private fun parseVideoEntry(data: ByteArray, entry: Int, size: Int, track: TrackInit) {
        if (size < 86) {
            return
        }

        track.width = read16(data, entry + 32)
        track.height = read16(data, entry + 34)

        val avcc = findBox(data, entry + 86, size - 86, "avcC")

        if (avcc == null) {
            Log.d { "[YouTube/MP4] В avc1 нет avcC" }

            return
        }

        val at = avcc[0]
        val length = avcc[1]

        if (length < 6) {
            return
        }

        /**
         * Раскладка avcC: версия, три байта профиля, потом байт, младшие
         * два бита которого — длина поля длины NALU минус один, потом байт
         * с числом SPS в младших пяти битах.
         */
        track.nalLengthSize = (data[at + 4].toInt() and 0x03) + 1

        var cursor = at + 5
        val end = at + length

        var count = data[cursor].toInt() and 0x1F

        cursor++

        val sps = ArrayList<ByteArray>()

        var index = 0

        while (index < count && cursor + 2 <= end) {
            val one = read16(data, cursor)

            cursor += 2

            if (cursor + one > end) {
                break
            }

            sps.add(data.copyOfRange(cursor, cursor + one))

            cursor += one
            index++
        }

        track.sps = sps

        if (cursor >= end) {
            return
        }

        count = data[cursor].toInt() and 0xFF

        cursor++

        val pps = ArrayList<ByteArray>()

        index = 0

        while (index < count && cursor + 2 <= end) {
            val one = read16(data, cursor)

            cursor += 2

            if (cursor + one > end) {
                break
            }

            pps.add(data.copyOfRange(cursor, cursor + one))

            cursor += one
            index++
        }

        track.pps = pps
    }

    /**
     * `mp4a` — звуковая запись. Заголовок 28 байт вместе с общими восемью,
     * дальше `esds`, внутри которого AudioSpecificConfig.
     */
    private fun parseAudioEntry(data: ByteArray, entry: Int, size: Int, track: TrackInit) {
        if (size < 36) {
            return
        }

        val esds = findBox(data, entry + 36, size - 36, "esds")

        if (esds == null) {
            Log.d { "[YouTube/MP4] В mp4a нет esds" }

            return
        }

        val end = esds[0] + esds[1]

        /**
         * Внутри esds — дескрипторы MPEG-4: тег, длина в «растянутом» виде
         * (по семь бит на байт, старший бит — признак продолжения), тело.
         * Нужен тег 0x05, DecoderSpecificInfo: там и лежит
         * AudioSpecificConfig.
         *
         * Идём по дескрипторам, а не по постоянным смещениям: длины
         * необязательных полей внутри 0x03 и 0x04 плавают.
         */
        var cursor = esds[0] + 4 // версия и флаги

        while (cursor + 2 <= end) {
            val tag = data[cursor].toInt() and 0xFF

            cursor++

            var length = 0
            var guard = 0

            while (cursor < end && guard < 4) {
                val byte = data[cursor].toInt() and 0xFF

                cursor++
                guard++

                length = (length shl 7) or (byte and 0x7F)

                if (byte and 0x80 == 0) {
                    break
                }
            }

            if (tag == 0x03) {
                // ES_Descriptor: номер (2) и флаги (1), потом вложенные.
                if (cursor + 3 > end) {
                    return
                }

                val flags = data[cursor + 2].toInt() and 0xFF

                cursor += 3

                if (flags and 0x80 != 0) {
                    cursor += 2
                }

                if (flags and 0x40 != 0) {
                    if (cursor >= end) {
                        return
                    }

                    cursor += 1 + (data[cursor].toInt() and 0xFF)
                }

                if (flags and 0x20 != 0) {
                    cursor += 2
                }

                continue
            }

            if (tag == 0x04) {
                // DecoderConfigDescriptor: 13 байт до вложенных.
                cursor += 13

                continue
            }

            if (tag == 0x05) {
                if (cursor + 2 > end || length < 2) {
                    return
                }

                /**
                 * AudioSpecificConfig: 5 бит рода объекта, 4 бита номера
                 * частоты, 4 бита числа каналов.
                 */
                val config = read16(data, cursor)

                track.audioObjectType = (config shr 11) and 0x1F
                track.samplingFrequencyIndex = (config shr 7) and 0x0F
                track.channelConfig = (config shr 3) and 0x0F

                return
            }

            cursor += length
        }
    }

    /**
     * Разбирает `moof` в начале данных фрагмента.
     *
     * `init` нужен ради значений по умолчанию: `tfhd` вправе задать длину
     * и длительность сэмпла один раз на весь фрагмент вместо того, чтобы
     * повторять их в `trun` для каждого.
     */
    fun parseFragment(data: ByteArray): Mp4Fragment? {
        if (data.size < 8) {
            return null
        }

        val moof = findBox(data, 0, data.size, "moof") ?: return null

        val traf = findBox(data, moof[0], moof[1], "traf") ?: return null

        val fragment = Mp4Fragment()

        // Значения по умолчанию из tfhd — ими `trun` вправе не повторяться.
        var defaultDuration = 0
        var defaultSize = 0
        var defaultFlags = 0

        findBox(data, traf[0], traf[1], "tfhd")?.let { tfhd ->
            val at = tfhd[0]

            val flags = (read32(data, at) and 0x00FFFFFF).toInt()

            var cursor = at + 8 // версия с флагами (4), номер дорожки (4)

            if (flags and 0x000001 != 0) {
                cursor += 8 // base_data_offset
            }

            if (flags and 0x000002 != 0) {
                cursor += 4 // sample_description_index
            }

            if (flags and 0x000008 != 0 && at + tfhd[1] >= cursor + 4) {
                defaultDuration = read32(data, cursor).toInt()
                cursor += 4
            }

            if (flags and 0x000010 != 0 && at + tfhd[1] >= cursor + 4) {
                defaultSize = read32(data, cursor).toInt()
                cursor += 4
            }

            if (flags and 0x000020 != 0 && at + tfhd[1] >= cursor + 4) {
                defaultFlags = read32(data, cursor).toInt()
            }
        }

        findBox(data, traf[0], traf[1], "tfdt")?.let { tfdt ->
            val at = tfdt[0]

            if (data[at].toInt() == 1 && tfdt[1] >= 12) {
                fragment.baseMediaDecodeTime = read64(data, at + 4)
            } else if (tfdt[1] >= 8) {
                fragment.baseMediaDecodeTime = read32(data, at + 4)
            }
        }

        val trun = findBox(data, traf[0], traf[1], "trun") ?: return null

        if (trun[1] < 8) {
            return null
        }

        val at = trun[0]
        val end = at + trun[1]

        val version = data[at].toInt() and 0xFF
        val flags = (read32(data, at) and 0x00FFFFFF).toInt()
        val count = read32(data, at + 4).toInt()

        var cursor = at + 8

        /**
         * Смещение данных считается **от начала `moof`**, а не от начала
         * traf или mdat. Это частый источник ошибок: отсчитав от `mdat`,
         * получим первые кадры сдвинутыми на длину заголовка.
         */
        var dataOffset = 0

        if (flags and 0x000001 != 0 && end >= cursor + 4) {
            dataOffset = read32(data, cursor).toInt()
            cursor += 4
        }

        var firstSampleFlags = 0
        var hasFirstSampleFlags = false

        if (flags and 0x000004 != 0 && end >= cursor + 4) {
            firstSampleFlags = read32(data, cursor).toInt()
            hasFirstSampleFlags = true
            cursor += 4
        }

        fragment.dataOffset = moof[0] - 8 + dataOffset

        val samples = ArrayList<Mp4Sample>(count)

        var running = 0

        for (index in 0 until count) {
            var duration = defaultDuration
            var size = defaultSize
            var sampleFlags = defaultFlags
            var composition = 0

            if (flags and 0x000100 != 0) {
                if (end < cursor + 4) {
                    break
                }

                duration = read32(data, cursor).toInt()
                cursor += 4
            }

            if (flags and 0x000200 != 0) {
                if (end < cursor + 4) {
                    break
                }

                size = read32(data, cursor).toInt()
                cursor += 4
            }

            if (flags and 0x000400 != 0) {
                if (end < cursor + 4) {
                    break
                }

                sampleFlags = read32(data, cursor).toInt()
                cursor += 4
            }

            if (flags and 0x000800 != 0) {
                if (end < cursor + 4) {
                    break
                }

                // В нулевой версии сдвиг беззнаковый, в первой — знаковый;
                // и там и там четыре байта, а `Int` у нас знаковый сам.
                composition = read32(data, cursor).toInt()
                cursor += 4

                if (version == 0 && composition < 0) {
                    composition = 0
                }
            }

            if (index == 0 && hasFirstSampleFlags) {
                sampleFlags = firstSampleFlags
            }

            val sample = Mp4Sample()

            sample.offset = running
            sample.size = size
            sample.duration = duration
            sample.compositionOffset = composition

            /**
             * Ключевой кадр: бит `sample_is_non_sync_sample` снят.
             *
             * У звука ключевые все, и там этот бит обычно не выставлен
             * вовсе — что нас устраивает.
             */
            sample.isSync = sampleFlags and 0x00010000 == 0

            samples.add(sample)

            running += size
        }

        fragment.samples = samples

        return fragment
    }
}
