package ru.computershik.troubadour.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.TranslateAnimation
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Notify
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.locF
import ru.computershik.troubadour.net.Api
import ru.computershik.troubadour.net.Auth
import ru.computershik.troubadour.net.CommentItem
import ru.computershik.troubadour.net.WebAuth
import ru.computershik.troubadour.net.comments
import ru.computershik.troubadour.net.editComment
import ru.computershik.troubadour.net.postComment
import ru.computershik.troubadour.net.replyComment
import ru.computershik.troubadour.ui.Metrics.dp
import ru.computershik.troubadour.ui.Metrics.dpf

/**
 * Числа панели — из `YTCommentsSheet` и `CommentsBottomSheetPanel`.
 *
 * Высота 400, поля 10 по бокам и 6 снизу, скругление 15, полоса захвата
 * 40. Содержимое внутри карточки — `Margin="16,0,16,18"`.
 */
private const val SHEET_HEIGHT = 400f
private const val SHEET_SIDE_PAD = 10f
private const val SHEET_BOTTOM = 6f
private const val SHEET_CORNER = 15f
private const val SHEET_HANDLE = 40f
private const val SHEET_CONTENT_SIDE = 16f
private const val SHEET_CONTENT_BOTTOM = 18f

/**
 * Комментарии — порт `CommentsPanel` из Video.xaml.
 *
 * Запись: кружок 24 с отступом 10, автор 12 Medium secondary, время
 * 12 muted, текст 13. Всё на подложке `AppSurface` со скруглением 12,
 * поля 12, отступы 16,0,16,16.
 *
 * Внизу поле ввода. **Показывается оно по входу, а не по метке**, и это
 * исправление первого захода оригинала. Сперва было наоборот: поле
 * появлялось, только когда страница привезла `createParams`. Замысел был
 * честный — не рисовать того, что не отправится, — но метку кладут
 * в ответ WEB-клиента, а подписывает его одна лишь браузерная сессия.
 * У вошедшего кодом устройства запрос уходит анонимным, метки не бывает
 * никогда, и поле пряталось ровно от тех, у кого вход как раз есть.
 */
/**
 * Показывается панелью снизу, поверх страницы ролика.
 *
 * Порт `YTCommentsSheet`: карточка у нижнего края, отступ 10 по бокам
 * и 6 снизу, скругление 15, сверху область захвата 40 с серой полоской
 * 40×4. Подложка прозрачная — кадр над панелью остаётся виден
 * и продолжает играть. Так же устроено и в версии для Windows 10 Mobile:
 * `CommentsBottomSheetPanel` с `MaxHeight="400"` и `Margin="10,0,10,6"`.
 *
 * Отдельной страницей комментарии были только у нас, и это расходилось
 * с обеими исходными версиями: там ролик не уезжал с глаз.
 */
