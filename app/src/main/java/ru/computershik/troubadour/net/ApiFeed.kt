package ru.computershik.troubadour.net

import org.json.JSONArray
import org.json.JSONObject
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.model.VideoItem

/**
 * Разновидность искомого — порт `GetSearchParams` из `Search.xaml.cs`.
 *
 * [VIDEOS] у оригинала соответствует «видео»: без пометки сервер
 * подмешивает в выдачу что попало. Отдельного слова для Shorts там нет
 * намеренно — «Using no type token lets the search response include
 * the Shorts shelf», и уже из общей выдачи отбираются вертикальные.
 */
object SearchKind {
    const val VIDEOS = 0
    const val SHORTS = 1
    const val CHANNELS = 2
    const val PLAYLISTS = 3
}

/**
 * Таблетка над лентой: название и то, что за ней стоит.
 *
 * У всех, кроме двух, это поисковый запрос [query]. У «Всех» пусто —
 * это сама лента. У «Сейчас в эфире» вместо запроса стоит [browse]:
 * поиск по слову «live» приносит что угодно, кроме идущих трансляций,
 * а раздел `FEtopics_live` отвечает ровно ими.
 */
class HomeCategory(val title: String, val query: String, val browse: String = "")

/**
 * Лента «Главной». [continuation] — токен следующей страницы либо null
 * для первой.
 */
fun Api.homeFeed(continuation: String?): Api.Feed? = homeFeed(null, continuation)

/**
 * «Главная» — это `FEwhat_to_watch` у **TV-клиента и с токеном**.
 *
 * Так делает `GetRecommendationsPageAsync`, и никак иначе: там при пустом
 * refresh-токене метод сразу возвращает пустую страницу, а запрос уходит
 * через `PostTvBrowseAsync` — клиент TVHTML5, заголовок `Authorization`.
 *
 * Прежде здесь стоял WEB-клиент без токена, и это была отсебятина. Она же
 * и была причиной пустой ленты у вошедшего: анонимный `FEwhat_to_watch`
 * у WEB-клиента отвечает успешно, но без роликов — рекомендовать ему некому.
 *
 * У невошедшего ленты нет вовсе — тоже как в оригинале. На её месте
 * показывается призыв поискать (`SuggestionsSection` из Home.xaml), это
 * делает сам раздел, увидев пустой ответ.
 *
 * [params] сохранён для совместимости вызова и в обычном ходе не нужен:
 * выбранная таблетка выполняет поиск, а не листает ленту.
 */
fun Api.homeFeed(params: String?, continuation: String?): Api.Feed? {
    if (!Auth.isSignedIn()) {
        return null
    }

    val body = JSONObject()

    if (!continuation.isNullOrEmpty()) {
        body.put("continuation", continuation)
    } else {
        body.put("browseId", "FEwhat_to_watch")

        if (!params.isNullOrEmpty()) {
            body.put("params", params)
        }
    }

    val json = post(
        "browse", body, "TVHTML5", true,
        if (!continuation.isNullOrEmpty()) 0.0 else Api.FEED_TTL
    )

    return feedFrom(json)
}

/**
 * «Таблетки» — **не запрос**, а свой список.
 *
 * Так в оригинале, и там об этом сказано прямо: «Home chips are fixed
 * locally. Do not request categories/chips from Innertube»
 * (`GetHomeCategoriesAsync`). Раньше здесь искался `chipCloudChipRenderer`
 * в ответе «Главной» — лишний запрос ради набора, который и так известен,
 * да ещё и другой у вошедшего.
 *
 * У каждой таблетки, кроме первой, есть поисковый запрос: выбор таблетки
 * в оригинале выполняет обычный поиск по нему
 * (`GetHomeCategoryVideosAsync` → `GetAnonymousSearchVideosAsync`), а не
 * листает ленту с `params`. Запросы английские намеренно — они же в UWP:
 * так выдача не зависит от языка приложения.
 *
 * Список собирается заново при каждом обращении, а не один раз: названия
 * переводятся, а язык надписей меняется в настройках прямо на ходу.
 * В оригинале он собирался единожды — и после смены языка таблетки
 * оставались на прежнем до перезапуска.
 */
