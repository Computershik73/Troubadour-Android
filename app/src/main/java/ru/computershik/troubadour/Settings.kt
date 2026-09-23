package ru.computershik.troubadour

import android.os.Build
import android.content.Context
import android.content.SharedPreferences
import ru.computershik.troubadour.player.Streams
import ru.computershik.troubadour.ui.ImageLoader

/**
 * Каким путём добывается поток.
 *
 * Путей ровно два, и оба рабочие — разница в том, чем платишь.
 *
 * [SABR] — подача SABR от TV-клиента. Это то, чем YouTube раздаёт видео
 *     сегодня: вместо готовых ссылок сервер отдаёт поток кусками по запросу.
 *     Качества все, вплоть до 1080p и выше, звуковые дорожки все.
 *     Требует входа в учётную запись.
 *
 * [ANDROID_VR] — готовые адреса от клиента шлема. Старый способ: сервер
 *     присылает прямые ссылки на дорожки. Работает и без входа, но ссылки
 *     привязаны к тому адресу, с которого их взяли, — из-за VPN и адресов
 *     дата-центров раздача на них отвечает отказом чаще, чем хотелось бы.
 *
 * При [SABR] второй путь остаётся запасным: если подача не задалась,
 * приложение само переходит к готовым адресам.
 */
object Delivery {
    const val SABR = 0
    const val ANDROID_VR = 1
}

/**
 * Какую звуковую дорожку брать, когда их у ролика несколько.
 *
 * [ORIGINAL] — всегда родная, та, на которой ролик сняли.
 *
 * [DEVICE_AUTHORED] — на языке устройства, но только если её записал сам
 *     автор: синтезированный дубляж не берём. Нет авторской — оригинал.
 *
 * [DEVICE_ANY] — на языке устройства любая, включая машинный дубляж.
 *
 * [ASK] — спрашивать каждый раз, когда дорожек больше одной.
 */
object AudioLanguage {
    const val ORIGINAL = 0
    const val DEVICE_AUTHORED = 1
    const val DEVICE_ANY = 2
    const val ASK = 3
}

/**
 * Настройки приложения — порт `Settings.xaml` из версии для Windows 10 Mobile
 * и его хранилища.
 *
 * В UWP всё это лежит в `ApplicationData.Current.LocalSettings`, в iOS —
 * в NSUserDefaults, здесь — в SharedPreferences: разница только в имени
 * хранилища, набор ключей и значения по умолчанию те же.
 *
 * Чего здесь нет и почему:
 *
 *   «Живая плитка» — плиток ни на iOS, ни на Android не бывает;
 *   «Отправлять уведомления» — фоновые всплывающие уведомления требуют
 *       либо фоновой задачи, либо сервера. Сам экран уведомлений при этом
 *       перенесён и работает.
 *
 * А вот «предпросмотр перемотки», которого не было в iOS-версии на момент
 * написания её README, здесь есть: раскадровка перенесена.
 */
object Settings {

    private const val STORE = "troubadour"

    private const val LANGUAGE = "YTLanguage"
    private const val INTERFACE_LANGUAGE = "YTInterfaceLanguage"
    private const val PREFERRED_HEIGHT = "YTPreferredHeight"
    private const val SHORTS_HEIGHT = "YTShortsHeight"
    private const val THUMBNAIL_WIDTH = "YTThumbnailWidth"
    private const val DELIVERY = "YTDelivery"
    private const val DOWNLOAD_AUDIO_LANGUAGE = "YTDownloadAudioLanguage"
    private const val PLAYBACK_AUDIO_LANGUAGE = "YTPlaybackAudioLanguage"
    private const val CHANNEL_ICONS = "YTChannelIcons"
    private const val AUTO_FULLSCREEN = "YTAutoFullscreen"
    private const val PO_AUTO = "po_auto"
    private const val AUTOPLAY_QUEUE = "YTAutoplayQueue"
    private const val AUTOPLAY_SHORTS = "YTAutoplayShorts"
    private const val SUBTITLE_OFFSET = "YTSubtitleOffset"
    private const val SUBTITLE_PLACE = "YTSubtitlePlace"
    private const val SUBTITLE_PLACE_X = "YTSubtitlePlaceX"
    private const val SIXTY_FRAMES = "YTSixtyFrames"
    private const val HIDE_SHORTS = "YTHideShorts"
    private const val SEEK_PREVIEW = "YTSeekPreview"
    private const val SPONSOR_BLOCK = "YTSponsorBlock"
    private const val DISLIKES = "YTDislikes"
    private const val SEARCH_HISTORY = "YTSearchHistory"