class CommentsSheet(
    private val context: Context,
    private val videoId: String,
    private val token: String?
) {

    private val dialog = Dialog(context)

    private lateinit var panel: Panel
    private lateinit var list: ListView
    private lateinit var status: StatusView
    private lateinit var field: EditText
    private lateinit var composer: View

    private val items = ArrayList<CommentItem>()

    private var continuation: String? = null
    private var createParams: String? = null
    private var busy = false

    /**
     * Ветки качаются своим счётчиком занятости.
     *
     * Общий не годится: пока едет страница ветки, список перестал бы
     * добирать себя, и наоборот — а идут они по одной прокрутке.
     */
    private var busyReplies = false

    /** Что правим или на что отвечаем; пусто — пишем новый. */
    private var replyTo: CommentItem? = null
    private var editing: CommentItem? = null

    private val adapter = object : BaseAdapter() {

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? CommentRow) ?: CommentRow(context)

            row.bind(items[position], this@CommentsSheet)

            return row
        }
    }

    init {
        list = ListView(context)

        list.adapter = adapter
        list.divider = null
        list.dividerHeight = 0
        list.setCacheColorHint(0)
        list.clipToPadding = false

        composer = buildComposer()
        status = StatusView(context)

        panel = Panel(context)

        buildWindow()

        list.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {

            override fun onScrollStateChanged(view: android.widget.AbsListView, state: Int) {}

            override fun onScroll(
                view: android.widget.AbsListView,
                first: Int,
                visible: Int,
                total: Int
            ) {
                if (!busy && !continuation.isNullOrEmpty() &&
                    first + visible >= total - visible
                ) {
                    load(continuation)
                }

                loadRepliesInView(first, visible)
            }
        })

        applyComposer()

        status.showBusy()

        load(token)
    }

    /**
     * Окно панели: во весь экран, прозрачное и без затемнения.
     *
     * Затемнять нельзя — за панелью играет ролик, и в оригинале его
     * видно. По той же причине панель не уводит страницу с глаз: это
     * лист поверх неё, а не отдельная страница.
     */
    private fun buildWindow() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        dialog.setContentView(
            panel,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            /**
             * Клавиатура укорачивает окно, а не наезжает на него: строка
             * ввода прижата к низу панели и остаётся над клавиатурой.
             * В iOS-версии то же самое считается руками по `keyboardLift`.
             */
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            )
        }

        dialog.setOnDismissListener { Notify.offAll(this) }

        Notify.on(Notify.THEME, this) { repaint() }
    }

    fun show() {
        dialog.show()

        panel.requestFocus()

        panel.slideIn()
    }

    fun dismiss() {
        try {
            dialog.dismiss()
        } catch (ignored: Exception) {
            // Страница могла уже уйти — тогда закрывать нечего.
        }
    }

    /**
     * Сама панель: карточка, полоса захвата, список, строка ввода.
     *
     * Раскладка руками, числами из `layoutSubviews` оригинала. Всё, что
     * мимо карточки, — пустое место, сквозь которое виден кадр; нажатие
     * по нему закрывает панель, как и нажатие по полосе.
     */
    inner class Panel(context: Context) : ViewGroup(context) {

        private val card = PillView(context)

        private val gripHost = TappableView(context)
        private val grip = PillView(context)

        init {
            card.cornerRadius = dpf(SHEET_CORNER)
            card.fillColor = Theme.divider

            /**
             * Карточка берёт нажатия на себя. Без этого они проваливались
             * бы сквозь неё на пустое место, а оно закрывает панель.
             */
            card.isClickable = true

            addView(card)

            gripHost.highlights = false
            gripHost.onTap = { dismiss() }

            grip.fillColor = Color.GRAY
            grip.cornerRadius = dpf(2f)

            gripHost.addView(grip)

            /**
             * Панель забирает ввод себе, пока его не попросили.
             *
             * Иначе первым в окне оказывается поле ввода, оно берёт
             * ввод на себя при открытии, и панель встречает клавиатурой
             * — а её вызывают читать, а не писать.
             */
            isFocusableInTouchMode = true

            addView(gripHost)
            addView(list)
            addView(composer)
            addView(status)
        }

        /** Выезд снизу — панель приходит оттуда же, откуда в оригинале. */
        fun slideIn() {
            val move = TranslateAnimation(0f, 0f, dpf(SHEET_HEIGHT), 0f)

            move.duration = 220

            startAnimation(move)
        }

        /** Нажатие мимо карточки — закрыть, как по `_tap` в оригинале. */
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP && event.y < card.top) {
                dismiss()
            }

            return true
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

            val panelWidth = width - dp(SHEET_SIDE_PAD) * 2

            /**
             * Панель не выше самого экрана: на невысоком, да ещё
             * и с поднятой клавиатурой, четыреста точек не помещаются
             * вместе с кадром.
             */
            var panelHeight = dp(SHEET_HEIGHT)
            var top = height - dp(SHEET_BOTTOM) - panelHeight

            if (top < 0) {
                panelHeight = height - dp(SHEET_BOTTOM)
                top = 0
            }

            card.frame(dp(SHEET_SIDE_PAD), top, panelWidth, panelHeight)

            gripHost.frame(dp(SHEET_SIDE_PAD), top, panelWidth, dp(SHEET_HANDLE))
            grip.frame(
                (panelWidth - dp(40f)) / 2, dp(SHEET_HANDLE) / 2 - dp(2f),
                dp(40f), dp(4f)
            )

            val contentLeft = dp(SHEET_SIDE_PAD) + dp(SHEET_CONTENT_SIDE)
            val contentWidth = panelWidth - dp(SHEET_CONTENT_SIDE) * 2

            /**
             * Строка ввода прижата к низу карточки и забирает себе её
             * нижнее поле: свои поля у неё есть, чужие ни к чему.
             */
            var composerHeight = 0

            if (composer.visibility == View.VISIBLE) {
                composer.measure(
                    MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )

                composerHeight = composer.measuredHeight

                composer.frame(
                    contentLeft, top + panelHeight - composerHeight,
                    contentWidth, composerHeight
                )
            }

            val listTop = top + dp(SHEET_HANDLE)

            val listHeight = panelHeight - dp(SHEET_HANDLE) - composerHeight -
                (if (composerHeight > 0) 0 else dp(SHEET_CONTENT_BOTTOM))

            list.frame(contentLeft, listTop, contentWidth, listHeight)
            status.frame(contentLeft, listTop, contentWidth, listHeight)
        }
    }

    private fun buildComposer(): View {
        val row = LinearLayout(context)

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        row.setPadding(dp(16f), dp(8f), dp(8f), dp(8f))

        field = EditText(context)

        field.hint = loc("Добавьте комментарий")
        field.typeface = Fonts.regular
        field.setTextColor(Theme.primaryText)
        field.setHintTextColor(Theme.mutedText)
        field.setBackgroundColor(Color.TRANSPARENT)
        field.maxLines = 4

        row.addView(
            field,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val send = TappableView(context)

        val icon = ImageView(context)

        icon.setImageBitmap(Icons.icon("pl_send"))
        icon.scaleType = ImageView.ScaleType.FIT_CENTER

        send.addView(icon, FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER))

        send.onTap = { submit() }

        row.addView(send, LinearLayout.LayoutParams(dp(44f), dp(44f)))

        return row
    }

    /**
     * Поле ввода — по входу.
     *
     * Есть чем представиться — поле есть; метку, если её не прислали
     * со списком, спрашивает сама отправка.
     */
    private fun applyComposer() {
        val canWrite = Auth.isSignedIn() || WebAuth.isSignedIn()

        composer.visibility = if (canWrite) View.VISIBLE else View.GONE

        Log.d {
            "[YouTube/Комментарий] Поле ввода: " +
                (if (canWrite) "показано" else "скрыто") +
                " (вход: токен ${if (Auth.isSignedIn()) "есть" else "нет"}, " +
                "сессия ${if (WebAuth.isSignedIn()) "есть" else "нет"})"
        }
    }

    private fun load(token: String?) {
        if (token.isNullOrEmpty()) {
            status.hide()

            return
        }

        busy = true

        async {
            val page = Api.comments(token)

            main {
                busy = false

                status.hide()

                if (page == null) {
                    if (items.isEmpty()) {
                        status.showNoConnection { load(this@CommentsSheet.token) }
                    }

                    return@main
                }

                continuation = page.continuation

                page.createParams?.let { createParams = it }

                items.addAll(page.items)

                Log.d {
                    "[YouTube/Комментарии] Страница перечня: +${page.items.size}, " +
                        "всего ${items.size}, дальше " +
                        (if (continuation.isNullOrEmpty()) "нет" else "есть")
                }

                adapter.notifyDataSetChanged()

                if (items.isEmpty()) {
                    /**
                     * Закрытые комментарии — отдельный случай, а не «их
                     * просто нет». Сервер говорит об этом сам и на языке
                     * человека; своё придумывать незачем.
                     */
                    status.showMessage(
                        page.disabledMessage ?: loc("Комментариев нет")
                    )
                }
            }
        }
    }

    /**
      * Показался хвост раскрытой ветки — значит, пора за её продолжением.
      */
    private fun loadRepliesInView(first: Int, visible: Int) {
        if (busyReplies || visible <= 0) {
            return
        }

        val last = minOf(first + visible, items.size) - 1

        for (index in maxOf(0, first)..last) {
            val item = items[index]

            if (!item.moreReplies.isNullOrEmpty()) {
                loadMoreReplies(item)

                return
            }
        }
    }

    /** Ответить на чужой комментарий — зовётся из строки. */
    fun beginReply(item: CommentItem) {
        replyTo = item
        editing = null

        field.hint = locF("Ответ %@", item.author)

        field.requestFocus()
    }

    /** Поправить свой — метку правки сервер даёт только у своих. */
    fun beginEdit(item: CommentItem) {
        editing = item
        replyTo = null

        field.setText(item.text)
        field.hint = loc("Правка своего комментария")

        field.requestFocus()
    }

    /**
     * Ветка ответов раскрывается прямо в списке.
     *
     * Так это устроено и в оригинале: строка «Ответы (N)» уступает место
     * самим ответам, а те рисуются с отступом. Отдельной страницы для
     * ветки нет ни в iOS-версии, ни в версии для Windows 10 Mobile.
     */
    fun openReplies(item: CommentItem) {
        val token = item.replies

        if (token.isNullOrEmpty() || busyReplies) {
            return
        }

        // Метку убираем сразу: раскрывать ветку второй раз незачем,
        // а ответ из сети придёт не в тот же миг.
        item.replies = null

        adapter.notifyDataSetChanged()

        loadReplies(token, item, false)
    }

    /**
     * Следующая страница ветки — по появлению хвостового ответа.
     *
     * Ветку догружает та же прокрутка, что и сам список: показался
     * последний привезённый ответ — значит, читают ветку, и остальные
     * ответы нужны.
     */
    private fun loadMoreReplies(tail: CommentItem) {
        val token = tail.moreReplies

        if (token.isNullOrEmpty() || busyReplies) {
            return
        }

        // Метка переезжает на новый хвост; со старого её снимаем сразу,
        // чтобы прокрутка не попросила ту же страницу дважды.
        tail.moreReplies = null

        loadReplies(token, tail, true)
    }

    /** Страница ветки: приходит и встаёт следом за той записью, от которой пошли. */
    private fun loadReplies(token: String, after: CommentItem, more: Boolean) {
        busyReplies = true

        async {
            val page = Api.comments(token, replies = true)

            main {
                busyReplies = false

                val replies = page?.items ?: emptyList()

                // Пока ходили в сеть, список мог смениться — ищем строку заново.
                val at = items.indexOfFirst { it === after }

                if (at < 0) {
                    return@main
                }

                var put = at + 1

                for (reply in replies) {
                    reply.isReply = true
                    reply.replies = null

                    items.add(put, reply)

                    put++
                }

                /**
                 * Метка следующей страницы — у последнего ответа ветки.
                 *
                 * Если ответов не привезли вовсе, вешать её некуда
                 * и незачем: ветка кончилась.
                 */
                val last = items.getOrNull(put - 1)

                if (replies.isNotEmpty() && last != null) {
                    last.moreReplies = page?.continuation
                }

                Log.d {
                    (if (more) "[YouTube/Комментарии] Ветка продолжена: ответов "
                    else "[YouTube/Комментарии] Ветка раскрыта: ответов ") +
                        "${replies.size}, дальше " +
                        (if (last?.moreReplies.isNullOrEmpty()) "нет" else "есть")
                }

                adapter.notifyDataSetChanged()

                /**
                 * Хвост ветки мог оказаться на виду сразу — тогда за
                 * следующей страницей идём, не дожидаясь прокрутки:
                 * ждать её было бы нечестно, ветка уже на экране вся.
                 */
                list.post {
                    loadRepliesInView(list.firstVisiblePosition, list.childCount)
                }
            }
        }
    }

    private fun submit() {
        val text = field.text.toString().trim()

        if (text.isEmpty()) {
            return
        }

        val reply = replyTo
        val edit = editing

        field.setText("")
        field.hint = loc("Добавьте комментарий")

        replyTo = null
        editing = null

        async {
            val result = when {
                edit != null -> Api.editComment(text, edit.editParams)
                reply != null -> Api.replyComment(text, reply.replyParams)
                else -> Api.postComment(text, videoId, createParams, token)
            }

            main {
                if (result.accepted) {
                    Toast.show(context, loc("Отправлено"))

                    return@main
                }

                /**
                 * Причину показываем **ту, что назвал сервер**.
                 *
                 * У InnerTube отказ почти всегда несёт готовую надпись
                 * для человека: «комментарии отключены», «войдите
                 * в аккаунт», «слишком часто». Она точнее любой нашей
                 * догадки и уже переведена.
                 */
                Toast.showLong(
                    context,
                    loc("Комментарий не принят") + ": " + (result.reason ?: loc(
                        "YouTube отказал в отправке и причины не назвал. " +
                            "Подробности — в журнале приложения."
                    ))
                )
            }
        }
    }

    private fun repaint() {
        field.setTextColor(Theme.primaryText)
        field.setHintTextColor(Theme.mutedText)

        panel.requestLayout()
        panel.invalidate()

        adapter.notifyDataSetChanged()
    }
}

