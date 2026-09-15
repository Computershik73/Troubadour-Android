package ru.computershik.troubadour.player

import ru.computershik.troubadour.Log

/** Глава ролика: с какой секунды начинается и как называется. */
class Chapter(val start: Double, val title: String)

/**
 * Главы — из временных меток в описании, порт
 * `UpdateDescriptionChaptersFromDescription`.
 *
 * Отдельного списка глав сервер не присылает: то, что показывает сам
 * YouTube, он собирает из описания — строк вида «12:34 Название». Берём
 * оттуда же. Метка должна стоять **в начале строки**: число посреди
 * текста («в 3:15 будет видно») главой не является.
 */
object Chapters {

    /**
     * Знаки, которыми отделяют время от названия.
     *
     * Пишут кто во что горазд — «0:00 — Вступление», «0:00 | Вступление»,
     * «0:00. Вступление». Список взят из `ExtractDescriptionChapterTitle`
     * оригинала.
     */
    private const val SEPARATORS = " \t-–—:|.()"

    fun parse(description: String?): List<Chapter> {
        if (description.isNullOrEmpty()) {
            return emptyList()
        }

        val found = ArrayList<Chapter>()

        for (line in description.split('\n', '\r')) {
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                continue
            }

            val head = trimmed.substringBefore(' ')
            val parts = head.split(':')

            if (parts.size < 2 || parts.size > 3) {
                continue
            }

            var seconds = 0.0
            var good = true

            for (part in parts) {
                /**
                 * Одни цифры, и не больше двух: «1:02:03» — время,
                 * «2025:07» — нет.
                 */
                if (part.isEmpty() || part.length > 2 || part.any { !it.isDigit() }) {
                    good = false

                    break
                }

                seconds = seconds * 60 + part.toInt()
            }

            if (!good) {
                continue
            }

            val title = trimmed.substring(head.length).trim { it in SEPARATORS }

            found.add(Chapter(seconds, title))
        }

        if (found.size < 2) {
            // Одна метка — это не разбивка, а просто число в описании.
            return emptyList()
        }

        Log.d { "[YouTube/Плеер] Глав в описании: ${found.size}" }

        return found
    }

    /** Название главы, идущей в этот миг; пусто, если глав нет. */
    fun titleAt(chapters: List<Chapter>, seconds: Double): String {
        var title = ""

        for (chapter in chapters) {
            if (chapter.start > seconds) {
                break
            }

            title = chapter.title
        }

        return title
    }

    /** Начала глав в секундах — деления на полосе. */
    fun starts(chapters: List<Chapter>): List<Double> = chapters.map { it.start }
}