/**
 * Готовые запросы для невошедшего — те же восемь, что
 * в `trendingSuggestions` оригинала, и в том же порядке.
 *
 * Рекомендовать анониму некому: `FEwhat_to_watch` отвечает ему успешно,
 * но без роликов. Вместо пустой ленты — список запросов, с которых можно
 * начать.
 */
fun Api.trendingQueries(): List<String> = listOf(
    loc("Музыкальные видео"),
    loc("Игровые моменты"),
    loc("Кулинарные рецепты"),
    loc("Обзоры технологий"),
    loc("Трейлеры фильмов"),
    loc("Спортивные моменты"),
    loc("Комедийные скетчи"),
    loc("Сделай сам")
)

fun Api.homeCategories(): List<HomeCategory> = listOf(
    HomeCategory(loc("Все"), ""),
    /**
     * «Сейчас в эфире» — не поиск, а свой раздел.
     *
     * Стоит второй, сразу за «Всеми», — так же, как в вебе.
     */
    HomeCategory(loc("Сейчас в эфире"), "", "FEtopics_live"),
    HomeCategory(loc("Фильмы и анимация"), "Film & Animation"),
    HomeCategory(loc("Авто"), "Autos & Vehicles"),
    HomeCategory(loc("Музыка"), "Music"),
    HomeCategory(loc("Животные"), "Pets & Animals"),
    HomeCategory(loc("Спорт"), "Sports"),
    HomeCategory(loc("Короткометражки"), "Short Movies"),
    HomeCategory(loc("Путешествия"), "Travel & Events"),
    HomeCategory(loc("Игры"), "Gaming"),
    HomeCategory(loc("Влоги"), "Videoblogging"),
    HomeCategory(loc("Люди и блоги"), "People & Blogs"),
    HomeCategory(loc("Юмор"), "Comedy videos"),
    HomeCategory(loc("Развлечения"), "Entertainment"),
    HomeCategory(loc("Новости и политика"), "News & Politics"),
    HomeCategory(loc("Стиль"), "Howto & Style"),
    HomeCategory(loc("Образование"), "Education"),
    HomeCategory(loc("Наука и техника"), "Science & Technology"),
    HomeCategory(loc("Благотворительность"), "Nonprofits & Activism"),
    HomeCategory(loc("Кино"), "Movies"),
    HomeCategory(loc("Аниме"), "Anime Animation"),
    HomeCategory(loc("Боевики"), "Action Adventure movies"),
    HomeCategory(loc("Классика"), "Classic movies"),
    HomeCategory(loc("Комедии"), "Comedy movies"),
    HomeCategory(loc("Документальные"), "Documentary movies"),
    HomeCategory(loc("Драмы"), "Drama movies"),
    HomeCategory(loc("Семейные"), "Family movies"),
    HomeCategory(loc("Зарубежные"), "Foreign movies"),
    HomeCategory(loc("Ужасы"), "Horror movies"),
    HomeCategory(loc("Фантастика"), "Sci-Fi Fantasy movies"),
    HomeCategory(loc("Триллеры"), "Thriller movies"),
    HomeCategory("Shorts", "YouTube Shorts"),
    HomeCategory(loc("Сериалы"), "Shows"),
    HomeCategory(loc("Трейлеры"), "Trailers")
)

fun Api.search(query: String, continuation: String?): Api.Feed? =
    search(query, continuation, SearchKind.VIDEOS)

