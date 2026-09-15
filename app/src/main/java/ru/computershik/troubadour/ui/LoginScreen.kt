package ru.computershik.troubadour.ui

import android.content.Context
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import org.json.JSONObject
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.net.Api
import ru.computershik.troubadour.net.Auth
import ru.computershik.troubadour.net.Http
import ru.computershik.troubadour.net.Json
import ru.computershik.troubadour.ui.Metrics.dp
import ru.computershik.troubadour.ui.Metrics.dpf

/**
 * Вход — порт `Login.xaml`.
 *
 * Числа оттуда же:
 *
 *     подпись сверху  18, AppPrimaryText, по центру, Margin="0,0,0,20"
 *     рамка QR        белая, CornerRadius="5.33", Padding="6.67", не больше 200
 *     код             24 Bold, Margin="0,20,0,0", CharacterSpacing="200"
 *     подпись под ним 14, AppMutedText, Margin="0,4,0,0"
 *     кнопка          обводка 2, скругление 32, высота 54, ширина от 173.33,
 *                     подпись 13.33 SemiBold, Margin="0,20,0,0"
 *     подвал          «Настройки» и «О программе», 13, AppMutedText, высота 30
 *
 * Разметка руками, а не контейнерами, и это не прихоть: в оригинале
 * `layoutSubviews` расставляет всё по этим числам, а всякий контейнер
 * привносит свои умолчания — на них вёрстка и поехала в первый раз.
 */
class LoginScreen(context: Context) : Screen(context) {

    private lateinit var header: ScreenHeader
    private lateinit var panel: LoginPanel

    override fun build(root: FrameLayout) {
        header = ScreenHeader(context, loc("Вход"))

        panel = LoginPanel(context)

        root.addView(
            panel.body,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { topMargin = dp(Metrics.NAV_BAR_HEIGHT) + statusBarHeight() }
        )

        root.addView(header)

        panel.onDone = { Nav.pop() }

        panel.activate()
    }

    override fun destroy() {
        super.destroy()

        panel.deactivate()
    }

    override fun repaint() {
        super.repaint()

        header.repaint()
        panel.body.repaint()
    }
}

/**
 * Ход входа — отдельно от экрана.
 *
 * В оригинале вход не отдельная страница, а содержимое вкладки «Вы»:
 * `YTMeView` держит `YTLoginView` у себя и показывает его во всю вкладку,
 * пока никто не вошёл, включая и выключая опрос через `activate`
 * и `deactivate`. Значит, ход входа обязан жить отдельно от экрана —
 * иначе его нельзя вставить во вкладку.
 */
class LoginPanel(context: Context) {

    val body = LoginBody(context)

    private var polling = false
    private var stopped = true

    /** Кого позвать, когда вход удался. */
    var onDone: (() -> Unit)? = null

    init {
        body.onRefresh = { begin() }
    }

    fun activate() {
        if (!stopped) {
            return
        }

        stopped = false

        begin()
    }

    fun deactivate() {
        stopped = true
    }

    private fun begin() {
        body.setCode("")
        body.setQr(null)
        body.setStatus(loc("Запрашиваем код…"))

        async {
            val code = Auth.beginDeviceFlow()

            if (stopped) {
                return@async
            }

            if (code.isNullOrEmpty()) {
                main { body.setStatus(loc("Не удалось получить код. Повторите попытку.")) }

                return@async
            }

            main {
                body.setStatus(loc("Отсканируйте QR-код, чтобы войти"))
                body.setCode(code)
            }

            /**
             * QR рисует не приложение, а YouTube: запрос `mdx/handoff`
             * возвращает готовую картинку прямо в теле ответа, адресом
             * вида `data:image/png;base64,…`. Порт `GetTvQrBase64Async`.
             */
            val qr = fetchQr(code)

            main {
                if (!stopped) {
                    body.setQr(qr)
                }
            }
        }

        poll()
    }

    /**
     * Опрос сервера.
     *
     * «Ещё нет» — штатный ответ, а не ошибка: сервер отвечает
     * `authorization_pending` на каждый опрос, пока код не введён. Паузу
     * между опросами называет он же; попросит опрашивать реже —
     * послушаемся, иначе начнёт отказывать.
     */
    private fun poll() {
        if (polling || stopped) {
            return
        }

        polling = true

        async {
            while (!stopped) {
                val answer = Auth.pollDeviceFlow()

                if (answer == 1) {
                    main {
                        body.setStatus(loc("Готово"))

                        onDone?.invoke()
                    }

                    break
                }

                if (answer < 0) {
                    main { body.setStatus(loc("Код больше не действует. Обновите его.")) }

                    break
                }

                try {
                    Thread.sleep((Auth.pollInterval * 1000).toLong())
                } catch (error: InterruptedException) {
                    break
                }
            }

            polling = false
        }
    }

