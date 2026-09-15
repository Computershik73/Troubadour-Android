package ru.computershik.troubadour.net

import org.json.JSONArray
import org.json.JSONObject
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.loc

/**
 * Отправка комментария и всё, что для неё нужно.
 *
 * **Телевизором писать можно** — это опыт, ради которого здесь всё
 * и устроено так, а не иначе. Официально телевизор комментировать
 * не умеет: YouTube на нём показывает «оставьте комментарий с телефона
 * или сайта», и панели комментариев в ответе TV-клиента действительно
 * нет — проверено запросом, её там ноль. Но узел `comment/create_comment`
 * про клиента ничего не спрашивает: он проверяет метку и учётную запись,
 * а метка привязана к ролику, не к тому, кто её показал.
 *
 * Отсюда устройство отправки: **метку берём там, где дают, а пишем
 * токеном TV-клиента.** Проверено на устройстве 23.08.2026 — комментарий
 * появился под роликом.
 */

/**
 * `createCommentParams` в ответе — метка, которой сервер разрешает писать.
 *
 * Лежит она в поле ввода над списком, по пути
 * `commentsHeaderRenderer.createRenderer.commentSimpleboxRenderer`, и не
 * в самом поле, а **в его кнопке отправки**. Строка непрозрачная
 * и подписана сервером: собрать её самим нельзя, только взять из ответа —
 * ровно как токены продолжения.
 *
 * Ищем обходом, а не по этому пути: разбор всего остального здесь устроен
 * так же, и место поля от клиента к клиенту разное.
 */
internal fun Api.createParamsIn(json: Any?): String? {
    /**
     * Ищем **строку**, а не объект, и это главное.
     *
     * Первый заход искал метку через поиск словарей, а тот берёт значение
     * под ключом, лишь когда это объект. Метка же строка, и найтись так
     * не могла ни при каком потолке. Отладка ушла в сторону надолго:
     * по журналу это выглядело как «сервер не даёт писать», хотя сервер
     * давал, а не читали мы.
     */
    Json.findString("createCommentParams", json, 200000)?.let { return it }

    /**
     * Запасной путь: метка по форме, а не по имени.
     *
     * На случай, если YouTube переименует ключ. Приметы известны: метка
     * лежит в кнопке отправки поля ввода и записана строкой под ключом,
     * оканчивающимся на `Params`. Следящие метки (`trackingParams`,
     * `clickTrackingParams`) подходят под то же описание, но метками
     * записи не являются — их отбрасываем поимённо.
     */
    val box = Json.findFirst("commentSimpleboxRenderer", json, 200000)

    val guess = paramsLikeIn(box?.opt("submitButton"), 8)

    if (guess != null) {
        Log.d { "[YouTube/Комментарий] Метка взята по форме, длина ${guess.length}" }
    }

    return guess
}

/**
 * Строка под ключом, оканчивающимся на `Params`, — обходом поддерева.
 *
 * Отдельным обходом, а не общим поиском, потому что тот ищет по точному
 * имени, а нам имя как раз и неизвестно.
 */
private fun paramsLikeIn(node: Any?, depth: Int): String? {
    if (depth <= 0 || node == null) {
        return null
    }

    if (node is JSONArray) {
        for (index in 0 until node.length()) {
            paramsLikeIn(node.opt(index), depth - 1)?.let { return it }
        }

        return null
    }

    if (node !is JSONObject) {
        return null
    }

    var keys = node.keys()

    while (keys.hasNext()) {
        val key = keys.next()
        val value = node.opt(key)

        if (key.endsWith("Params") &&
            key != "trackingParams" &&
            key != "clickTrackingParams" &&
            value is String &&
            value.isNotEmpty()
        ) {
            Log.d { "[YouTube/Комментарий] Похоже на метку: $key (${value.length} знаков)" }

            return value
        }
    }

    // Вглубь — только после того, как весь этот уровень осмотрен.
    keys = node.keys()

    while (keys.hasNext()) {
        paramsLikeIn(node.opt(keys.next()), depth - 1)?.let { return it }
    }

    return null
}

/**
 * Что на самом деле лежит в кнопке отправки — строками в журнал.
 *
 * Нужно ровно тогда, когда метку не нашли ни по имени, ни по форме:
 * без этого следующий шаг снова был бы гаданием. Вывод ограничен
 * и по глубине, и по числу строк — журнал пишется на устройстве,
 * и вываливать в него четверть мегабайта незачем.
 */
