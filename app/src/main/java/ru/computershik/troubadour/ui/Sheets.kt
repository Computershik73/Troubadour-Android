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
import android.widget.ImageView
import android.widget.ScrollView
import ru.computershik.troubadour.AudioLanguage
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.loc
import ru.computershik.troubadour.net.Account
import ru.computershik.troubadour.net.Api
import ru.computershik.troubadour.net.accountsList
import ru.computershik.troubadour.net.activeAccountPage
import ru.computershik.troubadour.net.setActiveAccountPage
import ru.computershik.troubadour.player.PlayerEngine
import ru.computershik.troubadour.player.Streams
import ru.computershik.troubadour.ui.Metrics.dp
import ru.computershik.troubadour.ui.Metrics.dpf

/** Числа `SettingsBottomSheetPanel` из Video.xaml. */
private const val SHEET_SIDE = 10f
private const val SHEET_RADIUS = 15f
private const val SHEET_GRIP = 40f
private const val SHEET_PAD = 20f
private const val SHEET_ROW = 44f

/**
 * Строка канала выше обычной: в ней кружок и две подписи.
 *
 * Числа не из оригинала — там этого списка нет вовсе. Взяты по соседям:
 * кружок 36, как у автора комментария, и высота, при которой две строки
 * текста стоят с теми же полями, что одна в обычной строке.
 */
private const val SHEET_ACCOUNT = 56f
private const val SHEET_PHOTO = 36f

/** `Margin="0,0,0,4"` у каждой строки — просвет между ними. */
private const val SHEET_GAP = 4f

private const val SHEET_ICON = 24f
private const val SHEET_CHEVRON = 16f
private const val SHEET_CHECK = 28f

/**
 * Лист снизу — порт `YTSettingsSheet`.
 *
 * Это не список во всю ширину, а отдельная карточка: отступ 10 со всех
 * сторон, включая нижнюю, скругление 15, сверху область захвата высотой
 * 40 с серой полоской 40×4 посередине. Нажатие по полоске и мимо карточки
 * закрывает её — как `SettingsDragArea_Tapped` и `OverlayGrid`
 * в оригинале.
 *
 * Строки высотой 44 с просветом 4; у строки канала высота 56.
 */
open class Sheet(protected val context: Context, title: String) {

    private val dialog = Dialog(context)

    /** Панель над плеером всегда тёмная, где бы ни стояла тема. */
    protected open val dark: Boolean get() = false

    private val panel: SheetPanel

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        panel = SheetPanel(context, title, dark)

        panel.onClose = { dismiss() }

