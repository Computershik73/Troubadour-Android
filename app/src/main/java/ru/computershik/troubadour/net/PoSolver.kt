package ru.computershik.troubadour.net

import android.annotation.SuppressLint
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.ui.async
import ru.computershik.troubadour.ui.main
import ru.computershik.troubadour.ui.mainAfter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PO-токен — доказательство того, что запрос идёт от настоящего клиента.
 *
 * Порт `common/potoken.m` и `common-google/potoken-google.m` из TubeReplacer.
 *
 * Нужен затем, что `LOGIN_REQUIRED` от TV-клиента означает не «войдите
 * в аккаунт», а «докажите, что вы не подделка». Доказательство выдаёт
 * не сервер, а сам клиент: он получает от Google программу (BotGuard),
 * исполняет её у себя и отдаёт результат. Ни аккаунт, ни куки в этом
 * не участвуют — токен ортогонален входу и складывается с ним, а не
 * заменяет его.
 *
 * Программа исполняется в невидимом `WebView`. Страница-решатель — тот же
 * файл, что и в iOS-версии, вплоть до полифилов в начале: они дописывают
 * старому движку то, чего в нём нет, а движку Android 4.1 недостаёт ровно
 * того же, что движку iOS 7.
 *
 * Выдача долгая (секунды) и делается один раз: дальше токены чеканятся
 * мгновенно, по одному на ролик. Держится он ограниченное время — сервер
 * сам называет срок, и по нему заводится обновление.
 *
 * **Про разговор со страницей.** Записка о переносе советует взять
 * `addJavascriptInterface` вместо переходов по выдуманным адресам —
 * «то же самое, только прямо». Совет здесь не принят, и по двум причинам
 * сразу.
 *
 * Первая: до API 17 через этот мост страница дотягивается рефлексией
 * до чего угодно в приложении, а мы грузим в этот веб-вид **чужой код**,
 * присланный Google. Отдавать ему такой рычаг ради удобства неразумно,
 * а нижняя граница у нас 16.
 *
 * Вторая: `evaluateJavascript` с возвратом значения появился только
 * в API 19, и до него результат всё равно надо возвращать каким-то
 * обратным каналом. Раз канал нужен, пусть будет один на всё — тот же,
 * которым решатель уже разговаривает.
 */
object PoSolver {

    /**
     * Окно, в которое кладётся спрятанный решатель. Ставит `MainActivity`
     * при запуске — так же, как мини-плееру.
     */
    private var host: ViewGroup? = null

    fun attach(parent: ViewGroup) {
        host = parent

        /**
         * Уже заведённый вид переезжает в новое окно.
         *
         * Окно пересоздаётся при повороте и при возврате из фона, а вид
         * живёт весь запуск: без переезда он остался бы висеть в снятом
         * окне и снова лишился бы кадров.
         */
        main {
            val view = web ?: return@main

            (view.parent as? ViewGroup)?.removeView(view)

            view.alpha = 0.01f
            view.isEnabled = false

            parent.addView(view, ViewGroup.LayoutParams(2, 2))
        }
    }

    /**
     * Завести решателю **своё** окно, не спрашивая ничьей разметки.
     *
     * Нужно там, где никакого окна под рукой нет, — в службе, живущей
     * отдельным процессом. Окно берётся размером в две точки, прозрачное,
     * не берущее ни касаний, ни ввода: видеть его пользователю незачем,
     * а кадры решателю нужны, без них программа не идёт.
     *
     * Тип `TYPE_TOAST` выбран потому, что он единственный не требует
     * разрешения «поверх других окон». Пользоваться им для настоящих
     * окон запретили с Android 8, но отдельный процесс мы заводим только
     * на системах ниже неё — там, где он и нужен.
     */
    fun attachHere(context: android.content.Context) {
        main {
            val box = android.widget.FrameLayout(context)

            val params = android.view.WindowManager.LayoutParams(
                2, 2,
                android.view.WindowManager.LayoutParams.TYPE_TOAST,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT
            )

            params.gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT

            try {
                val windows = context.getSystemService(android.content.Context.WINDOW_SERVICE)
                    as android.view.WindowManager

                windows.addView(box, params)

                /**
                 * Дальше всё идёт обычным ходом: окно теперь есть, и
                 * `prepare` положит вид в него так же, как в нашем
                 * процессе кладёт в разметку `MainActivity`.
                 */
                host = box

                web?.let { view ->
                    (view.parent as? ViewGroup)?.removeView(view)

                    view.alpha = 0.01f
                    view.isEnabled = false

                    box.addView(view, ViewGroup.LayoutParams(2, 2))
                }

                Log.d { "[YouTube/PO] Решатель получил своё окно" }
            } catch (error: Throwable) {
                Log.d { "[YouTube/PO] Окна решателю не досталось: ${error.message}" }
            }
        }
    }

