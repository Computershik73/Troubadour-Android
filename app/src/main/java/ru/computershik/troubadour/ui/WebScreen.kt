package ru.computershik.troubadour.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.net.WebAuth
import ru.computershik.troubadour.ui.Metrics.dp

/**
 * Встроенный браузер: вход в аккаунт и проверка «вы не робот».
 *
 * Порт `YTChallengeView`. Экран один на две задачи, потому что задача
 * у него по сути одна: показать настоящую страницу Google и забрать
 * то, что после неё останется в куках.
 *
 * **Про движок.** Соблазн сказать «на Android веб-вид современный»
 * велик и неверен. На 4.1 это WebKit 534.30 — ровесник, а то и предок
 * `UIWebView` из iOS 7 (537.51), с которым оригинал и мучился. Значит,
 * и мучения те же, и лекарства те же: имя браузера подменяется, а выбор
 * имени — про равновесие между «слишком старый» и «слишком новый».
 *
 * Развилка проходит там же, где в оригинале. Там нынешний веб-вид
 * (`WKWebView`) брался с iOS 11 — не раньше, потому что до неё
 * не было доступа к его хранилищу кук, а без него вход виден на экране,
 * но не в запросах. Здесь ровно то же условие: с API 21 веб-вид
 * отдельный, обновляемый через магазин, и `CookieManager.flush`
 * появился тогда же. До 21-й — старый движок и подменённые имена.
 */