        dialog.setContentView(
            panel,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        dialog.window?.let { window ->
            /**
             * Затемнение рисуем сами.
             *
             * Своё у окна лежит **под** содержимым и не даёт закрыть лист
             * нажатием мимо панели: касание попадает в окно, а не
             * в затемнение. Панель занимает весь экран и красит фон сама.
             */
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    /**
     * Пункт списка: столбец 28 под галочку, название рядом.
     * У выбранного оно полужирное — так же в оригинале.
     */
    protected fun row(
        text: String,
        hint: String? = null,
        chosen: Boolean = false,
        action: () -> Unit
    ): View = ChoiceRow(context, dark, text, hint, chosen).also { view ->
        view.onTap = {
            action()

            dismiss()
        }
    }

    /** Строка-раздел: значок слева, значение и стрелка справа. */
    protected fun section(
        icon: String?,
        text: String,
        value: String?,
        action: () -> Unit
    ): View = SectionRow(context, dark, icon, text, value, true).also { view ->
        view.onTap = { action() }
    }

    /** Строка-действие: значок и подпись, без значения и без стрелки. */
    protected fun command(icon: String?, text: String, action: () -> Unit): View =
        SectionRow(context, dark, icon, text, null, false).also { view ->
            view.onTap = {
                dismiss()

                action()
            }
        }

    /** Строка канала: кружок, имя, собачка под ним, галочка справа. */
    protected fun account(
        name: String,
        handle: String?,
        avatar: String?,
        chosen: Boolean,
        action: () -> Unit
    ): View = AccountRow(context, dark, name, handle, avatar, chosen).also { view ->
        view.onTap = {
            action()

            dismiss()
        }
    }

    protected fun add(view: View) {
        panel.addRow(view)
    }

    protected fun clearRows() {
        panel.clearRows()
    }

    fun show() {
        dialog.show()
    }

    fun dismiss() {
        try {
            dialog.dismiss()
        } catch (ignored: Exception) {
            // Экран мог уже уйти — тогда закрывать нечего.
        }
    }
}

/**
 * Затемнение и сама карточка.
 *
 * Раскладка руками, числами из `layoutSubviews` оригинала: высота
 * карточки — по содержимому, но не выше семи десятых экрана.
 */
class SheetPanel(context: Context, titleText: String, private val dark: Boolean) :
    ViewGroup(context) {

    private val card = PillView(context)

    private val gripHost = TappableView(context)
    private val grip = PillView(context)

    private val title = label(context, Fonts.semiBold, 16f, primary(), 1)

    private val list = ScrollView(context)
    private val rows = RowsBox(context)

    var onClose: (() -> Unit)? = null

    init {
        setBackgroundColor(0x99000000.toInt())

        card.cornerRadius = dpf(SHEET_RADIUS)
        card.fillColor = panelColor()

        /**
         * Взаимодействие приходится включать обратно.
         *
         * `PillView` только рисует и потому не берёт нажатий. Здесь она
         * держит строки, и без этого они проваливались бы сквозь неё
         * на затемнение, а оно закрывает панель: по любой строке
         * она просто закрывалась.
         */
        card.isClickable = true

        addView(card)

        /**
         * Полоса захвата — `Rectangle Width="40" Height="4"` серым
         * по центру области высотой 40. Тянуть панель пальцем нельзя,
         * но нажатие по этой области закрывает её.
         */
        gripHost.highlights = false
        gripHost.onTap = { onClose?.invoke() }

        grip.fillColor = Color.GRAY
        grip.cornerRadius = dpf(2f)

        gripHost.addView(grip)

        addView(gripHost)

        title.text = titleText

        addView(title)

        list.isVerticalScrollBarEnabled = false

        list.addView(
            rows,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        addView(list)
    }

    private fun panelColor(): Int = if (dark) 0xFF222222.toInt() else Theme.divider

    private fun primary(): Int = if (dark) Color.WHITE else Theme.primaryText

    fun addRow(view: View) {
        rows.addView(view)

        requestLayout()
    }

    fun clearRows() {
        rows.removeAllViews()

        requestLayout()
    }

    /** Нажатие мимо карточки — закрыть, как по `OverlayGrid`. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && event.y < card.top) {
            onClose?.invoke()

            return true
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

        val panelWidth = width - dp(SHEET_SIDE) * 2
        val inner = panelWidth - dp(SHEET_PAD) * 2

        val header = if (title.text.isNullOrEmpty()) 0 else dp(20f) + dp(16f)

        val listHeight = rows.contentHeight(inner)

        val content = dp(SHEET_GRIP) + header + listHeight + dp(SHEET_PAD)

        val panelHeight = minOf(content, (height * 0.7f).toInt())

        card.frame(
            dp(SHEET_SIDE), height - panelHeight - dp(SHEET_SIDE),
            panelWidth, panelHeight
        )

        val top = card.top

        gripHost.frame(dp(SHEET_SIDE), top, panelWidth, dp(SHEET_GRIP))
        grip.frame(
            (panelWidth - dp(40f)) / 2, dp(SHEET_GRIP) / 2 - dp(2f), dp(40f), dp(4f)
        )

        title.frame(
            dp(SHEET_SIDE) + dp(SHEET_PAD), top + dp(SHEET_GRIP), inner, dp(20f)
        )

        val listTop = dp(SHEET_GRIP) + header

        list.frame(
            dp(SHEET_SIDE) + dp(SHEET_PAD), top + listTop, inner,
            maxOf(0, panelHeight - listTop - dp(SHEET_PAD))
        )
    }
}

/** Столбец строк: каждая во всю ширину, просвет 4 под каждой. */
class RowsBox(context: Context) : ViewGroup(context) {

    /** Ширина, которую перечню отвели, — по ней меряется сплошной текст. */
    private var inner = 0

    fun contentHeight(width: Int = inner): Int {
        inner = width

        var total = 0

        for (index in 0 until childCount) {
            total += heightOf(getChildAt(index), width) + dp(SHEET_GAP)
        }

        return total
    }

    private fun heightOf(child: View, width: Int): Int =
        if (child is SheetRowView) child.rowHeight(width) else dp(SHEET_ROW)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)

        setMeasuredDimension(width, contentHeight(width))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l

        var at = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)

            val height = heightOf(child, width)

            child.frame(0, at, width, height)

            at += height + dp(SHEET_GAP)
        }
    }
}

