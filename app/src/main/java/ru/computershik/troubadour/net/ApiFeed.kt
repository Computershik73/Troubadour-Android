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

/** Таблетка над лентой: название и поисковый запрос за ней. */
class HomeCategory(val title: String, val query: String)

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
