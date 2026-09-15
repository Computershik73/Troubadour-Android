package ru.computershik.troubadour.player

import android.net.Uri
import com.google.android.exoplayer2.extractor.Extractor
import com.google.android.exoplayer2.extractor.ExtractorsFactory
import com.google.android.exoplayer2.extractor.mp4.FragmentedMp4Extractor
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.TransferListener
import ru.computershik.troubadour.Log
import java.io.IOException

/**
 * Мост между подачей SABR и ExoPlayer.
 *
 * **Здесь исчезает половина сложности оригинала**, и стоит сказать, какая
 * именно. В iOS-версии `AVPlayer` не умел ни DASH, ни фрагменты MP4
 * из памяти, поэтому путь был такой:
 *
 *     подача → разбор боксов fMP4 → ремукс в MPEG-TS → локальный сервер
 *     HTTP на 127.0.0.1 → синтезированный плейлист HLS → AVPlayer
 *
 * Это `YTMp4` (691 строка), `YTTsMuxer` (621) и `YTHlsProxy` (2599) —
 * и все три со своими бедами: пометка точек входа битом
 * `random_access_indicator`, часы PCR на ключевых кадрах, постоянство
 * токенов сегментов, `SIGPIPE` при закрытии сокета, ATS, запрещающая
 * незашифрованную петлю.
 *
 * Здесь ничего этого нет. `FragmentedMp4Extractor` разбирает те же самые
 * фрагменты, которые присылает подача, и делает это внутри плеера.
 * От нас требуется одно: подавать байты.
 *
 * **Как устроена подача байтов.** Дорожки у подачи раздельные — видео
 * без звука и звук без видео, — и каждая приходит своим потоком фрагментов
 * fMP4. ExoPlayer читает такой поток как обычный файл: сперва заголовок
 * дорожки (`moov` с описанием кодека), затем фрагменты подряд. Значит,
 * источник должен выдавать их **склеенными**, будто это один файл, —
 * что и делает [SabrDataSource].
 *
 * Две дорожки соединяются `MergingMediaSource`: он берёт видеоряд у одного
 * источника, звук у другого и синхронизирует их по времени внутри
 * фрагментов. Ровно то, чем занимался ремуксер, только чужими руками
 * и без единой нашей строки про SPS/PPS.
 *
 * **Про перемотку.** Поток здесь непрерывный и по байтам не ищется:
 * фрагменты приходят по мере просьбы, и байтового смещения у них нет
 * вовсе. Поэтому прыжок по ролику делается не источником, а сменой
 * источника: [PlayerEngine] собирает его заново с новым началом. Снаружи
 * это неотличимо от обычной перемотки, а внутри — то же самое, что делал
 * оригинал, переписывая плейлист.
 */