/** Общее у строк листа: своя высота и своя раскладка внутри. */
abstract class SheetRowView(context: Context, protected val dark: Boolean) :
    TappableView(context) {

    protected fun primary(): Int = if (dark) Color.WHITE else Theme.primaryText

    protected fun secondary(): Int =
        if (dark) 0xFFAAAAAA.toInt() else Theme.secondaryText

    /**
     * Своя высота при такой ширине.
     *
     * Ширина нужна не всем — у строк она своя высота, — но у сплошного
     * текста высота от неё и зависит, а иного случая узнать её нет:
     * перечень спрашивает высоту до раскладки.
     */
    open fun rowHeight(width: Int): Int = dp(SHEET_ROW)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)

        setMeasuredDimension(width, rowHeight(width))
    }
}

/**
 * Пункт списка.
 *
 * Галочка стоит **слева**, в столбце шириной 28, а не справа от названия:
 * так в оригинале, и так глаз находит выбранное, не дочитывая строку
 * до конца.
 */
class ChoiceRow(
    context: Context,
    dark: Boolean,
    text: String,
    private val hint: String?,
    chosen: Boolean
) : SheetRowView(context, dark) {

    private val check = label(context, Fonts.regular, 16f, primary(), 1)

    // `FontSize = 14` у пункта списка — в разделах шрифт крупнее.
    private val title = label(
        context, if (chosen) Fonts.semiBold else Fonts.regular, 14f, primary(), 1
    )

    private val note = label(context, Fonts.regular, 12f, secondary(), 1)

    init {
        highlights = true

        check.text = if (chosen) "✓" else ""
        check.gravity = Gravity.CENTER_VERTICAL

        title.text = text

        addView(check)
        addView(title)

        note.text = hint ?: ""
        note.visibility = if (hint.isNullOrEmpty()) GONE else VISIBLE

        addView(note)
    }

    /**
     * Пояснение под названием — сверх оригинала.
     *
     * Там пункты качества голые, но потолок декодера сообщить надо:
     * ступень выше него не запрещается, а помечается, и пометке нужна
     * своя строка.
     */
    override fun rowHeight(width: Int): Int =
        if (hint.isNullOrEmpty()) dp(SHEET_ROW) else dp(SHEET_ACCOUNT)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        check.frame(0, 0, dp(SHEET_CHECK), height)

        val left = dp(SHEET_CHECK)

        if (note.visibility == GONE) {
            title.frame(left, 0, width - left, height)

            return
        }

        val titleHeight = dp(18f)
        val noteHeight = dp(15f)

        val top = (height - titleHeight - noteHeight) / 2

        title.frame(left, top, width - left, titleHeight)
        note.frame(left, top + titleHeight, width - left, noteHeight)
    }
}

/** Строка-раздел: значок 24 слева, значение и стрелка справа. */
class SectionRow(
    context: Context,
    dark: Boolean,
    icon: String?,
    text: String,
    value: String?,
    private val chevron: Boolean
) : SheetRowView(context, dark) {

    private val image = ImageView(context)

    private val title = label(context, Fonts.regular, 16f, primary(), 1)
    private val value = label(context, Fonts.regular, 14f, secondary(), 1)

    private val arrow = ImageView(context)

    init {
        highlights = true

        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.setImageBitmap(if (icon.isNullOrEmpty()) null else Icons.icon(icon))
        image.visibility = if (icon.isNullOrEmpty()) GONE else VISIBLE

        addView(image)

        title.text = text

        addView(title)

        this.value.text = value ?: ""
        this.value.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        this.value.visibility = if (value.isNullOrEmpty()) GONE else VISIBLE

        addView(this.value)

        arrow.scaleType = ImageView.ScaleType.FIT_CENTER
        arrow.setImageBitmap(Icons.icon("pl_skip"))
        arrow.alpha = 0.6f
        arrow.visibility = if (chevron) VISIBLE else GONE

        addView(arrow)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        var left = 0

        if (image.visibility != GONE) {
            // `Width="24" Height="24" Margin="0,0,16,0"` в оригинале.
            image.frame(0, (height - dp(SHEET_ICON)) / 2, dp(SHEET_ICON), dp(SHEET_ICON))

            left = dp(SHEET_ICON) + dp(16f)
        }

        var right = width

        if (arrow.visibility != GONE) {
            // Стрелка: `Width="16" Margin="14,0,0,0"`.
            right -= dp(SHEET_CHEVRON)

            arrow.frame(
                right, (height - dp(SHEET_CHEVRON)) / 2,
                dp(SHEET_CHEVRON), dp(SHEET_CHEVRON)
            )

            right -= dp(14f)
        }

        if (value.visibility != GONE) {
            /**
             * Значение прижато к стрелке и берёт ровно столько, сколько
             * ему нужно: в оригинале это столбец `Auto`, а название — `*`.
             */
            val wanted = Metrics.textWidth(value.text.toString(), Fonts.regular, 14f)

            val valueWidth = minOf(wanted + dp(8f), (right - left) / 2)

            value.frame(right - valueWidth, 0, valueWidth, height)

            right -= valueWidth
        }

        title.frame(left, 0, maxOf(0, right - left), height)
    }
}

