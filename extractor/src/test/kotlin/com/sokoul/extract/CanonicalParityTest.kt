package com.sokoul.extract

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CanonicalParityTest {
    @Test fun allElevenCanonicalHostFixturesWorkInNativeQuickJs() = runBlocking {
        val body = javaClass.getResourceAsStream("/native-fixtures.json")!!.use { it.readBytes().decodeToString() }
        val fixtures = Json.parseToJsonElement(body).jsonArray
        assertEquals(11, fixtures.size)
        for (value in fixtures) {
            val fixture = value.jsonObject
            fun field(name: String) = fixture[name]?.jsonPrimitive?.content.orEmpty()
            val source = field("source")
            val url = field("url")
            assertTrue(source, LocalExtractor.supports(url))
            val bridge = HttpBridge { req ->
                val data = if ((source == "veev" && req.url.contains("/dl?")) || (source == "doodstream" && req.url.contains("/pass_md5/"))) field("api") else field("body")
                BridgeResponse(200, "OK", listOf("Content-Type" to "text/html"), req.url, data.toByteArray())
            }
            val runtime = ExtractorRuntime(bridge, { LocalExtractor.canonicalScript() }, "Fixture UA")
            try {
                val result = runtime.extract(url)
                assertEquals(source + ": " + result.error, field("expected"), result.url)
                assertFalse(source, result.headers.isEmpty())
            } finally { runtime.close() }
        }
    }
    @Test fun detectorsIncludeCanonicalAliasesAndRejectUnknownEmbeds() {
        assertTrue(LocalExtractor.supports("https://ansembed.net/embed-123.html"))
        assertTrue(LocalExtractor.supports("https://claireguessthousand.com/e/id"))
        assertFalse(LocalExtractor.supports("https://unknown.example/embed-id"))
        assertFalse(LocalExtractor.supports("http://127.0.0.1/#id"))
    }
}
