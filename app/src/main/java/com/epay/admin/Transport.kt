package com.epay.admin

import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal object PlatformTransport {
    // Use OkHttp/Android defaults for HTTPS trust, hostname checks and system proxies.
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain -> AppIntegrity.requireTrusted(); chain.proceed(chain.request()) }
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .build()
}

internal fun transportFailure(error: IOException): ApiFailure = if (error is ApiFailure) error else ApiFailure(when (error) {
    is SSLPeerUnverifiedException -> "HTTPS 证书与平台地址不匹配，请检查平台地址和证书。"
    is SSLHandshakeException -> "HTTPS 连接失败，请检查设备时间、网络和平台证书。"
    is SSLException -> "HTTPS 连接失败，请检查网络和平台证书。"
    else -> "连接失败，请检查网络和平台地址"
})