/** Строка канала: кружок 36, имя, собачка под ним, галочка справа. */
class AccountRow(
    context: Context,
    dark: Boolean,
    name: String,
    handle: String?,
    avatar: String?,
    chosen: Boolean
) : SheetRowView(context, dark) {

    private val photo = RoundedImage(context)

    private val title = label(
        context, if (chosen) Fonts.semiBold else Fonts.regular, 15f, primary(), 1
    )

    private val under = label(context, Fonts.regular, 12f, secondary(), 1)

    private val check = label(context, Fonts.regular, 16f, Theme.ACCENT_BLUE, 1)

    init {
        highlights = true

        photo.circular = true
        photo.placeholderColor = Theme.avatarPlaceholder

        addView(photo)

        title.text = name
        under.text = handle ?: ""

        check.text = if (chosen) "✓" else ""
        check.gravity = Gravity.END or Gravity.CENTER_VERTICAL

        addView(title)
        addView(under)
        addView(check)

        if (!avatar.isNullOrEmpty()) {
            ImageLoader.loadInto(photo, avatar, SHEET_PHOTO)
        }
    }

    override fun rowHeight(width: Int): Int = dp(SHEET_ACCOUNT)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        val side = dp(SHEET_PHOTO)

        photo.frame(0, (height - side) / 2, side, side)

        val left = side + dp(12f)
        val right = dp(28f)

        val text = width - left - right

        val nameHeight = dp(18f)
        val underHeight = dp(15f)

        val top = (height - nameHeight - underHeight) / 2

        title.frame(left, top, text, nameHeight)
        under.frame(left, top + nameHeight, text, underHeight)

        check.frame(width - right, 0, right, height)
    }
}

/**
 * Меню плеера — порт шестерёнки из `CustomVideoPlayer`.
 *
 * Качество, скорость, озвучка, субтитры. Каждое открывается своей
 * страницей того же листа.
 */
class PlayerMenu(context: Context, private val screen: PlayerScreen) :
    Sheet(context, loc("Настройки")) {

    override val dark: Boolean get() = true

    init {
        add(
            section("pl_quality", loc("Качество"), qualityTitle()) {
                dismiss()

                QualityMenu(context).show()
            }
        )

        add(
            section("pl_speed", loc("Скорость воспроизведения"), rateTitle()) {
                dismiss()

                RateMenu(context).show()
            }
        )

        val tracks = PlayerEngine.audioTracks()

        if (tracks.size > 1) {
            add(
                section("pl_speed", loc("Аудиодорожка"), null) {
                    dismiss()

                    AudioMenu(context).show()
                }
            )
        }

        if (screen.tracks().isNotEmpty()) {
            add(
                section("pl_comments", loc("Субтитры"), captionTitle()) {
                    dismiss()

                    CaptionMenu(context, screen).show()
                }
            )

            /**
             * Сдвиг субтитров — только когда они включены: двигать
             * нечего, пока дорожка не выбрана.
             */
            if (screen.currentSubtitleTrack() != null) {
                add(
                    section("pl_speed", loc("Сдвиг по времени"), offsetTitle()) {
                        dismiss()

                        SubtitleOffsetMenu(context, screen).show()
                    }
                )
            }
        }

        /** «Перезагрузить видео» — тот же поток заново, с того же места. */
        add(
            command("pl_reload", loc("Перезагрузить видео")) {
                PlayerEngine.pickHeight(PlayerEngine.pickedHeight)
            }
        )

        /** То же окно, что на сайте по правой кнопке: кодеки, сеть, буфер. */
        add(
            command("pl_quality", loc("Статистика для сисадминов")) {
                screen.toggleStats()
            }
        )
    }

    /**
     * Подпись строки «Качество»: что выбрано и, если это не одно и то же,
     * что играет.
     *
     * Выбранное и играющее — разные вещи. На подаче ступень назначает
     * сервер: попросив 1080p, легко смотреть 720p, и по одной подписи
     * этого было не понять.
     */
    private fun qualityTitle(): String {
        val picked = PlayerEngine.pickedHeight

        val name = if (picked > 0) "${picked}p" else loc("Авто")

        val playing = PlayerEngine.playingHeight()

        if (playing <= 0 || playing == picked) {
            return name
        }

        return "$name · ${playing}p"
    }

    private fun rateTitle(): String = rateName(PlayerEngine.rate())

    private fun offsetTitle(): String = subtitleOffsetTitle()

    private fun captionTitle(): String =
        screen.currentSubtitleTrack()?.displayName() ?: loc("Выключены")
}

