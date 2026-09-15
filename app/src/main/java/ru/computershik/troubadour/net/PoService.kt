package ru.computershik.troubadour.net

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import ru.computershik.troubadour.Log

/**
 * Решатель PO-токена, живущий отдельным процессом.
 *
 * Затеяно это ради одной вещи: WebView исполняет JavaScript страницы
 * **на главном потоке своего процесса**, а программа BotGuard считает
 * тяжело и написана не нами. Пока решатель жил в нашем процессе, его
 * счёт делил поток с рисованием окна — и на Tegra 3 планшет вставал
 * намертво прямо на главной странице. Отсрочками это можно смягчить,
 * но не убрать: чужой тяжёлый код в нашем потоке останется чужим
 * тяжёлым кодом.
 *
 * Здесь у него свой процесс и свой главный поток. Замрёт — замрёт
 * только он; рисование к тому времени идёт в другом процессе и об этом
 * не знает. Заодно и падение WebView, на старых системах не редкость,
 * больше не уносит приложение целиком.
 *
 * Разговор идёт через [Messenger] — простой обмен сообщениями, без
 * описаний на AIDL: ходят через границу три вещи, и заводить ради них
 * отдельный язык незачем.
 */
class PoService : Service() {

    private val inbox = Messenger(Handler { message ->
        when (message.what) {
            PREPARE -> {
                back = message.replyTo

                PoSolver.attachHere(this)
                PoSolver.prepare()

                watchReady()
            }

            MINT -> mint(message)
        }

        true
    })

    /** Куда отвечать: сюда прислал тот, кто попросил. */
    private var back: Messenger? = null

    /** Уже сказали, что готовы. */
    private var told = false

    override fun onBind(intent: Intent?): IBinder = inbox.binder

    /**
     * Готовность приходит не сообщением, а сменой состояния внутри
     * решателя, поэтому за ней приглядываем. Проверка редкая: подготовка
     * идёт секунды, и частить тут нечем.
     */
    private fun watchReady() {
        val handler = Handler()

        handler.postDelayed(object : Runnable {
            override fun run() {
                if (PoSolver.isReady()) {
                    if (!told) {
                        told = true

                        try {
                            back?.send(Message.obtain(null, READY))
                        } catch (ignored: Throwable) {
                            // Спросивший ушёл — отвечать некому.
                        }
                    }

                    return
                }

                handler.postDelayed(this, WATCH)
            }
        }, WATCH)
    }

    /**
     * Чеканка идёт в своём потоке, а не здесь.
     *
     * [PoSolver.tokenFor] дожидается ответа страницы, а ответ приходит
     * на главный поток этого процесса. Позови мы её отсюда — ждали бы
     * сами себя.
     */
    private fun mint(message: Message) {
        val binding = message.data?.getString(BINDING)
        val answer = message.replyTo ?: return

        Thread {
            val token = PoSolver.tokenFor(binding)

            val reply = Message.obtain(null, TOKEN)
            val data = Bundle()

            data.putString(TOKEN_VALUE, token)
            reply.data = data

            try {
                answer.send(reply)
            } catch (error: Throwable) {
                Log.d { "[YouTube/PO] Ответ не ушёл: ${error.message}" }
            }
        }.start()
    }

    companion object {
        /** Начать подготовку. */
        const val PREPARE = 1

        /** Отчеканить токен для привязки. */
        const val MINT = 2

        /** Подготовка удалась. */
        const val READY = 3

        /** Готовый токен. */
        const val TOKEN = 4

        const val BINDING = "binding"
        const val TOKEN_VALUE = "token"

        /** Как часто заглядывать, не готов ли решатель, мс. */
        private const val WATCH = 500L
    }
}
