package ru.computershik.troubadour.net

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import ru.computershik.troubadour.safeText

/**
 * Мелкие обёртки над org.json.
 *
 * Смысл тот же, что у `Json.cs` в UWP-версии и `YTJson` в iOS-версии:
 * ответы InnerTube — это дерево «рендереров» глубиной в десяток уровней,
 * где почти любое поле может отсутствовать, прийти пустым или оказаться
 * другого типа, чем в прошлый раз. Разбор от этого падать не должен:
 * пропавший заголовок стоит пустой строки, а не пустого экрана.
 *
 * Внешней библиотеки нет намеренно: org.json есть в системе с API 1,
 * ничего не весит и не требует рефлексии — на Dalvik это заметно. Gson
 * и Moshi здесь были бы вредны вдвойне: разбирать в готовые классы нечего,
 * форма ответа меняется от недели к неделе.
 *
 * Одна приятная разница с оригиналом: у Android `JSONObject` внутри
 * `LinkedHashMap`, то есть порядок ключей сохраняется. У `NSDictionary`
 * порядок не определён вовсе, и обход дерева там шёл в произвольном
 * порядке — при потолке на число узлов это значило, что один и тот же
 * ответ мог разобраться то так, то этак.
 */
object Json {

    /**
     * Разбор тела ответа. Возвращает null, если это не объект JSON.
     *
     * Верхним уровнем InnerTube всегда отдаёт объект; массив приходит только
     * внутри полей, поэтому чужой тип здесь означает не тот ответ.
     */
    fun parse(data: ByteArray?): JSONObject? {
        return parseAny(data) as? JSONObject
    }

    /**
     * То же, но каким бы ни был верхний уровень: годится и массив.
     *
     * Нужен для чужих служб. SponsorBlock отдаёт массив, и разбор молча
     * возвращал пустоту: запрос уходил, ответ приходил, вставок
     * не находилось никогда.
     */
    fun parseAny(data: ByteArray?): Any? {
        if (data == null || data.isEmpty()) {
            return null
        }

        return parseAny(String(data, Charsets.UTF_8))
    }

    fun parseAny(text: String?): Any? {
        if (text.isNullOrEmpty()) {
            return null
        }

        return try {
            JSONTokener(text).nextValue()
        } catch (error: Exception) {
            null
        }
    }

    fun parse(text: String?): JSONObject? = parseAny(text) as? JSONObject

