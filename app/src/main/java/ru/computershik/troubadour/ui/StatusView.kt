package ru.computershik.troubadour.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.ui.Metrics.dp
import ru.computershik.troubadour.ui.Metrics.dpf

/**
 * Состояние экрана вместо содержимого: кольцо ожидания либо сообщение.
 *
 * Порт `OfflinePanel` из Home.xaml, числа оттуда же:
 *
 *     кольцо      42×42 по центру
 *     картинка    170×170
 *     заголовок   22 SemiBold, AppPrimaryText, по центру
 *     пояснение   16 Regular, AppSecondaryText, по центру
 *     кнопка      высота 44, ширина от 150, поля 18×8, скругление 22,
 *                 голубая #3EA6FF, подпись белая
 *     отступы     картинка +20 заголовок +8 пояснение +22 кнопка
 *     ширина      текста — не больше 340 и не больше (ширина − 64)
 *
 * Разметка руками, как и в оригинале. Первый заход был собран из вложенных
 * контейнеров, и обе беды вышли оттуда: колонка получилась шириной
 * с картинку, отчего «Нет подключения к интернету» показалось как «Нет»,
 * а кнопка растянулась во весь экран.
 */
class StatusView(context: Context) : ViewGroup(context) {

    private val ring = LoadingRing(context)
    private val picture = ImageView(context)

    private val title = label(context, Fonts.semiBold, 22f, Theme.primaryText, 0)
    private val hint = label(context, Fonts.regular, 16f, Theme.secondaryText, 0)

    private val buttonFill = PillView(context)
    private val buttonTitle = label(context, Fonts.semiBold, 15f, Color.WHITE, 1)

    private var action: (() -> Unit)? = null

    init {
        picture.scaleType = ImageView.ScaleType.FIT_CENTER
        picture.visibility = GONE

        title.gravity = Gravity.CENTER
        hint.gravity = Gravity.CENTER
        buttonTitle.gravity = Gravity.CENTER

        buttonFill.cornerRadius = dpf(22f)
        buttonFill.fillColor = Theme.ACCENT_BLUE

        buttonFill.isClickable = true
        buttonFill.setOnClickListener { action?.invoke() }

        addView(ring)
        addView(picture)
        addView(title)
        addView(hint)
        addView(buttonFill)
        addView(buttonTitle)

        visibility = GONE
    }

    fun showBusy() {
        visibility = VISIBLE

        picture.visibility = GONE
        title.visibility = GONE
        hint.visibility = GONE
        buttonFill.visibility = GONE
        buttonTitle.visibility = GONE

        ring.color = Theme.loadingRing
        ring.start()
    }

    /** Просто сообщение — по центру, без картинки и кнопки. */
    fun showMessage(message: String) {
        visibility = VISIBLE

        ring.stop()

        picture.visibility = GONE
        hint.visibility = GONE
        buttonFill.visibility = GONE
        buttonTitle.visibility = GONE

        title.visibility = VISIBLE
        title.setTextColor(Theme.primaryText)
        title.text = message

        requestLayout()
    }

    /** Полный вид отказа: картинка, заголовок, пояснение и кнопка. */
    fun showOffline(
        titleText: String,
        hintText: String,
        actionTitle: String,
        onAction: (() -> Unit)?
    ) {
        visibility = VISIBLE

        ring.stop()

        // Цвета берём здесь, а не в конструкторе: вид переживает смену темы.
        title.setTextColor(Theme.primaryText)
        hint.setTextColor(Theme.secondaryText)

        buttonFill.fillColor = Theme.ACCENT_BLUE

        val failed = Icons.image("failed_loading")

        picture.setImageBitmap(failed)
        picture.visibility = if (failed != null) VISIBLE else GONE

        title.visibility = VISIBLE
        title.text = titleText

        hint.text = hintText
        hint.visibility = if (hintText.isEmpty()) GONE else VISIBLE

        action = onAction

        buttonTitle.text = actionTitle

        val shows = onAction != null && actionTitle.isNotEmpty()

        buttonFill.visibility = if (shows) VISIBLE else GONE
        buttonTitle.visibility = buttonFill.visibility

        requestLayout()
    }

    fun hide() {
        ring.stop()

        visibility = GONE
    }

    /** Показать «нет подключения» — самый частый случай отказа. */
    fun showNoConnection(retry: () -> Unit) {
        showOffline(
            loc("Нет подключения к интернету"),
            loc("Проверьте подключение и попробуйте снова"),
            loc("Повторить"),
            retry
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        val ringSide = dp(42f)

        ring.frame(width / 2 - ringSide / 2, height / 2 - ringSide / 2, ringSide, ringSide)

        if (picture.visibility == GONE) {
            // Просто сообщение — по центру, без картинки и кнопки.
            title.frame(dp(24f), height / 2 - dp(40f), width - dp(48f), dp(80f))

            return
        }

        var pictureSide = dp(170f)

        val textWidth = minOf(dp(340f), width - dp(64f))

        val titleHeight = Metrics.textHeight(
            title.text.toString(), Fonts.semiBold, 22f, textWidth, 0
        )

        val hintHeight = if (hint.visibility == GONE) {
            0
        } else {
            Metrics.textHeight(hint.text.toString(), Fonts.regular, 16f, textWidth, 0)
        }

        val buttonHeight = if (buttonFill.visibility == GONE) 0 else dp(44f)

        var total = pictureSide + dp(20f) + titleHeight + dp(8f) + hintHeight +
            (if (buttonHeight > 0) dp(22f) + buttonHeight else 0)

        /**
         * Не помещается — ужимаем картинку, а не текст.
         *
         * Совсем мелкую убираем вовсе: ниже 56 точек она уже не картинка,
         * а пятно. Так же поступает и оригинал.
         */
        if (total > height) {
            pictureSide -= (total - height)

            if (pictureSide < dp(56f)) {
                pictureSide = 0
            }

            total = pictureSide + (if (pictureSide > 0) dp(20f) else 0) +
                titleHeight + dp(8f) + hintHeight +
                (if (buttonHeight > 0) dp(22f) + buttonHeight else 0)
        }

        var y = maxOf(0, (height - total) / 2)

        picture.frame((width - pictureSide) / 2, y, pictureSide, pictureSide)

        if (pictureSide > 0) {
            y += pictureSide + dp(20f)
        }

        title.frame((width - textWidth) / 2, y, textWidth, titleHeight)

        y += titleHeight + dp(8f)

        hint.frame((width - textWidth) / 2, y, textWidth, hintHeight)

        y += hintHeight + dp(22f)

        if (buttonHeight <= 0) {
            return
        }

        /**
         * `MinWidth="150"`, `Padding="18,8"` — из шаблона кнопки
         * в Home.xaml. Ширина — по подписи, но не уже ста пятидесяти.
         */
        val labelWidth = Metrics.textWidth(buttonTitle.text.toString(), Fonts.semiBold, 15f)

        val buttonWidth = maxOf(dp(150f), labelWidth + dp(36f))

        buttonFill.frame((width - buttonWidth) / 2, y, buttonWidth, buttonHeight)
        buttonTitle.frame((width - buttonWidth) / 2, y, buttonWidth, buttonHeight)
    }
}
