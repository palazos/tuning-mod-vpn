package com.winlator.cmod.vpn.core

import android.content.Context
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Properties
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

// Single place that knows how to reach the broker: address, credentials and TLS trust.
// The credentials and the CA are not in the repo; they are read from assets/vpn/ and must
// be provided at build time (see vpn_broker.properties.example).
internal object BrokerConnection {
    const val CONNECT_TIMEOUT_MS = 8_000L
    private const val KEEP_ALIVE_S = 30

    private const val CREDENTIALS_ASSET = "vpn/vpn_broker.properties"

    // Only this CA is trusted for the broker, not the system store.
    private const val CA_ASSET = "vpn/vpn_broker_ca.crt"

    @Volatile
    private var context: Context? = null

    fun attach(context: Context) {
        this.context = context.applicationContext
    }

    private fun assets() = checkNotNull(context) { "BrokerConnection is not attached" }.assets

    private val credentials: Properties by lazy {
        val properties = Properties()
        runCatching { assets().open(CREDENTIALS_ASSET).use { properties.load(it) } }
            .getOrElse { throw IllegalStateException("Missing $CREDENTIALS_ASSET in the app assets", it) }
        properties
    }

    private val socketFactory: SSLSocketFactory by lazy {
        val ca = runCatching {
            assets().open(CA_ASSET).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        }.getOrElse { throw IllegalStateException("Missing $CA_ASSET in the app assets", it) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("vpn-broker-ca", ca)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
        SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }

    fun newClient(clientId: String) =
        MqttAsyncClient(VpnConfig.BROKER_URI, clientId, MemoryPersistence())

    fun options() = MqttConnectOptions().apply {
        isCleanSession = true
        isAutomaticReconnect = true
        keepAliveInterval = KEEP_ALIVE_S
        connectionTimeout = (CONNECT_TIMEOUT_MS / 1000).toInt()
        userName = credentials.getProperty("username", "")
        password = credentials.getProperty("password", "").toCharArray()
        socketFactory = this@BrokerConnection.socketFactory
        // The broker certificate has no IP SAN, which Android requires for hostname checks.
        // The chain is still validated against our private CA, which is the pinned identity.
        isHttpsHostnameVerificationEnabled = false
    }

    // MqttException often has no message of its own; the real reason is in the cause chain.
    fun describe(error: Throwable?): String {
        val parts = generateSequence(error) { it.cause }.take(4)
            .map { "${it.javaClass.simpleName}: ${it.message}" }
        return parts.joinToString(" <- ")
    }
}
