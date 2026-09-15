package ru.computershik.troubadour.ui

import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.Settings
import ru.computershik.troubadour.net.Http
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Загрузка превью.
 *
 * Своя, а не готовая библиотека (Glide, Picasso, Coil): здесь важны ровно
 * четыре приёма, которые нужно держать под контролем, и ещё один довод
 * сверху — все они требуют AndroidX новее того, что живёт на API 16.
 *
 * Первый и главный приём — **размер запрашиваемого файла**. У i.ytimg.com
 * для каждого ролика лежит полдюжины готовых вариантов кадра, и различаются
 * они не параметром, а именем файла: `default.jpg` — 120×90,
 * `mqdefault.jpg` — 320×180, `hqdefault.jpg` — 480×360, `sddefault.jpg` —
 * 640×480, `maxresdefault.jpg` — 1280×720. Разница в весе между
 * `mqdefault` и `maxresdefault` — примерно двадцатикратная, а карточке
 * на телефоне нужен как раз `mqdefault`. UWP-версия всегда запрашивала
 * `mqdefault` и на этом останавливалась; здесь ступень выбирается
 * по ширине места, чтобы на планшете карточка не была мыльной.
 *
 * У кружков каналов то же самое, но иначе: адреса `yt3.ggpht.com`
 * заканчиваются на `=s88-c-k-c0x00ffffff-no-rj`, где `s88` — сторона
 * в пикселях. Её и переписываем.
 *
 * Второй приём — **уменьшение при декодировании**. В полный размер кадр
 * 1280×720 занимает 3,7 МБ; два десятка карточек — и приложение
 * закрывается по памяти. На устройстве с четвертью гигабайта это не теория.
 *
 * Третий — **порядок**. Очередь разбирается с конца: во время быстрой
 * прокрутки набегают сотни запросов, и по порядку поступления видимые
 * сейчас карточки ждали бы, пока догрузятся давно уехавшие.
 *
 * Четвёртый — **отмена**. Запрос, чья карточка уже отдана другому ролику,
 * до сети не доходит вовсе.
 *
 * Дисковый кеш отдельно не нужен: превью отдаются с длинным `max-age`,
 * файлы складывает у себя OkHttp внутри [Http].
 */
object ImageLoader {

    /** Куда можно поставить картинку. */
    interface Target {
        fun setImage(image: Bitmap?)
    }

    /**
     * Сколько памяти отдаём под разобранные картинки.
     *
     * Восьмая доля того, что система обещает приложению. У неё это
     * `getMemoryClass` в мегабайтах: на устройстве 2012 года — 48,
     * на нынешнем — 256 и больше. Восьмая доля выходит от шести
     * мегабайт до тридцати с лишним — примерно два-три экрана карточек.
     */
    private val cache: LruCache<String, Bitmap> by lazy {
        val runtime = Runtime.getRuntime()

        val limit = (runtime.maxMemory() / 8).toInt().coerceIn(
            4 * 1024 * 1024, 48 * 1024 * 1024
        )

        Log.d { "[YouTube/Превью] Под картинки отведено ${limit / 1024 / 1024} МБ" }

        object : LruCache<String, Bitmap>(limit) {
            override fun sizeOf(key: String, value: Bitmap): Int = byteCount(value)
        }
    }

    private fun byteCount(bitmap: Bitmap): Int {
        // `getByteCount` появился в API 12; ниже нашей границы это не уходит.
        return bitmap.rowBytes * bitmap.height
    }

    /**
     * Кто чего ждёт.
     *
     * Ключ — сам вид: если карточку переработали под другой ролик,
     * прежний запрос до сети не доходит.
     */
    private val wanted = HashMap<Target, String>()

    /** Очередь; разбирается **с конца**. */
    private val queue = ArrayDeque<Job>()

    private class Job(val url: String, val width: Int, val target: Target?, val done: ((Bitmap?) -> Unit)?)

