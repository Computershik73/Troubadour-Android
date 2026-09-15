package ru.computershik.troubadour.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import ru.computershik.troubadour.BuildConfig
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.Strings
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.locF
import ru.computershik.troubadour.net.Auth
import ru.computershik.troubadour.net.PoToken
import ru.computershik.troubadour.net.WebAuth
import ru.computershik.troubadour.player.Streams
import ru.computershik.troubadour.ui.Metrics.dp

/**
 * Настройки — порт `Settings.xaml`.
 *
 * Строки те же и в том же порядке: язык надписей, язык ответов, качество,
 * качество Shorts, качество превью, способ получения потока, переключатели.
 */
class SettingsScreen(context: Context) : Screen(context) {

    private lateinit var header: ScreenHeader
    private lateinit var column: LinearLayout

    override fun build(root: FrameLayout) {
        val holder = LinearLayout(context)

        holder.orientation = LinearLayout.VERTICAL

        header = ScreenHeader(context, loc("Настройки"))

        holder.addView(header)

        val scroll = ScrollView(context)

        column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL

        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        holder.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        root.addView(
            holder,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        rebuild()

        Notify.on(Notify.SETTINGS, this) { rebuild() }
    }

    private fun rebuild() {
        column.removeAllViews()

        group(loc("Оформление"))

        pick("theme_light", loc("Тема"), Theme.titleForMode(Theme.mode)) {
            ThemeSheet(context).show()
        }

        /**
         * Языковое — своим разделом.
         *
         * Язык надписей, язык ответов сервера и язык звука отвечают
         * на один и тот же вопрос «на каком языке», и разносить их
         * по «Оформлению» и «Видео» значило бы прятать половину.
         */
        group(loc("Язык"))

        pick("languages_light", loc("Язык приложения"), Strings.title(Settings.interfaceLanguage)) {
            InterfaceLanguageSheet(context).show()
        }

        pick("languages_light", loc("Язык YouTube"), Settings.languageTitle(Settings.language)) {
            LanguageSheet(context).show()
        }

        pick(
            "languages_light", loc("Язык звука при просмотре"),
            Settings.audioLanguageTitle(Settings.playbackAudioLanguage)
        ) {
            AudioLanguageSheet(context, false).show()
        }

        pick(
            "languages_light", loc("Язык звука при скачивании"),
            Settings.audioLanguageTitle(Settings.downloadAudioLanguage)
        ) {
            AudioLanguageSheet(context, true).show()
        }

        group(loc("Видео"))

        pick("pl_quality_light", loc("Предпочитаемое качество"), Settings.qualityTitle(Settings.preferredHeight)) {
            HeightSheet(context, false).show()
        }

        pick(
            "pl_quality_light", loc("Качество Shorts"),
            Settings.qualityTitle(Settings.shortsHeight)
        ) {
            HeightSheet(context, true).show()
        }

        pick(
            "pl_quality_light", loc("Качество превью"),
            Settings.thumbnailTitle(Settings.thumbnailWidth)
        ) {
            ThumbnailSheet(context).show()
        }

        pick(
            "pl_download_light", loc("Способ воспроизведения"),
            Settings.deliveryTitle(Settings.delivery)
        ) {
            DeliverySheet(context).show()
        }

        group(loc("Переключатели"))

        toggle(loc("Значки каналов"), Settings.showsChannelIcons) {
            Settings.showsChannelIcons = it
        }

        toggle(loc("Полный экран при повороте"), Settings.autoFullscreenInLandscape) {
            Settings.autoFullscreenInLandscape = it
        }

        toggle(
            loc("Следующий в плейлисте"),
            Settings.autoplayNextInQueue
        ) {
            Settings.autoplayNextInQueue = it
        }

        toggle(loc("Следующий Shorts"), Settings.autoplayNextShort) {
            Settings.autoplayNextShort = it
        }

        toggle(loc("Скрыть Shorts"), Settings.hidesShorts) {
            Settings.hidesShorts = it
        }

        toggle(loc("Показывать кадры при перемотке"), Settings.showsSeekPreview) {
            Settings.showsSeekPreview = it
        }

        toggle(loc("Пропускать рекламные вставки"), Settings.usesSponsorBlock) {
            Settings.usesSponsorBlock = it
        }

        /**
         * Шестьдесят кадров — с предупреждением там, где железо их не тянет.
         *
         * Запрета здесь нет: тяжесть зависит и от самого ролика, и от того,
         * что ещё делает устройство, — а решать, смотреть ли ценой рывков,
         * человеку. Железо задаёт значение по умолчанию, а не потолок.
         */
        toggle(
            loc("Шестьдесят кадров"),
            Settings.allowsSixtyFrames,
            if (Settings.prefersThirtyByDevice()) {
                loc("Этот декодер шестьдесят кадров не тянет — возможны рывки")
            } else {
                null
            }
        ) {
            Settings.allowsSixtyFrames = it
        }

        group(loc("Вход"))

        pick(
            "log_out_light", loc("Вход в браузере"),
            if (WebAuth.isSignedIn()) loc("Выполнен") else loc("Входа нет")
        ) {
            if (WebAuth.isSignedIn()) {
                WebAuth.signOut()

                rebuild()
            } else {
                Nav.push(WebScreen(context, login = true))
            }
        }

        /**
         * Выход показывается только вошедшему — вместе со своим
         * пунктом: раздел из одного пункта, и без него в нём ничего
         * не остаётся.
         */
        if (Auth.isSignedIn()) {
            pick("log_out_light", loc("Выйти"), "") {
                Auth.signOut()

                rebuild()
            }
        }

        group(loc("О программе"))

        pick("info", loc("Сведения"), "") {
            Nav.push(AboutScreen(context))
        }

        /**
         * Выдача PO-токена — не настройка, а инструмент, и стоит она
         * здесь потому, что это единственное место, куда можно нажать
         * осознанно. Обычно подготовка идёт сама, перед первым роликом;
         * отсюда её можно запустить руками и посмотреть по журналу,
         * чем кончилось.
         */
        pick(
            "info", loc("PO-токен"),
            if (PoToken.isReady()) loc("Готов") else loc("Нет")
        ) {
            PoToken.prepare()

            rebuild()
        }

        toggle(loc("Готовить PO-токен сам"), Settings.preparesPoToken) {
            Settings.preparesPoToken = it
        }
    }

    private fun group(title: String) {
        val label = label(context, Fonts.semiBold, 13f, Theme.secondaryText, 1)

        label.text = title
        label.setPadding(dp(16f), dp(16f), dp(16f), dp(6f))

        column.addView(label)
    }

    /** Строка выбора: значок, название, нынешнее значение справа. */
    private fun pick(icon: String, title: String, value: String, action: () -> Unit) {
        val view = TappableView(context)

        view.highlights = true

        val row = LinearLayout(context)

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val image = ImageView(context)

        image.setImageBitmap(Icons.icon(icon.removeSuffix("_light")))
        image.scaleType = ImageView.ScaleType.FIT_CENTER

        row.addView(image, LinearLayout.LayoutParams(dp(24f), dp(24f)))

        val names = LinearLayout(context)

        names.orientation = LinearLayout.VERTICAL

        val name = label(context, Fonts.regular, 15f, Theme.primaryText, 1)
        val note = label(context, Fonts.regular, 12f, Theme.mutedText, 1)

        name.text = title
        note.text = value

        names.addView(name)
        names.addView(note)

        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        params.leftMargin = dp(16f)

        row.addView(names, params)

        view.addView(row)
        view.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))

