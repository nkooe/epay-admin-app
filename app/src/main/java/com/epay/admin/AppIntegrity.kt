package com.epay.admin

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.security.MessageDigest

/** Checks current signers, not signing history: an old trusted signer is not enough. */
internal object ReleaseIdentityPolicy {
    fun accepts(packageName: String, signers: List<ByteArray>, debuggable: Boolean, testOnly: Boolean, debugger: Boolean): Boolean =
        packageName == "com.epay.admin" && !debuggable && !testOnly && !debugger &&
            signers.size == 1 && MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(signers.single()),
                BuildConfig.RELEASE_CERTIFICATE_SHA256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            )
}

class AdminApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppIntegrity.initialize(this)
    }
}

internal object AppIntegrity {
    const val MESSAGE = "安装包或运行环境未通过安全校验，请关闭调试器并安装官方版本。"
    @Volatile private var application: Context? = null

    fun initialize(context: Context) { application = context.applicationContext }

    fun problem(context: Context): String? {
        if (BuildConfig.DEBUG) return null
        return try {
            val manager = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 28) {
                manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.map { it.toByteArray() }.orEmpty()
            }
            val flags = context.applicationInfo.flags
            if (ReleaseIdentityPolicy.accepts(
                    context.packageName, signatures,
                    flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                    flags and ApplicationInfo.FLAG_TEST_ONLY != 0,
                    Debug.isDebuggerConnected() || Debug.waitingForDebugger()
                )) null else MESSAGE
        } catch (_: Exception) { MESSAGE }
    }

    // Fail closed for release requests even if an entry point skips the Activity.
    fun requireTrusted() {
        if (BuildConfig.DEBUG) return
        val context = application ?: throw ApiFailure(MESSAGE, 403)
        if (problem(context) != null) throw ApiFailure(MESSAGE, 403)
    }
}
