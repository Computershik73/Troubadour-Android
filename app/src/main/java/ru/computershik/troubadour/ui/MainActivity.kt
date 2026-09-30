package ru.computershik.troubadour.ui

import android.os.Looper
import ru.computershik.troubadour.Settings
import android.annotation.TargetApi
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.net.PoToken
import ru.computershik.troubadour.player.MiniPlayer
import ru.computershik.troubadour.player.NowPlaying
import ru.computershik.troubadour.player.PlayerEngine

/**
 * Единственный экран приложения.
 *
 * Разделы нижней панели живут внутри одной оболочки, переходы вглубь —
 * стопкой видов над ней, свёрнутый в окно ролик — над всем. В оригинале
 * ровно так же: `Home.xaml` в UWP и `YTShellViewController` под невидимым
 * `UINavigationController` в iOS.
 *
 * Activity на каждый экран не годится совсем. Переключение вкладки
 * означало бы пересоздание и потерю загруженной ленты; свёрнутый ролик
 * пришлось бы тащить сквозь все Activity разом; поворот на странице
 * видео обрывал бы воспроизведение. Всё это в оригинале решено тем,
 * что оболочка одна, — здесь тем же самым.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var shell: Shell
    private lateinit var stack: FrameLayout

    /** Какой была тема на момент сборки экрана. */
    private var dark = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dark = Theme.isDark

        root = FrameLayout(this)

        root.setBackgroundColor(Theme.background)

        shell = Shell(this)

        root.addView(
            shell,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        /**
         * Стопка экранов лежит **над** оболочкой, а не вместо неё.
         *
         * Так же и в оригинале: переходы идут через навигацию, но нижняя
         * панель под ними никуда не девается — экран, открытый поверх,
         * просто закрывает её собой.
         */
        stack = FrameLayout(this)

        root.addView(
            stack,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        Nav.host = stack

        // Мини-плеер — поверх всего, включая стопку.
        MiniPlayer.attach(root)

        /**
         * Решателю PO-токена нужно окно: без кадров программа не идёт.
         * Поэтому и заводится он здесь, а не в `App`, — тот поднимается
         * раньше любого окна.
         */
        PoToken.attach(root)

        /**
         * Подготовка ждёт, пока главный поток освободится.
         *
         * Запускать её здесь же нельзя: ровно в этот миг грузится и
         * раскладывается главная страница, а считает программа BotGuard
         * в том же самом потоке. На Tegra 3 два этих дела вместе вешали
         * планшет намертво — по нажатию, на свободном потоке, та же
         * подготовка проходит без запинки.
         *
         * `addIdleHandler` срабатывает, когда очередь опустела, то есть
         * первый кадр уже нарисован; секунда сверху — на случай, если
         * очередь опустеет на миг между двумя порциями работы.
         */
        if (Settings.preparesPoToken) {
            Looper.myQueue().addIdleHandler {
                mainAfter(PO_DELAY) {
                    if (Settings.preparesPoToken) {
                        PoToken.prepare()
                    }
                }

                false
            }
        }

        setContentView(root)

        applySystemBars()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(pipReceiver, IntentFilter(ACTION_PIP_TOGGLE))
        }

        Notify.on(Notify.THEME, this) {
            root.setBackgroundColor(Theme.background)

            applySystemBars()

            Nav.repaintAll()
        }

        /**
         * Смена языка перестраивает всё разом.
         *
         * Подписи собраны в момент создания видов, и перечитывать их
         * по одной пришлось бы в трёх десятках мест. Оригинал поступал
         * так же — там смена языка пересоздавала оболочку целиком.
         */
        Notify.on(Notify.LANGUAGE, this) { recreate() }

        /**
         * Пока что-нибудь играет, экран не гаснет.
         *
         * Держим это здесь, а не на странице ролика, и вот почему.
         * Плеер в приложении один, а показывают его трое: страница
         * ролика, листалка Shorts и окошко мини-плеера. Флаг же —
         * свойство окна, и окно тоже одно. Прежде его ставила одна
         * страница ролика: в Shorts экран гас посреди просмотра,
         * а свернув ролик в окошко, человек получал то же самое —
         * уходя, страница флаг снимала.
         *
         * `FLAG_KEEP_SCREEN_ON` закрывает и угасание: система приглушает
         * подсветку перед тем, как погасить экран, и с этим флагом
         * не делает ни того, ни другого. А вот самочинную подстройку
         * яркости по датчику освещённости он не отменяет — ею
         * распоряжается система, и снаружи её не запретить.
         */
        Notify.on(PlayerEngine.STATE, this) { applyKeepAwake() }

        Notify.on(PlayerEngine.FIRST_FRAME, this) { applyKeepAwake() }

        handleIntent(intent)

        /**
         * Недокачанное продолжаем при запуске.
         *
         * Приложение снимают посреди загрузки — сами ли, система ли ради
         * памяти, — и запись возвращается в очередь. Прежде она там
         * и лежала: разбудить службу было некому, кроме нового нажатия
         * «скачать». Файл при этом цел, и загрузка продолжается с того же
         * места, а не заново.
         */
        if (ru.computershik.troubadour.net.Downloads.next() != null) {
            ru.computershik.troubadour.net.DownloadService.wake()
        }

        /** Скачанное прежним порядком переезжает в общие «Загрузки». */
        ru.computershik.troubadour.net.Downloads.migrate()
    }

    companion object {

        /** Повод «покажи скачанное» — им пользуется уведомление службы. */
        const val ACTION_DOWNLOADS = "ru.computershik.troubadour.downloads"
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        handleIntent(intent)
    }

    /**
     * Ссылка на ролик, открытая снаружи.
     *
     * В оригинале этого нет: приложение, установленное мимо App Store,
     * ссылки на себя не забирает — для этого нужна регистрация схемы,
     * которую LaunchServices у такой связки не читает. Здесь достаточно
     * строчки в манифесте, и не воспользоваться этим было бы странно.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_DOWNLOADS) {
            Nav.push(DownloadsScreen(this))

            return
        }

        val data = intent?.data ?: return

        val videoId = when {
            data.host?.endsWith("youtu.be") == true ->
                data.pathSegments.firstOrNull()

            data.path == "/watch" -> data.getQueryParameter("v")

            data.path?.startsWith("/shorts/") == true ->
                data.pathSegments.getOrNull(1)

            /**
             * Ссылка на трансляцию выглядит иначе: `/live/<ролик>`.
             *
             * Её даёт сама кнопка «Поделиться» у эфира, и по ней же
             * приходят люди из чатов. Прежде такая ссылка открывала
             * приложение на главной — «эфир не играет» начиналось
             * ровно здесь, ещё до всякого плеера.
             *
             * Заодно берём и старые виды адреса: `/embed/` встречается
             * в пересказах статей, `/v/` — в записях десятилетней
             * давности, а `/shorts/` уже был.
             */
            data.path?.startsWith("/live/") == true ||
                data.path?.startsWith("/embed/") == true ||
                data.path?.startsWith("/v/") == true ->
                data.pathSegments.getOrNull(1)

            else -> null
        }

        if (videoId.isNullOrEmpty()) {
            return
        }

        Log.d { "[YouTube/Навигация] Ссылка снаружи: $videoId" }

        val start = data.getQueryParameter("t")?.filter { it.isDigit() }?.toIntOrNull()

        Nav.openVideo(videoId, "", data.getQueryParameter("list"))

        if (start != null && start > 0) {
            mainAfter(1500) { PlayerEngine.seekTo(start.toDouble()) }
        }
    }

    /**
     * Строка состояния под тему.
     *
     * На тёмном фоне нужны светлые значки, на светлом — тёмные. Признак
     * «тёмные значки» появился в API 23; до него строка состояния всегда
     * со светлыми, и это как раз то, что нужно тёмной теме, а светлой
     * приходится мириться.
     */
    private fun applySystemBars() {
        window.decorView.systemUiVisibility = baseSystemUi()
    }

    /**
     * Обычное состояние системных панелей — то, к чему возвращаемся
     * после полноэкранного вида.
     *
     * С Android 5 строка состояния объявлена прозрачной (см.
     * `values-v21/styles.xml`), но одного этого мало: без
     * `LAYOUT_FULLSCREEN` система по-прежнему сдвигает окно вниз, и под
     * прозрачной строкой оказывается не наша панель, а фон окна — а наши
     * панели отмеряют её высоту ещё раз, и выходит двойной отступ.
     * Полосу навигации это не трогает: нижняя панель вкладок стоит над
     * ней, как и должна.
     *
     * До Android 5 флага нет и рисовать под строкой нечем — там окно
     * живёт под ней, а [statusBarHeight] отдаёт ноль.
     */
    private fun baseSystemUi(): Int {
        var flags = View.SYSTEM_UI_FLAG_VISIBLE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }

        /**
         * Тёмные значки в строке состояния — с API 23; до него она
         * всегда со светлыми, и это как раз то, что нужно тёмной теме,
         * а светлой приходится мириться.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Theme.wantsLightStatusBar) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        return flags
    }

    /**
     * Полноэкранный кадр: убрать строку состояния и кнопки.
     *
     * Зовётся страницей ролика. Флаг `IMMERSIVE_STICKY` появился
     * в API 19; на 16–18 довольствуемся `HIDE_NAVIGATION`, который
     * возвращает панели при первом же касании, — там иначе нельзя.
     */
    fun applyImmersive(immersive: Boolean) {
        val decor = window.decorView

        wantsImmersive = immersive

        watchSystemBars(decor)

        if (!immersive) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

            applySystemBars()

            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        /**
         * Спрятать панели мало — надо ещё занять их место.
         *
         * `FULLSCREEN` и `HIDE_NAVIGATION` убирают строку состояния
         * и кнопки, но раскладка окна остаётся прежней: там, где были
         * кнопки, зияет полоса цвета окна. На телефоне в горизонтальном
         * виде полоса эта сбоку от кадра и хорошо заметна. Место
         * отдают спутники `LAYOUT_*`; они есть с API 16, то есть на всём
         * нашем диапазоне.
         */
        var flags = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        decor.systemUiVisibility = flags
    }

    /** Просили ли мы сейчас полноэкранный вид. */
    private var wantsImmersive = false

    private var watchingBars = false

    /**
     * Следим за системными полосами.
     *
     * До Android 4.4 нет `IMMERSIVE_STICKY`, и полоса возвращается
     * от первого же касания в полноэкранном виде. Касание это система
     * забирает себе: приложение его не видит вовсе, и человеку кажется,
     * что пульт не отзывается — на деле пропадает каждое второе нажатие.
     *
     * Поэтому возвращение полосы мы считаем тем самым касанием
     * и показываем по нему пульт. Прятать её обратно по таймеру нельзя:
     * тогда следующее нажатие снова пропадёт. Полоса уходит вместе
     * с пультом — об этом просит сама страница ролика.
     */
    private fun watchSystemBars(decor: View) {
        if (watchingBars) {
            return
        }

        watchingBars = true

        decor.setOnSystemUiVisibilityChangeListener { flags ->
            val hidden = (flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0

            if (!wantsImmersive || hidden) {
                return@setOnSystemUiVisibilityChangeListener
            }

            Notify.post(Notify.SYSTEM_BARS, true)
        }
    }

    /** Экран не гаснет, пока идёт видео. */
    /** Сверяет флаг с тем, идёт ли показ. */
    fun applyKeepAwake() {
        keepAwake(PlayerEngine.holdsScreen)
    }

    fun keepAwake(keep: Boolean) {
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onBackPressed() {
        if (MiniPlayer.handleBack()) {
            return
        }

        if (Nav.back()) {
            return
        }

        super.onBackPressed()
    }

    /**
     * Поворот обрабатываем сами — в манифесте он объявлен
     * в `configChanges`.
     *
     * Пересоздание экрана оборвало бы воспроизведение: плеер живёт
     * в [PlayerEngine], но поверхность, на которую он рисует, — во виде,
     * а вид при пересоздании умирает. В оригинале этой беды нет вовсе:
     * там поворот не пересоздаёт контроллер.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        root.requestLayout()

        Notify.post(Nav.NAV)

        // Кадр разворачивается на весь экран сам — см. `PlayerScreen`.
        Notify.post(Notify.ORIENTATION, newConfig.orientation)
    }

    override fun onResume() {
        super.onResume()

        /**
         * Тему могли сменить, пока экран был в фоне, — например,
         * системную. Пересобирать всё не надо: перекрашиваемся.
         */
        if (dark != Theme.isDark) {
            dark = Theme.isDark

            shell.applyTheme()

            Nav.repaintAll()

            applySystemBars()
        }

        Nav.top()?.appear()

        /**
         * Вернулись из фона — флаг мог не пережить ухода, а показ идёт.
         */
        applyKeepAwake()

        // Окно развернули обратно на весь экран — оно больше не наше дело.
        leftInPip = false
    }

    override fun onStop() {
        super.onStop()

        /**
         * Окно смахнули, а не развернули.
         *
         * Развёрнутое окно сначала возвращает экран (`onResume`) и снимает
         * пометку, смахнутое — сразу останавливает его. Играть дальше
         * в никуда незачем: так же поступает и YouTube.
         */
        if (leftInPip) {
            leftInPip = false

            PlayerEngine.pause()
            NowPlaying.update()
        }
    }

    override fun onPause() {
        super.onPause()

        Nav.top()?.disappear()
    }

    override fun onDestroy() {
        super.onDestroy()

        Notify.offAll(this)
        Notify.offAll(pipOwner)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                unregisterReceiver(pipReceiver)
            } catch (error: Throwable) {
            }
        }

        Nav.host = null

        /**
         * Плеер отпускаем только если экран уходит насовсем.
         *
         * `isFinishing` отличает закрытие от пересоздания: при повороте
         * Activity умирает и рождается заново, и глушить звук при этом
         * было бы неверно.
         */
        if (isFinishing) {
            PlayerEngine.release()

            MiniPlayer.close()
        }
    }

    // --- Картинка в картинке ---------------------------------------------

    /**
     * Ролик в мини-окне поверх других приложений, пока наше свёрнуто.
     *
     * На Android 8 и новее: раньше такого режима у телефонов нет. Окно
     * открывается само, когда человек уходит из приложения (кнопкой
     * «Домой» или жестом), а ролик в этот миг идёт. На паузе — нет:
     * держать на экране стоящий кадр незачем.
     *
     * В окне остаётся только кадр. Всё остальное — ленты, панели, пульт —
     * в окошке размером с почтовую марку не нужно, поэтому поверх всего
     * встаёт чёрная подложка со своей поверхностью, и плеер на время
     * рисует в неё. Экраны под ней не трогаем: вернётся человек —
     * подложка уйдёт, и всё окажется ровно там, где было.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        enterPipIfPlaying()
    }

    private fun enterPipIfPlaying() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        if (PlayerEngine.videoId == null || !PlayerEngine.holdsScreen ||
            PlayerEngine.attachedSurface == null
        ) {
            return
        }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return
        }

        try {
            enterPictureInPictureMode(pipParams())
        } catch (error: Throwable) {
            // Режим бывает запрещён человеком в настройках системы.
            Log.d { "[YouTube/Окно] Картинка в картинке не открылась: ${error.message}" }
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun pipParams(): PictureInPictureParams {
        // Система принимает пропорции окна от 1:2,39 до 2,39:1.
        val ratio = PlayerEngine.videoRatio.coerceIn(0.42f, 2.39f)

        val playing = PlayerEngine.holdsScreen
        val label = if (playing) loc("Пауза") else loc("Играть")

        val toggle = PendingIntent.getBroadcast(
            this, 10,
            Intent(ACTION_PIP_TOGGLE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val action = RemoteAction(
            Icon.createWithResource(
                this,
                if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            ),
            label, label, toggle
        )

        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setActions(listOf(action))
            .build()
    }

    /** Кнопка в окне меняет вид вместе с плеером. */
    private fun updatePipActions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isInPictureInPictureMode) {
            return
        }

        try {
            setPictureInPictureParams(pipParams())
        } catch (error: Throwable) {
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

        if (isInPictureInPictureMode) {
            showPipFrame()
        } else {
            hidePipFrame()
        }
    }

    private fun showPipFrame() {
        leftInPip = true

        if (pipBox != null) {
            return
        }

        val box = FrameLayout(this)

        box.setBackgroundColor(Color.BLACK)

        val texture = TextureView(this)

        box.addView(
            texture,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            box,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        pipBox = box
        pipSurface = texture
        surfaceBeforePip = PlayerEngine.attachedSurface

        PlayerEngine.attach(texture)

        Notify.on(PlayerEngine.STATE, pipOwner) { updatePipActions() }

        Log.d { "[YouTube/Окно] Ролик ушёл в окно поверх других приложений" }
    }

    private fun hidePipFrame() {
        val box = pipBox ?: return

        Notify.offAll(pipOwner)

        /**
         * Возвращаем плееру прежнюю поверхность — но только если за это
         * время её никто не сменил. Сменить могли: следующий ролик
         * подборки открывается своим экраном и берёт свою поверхность.
         */
        if (PlayerEngine.attachedSurface === pipSurface) {
            PlayerEngine.attach(surfaceBeforePip)
        }

        root.removeView(box)

        pipBox = null
        pipSurface = null
        surfaceBeforePip = null

        Log.d { "[YouTube/Окно] Окно закрыто" }
    }

    /** Подложка с кадром, пока приложение в окне. */
    private var pipBox: FrameLayout? = null
    private var pipSurface: TextureView? = null

    /** Куда плеер рисовал до окна — туда он и вернётся. */
    private var surfaceBeforePip: TextureView? = null

    /** Окно открывали, а на весь экран приложение с тех пор не возвращалось. */
    private var leftInPip = false

    /** Хозяин подписок окна — чтобы снимать их, не трогая остальные. */
    private val pipOwner = Any()

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            PlayerEngine.togglePlay()

            NowPlaying.update()

            updatePipActions()
        }
    }
}

/** Кнопка «играть/пауза» в окне картинки в картинке. */
private const val ACTION_PIP_TOGGLE = "ru.computershik.troubadour.PIP_TOGGLE"

/** Сколько ждать после простоя, прежде чем браться за PO-токен. */
private const val PO_DELAY = 1000L