        view.onTap = action

        column.addView(view)
    }

    /** Переключатель: название, пояснение под ним и сам тумблер. */
    private fun toggle(
        title: String,
        value: Boolean,
        hint: String? = null,
        action: (Boolean) -> Unit
    ) {
        val view = TappableView(context)

        view.highlights = true

        val row = LinearLayout(context)

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val names = LinearLayout(context)

        names.orientation = LinearLayout.VERTICAL

        val name = label(context, Fonts.regular, 15f, Theme.primaryText, 0)

        name.text = title

        names.addView(name)

        if (!hint.isNullOrEmpty()) {
            val note = label(context, Fonts.regular, 12f, Theme.mutedText, 0)

            note.text = hint

            names.addView(note)
        }

        row.addView(
            names,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        /**
         * Тумблер системный.
         *
         * В оригинале он свой: `UISwitch` там красится только целиком
         * и на iOS 5 выглядит иначе, чем на 9. Здесь `Switch` один
         * на все версии от 14-й и красится темой сам.
         */
        val switch = android.widget.Switch(context)

        switch.isChecked = value
        switch.isClickable = false

        row.addView(switch)

        view.addView(row)
        view.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))

        view.onTap = {
            switch.isChecked = !switch.isChecked

            action(switch.isChecked)
        }

        column.addView(view)
    }

    override fun repaint() {
        super.repaint()

        header.repaint()

        rebuild()
    }
}