    /** QR-код с самим кодом внутри — порт `GetTvQrBase64Async`. */
    private fun fetchQr(userCode: String): Bitmap? {
        val client = JSONObject()

        client.put("clientName", "TVHTML5")
        client.put("clientVersion", "7.20251217.19.00")
        client.put("deviceMake", "Samsung")
        client.put("deviceModel", "SmartTV")
        client.put("platform", "TV")
        client.put("hl", Api.hl())
        client.put("gl", Api.gl())

        val rapid = JSONObject()

        rapid.put(
            "qrPresetStyle",
            "HANDOFF_QR_LIMITED_PRESET_STYLE_MODERN_BIG_DOTS_INVERT_WITH_YT_LOGO"
        )
        rapid.put("userCode", userCode)
        rapid.put("rapidQrFeature", "RAPID_QR_FEATURE_DEFAULT")

        val payload = JSONObject()

        payload.put("context", JSONObject().put("client", client))
        payload.put("handoffQrParams", JSONObject().put("rapidQrParams", rapid))

        val builder = Http.request(
            "https://www.youtube.com/youtubei/v1/mdx/handoff?key=${Api.INNERTUBE_KEY}"
        ) ?: return null

        builder.post(Http.jsonBody(Json.encode(payload)))
        builder.header("Content-Type", "application/json")
        builder.header("User-Agent", "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0)")

        val response = Http.send(builder.build(), 2 * 1024 * 1024, caching = false)

        if (!response.isSuccessful) {
            Log.d { "[YouTube/Вход] QR не получен: код ${response.statusCode}" }

            return null
        }

        val json = Json.parse(response.body)

        /**
         * Ответ уложен так: `rapidQrRenderer → qrCodeRenderer → qrCodeImage
         * → thumbnails[0].url`. Ищем **владельца** `qrCodeImage`, а не саму
         * картинку: помощник ждёт ключ, за которым лежит объект с массивом
         * `thumbnails`, и передавать ему сам массив бесполезно — он молча
         * вернёт пустоту. На этом QR и не появлялся.
         */
        val renderer = Json.findFirst("qrCodeRenderer", json, 2000)

        val url = Json.thumbnail(renderer, "qrCodeImage", 0)

        if (url.isNullOrEmpty()) {
            Log.d { "[YouTube/Вход] В ответе нет картинки QR" }

            return null
        }

        val marker = url.indexOf("base64,")

        if (marker < 0) {
            return null
        }

        return try {
            val bytes = Base64.decode(url.substring(marker + 7), Base64.DEFAULT)

            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (error: Throwable) {
            Log.d { "[YouTube/Вход] QR не разобрался: ${error.message}" }

            null
        }
    }
}

/**
 * Содержимое экрана входа, размеченное руками.
 *
 * Порядок и отступы — из `layoutSubviews` оригинала: подпись, QR, код,
 * подпись под кодом, кнопка обновления, две надписи подвала в один ряд.
 */
class LoginBody(context: Context) : ViewGroup(context) {

    companion object {
        private const val QR_SIDE = 200f

        /**
         * Меньше этого код не ужимаем: мелкий QR камера соседнего телефона
         * уже не разбирает, а ради него весь экран и существует.
         */
        private const val QR_LEAST = 132f

        private const val QR_PADDING = 6.67f
        private const val BUTTON_HEIGHT = 54f
        private const val BUTTON_WIDTH = 173.33f
        private const val ABOUT_HEIGHT = 30f
    }

    private val status = label(context, Fonts.regular, 18f, Theme.primaryText, 0)

    /**
     * Рамка QR всегда белая — это не цвет темы, а фон самого кода:
     * на тёмной подложке он не читается сканером.
     */
    private val qrFrame = PillView(context)
    private val qr = ImageView(context)

    private val code = label(context, Fonts.bold, 24f, Theme.primaryText, 1)
    private val caption = label(context, Fonts.regular, 14f, Theme.mutedText, 1)

    private val refreshFill = PillView(context)
    private val refreshTitle = label(context, Fonts.semiBold, 13.33f, Theme.primaryText, 1)

    private val settings = label(context, Fonts.regular, 13f, Theme.mutedText, 1)
    private val about = label(context, Fonts.regular, 13f, Theme.mutedText, 1)

    var onRefresh: (() -> Unit)? = null