    /**
     * Потоков немного, и это не бережливость.
     *
     * Восемь одновременных запросов к одному узлу не быстрее четырёх:
     * они начинают мешать друг другу за канал, и первая картинка
     * появляется позже. Плюс каждый разбор картинки — это её полный
     * размер в памяти на время разбора.
     */
    private val pool = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "troubadour-images").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
        }
    }

    private var running = 0

    /**
     * Ставит превью в вид.
     *
     * @param targetWidth ширина в точках, в которой картинка реально будет
     *   показана: по ней и считается, во сколько раз уменьшать и какую
     *   ступень просить у CDN.
     */
    fun loadInto(target: Target, url: String?, targetWidth: Float) {
        if (url.isNullOrEmpty()) {
            target.setImage(null)

            return
        }

        val width = pickWidth(targetWidth)
        val address = resize(url, width)

        synchronized(wanted) {
            wanted[target] = address
        }

        cache.get(address)?.let {
            target.setImage(it)

            return
        }

        target.setImage(null)

        enqueue(Job(address, width, target, null))
    }

    /** То же, но результат отдаётся замыканием — для тех, кто рисует сам. */
    fun loadUrl(url: String?, targetWidth: Float, done: (Bitmap?) -> Unit) {
        if (url.isNullOrEmpty()) {
            done(null)

            return
        }

        val width = pickWidth(targetWidth)
        val address = resize(url, width)

        cache.get(address)?.let {
            done(it)

            return
        }

        enqueue(Job(address, width, null, done))
    }

    /**
     * Синхронная загрузка — для тех, кто уже в фоне и умеет ждать.
     *
     * Нужна карточке уведомления: она собирается в фоновом потоке
     * и без обложки не имеет смысла.
     */
    fun fetch(url: String?, targetWidth: Float): Bitmap? {
        if (url.isNullOrEmpty()) {
            return null
        }

        val width = pickWidth(targetWidth)
        val address = resize(url, width)

        cache.get(address)?.let { return it }

        return decode(address, width)
    }

    private fun enqueue(job: Job) {
        synchronized(queue) {
            queue.addLast(job)
        }

        pump()
    }

    private fun pump() {
        synchronized(queue) {
            if (running >= 3) {
                return
            }

            // С конца: видимое сейчас важнее того, что уже уехало.
            val job = queue.pollLast() ?: return

            running++

            pool.execute {
                work(job)

                synchronized(queue) {
                    running--
                }

                pump()
            }
        }
    }

    private fun work(job: Job) {
        val target = job.target

        // Карточку уже отдали другому ролику — в сеть не идём вовсе.
        if (target != null) {
            val still = synchronized(wanted) { wanted[target] }

            if (still != job.url) {
                return
            }
        }

        val bitmap = decode(job.url, job.width)

        main {
            if (target != null) {
                val still = synchronized(wanted) { wanted[target] }

                if (still != job.url) {
                    return@main
                }

                target.setImage(bitmap)
            }

            job.done?.invoke(bitmap)
        }
    }

    private fun decode(url: String, width: Int): Bitmap? {
        val builder = Http.request(url) ?: return null

        val response = Http.send(builder.build(), 8 * 1024 * 1024)

        val body = response.body

        if (!response.isSuccessful || body == null || body.isEmpty()) {
            return null
        }

        return try {
            /**
             * Уменьшение при декодировании.
             *
             * Сперва читаем одни размеры (`inJustDecodeBounds`), считаем
             * степень двойки и только потом разбираем по-настоящему.
             * Иначе полный кадр 1280×720 займёт 3,7 МБ — и займёт их даже
             * тогда, когда показать его надо в 180 точек.
             */
            val bounds = BitmapFactory.Options()

            bounds.inJustDecodeBounds = true

            BitmapFactory.decodeByteArray(body, 0, body.size, bounds)

            val options = BitmapFactory.Options()

            options.inSampleSize = sampleSize(bounds.outWidth, width)

            /**
             * `RGB_565` — вдвое меньше `ARGB_8888`.
             *
             * Прозрачности у превью нет вовсе, а разницу в глубине цвета
             * на кадре 320×180 не видно. Оригинал экономил тем же:
             * там кадр рисовался в контекст без альфа-канала.
             */
            options.inPreferredConfig = Bitmap.Config.RGB_565

            val bitmap = BitmapFactory.decodeByteArray(body, 0, body.size, options)

            if (bitmap != null) {
                cache.put(url, bitmap)
            }

            bitmap
        } catch (error: Throwable) {
            Log.d { "[YouTube/Превью] Не разобралась: ${error.message}" }

            null
        }
    }

    private fun sampleSize(source: Int, wanted: Int): Int {
        if (source <= 0 || wanted <= 0) {
            return 1
        }

        var size = 1

        while (source / (size * 2) >= wanted) {
            size *= 2
        }

        return size
    }

    /**
     * Какую ступень просить у CDN.
     *
     * Настройка главнее: там человек мог назвать ступень прямо.
     * «Авто» означает «под размер карточки на экране».
     */
    private fun pickWidth(targetWidth: Float): Int {
        val chosen = Settings.thumbnailWidth

        if (chosen > 0) {
            return chosen
        }

        val pixels = Metrics.dp(targetWidth)

        return when {
            pixels <= 160 -> 120
            pixels <= 360 -> 320
            pixels <= 520 -> 480
            pixels <= 700 -> 640
            else -> 1280
        }
    }

    /**
     * Переписывает адрес под нужную ступень.
     *
     * У i.ytimg.com ступень — это имя файла, у yt3.ggpht.com — кусок
     * `=s88` в хвосте.
     */
    private fun resize(url: String, width: Int): String {
        if (url.contains("ggpht.com") || url.contains("=s")) {
            val marker = url.indexOf("=s")

            if (marker > 0) {
                var tail = marker + 2

                while (tail < url.length && url[tail].isDigit()) {
                    tail++
                }

                return url.substring(0, marker + 2) + width + url.substring(tail)
            }

            return url
        }

        if (!url.contains("/vi/") && !url.contains("/vi_webp/")) {
            return url
        }

        /**
         * Выше `hqdefault` не поднимаемся.
         *
         * У i.ytimg.com ступеней пять: `default` 120×90, `mqdefault`
         * 320×180, `hqdefault` 480×360, `sddefault` 640×480
         * и `maxresdefault` 1280×720. Но две верхние есть далеко
         * не у всякого ролика: у старых и у залитых в низком разрешении
         * их просто нет, и CDN отвечает отказом 404. Карточка тогда
         * остаётся пустой — это и выглядело как сломанный подбор
         * качества превью, особенно на широких карточках, где «авто»
         * просило самую верхнюю ступень.
         *
         * `hqdefault` есть всегда, а разницы с `sddefault` на карточке
         * не видно. Так же поступает и iOS-версия.
         */
        val name = when {
            width <= 120 -> "default.jpg"
            width <= 320 -> "mqdefault.jpg"
            else -> "hqdefault.jpg"
        }

        val slash = url.lastIndexOf('/')

        if (slash < 0) {
            return url
        }

        val question = url.indexOf('?', slash)

        val query = if (question > 0) url.substring(question) else ""

        return url.substring(0, slash + 1) + name + query
    }

    /**
     * Выбрасывает все разобранные картинки.
     *
     * Нужно при смене качества превью: сняты они были под прежнюю ступень,
     * и без сброса новая настройка не действовала бы, пока кеш сам
     * не вытеснит старое.
     */
    fun dropCache() {
        cache.evictAll()
    }

    /** Освобождает память, когда система просит потесниться. */
    fun trim() {
        /**
         * Ужать наполовину умеет только API 17 и новее; ниже — выбрасываем
         * всё. Просят нас об этом, когда памяти уже нет, и половина
         * от «нет» — всё равно ничего.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            cache.trimToSize(cache.size() / 2)
        } else {
            cache.evictAll()
        }

        synchronized(queue) {
            queue.clear()
        }
    }
}
