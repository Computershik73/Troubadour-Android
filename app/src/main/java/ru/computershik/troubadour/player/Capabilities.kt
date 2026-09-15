package ru.computershik.troubadour.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import ru.computershik.troubadour.Log

/**
 * Что тянет декодер этого устройства.
 *
 * **Здесь новая платформа отвечает на вопрос, на который старая отвечать
 * отказывалась** — и это, пожалуй, единственное место во всём переносе,
 * где кода стало не меньше, а больше, и не зря.
 *
 * В iOS-версии потолок разрешения был перечислением моделей: спросить
 * декодер до iOS 8 невозможно — VideoToolbox закрыт, AVFoundation о своих
 * возможностях не рассказывает. Таблица там не гадание, а именно
 * перечисление: набор железа у Apple закрытый и известен наперёд.
 *
 * Здесь набора нет. Устройств на Android 4.1 тысячи, и предсказать по
 * имени модели, что она декодирует, нельзя вовсе. Зато можно спросить —
 * `MediaCodecInfo` знает и профиль, и уровень, и (с API 21) прямо
 * предельные размеры с частотой кадров.
 *
 * Записка о переносе советует именно это: «Возможности железа спрашивать
 * у системы, а не описывать таблицей моделей. На Android это возможно —
 * грех не воспользоваться.» И там же предупреждение, ради которого всё:
 * просьба пользователя не является свидетельством о возможностях железа.
 * В оригинале границу «60 кадров с iPhone 4S» взяли по просьбе и
 * не проверили — а A5 декодирует H.264 только до 1080p при тридцати
 * кадрах, и вышел зелёный кадр с полосой мусора вместо картинки.
 */
object Capabilities {

    private var computed = false

    private var maxHeightValue = 1080
    private var sixtyValue = true

    /**
     * Потолок **для шестидесяти кадров** — он свой и всегда не выше общего.
     *
     * Раньше «тянет шестьдесят кадров» был один на все ступени, и этого
     * оказалось мало. GT-N8000 честно играет 720p60, а на 1080p60 его
     * декодер отвечает отказом:
     *
     *     format=…avc1.64002A, [1920, 1080], format_supported=NO_EXCEEDS_CAPABILITIES
     *     (Decoder failed: OMX.SEC.avc.dec)
     *
     * Потолок при этом честно показывал 1080p: тридцатикадровое 1080p
     * устройство и правда тянет. Считать надо не «тянет ли шестьдесят»,
     * а «до какой ступени тянет».
     */
    private var sixtyHeightValue = 1080

    /**
     * Уровни H.264 и то, что они разрешают.
     *
     * Числа не выдуманы: это таблица A-1 из самого стандарта — предельное
     * число макроблоков в кадре и в секунду. Макроблок это 16×16 точек,
     * так что из числа в кадре получается площадь, а из числа в секунду —
     * частота при этой площади.
     *
     * Нужна она только на API 16–20: с 21-го те же сведения отдаёт
     * `VideoCapabilities` прямо, без счёта.
     */
    private val LEVELS = mapOf(
        MediaCodecInfo.CodecProfileLevel.AVCLevel1 to (99 to 1485),
        MediaCodecInfo.CodecProfileLevel.AVCLevel1b to (99 to 1485),
        MediaCodecInfo.CodecProfileLevel.AVCLevel11 to (396 to 3000),
        MediaCodecInfo.CodecProfileLevel.AVCLevel12 to (396 to 6000),
        MediaCodecInfo.CodecProfileLevel.AVCLevel13 to (396 to 11880),
        MediaCodecInfo.CodecProfileLevel.AVCLevel2 to (396 to 11880),
        MediaCodecInfo.CodecProfileLevel.AVCLevel21 to (792 to 19800),
        MediaCodecInfo.CodecProfileLevel.AVCLevel22 to (1620 to 20250),
        MediaCodecInfo.CodecProfileLevel.AVCLevel3 to (1620 to 40500),
        MediaCodecInfo.CodecProfileLevel.AVCLevel31 to (3600 to 108000),
        MediaCodecInfo.CodecProfileLevel.AVCLevel32 to (5120 to 216000),
        MediaCodecInfo.CodecProfileLevel.AVCLevel4 to (8192 to 245760),
        MediaCodecInfo.CodecProfileLevel.AVCLevel41 to (8192 to 245760),
        MediaCodecInfo.CodecProfileLevel.AVCLevel42 to (8704 to 522240),
        MediaCodecInfo.CodecProfileLevel.AVCLevel5 to (22080 to 589824),
        MediaCodecInfo.CodecProfileLevel.AVCLevel51 to (36864 to 983040),

        /**
         * `AVCLevel52` объявлен с API 21, и проверка это отмечает —
         * но беды здесь нет: это `static final int`, он вшивается
         * в код при сборке, и обращения к системе не происходит вовсе.
         * На старой системе запись просто никогда не совпадёт: декодер,
         * не знающий такого уровня, о нём и не заявит.
         *
         * Значения уровней заданы стандартом H.264 и не меняются, так
         * что вшитое число не устареет.
         */
        MediaCodecInfo.CodecProfileLevel.AVCLevel52 to (36864 to 2073600)
    )

