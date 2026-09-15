package ru.computershik.troubadour.ui

import android.content.Context
import ru.computershik.troubadour.safeText

/**
 * Короткое сообщение — то же, что `showNotice:` на странице ролика.
 *
 * В оригинале это своя плашка поверх кадра: `UIAlertView` там годится
 * только для вопросов, а для «вставка пропущена» нужна надпись, которая
 * уходит сама. Здесь ровно то же делает системный `Toast`, и заводить
 * своё было бы отсебятиной — вид у него на Android привычный и один
 * на все приложения.
 *
 * Отдельная обёртка нужна ради двух вещей: проверки текста (он приходит
 * и от сервера тоже) и того, чтобы показ шёл с главного потока — с чужого
 * `Toast` на старых версиях просто не появляется, молча.
 */
object Toast {

    fun show(context: Context, text: String?) {
        if (text.isNullOrEmpty()) {
            return
        }

        val safe = safeText(text)

        main {
            android.widget.Toast
                .makeText(context.applicationContext, safe, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    /** Длинное — для причин отказа, которые надо успеть прочитать. */
    fun showLong(context: Context, text: String?) {
        if (text.isNullOrEmpty()) {
            return
        }

        val safe = safeText(text)

        main {
            android.widget.Toast
                .makeText(context.applicationContext, safe, android.widget.Toast.LENGTH_LONG)
                .show()
        }
    }
}
