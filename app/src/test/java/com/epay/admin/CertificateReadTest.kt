package com.epay.admin

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CertificateReadTest {
    @Test fun providerWithUnknownSizeCannotExceedUploadLimit(){
        assertThrows(IllegalArgumentException::class.java){readCertificate(ByteArrayInputStream(ByteArray(262145)))}
    }
    @Test fun acceptsExactLimitWithoutTruncation(){
        val bytes=ByteArray(262144){(it%127).toByte()}
        assertArrayEquals(bytes,readCertificate(ByteArrayInputStream(bytes)))
    }
    @Test fun rejectsEmptyProvider(){
        assertThrows(IllegalArgumentException::class.java){readCertificate(ByteArrayInputStream(byteArrayOf()))}
    }
}