/**
 * Выбор качества.
 *
 * Ступени берутся у разобранных дорожек, а на подаче — у неё самой:
 * там дорожек с адресами нет вовсе, есть только описания.
 *
 * Ступень выше той, что тянет декодер, **не запрещается**, но помечается:
 * потолок здесь совет, а не запрет. Иначе человек включит её сам
 * и получит звук без картинки, не поняв почему.
 */
class QualityMenu(context: Context) : Sheet(context, loc("В каком качестве смотреть")) {

    override val dark: Boolean get() = true

    init {
        val heights = PlayerEngine.heights.ifEmpty { Streams.sabrHeights() }

        val picked = PlayerEngine.pickedHeight

        /**
         * Какая ступень идёт на самом деле — её и отмечаем словом.
         *
         * Галочка стоит у выбранной, а «сейчас» дописывается к играющей:
         * при «Авто» галочка иначе не говорила бы ни о чём.
         */
        val playing = PlayerEngine.playingHeight()

        add(
            row(loc("Авто"), null, picked == 0) {
                PlayerEngine.pickHeight(0)
            }
        )

        // Сверху вниз, от крупного к мелкому — привычный порядок.
        for (height in heights.sortedDescending()) {
            var title = "${height}p"

            if (Streams.isBeyondDevice(height)) {
                title += loc(" — может не пойти")
            }

            if (height == playing) {
                title = "$title · " + loc("сейчас")
            }

            add(
                row(title, null, height == picked) {
                    PlayerEngine.pickHeight(height)
                }
            )
        }
    }
}

/**
 * Выбор скорости.
 *
 * Заказанное значение читается обратно — то самое правило, ради которого
 * в оригинале ушло полдня: `AVPlayer` на просьбу ускориться выбирал
 * ближайшее, что умел, и ближайшим оказывался ноль.
 */
class RateMenu(context: Context) : Sheet(context, loc("Скорость воспроизведения")) {

    override val dark: Boolean get() = true

    init {
        /**
         * Список длиннее, чем в оригинале, и это осознанно.
         *
         * Там он обрывается на единице: `AVPlayer` по их подаче идёт
         * через прокси как HLS и на просьбу ускориться **встаёт** —
         * `setRate:` берёт ближайшее, что умеет, а ближайшим оказывается
         * ноль. `ExoPlayer` разгоняется без этого, запрещать здесь нечего.
         */
        val rates = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

        val now = PlayerEngine.rate()

        for (rate in rates) {
            add(
                row(rateName(rate), null, Math.abs(rate - now) < 0.01f) {
                    PlayerEngine.setRate(rate)
                }
            )
        }
    }
}

/** Выбор озвучки — у подачи свои, у готовых дорожек свои. */
class AudioMenu(context: Context) : Sheet(context, loc("Аудиодорожка")) {

    override val dark: Boolean get() = true

    init {
        val tracks = PlayerEngine.audioTracks()

        if (tracks.isEmpty()) {
            add(row(loc("Другой озвучки нет"), null, false) {})
        }

        for (track in tracks) {
            add(
                row(track.title, null, track.isDefault) {
                    PlayerEngine.pickAudioTrack(track.id)
                }
            )
        }
    }
}

/** Выбор дорожки субтитров. */
class CaptionMenu(context: Context, private val screen: PlayerScreen) :
    Sheet(context, loc("Субтитры")) {

    override val dark: Boolean get() = true

    init {
        val now = screen.currentSubtitleTrack()

        if (screen.tracks().isEmpty()) {
            add(row(loc("У этого ролика их нет"), null, false) {})
        }

        add(
            row(loc("Выключены"), null, now == null) {
                screen.pickSubtitles(null)
            }
        )

        for (track in screen.tracks()) {
            add(
                row(track.displayName(), null, track === now) {
                    screen.pickSubtitles(track)
                }
            )
        }
    }
}

