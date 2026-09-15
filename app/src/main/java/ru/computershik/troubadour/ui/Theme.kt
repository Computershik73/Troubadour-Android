package ru.computershik.troubadour.ui

import android.content.Context
import android.content.res.Configuration
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.loc

/**
 * Светлая и тёмная темы плюс вся палитра.
 *
 * Значения перенесены из `App.xaml` версии для UWP один в один, вместе
 * с именами: там они лежат в двух `ResourceDictionary` — `Dark` и `Light`, —
 * и здесь получаются двумя ветками одного метода. Числа не пересчитывались:
 * в XAML цвета заданы в #RRGGBB, а размеры — в эффективных пикселях, что
 * для Android означает те же самые независимые точки.
 *
 * Устройство переключателя от UWP отличается. Там тему подставляла сама
 * система: `ThemeResource` разрешается на лету, и при смене `RequestedTheme`
 * все кисти перечитываются. Здесь цвет спрашивается у этого объекта в момент
 * отрисовки, а при смене темы экраны перекрашиваются по оповещению.
 *
 * Через `values-night` так не выйдет, и это не лень: тему выбирает
 * пользователь в настройках приложения, а квалификатор ресурсов смотрит
 * на системный флаг. Подменять конфигурацию ради него — приём рабочий,
 * но он перекрашивает и то, что перекрашивать нельзя (кадр плеера всегда
 * тёмный), и требует пересоздания экрана там, где в оригинале хватало
 * перерисовки.
 *
 * Отсюда же правило, которое стоит держать в голове по всему проекту:
 * **всё, что зависит от темы, назначается там же, где данные** — в `bind`
 * ячейки, а не в её конструкторе. Ячейки живут в пуле переработки и смену
 * темы переживают: `notifyDataSetChanged` их не пересоздаёт,
 * а перепривязывает, и взятый однажды цвет так и остался бы прежним.
 */
object Theme {

    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    private const val STORE = "troubadour"
    private const val KEY = "yt_theme"

    var mode: String
        get() {
            val saved = App.require()
                .getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .getString(KEY, SYSTEM)

            return if (saved == LIGHT || saved == DARK) saved else SYSTEM
        }
        set(value) {
            App.require()
                .getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, value)
                .apply()

            // Набор значков меняется целиком — светлый на тёмный.
            Icons.drop()

            Notify.post(Notify.THEME)
        }

    fun titleForMode(mode: String): String = when (mode) {
        LIGHT -> loc("Светлая")
        DARK -> loc("Тёмная")
        else -> loc("Как в системе")
    }

    /**
     * Тёмная ли тема сейчас — с учётом выбора пользователя и системы.
     *
     * «Как в системе» до Android 10 всегда означает тёмную: системного
     * ночного режима там нет, а YouTube по умолчанию тёмный — в UWP-версии
     * стоит ровно то же, и все семь снимков экрана в её README сделаны
     * в темноте. Ровно так же решала и версия для iOS: спросить систему
     * можно было только с iOS 13, а до неё ответ — «тёмная».
     */
    val isDark: Boolean
        get() {
            when (mode) {
                LIGHT -> return false
                DARK -> return true
            }

            val configuration = App.context?.resources?.configuration ?: return true

            val night = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

            return when (night) {
                Configuration.UI_MODE_NIGHT_NO -> false
                Configuration.UI_MODE_NIGHT_YES -> true
                // UI_MODE_NIGHT_UNDEFINED — системе нечего сказать.
                else -> true
            }
        }

    // --- Фирменные цвета --------------------------------------------------

    /** Красный значка YouTube. В темноте не меняется: по нему приложение узнаётся. */
    const val BRAND_RED = 0xFFFF0000.toInt()

    /**
     * Голубой ссылок и кнопки «Повторить» — `#3EA6FF` из `Home.xaml`.
     * Тоже одинаковый в обеих темах: в оригинале он записан числом, а не кистью.
     */
    const val ACCENT_BLUE = 0xFF3EA6FF.toInt()

    // --- Палитра App.xaml -------------------------------------------------

    /** `AppBackgroundColor` — фон страницы. */
    val background: Int get() = if (isDark) 0xFF0F0F0F.toInt() else 0xFFFFFFFF.toInt()

    /** `AppSurfaceColor` — подложка «таблеток» категорий и кнопок под роликом. */
    val surface: Int get() = if (isDark) 0xFF272727.toInt() else 0xFFF2F2F2.toInt()

    /** `AppSurfaceAltColor` — место превью и карточка комментариев. */
    val surfaceAlt: Int get() = if (isDark) 0xFF1A1A1A.toInt() else 0xFFF7F7F7.toInt()

    /** `AppSurfaceHoverColor` — нажатое состояние. */
    val surfaceHover: Int get() = if (isDark) 0xFF222222.toInt() else 0xFFE8E8E8.toInt()

    /** `AppPrimaryTextColor` — название ролика, заголовки. */
    val primaryText: Int get() = if (isDark) 0xFFFFFFFF.toInt() else 0xFF0F0F0F.toInt()

    /** `AppSecondaryTextColor` — подписи, число подписчиков. */
    val secondaryText: Int get() = if (isDark) 0xFFAAAAAA.toInt() else 0xFF606060.toInt()

    /** `AppMutedTextColor` — строка «автор • просмотры • давность» под названием. */
    val mutedText: Int get() = if (isDark) 0xFF888888.toInt() else 0xFF777777.toInt()

    /** `AppDividerColor` — разделители и полоса над нижней панелью. */
    val divider: Int get() = if (isDark) 0xFF222222.toInt() else 0xFFE5E5E5.toInt()

    /** `VideoPlaceholderColor` — фон кадра плеера. */
    val videoPlaceholder: Int get() = if (isDark) 0xFF000000.toInt() else 0xFFCECECE.toInt()

    /** `AvatarPlaceholderColor` — кружок канала, пока картинка не пришла. */
    val avatarPlaceholder: Int get() = if (isDark) 0xFF333333.toInt() else 0xFFD9D9D9.toInt()

    /** `LoadingRingColor` — кольцо ожидания. */
    val loadingRing: Int get() = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

    /**
     * `PrimaryActionBackgroundColor` / `PrimaryActionForegroundColor` — заливка
     * главных кнопок («Подписаться», выбранная «таблетка») и цвет подписи
     * на них. Отдельно от primaryText: в тёмной теме основной текст белый,
     * и белая подпись на белой заливке пропала бы.
     */
    val primaryActionBackground: Int get() = if (isDark) 0xFFF1F1F1.toInt() else 0xFF0F0F0F.toInt()
    val primaryActionForeground: Int get() = if (isDark) 0xFF0F0F0F.toInt() else 0xFFFFFFFF.toInt()

    /**
     * Плашка длительности поверх превью — `#CC000000` из шаблона карточки.
     * Она одинакова в обеих темах: лежит поверх кадра, а не поверх страницы.
     */
    const val BADGE = 0xCC000000.toInt()

    /**
     * Светлые ли значки строки состояния нужны при этой теме.
     *
     * Ответ обратный тому, что даёт `statusBarStyle` в оригинале, только
     * по-другому названный: там светлый стиль просят на тёмном фоне,
     * здесь — снимают признак «тёмные значки».
     */
    val wantsLightStatusBar: Boolean get() = isDark
}
