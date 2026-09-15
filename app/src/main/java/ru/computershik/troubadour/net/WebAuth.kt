package ru.computershik.troubadour.net

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.CookieSyncManager
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Notify
import java.security.MessageDigest

/**
 * Вход настоящим сеансом браузера — порт из SimpMusicLumia.
 *
 * Это второй, независимый от OAuth способ представиться. Разница между
 * ними принципиальная:
 *
 *   OAuth ([Auth])  — вход по коду устройства. Токен выдан TV-клиенту,
 *                     им подписываются подписки, история и аккаунт;
 *   веб-сессия      — обычные куки Google, какие получает браузер после
 *                     входа на `accounts.google.com`. Ими подписывается
 *                     всё, что просит WEB-клиент, — и, главное, `/player`.
 *
 * Ради последнего всё и затеяно. Стена «подтвердите, что вы не бот» встаёт
 * перед клиентами, у которых нет сеанса: ANDROID_VR ходит с одним
 * `visitorData`, и для Google это запрос ниоткуда. Настоящая веб-сессия
 * снимает вопрос — с ней запрос неотличим от браузера, в котором человек
 * вошёл в аккаунт. Комментарии без неё тоже не пишутся: метку записи
 * кладут только в ответ подписанного веб-клиента.
 *
 * Подпись — `SAPISIDHASH`, придуманная Google для своих же страниц:
 * SHA-1 от строки «время SAPISID origin». Смысл в том, что сама `SAPISID`
 * в заголовок не уходит — уходит её отпечаток, привязанный ко времени
 * и к тому, кто спрашивает.
 *
 * **Здесь одно существенное отличие от iOS-версии.** Там `UIWebView`
 * и `NSURLConnection` делили одно хранилище кук, и всё, что человек
 * получил при входе, само уходило с запросами на youtube.com — своей
 * заботой оставалась только подпись. Это же выходило боком: куки
 * прикладывались и к запросам ANDROID_VR, клиента заведомо анонимного,
 * и Google встречал их как «наполовину вошедшие».
 *
 * У WebView и OkHttp хранилища разные, и это к лучшему: куки надо
 * приложить руками — и ровно туда, куда нужно. Никакого
 * `setHTTPShouldHandleCookies:NO` расставлять не приходится.
 */
object WebAuth {

    /** Под каким именем лежит отложенная веб-сессия. */
    private const val STORE = "troubadour"
    private const val SESSION_KEY = "YTWebSessionCookies"

    /**
     * Имена куки, по которым узнаётся вход, — в том же порядке,
     * что в `BuildSapisidAuthorization` оригинала. Первая найденная и идёт
     * в подпись.
     */
    private val SAPISID_NAMES = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

    /**
     * Имена кук, по которым Google узнаёт вошедшего.
     *
     * `SAPISID` и её собратья идут в подпись, `SID` с `HSID` и `SSID` —
     * сам сеанс, `LOGIN_INFO` — то, чем YouTube отличает вошедшего
     * от гостя. Не хватает хоть одной существенной — и сервер встречает
     * нас проверкой «вы не бот», хотя вход как будто состоялся.
     */
    private val SESSION_NAMES = listOf(
        "SID", "HSID", "SSID", "APISID", "SAPISID",
        "__Secure-1PSID", "__Secure-3PSID",
        "__Secure-1PAPISID", "__Secure-3PAPISID",
        "LOGIN_INFO"
    )

    private const val YOUTUBE = "https://www.youtube.com"
    private const val GOOGLE = "https://google.com"

    private val store
        get() = App.require().getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /**
     * Менеджер кук просыпается не сам: на старых системах его надо завести
     * через `CookieSyncManager`, иначе первое же обращение вернёт пустоту.
     */
    private fun manager(): CookieManager? {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                @Suppress("DEPRECATION")
                CookieSyncManager.createInstance(App.require())
            }