/** Тема: как в системе, светлая, тёмная. */
class ThemeSheet(context: Context) : Sheet(context, loc("Тема")) {

    init {
        for (mode in listOf(Theme.SYSTEM, Theme.LIGHT, Theme.DARK)) {
            add(
                row(Theme.titleForMode(mode), null, mode == Theme.mode) {
                    Theme.mode = mode
                }
            )
        }
    }
}

/** Язык надписей приложения — свой список из тринадцати. */
class InterfaceLanguageSheet(context: Context) : Sheet(context, loc("Язык приложения")) {

    init {
        add(
            row(loc("Как в системе"), null, Settings.interfaceLanguage.isEmpty()) {
                Settings.interfaceLanguage = ""
            }
        )

        for ((code, title) in Strings.languages) {
            add(
                row(title, null, code == Settings.interfaceLanguage) {
                    Settings.interfaceLanguage = code
                }
            )
        }
    }
}

/** Язык ответов сервера — то, что уходит в `hl`. */
class LanguageSheet(context: Context) : Sheet(context, loc("Язык YouTube")) {

    init {
        add(
            row(loc("Как в системе"), null, Settings.language.isEmpty()) {
                Settings.language = ""
            }
        )

        for ((code, title) in Settings.languageOptions) {
            add(
                row(title, null, code == Settings.language) {
                    Settings.language = code
                }
            )
        }
    }
}

/** Качество: общее либо у Shorts — у них свой выбор, как в оригинале. */
class HeightSheet(context: Context, private val shorts: Boolean) :
    Sheet(context, loc("В каком качестве смотреть")) {

    init {
        val now = if (shorts) Settings.shortsHeight else Settings.preferredHeight

        for (height in Settings.qualityOptions) {
            val hint = if (height > 0 && Streams.isBeyondDevice(height)) {
                locF("%ldp выше меры устройства — картинка будет отставать", height)
            } else {
                null
            }

            add(
                row(Settings.qualityTitle(height), hint, height == now) {
                    if (shorts) {
                        Settings.shortsHeight = height
                    } else {
                        Settings.preferredHeight = height
                    }
                }
            )
        }
    }
}

/** Качество превью: ступени у самих превью i.ytimg.com. */
class ThumbnailSheet(context: Context) : Sheet(context, loc("Качество превью")) {

    init {
        for (width in Settings.thumbnailOptions) {
            add(
                row(Settings.thumbnailTitle(width), null, width == Settings.thumbnailWidth) {
                    Settings.thumbnailWidth = width
                }
            )
        }
    }
}

/**
 * Какую озвучку брать — отдельно для просмотра и для скачивания.
 *
 * Лады у обоих одни и те же, поэтому лист один, а [forDownload]
 * говорит, чью настройку он правит.
 */
class AudioLanguageSheet(context: Context, private val forDownload: Boolean) :
    Sheet(
        context,
        if (forDownload) loc("Язык звука при скачивании") else loc("Язык звука при просмотре")
    ) {

    init {
        val now = if (forDownload) {
            Settings.downloadAudioLanguage
        } else {
            Settings.playbackAudioLanguage
        }

        for (mode in Settings.audioLanguageOptions) {
            add(
                row(
                    Settings.audioLanguageTitle(mode),
                    Settings.audioLanguageHint(mode),
                    mode == now
                ) {
                    if (forDownload) {
                        Settings.downloadAudioLanguage = mode
                    } else {
                        Settings.playbackAudioLanguage = mode
                    }
                }
            )
        }
    }
}

/** Способ получения потока: подача SABR либо готовые адреса. */
class DeliverySheet(context: Context) : Sheet(context, loc("Способ воспроизведения")) {