    init {
        setBackgroundColor(Theme.background)

        status.gravity = Gravity.CENTER
        status.text = loc("Отсканируйте QR-код, чтобы войти")

        addView(status)

        qrFrame.fillColor = Color.WHITE
        qrFrame.cornerRadius = dpf(5.33f)

        addView(qrFrame)

        qr.scaleType = ImageView.ScaleType.FIT_CENTER

        addView(qr)

        code.gravity = Gravity.CENTER

        /**
         * `CharacterSpacing="200"` — двести тысячных кегля.
         *
         * Разрядка появилась только в API 21, и вызов её без оглядки
         * ронял приложение **при запуске** на 4.1 и 4.4: оболочка
         * трогает вид каждого раздела сразу, а вид строится по первому
         * обращению — то есть экран входа собирается вместе с прочими,
         * ещё до того, как его кто-то открыл. На 5.1 и новее этого
         * не видно вовсе.
         *
         * Ниже 21 код остаётся без разрядки: читается он и так.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            code.letterSpacing = 0.2f
        }

        addView(code)

        caption.gravity = Gravity.CENTER
        caption.text = loc("Код подтверждения")

        addView(caption)

        /**
         * Кнопка обновления — обводка 2 точки со скруглением 32,
         * `PillOutlineButtonStyle` из разметки оригинала. Заливки у неё
         * нет: только рамка цвета `AppDivider`.
         */
        refreshFill.fillColor = Theme.background
        refreshFill.cornerRadius = dpf(32f)
        refreshFill.strokeColor = Theme.divider
        refreshFill.strokeWidth = dpf(2f)

        refreshFill.isClickable = true
        refreshFill.setOnClickListener { onRefresh?.invoke() }

        addView(refreshFill)

        refreshTitle.gravity = Gravity.CENTER
        refreshTitle.text = loc("Обновить QR-код")

        addView(refreshTitle)

        settings.gravity = Gravity.CENTER
        settings.text = loc("Настройки")

        settings.isClickable = true
        settings.setOnClickListener { Nav.push(SettingsScreen(context)) }

        addView(settings)

        about.gravity = Gravity.CENTER
        about.text = loc("О программе")

        about.isClickable = true
        about.setOnClickListener { Nav.push(AboutScreen(context)) }

        addView(about)
    }

    fun setStatus(text: String) {
        status.text = text

        requestLayout()
    }

    fun setCode(text: String) {
        code.text = text

        caption.visibility = if (text.isEmpty()) GONE else VISIBLE

        requestLayout()
    }

    fun setQr(image: Bitmap?) {
        qr.setImageBitmap(image)

        qrFrame.visibility = if (image == null) GONE else VISIBLE
        qr.visibility = qrFrame.visibility

        requestLayout()
    }

    fun repaint() {
        setBackgroundColor(Theme.background)

        status.setTextColor(Theme.primaryText)
        code.setTextColor(Theme.primaryText)
        caption.setTextColor(Theme.mutedText)
        refreshTitle.setTextColor(Theme.primaryText)
        settings.setTextColor(Theme.mutedText)
        about.setTextColor(Theme.mutedText)

        refreshFill.fillColor = Theme.background
        refreshFill.strokeColor = Theme.divider
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val bounds = r - l
        val height = b - t

        // Содержимое по центру, `Margin="20,0,20,0"`.
        val width = bounds - dp(40f)

        val statusHeight = maxOf(
            Metrics.textHeight(status.text.toString(), Fonts.regular, 18f, width, 0),
            Metrics.lineHeight(Fonts.regular, 18f)
        )

        val codeHeight = Metrics.lineHeight(Fonts.bold, 24f)
        val captionHeight = Metrics.lineHeight(Fonts.regular, 14f)

        val fixed = statusHeight + dp(20f) + dp(20f) + codeHeight + dp(4f) +
            captionHeight + dp(20f) + dp(BUTTON_HEIGHT) + dp(12f) + dp(ABOUT_HEIGHT)

        var qrSide = minOf(dp(QR_SIDE), width)

        if (height > 0) {
            val room = height - fixed - dp(16f)

            if (room < qrSide) {
                qrSide = maxOf(dp(QR_LEAST), room)
            }
        }

        val total = fixed + qrSide

        var y = maxOf(0, (height - total) / 2)

        status.frame(dp(20f), y, width, statusHeight)

        y += statusHeight + dp(20f)

        val qrLeft = (bounds - qrSide) / 2
        val pad = dp(QR_PADDING)

        qrFrame.frame(qrLeft, y, qrSide, qrSide)
        qr.frame(qrLeft + pad, y + pad, qrSide - pad * 2, qrSide - pad * 2)

        y += qrSide + dp(20f)

        code.frame(dp(20f), y, width, codeHeight)

        y += codeHeight + dp(4f)

        caption.frame(dp(20f), y, width, captionHeight)

        y += captionHeight + dp(20f)

        val buttonWidth = dp(BUTTON_WIDTH)
        val buttonLeft = (bounds - buttonWidth) / 2

        refreshFill.frame(buttonLeft, y, buttonWidth, dp(BUTTON_HEIGHT))
        refreshTitle.frame(buttonLeft, y, buttonWidth, dp(BUTTON_HEIGHT))

        y += dp(BUTTON_HEIGHT) + dp(12f)

        // Две надписи в один ряд: каждой по половине ширины.
        val half = width / 2

        settings.frame(dp(20f), y, half, dp(ABOUT_HEIGHT))
        about.frame(dp(20f) + half, y, half, dp(ABOUT_HEIGHT))
    }
}