/**
 * Выбор канала учётной записи.
 *
 * У одной записи Google бывает и личный канал, и бренд-каналы, и детский.
 * Какой из них считать своим, сервер решает сам — и решает не всегда так,
 * как ждёт человек: у одного из наших он выбрал канал YouTube Kids.
 */
class AccountSheet(context: Context) : Sheet(context, loc("Аккаунты")) {

    init {
        add(row(loc("Запрашиваем…"), null, false) {})

        async {
            val accounts = Api.accountsList()

            main { fill(accounts) }
        }
    }

    private fun fill(accounts: List<Account>) {
        clearRows()

        if (accounts.isEmpty()) {
            add(row(loc("Каналов не нашлось"), null, false) {})

            return
        }

        val chosen = Api.activeAccountPage

        for (item in accounts) {
            val marked = item.page == chosen ||
                (chosen.isNullOrEmpty() && item.primary)

            add(
                account(item.name, item.handle, item.avatar, marked) {
                    /**
                     * Вторая примета канала сохраняется вместе с первой.
                     *
                     * Сервер присылает обе разом и не говорит, какую ждёт
                     * обратно. Шлём `pageId`; получив 401, приложение
                     * перейдёт на пару «профиль||владелец» — но только
                     * если она у нас есть.
                     */
                    Api.setActiveAccountPage(item.page, item.datasync)
                }
            )
        }
    }
}

/**
 * Панель колокольчика — те же четыре строки, что
 * в `SubscriptionMenuBottomSheetPanel`: все оповещения, по интересам,
 * никаких и отписаться.
 *
 * «По интересам» помечается и при неизвестном состоянии: сервер
 * не всегда присылает его вовсе, а это его же умолчание.
 */
class BellSheet(
    context: Context,
    private val state: Int,
    private val onPick: (Int) -> Unit,
    private val onUnsubscribe: () -> Unit
) : Sheet(context, loc("Оповещения")) {

    init {
        add(
            row(loc("Все"), null, state == ru.computershik.troubadour.net.Notifications.ALL) {
                onPick(ru.computershik.troubadour.net.Notifications.ALL)
            }
        )

        val personalized =
            state == ru.computershik.troubadour.net.Notifications.PERSONALIZED ||
                state == ru.computershik.troubadour.net.Notifications.UNKNOWN

        add(
            row(loc("По интересам"), null, personalized) {
                onPick(ru.computershik.troubadour.net.Notifications.PERSONALIZED)
            }
        )

        add(
            row(loc("Нет"), null, state == ru.computershik.troubadour.net.Notifications.NONE) {
                onPick(ru.computershik.troubadour.net.Notifications.NONE)
            }
        )

        add(command("unsubscribe", loc("Отменить подписку")) { onUnsubscribe() })
    }
}

/**
 * Название скорости: «Обычная» для единицы, иначе число с крестиком.
 *
 * Лишний ноль убирается — «1,5×», а не «1,50×», и «2×», а не «2.0×».
 */
fun rateName(rate: Float): String {
    if (rate == 1.0f) {
        return loc("Обычная")
    }

    var number = String.format(java.util.Locale.US, "%.2f", rate)

    while (number.endsWith("0")) {
        number = number.dropLast(1)
    }

    if (number.endsWith(".")) {
        number = number.dropLast(1)
    }

    return number + "×"
}

/**
 * Сплошной текст в листе — описание ролика и подобное.
 *
 * У оригинала это не строка списка, а свой блок: сверху приглушённая
 * подпись со сведениями («1,2 млн просмотров • 3 дня назад»), под ней
 * сам текст, и между ними просвет `YTSheetNoteGap = 10`.
 */
class TextRow(
    context: Context,
    dark: Boolean,
    private val note: String?,
    private val body: String
) : SheetRowView(context, dark) {

    private val noteLabel = label(context, Fonts.regular, 12f, secondary(), 1)
    private val bodyLabel = label(context, Fonts.regular, 14f, primary(), 0)

    init {
        highlights = false
        isClickable = false

        noteLabel.text = note ?: ""
        noteLabel.visibility = if (note.isNullOrEmpty()) GONE else VISIBLE

        bodyLabel.text = body
        bodyLabel.gravity = Gravity.TOP

        addView(noteLabel)
        addView(bodyLabel)
    }

    private fun noteHeight(): Int =
        if (note.isNullOrEmpty()) 0 else Metrics.lineHeight(Fonts.regular, 12f) + dp(10f)

    override fun rowHeight(width: Int): Int {
        if (width <= 0) {
            return dp(SHEET_ROW)
        }

        return noteHeight() + Metrics.textHeight(body, Fonts.regular, 14f, width, 0)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t

        val top = noteHeight()

        if (top > 0) {
            noteLabel.frame(0, 0, width, top - dp(10f))
        }

        bodyLabel.frame(0, top, width, maxOf(0, height - top))
    }
}

