package ru.computershik.troubadour.player

import java.io.ByteArrayOutputStream

/**
 * Protocol Buffers — ровно столько, сколько нужно для SABR.
 *
 * Подача через SABR разговаривает не JSON'ом, а protobuf: запрос
 * `VideoPlaybackAbrRequest` уходит двоичным телом, ответ приходит потоком
 * UMP, внутри которого снова protobuf.
 *
 * Своё, а не библиотека, и на Android эта причина даже крепче, чем была
 * на iOS. Там `protobuf-objc` требовал куда более новой системы, чем 5.1.
 * Здесь `protobuf-javalite` собралась бы, но потянула бы за собой
 * генерацию классов из `.proto` в сборку, полторы сотни килобайт кода
 * и рефлексию — а формат прост: каждое поле это номер с типом (varint),
 * а дальше либо число, либо длина и байты. Всё, что нам встретится,
 * укладывается в три типа из шести.
 *
 * Описания сообщений взяты из `.proto` TubeReplacer, так что номера полей
 * не угаданы, а известны.
 *
 * Чего здесь нет намеренно: описаний сообщений, генерации классов,
 * проверки обязательных полей. Сообщения собираются и разбираются вручную
 * в том месте, где они нужны, — их немного, и так виднее, что именно
 * уходит на сервер.
 */

/** Тип поля: целое переменной длины. */
private const val TYPE_VARINT = 0

/** Тип поля: длина, а за ней столько байт. */
private const val TYPE_BYTES = 2

/** Тип поля: ровно четыре байта. */
private const val TYPE_FIXED32 = 5

/** Собиратель двоичного сообщения. */
class ProtoWriter {

    private val body = ByteArrayOutputStream(256)

    /**
     * Целое переменной длины: по семь бит на байт, старший бит — признак
     * того, что байт не последний. Маленькие числа занимают один байт,
     * и на этом весь формат и держится.
     */
    private fun appendVarint(value: Long) {
        var rest = value

        do {
            var byte = (rest and 0x7F).toInt()

            rest = rest ushr 7

            if (rest != 0L) {
                byte = byte or 0x80
            }

            body.write(byte)
        } while (rest != 0L)
    }

    /** Заголовок поля: номер и тип, слитые в одно число. */
    private fun putTag(field: Int, type: Int) {
        appendVarint((field.toLong() shl 3) or type.toLong())
    }

    /** Целое поле. Отрицательных здесь не бывает — они кодируются иначе. */
    fun putVarint(value: Long, field: Int): ProtoWriter {
        putTag(field, TYPE_VARINT)
        appendVarint(value)

        return this
    }

    /** Логическое поле — то же целое, 0 или 1. */
    fun putBool(value: Boolean, field: Int): ProtoWriter = putVarint(if (value) 1 else 0, field)

    /**
     * Дробное поле — четыре байта, младшими вперёд.
     *
     * Единственное такое во всём, что мы шлём: скорость воспроизведения.
     */
    fun putFloat(value: Float, field: Int): ProtoWriter {
        putTag(field, TYPE_FIXED32)

        val bits = java.lang.Float.floatToRawIntBits(value)

        for (index in 0 until 4) {
            body.write((bits ushr (index * 8)) and 0xFF)
        }

        return this
    }

    /** Байтовое поле: строка, вложенное сообщение, что угодно. */
    fun putData(value: ByteArray?, field: Int): ProtoWriter {
        if (value == null) {
            return this
        }

        putTag(field, TYPE_BYTES)
        appendVarint(value.size.toLong())

        body.write(value, 0, value.size)

        return this
    }

    /** Строковое поле — те же байты в UTF-8. */
    fun putString(value: String?, field: Int): ProtoWriter {
        if (value.isNullOrEmpty()) {
            return this
        }

        return putData(value.toByteArray(Charsets.UTF_8), field)
    }