    /** Обратно в данные — для тел запросов к InnerTube. */
    fun encode(value: Any?): ByteArray {
        if (value == null) {
            return "{}".toByteArray(Charsets.UTF_8)
        }

        return value.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Достаёт значение по ключу, сводя к null обе разновидности пустоты:
     * отсутствующий ключ и присланный сервером null (он приходит как
     * `JSONObject.NULL`).
     */
    private fun value(parent: JSONObject?, key: String?): Any? {
        if (parent == null || key == null) {
            return null
        }

        if (parent.isNull(key)) {
            return null
        }

        return parent.opt(key)
    }

    fun obj(parent: JSONObject?, key: String): JSONObject? = value(parent, key) as? JSONObject

    fun array(parent: JSONObject?, key: String): JSONArray? = value(parent, key) as? JSONArray

    fun objectAt(array: JSONArray?, index: Int): JSONObject? {
        if (array == null || index < 0 || index >= array.length()) {
            return null
        }

        return array.opt(index) as? JSONObject
    }

    /**
     * Строка, но пустая считается отсутствующей.
     *
     * У InnerTube это встречается постоянно: пустая строка вместо
     * отсутствующего поля — обычный ответ, и без такой проверки запасные
     * варианты («нет автора — возьми из другого места») не срабатывали бы
     * никогда.
     */
    fun text(parent: JSONObject?, key: String): String? =
        string(parent, key)?.takeIf { it.isNotEmpty() }

    /**
     * Строка из ответа — уже проверенная.
     *
     * Здесь единственная воронка, через которую весь текст сервера входит
     * в приложение: и названия, и имена каналов, и подписи кнопок в панелях.
     * Проверка стоит именно тут, а не у каждой подписи, потому что показать
     * этот текст можно по-разному — подписью, заголовком кнопки, строкой
     * системного листа, — а испортить его достаточно один раз.
     *
     * Разбирается ответ на чужом потоке, но проверка ни на что
     * в приложении не смотрит и помнит уже спрошенное, так что чужому
     * потоку не мешает.
     */
    fun string(parent: JSONObject?, key: String, fallback: String? = null): String? {
        val found = value(parent, key) ?: return fallback

        if (found is String) {
            return safeText(found)
        }

        // Числа приходят то числом, то строкой — `lengthSeconds` тому пример.
        if (found is Number || found is Boolean) {
            return found.toString()
        }

        return fallback
    }

    fun int(parent: JSONObject?, key: String, fallback: Int = 0): Int {
        val found = value(parent, key) ?: return fallback

        if (found is Number) {
            return found.toInt()
        }

        if (found is String) {
            // Разбор целиком, а не «сколько прочлось»: «0 просмотров»
            // и «поле не пришло» — разные вещи, и второе должно давать
            // именно fallback.
            return found.trim().toLongOrNull()?.toInt() ?: fallback
        }

        return fallback
    }

    fun long(parent: JSONObject?, key: String, fallback: Long = 0): Long {
        val found = value(parent, key) ?: return fallback

        if (found is Number) {
            return found.toLong()
        }

        if (found is String) {
            return found.trim().toLongOrNull() ?: fallback
        }

        return fallback
    }

    fun double(parent: JSONObject?, key: String, fallback: Double = 0.0): Double {
        val found = value(parent, key) ?: return fallback

        if (found is Number) {
            return found.toDouble()
        }

        if (found is String) {
            return found.trim().toDoubleOrNull() ?: fallback
        }

        return fallback
    }

    fun bool(parent: JSONObject?, key: String, fallback: Boolean = false): Boolean {
        val found = value(parent, key) ?: return fallback

        if (found is Boolean) {
            return found
        }

        if (found is Number) {
            return found.toInt() != 0
        }

        if (found is String) {
            return found.equals("true", ignoreCase = true)
        }

        return fallback
    }

    // --- Формы InnerTube --------------------------------------------------

    /**
     * Текст «рендерера»: `{"simpleText": "…"}` либо `{"runs": [{"text": "…"}, …]}`.
     *
     * Это самая частая форма в ответах InnerTube и одновременно самая
     * коварная: одно и то же поле у разных рендереров приходит то одним
     * видом, то другим — название ролика в `videoRenderer` обычно `runs`,
     * а в `compactVideoRenderer` бывает `simpleText`. Порт
     * `ExtractTextFromField` из VideoAPI.cs, где ровно эти две ветки
     * и разбираются.
     */
    fun renderedValue(node: JSONObject?): String? {
        if (node == null) {
            return null
        }

        text(node, "simpleText")?.let { return it }

        /**
         * Третья форма, которой в UWP-версии ещё не было: `{"content": "…"}`.
         *
         * Так размечены новые view-model — `lockupMetadataViewModel`,
         * `contentMetadataViewModel` и прочие, которыми WEB-клиент теперь
         * присылает похожие ролики. Разбор знал только `simpleText` и `runs`,
         * и у таких карточек не находилось ни названия, ни автора: оставалось
         * одно превью, собранное по идентификатору.
         */
        text(node, "content")?.let { return it }

        val runs = array(node, "runs") ?: return null

        val joined = StringBuilder()

        for (index in 0 until runs.length()) {
            val run = runs.opt(index) as? JSONObject ?: continue

            string(run, "text")?.let { joined.append(it) }
        }

        return joined.toString().takeIf { it.isNotEmpty() }
    }

    fun renderedText(parent: JSONObject?, key: String): String? =
        renderedValue(obj(parent, key))

    /**
     * Адрес картинки нужной ширины из `{"thumbnails": [...]}`.
     *
     * Список приходит от мелкой к крупной, и в UWP-версии брался нулевой
     * элемент — то есть самая мелкая. Для кружка канала в 36 точек это
     * разумно, а для превью карточки давало мыло, поэтому здесь берётся
     * нужная по ширине: первая, что не уже запрошенной, иначе последняя.
     *
     * Список приходит четырьмя видами, и все они здесь:
     *
     *     {"sources": [ … ]}                      — новые view-model
     *     {"thumbnails": [ … ]}                   — старые рендереры
     *     {"thumbnail": {"thumbnails": [ … ]}}    — они же, завёрнутые
     *
     * Первый вид долго не читался вовсе: у него список лежит **прямо**
     * под ключом, а помощник ждал под ним объект и искал внутри
     * `thumbnails`. Оттого пропадали превью подборок на странице канала
     * и подложка у новой шапки.
     */
    fun thumbnail(parent: JSONObject?, key: String, minWidth: Int): String? {
        var list = array(parent, key)

        if (list == null) {
            val holder = obj(parent, key)

            list = array(holder, "thumbnails")
                ?: array(holder, "sources")
                ?: array(obj(holder, "thumbnail"), "thumbnails")
        }

        if (list == null || list.length() == 0) {
            return null
        }

        var best: String? = null

        for (index in 0 until list.length()) {
            val item = list.opt(index) as? JSONObject ?: continue
            val url = text(item, "url") ?: continue

            best = url

            // Список идёт от мелкой к крупной: как только дошли
            // до достаточно широкой, дальше смотреть незачем — крупнее
            // только тяжелее.
            if (minWidth > 0 && int(item, "width") >= minWidth) {
                break
            }
        }

        val found = best ?: return null

        // Часть адресов приходит без схемы: «//i.ytimg.com/…».
        if (found.startsWith("//")) {
            return "https:$found"
        }

        return found
    }

    // --- Обход дерева -----------------------------------------------------

    /**
     * Обходит дерево и возвращает первый найденный объект с таким ключом.
     *
     * Ответы InnerTube меняют форму от клиента к клиенту и от недели
     * к неделе: тот же список видео лежит то в `richGridRenderer`, то
     * в `sectionListRenderer`, то на два уровня глубже. UWP-версия
     * по этой же причине искала маркеры по всему дереву
     * (`MaxObjectsToScanForVideoCards`), а не ходила по известному пути.
     *
     * Ограничение по числу просмотренных узлов оттуда же и по той же
     * причине: ответ «Главной» — это мегабайты JSON, и полный обход
     * в поисках того, чего там нет, стоит заметного времени даже
     * на быстром железе.
     *
     * **Находятся только словари.** Значение под искомым ключом берётся
     * лишь тогда, когда это объект: искать так строку, число или список —
     * значит всегда получать null, и притом молча. Оба метода задуманы
     * для поиска рендереров, и это не изъян, а их назначение; за строкой
     * есть [findString].
     *
     * Оговорка не теоретическая: на ней уже потеряно два захода отладки.
     * `createCommentParams` — строка, и поиск её через [findAll] не мог
     * сработать ни при каком потолке.
     */
    fun findFirst(key: String, tree: Any?, limit: Int): JSONObject? {
        val out = ArrayList<JSONObject>(1)
        val visited = intArrayOf(0)

        walk(key, tree, out, if (limit > 0) limit else Int.MAX_VALUE, visited, true)

        return out.firstOrNull()
    }

    /** Все объекты с таким ключом, в порядке обхода. Только словари — см. выше. */
    fun findAll(key: String, tree: Any?, limit: Int): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val visited = intArrayOf(0)

        walk(key, tree, out, if (limit > 0) limit else Int.MAX_VALUE, visited, false)

        return out
    }