    private val store: SharedPreferences
        get() = App.require().getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /**
     * Ключа нет и ключ со значением 0 неразличимы: `getInt` отвечает нулём
     * в обоих случаях. Для переключателей, у которых «по умолчанию включено»,
     * этого мало, поэтому они хранятся числом со сдвигом: 0 — не спрашивали,
     * 1 — выключено, 2 — включено.
     *
     * `getBoolean` с готовым значением по умолчанию тут не годится: у части
     * переключателей это значение зависит от железа и может измениться —
     * скажем, при переносе настроек на другое устройство, — и тогда молчание
     * пользователя истолковалось бы задним числом иначе.
     */
    private fun flag(key: String, fallback: Boolean): Boolean {
        val stored = store.getInt(key, 0)

        if (stored == 0) {
            return fallback
        }

        return stored == 2
    }

    private fun setFlag(key: String, value: Boolean) {
        store.edit().putInt(key, if (value) 2 else 1).apply()
        changed()
    }

    private fun changed() {
        Notify.post(Notify.SETTINGS)
    }

    // --- Язык -------------------------------------------------------------

    /**
     * Язык надписей самого приложения. Пусто — как в системе.
     *
     * Отдельно от языка ответов ниже, и это не придирка: смотреть ролики
     * с русскими подписями, а приложение держать на английском — обычное
     * желание, и наоборот тоже.
     */
    var interfaceLanguage: String
        get() = store.getString(INTERFACE_LANGUAGE, "") ?: ""
        set(value) {
            store.edit().putString(INTERFACE_LANGUAGE, value).apply()

            Strings.reset()

            Notify.post(Notify.LANGUAGE)
            changed()
        }

    /**
     * Язык ответов сервера — то, что уходит в `hl`. Пустая строка означает
     * «как в системе»: тогда берётся язык устройства.
     */
    var language: String
        get() = store.getString(LANGUAGE, "") ?: ""
        set(value) {
            store.edit().putString(LANGUAGE, value).apply()
            changed()
        }

    /**
     * Список перенесён из `Localization.SupportedLanguages` вместе с порядком:
     * сперва латиница по алфавиту, затем кириллица, затем письменности Азии.
     * Пары «код для hl» / «как называется на самом этом языке» — названия
     * не переводятся, ровно как в оригинале.
     */
    val languageOptions: List<Pair<String, String>> = listOf(
        "af" to "Afrikaans",
        "az" to "Azərbaycan",
        "id" to "Bahasa Indonesia",
        "ms" to "Bahasa Malaysia",
        "bs" to "Bosanski",
        "ca" to "Català",
        "cs" to "Čeština",
        "da" to "Dansk",
        "de" to "Deutsch",
        "et" to "Eesti",
        "en-IN" to "English (India)",
        "en-GB" to "English (UK)",
        "en" to "English (US)",
        "es" to "Español (España)",
        "es-419" to "Español (Latinoamérica)",
        "es-US" to "Español (US)",
        "eu" to "Euskara",
        "fil" to "Filipino",
        "fr" to "Français",
        "fr-CA" to "Français (Canada)",
        "gl" to "Galego",
        "hr" to "Hrvatski",
        "zu" to "IsiZulu",
        "is" to "Íslenska",
        "it" to "Italiano",
        "sw" to "Kiswahili",
        "lv" to "Latviešu valoda",
        "lt" to "Lietuvių",
        "hu" to "Magyar",
        "nl" to "Nederlands",
        "no" to "Norsk",
        "uz" to "O‘zbek",
        "pl" to "Polski",
        "pt-PT" to "Português",
        "pt" to "Português (Brasil)",
        "ro" to "Română",
        "sq" to "Shqip",
        "sk" to "Slovenčina",
        "sl" to "Slovenščina",
        "sr-Latn" to "Srpski",
        "fi" to "Suomi",
        "sv" to "Svenska",
        "vi" to "Tiếng Việt",
        "tr" to "Türkçe",
        "be" to "Беларуская",
        "bg" to "Български",
        "ky" to "Кыргызча",
        "kk" to "Қазақ Тілі",
        "mk" to "Македонски",
        "mn" to "Монгол",
        "ru" to "Русский",
        "sr" to "Српски",
        "uk" to "Українська",
        "el" to "Ελληνικά",
        "hy" to "Հայերեն",
        "ne" to "नेपाली",
        "mr" to "मराठी",
        "hi" to "हिन्दी",
        "as" to "অসমীয়া",
        "bn" to "বাংলা",
        "pa" to "ਪੰਜਾਬੀ",
        "gu" to "ગુજરાતી",
        "or" to "ଓଡ଼ିଆ",
        "ta" to "தமிழ்",
        "te" to "తెలుగు",
        "kn" to "ಕನ್ನಡ",
        "ml" to "മലയാളം",
        "si" to "සිංහල",
        "th" to "ภาษาไทย",
        "lo" to "ລາວ",
        "my" to "ဗမာ",
        "ka" to "ქართული",
        "am" to "አማርኛ",
        "km" to "ខ្មែរ",
        "zh-CN" to "中文 (简体)",
        "zh-TW" to "中文 (繁體)",
        "zh-HK" to "中文 (香港)",
        "ja" to "日本語",
        "ko" to "한국어"
    )

