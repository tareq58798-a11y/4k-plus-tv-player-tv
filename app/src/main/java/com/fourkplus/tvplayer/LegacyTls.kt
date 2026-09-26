package com.fourkplus.tvplayer

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Lets the app's own connections trust today's certificates on televisions too old to.
 *
 * The app runs from Android 5.0. A set on Android 7.0 or older carries the root certificates it
 * shipped with and never gets new ones, and two roots that a large share of the web now depends on
 * are missing from them: ISRG Root X1, behind every Let's Encrypt certificate (added in Android
 * 7.1.1; the older cross-signature that used to cover it ended in 2024), and Google Trust
 * Services' GTS Root R1. On such a set any HTTPS server using them - artwork hosts, the activation
 * server - fails the handshake, and the viewer sees missing posters or an activation that never
 * completes, with nothing to say why.
 *
 * So on those versions only, every OkHttp client in the app trusts the device's own roots plus
 * these two, bundled in res/raw. They were exported from Windows' own trusted store on 2026-09-26
 * (SHA-1 CA:BD:2A:79... and E5:8C:1C:C4..., the published fingerprints), not downloaded. Nothing
 * is trusted that a current Android does not already trust, and from 7.1.1 on the device's own
 * store is used untouched.
 *
 * The in-app trailer player's WebView cannot be reached this way; on an old set whose WebView
 * refuses a certificate, the trailer does what it does whenever it cannot play - offers YouTube.
 */
internal object LegacyTls {
    private const val LAST_VERSION_NEEDING_IT = Build.VERSION_CODES.N // 7.0; 7.1.1 has ISRG

    @Volatile
    private var factory: Pair<SSLSocketFactory, X509TrustManager>? = null

    /** Called once from Application.onCreate, before any client is built. */
    fun install(context: Context) {
        if (Build.VERSION.SDK_INT > LAST_VERSION_NEEDING_IT) return
        factory = runCatching { build(context) }
            .onFailure { Log.w("LegacyTls", "could not add bundled roots; using the device's own", it) }
            .getOrNull()
    }

    private fun build(context: Context): Pair<SSLSocketFactory, X509TrustManager> {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        // The device's own roots first, so nothing it trusted today stops being trusted.
        val system = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
        for (alias in system.aliases()) {
            system.getCertificate(alias)?.let { store.setCertificateEntry("system:$alias", it) }
        }
        val certificates = CertificateFactory.getInstance("X.509")
        for (resource in listOf(R.raw.isrg_root_x1, R.raw.gts_root_r1)) {
            val certificate = context.resources.openRawResource(resource).use {
                certificates.generateCertificate(it) as X509Certificate
            }
            store.setCertificateEntry("bundled:$resource", certificate)
        }
        val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return ssl.socketFactory to trustManager
    }

    /** Applied to every OkHttp client the app builds. Does nothing from Android 7.1 on. */
    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val (socketFactory, trustManager) = factory ?: return builder
        return builder.sslSocketFactory(socketFactory, trustManager)
    }
}

/** See [LegacyTls]. */
internal fun OkHttpClient.Builder.trustModernRoots(): OkHttpClient.Builder = LegacyTls.apply(this)
