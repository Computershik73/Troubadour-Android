package ru.computershik.troubadour.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.net.CommentItem
import ru.computershik.troubadour.ui.Metrics.dp

/**
 * Карточка комментариев под роликом — порт `applyComments:`.
 *
 * Показывается **один** комментарий: так же в оригинале, где под
 * заголовком «Комментарии» стоит ровно одна запись, а весь список
 * открывается отдельно. Нажатие по карточке ведёт в список.
 *
 * Числа из раскладки: поля 12, кружок 24, текст с отступом 34 слева,
 * время в колонке 90 справа, заголовок 14 Bold, автор 12 Medium,
 * время 12 muted, текст 13 в две строки.
 */
class CommentsCard(context: Context) : ViewGroup(context) {

    private val card = PillView(context)

    private val title = label(context, Fonts.bold, 14f, Theme.primaryText, 1)

    private val avatar = RoundedImage(context)
    private val author = label(context, Fonts.medium, 12f, Theme.secondaryText, 1)
    private val time = label(context, Fonts.regular, 12f, Theme.mutedText, 1)
    private val text = label(context, Fonts.regular, 13f, Theme.primaryText, 2)

    /** Когда сказать нечего: сообщение вместо комментария. */
    private var notice: String? = null

    var onOpen: (() -> Unit)? = null

    init {
        card.cornerRadius = Metrics.dpf(12f)
        card.fillColor = Theme.surface

        addView(card)

        title.text = loc("Комментарии")

        avatar.circular = true
        avatar.placeholderColor = Theme.avatarPlaceholder

        addView(title)
        addView(avatar)
        addView(author)
        addView(text)

        time.gravity = Gravity.END

        addView(time)

        isClickable = true
        setOnClickListener { onOpen?.invoke() }

        visibility = GONE
    }

    /**
     * Пусто по-разному: сервер сказал, почему, — говорим его словами;
     * промолчал — карточки нет вовсе. Придумывать причины там, где их
     * могло просто не быть, не станем.
     */
    fun showNotice(said: String?) {
        if (said.isNullOrEmpty()) {
            visibility = GONE

            return
        }

        notice = said

        visibility = VISIBLE

        avatar.visibility = GONE
        author.visibility = GONE
        time.visibility = GONE

        text.text = said

        requestLayout()
    }

    fun bind(first: CommentItem) {
        notice = null

        visibility = VISIBLE

        avatar.visibility = VISIBLE
        author.visibility = VISIBLE
        time.visibility = VISIBLE

        author.text = first.author
        time.text = first.published ?: ""

        /**
         * Текст обрезается, и это не косметика: у ролика бывает
         * тридцать с лишним тысяч знаков комментариев, а замер текста
         * на старом железе занимает секунды.
         */
        text.text = Metrics.clampText(first.text, 600)

        ImageLoader.loadInto(avatar, first.avatar, 24f)

        requestLayout()
    }

    fun applyTheme() {
        card.fillColor = Theme.surface

        title.setTextColor(Theme.primaryText)
        author.setTextColor(Theme.secondaryText)
        time.setTextColor(Theme.mutedText)
        text.setTextColor(Theme.primaryText)

        avatar.placeholderColor = Theme.avatarPlaceholder
    }

    private fun cardHeight(width: Int): Int {
        val inner = width - dp(16f) * 2 - dp(24f)

        val headerHeight = Metrics.lineHeight(Fonts.bold, 14f)
        val authorHeight = Metrics.lineHeight(Fonts.medium, 12f)

        val textWidth = inner - dp(24f) - dp(10f)

        val textHeight = Metrics.textHeight(
            text.text.toString(), Fonts.regular, 13f, textWidth, 2
        )

        if (notice != null) {
            return dp(12f) + headerHeight + dp(8f) + textHeight + dp(12f)
        }

        return dp(12f) + headerHeight + dp(8f) + authorHeight + textHeight + dp(12f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)

        setMeasuredDimension(
            width, if (visibility == GONE) 0 else cardHeight(width)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l

        val margin = dp(16f)
        val content = width - margin * 2
        val inner = content - dp(24f)

        card.frame(margin, 0, content, cardHeight(width))

        val left = margin + dp(12f)

        var cursor = dp(12f)

        val headerHeight = Metrics.lineHeight(Fonts.bold, 14f)

        title.frame(left, cursor, inner, headerHeight)

        cursor += headerHeight + dp(8f)

        val textWidth = inner - dp(24f) - dp(10f)

        val textHeight = Metrics.textHeight(
            text.text.toString(), Fonts.regular, 13f, textWidth, 2
        )

        // Сообщение вместо комментария — без кружка и без автора.
        if (notice != null) {
            text.frame(left, cursor, inner, textHeight)

            return
        }

        avatar.frame(left, cursor, dp(24f), dp(24f))

        val authorHeight = Metrics.lineHeight(Fonts.medium, 12f)
        val timeWidth = dp(90f)

        author.frame(
            left + dp(34f), cursor, maxOf(0, textWidth - timeWidth), authorHeight
        )

        time.frame(
            left + dp(34f) + textWidth - timeWidth, cursor, timeWidth, authorHeight
        )

        text.frame(left + dp(34f), cursor + authorHeight, textWidth, textHeight)
    }
}
