package ru.computershik.troubadour.net

import org.json.JSONArray
import org.json.JSONObject
import ru.computershik.troubadour.Log

/**
 * Чат трансляции — порт `liveChat:` и `liveChatFiltersForVideo:`
 * из iOS-версии.
 *
 * Разговор берётся страницами по метке продолжения: каждый ответ несёт
 * новые сообщения и метку следующей страницы, а старая после ответа
 * уже не годится. Сервер сам называет, когда приходить снова, — обычно
 * десять секунд, и спорить с ним незачем: чаще он всё равно ничего
 * не отдаст.
 *
 * Клиент здесь **WEB и без подписи**. Своего чата у TV-клиента нет вовсе,
 * а вошедшим этот запрос быть не обязан: читать разговор даёт кому угодно.
 */

/** Одно сообщение чата. */
class ChatItem {
    var author: String = ""
    var text: String = ""
    var avatar: String? = null

    /** Метка сообщения: по ней отличают уже виденное от нового. */
    var id: String? = null
}

/** Страница чата: сообщения, метка следующей и задержка до неё. */
class ChatPage {
    var items: List<ChatItem> = emptyList()
    var continuation: String? = null

    /** Сколько ждать до следующего захода, в миллисекундах. */
    var waitMillis: Long = 10_000

    /**
     * Закреплённое сообщение.
     *
     * Приходит отдельным действием и живёт, пока автор его не снимет:
     * своего повторения в следующих страницах у него нет. Поэтому панель
     * держит последнее виденное, а не ждёт его в каждом ответе.
     */
    var banner: ChatItem? = null
}

/** Один пункт выбора: «интересные сообщения» или «все сообщения». */
class ChatFilter {
    var token: String = ""
    var serverTitle: String = ""
}

/**
 * Страница чата по метке продолжения.
 *
 * `null` — сервер не ответил или ответил не чатом. Молчание чат
 * не кончает: у трансляции бывают и пустые ответы, и звать сюда
 * следует снова с той же меткой.
 */
fun Api.liveChat(token: String?): ChatPage? {
    if (token.isNullOrEmpty()) {
        return null
    }

    val body = JSONObject()

    body.put("continuation", token)

    val json = post("live_chat/get_live_chat", body, "WEB", authorize = false)
        ?: return null

    val feed = Json.findFirst("liveChatContinuation", json, 200000) ?: return null

    val page = ChatPage()

    page.banner = bannerIn(feed)
    page.items = messagesIn(feed)

    continuationIn(feed, page)

    return page
}

/**
 * Закреплённое сообщение, если оно пришло этой страницей.
 */
private fun bannerIn(feed: JSONObject): ChatItem? {
    for (action in objectsIn(Json.array(feed, "actions"))) {
        val add = Json.findFirst("addBannerToLiveChatCommand", action, 2000) ?: continue

        val said = Json.findFirst("liveChatTextMessageRenderer", add, 4000) ?: continue

        val text = chatTextIn(said)

        if (text.isEmpty()) {
            continue
        }

        val item = ChatItem()

        item.author = Json.renderedText(said, "authorName") ?: ""
        item.text = text

        return item
    }

    return null
}

private fun messagesIn(feed: JSONObject): List<ChatItem> {
    val items = ArrayList<ChatItem>()

    for (action in objectsIn(Json.array(feed, "actions"))) {
        val add = Json.findFirst("addChatItemAction", action, 2000) ?: continue

        val said = Json.findFirst("liveChatTextMessageRenderer", add, 2000) ?: continue

        val text = chatTextIn(said)

        if (text.isEmpty()) {
            continue
        }

        val item = ChatItem()

        item.author = Json.renderedText(said, "authorName") ?: ""
        item.text = text
        item.avatar = Json.thumbnail(said, "authorPhoto", 24)
        item.id = Json.text(said, "id")

        items.add(item)
    }

    return items
}

/**
 * Метка следующей страницы и названная сервером задержка.
 *
 * Меток бывает три вида, и берётся первая нашедшаяся: `invalidation`
 * у живого чата, `timed` у него же на медленной ленте, `reload` —
 * у записи трансляции.
 */
private fun continuationIn(feed: JSONObject, page: ChatPage) {
    for (step in objectsIn(Json.array(feed, "continuations"))) {
        val data = Json.findFirst("invalidationContinuationData", step, 2000)
            ?: Json.findFirst("timedContinuationData", step, 2000)
            ?: Json.findFirst("reloadContinuationData", step, 2000)
            ?: continue

        val next = Json.text(data, "continuation")

        if (next.isNullOrEmpty()) {
            continue
        }

        page.continuation = next

        val said = Json.long(data, "timeoutMs")

        /**
         * Потолок в тридцать секунд — тот же, что в iOS-версии.
         *
         * Сервер иногда называет заметно больше, и тогда разговор
         * подолгу стоит на месте, хотя идёт.
         */
        if (said > 1000) {
            page.waitMillis = minOf(30_000L, said)
        }

        return
    }
}