fun Api.search(query: String, continuation: String?, kind: Int): Api.Feed? {
    val body = JSONObject()

    if (!continuation.isNullOrEmpty()) {
        body.put("continuation", continuation)
    } else {
        body.put("query", query)

        // Пометки — те же, что в `GetSearchParams`.
        val params = when (kind) {
            SearchKind.PLAYLISTS -> "EgIQAw=="
            SearchKind.CHANNELS -> "EgIQAg=="
            SearchKind.SHORTS -> null
            else -> "EgIQAQ=="
        }

        if (params != null) {
            body.put("params", params)
        }
    }

    var json = post("search", body, "WEB", false, 0.0)

    /**
     * Пустая выдача — тоже повод усомниться в локали.
     *
     * Поиск, в отличие от `browse`, на непонятную локаль отвечает
     * не отказом, а вежливым ничем: код 200, два килобайта одного лишь
     * `responseContext`.
     *
     * Проверка `json != null` здесь обязательна, и это не перестраховка.
     * Пустой ответ и **отсутствие** ответа — разные вещи, а по одному лишь
     * `contents` они неразличимы: у неудачи там тоже пусто. Из-за этого
     * всякий отказ поиска — хоть 401, хоть обрыв связи — проходил
     * за «непонятную локаль» и уводил приложение в `hl=en gl=US`
     * до перезапуска, вместе с лентой и подписями.
     */
    while (json != null &&
        Json.obj(json, "contents") == null &&
        continuation.isNullOrEmpty() &&
        relaxLocaleForSearch()
    ) {
        Log.d { "[YouTube/API] Поиск ничего не дал — повторяем" }

        json = post("search", body, "WEB", false, 0.0)
    }

    /**
     * Каналы разбираются своим ходом: общий разбор знает только ролики
     * и подборки, а `channelRenderer` пропускает — оттого вкладка «Каналы»
     * и отвечала «роликов в нём нет».
     */
    if (kind == SearchKind.CHANNELS) {
        return Api.Feed(VideoItem.parseChannelsFrom(json), continuationIn(json))
    }

    /**
     * Вертикальные отбираются из общей выдачи своим разбором.
     *
     * Пометки `params` для них у YouTube нет — сервер подмешивает их полкой
     * в обычную выдачу, откуда оригинал их и достаёт.
     */
    if (kind == SearchKind.SHORTS) {
        return Api.Feed(VideoItem.parseShortsFrom(json), continuationIn(json))
    }

    val feed = feedFrom(json) ?: Api.Feed(emptyList(), null)

    /**
     * Пусто, а ответ полон — досматриваем вертикальные.
     *
     * Общий разбор их пропускает намеренно: у Shorts своя лента и свой
     * разбор. Но в выдаче поиска они встречаются полкой посреди обычных
     * роликов, и бывает выдача, где кроме них ничего и нет, — по такому
     * запросу экран оставался пустым. Лучше показать вертикальные,
     * чем ничего.
     */
    if (feed.items.isEmpty() && json != null && !Settings.hidesShorts) {
        val shorts = VideoItem.parseShortsFrom(json)

        if (shorts.isNotEmpty()) {
            Log.d {
                "[YouTube/API] Обычных роликов в выдаче нет, " +
                    "зато вертикальных ${shorts.size} — показываем их"
            }

            return Api.Feed(shorts, feed.continuation)
        }
    }

    return feed
}

/**
 * Подсказки поиска — отдельная служба, не InnerTube.
 *
 * Живут они в старой службе `suggestqueries`, и отвечает она не JSON,
 * а JSONP: `window.google.ac.h([...])`. Разбирается это отрезанием обёртки —
 * так же делала UWP-версия.
 */