    private fun walk(
        key: String,
        node: Any?,
        out: MutableList<JSONObject>,
        limit: Int,
        visited: IntArray,
        stopAtFirst: Boolean
    ) {
        if (visited[0] >= limit) {
            return
        }

        if (node is JSONObject) {
            visited[0]++

            val found = node.opt(key)

            if (found is JSONObject) {
                out.add(found)

                if (stopAtFirst) {
                    return
                }
            }

            val keys = node.keys()

            while (keys.hasNext()) {
                walk(key, node.opt(keys.next()), out, limit, visited, stopAtFirst)

                if (stopAtFirst && out.isNotEmpty()) {
                    return
                }

                if (visited[0] >= limit) {
                    return
                }
            }

            return
        }

        if (node is JSONArray) {
            for (index in 0 until node.length()) {
                walk(key, node.opt(index), out, limit, visited, stopAtFirst)

                if (stopAtFirst && out.isNotEmpty()) {
                    return
                }

                if (visited[0] >= limit) {
                    return
                }
            }
        }
    }

    /**
     * Первая **строка** под таким ключом.
     *
     * Пара к [findFirst], для случая, когда искомое — не рендерер,
     * а значение: непрозрачная метка, идентификатор, готовый адрес.
     * Пустые строки пропускаются: в ответах они попадаются как заглушки.
     *
     * Отдельной веткой, а не признаком у общего обхода: тот кладёт
     * найденное в список объектов, и подмешивать туда строки значило бы
     * заставить всех зовущих проверять род каждого найденного.
     */
    fun findString(key: String, tree: Any?, limit: Int): String? {
        val visited = intArrayOf(0)

        return walkString(key, tree, if (limit > 0) limit else Int.MAX_VALUE, visited)
    }