    init {
        for (delivery in Settings.deliveryOptions) {
            add(
                row(
                    Settings.deliveryTitle(delivery),
                    Settings.deliveryHint(delivery),
                    delivery == Settings.delivery
                ) {
                    Settings.delivery = delivery
                }
            )
        }
    }
}

/**
 * «О программе» — порт `About.xaml`.
 *
 * Версия, ссылки, путь к журналу и кнопка «поделиться» им: журнал пишется
 * в файл ровно затем, чтобы его можно было прислать.
 */
class AboutScreen(context: Context) : Screen(context) {

    private lateinit var header: ScreenHeader

    override fun build(root: FrameLayout) {
        val holder = LinearLayout(context)

        holder.orientation = LinearLayout.VERTICAL

        header = ScreenHeader(context, loc("О программе"))

        holder.addView(header)

        val scroll = ScrollView(context)
        val column = LinearLayout(context)

        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(16f), dp(16f), dp(16f), dp(16f))

        val title = label(context, Fonts.bold, 22f, Theme.primaryText, 1)

        title.text = loc("Трубадур")

        column.addView(title)

        val version = label(context, Fonts.regular, 14f, Theme.secondaryText, 1)

        /**
         * Рядом с версией — отметка сборки.
         *
         * Версия между сборками не меняется, и понять по экрану, что
         * именно стоит на устройстве, было нельзя. Та же отметка стоит
         * первой строкой каждого запуска в журнале — по ней записи
         * и сходятся со сборкой.
         */
        version.text = locF("Версия %@", BuildConfig.VERSION_NAME) +
            " (${BuildConfig.BUILD_STAMP})"

        column.addView(version)

        /**
         * Чей это клиент, сказано отдельной строкой: ставить YouTube
         * первым словом в названии приложения нельзя, магазины такое
         * отклоняют. В «Трубаче» ровно та же оговорка.
         */
        val unofficial = label(context, Fonts.regular, 15f, Theme.secondaryText, 0)

        unofficial.text = loc(
            "Неофициальный клиент YouTube для iOS 5.1 и новее."
        )

        column.addView(unofficial, spaced())

        val developer = label(context, Fonts.regular, 15f, Theme.primaryText, 1)

        developer.text = loc("Разработчик: Computershik")

        column.addView(developer, spaced())

        val about = label(context, Fonts.regular, 14f, Theme.primaryText, 0)

        about.text = loc(
            "Вдохновлено клиентом YouTube UWP для Windows 10 Mobile " +
                "от zemonkamin — в его разработке я тоже участвовал."
        )

        column.addView(about, spaced())

        val port = label(context, Fonts.regular, 14f, Theme.secondaryText, 0)

        port.text = loc(
            "Порт клиента для iOS 5.1. Нижняя граница здесь — Android 4.1: " +
                "с неё в системе появились MediaCodec и TLS 1.2."
        )

        column.addView(port, spaced())

        column.addView(
            link(
                loc("Страница на 4PDA"),
                "https://4pda.to/forum/index.php?showuser=4458524"
            ),
            spaced()
        )

        column.addView(link(loc("Telegram-канал"), "https://t.me/cmplog"), spaced())

        column.addView(
            link(loc("Поддержать финансово"), "https://pay.cloudtips.ru/p/83821e32"),
            spaced()
        )

        val system = label(context, Fonts.regular, 13f, Theme.secondaryText, 1)

        system.text = locF(
            "Система: Android %@", android.os.Build.VERSION.RELEASE ?: ""
        )

        column.addView(system, spaced())

        /**
         * Про журнал — только там, где он есть.
         *
         * Готовая сборка не пишет ни строки, и обещать в ней журнал
         * значит отправить человека искать пустой файл.
         */
        if (ru.computershik.troubadour.BuildConfig.LOG) {
            val log = label(context, Fonts.regular, 13f, Theme.mutedText, 0)

            log.text = locF(
                "Журнал работы пишется в %@. Если что-то пошло не так, " +
                    "нужен именно этот файл.",
                Log.path()
            )

            column.addView(log, spaced())
        }

        val share = TappableView(context)

        val shareLabel = label(context, Fonts.semiBold, 14f, Theme.ACCENT_BLUE, 1)

        shareLabel.text = loc("Отправить журнал")

        share.addView(shareLabel)
        share.setPadding(0, dp(12f), 0, dp(12f))

        share.onTap = { shareLog() }

