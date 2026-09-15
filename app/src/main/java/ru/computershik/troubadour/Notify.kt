package ru.computershik.troubadour

import android.os.Handler
import android.os.Looper

/**
 * Оповещения между экранами — то, чем в оригинале служит NSNotificationCenter.
 *
 * Своё, а не LocalBroadcastManager: тому нужен Intent на каждое оповещение,
 * а их здесь десятки в секунду (ход воспроизведения, порция превью), и
 * заворачивать в Bundle нечего — подписчику нужен сам факт.
 *
 * Все вызовы приходят на главный поток: подписчики почти без исключения
 * перекрашивают виды, а трогать их с чужого потока нельзя — это не «иногда
 * мигает», это падение. Та же ловушка была и в оригинале, где KVO у
 * `AVPlayerItem.status` срабатывает на внутренней очереди AVFoundation.
 */
object Notify {

    /** Сменилась тема — открытые экраны перекрашиваются. */
    const val THEME = "theme"

    /** Изменилась любая настройка — открытые экраны перечитывают своё. */
    const val SETTINGS = "settings"

    /** Экран повернули. Значением идёт `Configuration.ORIENTATION_*`. */
    const val ORIENTATION = "orientation"

    /**
     * Системная полоса вернулась на экран.
     *
     * До Android 4.4 её возвращает первое же касание, и это касание
     * система забирает себе — приложение его не видит. Оповещение
     * заменяет потерянное касание: по нему полноэкранный вид показывает
     * пульт, как если бы человек ткнул в кадр.
     */
    const val SYSTEM_BARS = "system_bars"


    /** Сменился язык надписей — экраны пересобирают подписи. */
    const val LANGUAGE = "language"

    /** Вход выполнен, сброшен или сменился канал. */
    const val ACCOUNT = "account"

    /** Изменилась очередь скачиваний: добавилось, качается, готово. */
    const val DOWNLOADS = "downloads"

    /** Свёрнутый в окно ролик появился, исчез или сменил состояние. */
    const val MINIPLAYER = "miniplayer"

    /** Изменился список подписок — экраны с кнопкой обновляют её вид. */
    const val SUBSCRIPTIONS = "subscriptions"

    /** Пополнилась история просмотров. */
    const val HISTORY = "history"

    private val main = Handler(Looper.getMainLooper())

    private val listeners = HashMap<String, MutableList<Pair<Any, (Any?) -> Unit>>>()

    /**
     * Подписка привязана к владельцу, а не к самому замыканию: отписаться
     * от лямбды по значению нельзя, а экранов, которые надо отцепить при
     * уходе, у нас десятки.
     */
    fun on(name: String, owner: Any, action: (Any?) -> Unit) {
        synchronized(listeners) {
            listeners.getOrPut(name) { ArrayList() }.add(owner to action)
        }
    }

    /** Отписать владельца от одного оповещения. */
    fun off(name: String, owner: Any) {
        synchronized(listeners) {
            listeners[name]?.removeAll { it.first === owner }
        }
    }

    /** Отписать владельца от всех сразу — зовётся, когда экран уходит. */
    fun offAll(owner: Any) {
        synchronized(listeners) {
            for (list in listeners.values) {
                list.removeAll { it.first === owner }
            }
        }
    }

    fun post(name: String, value: Any? = null) {
        val copy: List<Pair<Any, (Any?) -> Unit>>

        synchronized(listeners) {
            copy = listeners[name]?.toList() ?: return
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (pair in copy) {
                pair.second(value)
            }

            return
        }

        main.post {
            for (pair in copy) {
                pair.second(value)
            }
        }
    }
}
