package ru.computershik.troubadour.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.model.VideoItem

/**
 * Экран — то же, чем в оригинале служит `UIViewController`.
 *
 * Разделы нижней панели там живут внутри одной оболочки, а переходы
 * вглубь идут стопкой `UINavigationController` с невидимой полосой:
 * шапку каждый экран рисует сам. Здесь ровно так же, и по той же причине —
 * переключение вкладки не должно терять уже загруженную ленту.
 *
 * Activity на каждый экран не годится совсем: там переход между вкладками
 * означал бы пересоздание, а свёрнутый в окно ролик пришлось бы тащить
 * сквозь все Activity разом. В оригинале эта беда решена тем, что оболочка
 * одна; здесь — тем же самым.
 */
abstract class Screen(val context: Context) {

    /** Вид экрана. Строится по первому обращению. */
    val view: FrameLayout by lazy {
        val root = FrameLayout(context)

        root.setBackgroundColor(Theme.background)

        build(root)

        root
    }

    private var built = false

    /** Собрать содержимое. Зовётся один раз. */
    protected abstract fun build(root: FrameLayout)

    /** Экран показался. */
    open fun appear() {}

    /** Экран ушёл — но остался в стопке. */
    open fun disappear() {}

    /** Экран выброшен насовсем: пора отписаться от всего. */
    open fun destroy() {
        Notify.offAll(this)
    }

    /** Сменилась тема — перекрасить то, что не перекрашивается само. */
    open fun repaint() {
        view.setBackgroundColor(Theme.background)
    }

    /**
     * Нажата «назад».
     *
     * true — экран обработал сам (закрыл меню, свернул кадр), и уходить
     * не надо.
     */
    open fun handleBack(): Boolean = false

    /** Разрешает ли экран поворот в альбомную ориентацию. */
    open fun allowsLandscape(): Boolean = false
}

/**
 * Переходы между экранами.
 *
 * Разделы не держат ссылку на навигацию, а просят её у этого объекта —
 * иначе каждому пришлось бы тащить контроллер сквозь всю цепочку
 * создания. Порт `YTNav`.
 */
object Nav {

    /** Куда класть виды экранов. Ставит [MainActivity] при запуске. */
    @Volatile
    var host: ViewGroup? = null

    private val stack = ArrayList<Screen>()

    /** Что сейчас на виду. */
    fun top(): Screen? = stack.lastOrNull()

    /** Глубина стопки: 0 — на виду оболочка. */
    fun depth(): Int = stack.size

    fun push(screen: Screen) {
        val parent = host ?: return

        stack.lastOrNull()?.let {
            it.disappear()
            it.view.visibility = View.GONE
        }

        stack.add(screen)

        parent.addView(
            screen.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        screen.appear()

        Notify.post(NAV)
    }

    fun pop() {
        val parent = host ?: return

        val leaving = stack.removeLastOrNull() ?: return

        leaving.disappear()
        leaving.destroy()

        parent.removeView(leaving.view)

        stack.lastOrNull()?.let {
            it.view.visibility = View.VISIBLE
            it.appear()
        }

        Notify.post(NAV)
    }

    /** Стоит ли этот экран в стопке. */
    fun contains(screen: Screen): Boolean = stack.contains(screen)

    /** Возврат к экрану, который уже стоит в стопке. */
    fun popTo(screen: Screen) {
        while (stack.isNotEmpty() && stack.last() !== screen) {
            pop()
        }
    }

    /** Всё убрать — при выходе из аккаунта и смене языка. */
    fun popToRoot() {
        while (stack.isNotEmpty()) {
            pop()
        }
    }

    const val NAV = "nav"

    /** Сменилась тема — сказать всем экранам стопки. */
    fun repaintAll() {
        for (screen in stack) {
            screen.repaint()
        }
    }

    /** Нажата «назад». false — уходить некуда, пусть решает система. */
    fun back(): Boolean {
        val current = stack.lastOrNull() ?: return false

        if (current.handleBack()) {
            return true
        }

        pop()

        return true
    }

    // --- Переходы, которые зовут отовсюду ---------------------------------

    /** Открыть страницу ролика — зовётся отовсюду, где есть карточка. */
    fun openVideo(videoId: String?, title: String?, playlistId: String? = null) {
        val id = videoId ?: return
        val parent = host ?: return

        Log.d { "[YouTube/Навигация] Открываем ролик $id" }

        /**
         * Окошко с прежним роликом закрываем.
         *
         * Двух воспроизведений разом всё равно не бывает — плеер один,
         * — и оставленное окно показывало бы застывший кадр, притворяясь
         * живым, да ещё и уводило бы по тычку не туда.
         */
        closeMiniPlayer()

        push(PlayerScreen(parent.context, id, title ?: "", playlistId))
    }

    /** Убирает окошко мини-плеера, если оно открыто. */
    fun closeMiniPlayer() {
        if (ru.computershik.troubadour.player.MiniPlayer.isOpen()) {
            ru.computershik.troubadour.player.MiniPlayer.close()
        }
    }

    /**
     * Открыть вертикальный ролик листалкой, начав ленту с него.
     *
     * Карточка передаётся целиком: в ней и пропуск на ленту вокруг ролика,
     * и подписи с превью, которые листалка покажет, пока лента едет.
     */
    fun openShort(item: VideoItem) {
        val parent = host ?: return

        closeMiniPlayer()

        push(ShortsScreen(parent.context, item))
    }

    fun openChannel(channelId: String?, title: String?) {
        val id = channelId ?: return
        val parent = host ?: return

        push(ChannelScreen(parent.context, id, title ?: ""))
    }

    fun openPlaylist(playlistId: String?, title: String?) {
        val id = playlistId ?: return
        val parent = host ?: return

        push(PlaylistScreen(parent.context, id, title ?: ""))
    }

    /**
     * Переключить нижнюю панель на раздел.
     *
     * Нужно там, где один раздел отсылает к другому, — например, «Подписки»
     * невошедшего отправляют на «Моё», где живёт вход.
     */
    fun selectTab(index: Int) {
        Notify.post(SELECT_TAB, index)
    }

    const val SELECT_TAB = "select-tab"
}

/** `removeLastOrNull` появился в Kotlin 1.4; у нас 1.6, но список свой. */
private fun <T> ArrayList<T>.removeLastOrNull(): T? =
    if (isEmpty()) null else removeAt(size - 1)
