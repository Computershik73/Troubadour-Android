package ru.computershik.troubadour

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Перевод надписей приложения.
 *
 * Ключ — сама русская строка, а не выдуманное имя вроде `settings_title`.
 * Причина простая: приложение уже написано по-русски, и всякое иное решение
 * потребовало бы переписать каждую строку дважды — сперва на ключ, потом
 * ключ на перевод. С русской строкой в роли ключа перевод добавляется там,
 * где он есть, а где его нет, остаётся исходная надпись: непереведённое
 * место видно на экране, но ничего не ломается.
 *
 * Таблицы лежат в `assets/lang/<код>.json` — те же самые файлы, что
 * в оригинале, — и читаются по требованию: при первом обращении и заново
 * при смене языка. Русского файла нет вовсе: для него перевод — это сама
 * строка.
 *
 * Через `values-<язык>` так не сделать по двум причинам сразу: ключом там
 * не может быть произвольная строка, и выбирает язык система, а не мы,
 * тогда как язык надписей здесь — настройка приложения.
 */
object Strings {

    /**
     * Языки, которые приложение знает. Порядок — тот, в каком они показаны
     * в настройках: сперва те, на которых говорят его пользователи, дальше
     * по распространённости.
     */
    val languages: List<Pair<String, String>> = listOf(
        "ru" to "Русский",
        "en" to "English",
        "uk" to "Українська",
        "pl" to "Polski",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "it" to "Italiano",
        "pt" to "Português",
        "zh" to "中文",
        "ja" to "日本語",
        "ar" to "العربية",
        "fa" to "فارسی"
    )

    private val lock = Any()

    private var table: Map<String, String>? = null
    private var tableCode: String? = null

    /**
     * Какой язык взять у системы.
     *
     * Locale отдаёт коды вида `ru`, `en`, `zh` — смотрим, знаем ли такой.
     * Не знаем — английский: он ближе к незнакомому языку, чем русский,
     * на котором приложение написано.
     */
    private fun systemLanguage(): String {
        val code = Locale.getDefault().language.lowercase(Locale.US)

        return if (languages.any { it.first == code }) code else "en"
    }

    /** Код языка, на котором приложение говорит сейчас. */
    fun current(): String {
        val chosen = Settings.interfaceLanguage

        return if (chosen.isNotEmpty()) chosen else systemLanguage()
    }

    /** Забыть прочитанную таблицу — после смены языка в настройках. */
    fun reset() {
        synchronized(lock) {
            table = null
            tableCode = null
        }
    }

    /** Пишется ли этот язык справа налево. */
    fun isRightToLeft(): Boolean {
        val code = current()

        return code == "ar" || code == "fa"
    }

    /** Человеческое название языка по коду; для пустого — «Как в системе». */
    fun title(code: String): String {
        if (code.isEmpty()) {
            return loc("Как в системе")
        }

        return languages.firstOrNull { it.first == code }?.second ?: code
    }

    private fun table(): Map<String, String> {
        val code = current()

        synchronized(lock) {
            val ready = table

            if (ready != null && tableCode == code) {
                return ready
            }
        }

        var parsed: Map<String, String>? = null

        if (code != "ru") {
            parsed = read(code)

            if (parsed == null) {
                Log.d { "[YouTube/Язык] Перевод «$code» не прочитан, остаёмся на русском" }
            }
        }

        val ready = parsed ?: emptyMap()

        synchronized(lock) {
            table = ready
            tableCode = code
        }

        return ready
    }

    private fun read(code: String): Map<String, String>? {
        val context: Context = App.context ?: return null

        return try {
            val text = context.assets.open("lang/$code.json")
                .use { it.readBytes() }
                .toString(Charsets.UTF_8)

            val json = JSONObject(text)
            val result = HashMap<String, String>(json.length())

            val keys = json.keys()

            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key)

                if (value.isNotEmpty()) {
                    result[key] = value
                }
            }

            result
        } catch (error: Exception) {
            null
        }
    }

    fun translate(russian: String): String {
        if (russian.isEmpty()) {
            return russian
        }

        // Нет перевода — отдаём исходную строку: пусть будет по-русски,
        // но будет.
        return table()[russian] ?: russian
    }
}

/** Перевод надписи. Ключ — русская строка. */
fun loc(russian: String): String = Strings.translate(russian)

/**
 * То же с подстановкой: `locF("Версия %@", version)`.
 *
 * Заполнители в таблицах перевода записаны по-обжективосишному (`%@`, `%ld`,
 * `%lu`): файлы взяты у оригинала без правки, чтобы обе версии переводились
 * одними и теми же строками и `check-strings` проверял их вместе. Здесь они
 * переводятся в java-подобные прямо перед подстановкой.
 */
fun locF(russian: String, vararg arguments: Any?): String {
    val pattern = Strings.translate(russian)

    return try {
        String.format(Locale.US, javaFormat(pattern), *arguments)
    } catch (error: Exception) {
        // Перевод с испорченным заполнителем не должен ронять экран:
        // лучше показать исходную русскую строку.
        try {
            String.format(Locale.US, javaFormat(russian), *arguments)
        } catch (again: Exception) {
            russian
        }
    }
}

/**
 * `%@` → `%s`, `%ld`/`%lu`/`%llu` → `%d`, `%zu` → `%d`.
 *
 * Порядок замен важен: длинные окончания разбираются раньше коротких,
 * иначе `%llu` превратилось бы в `%ld` + мусор.
 */
private fun javaFormat(pattern: String): String {
    val result = StringBuilder(pattern.length)
    var index = 0

    while (index < pattern.length) {
        val symbol = pattern[index]

        if (symbol != '%') {
            result.append(symbol)
            index++
            continue
        }

        if (index + 1 < pattern.length && pattern[index + 1] == '%') {
            result.append("%%")
            index += 2
            continue
        }

        // Флаги и ширина переносятся как есть: `%.2f`, `%.0f%%`, `%03ld`.
        var tail = index + 1

        while (tail < pattern.length && (pattern[tail] in "0123456789.+- #'")) {
            tail++
        }

        val flags = pattern.substring(index + 1, tail)
        val rest = pattern.substring(tail)

        when {
            rest.startsWith("@") -> {
                result.append('%').append(flags).append('s')
                index = tail + 1
            }
            rest.startsWith("llu") || rest.startsWith("lld") -> {
                result.append('%').append(flags).append('d')
                index = tail + 3
            }
            rest.startsWith("lu") || rest.startsWith("ld") || rest.startsWith("zu") -> {
                result.append('%').append(flags).append('d')
                index = tail + 2
            }
            else -> {
                result.append('%').append(flags)
                index = tail
            }
        }
    }

    return result.toString()
}
