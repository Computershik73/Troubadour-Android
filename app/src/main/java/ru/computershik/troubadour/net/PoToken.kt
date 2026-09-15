package ru.computershik.troubadour.net

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.ViewGroup
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Дверь к решателю PO-токена. За ней либо он сам, либо служба
 * в отдельном процессе — снаружи разницы нет.
 *
 * Разделение включается **не везде**, и это осознанно. Своё окно службе
 * достаётся через `TYPE_TOAST` — единственный тип, не требующий у
 * пользователя разрешения «поверх других окон». С Android 8 так делать
 * запрещено, и там пришлось бы просить разрешение — пугающее, ради
 * невидимой служебной надобности. Но и нужды там нет: беда с замиранием
 * живёт на старых неспешных устройствах, а на новых решатель считает
 * незаметно. Поэтому ниже Android 8 — отдельный процесс, выше — как
 * было, в нашем.
 */
object PoToken {

    /**
     * Заводить ли решателя отдельным процессом. Смотри пояснение к
     * самому объекту: дело не в возможностях, а в цене разрешения.
     */
    private val apart = Build.VERSION.SDK_INT < Build.VERSION_CODES.O

    // --- Обращения снаружи -------------------------------------------------

    /**
     * Окно для решателя. В раздельном случае не нужно: он заводит себе
     * своё, а нашей разметки не касается вовсе.
     */
    fun attach(parent: ViewGroup) {
        if (!apart) {
            PoSolver.attach(parent)
        }
    }

    fun prepare() {
        if (!apart) {
            PoSolver.prepare()

            return
        }

        bind()

        send(Message.obtain(null, PoService.PREPARE).also { it.replyTo = inbox })
    }

    fun isReady(): Boolean = if (apart) ready else PoSolver.isReady()

    /**
     * Токен для привязки. Зовётся из фона и ответа дожидается: токен
     * нужен прямо сейчас, а чеканка занимает миллисекунды.
     */
    fun tokenFor(binding: String?): String? {
        if (!apart) {
            return PoSolver.tokenFor(binding)
        }

        if (!ready || binding.isNullOrEmpty()) {
            return null
        }

        val latch = CountDownLatch(1)

        synchronized(this) {
            mintLatch = latch
            mintResult = null
        }

        val message = Message.obtain(null, PoService.MINT)
        val data = Bundle()

        data.putString(PoService.BINDING, binding)

        message.data = data
        message.replyTo = inbox

        send(message)

        val delivered = try {
            latch.await(WAIT, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            false
        }

        return synchronized(this) {
            mintLatch = null

            if (delivered) mintResult else null
        }
    }

    // --- Разговор со службой -----------------------------------------------

    private var outbox: Messenger? = null
    private var bound = false
    private var ready = false

    private var mintLatch: CountDownLatch? = null
    private var mintResult: String? = null

    /**
     * Сообщения, отправленные до того, как связь установилась.
     *
     * Привязка к службе идёт не мгновенно, а просьба «готовься» приходит
     * сразу при запуске. Без очереди она бы просто пропала, и подготовка
     * не началась бы вовсе.
     */
    private val waiting = ArrayList<Message>()

    private val inbox = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            PoService.READY -> {
                ready = true

                Log.d { "[YouTube/PO] Служба доложила: чеканщик поднят" }
            }

            PoService.TOKEN -> {
                val token = message.data?.getString(PoService.TOKEN_VALUE)

                synchronized(this) {
                    mintResult = token
                    mintLatch?.countDown()
                }
            }
        }

        true
    })

    private val link = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            outbox = Messenger(binder)

            Log.d { "[YouTube/PO] Служба на связи" }

            val queued = ArrayList(waiting)

            waiting.clear()

            for (message in queued) {
                send(message)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            outbox = null
            ready = false

            Log.d { "[YouTube/PO] Служба отвалилась" }
        }
    }

    private fun bind() {
        if (bound) {
            return
        }

        bound = true

        val context = App.require()

        context.bindService(
            Intent(context, PoService::class.java),
            link,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun send(message: Message) {
        val target = outbox

        if (target == null) {
            waiting.add(message)

            return
        }

        try {
            target.send(message)
        } catch (error: Throwable) {
            Log.d { "[YouTube/PO] Служба не приняла просьбу: ${error.message}" }
        }
    }

    /** Сколько ждать отчеканенный токен, секунды. */
    private const val WAIT = 4L
}