    private fun walkString(key: String, node: Any?, limit: Int, visited: IntArray): String? {
        if (visited[0] >= limit) {
            return null
        }

        if (node is JSONObject) {
            visited[0]++

            val found = node.opt(key)

            if (found is String && found.isNotEmpty()) {
                return found
            }

            val keys = node.keys()

            while (keys.hasNext()) {
                val deeper = walkString(key, node.opt(keys.next()), limit, visited)

                if (deeper != null) {
                    return deeper
                }

                if (visited[0] >= limit) {
                    return null
                }
            }

            return null
        }

        if (node is JSONArray) {
            for (index in 0 until node.length()) {
                val deeper = walkString(key, node.opt(index), limit, visited)

                if (deeper != null) {
                    return deeper
                }

                if (visited[0] >= limit) {
                    return null
                }
            }
        }

        return null
    }

    /** Найденный рендерер вместе с именем ключа, под которым он лежал. */
    class Named(val name: String, val node: JSONObject)

    /**
     * Поиск сразу по нескольким именам — за один обход.
     *
     * Нужно там, где имён много: [findAll] на каждое имя проходит дерево
     * заново, и потолок в узлах каждый раз отсчитывается с нуля, то есть
     * дальше первых `limit` узлов не заглядывает ни один из проходов.
     * На ленте подписок это и вылезало: ответ TV-клиента больше мегабайта,
     * ролики в нём разложены по полке на канал, и все проходы упирались
     * в первую полку — лента показывала ролики одного канала.
     *
     * Имя нужно разбору — по нему видно, чем считать узел.
     */
    fun findAllOfAny(keys: Set<String>, tree: Any?, limit: Int): List<Named> {
        val out = ArrayList<Named>()
        val visited = intArrayOf(0)

        walkAny(keys, tree, out, if (limit > 0) limit else Int.MAX_VALUE, visited)

        return out
    }

    private fun walkAny(
        keys: Set<String>,
        node: Any?,
        out: MutableList<Named>,
        limit: Int,
        visited: IntArray
    ) {
        if (visited[0] >= limit) {
            return
        }

        if (node is JSONObject) {
            visited[0]++

            val names = node.keys()
            val children = ArrayList<Any?>(node.length())

            while (names.hasNext()) {
                val name = names.next()
                val found = node.opt(name)

                if (keys.contains(name) && found is JSONObject) {
                    out.add(Named(name, found))
                }

                children.add(found)
            }

            for (child in children) {
                walkAny(keys, child, out, limit, visited)

                if (visited[0] >= limit) {
                    return
                }
            }

            return
        }

        if (node is JSONArray) {
            for (index in 0 until node.length()) {
                walkAny(keys, node.opt(index), out, limit, visited)

                if (visited[0] >= limit) {
                    return
                }
            }
        }
    }
}
