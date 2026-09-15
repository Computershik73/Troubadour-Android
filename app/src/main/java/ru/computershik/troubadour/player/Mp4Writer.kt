package ru.computershik.troubadour.player

import ru.computershik.troubadour.Log
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Сборка обычного MP4 из двух фрагментированных дорожек.
 *
 * Порт `YTMp4Writer` из iOS-версии. Нужна ровно для скачивания: YouTube
 * раздаёт качество выше 360p только раздельными дорожками DASH — видео
 * без звука и звук без видео, каждая фрагментированным MP4. Ни системный
 * проигрыватель, ни наш собственный такого файла не покажут: нужен
 * обычный MP4, где есть `moov` с таблицами сэмплов и один `mdat`
 * с данными.
 *
 * **Почему не системный сводчик.** `MediaMuxer` появился в API 18, и на
 * 16–17 его нет вовсе — а это ровно те устройства, ради которых всё
 * и затевалось. Но дело не только в них: сводчику нужен `MediaExtractor`,
 * то есть системный разборщик должен сам прочитать фрагментированный MP4,
 * чего разборщики тех лет толком не умеют. Свой писатель не зависит
 * ни от того ни от другого и одинаково работает на всех версиях.
 *
 * **Что получается на выходе.** Нефрагментированный MP4: `ftyp`, `moov`
 * с таблицами и `mdat` со всеми сэмплами подряд.
 *
 * **Чего не делается.** Сэмплы не перекодируются и даже не трогаются:
 * H.264 и AAC перекладываются байт в байт. Это не превращение, а смена
 * обёртки — иначе сборка ролика на неспешном устройстве шла бы часами.
 */
object Mp4Writer {

    /** Шкала времени всего фильма: тысяча тиков в секунде. */
    private const val MOVIE_SCALE = 1000

    /** Сколько читать за раз, когда переливаем сэмплы в `mdat`. */
    private const val COPY_CHUNK = 256 * 1024

    // --- Мелкая запись -----------------------------------------------------

    /** Сборщик байтов: всё, что пишется в MP4, идёт старшим байтом вперёд. */
    private class Buf {

        private val out = ByteArrayOutputStream()

        val size: Int get() = out.size()

        fun put8(value: Int): Buf {
            out.write(value and 0xFF)

            return this
        }

        fun put16(value: Int): Buf {
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)

            return this
        }

        fun put32(value: Long): Buf {
            out.write(((value shr 24) and 0xFF).toInt())
            out.write(((value shr 16) and 0xFF).toInt())
            out.write(((value shr 8) and 0xFF).toInt())
            out.write((value and 0xFF).toInt())

            return this
        }

        fun put32(value: Int): Buf = put32(value.toLong() and 0xFFFFFFFFL)

        fun tag(name: String): Buf {
            for (letter in name) {
                out.write(letter.code and 0xFF)
            }

            return this
        }

        fun bytes(data: ByteArray?): Buf {
            if (data != null) {
                out.write(data, 0, data.size)
            }

            return this
        }