class SabrDataSource(
    private val sabr: Sabr,
    private val isVideo: Boolean,
    private val startSeconds: Double
) : DataSource {

    private var uri: Uri? = null
    private var opened = false

    /** Что отдаётся прямо сейчас: заголовок дорожки либо фрагмент. */
    private var current: ByteArray? = null
    private var currentAt = 0

    /** Номер следующего фрагмента, который надо отдать. */
    private var nextSequence = 0

    /**
     * Время, с которого нас открыли, пока номер фрагмента не известен;
     * −1, когда мы уже привязались к номеру.
     */
    private var anchor = -1.0

    /** Отдан ли уже заголовок дорожки. */
    private var initSent = false

    /**
     * Номер дорожки, чей заголовок отдан.
     *
     * Сервер меняет дорожку сам, когда сеть проседает, и присылает новый
     * заголовок с другими SPS/PPS. Подсунуть кадры новой дорожки через
     * старое описание — значит получить зелёную кашу вместо картинки,
     * и это ровно та беда, о которой предупреждает записка о переносе.
     *
     * Заметив смену, источник кончается: плеер получит конец потока,
     * а [PlayerEngine] соберёт источник заново — уже с новым заголовком.
     */
    private var initItag = 0

    /** Сколько раз подряд подача не дала ничего нового. */
    private var empty = 0

    override fun addTransferListener(transferListener: TransferListener) {
        // Слушателя переноса не заводим: считать байты этого источника
        // незачем — они не из сети, а из памяти.
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        opened = true

        sabr.sourceOpen(isVideo, this, true)

        current = null
        currentAt = 0
        initSent = false
        empty = 0

        /**
         * С какого фрагмента начинать.
         *
         * Номера фрагментов в подаче сплошные, а длины разные, поэтому
         * место ищется **по времени**, а не счётом: берём тот, в чьи
         * границы попадает начало.
         */
        nextSequence = sequenceAt(startSeconds)

        if (nextSequence > 0) {
            /**
             * Место известно сразу — и подаче об этом надо сказать.
             *
             * После прыжка обе дорожки ведутся к месту прыжка, пока обе
             * на него не встанут; «встал» им сообщает источник. Но если
             * нужный кусок уже лежал в памяти, источник вставал молча,
             * и подача так и не узнавала об этом. Ждать было некого:
             * якорь не снимался никогда, каждая просьба уходила с одним
             * и тем же мигом, сервер повторял те же три куска, а плеер
             * стоял на месте.
             *
             * В журнале это выглядело как бесконечная череда
             * «Просьба N: с 490.618 с» с одинаковыми ответами.
             */
            anchor = -1.0

            sabr.anchored(isVideo, segmentStart(nextSequence))
        } else {
            // Не нашли — не гадаем: номер спросим у подачи, попросив её с этого мига.
            anchor = startSeconds
        }

        Log.d {
            "[YouTube/Источник] Открыт ${if (isVideo) "видео" else "звук"} " +
                "с ${startSeconds.toInt()} с, " +
                if (nextSequence > 0) "фрагмент $nextSequence" else "фрагмент ещё неизвестен"
        }

        // Длина неизвестна: поток идёт, пока идёт ролик.
        return C_LENGTH_UNSET
    }

    /**
     * Номер фрагмента, накрывающего это время, либо ноль.
     *
     * Ноль означает «не знаю», и это не то же самое, что «следующий
     * за последним». Прежде здесь возвращалось именно последнее плюс
     * один, и после прыжка выходила чепуха: в памяти лежали фрагменты
     * с прежнего места, на просьбу «открой с 184 с» источник называл
     * фрагмент 10, а тот по разметке начинался на 45 с — туда и уезжала
     * подача. У звука к тому же длины свои, вдвое длиннее видео, и
     * промах был вдвое больше: сервер отвечал пустотой, три попытки
     * подряд, и звук пропадал совсем.
     */
    private fun sequenceAt(seconds: Double): Int {
        val sequences = if (isVideo) sabr.videoSequences() else sabr.audioSequences()

        for (sequence in sequences) {
            val start = segmentStart(sequence)
            val length = segmentDuration(sequence)

            if (length > 0 && seconds >= start && seconds < start + length) {
                return sequence
            }

            /**
             * У эфира длина куска приходит не сразу.
             *
             * Сервер её не называет вовсе — она вычисляется по расстоянию
             * до следующего куска, а следующего в этот миг ещё нет.
             * Поэтому у самого свежего куска длина нулевая, и по общему
             * правилу он не накрывает ни одного мига — в том числе
             * собственное начало, с которого мы и открываем эфир.
             */
            if (length <= 0 && start > 0 && kotlin.math.abs(seconds - start) < 0.5) {
                return sequence
            }
        }

        return 0
    }

    private fun segmentStart(sequence: Int): Double =
        if (isVideo) sabr.videoSegmentStart(sequence) else sabr.audioSegmentStart(sequence)

    private fun segmentDuration(sequence: Int): Double =
        if (isVideo) {
            sabr.videoSegmentDuration(sequence)
        } else {
            sabr.audioSegmentDuration(sequence)
        }

    /**
     * Первый фрагмент не раньше искомого времени — из тех, что пришли.
     *
     * Зовётся после того, как подачу попросили начать с этого мига:
     * какой номер сервер присвоит первому куску, знает только он сам,
     * и наше дело — принять его, а не угадать.
     */
    private fun sequenceFrom(seconds: Double): Int {
        val sequences = if (isVideo) sabr.videoSequences() else sabr.audioSequences()

        if (sequences.isEmpty()) {
            return 0
        }

        val covering = sequenceAt(seconds)

        if (covering > 0) {
            return covering
        }

        /**
         * Ни один не накрывает — берём ближайший по началу, с любой стороны.
         *
         * Требовать попадания в полсекунды оказалось нельзя: после прыжка
         * на 342-ю секунду подача прислала видео с 340.34, звук с 339.475,
         * и по строгой мерке не годилось ни то, ни другое. Источник видео
         * ждал подходящего куска, подача давала пустые ответы — три подряд,
         * и он кончался. Это и был «статичный кадр через раз».
         *
         * Расхождение дорожек при этом держит не мерка, а якорь: пока обе
         * не встали, все просьбы ведут к месту прыжка, и разойтись дальше
         * одного фрагмента им негде.
         */
        var best = 0
        var bestGap = Double.MAX_VALUE

        for (sequence in sequences) {
            val gap = Math.abs(segmentStart(sequence) - seconds)

            if (gap < bestGap) {
                bestGap = gap
                best = sequence
            }
        }

        return best
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (!opened) {
            throw IOException("Источник закрыт")
        }

        if (readLength == 0) {
            return 0
        }

        val piece = ensurePiece() ?: return C_RESULT_END_OF_INPUT

        val take = minOf(readLength, piece.size - currentAt)

        System.arraycopy(piece, currentAt, buffer, offset, take)

        currentAt += take

        if (currentAt >= piece.size) {
            current = null
            currentAt = 0
        }

        return take
    }

    /**
     * Готовит следующий кусок к выдаче: заголовок, фрагмент из памяти
     * либо, если его ещё нет, просит подачу прислать ещё.
     */
    private fun ensurePiece(): ByteArray? {
        current?.let { return it }

        if (!initSent) {
            /**
             * Заголовка дорожки может ещё не быть — тогда ждём, а не кончаемся.
             *
             * Прежде здесь стоял немой `return null`, то есть «конец
             * потока», и плеер получал его через считанные миллисекунды
             * после пересборки. Ни одного кадра при этом не приходило,
             * буфер оставался пуст, и плеер объявлял `Playback stuck
             * buffering and not loading` — ту самую ошибку во весь экран
             * при быстрой перемотке. Заголовок же просто ещё не успел
             * прийти: подачу об этом надо попросить, а не сдаваться.
             */
            var init = if (isVideo) sabr.videoInit else sabr.audioInit

            var asked = 0

            while (init == null && asked < 5) {
                asked++

                Log.d {
                    "[YouTube/Источник] Заголовка ${if (isVideo) "видео" else "звука"} " +
                        "ещё нет — просим подачу (попытка $asked)"
                }

                sabr.requestMoreFrom(if (anchor >= 0) anchor else startSeconds)

                init = if (isVideo) sabr.videoInit else sabr.audioInit
            }

            if (init == null) {
                Log.d {
                    "[YouTube/Источник] Заголовка ${if (isVideo) "видео" else "звука"} " +
                        "подача так и не дала — источник кончился"
                }

                return null
            }

            initSent = true
            initItag = if (isVideo) sabr.videoInitItag else 0

            current = init
            currentAt = 0

            return init
        }

        /**
         * Смена дорожки на ходу — конец этого источника.
         *
         * Дальше пойдут кадры, описанные другим `moov`, и скармливать их
         * разборщику, настроенному на прежний, нельзя.
         */
        if (isVideo && sabr.videoInitItag != initItag && sabr.videoInitItag != 0) {
            Log.d {
                "[YouTube/Источник] Дорожка сменилась ($initItag → " +
                    "${sabr.videoInitItag}) — источник кончился"
            }

            return null
        }

        /**
         * Ролик кончился — кончился и источник.
         *
         * Сервер называет общее число фрагментов дорожки, и просить
         * то, что за последним, бессмысленно. Раньше мы всё равно
         * просили: подача отвечала теми же кусками, что уже лежат
         * в памяти, признак «что-то пришло» срабатывал, и круг шёл
         * заново — без конца.
         *
         * В журнале с устройства это выглядело так: ролик на 19 секунд
         * из трёх фрагментов, тысяча триста запросов подряд, и каждый
         * тянет тот же самый мегабайт. Четверть гигабайта за один
         * короткий ролик — и это только то, что попало в журнал.
         */
        if (isVideo && sabr.videoSegmentCount > 0 && nextSequence > sabr.videoSegmentCount &&
            collected() >= sabr.duration - 0.5
        ) {
            Log.d {
                "[YouTube/Источник] Видео: фрагмент $nextSequence за последним " +
                    "(${sabr.videoSegmentCount}), набрано до ${collected().toInt()} с " +
                    "из ${sabr.duration.toInt()} — ролик кончился"
            }

            return null
        }

        /**
         * У звука перечня фрагментов сервер не даёт, зато даёт длину
         * ролика. Если предыдущий кусок дотянул до неё, дальше ничего
         * нет — и просить не о чем.
         */
        if (!isVideo && sabr.duration > 0 && collected() >= sabr.duration - 0.5) {
            Log.d {
                "[YouTube/Источник] Звук: набрано до ${collected().toInt()} с " +
                    "из ${sabr.duration.toInt()} — ролик кончился"
            }

            return null
        }

        while (true) {
            /**
             * Плеер закрыл источник — уходим.
             *
             * Круг этот блокирующий: он спрашивает подачу, ждёт сеть
             * и спрашивает снова. Закрытие меж тем случается посреди
             * ожидания — человек открыл другой ролик, — и без проверки
             * круг продолжал ходить в сеть за роликом, которого никто
             * уже не смотрит. Два таких потока разом мешали друг другу,
             * и новый ролик получал отказы.
             */
            if (!opened) {
                Log.d {
                    "[YouTube/Источник] ${if (isVideo) "Видео" else "Звук"}: " +
                        "источник закрыт — уходим"
                }

                return null
            }

            val ready = if (nextSequence <= 0) {
                null
            } else if (isVideo) {
                sabr.videoSegment(nextSequence)
            } else {
                sabr.audioSegment(nextSequence)
            }

            if (ready != null) {
                nextSequence++
                empty = 0

                current = ready
                currentAt = 0

                return ready
            }

            /**
             * Фрагмента ещё нет — просим подачу.
             *
             * Просьба блокирующая, и это правильно: `read` у ExoPlayer
             * зовётся с загрузочного потока, который для того и заведён,
             * чтобы ждать сеть.
             */
            /**
             * Откуда просить.
             *
             * Пока номер неизвестен — с того мига, ради которого нас
             * открыли. Как только он назван, отмеряем от конца
             * предыдущего куска **своей** дорожки: у звука фрагменты
             * почти вдвое длиннее видео, и общая мерка промахивалась.
             */
            val from = if (anchor >= 0) {
                anchor
            } else {
                sabr.startForSequence(nextSequence, averageLength(), isVideo)
            }

            /**
             * У эфира не просим того, что ещё не снято.
             *
             * Сервер в каждом ответе говорит, докуда снята трансляция.
             * Прежде мы этого не слушали: просили следующий кусок, пока
             * его не существует, получали пустоту и просили снова —
             * два десятка пустых заходов за две с половиной минуты.
             * А после третьего такого захода источник считал кусок
             * потерянным и перешагивал через него: картинка дёргалась
             * на ровном месте.
             *
             * Теперь ждём столько, сколько ему осталось сниматься,
             * и пустой заход в счёт потерь не идёт.
             */
            /**
             * У эфира не торопим сервер: ждём, пока кусок снимут.
             *
             * Сервер в каждом ответе говорит, докуда снята трансляция
             * (сравнивать надо по времени: номер края у него свой
             * и с нашими номерами не совпадает). Если мы просим дальше
             * этого места, куска ещё нет — и просьба вернётся пустой.
             * Прежде мы так и делали: за две с половиной минуты
             * набегало полтора-два десятка пустых заходов, а на третьем
             * подряд источник считал кусок потерянным и перешагивал
             * через него — картинка дёргалась на ровном месте.
             *
             * Подождав, **всё равно спрашиваем**: край обновляется
             * только ответом, и молчаливое ожидание превратилось бы
             * в вечное — на пробе показ так и встал, тридцать восемь
             * секунд за две с половиной минуты.
             */
            val head = sabr.liveHeadSeconds

            if (sabr.liveMode && head > 0 && from > head) {
                val wait = minOf(from - head, 5.0)

                Log.d {
                    "[YouTube/Источник] ${if (isVideo) "Видео" else "Звук"}: " +
                        "${from.toInt()} с ещё не снято (край ${head.toInt()}) — " +
                        "ждём ${wait.toInt()} с"
                }

                try {
                    Thread.sleep((wait * 1000).toLong())
                } catch (error: InterruptedException) {
                    return null
                }
            }

            val backoff = sabr.backoff()

            if (backoff > 0) {
                try {
                    Thread.sleep((backoff * 1000).toLong())
                } catch (error: InterruptedException) {
                    // Плеер отменил загрузку — обычное дело при перемотке.
                    Log.d {
                        "[YouTube/Источник] ${if (isVideo) "Видео" else "Звук"}: " +
                            "ожидание прервано — источник кончился"
                    }

                    return null
                }
            }

            val more = sabr.requestMoreFrom(from)

            /**
             * Номер первого куска после прыжка присваивает сервер —
             * принимаем его, каким бы он ни был.
             */
            if (anchor >= 0) {
                /**
                 * Метим туда же, куда встала соседняя дорожка, если она
                 * уже встала: так между ними остаётся не больше половины
                 * куска вместо семи секунд.
                 */
                val aim = sabr.anchorStart().takeIf { it >= 0 } ?: anchor

                val found = sequenceFrom(aim)

                if (found > 0) {
                    nextSequence = found
                    anchor = -1.0

                    // Встали — теперь подача вправе вести и вторую дорожку.
                    sabr.anchored(isVideo, segmentStart(found))

                    Log.d {
                        "[YouTube/Источник] ${if (isVideo) "Видео" else "Звук"} " +
                            "привязан к фрагменту $found " +
                            "(${segmentStart(found).toInt()} с)"
                    }

                    continue
                }
            }

            /**
             * Сервер попросил обновить ответ `/player` — обновляем
             * и пробуем ещё раз. Без этого он не даст больше ни байта.
             */
            if (sabr.needsReload) {
                if (!Streams.renewSabr(sabr)) {
                    Log.d {
                        "[YouTube/Источник] ${if (isVideo) "Видео" else "Звук"}: " +
                            "подача просила обновиться, но не вышло — источник кончился"
                    }

                    return null
                }

                continue
            }

            if (!more) {
                empty++

                Log.d {
                    "[YouTube/Источник] Подача не дала кусок $nextSequence " +
                        "(время ${from.toInt()} с), попытка $empty"
                }

                /**
                 * Куска нет, а следующие есть — перешагиваем через него.
                 *
                 * Подача изредка пропускает номер: после прыжка на 307-ю
                 * секунду пришли фрагменты 62, 63, 64, 66, 67 — а 65-го
                 * не было вовсе. Источник ждал именно его, просил подачу
                 * с 320-й секунды, а та отвечала, что этот кусок у нас
                 * уже есть, — и через три попытки источник кончался.
                 * Звук при этом шёл своим чередом: получалась застывшая
                 * картинка при живом звуке.
                 *
                 * Пропуск пяти секунд картинки — беда куда меньшая, чем
                 * оборванный ролик, поэтому шагаем к ближайшему из тех,
                 * что есть. Сразу не шагаем: кусок бывает просто в пути.
                 */
                /**
                 * У эфира шагаем вперёд, только если край ушёл дальше.
                 *
                 * Кусок, до которого трансляция ещё не дошла, не потерян —
                 * он просто не снят, и перешагивать через него значит
                 * терять картинку зря.
                 */
                val lost = !sabr.liveMode || sabr.liveHeadSeconds <= 0 ||
                    from < sabr.liveHeadSeconds

                /**
                 * Эфиру даём больше попыток, чем записи.
                 *
                 * У записи недошедший кусок и вправду чаще всего потерян:
                 * ролик лежит целиком, и если его не дали трижды — не дадут.
                 * У трансляции он сплошь и рядом просто в пути: сервер
                 * отдаёт куски по мере съёмки, и лишняя пара попыток
                 * дешевле пропущенных пяти секунд картинки.
                 */
                if (empty >= (if (sabr.liveMode) 4 else 2) && lost) {
                    val forward = nextAvailableAfter(nextSequence)

                    if (forward > 0) {
                        Log.d {
                            "[YouTube/Источник] ${if (isVideo) "Видео" else "Звук"}: " +
                                "куска $nextSequence подача не дала — " +
                                "шагаем к $forward (${segmentStart(forward).toInt()} с)"
                        }

                        nextSequence = forward
                        empty = 0

                        continue
                    }
                }

                /**
                 * У эфира пустой ответ — не конец, а «ещё не сняли».
                 *
                 * Обычный ролик кончается тем, что куски перестают
                 * приходить, и по нескольким пустым ответам подряд мы
                 * объявляем конец потока. У трансляции всё наоборот:
                 * следующего куска ещё попросту не существует — его
                 * снимают прямо сейчас, — и ждать его надо столько,
                 * сколько потребуется.
                 *
                 * Прежде источник тут кончался, плеер получал конец
                 * потока и объявлял «Playback stuck buffering and not
                 * loading» — ту самую ошибку, за которой эфир и не играл.
                 */
                if (sabr.liveMode) {
                    /**
                     * Ждём подольше, чем у записи.
                     *
                     * Пустой ответ у эфира означает «кусок ещё снимают»,
                     * а снимают его секунд пять. Спрашивать чаще — зря
                     * тревожить сеть и сервер: на пробе набегало
                     * до сорока пустых ответов за две минуты.
                     */
                    try {
                        Thread.sleep(1500)
                    } catch (error: InterruptedException) {
                        return null
                    }

                    continue
                }

                /**
                 * Несколько пустых ответов подряд — конец.
                 *
                 * Так кончается и обычный ролик: фрагменты просто
                 * заканчиваются. Отличить это от беды по одному ответу
                 * нельзя. Неприкаянному источнику даётся больше попыток:
                 * он ещё ничего не получил, и кончаться ему рано.
                 */
                if (empty >= (if (anchor >= 0) STRIKES_BEFORE_ANCHOR else STRIKES)) {
                    return null
                }

                /**
                 * Между пустыми ответами — передышка.
                 *
                 * Без неё три попытки укладывались в четверть секунды,
                 * и источник объявлял конец потока там, где сервер
                 * просто не успел. Особенно заметно сразу после прыжка:
                 * дорожка встаёт на новое место, тут же просит следующий
                 * кусок, получает три пустых ответа подряд и кончается.
                 * Плеер остаётся без видео, объявляет затор, и человек
                 * видит бесконечную загрузку.
                 *
                 * Своя пауза нужна и тогда, когда сервер не просил ждать:
                 * его `backoff` в этом случае ноль.
                 */
                try {
                    Thread.sleep(EMPTY_PAUSE_MS)
                } catch (error: InterruptedException) {
                    Log.d {
                        "[YouTube/Источник] ${if (isVideo) "Видео" else "Звук"}: " +
                            "ожидание прервано — источник кончился"
                    }

                    return null
                }
            }
        }
    }

    /**
     * Докуда дотянул предыдущий кусок своей дорожки, секунды; 0, если
     * его ещё не было.
     *
     * По нему судим о конце ролика: числу фрагментов одному верить
     * нельзя, у прямого эфира оно растёт на ходу.
     */
    private fun collected(): Double {
        val previous = nextSequence - 1

        if (previous < 1) {
            return 0.0
        }

        return segmentStart(previous) + segmentDuration(previous)
    }

    /** Ближайший из имеющихся кусков после этого номера; 0, если таких нет. */
    private fun nextAvailableAfter(sequence: Int): Int {
        val sequences = if (isVideo) sabr.videoSequences() else sabr.audioSequences()

        for (candidate in sequences) {
            if (candidate > sequence) {
                return candidate
            }
        }

        return 0
    }

    /** Средняя длина фрагмента — для расчёта времени неизвестных. */
    private fun averageLength(): Double {
        /**
         * Средняя берётся по своей дорожке.
         *
         * Перечень фрагментов сервер присылает для видео; у звука их
         * меньше, и длины другие. Пока своего перечня нет, считаем
         * по тому, что уже пришло, и лишь потом — по видео.
         */
        if (!isVideo) {
            val sequences = sabr.audioSequences()

            var total = 0.0
            var counted = 0

            for (sequence in sequences) {
                val length = sabr.audioSegmentDuration(sequence)

                if (length > 0) {
                    total += length
                    counted++
                }
            }

            if (counted > 0) {
                return total / counted
            }
        }

        val count = sabr.videoSegmentCount

        if (count > 0 && sabr.duration > 0) {
            return sabr.duration / count
        }

        return 5.0
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        opened = false
        current = null

        sabr.sourceOpen(isVideo, this, false)
    }

    companion object {
        /** `C.LENGTH_UNSET` и `C.RESULT_END_OF_INPUT` — свои имена без импорта. */
        private const val C_LENGTH_UNSET = -1L
        private const val C_RESULT_END_OF_INPUT = -1

        /**
         * Сколько пустых ответов подряд считать концом.
         *
         * Обычный ролик кончается так же — фрагменты просто заканчиваются,
         * — и отличить одно от другого по одному ответу нельзя. Но пока
         * источник ещё не встал на свой кусок, кончаться ему рано: он
         * ничего и не получил ни разу. Поэтому неприкаянному даётся втрое
         * больше попыток.
         */
        private const val STRIKES = 6
        private const val STRIKES_BEFORE_ANCHOR = 9

        /**
         * Сколько ждать между пустыми ответами.
         *
         * Полсекунды: ответ подачи занимает от силы столько же, так что
         * шесть попыток — это три секунды терпения. Прежние три попытки
         * без пауз укладывались в четверть секунды и не давали серверу
         * ни одного шанса.
         */
        private const val EMPTY_PAUSE_MS = 500L
    }
}

