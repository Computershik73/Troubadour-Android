package ru.computershik.troubadour.player

import org.json.JSONObject
import ru.computershik.troubadour.net.Json

/** Один кадр раскадровки: где он лежит и какое место занимает на листе. */
class StoryboardFrame(
    val sheet: String,
    val column: Int,
    val row: Int,
    val width: Int,
    val height: Int
)

/** Один уровень описания: свой размер кадра, сетка и шаг по времени. */
private class StoryboardLevel(
    val index: Int,
    val width: Int,
    val height: Int,
    val total: Int,
    val columns: Int,
    val rows: Int,
    val intervalMs: Int,
    val nameTemplate: String,
    val sigh: String
)

/**
 * Раскадровка для перемотки — порт `StoryboardThumbnails.cs`.
 *
 * YouTube кладёт мелкие кадры ролика на общие листы-спрайты, а как их
 * читать, описано одной строкой в ответе `/player`
 * (`storyboards.playerStoryboardSpecRenderer.spec`): адрес с подстановками
 * и через `|` — уровни, каждый со своим размером кадра, числом кадров,
 * сеткой и шагом по времени.
 *
 * В iOS-версии этого не было: там раскадровка числилась среди
 * непереносённого, и переключатель «предпросмотр перемотки» управлял бы
 * пустотой. Здесь она есть — и переключатель тоже.
 */
class Storyboard private constructor(
    private val base: String,
    private val levels: List<StoryboardLevel>
) {

    companion object {

        /** Строка описания из ответа `/player`. */
        @JvmStatic
        fun specIn(playerResponse: JSONObject?): String? = Json.text(
            Json.obj(
                Json.obj(playerResponse, "storyboards"), "playerStoryboardSpecRenderer"
            ),
            "spec"
        )

        /** Разбирает строку описания; null, если она пуста или непонятна. */
        @JvmStatic
        fun parse(spec: String?): Storyboard? {
            if (spec.isNullOrEmpty()) {
                return null
            }

            val parts = spec.split("|")

            if (parts.size < 2) {
                return null
            }

            val levels = ArrayList<StoryboardLevel>()

            for (index in 1 until parts.size) {
                val fields = parts[index].split("#")

                if (fields.size < 8) {
                    continue
                }

                val level = StoryboardLevel(
                    index - 1,
                    fields[0].toIntOrNull() ?: 0,
                    fields[1].toIntOrNull() ?: 0,
                    fields[2].toIntOrNull() ?: 0,
                    fields[3].toIntOrNull() ?: 0,
                    fields[4].toIntOrNull() ?: 0,
                    fields[5].toIntOrNull() ?: 0,
                    fields[6],
                    fields[7]
                )

                if (level.width > 0 && level.height > 0 && level.total > 0 &&
                    level.columns > 0 && level.rows > 0 && level.intervalMs > 0
                ) {
                    levels.add(level)
                }
            }

            if (levels.isEmpty()) {
                return null
            }

            return Storyboard(parts[0], levels)
        }
    }

    /**
     * Уровень, которым показываем.
     *
     * Берём тот, у которого на одном листе **больше всего** кадров: чем
     * плотнее лист, тем меньше их придётся качать. Кадры при этом мельче,
     * но при перемотке важнее, чтобы картинка успевала появиться.
     * При равенстве — тот, что крупнее. Так же выбирает и оригинал.
     */
    private fun best(): StoryboardLevel? {
        var best: StoryboardLevel? = null

        for (level in levels) {
            val perSheet = level.columns * level.rows

            if (best == null) {
                best = level

                continue
            }

            val bestPerSheet = best.columns * best.rows

            if (perSheet > bestPerSheet ||
                (perSheet == bestPerSheet && level.width > best.width)
            ) {
                best = level
            }
        }

        return best
    }

    /** Кадр, который приходится на эту секунду. */
    fun frameAt(seconds: Double): StoryboardFrame? {
        val level = best() ?: return null

        val perSheet = level.columns * level.rows

        var frame = Math.floor(seconds * 1000.0 / level.intervalMs).toInt()

        if (frame < 0) frame = 0
        if (frame > level.total - 1) frame = level.total - 1

        val sheet = frame / perSheet
        val place = frame % perSheet

        val name = level.nameTemplate.replace("\$M", sheet.toString())

        var url = base.replace("\$L", level.index.toString())

        url = url.replace("\$N", name)
        url += (if (url.contains("?")) "&" else "?") + "sigh=" + level.sigh

        return StoryboardFrame(
            url,
            place % level.columns,
            place / level.columns,
            level.width,
            level.height
        )
    }
}
