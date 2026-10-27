package com.epay.admin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.KeyStore
import java.text.DecimalFormat
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
fun JSONObject.s(key: String, fallback: String = "") = if (isNull(key)) fallback else optString(key, fallback)
// PHP serializes consecutive numeric option keys as an array, named keys as an object.
internal fun JSONObject.fieldOptions(): JSONObject = when (val options = opt("options")) {
    is JSONObject -> options
    is JSONArray -> JSONObject().apply { for (index in 0 until options.length()) put(index.toString(), options.get(index)) }
    else -> JSONObject()
}
internal fun fieldVisible(field:JSONObject,values:Map<String,String>):Boolean {
    val conditions=field.optJSONObject("show_when")?:return true
    return conditions.keys().asSequence().all{key->
        val allowed=conditions.optJSONArray(key)?:return@all false
        (0 until allowed.length()).any{allowed.optString(it)==values[key]}
    }
}
internal fun monitorConfiguration(site:String,pid:String,key:String):String {
    val normalized=platformUrl(site).toString().trimEnd('/')
    require(pid.matches(Regex("[1-9][0-9]{0,9}"))){"请填写有效商户号"}
    require(key.isNotBlank()&&key.length<=1024){"请填写商户 V1 MD5 密钥"}
    return java.util.Base64.getEncoder().encodeToString(obj("site" to normalized,"pid" to pid.toLong(),"key" to key).toString().toByteArray(Charsets.UTF_8))
}
fun JSONObject.rows(key: String = "rows"): List<JSONObject> = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
fun money(value: String): String = try { DecimalFormat("#,##0.00").format(BigDecimal(value).setScale(2, RoundingMode.HALF_UP)) } catch (_: NumberFormatException) { "0.00" }
fun platformUrl(input: String): HttpUrl {
    val url = input.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("请输入完整的平台 HTTPS 地址")
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "平台地址必须使用 HTTPS，且不能包含账号、参数或锚点" }
    var path = url.encodedPath
    if (path.endsWith("/appapi.php")) path = path.removeSuffix("appapi.php")
    require(!path.substringAfterLast('/').contains('.')) { "请填写平台根目录地址，例如 https://pay.example.com/" }
    return url.newBuilder().encodedPath(path.trimEnd('/') + "/").build()
}
fun csvCell(value: String): String {
    val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || value.startsWith('\t') || value.startsWith('\r')) "'$value" else value
    return "\"${safe.replace("\"", "\"\"")}\""
}

