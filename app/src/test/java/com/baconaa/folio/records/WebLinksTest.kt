package com.baconaa.folio.records

import org.junit.Assert.*
import org.junit.Test

class WebLinksTest {
    @Test fun acceptsHttpAndHttpsWithoutContactingTheHost() {
        assertEquals("http://localhost:8080/a%20b?q=1#here", webAddress(" HTTP://localhost:8080/a%20b?q=1#here "))
        assertEquals("https://xn--fsqu00a.test/%E8%AF%B4%E6%98%8E", webAddress("https://例子.test/说明"))
        assertEquals("https://[::1]/", webAddress("https://[::1]/"))
    }

    @Test fun rejectsIncompleteHostsAndOtherSchemes() {
        for (address in listOf("", "https://", "https:filter", "//example.test", "https:///guide",
            "file:///storage/note", "javascript:alert(1)", "https://bad host/", "https://host:broken/", "http://host:65536")) {
            assertNull(address, webAddress(address))
        }
    }
}