/**
 * Собирает источник из подачи: видеоряд и звук двумя дорожками.
 */
object SabrSource {

    /**
     * Разборщик один и тот же на обе дорожки — `FragmentedMp4Extractor`.
     *
     * Флаг `FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME` не ставим:
     * у подачи фрагменты честные, с разметкой ключевых кадров. Это ровно
     * та разметка, которую оригиналу приходилось расставлять самому битом
     * `random_access_indicator` в MPEG-TS, — и без которой его плеер
     * считал, что войти в поток можно только с начала.
     */
    /**
     * Как часто загрузчик докладывает плееру о набранном.
     *
     * По умолчанию у ExoPlayer это мегабайт: пока источник не отдаст
     * столько, плеер не пересматривает, кого из двух догружать. Дорожки
     * у нас разные по весу — фрагмент звука около ста шестидесяти
     * килобайт, видео бывает и полсотни, — и выходило так: звук
     * набирал вперёд на минуту, а видеоисточник, отдав первые полсотни
     * килобайт, ждал своей очереди восемь секунд. Всё это время плеер
     * стоял «набирает» с тридцатью тремя миллисекундами картинки,
     * то есть с одним кадром.
     *
     * Шестьдесят четыре килобайта — оповещение шестнадцатью долями
     * вместо одной. Первый кадр на планшете вышел за 2,6 с вместо 10,5.
     */
    private const val LOADING_STEP = 64 * 1024

