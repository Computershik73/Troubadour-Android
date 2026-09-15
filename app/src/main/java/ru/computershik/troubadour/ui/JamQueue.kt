package ru.computershik.troubadour.ui

import ru.computershik.troubadour.Log
import ru.computershik.troubadour.model.VideoItem

/**
 * Очередь микса — своя, накопленная.
 *
 * Порт `queueFrom:` из iOS-версии, а тот, в свою очередь, — `MergeJamQueue`
 * из версии для Windows 10 Mobile.
 *
 * Сохранённый плейлист приходит целиком и в неизменном порядке, поэтому
 * свежий ответ и есть истина. Микс («джем») устроен иначе: сервер каждый
 * раз пересобирает скользящее окно вокруг нынешнего ролика. Брать его
 * как есть — значит на каждом переходе терять всё прослушанное и видеть
 * новый список, где выбранный ролик оказывается первым. Именно это
 * и происходило.
 *
 * Поэтому список ведём сами: он растёт, порядок в нём держится,
 * а дописывается он **только** когда играет последний в нём ролик —
 * так же растёт микс и на самом YouTube.
 *
 * Состояние общее на приложение, а не на страницу: страница ролика при
 * переходе может смениться, а микс продолжается. В iOS-версии оно
 * по той же причине статическое.
 */
object JamQueue {

    private var playlistId: String? = null

    private var items: MutableList<VideoItem> = ArrayList()

    private fun placeOf(videoId: String?, list: List<VideoItem>): Int {
        if (videoId.isNullOrEmpty()) {
            return -1
        }

        for (index in list.indices) {
            if (list[index].videoId == videoId) {
                return index
            }
        }

        return -1
    }

    /** Что показать в списке: у плейлиста — присланное, у микса — накопленное. */
    fun merge(playlist: String?, videoId: String, fresh: List<VideoItem>): List<VideoItem> {
        // Микс узнаётся по приставке: `RD` — автоподборка, `PL` и прочие — нет.
        val mix = playlist != null && playlist.startsWith("RD")

        if (!mix) {
            playlistId = null
            items = ArrayList()

            return fresh
        }

        if (items.isEmpty() || playlistId != playlist) {
            // Микс другой — история прежнего ни к чему.
            playlistId = playlist
            items = ArrayList(fresh)

            Log.d { "[YouTube/Очередь] Микс заведён заново: ${items.size} роликов" }

            return items
        }

        val place = placeOf(videoId, items)

        if (place >= 0 && place < items.size - 1) {
            Log.d {
                "[YouTube/Очередь] Микс не трогаем: ${place + 1} из ${items.size}"
            }

            return items
        }

        var added = 0

        for (item in fresh) {
            if (placeOf(item.videoId, items) < 0) {
                items.add(item)

                added++
            }
        }

        Log.d {
            "[YouTube/Очередь] Дошли до конца микса, дописано $added, " +
                "всего ${items.size}"
        }

        return items
    }

    /** Забыть накопленное — при уходе со страницы ролика без подборки. */
    fun forget() {
        playlistId = null
        items = ArrayList()
    }
}