/**
 * Склеивает текст сообщения из `runs`: куски строк и смайлы вперемешку.
 *
 * У обычного смайла в `emojiId` лежит сам символ, у канальных — длинный
 * ключ; вместо него подставляется ярлык вида `:name:`, картинки не тянем.
 */
private fun chatTextIn(said: JSONObject): String {
    val text = StringBuilder()

    for (run in objectsIn(Json.array(Json.obj(said, "message"), "runs"))) {
        val piece = Json.text(run, "text")

        if (!piece.isNullOrEmpty()) {
            text.append(piece)

            continue
        }

        val emoji = Json.obj(run, "emoji") ?: continue

        var sign = Json.text(emoji, "emojiId") ?: ""

        if (sign.length > 4) {
            val shortcuts = Json.array(emoji, "shortcuts")

            sign = if (shortcuts != null && shortcuts.length() > 0) {
                shortcuts.optString(0, "")
            } else {
                ""
            }
        }

        text.append(sign)
    }

    return text.toString()
}

/**
 * Метки фильтров чата: «интересные сообщения» и «все сообщения»,
 * в этом порядке.
 *
 * Берутся со страницы `/live_chat`, а не через InnerTube: в ответе `next`
 * те же метки приходят урезанными, и сервер отвечает на них отказом.
 * `null`, если чата нет или страница не разобралась.
 */
fun Api.liveChatFilters(videoId: String?): List<ChatFilter>? {
    if (videoId.isNullOrEmpty()) {
        return null
    }

    val address = "https://www.youtube.com/live_chat?v=$videoId&is_popout=1"

    val builder = Http.request(address) ?: return null

    /**
     * Представляемся браузером: странице `/live_chat` в разметке нужен
     * тот же `ytInitialData`, что видит браузер. Мобильному клиенту
     * сервер отдаёт другую страницу, где подменю фильтров нет вовсе.
     */
    builder.header(
        "User-Agent",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    val response = Http.send(builder.build(), 4 * 1024 * 1024, caching = false)

    val body = response.body

    if (!response.isSuccessful || body == null || body.isEmpty()) {
        return null
    }

    val data = jsonAfterMarker("ytInitialData", String(body, Charsets.UTF_8)) ?: return null

    val menu = Json.findFirst("sortFilterSubMenuRenderer", data, 200000)

    val items = objectsIn(Json.array(menu, "subMenuItems"))

    if (items.size < 2) {
        return null
    }

    val filters = ArrayList<ChatFilter>()

    for (item in items) {
        val reload = Json.findFirst("reloadContinuationData", item, 2000)

        val token = Json.text(reload, "continuation")

        if (token.isNullOrEmpty()) {
            continue
        }

        val filter = ChatFilter()

        filter.token = token
        filter.serverTitle = Json.renderedText(item, "title") ?: ""

        filters.add(filter)
    }

    Log.d { "[YouTube/Чат] Фильтров со страницы: ${filters.size}" }

    return if (filters.size >= 2) filters else null
}

/**
 * Достаёт объект JSON, лежащий в разметке после названия.
 *
 * Считаем скобки, а не ищем закрывающую: внутри страницы этот объект
 * идёт одной строкой в полмегабайта, и в нём полно и тех и других.
 * Кавычки при счёте пропускаем целиком — иначе скобка внутри чьего-то
 * ника оборвала бы разбор, — а экранированную кавычку не принимаем
 * за конец строки.
 */
private fun jsonAfterMarker(marker: String, page: String): JSONObject? {
    val found = page.indexOf(marker)

    if (found < 0) {
        return null
    }

    var at = found + marker.length

    // От названия до самой скобки идут кавычки, скобки и знак равенства.
    while (at < page.length && page[at] != '{') {
        val sign = page[at]

        if (sign == ';' || sign == '<') {
            return null
        }

        at++
    }

    if (at >= page.length) {
        return null
    }

    val start = at

    var depth = 0
    var inString = false
    var escaped = false

    var i = start

    while (i < page.length) {
        val sign = page[i]

        if (inString) {
            when {
                escaped -> escaped = false
                sign == '\\' -> escaped = true
                sign == '"' -> inString = false
            }

            i++

            continue
        }

        when (sign) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--

                if (depth == 0) {
                    return Json.parse(page.substring(start, i + 1))
                }
            }
        }

        i++
    }

    return null
}

/** Объекты массива подряд — в `org.json` своего перебора нет. */
private fun objectsIn(array: JSONArray?): List<JSONObject> {
    if (array == null) {
        return emptyList()
    }

    val out = ArrayList<JSONObject>(array.length())

    for (i in 0 until array.length()) {
        (array.opt(i) as? JSONObject)?.let { out.add(it) }
    }

    return out
}