/**
 * Строка комментария.
 *
 * Подложка `AppSurface` со скруглением 12 и полями 12; кружок 24
 * с отступом 10, автор 12 Medium secondary, время 12 muted, текст 13.
 */
private class CommentRow(context: Context) : FrameLayout(context) {

    private val avatar = RoundedImage(context)

    /**
     * Размеры и цвета — как в iOS-версии: имя полужирным 15, время
     * обычным 12, сам текст обычным 14. Здесь стояли 12 и 13, и список
     * выглядел мельче оригинала.
     */
    private val author = label(context, Fonts.bold, 15f, Theme.secondaryText, 1)
    private val published = label(context, Fonts.regular, 12f, Theme.mutedText, 1)
    private val text = label(context, Fonts.regular, 14f, Theme.primaryText, 0)
    private val replies = label(context, Fonts.semiBold, 13f, Theme.ACCENT_BLUE, 1)
    private val edit = label(context, Fonts.semiBold, 13f, Theme.ACCENT_BLUE, 1)

    /** Хранится полем: ответу в раскрытой ветке добавляется отступ слева. */
    private val row = LinearLayout(context)

    init {
        /**
         * Подложки под строкой нет: в iOS-версии список комментариев
         * плоский, строки лежат прямо на фоне страницы. Здесь под каждой
         * рисовалась скруглённая плашка, и список выглядел иначе.
         */
        row.orientation = LinearLayout.HORIZONTAL

        avatar.circular = true
        avatar.placeholderColor = Theme.avatarPlaceholder

        row.addView(avatar, LinearLayout.LayoutParams(dp(24f), dp(24f)))

        val column = LinearLayout(context)

        column.orientation = LinearLayout.VERTICAL

        val top = LinearLayout(context)

        top.orientation = LinearLayout.HORIZONTAL

        top.addView(author)

        val whenParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        whenParams.leftMargin = dp(6f)

        top.addView(published, whenParams)

        column.addView(top)
        column.addView(text)

        val actions = LinearLayout(context)

        actions.orientation = LinearLayout.HORIZONTAL

        replies.text = loc("Ответы")
        edit.text = loc("Изменить")

        actions.addView(replies)

        val editParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        editParams.leftMargin = dp(16f)

        actions.addView(edit, editParams)

        column.addView(actions)

        val columnParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )

        columnParams.leftMargin = dp(10f)

        row.addView(column, columnParams)


        /**
         * Ряд кладётся первым и меряется по содержимому, подложка —
         * вторым и растягивается под него.
         *
         * Наоборот нельзя: `FrameLayout`, у которого оба ребёнка
         * `MATCH_PARENT`, при `WRAP_CONTENT` по высоте схлопывается —
         * та же беда, что съела кнопки на странице ролика.
         */
        addView(
            row,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val params = android.widget.AbsListView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        layoutParams = params
    }

    fun bind(item: CommentItem, screen: CommentsSheet) {
        /** Отступ ответа — `YTReplyIndent` из iOS-версии. */
        row.setPadding(
            dp(12f) + (if (item.isReply) dp(30f) else 0),
            dp(12f), dp(12f), dp(12f)
        )

        author.setTextColor(Theme.secondaryText)
        published.setTextColor(Theme.mutedText)
        text.setTextColor(Theme.primaryText)

        avatar.placeholderColor = Theme.avatarPlaceholder

        author.text = item.author
        published.text = item.published ?: ""
        text.text = item.text

        ImageLoader.loadInto(avatar, item.avatar, 24f)

        replies.visibility = if (item.replies.isNullOrEmpty()) GONE else VISIBLE

        replies.text = if (item.replyCount.isNullOrEmpty()) {
            loc("Ответы")
        } else {
            locF("Ответы (%@)", item.replyCount ?: "")
        }

        replies.setOnClickListener { screen.openReplies(item) }

        /**
         * «Изменить» показывается только там, где сервер дал метку
         * правки: своего списка авторства у нас нет, а сверять имя
         * канала ненадёжно — имена повторяются.
         */
        edit.visibility = if (item.editParams.isNullOrEmpty()) GONE else VISIBLE

        edit.setOnClickListener { screen.beginEdit(item) }

        setOnClickListener { screen.beginReply(item) }
    }
}
