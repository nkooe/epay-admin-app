package com.epay.admin

import org.junit.Assert.*
import org.junit.Test
import java.security.cert.CertificateFactory

class AppIntegrityTest {
    private val officialCertificate: ByteArray get() = javaClass.getResourceAsStream("/release-certificate.crt")!!.use {
        CertificateFactory.getInstance("X.509").generateCertificate(it).encoded
    }
    private fun trusted(
        packageName: String = "com.epay.admin", signers: List<ByteArray> = listOf(officialCertificate),
        debuggable: Boolean = false, testOnly: Boolean = false, debugger: Boolean = false
    ) = ReleaseIdentityPolicy.accepts(packageName, signers, debuggable, testOnly, debugger)

    @Test fun originalReleaseCertificateIsAccepted() { assertTrue(trusted()) }
    @Test fun resignedCertificateIsRejected() { assertFalse(trusted(signers = listOf("repacked".toByteArray()))) }
    @Test fun missingSignerIsRejected() { assertFalse(trusted(signers = emptyList())) }
    @Test fun extraSignerCannotHideBehindOfficialCertificate() {
        assertFalse(trusted(signers = listOf(officialCertificate, "extra".toByteArray())))
    }
    @Test fun changedPackageIsRejected() { assertFalse(trusted(packageName = "com.epay.copy")) }
    @Test fun debuggableReleaseIsRejected() { assertFalse(trusted(debuggable = true)) }
    @Test fun testOnlyReleaseIsRejected() { assertFalse(trusted(testOnly = true)) }
    @Test fun attachedDebuggerIsRejected() { assertFalse(trusted(debugger = true)) }
}