    fun languageTitle(code: String): String {
        if (code.isEmpty()) {
            return loc("Как в системе")
        }

        return languageOptions.firstOrNull { it.first == code }?.second ?: code
    }

    // --- Видео ------------------------------------------------------------

    /**
     * Предпочитаемая высота кадра: 0 — «Авто». Плеер берёт формат не выше её
     * и не выше того, что тянет устройство.
     */
    var preferredHeight: Int
        get() = store.getInt(PREFERRED_HEIGHT, 0)
        set(value) {
            store.edit().putInt(PREFERRED_HEIGHT, value).apply()
            changed()
        }

    /**
     * Предпочитаемая высота у вертикальных роликов — своя.
     *
     * Shorts почти всегда смотрят мимоходом и в дороге, а весят они при том
     * же качестве заметно больше обычного. В оригинале выбор качества
     * у Shorts тоже отдельный — своя шестерёнка в правом верхнем углу.
     */
    var shortsHeight: Int
        get() = store.getInt(SHORTS_HEIGHT, 0)
        set(value) {
            store.edit().putInt(SHORTS_HEIGHT, value).apply()
            changed()
        }

    val qualityOptions: List<Int> = listOf(0, 1080, 720, 480, 360, 240, 144)

    fun qualityTitle(height: Int): String {
        if (height <= 0) {
            return loc("Авто")
        }

        return "${height}p"
    }

    // --- Превью -----------------------------------------------------------

    /**
     * Качество превью: ширина картинки, которую просить у i.ytimg.com.
     * 0 — «Авто», то есть под размер карточки на экране.
     */
    var thumbnailWidth: Int
        get() = store.getInt(THUMBNAIL_WIDTH, 0)
        set(value) {
            store.edit().putInt(THUMBNAIL_WIDTH, value).apply()

            /**
             * Разобранные картинки больше не годятся: они сняты под прежнюю
             * ступень. Без сброса настройка не действовала бы, пока кэш сам
             * не вытеснит старое, — то есть на глаз выглядела бы сломанной.
             */
            ImageLoader.dropCache()

            changed()
        }

    /**
     * Ширины взяты у самих превью i.ytimg.com: `mqdefault` — 320,
     * `hqdefault` — 480, `sddefault` — 640. Придумывать промежуточные
     * значения смысла нет, других размеров сервер всё равно не отдаёт.
     */
    val thumbnailOptions: List<Int> = listOf(0, 640, 480, 320)

    fun thumbnailTitle(width: Int): String = when (width) {
        640 -> loc("Высокое")
        480 -> loc("Среднее")
        320 -> loc("Низкое")
        else -> loc("Авто")
    }

    // --- Поток ------------------------------------------------------------

