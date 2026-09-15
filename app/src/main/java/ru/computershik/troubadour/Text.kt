package ru.computershik.troubadour

import android.graphics.Paint
import android.os.Build

/**
 * Приводит строку к тому виду, который система в силах нарисовать.
 *
 * Две беды приходят с сервера, и в оригинале обе кончались падением внутри
 * CoreText, не оставляя в отчёте ни одной своей строки:
 *
 * 1. Оборванная суррогатная пара. YouTube режет длинные названия по своей
 *    мерке, не глядя на пары, и у названия со значком в месте обрыва
 *    приезжает половина знака. Такая строка — уже не UTF-16, а строки
 *    в Java точно так же UTF-16, как в Foundation.
 *
 * 2. Знак, которого в этой системе нет вовсе. Значки прибывают в Unicode
 *    каждый год, а Android 4.1 старше их на десятилетие: в его наборе
 *    нет ни одного цветного эмодзи, только чёрно-белые из Roboto.
 *
 * На Android второе не падает, а рисуется пустым прямоугольником — «тофу».
 * Это мягче, чем на iOS, но не лучше: название ролика, наполовину состоящее
 * из пустых квадратов, читается не лучше, чем название без них. Поэтому
 * поведение оставлено то же: знак, которого нечем нарисовать, выбрасывается.
 *
 * Зовётся у самого входа — при разборе ответа сервера, — поэтому дальше
 * по приложению ходит уже безопасный текст. Обычный текст — буквы, цифры,
 * знаки препинания — распознаётся первым же перебором и возвращается как есть.
 */
object Text {

    /**
     * Шрифт для опроса и ширина заведомо отсутствующего знака.
     *
     * `hasGlyph` появился только в API 23. На всём, что старше, работает
     * замер: у знака без глифа система рисует «тофу», и ширина у всех таких
     * знаков одна и та же. Сравниваем с ней.
     *
     * Мерка снимается с непарного знака из области, которую не занимает
     * ни один шрифт (U+FFFE — «не символ»), а не с случайного эмодзи:
     * шрифт может однажды научиться рисовать любой конкретный значок,
     * но «не символ» глифа не получит никогда.
     */
    private val probe = Paint()

    private val tofuWidth: Float by lazy { probe.measureText("￾") }

    /** Знаков в обиходе немного, а строк с ними — тысячи. */
    private val known = HashMap<String, Boolean>()

    fun safe(text: String?): String {
        if (text.isNullOrEmpty()) {
            return text ?: ""
        }

        val repaired = repairPairs(text)

        return drawable(repaired)
    }

    /**
     * Чинит оборванные суррогатные пары.
     *
     * Половина пары заменяется на U+FFFD — так же, как в оригинале.
     * Выбрасывать её нельзя: она может быть единственным знаком строки,
     * и пустое название хуже названия со знаком вопроса.
     */
    private fun repairPairs(text: String): String {
        var broken = false
        var index = 0

        while (index < text.length) {
            val unit = text[index]

            if (unit.isHighSurrogate()) {
                if (index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                    index += 2
                    continue
                }

                broken = true
                break
            }

            if (unit.isLowSurrogate()) {
                broken = true
                break
            }

            index++
        }

        if (!broken) {
            return text
        }

        val units = text.toCharArray()
        var position = 0

        while (position < units.size) {
            val unit = units[position]

            // Старшая половина: годна, только если за ней идёт младшая.
            if (unit.isHighSurrogate()) {
                if (position + 1 < units.size && units[position + 1].isLowSurrogate()) {
                    position += 2
                    continue
                }

                units[position] = '�'
                position++
                continue
            }

            // Младшая половина сама по себе — всегда обломок.
            if (unit.isLowSurrogate()) {
                units[position] = '�'
            }

            position++
        }

        val fixed = String(units)

        Log.d { "[YouTube/Текст] Оборванная пара UTF-16, чиню: «$fixed»" }

        return fixed
    }

    /** Выбрасывает знаки, которых нет ни в одном шрифте системы. */
    private fun drawable(text: String): String {
        /**
         * Быстрая проверка: пока все единицы ниже 0x2000, это обычный текст —
         * буквы, цифры, знаки препинания, — и ни один шрифт на нём
         * не споткнётся. Такова подавляющая часть строк, и до разбора дело
         * не доходит.
         */
        var exotic = false

        for (unit in text) {
            if (unit.code >= 0x2000) {
                exotic = true
                break
            }
        }

        if (!exotic) {
            return text
        }

        val kept = StringBuilder(text.length)
        var dropped = 0
        var index = 0

        while (index < text.length) {
            val point = text.codePointAt(index)
            val width = Character.charCount(point)
            val piece = text.substring(index, index + width)

            index += width

            if (width == 1 && point < 0x2000) {
                kept.append(piece)
                continue
            }

            /**
             * Знаки-модификаторы поодиночке не рисуются ничем и по замеру
             * всегда выходят «нет глифа» — а выбросив их, мы испортили бы
             * составной значок или букву с диакритикой. Оставляем как есть:
             * вреда от них нет, они лишь меняют соседа.
             */
            if (isCombining(point)) {
                kept.append(piece)
                continue
            }

            if (canDraw(piece)) {
                kept.append(piece)
                continue
            }

            dropped++
        }

        if (dropped == 0) {
            return text
        }

        Log.d { "[YouTube/Текст] Нет глифов для $dropped знаков, убираю их из «$text»" }

        return kept.toString()
    }

    private fun isCombining(point: Int): Boolean {
        return when (Character.getType(point).toByte()) {
            Character.NON_SPACING_MARK,
            Character.COMBINING_SPACING_MARK,
            Character.ENCLOSING_MARK,
            Character.FORMAT -> true
            else -> false
        }
    }

    private fun canDraw(piece: String): Boolean {
        synchronized(known) {
            known[piece]?.let { return it }
        }

        val answer = ask(piece)

        synchronized(known) {
            // Потолок на всякий случай: словарь растёт от чужих данных,
            // а расти ему бесконечно незачем.
            if (known.size > 4000) {
                known.clear()
            }

            known[piece] = answer
        }

        return answer
    }

    private fun ask(piece: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return probe.hasGlyph(piece)
        }

        /**
         * Замер. Ноль означает, что рисовать нечего вовсе; совпадение
         * с шириной «тофу» — что рисуется пустой прямоугольник.
         *
         * Сравнение нестрогое: система может подставить «тофу» из другого
         * шрифта с чуть иной шириной, но разница там в доли точки.
         */
        val width = probe.measureText(piece)

        if (width <= 0f) {
            return false
        }

        return Math.abs(width - tofuWidth) > 0.5f
    }
}

/** Короткое имя — оно зовётся из каждого разбора ответа. */
fun safeText(text: String?): String = Text.safe(text)