/**
 * Описание ролика — порт `openDescription`.
 *
 * Открывается нажатием по названию: отдельного места под описание
 * на странице нет ни в оригинале, ни здесь. Сведения о просмотрах и дате
 * идут той же подписью сверху.
 */
class DescriptionSheet(
    context: Context,
    private val note: String?,
    private val body: String
) : Sheet(context, loc("Описание")) {

    init {
        add(TextRow(context, dark, note, body))
    }
}

/**
 * Качество для скачивания — порт `showQualityMenu:`.
 *
 * Меню строится по тому, что у ролика есть, а не по постоянному списку.
 * Прежде в оригинале предлагали от 144p до 1080p всем подряд: человек
 * выбирал 1080p, а загрузчик молча брал ближайшее, что нашлось.
 *
 * Открывается и тогда, когда ролик уже скачан: взять его же в другом
 * качестве не менее нужно, а убрать скачанное можно повторным выбором
 * того же качества.
 */
class DownloadQualitySheet(
    context: Context,
    private val videoId: String,
    private val title: String,
    private val details: ru.computershik.troubadour.net.VideoDetails?
) : Sheet(context, loc("Качество для скачивания")) {

    init {
        val heights = ru.computershik.troubadour.player.PlayerEngine.heights
            .ifEmpty { Streams.sabrHeights() }

        if (heights.isEmpty()) {
            add(row(loc("Скачать не выйдет"), null, false) {})
        }

        for (height in heights.sortedDescending()) {
            /**
             * Отметка — у **этого** качества, а не у ролика вообще.
             *
             * Прежде отмеченной оказывалась любая ступень, о которой
             * была хоть какая-то запись: недокачанная, отменённая,
             * не скачавшаяся. Нажатие на неё предлагало убрать
             * несуществующий файл, и скачать заново было нельзя.
             */
            val saved = ru.computershik.troubadour.net.Downloads.find(videoId, height)

            val chosen = saved?.state == ru.computershik.troubadour.net.Download.DONE

            val busy = saved?.state == ru.computershik.troubadour.net.Download.RUNNING ||
                saved?.state == ru.computershik.troubadour.net.Download.QUEUED

            val note = when {
                chosen -> sizeText(saved?.received ?: 0L)
                busy -> loc("Качается")
                saved?.state == ru.computershik.troubadour.net.Download.FAILED ->
                    saved.error
                else -> null
            }

            add(
                row("${height}p", note, chosen) {
                    if (busy) {
                        /**
                         * Начатое второй раз не начинают: нажатие здесь
                         * значит «передумал».
                         */
                        android.app.AlertDialog.Builder(context)
                            .setTitle(loc("Отменить закачку?"))
                            .setNegativeButton(loc("Отмена"), null)
                            .setPositiveButton(loc("Отменить закачку")) { _, _ ->
                                ru.computershik.troubadour.net.Downloads
                                    .cancel(videoId, height)
                            }
                            .show()

                        return@row
                    }

                    if (chosen) {
                        /**
                         * Выбрали то, что уже скачано, — значит хотят
                         * это убрать: другого смысла у такого нажатия
                         * нет, качать заново незачем. Спрашиваем
                         * подтверждение — удаление файла необратимо.
                         */
                        android.app.AlertDialog.Builder(context)
                            .setTitle(loc("Убрать скачанное?"))
                            .setMessage(loc("Файл ролика будет удалён с устройства."))
                            .setNegativeButton(loc("Отмена"), null)
                            .setPositiveButton(loc("Убрать")) { _, _ ->
                                ru.computershik.troubadour.net.Downloads
                                    .remove(videoId, height)
                            }
                            .show()

                        return@row
                    }

                    /**
                     * Разрешение на запись — до очереди, а не посреди неё.
                     *
                     * Ролик кладётся в общие «Загрузки», и на Android
                     * с шестой по девятую версию для этого нужно спросить.
                     * Спрашивать посреди закачки поздно: к тому времени
                     * файл уже некуда класть, а окно с вопросом всплывёт
                     * поверх чужого дела.
                     */
                    ru.computershik.troubadour.net.PublicStore.ask(context)

                    val item = ru.computershik.troubadour.net.Download()

                    item.videoId = videoId
                    item.title = title
                    item.channelTitle = details?.channelTitle ?: ""
                    /**
                     * Превью берём привычным адресом.
                     *
                     * У страницы ролика своего превью в ответе нет —
                     * оно там лежит внутри кадра, — а `hqdefault` есть
                     * у любого ролика и не зависит от ответа.
                     */
                    item.thumbnail =
                        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    item.height = height

                    /**
                     * «Спрашивать каждый раз» — спрашиваем и тут.
                     *
                     * Дорожки берём у плеера: ответ `/player` для этого
                     * ролика уже разобран, и второй запрос ради перечня
                     * был бы лишним. Дорожка одна — спрашивать не о чем.
                     */
                    val tracks = PlayerEngine.audioTracks()

                    if (Settings.downloadAudioLanguage == AudioLanguage.ASK &&
                        tracks.size > 1
                    ) {
                        DownloadAudioSheet(context, tracks) { chosen ->
                            item.audioTrack = chosen

                            ru.computershik.troubadour.net.Downloads.enqueue(item)
                        }.show()

                        return@row
                    }

                    ru.computershik.troubadour.net.Downloads.enqueue(item)
                }
            )
        }
    }
}