class WebScreen(
    context: Context,
    private val login: Boolean,
    private val videoId: String? = null,
    private val onDone: (() -> Unit)? = null
) : Screen(context) {

    companion object {

        /**
         * Кем представляется встроенный браузер на проверке.
         *
         * Со своим настоящим именем — WebKit 534.30 от Android 4.1 —
         * Google встречает страницей «обновите браузер» вместо проверки,
         * и решать становится нечего. Поэтому имя подменяется.
         *
         * Выбор — про равновесие, и оба края плохи. Назовёшься слишком
         * свежим (нынешний Chrome) — придёт нынешняя сборка страницы,
         * которую движок 4.1 не выполнит вовсе. Останешься собой —
         * не пустят на порог. Отсюда середина: Safari из iOS 12. Он новее
         * порога, за которым Google считает браузер устаревшим, и при этом
         * его сборка страницы — эпохи ES6, с которой старый WebKit
         * хоть как-то справляется.
         *
         * Строка настоящая, не сочинённая: такую шлёт Safari на iPhone
         * с iOS 12.5.7. Она же стоит и в оригинале — менять её на
         * андроидную незачем: важно не то, чем мы притворяемся, а какую
         * сборку страницы за это имя отдадут.
         */
        const val CHALLENGE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 12_5_7 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/12.1.2 " +
                "Mobile/15E148 Safari/604.1"

        /**
         * Имя для страницы входа — Opera Mini.
         *
         * Расчёт обратный тому, что у проверки. Нынешняя страница входа
         * (`GlifWebSignIn`) — целиком приложение на JavaScript: формы там
         * нет вовсе, поля рисованные, отправка идёт запросом изнутри.
         * Старому движку такое не по силам, и человек видит пустой экран.
         *
         * Зато у Google до сих пор жива простая разметка для браузеров,
         * которым JavaScript не по силам, — с настоящим `<form>`
         * и обычной отправкой. Отдаётся она по имени браузера, и Opera
         * Mini на Symbian — как раз тот случай: дальше некуда.
         *
         * Под этим же именем проверка отвечает прямым отказом («Чтобы
         * увидеть reCAPTCHA, перейдите на поддерживаемый браузер») —
         * потому у проверки имя своё, а не это.
         */
        const val LOGIN_USER_AGENT =
            "Opera/9.80 (J2ME/MIDP; Opera Mini/5.0 (SymbianOS/24.838; U; en) " +
                "Presto/2.5.25 Version/10.54"

        /**
         * Есть ли на этой системе нынешний веб-вид **вместе** с доступом
         * к его кукам.
         *
         * Одного движка мало: без хранилища куки остались бы у него
         * внутри, и вход, пройденный на глазах, для наших запросов
         * не случился бы. `CookieManager.flush` появился в API 21 —
         * тогда же, когда веб-вид стал отдельным обновляемым.
         */
        fun modernWebAvailable(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
    }

    private lateinit var header: ScreenHeader
    private lateinit var web: WebView
    private lateinit var busy: LoadingRing

    /** Сколько кук уже перенесли — чтобы не писать в журнал одно и то же. */
    private var movedCookies = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun build(root: FrameLayout) {
        val column = LinearLayout(context)

        column.orientation = LinearLayout.VERTICAL

        header = ScreenHeader(
            context, if (login) loc("Вход в браузере") else loc("Проверка")
        )

        column.addView(header)

        val hint = label(context, Fonts.regular, 13f, Theme.secondaryText, 0)

        hint.text = if (login) {
            loc("Войдите в аккаунт Google. После входа окно закроется само.")
        } else {
            loc(
                "Выберите на картинке то, что просит подпись, и нажмите кнопку " +
                    "под ней. Проверок может быть несколько."
            )
        }

        hint.setPadding(dp(16f), 0, dp(16f), dp(8f))

        column.addView(hint)

        web = WebView(context)

        val settings = web.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        /**
         * Страницу вписываем в ширину.
         *
         * То же, что `scalesPageToFit` у старого веб-вида в оригинале:
         * простая разметка входа свёрстана под настольный экран,
         * и без этого её видно четвертью.
         */
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        /**
         * Имя браузера.
         *
         * На нынешнем веб-виде оставляем своё: он и правда современный,
         * и подменять его — значит просить сборку страницы хуже той,
         * которую он потянет. На старом подменяем, и каждой из двух
         * задач своим именем.
         */
        if (!modernWebAvailable()) {
            settings.userAgentString = if (login) LOGIN_USER_AGENT else CHALLENGE_USER_AGENT
        }

        CookieManager.getInstance().setAcceptCookie(true)

        /**
         * Куки третьих сторон — обязательно.
         *
         * С Android 5 веб-вид по умолчанию их не принимает, а вход
         * в Google идёт через `accounts.google.com` и возвращается
         * на `youtube.com`: без чужих кук сеанс не соберётся.
         * Метод появился в API 21 — ровно там, где появился и запрет.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = Client()

        column.addView(
            web,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        if (!login) {
            column.addView(buildDone())
        }

        root.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        busy = LoadingRing(context)

        busy.color = Theme.loadingRing

        root.addView(
            busy,
            FrameLayout.LayoutParams(dp(32f), dp(32f), Gravity.CENTER)
        )

        /**
         * Либо страница входа, либо страница ролика.
         *
         * Вход — обычная страница Google с возвратом на youtube.com.
         * После неё в хранилище остаются куки настоящего сеанса, и ими
         * подписываются наши запросы.
         *
         * Проверка открывает **страницу ролика** (`m.youtube.com/watch`),
         * а не главную: Google показывает проверку там, где она нужна,
         * и на главной может не показать её вовсе.
         */
        val address = if (login) {
            WebAuth.loginUrl()
        } else {
            "https://m.youtube.com/watch?v=${videoId ?: ""}"
        }

        Log.d { "[YouTube/Проверка] Открываем $address" }

        Log.d {
            "[YouTube/Проверка] Представляемся: " + if (modernWebAvailable()) {
                "нынешний веб-вид, имя своё"
            } else {
                if (login) LOGIN_USER_AGENT else CHALLENGE_USER_AGENT
            }
        }

        busy.start()

        web.loadUrl(address)
    }

    /**
     * Кнопка «Готово» — только у проверки.
     *
     * У входа её нет: там окно закрывается само, когда куки собрались.
     * А проверку Google не всегда доводит до перехода — бывает, что она
     * решена, а страница осталась той же; человеку нужен способ сказать
     * «я закончил».
     */
    private fun buildDone(): android.view.View {
        val done = TappableView(context)

        val pill = PillView(context)

        pill.fillColor = Theme.ACCENT_BLUE
        pill.cornerRadius = Metrics.dpf(22f)

        done.addView(
            pill,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val doneLabel = label(context, Fonts.semiBold, 15f, android.graphics.Color.WHITE, 1)

        doneLabel.text = loc("Готово")

        done.addView(
            doneLabel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        done.setPadding(dp(18f), dp(10f), dp(18f), dp(10f))

        done.onTap = {
            harvest()

            Nav.pop()

            onDone?.invoke()
        }

        done.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16f), dp(8f), dp(16f), dp(16f)) }

        return done
    }

    private inner class Client : WebViewClient() {

        @Suppress("OverridingDeprecatedMember", "DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false

        override fun onPageFinished(view: WebView?, url: String?) {
            busy.stop()

            harvest()

            if (!login || url == null) {
                return
            }

            /**
             * Вход кончился, когда нас вернуло на youtube.com **и** куки
             * собрались.
             *
             * Одного адреса мало: на него же попадают и по дороге,
             * до ввода пароля. Проверяем именно набор кук — тот самый,
             * без которого сервер встречает нас проверкой «вы не бот».
             */
            if (!url.contains("youtube.com") || !WebAuth.isSignedIn()) {
                return
            }

            Log.d {
                "[YouTube/Вход] Браузерный вход состоялся: ${WebAuth.sessionReport()}"
            }

            Toast.show(context, loc("Вход выполнен"))

            Nav.pop()

            onDone?.invoke()
        }

        @Suppress("OverridingDeprecatedMember", "DEPRECATION")
        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            busy.stop()

            Log.d { "[YouTube/Проверка] Страница не загрузилась: $description" }
        }
    }

    /**
     * Сбрасывает куки веб-вида на диск и откладывает их про запас.
     *
     * В оригинале это `harvestModernCookies` — там куки нынешнего
     * веб-вида приходилось руками перекладывать в общее хранилище,
     * иначе вход был виден на экране, но не в запросах.
     *
     * Здесь хранилище одно на все веб-виды, но своё запасное всё равно
     * нужно: наши запросы идут через OkHttp, у которого хранилища кук нет
     * вовсе, — и [WebAuth] читает их у менеджера сам.
     */
    private fun harvest() {
        WebAuth.flush()

        if (!WebAuth.isSignedIn()) {
            /**
             * Говорим только когда число изменилось.
             *
             * Иначе журнал заполнялся бы десятками одинаковых строк,
             * в которых тонуло бы всё остальное.
             */
            val now = WebAuth.sessionReport().hashCode()

            if (now != movedCookies) {
                movedCookies = now

                Log.d { "[YouTube/Вход] Сеанса пока нет: ${WebAuth.sessionReport()}" }
            }

            return
        }

        WebAuth.keepSession()
    }

    override fun handleBack(): Boolean {
        if (web.canGoBack()) {
            web.goBack()

            return true
        }

        return false
    }

    override fun destroy() {
        super.destroy()

        harvest()

        web.destroy()
    }

    override fun repaint() {
        super.repaint()

        header.repaint()
    }
}
