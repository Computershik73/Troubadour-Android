package ru.computershik.troubadour.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * Превью со скруглёнными углами и кружок канала.
 *
 * В UWP-версии от той же беды спасались иначе: поверх картинки клали PNG
 * с вырезанными углами (`rounding.png`, `rounding_up.png`), закрашенными
 * цветом фона. Приём рабочий, но он подразумевает, что под картинкой ровно
 * тот фон, в который PNG покрашен, — и на другом фоне углы получились бы
 * чужого цвета.
 *
 * В iOS-версии кадр обрезался по контуру один раз, а результат клался
 * в кеш и отдавался слою через `layer.contents`: `cornerRadius` вместе
 * с `masksToBounds` заставлял систему рисовать слой отдельным проходом,
 * и в прокручиваемом списке на iPhone 4 это было заметно.
 *
 * Здесь скругление тоже настоящее, но **без кеша готовых кадров**,
 * и это осознанная разница. `BitmapShader` рисует ту же картинку через
 * матрицу прямо в свой холст: отдельного прохода композитора нет,
 * второй копии кадра в памяти — тоже. Оригиналу такого инструмента
 * не предлагалось, оттого и кеш.
 */
class RoundedImage(context: Context) : View(context), ImageLoader.Target {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val matrix = Matrix()

    private var bitmap: Bitmap? = null
    private var shader: BitmapShader? = null

    /** Радиус скругления; для кружка канала ставится в половину стороны. */
    var cornerRadius: Float = Metrics.dpf(Metrics.THUMB_RADIUS)
        set(value) {
            field = value
            invalidate()
        }

    /** Круглый — то же, что радиус в половину меньшей стороны. */
    var circular: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Цвет, пока картинки нет: `VideoPlaceholderColor` либо `AvatarPlaceholder`. */
    var placeholderColor: Int = Theme.surfaceAlt
        set(value) {
            field = value
            invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun setImage(image: Bitmap?) {
        bitmap = image

        shader = if (image != null) {
            BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        } else {
            null
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) {
            return
        }

        val radius = if (circular) {
            minOf(width, height) / 2f
        } else {
            minOf(cornerRadius, minOf(width, height) / 2f)
        }

        box.set(0f, 0f, width.toFloat(), height.toFloat())

        val picture = bitmap
        val brush = shader

        if (picture == null || brush == null) {
            placeholderPaint.color = placeholderColor

            canvas.drawRoundRect(box, radius, radius, placeholderPaint)

            return
        }

        /**
         * Заполнение с обрезкой по краям — то же, что `scaleAspectFill`.
         *
         * Картинка растягивается по большей стороне и подрезается
         * по меньшей. У вертикальных превью это как раз то, чего делать
         * **не надо**: у них пропорция 9:16, и в место под 16:9 от кадра
         * осталась бы узкая полоса посередине. Поэтому карточка
         * вертикального ролика заводит вид другой пропорции, а не правит
         * заполнение здесь.
         */
        val scale = maxOf(
            width.toFloat() / picture.width,
            height.toFloat() / picture.height
        )

        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            (width - picture.width * scale) / 2f,
            (height - picture.height * scale) / 2f
        )

        brush.setLocalMatrix(matrix)

        paint.shader = brush

        canvas.drawRoundRect(box, radius, radius, paint)
    }

    companion object {

        /**
         * Сбрасывает кеш готовых кадров.
         *
         * Оставлено ради того же имени, что в оригинале, — но кеша здесь
         * нет: `BitmapShader` рисует исходную картинку, а её держит
         * [ImageLoader]. Сбрасывать нечего, и это правильный ответ,
         * а не заглушка.
         */
        @JvmStatic
        fun trimFrames() {
            // Ничего: готовых кадров мы не храним.
        }
    }
}
