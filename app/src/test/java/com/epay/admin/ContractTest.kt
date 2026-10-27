package com.epay.admin

import org.junit.Assert.*
import org.junit.Test

class ContractTest {
    @Test fun platformRootAndSubdirectory() {
        assertEquals("https://example.com/",platformUrl("https://example.com").toString())
        assertEquals("https://example.com/pay/",platformUrl("https://example.com/pay").toString())
        assertEquals("https://example.com/pay/",platformUrl("https://example.com/pay/appapi.php").toString())
    }
    @Test fun unsafeAddressesRejected() {
        listOf("http://example.com/","https://admin:password@example.com/","https://example.com/?token=x","https://example.com/#a","https://example.com/admin/login.php","not a url").forEach {
            try {platformUrl(it);fail("Accepted $it")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun decimalMoneyDoesNotLosePrecision() {
        assertEquals("99,999,999.99",money("99999999.99"))
        assertEquals("0.10",money("0.1"))
        assertEquals("12.35",money("12.345"))
        assertEquals("0.00",money("not-money"))
    }
    @Test fun csvCannotStartFormula() {
        assertEquals("\"'=SUM(A1:A2)\"",csvCell("=SUM(A1:A2)"))
        assertEquals("\"'  +42\"",csvCell("  +42"))
        assertEquals("\"a\"\"b,c\"",csvCell("a\"b,c"))
    }
}
