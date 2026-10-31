package app.localdrop

import org.junit.Assert.*
import org.junit.Test

class BrowserRulesTest {
    @Test fun addsHttpsForTypedDomain() { assertEquals("https://youtube.com",BrowserRules.address(" youtube.com ")) }
    @Test fun preservesVideoQuery() {
        assertEquals("https://m.youtube.com/watch?v=abc&list=xyz",BrowserRules.address("https://m.youtube.com/watch?v=abc&list=xyz"))
    }
    @Test fun rejectsUnsafeSchemes() {
        listOf("javascript:alert(1)","file:///data/user/0/a","content://media/a","intent://watch","http://example.com","data:text/html,a")
            .forEach { assertNull(it,BrowserRules.address(it)) }
    }
    @Test fun rejectsCredentialsAndMalformedAddresses() {
        listOf("", "not a url", "https://user:pass@example.com", "https:///watch", "https://")
            .forEach { assertNull(it,BrowserRules.address(it)) }
    }
}