    /**
     * Ноль — «не спрашивали» и «подача SABR» одновременно, и это ровно то,
     * что нужно: путь по умолчанию у нас первый в перечислении.
     */
    var delivery: Int
        get() = if (store.getInt(DELIVERY, 0) == Delivery.ANDROID_VR) {
            Delivery.ANDROID_VR
        } else {
            Delivery.SABR
        }
        set(value) {
            store.edit().putInt(DELIVERY, value).apply()
            changed()
        }

    val deliveryOptions: List<Int> = listOf(Delivery.SABR, Delivery.ANDROID_VR)

    // --- Язык звука -------------------------------------------------------

    /**
     * Ноль в хранилище означает «не выбирали», а не «оригинал»: у пустых
     * настроек целое всегда ноль, и отличить одно от другого нельзя.
     * Поэтому наружу значения сдвинуты на единицу — внутри лежит
     * `режим + 1`, и ноль честно читается как «ничего не выбрано».
     */
    private fun audioLanguageFor(key: String): Int {
        val stored = store.getInt(key, 0)

        if (stored < 1 || stored > AudioLanguage.ASK + 1) {
            return AudioLanguage.DEVICE_AUTHORED
        }

        return stored - 1
    }

    private fun setAudioLanguage(key: String, mode: Int) {
        store.edit().putInt(key, mode + 1).apply()

        changed()
    }

    var downloadAudioLanguage: Int
        get() = audioLanguageFor(DOWNLOAD_AUDIO_LANGUAGE)
        set(value) = setAudioLanguage(DOWNLOAD_AUDIO_LANGUAGE, value)

    var playbackAudioLanguage: Int
        get() = audioLanguageFor(PLAYBACK_AUDIO_LANGUAGE)
        set(value) = setAudioLanguage(PLAYBACK_AUDIO_LANGUAGE, value)

    val audioLanguageOptions: List<Int> = listOf(
        AudioLanguage.ORIGINAL,
        AudioLanguage.DEVICE_AUTHORED,
        AudioLanguage.DEVICE_ANY,
        AudioLanguage.ASK
    )

    fun audioLanguageTitle(mode: Int): String = when (mode) {
        AudioLanguage.ORIGINAL -> loc("Оригинал")
        AudioLanguage.DEVICE_AUTHORED -> loc("Язык устройства")
        AudioLanguage.ASK -> loc("Спрашивать каждый раз")
        else -> loc("Язык устройства, можно автодубляж")
    }

    fun audioLanguageHint(mode: Int): String = when (mode) {
        AudioLanguage.ORIGINAL ->
            loc("Та дорожка, на которой ролик сняли")

        AudioLanguage.DEVICE_AUTHORED ->
            loc(
                "Озвучка на языке устройства, если её записал сам автор. " +
                    "Синтезированный дубляж не берём; нет авторской — " +
                    "играет оригинал"
            )

        AudioLanguage.ASK ->
            loc("Выбор дорожки при каждом ролике, у которого их больше одной")

        else ->
            loc(
                "Любая дорожка на языке устройства, в том числе " +
                    "автоматический дубляж. Нет ни одной — играет оригинал"
            )
    }

    fun deliveryTitle(delivery: Int): String =
        if (delivery == Delivery.ANDROID_VR) loc("Готовые адреса") else loc("Подача SABR")

    fun deliveryHint(delivery: Int): String {
        if (delivery == Delivery.ANDROID_VR) {
            return loc(
                "Прямые ссылки от клиента шлема. Без входа, но чувствительны " +
                    "к смене адреса: через VPN раздача отказывает чаще"
            )
        }

        return loc(
            "Как у самого YouTube: поток идёт кусками по запросу. Все " +
                "качества и звуковые дорожки; нужен вход. Если не задастся — " +
                "приложение само перейдёт к готовым адресам"
        )
    }

    // --- Переключатели ----------------------------------------------------

    /** Показывать кружок канала на карточках — `ChannelIconsToggleButton`. */
    var showsChannelIcons: Boolean
        get() = flag(CHANNEL_ICONS, true)
        set(value) = setFlag(CHANNEL_ICONS, value)

