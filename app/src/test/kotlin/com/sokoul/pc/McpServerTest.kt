package com.sokoul.pc

import com.sokoul.pc.control.AppTool
import com.sokoul.pc.control.McpServer
import com.sokoul.pc.control.ToolParam
import com.sokoul.pc.control.ToolProblem
import com.sokoul.pc.control.ToolRegistry
import com.sokoul.pc.control.ToolRisk
import com.sokoul.pc.control.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The MCP server as a client sees it: the protocol's handshake, the tool list, a call, and who is turned away. */
class McpServerTest {
    private lateinit var server: McpServer
    private lateinit var tokenFile: File
    private val http = OkHttpClient()
    private val registry = ToolRegistry(
        listOf(
            AppTool("echo", "Says the word back.", ToolRisk.Read, listOf(ToolParam("word", "Any word"))) { a -> JsonObject(mapOf("said" to JsonPrimitive(a.text("word")))) },
            AppTool("broken", "Always fails.", ToolRisk.Navigate) { throw ToolProblem("No such page") },
        ),
    )

    @Before
    fun start() {
        tokenFile = File.createTempFile("mcp-token", ".txt").also { it.delete() }
        server = McpServer({ registry }, 0, tokenFile).also { it.start() }
    }

    @After
    fun stop() {
        server.stop()
        tokenFile.delete()
    }

    private fun post(body: String, token: String? = server.token, origin: String? = null): Pair<Int, String> {
        val request = Request.Builder().url("http://127.0.0.1:${server.boundPort}/mcp")
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply {
                token?.let { header("Authorization", "Bearer $it") }
                origin?.let { header("Origin", it) }
            }.build()
        return http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
    }

    private fun rpc(id: Int, method: String, params: String = "{}"): JsonObject {
        val (code, body) = post("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
        assertEquals(200, code)
        return Json.parseToJsonElement(body).jsonObject
    }

    @Test
    fun handshakeListAndCall() {
        val init = rpc(1, "initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""")
        assertEquals("sokoul", init["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(202, post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").first)

        val tools = rpc(2, "tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("echo", "broken"), tools.map { it["name"]!!.jsonPrimitive.content })
        assertEquals("word", tools[0]["inputSchema"]!!.jsonObject["required"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(tools[0]["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)

        val ok = rpc(3, "tools/call", """{"name":"echo","arguments":{"word":"sokoul"}}""")["result"]!!.jsonObject
        assertEquals(false, ok["isError"]!!.jsonPrimitive.boolean)
        assertEquals("sokoul", ok["structuredContent"]!!.jsonObject["said"]!!.jsonPrimitive.content)

        val failed = rpc(4, "tools/call", """{"name":"broken","arguments":{}}""")["result"]!!.jsonObject
        assertTrue(failed["isError"]!!.jsonPrimitive.boolean)
        assertEquals("No such page", failed["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)

        val unknown = rpc(5, "resources/list")
        assertEquals(-32601, unknown["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun refusesWithoutTokenOrFromAWebPage() {
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        assertEquals(401, post(body, token = null).first)
        assertEquals(401, post(body, token = "wrong").first)
        assertEquals(403, post(body, origin = "https://evil.example").first)
        assertEquals(200, post(body, origin = "http://localhost:3000").first)
    }

    @Test
    fun tokenIsKeptBetweenStarts() {
        val first = server.token
        server.stop()
        val again = McpServer({ registry }, 0, tokenFile)
        assertEquals(first, again.token)
        assertTrue(first.length >= 32)
    }
}