fun Api.searchSuggestions(query: String): List<String> {
    if (query.isEmpty()) {
        return emptyList()
    }

    /**
     * Кодировку заказываем явно, обе стороны.
     *
     * Служба старая, из времён до InnerTube, и по умолчанию отвечает
     * не в UTF-8: без `oe` кириллица приходила байтами, которые разбор
     * подменял знаками замены — на экране выходил ряд ромбиков
     * с вопросом вместо подсказок.
     */
    val url = "https://suggestqueries.google.com/complete/search" +
        "?client=youtube&ds=yt&ie=utf-8&oe=utf-8" +
        "&hl=${hl()}&q=${Http.encodeParameter(query)}"

    val request = Http.request(url) ?: return emptyList()

    val response = Http.send(request.build(), 256 * 1024)

    if (!response.isSuccessful) {
        return emptyList()
    }

    val text = response.text

    val open = text.indexOf('(')
    val close = text.lastIndexOf(')')

    if (open < 0 || close <= open) {
        return emptyList()
    }

    // Ответ здесь массив, а не объект, поэтому не через Json.parse.
    val parsed = Json.parseAny(text.substring(open + 1, close)) as? JSONArray

    if (parsed == null || parsed.length() < 2) {
        return emptyList()
    }

    val list = parsed.opt(1) as? JSONArray ?: return emptyList()

    val suggestions = ArrayList<String>(list.length())

    for (index in 0 until list.length()) {
        // Каждая строка — это [текст, вес, …]; нужен только текст.
        when (val row = list.opt(index)) {
            is JSONArray -> {
                (row.opt(0) as? String)?.let { suggestions.add(it) }
            }

            is String -> suggestions.add(row)
        }
    }

    return suggestions
}

/**
 * Полка ленты: заголовок и плитки под ним.
 *
 * Изменяемая намеренно — у полки под кнопкой «Показать ещё» меняются
 * и список, и токен. [more] пуст, когда листать больше нечего.
 */
class Shelf(val title: String, val items: MutableList<VideoItem>, var more: String?)

/** Лента, пришедшая полками: плоский список, токен и сами полки. */
class ShelfFeed(
    val items: List<VideoItem>,
    val continuation: String?,
    val groups: List<Shelf>
)

/**
 * Токен из старого списка `continuations`.
 *
 * Берётся **у названного узла**, а не первый попавшийся в дереве — в этом
 * вся разница. У телевизора на странице эфиров таких списков сразу два
 * вида, и они ведут в разные стороны (см. [liveContinuationIn]).
 */
private fun Api.listTokenIn(node: JSONObject?): String? {
    val first = Json.objectAt(Json.array(node, "continuations"), 0)

    Json.text(Json.obj(first, "nextContinuationData"), "continuation")?.let {
        return it
    }

    return Json.text(Json.obj(first, "reloadContinuationData"), "continuation")
}

/**
 * Продолжение страницы эфиров — вниз, а не вбок.
 *
 * На странице `FEtopics_live` живут два разных продолжения:
 *
 *   `sectionListRenderer.continuations` — следующие **полки**;
 *
 *   `shelfRenderer.content.horizontalListRenderer.continuations` —
 *       следующие плитки **одной полки**, по пять штук. Ими телевизор
 *       возит полку вбок, оставаясь на месте по вертикали.
 *
 * Общий [Api.continuationIn] берёт первый попавшийся и попадает
 * на полочный. Оттого лента и превращается в бесконечный ряд без
 * заголовков: тянется вбок одна-единственная полка — в журнале это видно
 * как десяток ответов по восемь килобайт и по пять роликов подряд.
 */
private fun Api.liveContinuationIn(json: JSONObject): String? {
    val contents = Json.obj(json, "continuationContents")

    // Ответ на полочный токен: следующий такой же, и он полочный.
    val horizontal = Json.obj(contents, "horizontalListContinuation")

    if (horizontal != null) {
        return listTokenIn(horizontal)
    }

    val section = Json.obj(contents, "sectionListContinuation")
        ?: Json.findFirst("sectionListRenderer", json, 200000)

    listTokenIn(section)?.takeIf { it.isNotEmpty() }?.let { return it }

    // Безымянному отвечает веб, а у него продолжение обычного вида.
    return continuationIn(json)
}