            CookieManager.getInstance()
        } catch (error: Throwable) {
            // Веб-вида в системе может не быть вовсе — на приставках
            // без сервисов Google это встречается.
            Log.d { "[YouTube/Вход] Хранилище кук недоступно: ${error.message}" }

            null
        }
    }

    /** Все куки Google и YouTube: имя → значение. */
    private fun cookies(): Map<String, String> {
        val manager = manager() ?: return emptyMap()

        val result = LinkedHashMap<String, String>()

        for (url in listOf(YOUTUBE, GOOGLE)) {
            val line = try {
                manager.getCookie(url)
            } catch (error: Throwable) {
                null
            } ?: continue

            for (piece in line.split(";")) {
                val trimmed = piece.trim()
                val equals = trimmed.indexOf('=')

                if (equals <= 0) {
                    continue
                }

                val name = trimmed.substring(0, equals)
                val value = trimmed.substring(equals + 1)

                /**
                 * Куки youtube.com главнее: одноимённые живут и там,
                 * и на google.com, а нужна та, что уйдёт с запросом
                 * к youtube.com — иначе подпись будет от чужого сеанса,
                 * и сервер её отвергнет. Первым читаем youtube.com,
                 * поэтому не перезаписываем уже найденное.
                 */
                if (value.isNotEmpty() && !result.containsKey(name)) {
                    result[name] = value
                }
            }
        }

        return result
    }

    private fun sapisid(): String? {
        val all = cookies()

        for (name in SAPISID_NAMES) {
            all[name]?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        return null
    }

    /** Есть ли настоящая веб-сессия: в хранилище лежит SAPISID. */
    fun isSignedIn(): Boolean = !sapisid().isNullOrEmpty()

    /**
     * Значение заголовка `Authorization` для запроса к этому источнику.
     * null, если сессии нет.
     *
     * [origin] — тот, что уйдёт в одноимённом заголовке: подпись считается
     * вместе с ним, и несовпадение делает её недействительной.
     */
    fun authorizationForOrigin(origin: String): String? {
        val sapisid = sapisid()

        if (sapisid.isNullOrEmpty()) {
            return null
        }

        val seconds = System.currentTimeMillis() / 1000

        val source = "$seconds $sapisid $origin"

        /**
         * SHA-1 из системы: он есть с первых версий, и тащить ради одного
         * отпечатка что-то ещё незачем. В iOS-версии для этого звался
         * CommonCrypto — здесь `MessageDigest`, разницы никакой.
         */
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(source.toByteArray(Charsets.UTF_8))

        val hex = StringBuilder(digest.size * 2)

        for (byte in digest) {
            hex.append(String.format("%02x", byte))
        }

        return "SAPISIDHASH ${seconds}_$hex"
    }

    /**
     * Готовая строка заголовка `Cookie` для запроса к этому адресу.
     *
     * Того, что в iOS-версии делалось само, здесь приходится делать руками:
     * у OkHttp своего хранилища кук нет. Зато и прикладываются они ровно
     * туда, куда надо, — к подписанным запросам веб-клиента и никуда больше.
     */
    fun cookieHeader(url: String): String? {
        val manager = manager() ?: return null

        return try {
            manager.getCookie(url)?.takeIf { it.isNotEmpty() }
        } catch (error: Throwable) {
            null
        }
    }

    /** Адрес страницы входа Google — с возвратом на youtube.com. */
    fun loginUrl(): String =
        "https://accounts.google.com/ServiceLogin?service=youtube" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2F"

    /** Убирает куки Google и YouTube: выход из веб-сессии. */
    fun signOut() {
        val manager = manager()

        if (manager != null) {
            try {
                /**
                 * Точечно удалить куки одного домена штатным способом
                 * нельзя ни на одной версии: `CookieManager` умеет либо
                 * стереть всё, либо перезаписать конкретную куку пустым
                 * значением с истёкшим сроком. Идём вторым путём — иначе
                 * заодно улетели бы куки, положенные страницей проверки
                 * «вы не робот», а её проходят отдельно и надолго.
                 */
                for (name in cookies().keys) {
                    for (host in listOf(".youtube.com", ".google.com", "youtube.com")) {
                        manager.setCookie(
                            "https://$host",
                            "$name=; Max-Age=0; Path=/; Domain=$host"
                        )
                    }
                }

                flush()
            } catch (error: Throwable) {
                Log.d { "[YouTube/Вход] Куки не стёрлись: ${error.message}" }
            }
        }

        store.edit().remove(SESSION_KEY).apply()

        Notify.post(Notify.ACCOUNT)
    }

    /**
     * Каких кук сеанса недостаёт — строкой для журнала.
     *
     * Одной `SAPISID` довольно, чтобы счесть вход состоявшимся и собрать
     * подпись, но серверу нужен весь набор. Неполный он встречает проверкой
     * «вы не бот» — с виду так же, как если бы входа не было вовсе,
     * и различить эти два случая можно только заглянув в хранилище.
     */
    fun sessionReport(): String {
        val have = cookies().keys

        val missing = SESSION_NAMES.filter { !have.contains(it) }

        if (missing.isEmpty()) {
            return "все нужные куки на месте"
        }

        return "не хватает: ${missing.joinToString(", ")}"
    }

    // --- Запас на следующий запуск ----------------------------------------

    /**
     * Откладывает куки сеанса про запас — и достаёт их обратно при запуске.
     *
     * На iOS хранилище кук принадлежало веб-виду лишь наполовину:
     * положенное им жило, пока приложение работает, а до следующего запуска
     * доходило не всегда. Со стороны это выглядело так, будто вход
     * в браузере «слетает».
     *
     * На Android `CookieManager` пишет своё хранилище на диск сам, и в
     * запасе строгой нужды нет. Он всё же оставлен, и по двум причинам:
     * запись отложенная (без `flush` куки теряются при снятии процесса),
     * а на части прошивок без сервисов Google веб-вид подменён и своё
     * хранилище не бережёт вовсе.
     */
    fun keepSession() {
        val all = cookies()

        if (all.isEmpty()) {
            return
        }

        flush()

        /**
         * Кладём парами «имя=значение» через перевод строки. Свойств куки
         * (срок, домен, флаги) здесь нет — при возврате они и не нужны:
         * куку мы ставим сами и сами задаём ей домен.
         */
        val text = all.entries.joinToString("\n") { "${it.key}=${it.value}" }

        store.edit().putString(SESSION_KEY, text).apply()

        Log.d {
            "[YouTube/Вход] Веб-сессия отложена: кук ${all.size}, ${sessionReport()}"
        }
    }

    fun restoreSession() {
        // Уже есть — значит, хранилище донесло само, и мешать ему незачем.
        if (isSignedIn()) {
            return
        }

        val text = store.getString(SESSION_KEY, null)

        if (text.isNullOrEmpty()) {
            return
        }

        val manager = manager() ?: return

        var back = 0

        try {
            manager.setAcceptCookie(true)

            for (line in text.split("\n")) {
                if (line.isEmpty()) {
                    continue
                }

                /**
                 * Домен `.youtube.com` с точкой — чтобы кука уходила
                 * и на `www.`, и на `m.`. Срок не ставим: без него кука
                 * сеансовая, а сеанс здесь — запуск приложения, после
                 * которого мы её отложим заново.
                 */
                manager.setCookie("https://www.youtube.com", "$line; Path=/; Domain=.youtube.com")
                manager.setCookie("https://google.com", "$line; Path=/; Domain=.google.com")

                back++
            }

            flush()
        } catch (error: Throwable) {
            Log.d { "[YouTube/Вход] Веб-сессия не поднялась: ${error.message}" }

            return
        }

        Log.d {
            "[YouTube/Вход] Веб-сессия поднята из запаса: кук $back, " +
                "вход ${if (isSignedIn()) "есть" else "не собрался"}, ${sessionReport()}"
        }

        if (isSignedIn()) {
            Notify.post(Notify.ACCOUNT)
        }
    }

    /** Сбрасывает хранилище кук на диск: с API 21 — `flush`, раньше — `sync`. */
    fun flush() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().flush()
            } else {
                @Suppress("DEPRECATION")
                CookieSyncManager.getInstance().sync()
            }
        } catch (error: Throwable) {
            // Не сбросилось — не беда: запас лежит и в своих настройках.
        }
    }
}