    private fun extractors(): ExtractorsFactory =
        ExtractorsFactory { arrayOf<Extractor>(FragmentedMp4Extractor()) }

    /**
     * Источник для плеера.
     *
     * @param startSeconds с какого места ролика начинать. Перемотка
     *   делается пересборкой источника: байтового поиска у подачи нет.
     */
    fun build(sabr: Sabr, startSeconds: Double): MediaSource {
        val video = ProgressiveMediaSource.Factory(
            { SabrDataSource(sabr, true, startSeconds) }, extractors()
        )
            .setContinueLoadingCheckIntervalBytes(LOADING_STEP)
            .createMediaSource(Uri.parse("sabr://video"))

        if (sabr.audioInit == null) {
            /**
             * Звука ещё не дали — играем одним видеорядом.
             *
             * Так бывает у ролика без звуковой дорожки вовсе и у первого
             * ответа, где сервер прислал только видео. Второй случай
             * лечится сам: источник пересоберётся, когда звук приедет.
             */
            Log.d { "[YouTube/Источник] Звуковой дорожки нет — играем без неё" }

            return video
        }

        val audio = ProgressiveMediaSource.Factory(
            { SabrDataSource(sabr, false, startSeconds) }, extractors()
        )
            .setContinueLoadingCheckIntervalBytes(LOADING_STEP)
            .createMediaSource(Uri.parse("sabr://audio"))

        /**
         * Соединение двух дорожек в одну ленту.
         *
         * `MergingMediaSource` берёт видеоряд у первого источника, звук
         * у второго и сводит их по времени, которое лежит внутри самих
         * фрагментов. Это и есть то место, где оригиналу приходилось
         * заниматься чересполосицей вручную: у видео и звука разная
         * нарезка — 5.5 с против 10 с, — и чтобы собрать один кусок
         * MPEG-TS, он вычислял, какие звуковые фрагменты попадают
         * в границы видеокадра.
         */
        return MergingMediaSource(video, audio)
    }

    /**
     * Источник для готовых адресов — там, где подачи нет.
     *
     * Две отдельные дорожки, каждая обычным HTTP с диапазонами байт.
     * Соединяются тем же `MergingMediaSource`.
     */
    fun build(
        factory: DataSource.Factory,
        videoUrl: String?,
        audioUrl: String?
    ): MediaSource? {
        if (videoUrl.isNullOrEmpty()) {
            return null
        }

        val video = ProgressiveMediaSource.Factory(factory)
            .setContinueLoadingCheckIntervalBytes(LOADING_STEP)
            .createMediaSource(Uri.parse(videoUrl))

        if (audioUrl.isNullOrEmpty()) {
            return video
        }

        val audio = ProgressiveMediaSource.Factory(factory)
            .setContinueLoadingCheckIntervalBytes(LOADING_STEP)
            .createMediaSource(Uri.parse(audioUrl))

        return MergingMediaSource(video, audio)
    }
}
