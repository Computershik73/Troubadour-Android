package ru.computershik.troubadour.player

import org.json.JSONObject
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.net.Http
import ru.computershik.troubadour.net.Json

/**
 * Дорожка субтитров.
 *
 * Дорожки перечислены в ответе `/player`,
 * в `captions.playerCaptionsTracklistRenderer`. Сам текст лежит отдельно,
 * по адресу дорожки; просим его в `json3` — там время в миллисекундах
 * и разбирать нечего, тогда как обычный ответ приходит XML-ом.
 */
class SubtitleTrack {

    var language: String? = null
    var name: String? = null
    var url: String? = null
    var automatic: Boolean = false

    /** Как показать в списке: у машинных дорожек к имени добавляется пометка. */
    fun displayName(): String {
        val title = name

        if (title.isNullOrEmpty()) {
            return language ?: ""
        }

        // Пометка та же, что в оригинале: машинную дорожку надо отличать.
        return if (automatic) "$title ${loc("(авто)")}" else title
    }
}

/** Одна реплика: с какой по какую секунду и что показать. */
class SubtitleCue(val start: Double, val end: Double, val text: String)

object Subtitles {

    /** Дорожки из ответа `/player`; пустой список, если субтитров нет. */
    fun tracksIn(playerResponse: JSONObject?): List<SubtitleTrack> {
        val renderer = Json.obj(
            Json.obj(playerResponse, "captions"), "playerCaptionsTracklistRenderer"
        )

        val list = Json.array(renderer, "captionTracks")

        val tracks = ArrayList<SubtitleTrack>()

        if (list != null) {
            for (index in 0 until list.length()) {
                val node = list.opt(index) as? JSONObject ?: continue

                val url = Json.text(node, "baseUrl") ?: continue

                val track = SubtitleTrack()

                track.url = url
                track.language = Json.text(node, "languageCode")
                track.name = Json.renderedText(node, "name")

                /**
                 * Машинная дорожка узнаётся по виду: `kind: "asr"` — это
                 * автоматическое распознавание речи. Так же её метит
                 * и оригинал.
                 */
                track.automatic = Json.text(node, "kind") == "asr"

                if (track.name.isNullOrEmpty()) {
                    track.name = track.language
                }

                tracks.add(track)
            }
        }

        Log.d { "[YouTube/Субтитры] дорожек: ${tracks.size}" }

        return tracks
    }

    /**
     * Реплики дорожки. Ходит в сеть, поэтому зовётся из фона.
     *
     * Пустой список вместо ошибки: субтитров может не оказаться, и это
     * не повод чему-либо ломаться.
     */
    fun cuesFor(track: SubtitleTrack?): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()

        val base = track?.url

        if (base.isNullOrEmpty()) {
            return cues
        }

        // `json3` просим явно: иначе приходит XML, а его разбор нам ни к чему.
        val builder = Http.request("$base&fmt=json3") ?: return cues

        val response = Http.sendCached(builder.build(), 4 * 1024 * 1024, 600.0)

        if (!response.isSuccessful) {
            Log.d { "[YouTube/Субтитры] Дорожка не взялась: код ${response.statusCode}" }

            return cues
        }

        val root = Json.parse(response.body)
        val events = Json.array(root, "events") ?: return cues

        for (index in 0 until events.length()) {
            val event = events.opt(index) as? JSONObject ?: continue

            val start = Json.int(event, "tStartMs")
            val length = Json.int(event, "dDurationMs")

            val text = StringBuilder()

            /**
             * Реплика собрана из кусочков — у машинных дорожек так
             * размечено каждое слово отдельно. Склеиваем подряд, ничего
             * не разделяя: пробелы уже внутри кусочков.
             */
            val pieces = Json.array(event, "segs")

            if (pieces != null) {
                for (at in 0 until pieces.length()) {
                    val piece = pieces.opt(at) as? JSONObject ?: continue

                    text.append(Json.string(piece, "utf8", "") ?: "")
                }
            }

            val plain = text.toString().trim()

            if (plain.isEmpty() || length <= 0) {
                continue
            }

            cues.add(SubtitleCue(start / 1000.0, (start + length) / 1000.0, plain))
        }

        Log.d { "[YouTube/Субтитры] реплик: ${cues.size}" }

        return cues
    }

    /**
     * Реплика, которая приходится на это время, с учётом поправки.
     *
     * Поправка положительная означает «раньше»: реплику читают до того,
     * как её произнесут, а не после. Ищем перебором с конца — реплик
     * бывает под тысячу, а идут они подряд, и нужная почти всегда рядом
     * с прошлой.
     */
    fun cueAt(cues: List<SubtitleCue>, seconds: Double, offset: Double): SubtitleCue? {
        val at = seconds + offset

        for (cue in cues) {
            if (at < cue.start) {
                return null
            }

            if (at < cue.end) {
                return cue
            }
        }

        return null
    }
}