/** Tokens are encrypted with a non-exportable Android Keystore key. Passwords are never stored. */
@android.annotation.SuppressLint("ApplySharedPref") // Persist token rotations and pending request keys before sending a write.
class SessionVault(context: Context) {
    private val prefs = context.getSharedPreferences("admin_session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("epay-admin-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("epay-admin-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): JSONObject? {
        AppIntegrity.requireTrusted()
        return try {
            prefs.getString("session", null)?.let {
                val bytes = Base64.decode(it, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            }
        } catch (_: Exception) { clear(); null }
    }
    fun write(value: JSONObject) {
        AppIntegrity.requireTrusted()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("session", encoded).commit()) { "无法保存登录会话" }
    }
    fun clear() { prefs.edit().clear().commit() }
}

class ApiFailure(message: String, val status: Int = 0) : IOException(message)
interface Source {
    suspend fun call(route: String, data: JSONObject = obj(), post: Boolean = false, key: String? = null): JSONObject
}

class NetworkSource(val base: HttpUrl, private val vault: SessionVault) : Source {
    companion object {
        private val instances = mutableMapOf<String, java.lang.ref.WeakReference<NetworkSource>>()
        private fun instanceKey(base: HttpUrl, value: JSONObject) = base.toString() + value.s("session_id")
        @Synchronized internal fun forStoredSession(base: HttpUrl, vault: SessionVault, value: JSONObject): NetworkSource {
            val key = instanceKey(base, value)
            return instances[key]?.get() ?: NetworkSource(base, vault).apply { restore(value) }
        }
        @Synchronized private fun remember(source: NetworkSource, value: JSONObject) {
            instances.entries.removeAll { it.value.get() == null }
            instances[instanceKey(source.base, value)] = java.lang.ref.WeakReference(source)
        }
    }
    private val client = PlatformTransport.client()
    private val mutex = Mutex()
    @Volatile private var session: JSONObject? = null
    fun restore(value: JSONObject) { session = value; remember(this, value) }
    fun accept(value: JSONObject) { value.put("base", base.toString()); session?.optJSONObject("pending")?.let { value.put("pending",it) }; vault.write(value); session = value; remember(this, value) }
    fun clear() { session = null; vault.clear() }
    private suspend fun send(route: String, data: JSONObject, post: Boolean, token: String, key: String?): JSONObject {
        val endpoint = base.resolve("appapi.php")!!.newBuilder().addQueryParameter("route", "v1/$route")
        if (!post) data.keys().forEach { endpoint.addQueryParameter(it, data.s(it)) }
        val builder = Request.Builder().url(endpoint.build()).header("Accept", "application/json").header("User-Agent", "EpayAdmin/${BuildConfig.VERSION_NAME} Android")
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        if (key != null) builder.header("Idempotency-Key", key)
        if (post) builder.post(data.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val requestClient=if(route=="plugins/run")client.newBuilder().readTimeout(95,java.util.concurrent.TimeUnit.SECONDS).callTimeout(100,java.util.concurrent.TimeUnit.SECONDS).build()else client
        val call = requestClient.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(transportFailure(e)) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (response.isRedirect) throw ApiFailure("平台发生跳转，请填写最终 HTTPS 地址", response.code)
                            val bodySource = response.body?.source() ?: throw ApiFailure("接口响应为空")
                            bodySource.request(2_000_001L)
                            if (bodySource.buffer.size > 2_000_000) throw ApiFailure("响应内容过大，请缩小查询范围")
                            val raw = bodySource.readUtf8()
                            val json = try { JSONObject(raw) } catch (_: Exception) { throw ApiFailure("接口未返回 JSON，请检查 appapi.php 是否部署", response.code) }
                            if (!response.isSuccessful || json.optInt("code", -1) != 0) throw ApiFailure(json.s("msg", "请求失败") + json.s("request_id").let { if (it.isEmpty()) "" else "\n请求编号：$it" }, response.code)
                            if (!continuation.isCancelled) continuation.resume(json.optJSONObject("data") ?: obj())
                        } catch (e: Exception) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
                    }
                }
            })
        }
    }
    override suspend fun call(route: String, data: JSONObject, post: Boolean, key: String?): JSONObject {
        val public = route in listOf("site", "auth/captcha", "auth/login", "auth/totp", "auth/refresh")
        val usedToken = if (public) "" else session?.s("access_token") ?: ""
        // A stable key covers the original attempt and the one retry after a confirmed 401.
        val business = post && !route.startsWith("auth/")
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest((route+data.toString()).toByteArray()).joinToString(""){"%02x".format(it)}
        val prior = session?.optJSONObject("pending")
        val requestKey = if(business && prior?.s("fingerprint")==fingerprint) prior.s("key") else key ?: if(business) UUID.randomUUID().toString() else null
        if(business) session?.let { stored -> val next=JSONObject(stored.toString()).put("pending",obj("fingerprint" to fingerprint,"key" to requestKey));vault.write(next);session=next }
        fun finished() { if(business)session?.let {stored->val next=JSONObject(stored.toString());next.remove("pending");vault.write(next);session=next} }
        try { val result=send(route, data, post, usedToken, requestKey);finished();return result }
        catch (e: ApiFailure) {
            if (e.status != 401 || public || session == null) { if(e.status in 400..499 && e.status!=409)finished();throw e }
            refreshSession(usedToken,e)
            val result=send(route, data, post, session?.s("access_token") ?: throw e, requestKey);finished();return result
        }
    }
    private suspend fun refreshSession(usedToken: String, failure: ApiFailure) = mutex.withLock {
        if (session?.s("access_token") == usedToken) {
            val refresh = session?.s("refresh_token") ?: throw failure
            try { accept(send("auth/refresh", obj("refresh_token" to refresh), true, "", null)) }
            catch (e: ApiFailure) { if (e.status == 401) clear(); throw e }
        }
    }
    internal suspend fun streamReceipts(data: JSONObject, receive: (String, JSONObject) -> Unit) {
        val token = session?.s("access_token") ?: throw ApiFailure("请重新登录",401)
        try { streamRequest(data,token,receive) }
        catch (e: ApiFailure) {
            if (e.status != 401 || session == null) throw e
            refreshSession(token,e)
            streamRequest(data,session?.s("access_token") ?: throw e,receive)
        }
    }
    private suspend fun streamRequest(data: JSONObject, token: String, receive: (String, JSONObject) -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        val endpoint = base.resolve("appapi.php")!!.newBuilder().addQueryParameter("route","v1/notifications/stream")
        data.keys().forEach { endpoint.addQueryParameter(it,data.s(it)) }
        val request = Request.Builder().url(endpoint.build()).header("Authorization","Bearer $token")
            .header("Accept","text/event-stream").header("User-Agent","EpayAdmin/${BuildConfig.VERSION_NAME} Android").build()
        val call = client.newBuilder().readTimeout(15,java.util.concurrent.TimeUnit.SECONDS).callTimeout(40,java.util.concurrent.TimeUnit.SECONDS).build().newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(transportFailure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) throw ApiFailure("收款连接失败",response.code)
                        if (response.header("Content-Type")?.substringBefore(';')?.trim() != "text/event-stream") throw ApiFailure("请更新平台收款通知接口",503)
                        val input = response.body?.source() ?: throw ApiFailure("收款连接为空")
                        val parser = ReceiptEventParser { type,event ->
                            if (type == "error") throw ApiFailure(event.s("message","收款连接中断"),event.optInt("status",503))
                            if (!continuation.isCancelled) receive(type,event)
                        }
                        while (!continuation.isCancelled && !input.exhausted()) parser.line(input.readUtf8LineStrict(16_384))
                        if (!continuation.isCancelled) continuation.resume(Unit)
                    } catch (e: Exception) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
                }
            }
        })
    }
}