        column.addView(share)

        val copy = TappableView(context)

        val copyLabel = label(context, Fonts.semiBold, 14f, Theme.ACCENT_BLUE, 1)

        copyLabel.text = loc("Скопировать журнал на карту")

        copy.addView(copyLabel)
        copy.setPadding(0, dp(12f), 0, dp(12f))

        copy.onTap = { copyLogToCard() }

        column.addView(copy)

        val clear = TappableView(context)

        val clearLabel = label(context, Fonts.semiBold, 14f, Theme.ACCENT_BLUE, 1)

        clearLabel.text = loc("Очистить журнал")

        clear.addView(clearLabel)
        clear.setPadding(0, dp(12f), 0, dp(12f))

        clear.onTap = {
            Log.clear()

            Log.d { "[YouTube] Журнал очищен" }

            Toast.show(context, loc("Журнал очищен"))
        }

        column.addView(clear)

        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        holder.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        root.addView(
            holder,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun spaced(): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        params.topMargin = dp(12f)

        return params
    }

    private fun link(text: String, url: String): View {
        val view = TappableView(context)

        val label = label(context, Fonts.regular, 14f, Theme.ACCENT_BLUE, 1)

        label.text = text

        view.addView(label)

        view.onTap = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (error: Exception) {
                Toast.show(context, loc("Не получилось"))
            }
        }

        return view
    }

    /**
     * Журнал уходит наружу через FileProvider.
     *
     * В оригинале для этого хватало `UIFileSharingEnabled`: папка
     * открывалась в «Файлах» и iTunes, и файл забирался оттуда. Здесь
     * такого нет вовсе — приложение обязано отдать файл само, и притом
     * через временный адрес `content://`: путь к своему файлу отдавать
     * наружу с Android 7 запрещено.
     */
    /**
     * Копия журнала в общую память — рядом с загрузками.
     *
     * Отправка через другое приложение годится не всегда: на старом
     * устройстве почты может не быть вовсе, а файловый менеджер есть
     * почти везде. Кладём в `Download`, а не в корень карты: с Android 10
     * писать в корень нельзя, а этот каталог общий и виден любому
     * менеджеру.
     *
     * Разрешение на запись спрашивается только там, где оно вообще
     * нужно, — до Android 6 его выдаёт установка, с Android 10 запись
     * в свой общий каталог идёт без него вовсе.
     */
    private fun copyLogToCard() {
        val file = Log.file()

        if (file == null || !file.exists()) {
            Toast.show(context, loc("Журнал пуст"))

            return
        }

        if (!ensureStorageAllowed()) {
            return
        }

        async {
            val done = try {
                val folder = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )

                folder.mkdirs()

                val target = java.io.File(folder, "troubadour.log")

                file.inputStream().use { from ->
                    java.io.FileOutputStream(target).use { to ->
                        from.copyTo(to)
                    }
                }

                target.absolutePath
            } catch (error: Throwable) {
                Log.d { "[YouTube] Журнал не скопировался: ${error.message}" }

                null
            }

            main {
                if (done == null) {
                    Toast.show(context, loc("Не получилось"))
                } else {
                    Toast.showLong(context, done)
                }
            }
        }
    }

    /**
     * Разрешение на запись, если оно ещё нужно.
     *
     * До Android 6 его выдаёт установка; с Android 10 запись в свой
     * общий каталог идёт без разрешения вовсе. Спрашивать надо только
     * между этими границами.
     */
    private fun ensureStorageAllowed(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
            android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.P
        ) {
            return true
        }

        val activity = context as? android.app.Activity ?: return true

        val name = android.Manifest.permission.WRITE_EXTERNAL_STORAGE

        if (activity.checkSelfPermission(name) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }

        activity.requestPermissions(arrayOf(name), 1)

        return false
    }

    private fun shareLog() {
        val file = Log.file()

        if (file == null || !file.exists()) {
            Toast.show(context, loc("Журнал пуст"))

            return
        }

        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.files", file
            )

            val intent = Intent(Intent.ACTION_SEND)

            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            context.startActivity(Intent.createChooser(intent, loc("Отправить журнал")))
        } catch (error: Exception) {
            Toast.show(context, loc("Не получилось"))
        }
    }

    override fun repaint() {
        super.repaint()

        header.repaint()
    }
}