        fun done(): ByteArray = out.toByteArray()
    }

    /** Бокс с готовым телом: длина, имя, тело. */
    private fun box(name: String, body: ByteArray?): ByteArray =
        Buf().put32((body?.size ?: 0) + 8).tag(name).bytes(body).done()

    /** Полный бокс: версия и флаги перед телом. */
    private fun fullBox(name: String, version: Int, flags: Int, body: ByteArray?): ByteArray =
        box(name, Buf().put8(version).put8((flags shr 16) and 0xFF)
            .put8((flags shr 8) and 0xFF).put8(flags and 0xFF).bytes(body).done())

    // --- Разобранная дорожка ----------------------------------------------

    /**
     * Дорожка целиком: описание кодека и таблица сэмплов.
     *
     * Сэмплы держатся плоскими рядами чисел, а не объектами. У часового
     * ролика их под сотню тысяч, и сотня тысяч объектов — это и память,
     * и работа сборщику мусора там, где нужны четыре числа.
     */
    private class TrackPlan {

        var init: TrackInit? = null

        var offsets = LongArray(4096)
        var sizes = IntArray(4096)
        var durations = IntArray(4096)
        var shifts = IntArray(4096)
        var syncs = BooleanArray(4096)

        var count = 0
        var syncCount = 0

        var totalBytes: Long = 0
        var totalTicks: Long = 0

        /** Куда лёг первый сэмпл в собранном файле. */
        var chunkStart: Long = 0

        fun room() {
            if (count < offsets.size) {
                return
            }

            val bigger = offsets.size * 2

            offsets = offsets.copyOf(bigger)
            sizes = sizes.copyOf(bigger)
            durations = durations.copyOf(bigger)
            shifts = shifts.copyOf(bigger)
            syncs = syncs.copyOf(bigger)
        }

        fun add(offset: Long, size: Int, duration: Int, shift: Int, sync: Boolean) {
            room()

            offsets[count] = offset
            sizes[count] = size
            durations[count] = duration
            shifts[count] = shift
            syncs[count] = sync

            if (sync) {
                syncCount++
            }

            totalBytes += size
            totalTicks += duration

            count++
        }
    }

    /** Заголовок бокса по смещению: длина и имя; ничего — читать нечего. */
    private fun readHeader(file: RandomAccessFile, at: Long, length: Long): Pair<Long, String>? {
        if (at + 8 > length) {
            return null
        }

        val head = ByteArray(16)

        try {
            file.seek(at)

            file.readFully(head, 0, 8)
        } catch (error: Exception) {
            return null
        }

        var size = Mp4.read32(head, 0)

        val name = String(
            CharArray(4) { (head[4 + it].toInt() and 0xFF).toChar() }
        )

        // Длина 1 означает, что настоящая лежит следом восемью байтами.
        if (size == 1L) {
            if (at + 16 > length) {
                return null
            }

            try {
                file.readFully(head, 8, 8)
            } catch (error: Exception) {
                return null
            }

            size = Mp4.read64(head, 8)
        }

        if (size < 8) {
            return null
        }

        return Pair(size, name)
    }

    /**
     * Проходит дорожку по боксам и собирает таблицу сэмплов.
     *
     * `sidx` не читается вовсе, и это нарочно: карта фрагментов нужна
     * тому, кто хочет прыгнуть в середину, а мы идём подряд от начала
     * до конца. Пары `moof` + `mdat` лежат в файле по порядку — этого
     * довольно.
     */
    private fun planFor(path: File): TrackPlan? {
        val length = path.length()

        val plan = TrackPlan()

        try {
            RandomAccessFile(path, "r").use { file ->
                var position = 0L

                while (position + 8 <= length) {
                    val head = readHeader(file, position, length) ?: break

                    val size = head.first

                    if (position + size > length) {
                        break
                    }

                    if (head.second == "moov") {
                        /**
                         * Описание кодека. Разбору нужен `ftyp` вместе
                         * с `moov`, поэтому отдаём начало файла целиком
                         * до конца этого бокса.
                         */
                        val whole = ByteArray((position + size).toInt())

                        file.seek(0)
                        file.readFully(whole)

                        plan.init = Mp4.parseInit(whole)
                    } else if (head.second == "moof") {
                        /**
                         * За `moof` всегда идёт `mdat` — в нём и лежат
                         * сэмплы. Читаем сам `moof`: разбору нужен он,
                         * а данные мы отсюда только адресуем, не копируем.
                         */
                        val next = readHeader(file, position + size, length) ?: break

                        if (next.second != "mdat" || position + size + next.first > length) {
                            break
                        }

                        val moof = ByteArray(size.toInt())

                        file.seek(position)
                        file.readFully(moof)

                        val fragment = Mp4.parseFragment(moof)

                        if (fragment != null) {
                            for (sample in fragment.samples) {
                                plan.add(
                                    position + fragment.dataOffset + sample.offset,
                                    sample.size,
                                    sample.duration,
                                    sample.compositionOffset,
                                    sample.isSync
                                )
                            }
                        }

                        position += size + next.first

                        continue
                    }

                    position += size
                }
            }
        } catch (error: Exception) {
            Log.d { "[YouTube/Сборка] ${path.name}: ${error.message}" }

            return null
        }

        val init = plan.init

        if (init == null || plan.count == 0) {
            Log.d {
                "[YouTube/Сборка] ${path.name}: разбор не дал ни описания, ни сэмплов"
            }

            return null
        }

        /**
         * Всё, чем дорожка описана, — в журнал.
         *
         * Негодный файл выглядит одинаково при любой причине: плеер
         * открывается и сразу закрывается. Отличить «не нашли SPS»
         * от «не та частота» по этому виду нельзя, а по этим числам —
         * можно за один взгляд.
         */
        Log.d {
            "[YouTube/Сборка] ${path.name}: " +
                (if (init.isVideo) "видео" else "звук") +
                ", шкала ${init.timescale}, сэмплов ${plan.count}, " +
                "тиков ${plan.totalTicks}"
        }

        Log.d {
            if (init.isVideo) {
                "[YouTube/Сборка]   ${init.width}×${init.height}, " +
                    "SPS ${init.sps.size} (${init.sps.firstOrNull()?.size ?: 0} байт), " +
                    "PPS ${init.pps.size}, длина NALU ${init.nalLengthSize}, " +
                    "ключевых ${plan.syncCount}"
            } else {
                "[YouTube/Сборка]   род ${init.audioObjectType}, " +
                    "частота №${init.samplingFrequencyIndex}, " +
                    "каналов ${init.channelConfig}"
            }
        }

        return plan
    }

    // --- Описание кодека ---------------------------------------------------

    /** `avcC` — то же, что лежало в исходной дорожке, собранное заново. */
    private fun avccFor(init: TrackInit): ByteArray? {
        val sps = init.sps.firstOrNull()

        if (sps == null || sps.size < 4) {
            Log.d {
                "[YouTube/Сборка] SPS негоден (${sps?.size ?: 0} байт) — " +
                    "описание кодека собрать нечем"
            }

            return null
        }

        val out = Buf()

        out.put8(1)                        // версия
        out.put8(sps[1].toInt() and 0xFF)  // профиль
        out.put8(sps[2].toInt() and 0xFF)  // совместимость профиля
        out.put8(sps[3].toInt() and 0xFF)  // уровень

        // Шесть единиц в старших битах, затем длина поля длины минус один.
        out.put8(0xFC or (init.nalLengthSize - 1))

        out.put8(0xE0 or init.sps.size)

        for (one in init.sps) {
            out.put16(one.size)
            out.bytes(one)
        }

        out.put8(init.pps.size)

        for (one in init.pps) {
            out.put16(one.size)
            out.bytes(one)
        }

        return out.done()
    }

    /**
     * `esds` для AAC.
     *
     * Дескрипторы MPEG-4 с длиной в переменном виде; длины здесь малы,
     * поэтому один байт на каждую — этого хватает, пока
     * AudioSpecificConfig занимает два байта, а он занимает два.
     */
    private fun esdsFor(init: TrackInit): ByteArray {
        val first = (init.audioObjectType shl 3) or (init.samplingFrequencyIndex shr 1)
        val second = ((init.samplingFrequencyIndex and 1) shl 7) or (init.channelConfig shl 3)

        val out = Buf()

        out.put32(0) // версия и флаги

        // ES_Descriptor
        out.put8(0x03)
        out.put8(25)
        out.put16(0)
        out.put8(0)

        // DecoderConfigDescriptor
        out.put8(0x04)
        out.put8(17)
        out.put8(0x40) // MPEG-4 AAC
        out.put8(0x15) // звуковой поток
        out.put8(0)
        out.put16(0)   // размер буфера
        out.put32(0)   // наибольший битрейт
        out.put32(0)   // средний битрейт

        // DecoderSpecificInfo
        out.put8(0x05)
        out.put8(2)
        out.put8(first)
        out.put8(second)

        // SLConfigDescriptor
        out.put8(0x06)
        out.put8(1)
        out.put8(0x02)

        return out.done()
    }

    /** Частота по номеру из AudioSpecificConfig. */
    private fun rateFor(index: Int): Int {
        val rates = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350, 0, 0, 0
        )

        return rates[index and 0x0F]
    }

    // --- Таблицы сэмплов ---------------------------------------------------

    private fun stblFor(plan: TrackPlan): ByteArray? {
        val init = plan.init ?: return null

        val video = init.isVideo

        // stsd — описание кодека.
        val entry = Buf()

        entry.put32(0).put16(0)  // шесть отведённых байт
        entry.put16(1)           // номер источника данных

        if (video) {
            /**
             * Ровно шестнадцать байт до ширины — ни байтом больше.
             *
             * По стандарту здесь `pre_defined` (2), `reserved` (2)
             * и `pre_defined[3]` (12). Лишнее слово сдвинуло бы всё
             * дальнейшее на четыре байта: ширину, высоту и сам `avcC`.
             * Файл при этом собрался бы и открылся, звук играл бы,
             * а картинки не было бы вовсе.
             */
            entry.put16(0)  // pre_defined
            entry.put16(0)  // reserved
            entry.put32(0).put32(0).put32(0)

            entry.put16(init.width)
            entry.put16(init.height)

            entry.put32(0x00480000L) // 72 точки на дюйм по горизонтали
            entry.put32(0x00480000L) // и по вертикали
            entry.put32(0)
            entry.put16(1)           // кадров на сэмпл

            entry.bytes(ByteArray(32)) // имя кодека — пустое

            entry.put16(0x0018)      // глубина цвета
            entry.put16(0xFFFF)      // таблицы цветов нет

            entry.bytes(box("avcC", avccFor(init) ?: return null))
        } else {
            entry.put32(0).put32(0)
            entry.put16(init.channelConfig)
            entry.put16(16)          // бит на отсчёт
            entry.put16(0).put16(0)

            // Частота записывается как 16.16; старшего слова довольно.
            entry.put16(rateFor(init.samplingFrequencyIndex))
            entry.put16(0)

            entry.bytes(box("esds", esdsFor(init)))
        }

        val stbl = Buf()

        stbl.bytes(
            fullBox(
                "stsd", 0, 0,
                Buf().put32(1).bytes(box(if (video) "avc1" else "mp4a", entry.done())).done()
            )
        )

        /**
         * stts — длительности, сжатые пробегами.
         *
         * У видео с постоянной частотой кадров пробег выходит один
         * на всю дорожку: вместо сотни тысяч записей — одна.
         */
        val stts = Buf()

        var runs = 0
        var index = 0

        while (index < plan.count) {
            val value = plan.durations[index]

            var same = 1

            while (index + same < plan.count && plan.durations[index + same] == value) {
                same++
            }

            stts.put32(same)
            stts.put32(value)

            runs++
            index += same
        }

        stbl.bytes(fullBox("stts", 0, 0, Buf().put32(runs).bytes(stts.done()).done()))

        /**
         * ctts — сдвиг показа, и **всегда нулевой версии**.
         *
         * В первой версии сдвиг знаковый, и это удобно: `trun` у YouTube
         * приносит и отрицательные. Но версия эта появилась поздней
         * правкой к стандарту, и разборщики постарше её не берут — файл
         * получает отказ целиком, ещё до дорожек.
         *
         * Поэтому приводим к нулевой: если среди сдвигов есть
         * отрицательные, поднимаем все на одну и ту же величину.
         * Взаимный порядок кадров от этого не меняется — сдвигается
         * только точка отсчёта показа, общая для всей дорожки.
         */
        var lift = 0
        var shifted = false

        for (at in 0 until plan.count) {
            if (plan.shifts[at] < lift) {
                lift = plan.shifts[at]
            }

            if (plan.shifts[at] != 0) {
                shifted = true
            }
        }

        lift = -lift

        if (shifted) {
            val ctts = Buf()

            var entries = 0

            index = 0

            while (index < plan.count) {
                val value = plan.shifts[index]

                var same = 1

                while (index + same < plan.count && plan.shifts[index + same] == value) {
                    same++
                }

                ctts.put32(same)
                ctts.put32(value + lift)

                entries++
                index += same
            }

            stbl.bytes(fullBox("ctts", 0, 0, Buf().put32(entries).bytes(ctts.done()).done()))

            if (lift != 0) {
                Log.d {
                    "[YouTube/Сборка] Сдвиги показа подняты на $lift — " +
                        "нулевая версия ctts знака не знает"
                }
            }
        }

        // stss — ключевые кадры; у звука ключевые все, и бокс не нужен.
        if (video && plan.syncCount > 0 && plan.syncCount < plan.count) {
            val body = Buf()

            body.put32(plan.syncCount)

            for (at in 0 until plan.count) {
                if (plan.syncs[at]) {
                    body.put32(at + 1)
                }
            }

            stbl.bytes(fullBox("stss", 0, 0, body.done()))
        }

        /**
         * stsc и co64 — одна порция на всю дорожку.
         *
         * Мы сами кладём сэмплы дорожки подряд, без чередования, поэтому
         * порция ровно одна и смещение у неё одно. Обычные файлы дробят
         * дорожку на порции ради чередования видео со звуком; нам это
         * не нужно — файл лежит на диске, а не течёт по сети.
         */
        stbl.bytes(
            fullBox(
                "stsc", 0, 0,
                Buf().put32(1).put32(1).put32(plan.count).put32(1).done()
            )
        )

        val stsz = Buf()

        stsz.put32(0)          // размеры разные
        stsz.put32(plan.count)

        for (at in 0 until plan.count) {
            stsz.put32(plan.sizes[at])
        }

        stbl.bytes(fullBox("stsz", 0, 0, stsz.done()))

        /**
         * co64, а не stco: смещение первой порции звука лежит за всем
         * видео, и у длинного ролика в 32 бита оно уже не всегда влезает.
         * Восемь байт — одна запись на дорожку, дешевле, чем гадать.
         */
        val co64 = Buf()

        co64.put32(1)
        co64.put32((plan.chunkStart ushr 32) and 0xFFFFFFFFL)
        co64.put32(plan.chunkStart and 0xFFFFFFFFL)

        stbl.bytes(fullBox("co64", 0, 0, co64.done()))

        return box("stbl", stbl.done())
    }

    // --- Дорожка целиком ---------------------------------------------------

    private fun trakFor(plan: TrackPlan, number: Int): ByteArray? {
        val init = plan.init ?: return null

        val video = init.isVideo

        val seconds = if (init.timescale > 0) {
            plan.totalTicks * MOVIE_SCALE / init.timescale
        } else {
            0L
        }

        val tkhd = Buf()

        tkhd.put32(0)               // создан
        tkhd.put32(0)               // изменён
        tkhd.put32(number)
        tkhd.put32(0)
        tkhd.put32(seconds)
        tkhd.put32(0).put32(0)
        tkhd.put16(0)               // слой
        tkhd.put16(0)               // группа
        tkhd.put16(if (video) 0 else 0x0100) // громкость
        tkhd.put16(0)

        // Единичная матрица преобразования.
        val matrix = longArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)

        for (value in matrix) {
            tkhd.put32(value)
        }

        tkhd.put32(if (video) init.width.toLong() shl 16 else 0L)
        tkhd.put32(if (video) init.height.toLong() shl 16 else 0L)

        val trak = Buf()

        // Флаг 3: дорожка участвует и в фильме, и в показе.
        trak.bytes(fullBox("tkhd", 0, 3, tkhd.done()))

        val mdhd = Buf()

        mdhd.put32(0)
        mdhd.put32(0)
        mdhd.put32(init.timescale)
        mdhd.put32(plan.totalTicks and 0xFFFFFFFFL)
        mdhd.put16(0x55C4)          // «und» — язык не указан
        mdhd.put16(0)

        val mdia = Buf()

        mdia.bytes(fullBox("mdhd", 0, 0, mdhd.done()))

        val hdlr = Buf()

        hdlr.put32(0)
        hdlr.tag(if (video) "vide" else "soun")
        hdlr.put32(0).put32(0).put32(0)
        hdlr.put8(0)                // имя обработчика — пустое

        mdia.bytes(fullBox("hdlr", 0, 0, hdlr.done()))

        val minf = Buf()

        if (video) {
            minf.bytes(
                fullBox("vmhd", 0, 1, Buf().put16(0).put16(0).put16(0).put16(0).done())
            )
        } else {
            minf.bytes(fullBox("smhd", 0, 0, Buf().put16(0).put16(0).done()))
        }

        // dinf/dref: данные лежат в этом же файле — одна запись с флагом 1.
        val dref = Buf()

        dref.put32(1)
        dref.bytes(fullBox("url ", 0, 1, null))

        minf.bytes(box("dinf", fullBox("dref", 0, 0, dref.done())))
        minf.bytes(stblFor(plan) ?: return null)

        mdia.bytes(box("minf", minf.done()))

        trak.bytes(box("mdia", mdia.done()))

        return box("trak", trak.done())
    }

    /** `moov` целиком — при уже известных местах порций. */
    private fun moovFor(video: TrackPlan, audio: TrackPlan?): ByteArray? {
        val init = video.init ?: return null

        val seconds = if (init.timescale > 0) {
            video.totalTicks * MOVIE_SCALE / init.timescale
        } else {
            0L
        }

        val mvhd = Buf()

        mvhd.put32(0)
        mvhd.put32(0)
        mvhd.put32(MOVIE_SCALE)
        mvhd.put32(seconds)
        mvhd.put32(0x00010000L)     // скорость
        mvhd.put16(0x0100)          // громкость
        mvhd.put16(0)
        mvhd.put32(0).put32(0)

        val matrix = longArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)

        for (value in matrix) {
            mvhd.put32(value)
        }

        for (index in 0 until 6) {
            mvhd.put32(0)
        }

        mvhd.put32(if (audio != null) 3 else 2) // следующий номер дорожки

        val moov = Buf()

        moov.bytes(fullBox("mvhd", 0, 0, mvhd.done()))
        moov.bytes(trakFor(video, 1) ?: return null)

        if (audio != null) {
            moov.bytes(trakFor(audio, 2) ?: return null)
        }

        return box("moov", moov.done())
    }

    // --- Сборка ------------------------------------------------------------

    /**
     * Складывает [videoPath] и [audioPath] в [path].
     *
     * Оба входных файла — дорожки, скачанные целиком, как их отдаёт
     * YouTube: `ftyp` + `moov` + `sidx` + фрагменты подряд. Так же
     * устроено и то, что складывает подача: заголовок дорожки, а следом
     * фрагменты. [audioPath] может быть пустым — тогда получится файл
     * без звука.
     *
     * [progress] зовётся по ходу с долей 0…1; вернёт false — сборка
     * бросается. Зовётся не с главного потока.
     *
     * false, если разобрать дорожки не вышло. Недописанный файл при этом
     * убирается: половина MP4 хуже, чем его отсутствие, — она выглядит
     * готовой и не играет.
     */
    fun writeTo(
        path: File,
        videoPath: File,
        audioPath: File?,
        progress: ((Float) -> Boolean)? = null
    ): Boolean {
        val video = planFor(videoPath)

        if (video == null) {
            Log.d { "[YouTube/Сборка] Видеодорожка не разобралась" }

            return false
        }

        /**
         * Видеодорожка обязана быть видеодорожкой.
         *
         * `isVideo` у разбора означает «нашлись SPS». Не нашлись — и всё
         * дальнейшее пойдёт по звуковой ветке: дорожка получит описание
         * `mp4a` и обработчик `soun`, а внутри будет H.264. Файл при этом
         * соберётся, но плеер откроет его и сразу закроет.
         */
        if (video.init?.isVideo != true) {
            Log.d { "[YouTube/Сборка] У видеодорожки нет SPS — собирать нечего" }

            return false
        }

        val audio = if (audioPath != null && audioPath.length() > 0) {
            planFor(audioPath)
        } else {
            null
        }

        Log.d {
            "[YouTube/Сборка] Видео: сэмплов ${video.count}, ${video.totalBytes} байт; " +
                "звук: " + (if (audio != null) "${audio.count} сэмплов" else "нет")
        }

        try {
            path.delete()

            // ftyp: обычный `isom`, какой понимают все.
            val header = box(
                "ftyp",
                Buf().tag("isom").put32(512).tag("isom").tag("iso2")
                    .tag("avc1").tag("mp41").done()
            )

            /**
             * `moov` пишется **перед** данными, а не после них.
             *
             * Хвостовой `moov` стандарт допускает, но разборщики
             * постарше его не любят: файл получает отказ ещё до дорожек.
             * Порядок «описание, потом данные» — тот, что кладут все.
             *
             * Сложность в том, что таблицы содержат места порций,
             * а места зависят от длины самого `moov`. Выход простой:
             * собрать `moov` дважды. Длина от значений смещений
             * не меняется — `co64` хранит их по восемь байт независимо
             * от величины, — поэтому второй сбор даёт ровно тот же
             * размер, что и первый.
             */
            video.chunkStart = 0

            audio?.chunkStart = video.totalBytes

            val draft = moovFor(video, audio) ?: return false

            val dataStart = header.size + draft.size + 16L

            video.chunkStart = dataStart

            audio?.chunkStart = dataStart + video.totalBytes

            val moov = moovFor(video, audio) ?: return false

            if (moov.size != draft.size) {
                Log.d {
                    "[YouTube/Сборка] Описание изменило длину " +
                        "(${draft.size} → ${moov.size}) — места порций " +
                        "сошлись бы неверно"
                }

                return false
            }

            val payload = video.totalBytes + (audio?.totalBytes ?: 0L)

            var done = 0L

            BufferedOutputStream(FileOutputStream(path), COPY_CHUNK).use { out ->
                out.write(header)
                out.write(moov)

                /**
                 * `mdat` с длиной в 64 бита, и длина известна заранее:
                 * это сумма размеров сэмплов плюс собственный заголовок.
                 * Править её потом не придётся.
                 */
                val mdatSize = payload + 16

                out.write(
                    Buf().put32(1).tag("mdat")
                        .put32((mdatSize ushr 32) and 0xFFFFFFFFL)
                        .put32(mdatSize and 0xFFFFFFFFL).done()
                )

                val buffer = ByteArray(COPY_CHUNK)

                for (which in 0 until 2) {
                    val plan = if (which == 0) video else audio ?: continue
                    val source = if (which == 0) videoPath else audioPath ?: continue

                    RandomAccessFile(source, "r").use { file ->
                        for (index in 0 until plan.count) {
                            file.seek(plan.offsets[index])

                            var left = plan.sizes[index]

                            while (left > 0) {
                                val take = if (left < COPY_CHUNK) left else COPY_CHUNK

                                val read = file.read(buffer, 0, take)

                                if (read <= 0) {
                                    break
                                }

                                out.write(buffer, 0, read)

                                left -= read
                                done += read
                            }

                            if (index and 0x3F == 0 && progress != null) {
                                val part = if (payload > 0) {
                                    done.toFloat() / payload.toFloat()
                                } else {
                                    0f
                                }

                                if (!progress(part)) {
                                    Log.d { "[YouTube/Сборка] Брошена по просьбе" }

                                    throw InterruptedException("отменено")
                                }
                            }
                        }
                    }
                }
            }

            Log.d {
                "[YouTube/Сборка] Готово: ${path.name} (${path.length()} байт, " +
                    "описание ${moov.size} байт впереди)"
            }

            return true
        } catch (error: Exception) {
            if (error !is InterruptedException) {
                Log.d { "[YouTube/Сборка] ${path.name}: ${error.message}" }
            }

            path.delete()

            return false
        }
    }
}
