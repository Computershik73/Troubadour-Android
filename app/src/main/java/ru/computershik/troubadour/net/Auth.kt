package ru.computershik.troubadour.net

import android.content.Context
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.loc
import java.util.UUID

/**
 * Вход в аккаунт Google по «коду устройства».
 *
 * Порт `Login.xaml.cs` вместе с `RefreshAccessTokenAsync` из Config.cs.
 * Способ тот же, что у телевизоров: приложение просит у YouTube короткий
 * код, показывает его человеку, тот вводит код на youtube.com/activate
 * с любого другого устройства, а мы тем временем спрашиваем сервер,
 * не подтвердили ли уже.
 *
 * В iOS-версии он был выбран не от хорошей жизни: обычный вход требует
 * встроенного браузера, а `UIWebView` на iOS 5 нынешнюю страницу входа
 * Google не осилит. Здесь такого ограничения нет — и обычный вход тоже
 * есть, он живёт в [WebAuth] и нужен ради комментариев. Но код устройства
 * остаётся основным путём: он проще, не требует ввода пароля в чужом окне
 * и работает на приставке, где клавиатуры нет вовсе.
 *
 * Учётные данные клиента — те же, что в UWP-версии: это открытые
 * идентификаторы клиента «YouTube on TV», они одинаковы у всех и никакой
 * тайны не составляют.
 *
 * Что хранится между запусками — только `refresh_token`. Токен доступа
 * живёт час и всякий раз выменивается заново; держать его на диске незачем.
 */
object Auth {

    /**
     * Учётные данные клиента «YouTube on TV».
     *
     * Взяты из Config.cs UWP-версии без изменений. Это не секрет:
     * у публичных клиентов OAuth «секрет» существует только формально,
     * он одинаков у всех установок и лежит в открытом виде в любом таком
     * приложении.
     */
    private const val CLIENT_ID =
        "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
    private const val CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"

