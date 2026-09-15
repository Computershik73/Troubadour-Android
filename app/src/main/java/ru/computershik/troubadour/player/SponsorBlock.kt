package ru.computershik.troubadour.player

import org.json.JSONArray
import org.json.JSONObject
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.net.Http
import ru.computershik.troubadour.net.Json
import java.security.MessageDigest

/** Кусок ролика, который стоит пропустить. */
class SponsorSegment(val start: Double, val end: Double, val category: String)

/**
 * Клиент общей базы SponsorBlock — порт `SponsorBlock.cs`.
 *
 * Спрашиваем по приставке хеша: наружу уходят четыре первых знака SHA-256
 * от номера ролика, а не сам номер, — сервер так и не узнаёт, что именно
 * смотрят. В ответе все ролики с такой приставкой, нужный отбирается у нас.
 */
object SponsorBlock {

    /**
     * Что пропускаем без спроса.
     *
     * `sponsor` — оплаченная вставка, `selfpromo` — реклама самого автора,
     * `interaction` — просьба поставить лайк и подписаться. Заставка,
     * титры и посторонняя музыка нарочно не тронуты: это части самого
     * ролика, а не вставки в него. Список тот же, что в оригинале.
     */
    private const val CATEGORIES = "[\"sponsor\",\"selfpromo\",\"interaction\"]"

    /** Первые четыре знака SHA-256 от номера ролика. */
    private fun hashPrefixFor(videoId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))

        return String.format("%02x%02x", digest[0], digest[1])
    }

    /**
     * Куски, которые стоит пропустить, по возрастанию времени.
     *
     * Ходит в сеть — зовётся из фона. Пустой список вместо ошибки: служба
     * посторонняя, и её молчание не повод чему-либо ломаться.
     */
    fun segmentsFor(videoId: String?): List<SponsorSegment> {
        val segments = ArrayList<SponsorSegment>()

        if (videoId.isNullOrEmpty()) {
            return segments
        }

        /**
         * Перечень разрядов кодируется целиком, вместе со скобками.
         *
         * В оригинале это стоило отдельной отладки: тамошний кодировщик
         * квадратные скобки не трогал — по его меркам они в адресе
         * законны, — а `NSURL` с ними разбирать адрес отказывался и отвечал
         * `nil`. Запрос не уходил вовсе, ни к одному ролику. Снаружи это
         * выглядело как «SponsorBlock не работает никогда», и ровно так
         * и было.
         */
        val address = "https://sponsor.ajay.app/api/skipSegments/" +
            "${hashPrefixFor(videoId)}?categories=${Http.encodeParameter(CATEGORIES)}"

        val builder = Http.request(address)

        if (builder == null) {
            Log.d { "[YouTube/SponsorBlock] Адрес не разобран: $address" }

            return segments
        }

        Log.d { "[YouTube/SponsorBlock] → $address" }

        val response = Http.sendCached(builder.build(), 2 * 1024 * 1024, 3600.0)

        if (!response.isSuccessful) {
            // 404 значит лишь, что для этой приставки хеша никто ничего
            // не размечал, — обычное дело, не беда.
            Log.d { "[YouTube/SponsorBlock] Ответ: код ${response.statusCode}" }

            return segments
        }

        /**
         * Именно разбор «чего угодно»: у этой службы ответ начинается
         * с массива, а разбор, пропускающий только объект, обрывался
         * на нём молча.
         */
        val root = Json.parseAny(response.body) as? JSONArray

        if (root == null) {
            Log.d { "[YouTube/SponsorBlock] Ответ не разобрался" }

            return segments
        }

        Log.d {
            "[YouTube/SponsorBlock] В ответе роликов: ${root.length()}, ищем $videoId"
        }

        for (index in 0 until root.length()) {
            val video = root.opt(index) as? JSONObject ?: continue

            if (Json.text(video, "videoID") != videoId) {
                continue
            }

            val list = Json.array(video, "segments") ?: continue

            for (at in 0 until list.length()) {
                val node = list.opt(at) as? JSONObject ?: continue

                /**
                 * `mute` и `full` — не пропуск: первое приглушает звук,
                 * второе помечает ролик целиком. Прыгать по ним нельзя.
                 */
                val action = Json.text(node, "actionType")

                if (!action.isNullOrEmpty() && action != "skip") {
                    continue
                }

                val bounds = Json.array(node, "segment") ?: continue

                if (bounds.length() < 2) {
                    continue
                }

                val start = bounds.optDouble(0, 0.0)
                val end = bounds.optDouble(1, 0.0)

                // Пустой или вывернутый кусок заставил бы пропуск ходить
                // по кругу.
                if (end - start < 0.5) {
                    continue
                }

                segments.add(
                    SponsorSegment(
                        start, end,
                        Json.string(node, "category", "sponsor") ?: "sponsor"
                    )
                )
            }
        }

        segments.sortBy { it.start }

        Log.d { "[YouTube/SponsorBlock] вставок: ${segments.size} у $videoId" }

        return segments
    }
}