private fun dumpNode(node: Any?, path: String, depth: Int, lines: MutableList<String>) {
    if (depth <= 0 || lines.size >= 40) {
        return
    }

    if (node is JSONObject) {
        val keys = node.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            val value = node.opt(key)

            val here = if (path.isEmpty()) key else "$path.$key"

            when (value) {
                is String -> lines.add(
                    "$here = ${if (value.length > 40) value.substring(0, 40) + "…" else value}"
                )

                is Number, is Boolean -> lines.add("$here = $value")

                else -> dumpNode(value, here, depth - 1, lines)
            }

            if (lines.size >= 40) {
                return
            }
        }

        return
    }

    if (node is JSONArray) {
        for (index in 0 until node.length()) {
            dumpNode(node.opt(index), "$path[$index]", depth - 1, lines)

            if (lines.size >= 40) {
                return
            }
        }
    }
}

/**
 * Почему метки не оказалось — по виду самого поля ввода.
 *
 * Различать надо два случая, снаружи неотличимые. У анонима поле приходит
 * **без кнопки отправки вовсе**: вместо неё `prepareAccountEndpoint`
 * с окном «Чтобы продолжить, нужно войти в аккаунт». У вошедшего кнопка
 * есть, и метка лежит в ней. То есть отсутствие метки при живой сессии
 * означает не «нельзя писать», а «сервер нас не узнал» — и лечится это
 * входом, а не другим клиентом.
 *
 * Третий случай — поля нет совсем: у ролика закрыты комментарии либо
 * ответ пришёл не тот.
 */
internal fun Api.reportComposerShapeIn(json: Any?) {
    if (!ru.computershik.troubadour.BuildConfig.LOG) {
        return
    }

    val box = Json.findFirst("commentSimpleboxRenderer", json, 200000)

    if (box == null) {
        Log.d {
            "[YouTube/Комментарий] Поля ввода в ответе нет вовсе — " +
                "похоже, комментарии у ролика закрыты"
        }

        return
    }

    val anonymous = box.opt("prepareAccountEndpoint") != null
    val hasSubmit = box.opt("submitButton") != null

    Log.d {
        "[YouTube/Комментарий] Поле ввода в ответе: кнопка отправки " +
            "${if (hasSubmit) "есть" else "нет"}, приглашение войти " +
            "${if (anonymous) "есть" else "нет"} → сервер считает нас " +
            if (anonymous && !hasSubmit) "гостем" else "вошедшими"
    }

    if (anonymous) {
        Log.d { "[YouTube/Комментарий] Куки сеанса: ${WebAuth.sessionReport()}" }
    }

    /**
     * Кнопка есть, а метки в ней не нашлось — выкладываем кнопку целиком.
     *
     * Это тот случай, когда сервер нас узнал и писать даёт, а мы не поняли,
     * чем именно. Разбираться по имени ключа больше нечем, и следующий шаг
     * без этого вывода был бы очередной догадкой.
     */
    if (hasSubmit) {
        val lines = ArrayList<String>()

        dumpNode(box.opt("submitButton"), "submitButton", 8, lines)

        Log.d { "[YouTube/Комментарий] Кнопка отправки, ${lines.size} строк:" }

        for (line in lines) {
            Log.d { "[YouTube/Комментарий]   $line" }
        }
    }
}

/**
 * Метка по токену панели комментариев.
 *
 * Панель — это и есть то место, где поле ввода живёт: в странице ролика
 * его нет вовсе. Первый заход искал метку именно там, в ответе
 * на `{videoId}`, и не находил никогда — ошибка тем обиднее, что разведка
 * это показала заранее, а вывод в код не доехал.
 *
 * Метку токеном телевизора взять нельзя — проверено 24.08.2026. Пробовали
 * попросить панель **именем WEB**, а удостовериться токеном телевизора:
 * сервер отвечает 400, и не «мы вас не узнали», а отказом разбирать
 * запрос — пара «веб-клиент и токен телевизора» для него не запрос вовсе.
 * Заход убран, а не оставлен выключенным: он стоил четырёх запросов
 * на каждое открытие комментариев и вдобавок сажал общую локаль.
 */
fun Api.commentParamsForToken(token: String?): String? {
    if (token.isNullOrEmpty()) {
        return null
    }

    val panel = postNext(JSONObject().put("continuation", token))

    val params = createParamsIn(panel)

    Log.d { "[YouTube/Комментарий] Метка из панели: ${if (params != null) "есть" else "нет"}" }

    if (params == null) {
        reportComposerShapeIn(panel)
    }

    return params
}

/**
 * Токен панели комментариев в ответе страницы ролика.
 *
 * Тот же разбор, что в `videoDetails`: панель узнаётся по имени
 * `engagement-panel-comments-section`, у старых ответов —
 * `comment-item-section`.
 */
