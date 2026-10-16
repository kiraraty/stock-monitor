package app.pnale.ticker

import android.content.Context
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class MarketRoute(val id: String, val rest: String, val ws: String, val client: OkHttpClient)

class MarketTransport(context: Context?, directOverride: MarketRoute? = null) {
    private fun builder() = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
    val direct = directOverride ?: MarketRoute("direct", "https://fapi.binance.com/fapi/v1", "https://fstream.binance.com/market/stream", builder().build())
    // Only the relay client trusts this specific server certificate. Host/IP verification remains enabled.
    val relay: MarketRoute? = context?.let {
        val cert = it.resources.openRawResource(R.raw.panel_relay).use { input -> CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("panel-relay", cert) }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers.single() as X509TrustManager
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        MarketRoute("relay", "https://43.156.153.114/fapi/v1", "https://43.156.153.114/market/stream", builder().sslSocketFactory(ssl.socketFactory, manager).build())
    }
    val gate = MarketRoute("gate", "https://api.gateio.ws/api/v4/futures/usdt", "wss://fx-ws.gateio.ws/v4/ws/usdt", builder().build())
    val htx = MarketRoute("htx", "https://api.hbdm.vn", "wss://api.hbdm.vn/linear-swap-ws", builder().build())
    // Direct / relay switching only applies to Binance; Gate is reached directly.
    // Auto mode starts from the route that last worked: on mainland networks direct Binance always times out first.
    fun initial(mode: String, source: String = BinanceSource.id, lastGood: String = "direct") =
        if (source == GateSource.id) gate else if (source == HtxSource.id) htx else if (mode == "relay" || (mode == "auto" && lastGood == "relay")) relay ?: direct else direct
    fun alternate(route: MarketRoute) = if (route.id == "gate") gate else if (route.id == "htx") htx else if (route.id == "direct") relay ?: direct else direct
}