    private fun compute() {
        synchronized(this) {
            if (computed) {
                return
            }

            computed = true
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                computeModern()
            } else {
                computeByLevel()
            }
        } catch (error: Throwable) {
            /**
             * Спросить не вышло — берём осторожное.
             *
             * 720p и тридцать кадров играет всё, что вообще умеет H.264;
             * ошибиться в эту сторону значит показать чуть мельче,
             * а в другую — зелёный кадр вместо картинки.
             */
            maxHeightValue = 720
            sixtyValue = false
            sixtyHeightValue = 0

            Log.d { "[YouTube/Декодер] Спросить не вышло (${error.message}) — берём 720p30" }
        }

        Log.d {
            "[YouTube/Декодер] Потолок ${maxHeightValue}p, шестьдесят кадров " +
                if (sixtyValue) "тянет до ${sixtyHeightValue}p" else "не тянет"
        }
    }

    /**
     * Программный ли это декодер.
     *
     * Отличать их приходится по имени: `isHardwareAccelerated` появился
     * только в API 29, а нижняя граница у нас 16. Имена же заданы
     * соглашением: эталонные декодеры Android зовутся `OMX.google.*`,
     * а с восьмёрки — `c2.android.*`. Всё прочее ставит изготовитель
     * железа, и это его собственный декодер.
     *
     * Зачем отличать — видно на GT-N8000. Рядом с самсунговскими
     * `OMX.SEC.*` там лежит `OMX.google.h264.decoder`, и он заявляет
     * уровень 5.1, то есть формально 4K. Заявление это честное — отказа
     * не будет, — но означает оно «разберу», а не «успею»: разбор идёт
     * на процессоре, и 4K он выдаёт кадрами в секунду поштучно. Потолок,
     * взятый по нему, выходил 2160p на устройстве двенадцатого года.
     */
    private fun isSoftware(name: String): Boolean {
        val lower = name.lowercase(java.util.Locale.US)

        return lower.startsWith("omx.google.") || lower.startsWith("c2.android.")
    }

    /** С API 21 у декодера можно спросить прямо. */
    @android.annotation.TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun computeModern() {
        if (!computeModern(hardwareOnly = true)) {
            /**
             * Своего декодера у устройства нет вовсе — считаем по общему.
             * Такое бывает на эмуляторе и на самых простых сборках; там
             * программный декодер единственный, и других сведений нет.
             */
            computeModern(hardwareOnly = false)
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun computeModern(hardwareOnly: Boolean): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)

        var best = 0
        var sixty = false
        var sixtyBest = 0

        for (info in list.codecInfos) {
            if (info.isEncoder) {
                continue
            }

            if (hardwareOnly && isSoftware(info.name)) {
                continue
            }

            if (!info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }) {
                continue
            }

            val video = try {
                info.getCapabilitiesForType("video/avc").videoCapabilities
            } catch (error: Throwable) {
                null
            } ?: continue

            val height = video.supportedHeights.upper

            if (height > best) {
                best = height
            }

            /**
             * Шестьдесят кадров спрашиваем **на 1280×720**, а не на пределе.
             *
             * Смысл вопроса именно такой: 720p60 — самая тяжёлая из ступеней,
             * которые YouTube предлагает слабому устройству, и тяжелее она,
             * чем 1080p30. Спрашивать про частоту на предельном размере
             * значило бы получить «нет» у всех подряд.
             */
            /**
             * Спрашиваем не «умеешь ли шестьдесят», а «до какой ступени».
             * Ответ у декодеров разный на каждой: тот же GT-N8000 берёт
             * 720p60 и отказывается от 1080p60.
             */
            for (tier in intArrayOf(2160, 1440, 1080, 720, 480, 360)) {
                if (tier <= sixtyBest) {
                    break
                }

                val supported = try {
                    video.areSizeAndRateSupported(tier * 16 / 9, tier, 60.0)
                } catch (error: Throwable) {
                    // Декодер не берётся отвечать про такой размер — и не надо.
                    false
                }

                if (supported) {
                    sixtyBest = tier

                    break
                }
            }

            if (sixtyBest > 0) {
                sixty = true
            }

            /**
             * Что ответил каждый декодер — в журнал.
             *
             * Ответы у них разные, и когда потолок выходит не тот, какого
             * ждёшь, спорить об этом надо не на память: здесь видно и имя
             * декодера, и его предельный размер, и то, что он сказал
             * про шестьдесят кадров на каждой ступени.
             */
            Log.d {
                val said = intArrayOf(2160, 1440, 1080, 720).joinToString(", ") { tier ->
                    val yes = try {
                        video.areSizeAndRateSupported(tier * 16 / 9, tier, 60.0)
                    } catch (error: Throwable) {
                        false
                    }

                    "${tier}p60 ${if (yes) "да" else "нет"}"
                }

                "[YouTube/Декодер] ${info.name}: до ${video.supportedWidths.upper}" +
                    "×${video.supportedHeights.upper}, кадров до " +
                    "${video.supportedFrameRates.upper}; $said"
            }
        }

        if (best > 0) {
            maxHeightValue = best
            sixtyValue = sixty
            sixtyHeightValue = minOf(sixtyBest, best)
        }

        return best > 0
    }

    /**
     * На API 16–20 `VideoCapabilities` ещё нет — считаем по уровню.
     *
     * Уровень декодер объявляет всегда: это часть его описания. Из него
     * известно и предельное число макроблоков в кадре, и в секунду, —
     * то есть и размер, и частота.
     */
    @Suppress("DEPRECATION")
    private fun computeByLevel() {
        if (!computeByLevel(hardwareOnly = true)) {
            computeByLevel(hardwareOnly = false)
        }
    }

    @Suppress("DEPRECATION")
    private fun computeByLevel(hardwareOnly: Boolean): Boolean {
        var blocks = 0
        var blocksPerSecond = 0

        for (index in 0 until MediaCodecList.getCodecCount()) {
            val info = MediaCodecList.getCodecInfoAt(index)

            if (info.isEncoder) {
                continue
            }

            if (hardwareOnly && isSoftware(info.name)) {
                continue
            }

            if (!info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }) {
                continue
            }

            val capabilities = try {
                info.getCapabilitiesForType("video/avc")
            } catch (error: Throwable) {
                null
            } ?: continue

            for (level in capabilities.profileLevels) {
                val limits = LEVELS[level.level] ?: continue

                if (limits.first > blocks) {
                    blocks = limits.first
                }

                if (limits.second > blocksPerSecond) {
                    blocksPerSecond = limits.second
                }
            }
        }

        if (blocks <= 0) {
            return false
        }

        /**
         * Из числа макроблоков в кадре — высота при пропорции 16:9.
         *
         * Кадр в макроблоках: ширина × высота = blocks, а ширина
         * относится к высоте как 16 к 9. Значит, высота в макроблоках —
         * корень из blocks × 9/16, а в точках она вшестнадцатеро больше.
         */
        val heightBlocks = Math.sqrt(blocks * 9.0 / 16.0)
        val height = (heightBlocks * 16).toInt()

        maxHeightValue = when {
            height >= 2160 -> 2160
            height >= 1440 -> 1440
            height >= 1080 -> 1080
            height >= 720 -> 720
            height >= 480 -> 480
            else -> 360
        }

        /**
         * Шестьдесят кадров на 720p — это 3600 макроблоков в кадре,
         * то есть 216 000 в секунду. Ровно уровень 3.2; всё, что ниже,
         * не потянет.
         */
        /**
         * Сколько макроблоков в секунду нужно ступени при шестидесяти
         * кадрах: ширина на высоту, поделить на 256 и умножить на 60.
         * 720p60 — это 216 000, 1080p60 — уже 489 600, вчетверо больше
         * порога, за которым мы прежде считали шестьдесят кадров
         * доступными вообще везде.
         */
        sixtyHeightValue = 0

        for (tier in intArrayOf(2160, 1440, 1080, 720, 480, 360)) {
            val need = (tier * 16 / 9) * tier / 256 * 60

            if (blocksPerSecond >= need && tier <= maxHeightValue) {
                sixtyHeightValue = tier

                break
            }
        }

        sixtyValue = sixtyHeightValue > 0

        return true
    }

    /**
     * Потолок разрешения, какой объявляет декодер.
     *
     * Это **потолок, а не значение по умолчанию** — та же оговорка,
     * что и в оригинале: иначе человек включит тумблер сам и получит
     * тот же чёрный экран своими руками.
     */
    fun maxHeight(): Int {
        compute()

        return maxHeightValue
    }

    /** До какой ступени устройство тянет шестьдесят кадров; 0 — не тянет вовсе. */
    fun maxSixtyHeight(): Int {
        compute()

        return sixtyHeightValue
    }

    /** Тянет ли устройство шестьдесят кадров. */
    fun supportsSixtyFrames(): Boolean {
        compute()

        return sixtyValue
    }
}