    /** Те же, что в Login.xaml.cs. */
    private const val DEVICE_SCOPE =
        "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
    private const val DEVICE_MODEL = "ytlr:samsung:smarttv"
    private const val TV_USER_AGENT = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0)"

    private const val STORE = "troubadour"
    private const val REFRESH_TOKEN_KEY = "yt_refresh_token"

    @Volatile
    private var refreshTokenValue: String? = null

    /** Токен доступа и когда он протухнет. На диск не кладётся. */
    private var accessTokenValue: String? = null
    private var accessTokenExpires = 0L

    /** Состояние идущего входа по коду. */
    private var deviceCode: String? = null

    @Volatile
    var userCode: String? = null
        private set

    /** Рекомендованная сервером пауза между опросами, секунды. */
    @Volatile
    var pollInterval: Double = 5.0
        private set

    private val store
        get() = App.require().getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** Поднимает сохранённый вход. Зовётся при запуске процесса. */
    fun restore() {
        refreshTokenValue = store.getString(REFRESH_TOKEN_KEY, null)

        Log.d {
            "[YouTube/Вход] " + if (!refreshTokenValue.isNullOrEmpty()) {
                "Сохранённый вход поднят"
            } else {
                loc("Входа нет")
            }
        }
    }

    fun isSignedIn(): Boolean = !refreshTokenValue.isNullOrEmpty()

    /** Долгоживущий токен; пусто, если не вошли. */
    fun refreshToken(): String = refreshTokenValue ?: ""

    private fun storeToken(token: String?) {
        refreshTokenValue = token

        val editor = store.edit()

        if (!token.isNullOrEmpty()) {
            editor.putString(REFRESH_TOKEN_KEY, token)
        } else {
            editor.remove(REFRESH_TOKEN_KEY)
        }

        editor.apply()

        synchronized(this) {
            accessTokenValue = null
            accessTokenExpires = 0
        }

        /**
         * Кратковременный кеш ответов сбрасывается вместе со входом:
         * витрина и ленты у вошедшего и невошедшего разные, а ключом там
         * один адрес — первые минуты после входа приходили бы прежние,
         * гостевые ответы.
         */
        Http.dropMemoryCache()

        Notify.post(Notify.ACCOUNT)
    }

    /** Забыть вход. */
    fun signOut() {
        Log.d { "[YouTube/Вход] Выход из аккаунта" }

        storeToken(null)
    }

    // --- Токен доступа ----------------------------------------------------

    /**
     * Токен доступа для заголовка `Authorization: Bearer`.
     *
     * Синхронный: зовётся из фоновых потоков API, каждый из которых и так
     * ждёт ответа сети. Результат держится в памяти до истечения срока,
     * а сам обмен закрыт замком — иначе десяток экранов, начавших грузиться
     * разом, отправили бы десяток одинаковых запросов
     * к oauth2.googleapis.com.
     */
    fun accessToken(): String {
        if (!isSignedIn()) {
            return ""
        }

        synchronized(this) {
            val ready = accessTokenValue

            if (ready != null && System.currentTimeMillis() < accessTokenExpires) {
                return ready
            }
        }

        /**
         * Замок на весь обмен, а не только на чтение поля.
         *
         * Если несколько экранов начинают грузиться разом, в сеть должен
         * пойти только первый: остальные подождут его и возьмут готовое.
         * Без этого при запуске уходило бы полдюжины одинаковых запросов,
         * и каждый следующий обесценивал бы предыдущий.
         */
        synchronized(this) {
            val ready = accessTokenValue

            if (ready != null && System.currentTimeMillis() < accessTokenExpires) {
                return ready
            }

            val body = "client_id=${Http.encodeParameter(CLIENT_ID)}" +
                "&client_secret=${Http.encodeParameter(CLIENT_SECRET)}" +
                "&refresh_token=${Http.encodeParameter(refreshTokenValue)}" +
                "&grant_type=refresh_token"

            val json = postForm("https://oauth2.googleapis.com/token", body)

            val token = Json.text(json, "access_token")

            if (token.isNullOrEmpty()) {
                val error = Json.string(json, "error", "нет ответа") ?: "нет ответа"

                Log.d { "[YouTube/Вход] Токен доступа не обновлён: $error" }

                /**
                 * `invalid_grant` — это не временная беда: долгоживущий
                 * токен отозван (сменили пароль, отобрали доступ
                 * приложению). Держать его дальше незачем, иначе каждый
                 * запрос будет ходить в сеть за заведомым отказом.
                 * Остальные ошибки — сетевые, и вход при них остаётся.
                 */
                if (error == "invalid_grant") {
                    Log.d { "[YouTube/Вход] Долгоживущий токен отозван — выходим" }

                    storeToken(null)
                }

                return ""
            }

            // Срок берём с запасом в минуту: между обменом и тем запросом,
            // ради которого он затевался, проходит время.
            val lifetime = Json.double(json, "expires_in", 3600.0)

            accessTokenValue = token
            accessTokenExpires = System.currentTimeMillis() + ((lifetime - 60) * 1000).toLong()

            return token
        }
    }

    // --- Вход по коду -----------------------------------------------------

    /**
     * Просит у YouTube код устройства. Блокирующий — звать с фоновой
     * очереди. Возвращает код для показа или null, если не вышло.
     */
    fun beginDeviceFlow(): String? {
        /**
         * `device_id` — случайный на каждую попытку. Он ни к чему
         * не привязан и служит только тем, чтобы сервер отличал
         * параллельные входы; UWP-версия кладёт туда свежий GUID.
         */
        val deviceId = UUID.randomUUID().toString()

        val body = "client_id=${Http.encodeParameter(CLIENT_ID)}" +
            "&scope=${Http.encodeParameter(DEVICE_SCOPE)}" +
            "&device_id=${Http.encodeParameter(deviceId)}" +
            "&device_model=${Http.encodeParameter(DEVICE_MODEL)}"

        val json = postForm("https://www.youtube.com/o/oauth2/device/code", body)

        deviceCode = Json.text(json, "device_code")
        userCode = Json.text(json, "user_code")
        pollInterval = Json.double(json, "interval", 5.0)

        if (deviceCode.isNullOrEmpty()) {
            Log.d { "[YouTube/Вход] Код устройства не получен" }

            return null
        }

        Log.d {
            "[YouTube/Вход] Код устройства получен, опрашивать раз " +
                "в ${pollInterval.toInt()} с"
        }

        return userCode
    }

    /**
     * Спрашивает, подтвердил ли человек код.
     *
     * Возвращает:
     *   1  — вошли, токен сохранён;
     *   0  — ещё нет, спросить снова через несколько секунд;
     *  -1  — отказ (код просрочен либо отклонён), начинать заново.
     *
     * Разделение именно такое, потому что «ещё нет» — это штатный ответ,
     * а не ошибка: сервер отвечает `authorization_pending` на каждый опрос,
     * пока код не введён.
     */
    fun pollDeviceFlow(): Int {
        val code = deviceCode

        if (code.isNullOrEmpty()) {
            return -1
        }

        val body = "client_id=${Http.encodeParameter(CLIENT_ID)}" +
            "&client_secret=${Http.encodeParameter(CLIENT_SECRET)}" +
            "&code=${Http.encodeParameter(code)}" +
            "&grant_type=${Http.encodeParameter("http://oauth.net/grant_type/device/1.0")}"

        val json = postForm("https://www.youtube.com/o/oauth2/token", body)

        val refresh = Json.text(json, "refresh_token")

        if (!refresh.isNullOrEmpty()) {
            Log.d { "[YouTube/Вход] Код подтверждён, вход выполнен" }

            deviceCode = null
            userCode = null

            storeToken(refresh)

            return 1
        }

        val error = Json.string(json, "error", "") ?: ""

        // Штатный ответ, пока код не введён, — не ошибка.
        if (error == "authorization_pending") {
            return 0
        }

        // Просят опрашивать реже — послушаемся, иначе сервер начнёт отказывать.
        if (error == "slow_down") {
            pollInterval += 5

            return 0
        }

        Log.d { "[YouTube/Вход] Вход не удался: ${error.ifEmpty { "нет ответа" }}" }

        deviceCode = null

        return -1
    }

    // --- Общее ------------------------------------------------------------

    /** POST с телом `application/x-www-form-urlencoded`. */
    private fun postForm(url: String, body: String): org.json.JSONObject? {
        val builder = Http.request(url) ?: return null

        builder.post(Http.formBody(body))
        builder.header("Content-Type", "application/x-www-form-urlencoded")
        builder.header("User-Agent", TV_USER_AGENT)

        // Кеш обходим намеренно: ответы про токены не кешируются никогда,
        // а на опрос кода это дало бы «ещё нет» вместо подтверждения.
        val response = Http.send(builder.build(), 256 * 1024, caching = false)

        // Разбираем тело и при отказе тоже: причина лежит в поле `error`,
        // а код ответа при `authorization_pending` — 428 или 400.
        return Json.parse(response.body)
    }
}