    /**
     * Готовить PO-токен самостоятельно, не дожидаясь нажатия.
     *
     * WebView исполняет JavaScript страницы **на главном потоке**, а
     * программа BotGuard считает тяжело. Сама по себе она даже Tegra 3
     * по силам — по нажатию всё проходит гладко, — но вместе с загрузкой
     * главной страницы это укладывало планшет намертво. Поэтому
     * подготовка ждёт простоя, а не начинается в самый занятый миг
     * (см. `MainActivity`). Переключатель оставлен на случай, если
     * на чьём-то устройстве и отсрочки окажется мало.
     */
    var preparesPoToken: Boolean
        get() = flag(PO_AUTO, true)
        set(value) = setFlag(PO_AUTO, value)

    /** Разворачивать кадр при повороте — `AutoFullscreenLandscapeToggleButton`. */
    var autoFullscreenInLandscape: Boolean
        get() = flag(AUTO_FULLSCREEN, true)
        set(value) = setFlag(AUTO_FULLSCREEN, value)

    /**
     * Включать следующий ролик очереди, когда нынешний доиграл.
     *
     * По умолчанию включено — так ведёт себя и плейлист, и микс на самом
     * YouTube. Выключенное оставляет последний кадр с кнопкой «сначала».
     */
    var autoplayNextInQueue: Boolean
        get() = flag(AUTOPLAY_QUEUE, true)
        set(value) = setFlag(AUTOPLAY_QUEUE, value)

    /**
     * Переходить к следующему Shorts, когда нынешний доиграл.
     *
     * По умолчанию выключено: там ролик повторяется по кругу, как и в самом
     * YouTube, — Shorts листают пальцем.
     */
    var autoplayNextShort: Boolean
        get() = flag(AUTOPLAY_SHORTS, false)
        set(value) = setFlag(AUTOPLAY_SHORTS, value)

    /**
     * Убрать Shorts отовсюду разом.
     *
     * Одним переключателем, а не пятью: вертикальные ролики попадаются
     * не только на своей вкладке, но и в выдаче поиска, и полками в ленте,
     * и вперемешку с обычными на канале, в подборках и в истории.
     *
     * Что происходит при включении:
     *
     *   * вкладка Shorts уходит из нижней панели;
     *   * в поиске пропадает таблетка Shorts, а из выдачи — вертикальные;
     *   * из всех лент вертикальные отсеиваются при разборе ответа.
     */
    var hidesShorts: Boolean
        get() = flag(HIDE_SHORTS, false)
        set(value) = setFlag(HIDE_SHORTS, value)

    /** Показывать кадры при перемотке — раскадровка со storyboard. */
    var showsSeekPreview: Boolean
        get() = flag(SEEK_PREVIEW, true)
        set(value) = setFlag(SEEK_PREVIEW, value)

    /** Пропускать рекламные вставки по меткам SponsorBlock. */
    var usesSponsorBlock: Boolean
        get() = flag(SPONSOR_BLOCK, false)
        set(value) = setFlag(SPONSOR_BLOCK, value)

    /**
     * Показывать число дизлайков по Return YouTube Dislike.
     *
     * Включено сразу: ради этого числа настройку и завели. Выключают её
     * те, кто не хочет отдавать номера роликов стороннему сервису.
     */
    var showsDislikes: Boolean
        get() = flag(DISLIKES, true)
        set(value) = setFlag(DISLIKES, value)

    /**
     * Брать ли шестидесятикадровые дорожки.
     *
     * В оригинале значение по умолчанию бралось из таблицы моделей: A4 и A5
     * шестидесяти кадров не тянут. Здесь спрашиваем сам декодер — так и
     * советует записка о переносе, и это единственное место, где новая
     * платформа отвечает на вопрос, на который старая отвечать отказывалась.
     *
     * Но запрет здесь по-прежнему неуместен: тяжесть зависит и от самого
     * ролика, и от того, что ещё делает устройство, — а решать, смотреть ли
     * ценой рывков, человеку. Железо задаёт значение по умолчанию, а не потолок.
     */
    var allowsSixtyFrames: Boolean
        get() = flag(SIXTY_FRAMES, !prefersThirtyByDevice())
        set(value) = setFlag(SIXTY_FRAMES, value)

    /** Отказывается ли устройство от шестидесяти кадров без особой просьбы. */
    fun prefersThirtyByDevice(): Boolean = Streams.deviceDislikesSixtyFrames()

