package ru.computershik.troubadour.player

/**
 * Номера частей UMP, которые мы разбираем. Полный список —
 * в `ump_part_id.proto` у TubeReplacer; здесь только нужные.
 */
object UmpPart {

    /** Заголовок сегмента: дорожка, номер, длина, признак init. */
    const val MEDIA_HEADER = 20

    /** Кусок сегмента. Первый байт — номер заголовка, к которому он. */
    const val MEDIA = 21

    /** Сегмент кончился. Тело — один байт с номером заголовка. */
    const val MEDIA_END = 22

    /** Как вести себя со следующим запросом; там же playback cookie. */
    const val NEXT_REQUEST_POLICY = 35

    /** Сведения о дорожке: сколько сегментов, какова длительность. */
    const val FORMAT_INITIALIZATION = 42

    /** Подача переехала на другой адрес. */
    const val REDIRECT = 43

    /** Запрос отвергнут — тело объясняет, чем именно. */
    const val ERROR = 44

    /**
     * Ответ `/player` устарел — сервер просит взять его заново.
     * Медиа в таком ответе нет вовсе.
     */
    const val RELOAD = 46

    /** Правила начала воспроизведения. */
    const val START_POLICY = 47

    /** Метка запроса. */
    const val REQUEST_ID = 52

    /** Правила отмены запроса. */
    const val CANCEL_POLICY = 53

    /** Требуется ли подтверждение подлинности (PO-токен). */
    const val PROTECTION_STATUS = 58

    /**
     * Сведения об эфире: докуда снято прямо сейчас.
     *
     * Приходит только у трансляций и в каждом ответе. Внутри — номер
     * последнего снятого куска и его время; по ним видно, есть ли смысл
     * просить следующий или его ещё снимают.
     */
    const val LIVE_HEAD = 31
}

/** Одна часть потока UMP. */
class UmpChunk(val type: Int, val body: ByteArray, val at: Int, val length: Int) {

    /** Тело отдельным массивом — там, где его надо сохранить. */
    fun copy(): ByteArray = body.copyOfRange(at, at + length)

    /** Читатель поверх тела, без копии. */
    fun reader(): ProtoReader = ProtoReader(body, at, at + length)
}

/**
 * Читатель потока UMP — того, чем отвечает подача SABR.
 *
 * Ответ устроен как череда частей: номер, длина, столько байт. Ничего
 * сложнее в нём нет, и вся хитрость в одном — **длины считаются не так,
 * как в protobuf**.
 *
 * В protobuf число переменной длины набирается по семь бит на байт,
 * а старший бит говорит «дальше есть ещё». Здесь же длину задают старшие
 * биты **первого** байта, как в UTF-8: `0xxxxxxx` — число в одном байте,
 * `10xxxxxx` — в двух, `110xxxxx` — в трёх и так далее, причём остальные
 * байты идут младшими вперёд. Спутать эти два способа — значит поехать
 * по всему потоку с первой же части, и понять это по обрывкам данных
 * будет уже нельзя. Поэтому здесь свой читатель, а не тот, что в [Proto].
 */
object Ump {

    /**
     * Число переменной длины по правилам UMP.
     *
     * Длину задаёт первый байт своими старшими битами, а сами разряды идут
     * младшими вперёд. Пять байт — особый случай: тогда первый байт
     * не несёт разрядов вовсе, и число лежит в четырёх следующих.
     *
     * Возвращает -1, если байтов не хватило: это не порча потока, а обычное
     * дело — часть пришла не целиком и дочитается следующим куском.
     * Прочитанное число кладётся в [out], новое положение — в `out[1]`.
     */
    private fun varint(bytes: ByteArray, length: Int, at: Int, out: LongArray): Int {
        if (at >= length) {
            return -1
        }

        val first = bytes[at].toInt() and 0xFF

        val size: Int
        var value: Long

        when {
            first and 0x80 == 0x00 -> {
                size = 1
                value = first.toLong()
            }

            first and 0xC0 == 0x80 -> {
                size = 2
                value = (first and 0x3F).toLong()
            }

            first and 0xE0 == 0xC0 -> {
                size = 3
                value = (first and 0x1F).toLong()
            }

            first and 0xF0 == 0xE0 -> {
                size = 4
                value = (first and 0x0F).toLong()
            }

            first == 0xF0 -> {
                size = 5
                value = 0
            }

            else -> return -1
        }

        if (at + size > length) {
            return -1
        }

        /**
         * Разряды из первого байта уже взяты; остальные байты добавляются
         * старше их. Для пятибайтового случая разрядов в первом нет,
         * поэтому сдвиг начинается с нуля.
         */
        var shift = if (size == 5) 0 else (8 - size)

        for (index in 1 until size) {
            value = value or ((bytes[at + index].toLong() and 0xFF) shl shift)

            shift += 8
        }

        out[0] = value

        return at + size
    }

    /**
     * Разбирает поток на части и отдаёт их по одной.
     *
     * Возвращает, сколько байт с конца не разобрано: часть могла прийти
     * не целиком, и её начало надо будет склеить со следующим куском сети.
     * Разобранные части при этом уже отданы.
     *
     * Тело части отдаётся **без копии** — окном в том же массиве. Копию,
     * если она нужна, делает получатель: у куска медиа она не нужна вовсе,
     * его сразу переписывают в буфер дорожки.
     */
    fun read(data: ByteArray, length: Int, handler: (UmpChunk) -> Unit): Int {
        var at = 0

        val out = LongArray(1)

        while (at < length) {
            /**
             * Начало части запоминаем до чтения заголовка: если тела
             * не хватило, отступить надо к самому началу части, а не к тому
             * месту, где кончились байты.
             */
            val start = at

            var next = varint(data, length, at, out)

            if (next < 0) {
                at = start

                break
            }

            val type = out[0].toInt()

            next = varint(data, length, next, out)

            if (next < 0) {
                at = start

                break
            }

            val size = out[0].toInt()

            if (size < 0 || next + size > length) {
                at = start

                break
            }

            handler(UmpChunk(type, data, next, size))

            at = next + size
        }

        return length - at
    }
}
