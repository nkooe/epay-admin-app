package com.epay.admin

import okhttp3.Dns
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

class TransportTest {
    @Test fun integrityRejectionRetainsForbiddenStatusToStopBackgroundRetries() {
        val failure = ApiFailure("untrusted package", 403)
        assertSame(failure, transportFailure(failure))
        assertEquals(403, transportFailure(failure).status)
    }
    private fun fixture(host: String, trusted: Boolean, tunnel: Boolean = false, test: (MockWebServer, okhttp3.OkHttpClient) -> Unit) {
        val certificate=HeldCertificate.Builder().commonName(host).addSubjectAlternativeName(host).build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls=HandshakeCertificates.Builder().apply { if(trusted)addTrustedCertificate(certificate.certificate) }.build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(),tunnel)
            if(tunnel)server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END))
            server.enqueue(MockResponse().setBody("ok"))
            server.start()
            val client=PlatformTransport.client().newBuilder()
                .sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager)
                .dns(object : Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) }).build()
            try { test(server,client) }
            finally { client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun validTrustedCertificateCanSendHttp() = fixture("localhost",true) { server,client ->
        client.newCall(Request.Builder().url(server.url("/").newBuilder().host("localhost").build()).build()).execute().use { assertEquals("ok",it.body!!.string()) }
        assertEquals(1,server.requestCount)
    }

    @Test fun trustedReplacementPlatformKeysWorkWithoutUpdatingClient() {
        // Each fixture generates a different key for the real platform hostname.
        repeat(2) { fixture("epay.973700.xyz",true) { server,client ->
            val url=server.url("/").newBuilder().host("epay.973700.xyz").build()
            client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals("ok",it.body!!.string()) }
            assertEquals(1,server.requestCount)
        } }
    }

    @Test fun selectedHttpsProxyCanTunnelRequest() = fixture("epay.973700.xyz",true,true) { server,client ->
        var selections=0
        val selector=object : ProxySelector() {
            override fun select(uri:URI):List<Proxy> { selections++;return listOf(server.toProxyAddress()) }
            override fun connectFailed(uri:URI,address:SocketAddress,error:IOException) { throw AssertionError(error) }
        }
        val proxied=client.newBuilder().proxySelector(selector).build()
        val url=server.url("/").newBuilder().host("epay.973700.xyz").build()
        try {
            proxied.newCall(Request.Builder().url(url).build()).execute().use { assertEquals("ok",it.body!!.string()) }
            assertTrue(selections>0)
            assertEquals("CONNECT",server.takeRequest().method)
            assertEquals("GET",server.takeRequest().method)
        } finally { proxied.connectionPool.evictAll();proxied.dispatcher.executorService.shutdown() }
    }

    @Test fun customPlatformStillRequiresTrustedCertificates() = fixture("localhost",false) { server,client ->
        assertThrows(SSLException::class.java) { client.newCall(Request.Builder().url(server.url("/").newBuilder().host("localhost").build()).build()).execute().close() }
        assertEquals(0,server.requestCount)
    }

    @Test fun trustedCertificateForWrongHostnameIsBlocked() = fixture("other.example",true) { server,client ->
        assertThrows(SSLPeerUnverifiedException::class.java) { client.newCall(Request.Builder().url(server.url("/").newBuilder().host("localhost").build()).build()).execute().close() }
        assertEquals(0,server.requestCount)
    }

    @Test fun httpsFailureDoesNotAskForAppUpdateOrLeakHandshakeDetails() {
        val failure=transportFailure(SSLPeerUnverifiedException("local-fixture-sensitive-header"))
        assertTrue(failure.message!!.contains("平台地址"))
        assertFalse(failure.message!!.contains("更新 App"))
        assertFalse(failure.message!!.contains("local-fixture"))
        assertEquals(0,failure.status)
        assertTrue(transportFailure(IOException("internal route")).message!!.contains("检查网络"))
    }
}