private fun Api.commentsTokenIn(json: Any?): String? {
    for (panel in Json.findAll("engagementPanelSectionListRenderer", json, 200000)) {
        val identifier = Json.string(panel, "panelIdentifier")
            ?: Json.string(panel, "targetId")

        if (identifier != "engagement-panel-comments-section" &&
            identifier != "comment-item-section"
        ) {
            continue
        }

        val command = Json.findFirst("continuationCommand", panel, 20000)

        Json.text(command, "token")?.let { return it }
    }

    return null
}

/**
 * Метка для ролика, когда токена панели под рукой нет.
 *
 * Заходов два, и оба обязательны: сперва страница ролика — за токеном
 * панели, затем сама панель — за меткой. Одним запросом не выйдет, поле
 * ввода в странице не лежит.
 *
 * null означает, что писать не дают вовсе.
 */
fun Api.commentParamsForVideo(videoId: String?): String? {
    if (videoId.isNullOrEmpty()) {
        return null
    }

    val page = postNext(JSONObject().put("videoId", videoId))

    val token = commentsTokenIn(page)

    if (token.isNullOrEmpty()) {
        Log.d { "[YouTube/Комментарий] Панели комментариев у ролика нет" }

        return null
    }

    return commentParamsForToken(token)
}

/**
 * Не отказ ли это, присланный под видом успеха.
 *
 * У InnerTube ответ на запись бывает удачным по коду и неудачным
 * по существу: приходит 200 с `actionResult`, где написано
 * `STATUS_FAILED`, либо с одним лишь `openPopupAction` — окном «войдите»
 * или «не получилось». Считать успехом всякий непустой ответ значило бы
 * показывать человеку «отправлено» там, где ничего не отправлено.
 *
 * Признаком успеха берётся то, что сервер присылает при удаче: либо прямо
 * `STATUS_SUCCEEDED`, либо готовая запись нового комментария, которую
 * клиент должен вставить в список.
 */
private fun commentAccepted(json: JSONObject?): Boolean {
    if (json == null) {
        return false
    }

    for (result in Json.findAll("actionResult", json, 20000)) {
        val status = Json.string(result, "status")

        if (status == "STATUS_SUCCEEDED") {
            return true
        }

        if (!status.isNullOrEmpty()) {
            Log.d { "[YouTube/Комментарий] Сервер о записи: $status" }

            return false
        }
    }

    // Ответ без `actionResult`: удачу выдаёт сама вставляемая запись.
    return Json.findFirst("commentEntityPayload", json, 20000) != null ||
        Json.findFirst("createCommentAction", json, 20000) != null
}

/**
 * Что сервер сказал словами, отказывая.
 *
 * У InnerTube отказ на запись почти всегда несёт готовую надпись
 * для человека — ту самую, что официальный клиент показал бы всплывающим
 * окном: «комментарии отключены», «войдите в аккаунт», «слишком часто».
 * Она куда точнее любой нашей догадки о причине, и её незачем
 * пересказывать своими словами — надо просто показать.
 *
 * Ищем по всему дереву: окно приезжает то `notificationTextRenderer`,
 * то `alertRenderer`, то диалогом с заголовком, и место у них разное.
 */
internal fun Api.refusalTextIn(json: JSONObject?): String? {
    if (json == null) {
        return null
    }

    val renderers = setOf(
        "notificationTextRenderer", "alertRenderer", "messageRenderer",
        "confirmDialogRenderer", "backstagePostDialogRenderer"
    )

    val fields = listOf(
        "successResponseText", "errorMessage", "text", "title",
        "message", "dialogMessage"
    )

    for (hit in Json.findAllOfAny(renderers, json, 20000)) {
        for (field in fields) {
            val said = Json.renderedText(hit.node, field)

            if (!said.isNullOrEmpty()) {
                return said
            }
        }
    }

    return null
}

/** Чем кончилась попытка записи: удача либо причина, названная сервером. */
class WriteResult(val accepted: Boolean, val reason: String?)

/**
 * Отправка комментария.
 *
 * [params] можно передать из [Api.comments] — тогда лишнего запроса
 * не будет; с null метка спрашивается сама. [commentsToken] позволяет
 * обойтись одним запросом вместо двух.
 */
