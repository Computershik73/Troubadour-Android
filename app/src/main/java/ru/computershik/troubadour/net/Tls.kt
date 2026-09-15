package ru.computershik.troubadour.net

import android.content.Context
import ru.computershik.troubadour.App
import ru.computershik.troubadour.Log
import ru.computershik.troubadour.R
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Доверие и версии протокола.
 *
 * Беда здесь ровно та же, что была в iOS-версии, только другого возраста.
 *
 * **Корни.** Всё, к чему обращается приложение — `www.youtube.com`,
 * `i.ytimg.com`, `*.googlevideo.com`, `oauth2.googleapis.com`, — выдано
 * Google Trust Services: цепочка идёт на GTS Root R1…R4. Эти корни выпущены
 * в 2016 году, а хранилище Android 4.1 заморожено в 2012-м — их там нет
 * вовсе, ровно как не было в хранилище iOS 5–9.
 *
 * До сих пор это сходило с рук: сервер присылает GTS Root R1,
 * кросс-подписанный старым `GlobalSign Root CA` (1998, есть везде), и цепочка
 * замыкается на него. Полагаться на кросс-подпись нельзя — она конечна,
 * и в день, когда её перестанут присылать, приложение молча перестанет
 * открывать что-либо. Поэтому те же четыре файла, что лежали
 * в `Resources/certs`, лежат теперь в `res/raw` и подставляются
 * дополнительными якорями. Формат DER читается `CertificateFactory`
 * так же, как PEM, — перекодировать их не пришлось.
 *
 * Системные корни при этом остаются главными: сначала спрашиваем систему,
 * и только если она отказала — свои. На новом устройстве отрабатывает
 * первый путь.
 *
 * **Протокол.** А вот это уже беда Android, которой на iOS не было:
 * там Secure Transport согласовывал TLS 1.2 сам начиная с iOS 5.0, потому
 * нижняя граница оригинала и стоит на 5.1. Здесь TLS 1.1 и 1.2 в системе
 * есть начиная с API 16, но **выключены по умолчанию** вплоть до API 20 —
 * включаем руками на каждом сокете. Фронт Google на TLS 1.0 давно
 * не отвечает.
 *
 * Отдельного OpenSSL или Conscrypt со своей `.so` не требуется — и хорошо:
 * на Android 15+ такие библиотеки упираются в требование 16-килобайтных
 * страниц.
 */
object Tls {

    /**
     * Отпечатки SHA-256 — те же, что записаны в README оригинала, чтобы
     * файлы можно было сверить, не доверяя тому, кто их положил:
     *
     *     gts_root_r1  D9:47:43:2A:BD:E7:B7:FA:90:FC:2E:6B:59:10:1B:12:…
     *     gts_root_r2  8D:25:CD:97:22:9D:BF:70:35:6B:DA:4E:B3:CC:73:40:…
     *     gts_root_r3  34:D8:A7:3E:E2:08:D9:BC:DB:0D:95:65:20:93:4B:4E:…
     *     gts_root_r4  34:9D:FA:40:58:C5:E2:63:12:3B:39:8A:E7:95:57:3C:…
     */
    private val ROOTS = intArrayOf(
        R.raw.gts_root_r1,
        R.raw.gts_root_r2,
        R.raw.gts_root_r3,
        R.raw.gts_root_r4
    )

    /** Порядок важен: сначала предлагаем 1.3, если он есть, затем 1.2. */
    private val WANTED_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2", "TLSv1.1")

    private var cachedTrust: X509TrustManager? = null

    fun trustManager(): X509TrustManager {
        cachedTrust?.let { return it }

        val manager = CompositeTrustManager(systemTrustManager(), bundledTrustManager())

        cachedTrust = manager

        return manager
    }