/**
 * Какую озвучку скачать — при ладе «спрашивать каждый раз».
 *
 * Отдельный лист, а не [AudioMenu]: тот переключает дорожку у идущего
 * воспроизведения, а здесь ответ нужен не плееру, а очереди загрузок.
 */
class DownloadAudioSheet(
    context: Context,
    tracks: List<ru.computershik.troubadour.player.AudioTrack>,
    onPick: (String) -> Unit
) : Sheet(context, loc("Язык звука при скачивании")) {

    init {
        for (track in tracks) {
            add(
                row(track.title, null, track.isDefault) {
                    onPick(track.id)
                }
            )
        }
    }
}

/**
 * В каком качестве смотреть скачанное — порт `YTOpenDownload`.
 *
 * Показывается только когда качеств у ролика больше одного: спрашивать
 * о выборе, которого нет, — пустая задержка. Объём стоит рядом
 * с качеством: по нему и выбирают, когда место на устройстве на исходе.
 */
class DownloadedQualitySheet(
    context: Context,
    items: List<ru.computershik.troubadour.net.Download>,
    onPick: (ru.computershik.troubadour.net.Download) -> Unit
) : Sheet(context, loc("В каком качестве смотреть")) {

    init {
        for (item in items.sortedByDescending { it.height }) {
            val note = sizeText(if (item.total > 0) item.total else item.received)

            add(
                row(qualityTitle(item.height), note, false) {
                    onPick(item)
                }
            )
        }
    }
}

/** Подпись сдвига: «+1,00 с» — со знаком, чтобы направление было видно. */
fun subtitleOffsetTitle(): String {
    val offset = ru.computershik.troubadour.Settings.subtitleOffset

    return ru.computershik.troubadour.locF(
        "%@%.2f с", if (offset >= 0) "+" else "", offset
    )
}

/**
 * Сдвиг субтитров — порт пятой страницы меню.
 *
 * Правится шагами, а не списком: и в оригинале это две кнопки «раньше»
 * и «позже» с шагом в четверть секунды. Список из четырёх десятков
 * значений тут был бы неудобнее.
 */
class SubtitleOffsetMenu(context: Context, private val screen: PlayerScreen) :
    Sheet(context, loc("Сдвиг субтитров")) {

    override val dark: Boolean get() = true

    init {
        add(command("pl_skip", loc("Раньше на 0,25 с")) { nudge(0.25) })
        add(command("pl_back", loc("Позже на 0,25 с")) { nudge(-0.25) })

        add(
            command("pl_reload", loc("Вернуть обычный (+2,00 с)")) {
                apply(2.0)
            }
        )

        add(row(subtitleOffsetTitle(), null, false) {})
    }

    private fun nudge(delta: Double) {
        apply(ru.computershik.troubadour.Settings.subtitleOffset + delta)
    }

    private fun apply(seconds: Double) {
        ru.computershik.troubadour.Settings.subtitleOffset = seconds

        ru.computershik.troubadour.Log.d {
            "[YouTube/Субтитры] Сдвиг: ${subtitleOffsetTitle()}"
        }

        // Строку перекладываем сразу, не дожидаясь следующей.
        screen.refreshSubtitles()
    }
}
