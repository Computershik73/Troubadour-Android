package ru.computershik.troubadour

/**
 * Короткая запись больших чисел: «4,4 тыс.», «12 тыс.», «1,2 млн».
 *
 * Лайки и просмотры приходят от сервера уже готовой строкой, и своего
 * счёта у нас до сих пор не было. Он понадобился ради дизлайков:
 * Return YouTube Dislike отдаёт голое число, а рядом с «4,4 тыс.»
 * лайков голое «4417» читалось бы как другой счётчик.
 *
 * Правила — как у YouTube, чтобы два числа в одной подложке выглядели
 * одинаково:
 *
 *  - до тысячи — как есть;
 *  - до десяти в разряде — с одним знаком после запятой, отброшенным,
 *    а не округлённым («4,49 тыс.» — это «4,4», а не «4,5»), и без
 *    нулевого («1 тыс.», а не «1,0 тыс.»);
 *  - от десяти — целыми («44 тыс.»).
 *
 * Счёт целочисленный нарочно: `4300 / 1000.0` в двоичной дроби равно
 * 4,2999…, и после отбрасывания выходило бы «4,2».
 */
object Counts {

    fun compact(value: Long): String {
        if (value < 1000) {
            return value.toString()
        }

        val unit: Long
        val pattern: String

        when {
            value < 1_000_000L -> {
                unit = 1_000L
                pattern = "%@ тыс."
            }
            value < 1_000_000_000L -> {
                unit = 1_000_000L
                pattern = "%@ млн"
            }
            else -> {
                unit = 1_000_000_000L
                pattern = "%@ млрд"
            }
        }

        val whole = value / unit

        val number = if (whole < 10) {
            val tenths = value * 10 / unit

            if (tenths % 10 == 0L) {
                (tenths / 10).toString()
            } else {
                "${tenths / 10}${separator()}${tenths % 10}"
            }
        } else {
            whole.toString()
        }

        return locF(pattern, number)
    }

    /**
     * Знак дробной части — по языку приложения, а не системы.
     *
     * Задан таблицей, а не взят у `DecimalFormatSymbols`: тот на разных
     * версиях Android отвечает по-разному (у арабского — то точку, то
     * `٫`), а цифры у нас латинские всегда, и к ним подходит точка.
     */
    private fun separator(): Char {
        return when (Strings.current()) {
            "ru", "uk", "pl", "de", "fr", "es", "pt", "it" -> ','
            else -> '.'
        }
    }
}