    /**
     * Ключ запроса задачи и ключ службы аттестации — постоянные, из
     * `potoken-google.m`. Не секретные: одинаковы у всех и лежат
     * в разметке YouTube.
     */
    private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
    private const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"

    /**
     * Страница-решатель разговаривает с нами переходами по служебным
     * адресам: вернуть значение из функции она не может — работа
     * асинхронная.
     *
     *     status://scriptsLoaded  — JavaScript страницы разобран;
     *     status://vmReady        — программа развернулась;
     *     status://poReady        — чеканщик готов;
     *     botguard-response://…   — ответ программы;
     *     po-token://…            — отчеканенный токен (наша добавка).
     *
     * Все они перехватываются в `shouldOverrideUrlLoading` и никуда
     * не ведут.
     */
    private const val STATUS_SCHEME = "status://"
    private const val RESPONSE_SCHEME = "botguard-response://"
    private const val TOKEN_SCHEME = "po-token://"

    private var web: WebView? = null

    /**
     * Страница-решатель целиком: она перезагружается с вложенным
     * исполнителем, так что нужна и после первой загрузки.
     */
    private var html: String = ""

    /**
     * Исполнитель уже вложен в страницу — второй раз задачу не просим.
     * Перезагруженная страница снова скажет `scriptsLoaded`, и без этого
     * признака подготовка пошла бы по кругу.
     */
    private var injected = false

    /** Сколько сообщений страницы уже взято в журнал. */
    private var said = 0

    /** Когда сторож главного потока отчитался в прошлый раз. */
    private var beat = 0L

    /** Куски задачи: сама программа, её имя в окне и код исполнителя. */
    private var program: String? = null
    private var globalName: String? = null
    private var safeScript: String? = null

    /** Токен целостности и срок, после которого его пора обновить. */
    private var integrityToken: String? = null
    private var renewAfter = 0L

    private var started = false

    @Volatile
    private var ready = false

    /** Докуда дошла подготовка — для сторожа и журнала. */
    @Volatile
    private var stage: String = ""

    /** Ожидание отчеканенного токена: сюда его положит перехватчик. */
    private var mintLatch: CountDownLatch? = null
    private var mintResult: String? = null

    fun isReady(): Boolean = ready

    // --- Шаг 1 — страница-решатель ----------------------------------------