    /** Вложенное сообщение. Повторять можно сколько угодно раз. */
    fun putMessage(value: ProtoWriter?, field: Int): ProtoWriter {
        if (value == null) {
            return this
        }

        return putData(value.data(), field)
    }

    /** Собранное сообщение. */
    fun data(): ByteArray = body.toByteArray()
}

/**
 * Читатель двоичного сообщения.
 *
 * Идёт по полям подряд; неизвестные пропускает сам, поэтому новые поля
 * в ответе сервера ничего не ломают — это и есть главное свойство формата,
 * ради которого он выбран.
 */
class ProtoReader(private val body: ByteArray, private val from: Int = 0, to: Int = body.size) {

    private val end = to

    private var at = from

    /** Номер поля, к которому перешли. */
    var field: Int = 0
        private set

    private var type: Int = 0

    /** Границы значения текущего поля. */
    private var valueAt: Int = 0
    private var valueLength: Int = 0

    /** Значение поля-числа: у него границ нет, оно уже прочитано. */
    private var value: Long = 0

    /**
     * Читает целое переменной длины. Возвращает false, если байты
     * кончились посреди числа, — так распознаётся обрыв.
     */
    private fun readVarint(): Long? {
        var result = 0L
        var shift = 0

        while (at < end) {
            val byte = body[at++].toInt() and 0xFF

            result = result or ((byte and 0x7F).toLong() shl shift)

            if (byte and 0x80 == 0) {
                return result
            }

            shift += 7

            // Больше десяти байт в 64-битное число не влезет — значит, мусор.
            if (shift > 63) {
                return null
            }
        }

        return null
    }

    /**
     * Переходит к следующему полю. false — сообщение кончилось либо
     * испорчено.
     */
    fun next(): Boolean {
        if (at >= end) {
            return false
        }

        val tag = readVarint() ?: return false

        field = (tag ushr 3).toInt()
        type = (tag and 0x07).toInt()

        value = 0
        valueAt = 0
        valueLength = 0

        if (type == TYPE_VARINT) {
            val read = readVarint() ?: return false

            value = read

            return true
        }

        if (type == TYPE_BYTES) {
            val size = readVarint() ?: return false

            if (size < 0 || at + size > end) {
                return false
            }

            valueAt = at
            valueLength = size.toInt()

            at += size.toInt()

            return true
        }

        /**
         * Остальные типы нам не встречаются, но пропустить их надо
         * правильно, иначе разбор поедет. 1 — восемь байт, 5 — четыре;
         * групп (3 и 4) в этих сообщениях нет.
         */
        if (type == 1 || type == TYPE_FIXED32) {
            val skip = if (type == 1) 8 else 4

            if (at + skip > end) {
                return false
            }

            at += skip

            return true
        }

        return false
    }

    /** Значение как целое; 0, если поле не того типа. */
    fun takeVarint(): Long = if (type == TYPE_VARINT) value else 0

    /** Значение как байты; null, если поле не того типа. */
    fun takeData(): ByteArray? {
        if (type != TYPE_BYTES) {
            return null
        }

        return body.copyOfRange(valueAt, valueAt + valueLength)
    }

    /**
     * Вложенное сообщение — читателем поверх тех же байтов, без копии.
     *
     * Добавка сверх оригинала, и ради неё стоило: в ответе подачи
     * вложенных сообщений десятки на каждый кусок, и копировать байты
     * ради каждого — заметная работа там, где её меньше всего можно себе
     * позволить.
     */
    fun takeMessage(): ProtoReader? {
        if (type != TYPE_BYTES) {
            return null
        }

        return ProtoReader(body, valueAt, valueAt + valueLength)
    }

    /** Значение как строка UTF-8; null, если поле не того типа. */
    fun takeString(): String? {
        if (type != TYPE_BYTES) {
            return null
        }

        return String(body, valueAt, valueLength, Charsets.UTF_8)
    }
}
