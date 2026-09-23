package ru.computershik.troubadour.net

import ru.computershik.troubadour.Log

/**
 * Число дизлайков — у Return YouTube Dislike.
 *
 * Сам YouTube его больше не отдаёт: в конце 2021 года счётчик убрали
 * из всех ответов, и узнать его у сервера нельзя никаким клиентом.
 * Return YouTube Dislike собирает его по старым данным и по оценкам
 * своих пользователей, так что это **оценка**, а не точный счёт —
 * об этом сказано и в настройке, которой его выключают.
 *
 * Запрос уходит стороннему сервису и несёт номер ролика — поэтому
 * он и выключаемый.
 */
object Dislikes {

    private const val ADDRESS = "https://returnyoutubedislikeapi.com/votes?videoId="

    /**
     * Сколько держать ответ.
     *
     * Число меняется медленно, а открывают ролик нередко по нескольку
     * раз подряд — назад и снова, из очереди и из истории.
     */
    private const val KEEP_SECONDS = 600.0

    /** Число дизлайков или `null`, если сервис не ответил. */
    fun count(videoId: String?): Long? {
        if (videoId.isNullOrEmpty()) {
            return null
        }

        val builder = Http.request(ADDRESS + Http.encodeParameter(videoId)) ?: return null

        val response = Http.sendCached(builder.build(), 64 * 1024, KEEP_SECONDS)

        val json = Json.parse(response.body)

        if (json == null || !json.has("dislikes")) {
            Log.d { "[YouTube/Дизлайки] $videoId: ответа нет (код ${response.statusCode})" }

            return null
        }

        val dislikes = json.optLong("dislikes", -1)

        if (dislikes < 0) {
            return null
        }

        Log.d { "[YouTube/Дизлайки] $videoId: $dislikes" }

        return dislikes
    }
}