    /**
     * Фабрика сокетов, включающая на каждом соединении современные версии
     * протокола. На новых Android список и так правильный, лишнего не делаем.
     */
    fun socketFactory(trustManager: X509TrustManager): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), null)

        return ProtocolSocketFactory(context.socketFactory)
    }

    private fun systemTrustManager(): X509TrustManager = trustManagerFor(null)

    private fun bundledTrustManager(): X509TrustManager {
        val context: Context = App.require()
        val factory = CertificateFactory.getInstance("X.509")

        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null, null)

        var loaded = 0

        for (id in ROOTS) {
            try {
                context.resources.openRawResource(id).use { stream ->
                    val certificate = factory.generateCertificate(stream) as X509Certificate

                    store.setCertificateEntry(certificate.subjectDN.name, certificate)
                    loaded++
                }
            } catch (error: Exception) {
                Log.d { "[YouTube/TLS] Корень не прочитан: ${error.message}" }
            }
        }

        Log.d { "[YouTube/TLS] Своих корней подставлено: $loaded" }

        return trustManagerFor(store)
    }

    private fun trustManagerFor(store: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)

        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * Системные корни плюс свои. Проверка идёт по очереди: первым спрашиваем
     * систему, и только если она отказала — свой список.
     *
     * Про кросс-подпись здесь беспокоиться не приходится, и это заметное
     * упрощение против оригинала. Там `SecTrustEvaluate` запоминал ответ
     * внутри объекта доверия, а построитель пути, увидев в присланном готовое
     * продолжение до `GlobalSign`, тянул путь туда вместо того, чтобы
     * остановиться на нашем корне с тем же именем, — и приходилось делать
     * три захода, последний с выброшенным из цепочки кросс-корнем.
     * `TrustManagerFactory` строит путь заново на каждый вызов и принимает
     * любое замыкание на якорь.
     */
    private class CompositeTrustManager(
        private val system: X509TrustManager,
        private val bundled: X509TrustManager
    ) : X509TrustManager {

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val started = System.nanoTime()

            try {
                system.checkServerTrusted(chain, authType)
            } catch (first: CertificateException) {
                // Старое устройство: корня цепочки в системе нет.
                try {
                    bundled.checkServerTrusted(chain, authType)
                } catch (second: CertificateException) {
                    /**
                     * Отвергнута обоими. В журнале это выглядит так же, как
                     * в оригинале, и смотреть надо туда же:
                     *
                     *   * часы устройства. На устройстве, пролежавшем
                     *     в ящике, дата сбрасывается, и тогда просроченным
                     *     оказывается **любой** сертификат;
                     *   * что прислал сервер — обычно три звена: лист,
                     *     промежуточный `WR2` и `GTS Root R1`;
                     *   * перехватчик трафика. Charles, Proxyman и mitmproxy
                     *     работают тем, что встают посередине и подменяют
                     *     сертификат своим. Для приложения это ровно то,
                     *     от чего оно защищается. Лечится тем же способом,
                     *     что и там: положить корень перехватчика
                     *     в `res/raw` рядом с корнями Google и вписать
                     *     его в список ROOTS.
                     */
                    Log.now {
                        val names = chain?.joinToString(" ← ") { it.subjectDN.name } ?: "пусто"

                        "[YouTube/TLS] Цепочка отвергнута. Часы: ${java.util.Date()}. " +
                            "Прислано: $names"
                    }

                    throw second
                }
            } finally {
                Http.noteTrustCheck((System.nanoTime() - started) / 1_000_000_000.0)
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            system.checkClientTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            system.acceptedIssuers + bundled.acceptedIssuers
    }

    /**
     * Обёртка, включающая современные версии протокола на каждом созданном
     * сокете. Список пересекаем с тем, что устройство объявляет
     * поддерживаемым: иначе на совсем древних прошивках прилетит
     * IllegalArgumentException.
     */
    private class ProtocolSocketFactory(
        private val delegate: SSLSocketFactory
    ) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        // Без этой перегрузки библиотека, попросившая несоединённый сокет,
        // получила бы отказ базового класса.
        override fun createSocket() = enable(delegate.createSocket())

        override fun createSocket(socket: Socket?, host: String?, port: Int, autoClose: Boolean) =
            enable(delegate.createSocket(socket, host, port, autoClose))

        override fun createSocket(host: String?, port: Int) =
            enable(delegate.createSocket(host, port))

        override fun createSocket(
            host: String?, port: Int, localHost: InetAddress?, localPort: Int
        ) = enable(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int) =
            enable(delegate.createSocket(host, port))

        override fun createSocket(
            address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int
        ) = enable(delegate.createSocket(address, port, localAddress, localPort))

        private fun enable(socket: Socket): Socket {
            if (socket is SSLSocket) {
                val supported = socket.supportedProtocols.toSet()
                val wanted = WANTED_PROTOCOLS.filter { supported.contains(it) }

                if (wanted.isNotEmpty()) {
                    socket.enabledProtocols = wanted.toTypedArray()
                }

                /**
                 * Указание имени узла в рукопожатии.
                 *
                 * До API 17 сокет, созданный без имени (а OkHttp создаёт
                 * именно такой — он делает `createSocket()` и соединяет
                 * сам), SNI не шлёт. Фронт Google без SNI отвечает
                 * сертификатом не того имени, и проверка честно его
                 * отвергает. OkHttp 3.12 умеет проставить имя сам через
                 * `setHostname`, но только когда знает про этот метод, —
                 * поэтому оставляем работу ему и просто не мешаем.
                 */
            }

            return socket
        }
    }
}
