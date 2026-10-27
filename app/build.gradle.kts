import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
val releaseCertificateSha256 = Properties().apply {
    rootProject.file("release-integrity.properties").inputStream().use { load(it) }
}.getProperty("certificateSha256").also {
    require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid public release certificate fingerprint" }
}
val signingProperties = Properties().apply {
    rootProject.file("signing/signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
android {
    namespace = "com.epay.admin"
    compileSdk = 35
    defaultConfig { applicationId = "com.epay.admin"; minSdk = 26; targetSdk = 35; versionCode = 30; versionName = "1.2.19"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; buildConfigField("String", "RELEASE_CERTIFICATE_SHA256", "\"$releaseCertificateSha256\"") }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs { if(signingProperties.isNotEmpty()) create("localRelease") {
        storeFile = rootProject.file("signing/"+signingProperties.getProperty("storeFile"))
        storePassword = signingProperties.getProperty("storePassword")
        keyAlias = signingProperties.getProperty("keyAlias")
        keyPassword = signingProperties.getProperty("keyPassword")
        enableV1Signing = false
        enableV2Signing = true
        enableV3Signing = true
    } }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release { isDebuggable = false; isJniDebuggable = false; isMinifyEnabled = true; isShrinkResources = true; if(signingProperties.isNotEmpty())signingConfig=signingConfigs.getByName("localRelease"); proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
val verifyReleaseSigning by tasks.registering {
    doLast {
        check(signingProperties.isNotEmpty()) { "Release signing is required; configure local signing/signing.properties" }
        val file = rootProject.file("signing/" + signingProperties.getProperty("storeFile"))
        val store = KeyStore.getInstance(file, signingProperties.getProperty("storePassword").toCharArray())
        val certificate = store.getCertificate(signingProperties.getProperty("keyAlias"))
        check(certificate != null) { "Release signing certificate is missing" }
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        check(actual == releaseCertificateSha256) { "Release certificate does not match the trusted certificate; refusing to produce an unusable APK" }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseSigning) }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