fun Api.postComment(
    text: String,
    videoId: String,
    params: String?,
    commentsToken: String? = null
): WriteResult {
    if (text.isEmpty() || videoId.isEmpty()) {
        return WriteResult(false, null)
    }

    if (!Auth.isSignedIn() && !WebAuth.isSignedIn()) {
        Log.d { "[YouTube/Комментарий] Не вошли — писать нечем" }

        return WriteResult(false, loc("Не выполнен вход."))
    }

    /**
     * Метка: готовая, затем по токену панели, и лишь затем через ролик.
     *
     * Порядок — по числу запросов: ноль, один, два. Прежде здесь всегда
     * шёл путь «через ролик», и на живом устройстве это выливалось в три
     * запроса подряд за одной и той же меткой, потому что экран к тому
     * времени уже спрашивал её сам.
     */
    var create = params

    if (create.isNullOrEmpty() && !commentsToken.isNullOrEmpty()) {
        create = commentParamsForToken(commentsToken)
    }

    if (create.isNullOrEmpty() && commentsToken.isNullOrEmpty()) {
        create = commentParamsForVideo(videoId)
    }

    if (create.isNullOrEmpty()) {
        Log.d { "[YouTube/Комментарий] Метки нет — сервер писать не даёт" }

        return WriteResult(
            false,
            loc(
                "YouTube не дал разрешения на запись: " +
                    "у этого ролика комментарии могут быть отключены."
            )
        )
    }

    return sendComment(
        text, "comment/create_comment", "createCommentParams", create, videoId
    )
}

/**
 * Ответ на чужой комментарий.
 *
 * [params] — `replyParams` из записи, которую вернул [Api.comments].
 * Разговор с сервером тот же, что и у нового комментария, включая перебор
 * клиентов: узел другой, а правила те же.
 */
fun Api.replyComment(text: String, params: String?): WriteResult =
    sendComment(
        text, "comment/create_comment_reply", "createReplyParams", params, "ответ"
    )

/**
 * Правка своего комментария.
 *
 * [params] — `editParams` из записи. Метку эту сервер даёт только у своих
 * записей, поэтому её наличие и есть ответ на вопрос «моё ли это».
 */
fun Api.editComment(text: String, params: String?): WriteResult =
    sendComment(
        text, "comment/update_comment", "updateCommentParams", params, "правка"
    )

/**
 * Общая отправка: написать, ответить, поправить.
 *
 * Все три — один и тот же разговор с сервером и отличаются лишь узлом
 * и именем поля с меткой. Перебор клиентов, разбор успеха и разбор отказа
 * у них общие, и разводить их по трём почти одинаковым кускам значило бы
 * чинить потом каждый по отдельности.
 */
private fun Api.sendComment(
    text: String,
    endpoint: String,
    field: String,
    params: String?,
    note: String
): WriteResult {
    if (text.isEmpty() || params.isNullOrEmpty()) {
        return WriteResult(false, null)
    }

    val body = JSONObject()

    body.put("commentText", text)
    body.put(field, params)

    /**
     * TV-клиент идёт первым — и это тот самый опыт, ради которого всё.
     *
     * Откажет — за ним MWEB и WEB, которых подписывает браузерная сессия;
     * порядок тот же, что у оценки, и по той же причине.
     */
    val clients = listOf("TVHTML5", "MWEB", "WEB")

    var reason: String? = null

    for (client in clients) {
        val tv = client == "TVHTML5"

        // TV представляется токеном, веб-семейство — сессией браузера;
        // подпись сессией ставит сам `post`, когда `authorize` снят.
        if (tv && !Auth.isSignedIn()) continue
        if (!tv && !WebAuth.isSignedIn()) continue

        val json = post(endpoint, body, client, tv, 0.0)

        if (commentAccepted(json)) {
            Log.d { "[YouTube/Комментарий] $endpoint: принят к $note клиентом $client" }

            return WriteResult(true, null)
        }

        /**
         * Причину запоминаем от **последнего** говорившего.
         *
         * Первым идёт TV-клиент, и его отказ ожидаем — показывать человеку
         * именно его значило бы объяснять беду тем, что мы же и затеяли
         * ради опыта. Важно то, чем кончил последний, кому писать
         * полагается по-настоящему.
         */
        val said = refusalTextIn(json)

        if (!said.isNullOrEmpty()) {
            reason = said
        }

        Log.d {
            "[YouTube/Комментарий] $client отказал " +
                "(${if (json != null) "ответ без успеха" else "нет ответа"})" +
                if (said.isNullOrEmpty()) "" else ": $said"
        }

        /**
         * Отказ веб-клиенту — повод пересчитать куки сеанса.
         *
         * Одной `SAPISID` довольно, чтобы счесть вход состоявшимся
         * и собрать подпись, но серверу нужен весь набор: неполный он
         * встречает так же, как если бы входа не было вовсе. Снаружи это
         * неотличимо от «телевизору не дают», а различие решающее —
         * лечится оно повторным входом, а не другим клиентом.
         */
        if (!tv) {
            Log.d { "[YouTube/Комментарий] Куки сеанса: ${WebAuth.sessionReport()}" }
        }
    }

    return WriteResult(false, reason)
}