/**
 * Полки ответа: заголовок и плитки под ним.
 *
 * Разбор тот же, что у «Истории» с её днями, но заголовок у телевизора
 * лежит двумя ступенями глубже: не `shelfHeaderRenderer.title`, а
 * `shelfHeaderRenderer.avatarLockup.avatarLockupRenderer.title` — рядом
 * с кружком канала. Оттого полки и «не находились», хотя в журнале
 * честно значились и `shelfRenderer`, и `avatarLockupRenderer`.
 */
private fun Api.shelvesIn(json: JSONObject): List<Shelf> {
    val groups = ArrayList<Shelf>()
    val seen = HashSet<String>()

    val names = setOf("shelfRenderer", "richShelfRenderer", "itemSectionRenderer")

    for (hit in Json.findAllOfAny(names, json, 200000)) {
        val node = hit.node

        var title = Json.renderedText(node, "title")

        if (title.isNullOrEmpty()) {
            for (name in listOf(
                "shelfHeaderRenderer", "richShelfHeaderRenderer", "headerRenderer"
            )) {
                title = Json.renderedText(Json.findFirst(name, node, 400), "title")

                if (!title.isNullOrEmpty()) {
                    break
                }
            }
        }

        if (title.isNullOrEmpty()) {
            val lockup = Json.findFirst(
                "avatarLockupRenderer", Json.obj(node, "headerRenderer"), 400
            )

            title = Json.renderedText(lockup, "title")
        }

        if (title.isNullOrEmpty()) {
            continue
        }

        val items = ArrayList<VideoItem>()

        for (item in VideoItem.parseFrom(node)) {
            val key = item.videoId?.takeIf { it.isNotEmpty() } ?: item.title

            if (key.isEmpty() || !seen.add(key)) {
                continue
            }

            items.add(item)
        }

        if (items.isEmpty()) {
            continue
        }

        /**
         * Полка листается отдельно от ленты — своим токеном.
         *
         * Телевизор возит полку вбок по пять плиток; у нас полки лежат
         * рядами, и тот же токен даёт кнопку «Показать ещё» под полкой.
         * Ленту он не двигает: для неё есть свой, вертикальный.
         */
        val more = listTokenIn(
            Json.obj(Json.obj(node, "content"), "horizontalListRenderer")
        )

        groups.add(Shelf(title, items, more?.takeIf { it.isNotEmpty() }))
    }

    return groups
}

/**
 * Вкладка «Сейчас в эфире» — это `FEtopics_live` у TV-клиента.
 *
 * У вошедшего и у безымянного разные двери. `FEtopics_live` — раздел
 * телевизора, и он требует токена: без входа не отвечает вовсе.
 * Безымянному эфиры отдаёт канал `UC4R8DWoMoI7CAwX8_LjQHig` — это и есть
 * youtube.com/live, куда браузер попадает без всякой подписи.
 *
 * Полки у них разные по виду, но не по смыслу: у телевизора
 * `shelfRenderer`, у веба `richShelfRenderer`. Разбор знает оба.
 */
fun Api.liveFeed(continuation: String?): ShelfFeed? {
    val signedIn = Auth.isSignedIn()

    val body = JSONObject()

    if (!continuation.isNullOrEmpty()) {
        body.put("continuation", continuation)
    } else {
        body.put(
            "browseId",
            if (signedIn) "FEtopics_live" else "UC4R8DWoMoI7CAwX8_LjQHig"
        )
    }

    val json = post(
        "browse", body, if (signedIn) "TVHTML5" else "WEB", signedIn, 0.0
    ) ?: return null

    val feed = feedFrom(json) ?: Api.Feed(emptyList(), null)

    /**
     * Продолжение переписываем: [Api.feedFrom] берёт первое попавшееся,
     * а здесь их два вида и путать их нельзя.
     */
    val token = liveContinuationIn(json)?.takeIf { it.isNotEmpty() }

    val groups = shelvesIn(json)

    Log.d {
        "[YouTube/Эфиры] полок ${groups.size}, плиток ${feed.items.size}, " +
            "продолжение ${if (token != null) "есть" else "нет"}"
    }

    return ShelfFeed(feed.items, token, groups)
}