    /**
     * Запускает подготовку, если она ещё не шла. Возвращается сразу: вся
     * работа идёт своим чередом, а готовность видно по [isReady].
     *
     * Звать можно сколько угодно — второй раз ничего не начнётся.
     */
    fun prepare() {
        synchronized(this) {
            /**
             * Второй заход начинается только тогда, когда токен целостности
             * состарился: подготовка стоит секунд, и повторять её на каждый
             * ролик незачем.
             */
            if (started && (renewAfter == 0L || renewAfter > System.currentTimeMillis())) {
                return
            }

            started = true
            ready = false
        }

        main { loadSolver() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadSolver() {
        injected = false
        said = 0

        html = try {
            App.require().assets.open("web/challenge_solver.html")
                .use { it.readBytes() }
                .toString(Charsets.UTF_8)
        } catch (error: Exception) {
            ""
        }

        if (html.isEmpty()) {
            Log.d { "[YouTube/PO] Решатель не прочитался" }

            return
        }

        if (web == null) {
            /**
             * Веб-вид обязан лежать в окне, и это не перестраховка.
             *
             * Считалось, что хватит самого объекта: JavaScript в WebView
             * идёт и без привязки к окну. Идёт, да не весь. Программа
             * проверки размечает работу по кадрам отрисовки, а кадров
             * у вида, не попавшего в окно, не бывает вовсе — их некому
             * рисовать. По журналу это выглядело так: «программа
             * запущена», и дальше тишина до самого сторожа, ровно
             * двадцать пять секунд.
             *
             * В оригинале вид тоже лежит в окне и тоже спрятан — рамкой
             * `-500,-500` за краем экрана. Здесь то же самое: два пикселя
             * в углу, прозрачные и не принимающие касаний.
             *
             * Контекст берём у окна, а не общий: без него `WebView`
             * на части устройств отказывается заводиться вовсе.
             */
            val parent = host

            val view = WebView(parent?.context ?: App.require())

            if (parent != null) {
                /**
                 * Прозрачность не полная нарочно: вид с `alpha = 0`
                 * система вправе не рисовать вовсе, а нам нужны как раз
                 * кадры. Сотая доля неотличима от нуля на глаз, а два
                 * пикселя в углу не видны и без неё.
                 */
                view.alpha = 0.01f
                view.isEnabled = false

                parent.addView(view, ViewGroup.LayoutParams(2, 2))
            } else {
                Log.d { "[YouTube/PO] Окна нет — решатель пойдёт без кадров" }
            }

            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true

            // Картинок на странице нет вовсе — незачем и заводить загрузчик.
            view.settings.loadsImagesAutomatically = false

            view.webViewClient = Client()

            /**
             * Читаем и консоль страницы.
             *
             * Решатель сообщает о своих бедах — «vm not found», «failed
             * to load program» — только в `console.log`, а канал
             * `status://` заводился лишь для успешных шагов. Оттого
             * подготовка и вставала беззвучно: в журнале «программа
             * запущена», и дальше двадцать пять секунд тишины до сторожа.
             */
            view.webChromeClient = object : android.webkit.WebChromeClient() {

                override fun onConsoleMessage(
                    message: android.webkit.ConsoleMessage
                ): Boolean {
                    /**
                     * Сообщений берём не больше счёта: страница чужая,
                     * и ошибиться в цикле она может запросто, а каждая
                     * строка отсюда — запись в файл на главном потоке.
                     * Без предела этого хватает, чтобы устройство встало.
                     */
                    if (said < SAY_LIMIT) {
                        said += 1

                        Log.d {
                            "[YouTube/PO] Страница: ${message.message()} " +
                                "(строка ${message.lineNumber()})"
                        }

                        if (said == SAY_LIMIT) {
                            Log.d { "[YouTube/PO] Дальше страница говорит в пустоту" }
                        }
                    }

                    return true
                }
            }

            web = view
        }

        Log.d { "[YouTube/PO] Открываем решатель (${html.length / 1024} КБ)" }

        watchMainThread()

        stage = "страница открыта"

        /**
         * Сторож подготовки.
         *
         * Вся она — разговор со страницей: та сама сообщает, что
         * загрузилась, что завела исполнителя, что готова чеканить.
         * Оборвись разговор на любом шаге — и в журнале просто не будет
         * следующей строки; понять, ждём мы сеть, страницу или уже ничего,
         * нельзя. Сторож говорит это вслух и позволяет начать заново
         * со следующего ролика.
         */
        mainAfter(25000) { checkProgress() }

        /**
         * Адрес основы обязателен: программа проверяет, откуда её
         * запустили, и с пустой основой отказывается работать.
         */
        web?.loadDataWithBaseURL(
            "https://www.youtube.com", html, "text/html", "UTF-8", null
        )
    }

    /** Сторож: подготовка либо кончилась, либо застряла — и мы скажем, где. */
    private fun checkProgress() {
        if (isReady()) {
            return
        }

        Log.d {
            "[YouTube/PO] Чеканщик не поднялся за 25 с, дошли до «" +
                stage.ifEmpty { "начала" } + "» — попробуем заново при следующем ролике"
        }

        /**
         * Снимаем пометку «подготовка началась», иначе следующий ролик
         * увидит её и молча уйдёт, а заканчивать её будет уже нечему.
         */
        synchronized(this) {
            started = false
        }
    }

    // --- Разговор со страницей --------------------------------------------

    private class Client : WebViewClient() {

        /**
         * Перегрузка со строкой, а не с `WebResourceRequest`.
         *
         * Вторая появилась в API 24, а первая объявлена устаревшей — но
         * зовётся система по-прежнему обе, и на всех версиях от 16 до
         * нынешней срабатывает именно эта. Держать две ради предупреждения
         * компилятора незачем.
         */
        @Suppress("OverridingDeprecatedMember", "DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
            handle(url)

        @Suppress("OverridingDeprecatedMember", "DEPRECATION")
        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            Log.d { "[YouTube/PO] Решатель не загрузился: $description" }
        }

        private fun handle(url: String?): Boolean {
            if (url == null) {
                return false
            }

            if (url.startsWith(STATUS_SCHEME)) {
                val status = decode(url.substring(STATUS_SCHEME.length))

                Log.d { "[YouTube/PO] Решатель: $status" }

                stage = status

                when (status) {
                    "scriptsLoaded" ->
                        /**
                         * Задачу спрашиваем в фоне: это сеть, а мы
                         * на главном потоке. Второй раз — не спрашиваем:
                         * страница с вложенным исполнителем скажет то же
                         * самое при своей загрузке.
                         */
                        if (!injected) {
                            async { requestChallenge() }
                        }

                    "vmReady" -> askBotguard()

                    "poReady" -> {
                        ready = true

                        Log.d { "[YouTube/PO] Готово: чеканщик поднят" }
                    }
                }

                return true
            }

            if (url.startsWith(RESPONSE_SCHEME)) {
                val answer = decode(url.substring(RESPONSE_SCHEME.length))

                Log.d { "[YouTube/PO] Ответ программы: ${answer.length} знаков" }

                async { requestIntegrityToken(answer) }

                return true
            }

            if (url.startsWith(TOKEN_SCHEME)) {
                deliverToken(decode(url.substring(TOKEN_SCHEME.length)))

                return true
            }

            return false
        }

        private fun decode(value: String): String = try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (error: Exception) {
            value
        }
    }

    // --- Шаг 2 — задача от Google -----------------------------------------

    /**
     * Общий ход к службе аттестации. `Create` выдаёт задачу, `GenerateIT` —
     * токен целостности; отличаются они только именем и телом.
     */
    private fun askAttestation(method: String, body: JSONObject): JSONObject? {
        val address = "https://www.youtube.com/api/jnn/v1/$method?noauth=1"

        /**
         * Три захода вместо одного.
         *
         * Несостоявшийся разговор — не отказ службы: не нашлось имя хоста,
         * не сложилось TLS, оборвалась связь. На неспешных и капризных
         * сетях такое случается именно в первые секунды после запуска,
         * когда мы сюда и приходим, — а цена промаха велика: без задачи
         * не будет ни токена целостности, ни PO-токена, и весь сеанс
         * пройдёт без них. Повтор через полторы секунды почти всегда
         * попадает.
         */
        for (attempt in 0 until 3) {
            val builder = Http.request(address) ?: return null

            builder.post(Http.jsonBody(Json.encode(body)))
            builder.header("content-type", "application/json")
            builder.header("x-goog-api-key", API_KEY)
            builder.header("x-user-agent", "grpc-web-javascript/0.1")

            // Служба общая для всех: сеанс аккаунта ей не нужен и только
            // мешает. Куки сюда не прикладываем — и не приложатся сами.
            val response = Http.send(builder.build(), 4 * 1024 * 1024, caching = false)

            if (response.isSuccessful) {
                return Json.parse(response.body)
            }

            Log.d {
                "[YouTube/PO] $method: код ${response.statusCode}" +
                    if (attempt < 2) " — пробуем снова" else ""
            }

            // Отказ службы повторять незачем: он придёт тот же.
            if (response.statusCode != 0) {
                return null
            }

            if (attempt < 2) {
                try {
                    Thread.sleep(1500)
                } catch (ignored: InterruptedException) {
                    return null
                }
            }
        }

        return null
    }

    private fun requestChallenge() {
        val json = askAttestation("Create", JSONObject().put("request_key", REQUEST_KEY))

        /**
         * Задача приходит перемешанной: это base64, у которого к каждому
         * байту прибавлено 97. Обратное действие даёт обычный JSON — порт
         * `descrambleChallenge`. Имя поля не закреплено, поэтому берём
         * единственную длинную строку в ответе.
         */
        var scrambled: String? = null

        if (json != null) {
            val keys = json.keys()

            while (keys.hasNext()) {
                val value = json.opt(keys.next())

                if (value is String && value.length > 100) {
                    scrambled = value

                    break
                }
            }
        }

        val parts = descramble(scrambled)

        if (parts == null || parts.length() < 6) {
            Log.d { "[YouTube/PO] Задача не разобралась — попробуем при следующем ролике" }

            /**
             * Снимаем пометку «подготовка началась».
             *
             * Без этого один неудачный заход при запуске означал сеанс
             * вовсе без PO-токена: [prepare] при каждом следующем ролике
             * видел начатую подготовку и молча уходил, а закончиться ей
             * было уже нечем.
             */
            synchronized(this) {
                started = false
            }

            return
        }

        /**
         * Раскладка та же, что в оригинале:
         *
         *     [1] — код исполнителя (список строк), он и заводит в окне
         *           объект, имя которого лежит в [5];
         *     [4] — сама программа;
         *     [5] — имя этого объекта.
         */
        var script: Any? = parts.opt(1)

        val list = script as? JSONArray

        if (list != null) {
            for (index in 0 until list.length()) {
                val piece = list.opt(index)

                if (piece is String && piece.isNotEmpty()) {
                    script = piece

                    break
                }
            }
        }

        synchronized(this) {
            safeScript = script as? String
            program = parts.opt(4) as? String
            globalName = parts.opt(5) as? String
        }

        if (safeScript.isNullOrEmpty() || program.isNullOrEmpty()) {
            Log.d { "[YouTube/PO] В задаче нет программы" }

            return
        }

        Log.d {
            "[YouTube/PO] Задача получена: программа ${program?.length} знаков, " +
                "исполнитель ${(safeScript?.length ?: 0) / 1024} КБ"
        }

        stage = "задача получена"

        main { startProgram() }
    }

    private fun descramble(scrambled: String?): JSONArray? {
        if (scrambled.isNullOrEmpty()) {
            return null
        }

        val raw = try {
            android.util.Base64.decode(scrambled, android.util.Base64.DEFAULT)
        } catch (error: Exception) {
            return null
        }

        if (raw.isEmpty()) {
            return null
        }

        val plain = ByteArray(raw.size)

        for (index in raw.indices) {
            plain[index] = (raw[index] + 97).toByte()
        }

        return Json.parseAny(String(plain, Charsets.UTF_8)) as? JSONArray
    }

    // --- Шаг 3 — запуск программы -----------------------------------------

    /**
     * Сперва в страницу вкладывается код исполнителя, и только потом
     * запускается программа: до этого объекта с нужным именем в окне нет,
     * и запуск отвечает «vm not found».
     */
    private fun startProgram() {
        val script: String?
        val code: String?
        val name: String?

        synchronized(this) {
            script = safeScript
            code = program
            name = globalName

            // Держать их дальше незачем: программа разворачивается один
            // раз, а весит она немало.
            safeScript = null
            program = null
        }

        if (script == null || code == null || name == null) {
            return
        }

        /**
         * Исполнитель вкладывается **в саму страницу**, а не скармливается
         * ей отдельно.
         *
         * Оба пути через `javascript:` на старых движках негодны. Без
         * кодирования содержимое читается как адрес: `%` начинает
         * шестнадцатеричную пару, `#` обрывает строку. С кодированием
         * выходит хуже: движок Android 4.1 percent-последовательности
         * **не раскодирует** вовсе, и страница честно отвечала
         * `Uncaught SyntaxError: Unexpected token %`.
         *
         * `evaluateJavascript` решает это с API 19, но ниже его нет.
         * Поэтому шестьдесят килобайт чужого кода не передаются никаким
         * каналом: страница перезагружается, уже неся их внутри себя.
         * Так работает везде одинаково, и разбирать нечего.
         */
        injected = true
        said = 0

        val page = before(
            before(
                before(html, "<!-- задача -->",
                    "<script>window.__taskInside = true;</script>\n"),
                "</body>", "<script>\n" + script + "\n</script>\n"
            ),
            "</html>",
            "<script>runBotguardChallenge(" +
                quote(code) + "," + quote(name) + ");</script>\n"
        )

        main {
            web?.loadDataWithBaseURL(
                "https://www.youtube.com", page, "text/html", "UTF-8", null
            )
        }

        Log.d { "[YouTube/PO] Программа вложена в страницу и запущена" }

        return
    }

    /** Программа развернулась — просим у неё ответ для службы аттестации. */
    private fun askBotguard() {
        run("(function(){try{createPOSignalOutput();}catch(e){}})()")

        Log.d { "[YouTube/PO] Ответ у программы спрошен" }
    }

    // --- Шаг 4 — токен целостности ----------------------------------------

    private fun requestIntegrityToken(botguardResponse: String) {
        val body = JSONObject()

        body.put("request_key", REQUEST_KEY)
        body.put("botguard_response", botguardResponse)

        val json = askAttestation("GenerateIT", body)

        val token = Json.text(json, "integrityToken")

        if (token.isNullOrEmpty()) {
            Log.d { "[YouTube/PO] Токена целостности не выдали" }

            return
        }

        /**
         * Срок сервер называет сам. Обновляем на восьмидесяти процентах —
         * так же, как `integrityTokenShouldProbablyRenew` в оригинале:
         * лучше обновиться заранее, чем посреди воспроизведения.
         */
        val ttl = Json.int(json, "estimatedTtlSecs", 3600)

        synchronized(this) {
            integrityToken = token
            renewAfter = System.currentTimeMillis() + (ttl * 800L)
        }

        Log.d { "[YouTube/PO] Токен целостности получен, годен $ttl с" }

        stage = "токен целостности получен"

        main {
            run("(function(){try{processIntegrityToken(${quote(token)});}catch(e){}})()")

            Log.d { "[YouTube/PO] Чеканщик поднимается" }
        }
    }

    // --- Шаг 5 — чеканка --------------------------------------------------

    /**
     * Токен для указанной привязки. null, если подготовка ещё
     * не закончилась либо не удалась.
     *
     * Привязка — то, с чем сервер сверит токен: у вошедшего признак
     * учётной записи, у гостя признак посетителя (см. `Api.sessionBinding`).
     * Идентификатором ролика привязывают только там, где сеанса нет вовсе.
     *
     * Чеканка идёт в веб-виде, а он живёт на главном потоке. Запросы
     * к `/player` идут из фона, поэтому переход обязателен — и ответа
     * мы дожидаемся: токен нужен прямо сейчас, а чеканка занимает
     * миллисекунды.
     */
    fun tokenFor(binding: String?): String? {
        if (!isReady() || binding.isNullOrEmpty()) {
            return null
        }

        val latch = CountDownLatch(1)

        synchronized(this) {
            mintLatch = latch
            mintResult = null
        }

        main {
            /**
             * Ответ возвращается тем же каналом, что и все прочие: страница
             * переходит по выдуманному адресу, мы его перехватываем.
             * `evaluateJavascript` с возвратом значения появился только
             * в API 19, а нижняя граница у нас 16.
             */
            run(
                "(function(){try{document.location='$TOKEN_SCHEME'+" +
                    "encodeURIComponent(mintPOToken(${quote(binding)})||'');}" +
                    "catch(e){document.location='$TOKEN_SCHEME';}})()"
            )
        }

        val delivered = try {
            latch.await(4, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            false
        }

        val token = synchronized(this) {
            mintLatch = null

            mintResult
        }

        if (!delivered || token.isNullOrEmpty()) {
            Log.d { "[YouTube/PO] Токен не отчеканился для $binding" }

            return null
        }

        return token
    }

    private fun deliverToken(value: String) {
        synchronized(this) {
            mintResult = value

            mintLatch?.countDown()
        }
    }

    /** Исполнить кусок JavaScript. Только с главного потока. */
    private fun run(script: String) {
        val view = web ?: return

        try {
            /**
             * Скрипт исполняется, а не открывается как адрес.
             *
             * Здесь стояло `loadUrl("javascript:" + script)` — и это
             * оказалось той самой причиной, по которой PO-токен не
             * получался ни разу. `javascript:` — это **адрес**, и
             * содержимое разбирается по правилам адреса: `%` читается
             * как начало шестнадцатеричной пары, а `#` обрывает строку,
             * потому что дальше по правилам идёт якорь. В шестидесяти
             * двух килобайтах ужатого JavaScript и то и другое есть
             * непременно — остаток исполнителя просто отсекался, имя
             * в окне не заводилось, и страница честно отвечала
             * «программы нет в окне под именем trayride».
             *
             * С API 19 есть `evaluateJavascript`, где строка — это код,
             * а не адрес. Ниже — тот же адрес, но код едет в нём
             * основанием 64.
             *
             * Percent-кодирование тут не годится: движок Android 4.1
             * ничего обратно не раскодирует, и страница получает сырые
             * `%7B` — «Uncaught SyntaxError: Unexpected token %».
             * Сырая же строка ломается о `#`: по правилам адреса дальше
             * идёт якорь, и остаток кода просто отсекается.
             *
             * Основание 64 в url-безопасном виде состоит из букв, цифр,
             * `-`, `_` и `=` — ни одного знака, о который адресу есть
             * обо что споткнуться. Раскодирует его сама страница;
             * `escape`/`decodeURIComponent` вокруг `atob` нужны затем,
             * что `atob` отдаёт байты, а нам нужны буквы в UTF-8.
             */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                view.evaluateJavascript(script, null)
            } else {
                val payload = android.util.Base64.encodeToString(
                    script.toByteArray(Charsets.UTF_8),
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )

                view.loadUrl(
                    "javascript:(function(p){try{eval(decodeURIComponent(escape(" +
                        "atob(p.replace(/-/g,\"+\").replace(/_/g,\"/\"))" +
                        ")))}catch(e){}})(\"" + payload + "\")"
                )
            }
        } catch (error: Throwable) {
            Log.d { "[YouTube/PO] Не исполнилось: ${error.message}" }
        }
    }

    /** Строка для подстановки в JavaScript — с полным экранированием. */
    private fun quote(value: String): String = JSONObject.quote(value)

    /**
     * Вставляет кусок перед **последним** вхождением метки.
     *
     * Замена всех вхождений тут негодна, и это стоило долгого поиска.
     * Среди заплат страницы есть строка `r.write("<head></head><body>`
     * — закрывающая метка внутри строкового литерала. Замена по всему
     * тексту попадала и туда: шестьдесят килобайт с переводами строк
     * оказывались посреди литерала, и блок переставал разбираться
     * целиком — вместе с `Symbol` и самим `runBotguardChallenge`.
     * В журнале это выглядело как три несвязанные ошибки в чужом коде.
     */
    private fun before(text: String, mark: String, piece: String): String {
        val at = text.lastIndexOf(mark)
        if (at < 0) return text + piece
        return text.substring(0, at) + piece + text.substring(at)
    }


    /**
     * Сторож потока, в котором идёт подготовка.
     *
     * WebView считает программу BotGuard в главном потоке своего
     * процесса, и заминка там — не догадка, а измеримая величина. Сторож
     * просыпается каждые полсекунды и, если его разбудили сильно позже
     * срока, пишет насколько.
     *
     * С тех пор как решатель живёт отдельным процессом, эта заминка
     * до рисования не достаёт — но знать её всё равно полезно: по ней
     * видно, тяжело ли устройству даётся сама программа.
     */
    private fun watchMainThread() {
        beat = android.os.SystemClock.uptimeMillis()

        val tick = object : Runnable {
            override fun run() {
                val now = android.os.SystemClock.uptimeMillis()
                val late = now - beat - BEAT

                beat = now

                if (late > BEAT) {
                    Log.d { "[YouTube/PO] Поток решателя стоял $late мс" }
                }

                if (!ready) {
                    mainAfter(BEAT) { run() }
                }
            }
        }

        mainAfter(BEAT) { tick.run() }
    }

/** Предел сообщений от страницы на одну загрузку. */
private const val SAY_LIMIT = 40

/** Как часто сторож проверяет главный поток. */
private const val BEAT = 500L
}
