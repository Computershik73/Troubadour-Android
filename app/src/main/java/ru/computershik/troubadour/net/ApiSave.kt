package ru.computershik.troubadour.net

import org.json.JSONArray
import org.json.JSONObject
import ru.computershik.troubadour.Log

/**
 * «Сохранить» — ролик в плейлист и обратно.
 *
 * Порт `GetSavePlaylistStatesAsync` и `SetVideoSavedToPlaylistAsync`
 * из UWP-оригинала. Путь тот же, каким ходит само TV-приложение YouTube,
 * — это видно по его коду в дампе `yttv5`: `playlist/get_add_to_playlist`
 * с `videoIds` возвращает плейлисты учётной записи, у каждого отметка
 * `containsSelectedVideos`, а нажатие шлёт `browse/edit_playlist`
 * с `playlistId` и `actions`.
 *
 * Клиенты — TVHTML5 первым, MWEB и WEB следом, как у оценки: токен
 * у нас от входа по QR-коду, и принимает его в паре с собой именно
 * TV-клиент. Оригинал пишет о том же: веб-клиент с таким токеном
 * список отдаёт, но отметку «уже лежит здесь» может не прислать.
 */

/** Плейлист в списке «Сохранить» и лежит ли в нём ролик. */
class PlaylistSaveState {
    var playlistId: String = ""
    var title: String = ""
    var contains: Boolean = false
}

/**
 * Плейлисты учётной записи с отметкой о ролике.
 *
 * `null` — не ответил ни один клиент. Пустым список не бывает:
 * «Смотреть позже» есть у всех, и ответ без единого плейлиста
 * считается отказом — так же и в оригинале.
 */
fun Api.playlistSaveStates(videoId: String?): List<PlaylistSaveState>? {
    if (videoId.isNullOrEmpty() || !Auth.isSignedIn()) {
        return null
    }

    val body = JSONObject()

    body.put("videoIds", JSONArray().put(videoId))
    body.put("excludeWatchLater", false)

    for (client in listOf("TVHTML5", "MWEB", "WEB")) {
        val json = post("playlist/get_add_to_playlist", body, client, true, 0.0)

        val options = Json.findAll("playlistAddToOptionRenderer", json, 16000)

        if (options.isEmpty()) {
            Log.d { "[YouTube/Плейлисты] Список для «Сохранить»: пусто от $client" }

            continue
        }

        val seen = HashSet<String>()
        val result = ArrayList<PlaylistSaveState>()

        for (option in options) {
            val id = normalizedPlaylistId(Json.text(option, "playlistId"))

            if (id.isEmpty() || !seen.add(id)) {
                continue
            }

            val state = PlaylistSaveState()

            state.playlistId = id
            state.title = Json.renderedText(option, "title") ?: id
            state.contains = containsVideo(option)

            result.add(state)
        }

        Log.d {
            "[YouTube/Плейлисты] Для «Сохранить»: ${result.size} от $client, " +
                "с роликом ${result.count { it.contains }}"
        }

        return result
    }

    return null
}

/**
 * Кладёт ролик в плейлист или убирает из него.
 *
 * «Понравившиеся» (`LL`) — не плейлист, который правят: ролик туда
 * попадает лайком. Для него и шлём оценку, как оригинал.
 */
fun Api.setSavedToPlaylist(playlistId: String?, videoId: String?, save: Boolean): Boolean {
    if (playlistId.isNullOrEmpty() || videoId.isNullOrEmpty() || !Auth.isSignedIn()) {
        return false
    }

    val id = normalizedPlaylistId(playlistId)

    if (id == "LL") {
        return rate(videoId, if (save) "like" else "none", null)
    }

    val action = JSONObject()

    if (save) {
        action.put("action", "ACTION_ADD_VIDEO")
        action.put("addedVideoId", videoId)
    } else {
        action.put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
        action.put("removedVideoId", videoId)
    }

    val body = JSONObject()

    body.put("playlistId", id)
    body.put("actions", JSONArray().put(action))

    /**
     * Узел — `browse/edit_playlist`, а не `playlist/edit`.
     *
     * Так его называет сам сервер в `playlistEditEndpoint`; оригинал
     * отдельно предупреждает, что `playlist/edit` на такое тело отвечает
     * отказом всегда.
     */
    for (client in listOf("TVHTML5", "MWEB", "WEB")) {
        val json = post("browse/edit_playlist", body, client, true, 0.0)

        if (json != null) {
            Log.d {
                "[YouTube/Плейлисты] $videoId ${if (save) "в" else "из"} $id: принято ($client)"
            }

            return true
        }

        Log.d { "[YouTube/Плейлисты] $videoId ${if (save) "в" else "из"} $id: отказ от $client" }
    }

    return false
}

/** В списке «Сохранить» у плейлиста бывает приставка `VL` — в правке её нет. */
private fun normalizedPlaylistId(id: String?): String {
    val value = id?.trim() ?: return ""

    return if (value.startsWith("VL") && value.length > 2) value.substring(2) else value
}

/**
 * Лежит ли ролик в плейлисте.
 *
 * Отметка приходит строкой — `ALL`, `SOME` или `NONE`, — а у иных
 * клиентов и логическим значением. «Частично» бывает, когда спрашивают
 * о нескольких роликах разом; у одного ролика это то же «да».
 */
private fun containsVideo(option: JSONObject): Boolean {
    return when (val value = option.opt("containsSelectedVideos")) {
        is Boolean -> value
        is String -> value.equals("ALL", true) ||
            value.equals("SOME", true) ||
            value.equals("true", true)
        else -> false
    }
}