    // --- Субтитры ---------------------------------------------------------

    /**
     * Насколько раньше показывать субтитры, в секундах.
     *
     * Положительное — раньше. В оригинале UWP секунда (`DefaultSubtitleOffsetMs
     * = 1000`), и там же сказано зачем — реплику читают до того, как её
     * произнесут, а не после. В iOS-версии две: там поток шёл через подачу
     * и склейку в MPEG-TS, и время у плеера отставало от времени в дорожке
     * ещё примерно на секунду.
     *
     * Здесь склейки нет — ExoPlayer получает фрагменты как есть, — поэтому
     * возвращаемся к оригинальной секунде. Предел ±5: дальше это уже
     * не поправка.
     */
    var subtitleOffset: Double
        get() = if (store.contains(SUBTITLE_OFFSET)) {
            java.lang.Double.longBitsToDouble(store.getLong(SUBTITLE_OFFSET, 0))
        } else {
            1.0
        }
        set(value) {
            val clamped = value.coerceIn(-5.0, 5.0)

            store.edit()
                .putLong(SUBTITLE_OFFSET, java.lang.Double.doubleToRawLongBits(clamped))
                .apply()
        }

    /**
     * Где на экране лежит строка субтитров: доля высоты кадра от верха.
     *
     * Её двигают пальцем, и место запоминается. Доля, а не точки, — чтобы
     * при повороте строка осталась там же по смыслу, а не уехала за край.
     */
    var subtitlePlace: Double
        get() = if (store.contains(SUBTITLE_PLACE)) {
            java.lang.Double.longBitsToDouble(store.getLong(SUBTITLE_PLACE, 0))
        } else {
            0.86
        }
        set(value) {
            val clamped = value.coerceIn(0.05, 0.95)

            store.edit()
                .putLong(SUBTITLE_PLACE, java.lang.Double.doubleToRawLongBits(clamped))
                .apply()
        }

    /** То же по горизонтали: доля ширины кадра до середины строки. */
    var subtitlePlaceX: Double
        get() = if (store.contains(SUBTITLE_PLACE_X)) {
            java.lang.Double.longBitsToDouble(store.getLong(SUBTITLE_PLACE_X, 0))
        } else {
            0.5
        }
        set(value) {
            val clamped = value.coerceIn(0.05, 0.95)

            store.edit()
                .putLong(SUBTITLE_PLACE_X, java.lang.Double.doubleToRawLongBits(clamped))
                .apply()
        }

    // --- История поиска ---------------------------------------------------

    /** Сколько запросов помним — столько же, сколько оригинал. */
    private const val SEARCH_HISTORY_LIMIT = 200

    /** Чем разделены запросы в одной строке настроек. */
    private const val SEPARATOR = "\n"

    /**
     * Прежние запросы, свежие впереди.
     *
     * Хранятся одной строкой через перевод строки, а не набором:
     * `putStringSet` порядка не держит вовсе, а он здесь и есть всё
     * содержание — список показывается сверху вниз от последнего.
     */
    val searchHistory: List<String>
        get() {
            val stored = store.getString(SEARCH_HISTORY, "") ?: ""

            if (stored.isEmpty()) {
                return emptyList()
            }

            return stored.split(SEPARATOR).filter { it.isNotEmpty() }
        }

    private fun setSearchHistory(items: List<String>) {
        store.edit().putString(SEARCH_HISTORY, items.joinToString(SEPARATOR)).apply()
    }

    /** Запрос уходит в начало списка; повтор не задваивается. */
    fun rememberSearch(text: String) {
        val trimmed = text.trim()

        if (trimmed.isEmpty() || trimmed.contains(SEPARATOR)) {
            return
        }

        val items = ArrayList(searchHistory)

        items.remove(trimmed)
        items.add(0, trimmed)

        while (items.size > SEARCH_HISTORY_LIMIT) {
            items.removeAt(items.size - 1)
        }

        setSearchHistory(items)
    }

    /** Забыть один запрос — по крестику в строке. */
    fun forgetSearch(text: String) {
        val items = ArrayList(searchHistory)

        if (!items.remove(text)) {
            return
        }

        setSearchHistory(items)
    }
}
