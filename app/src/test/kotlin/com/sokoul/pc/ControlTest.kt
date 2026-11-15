package com.sokoul.pc

import com.sokoul.desktop.data.DesktopDb
import com.sokoul.pc.control.AppBridge
import com.sokoul.pc.control.AppTool
import com.sokoul.pc.control.ToolPolicy
import com.sokoul.pc.control.ToolRegistry
import com.sokoul.pc.control.ToolResult
import com.sokoul.pc.control.ToolRisk
import com.sokoul.pc.data.CredentialValidator
import com.sokoul.pc.playback.ui.trackLabel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ControlTest {
    private val empty = JsonObject(emptyMap())

    private fun bridge(): AppBridge {
        val dir = Files.createTempDirectory("sokoul-control").toFile()
        val db = DesktopDb(File(dir, "t.db"))
        return testDeps(db, CredentialValidator(OkHttpClient()), FakeCatalog(), engine = FakeEngine()).bridge
    }

    private fun tool(name: String, risk: ToolRisk, value: String = "done") = AppTool(name, "d", risk) { JsonPrimitive(value) }

    @Test
    fun an_assistant_alone_reads_and_moves_but_asks_before_playing_or_changing() = runBlocking {
        val registry = ToolRegistry(listOf(tool("r", ToolRisk.Read), tool("n", ToolRisk.Navigate), tool("p", ToolRisk.Playback), tool("s", ToolRisk.Setting)))
        val alone = ToolPolicy.AssistantAlone
        assertTrue(registry.call("r", empty, alone) is ToolResult.Done)
        assertTrue(registry.call("n", empty, alone) is ToolResult.Done)
        assertTrue(registry.call("p", empty, alone) is ToolResult.Refused)
        assertTrue(registry.call("s", empty, alone) is ToolResult.Refused)
        assertTrue(registry.call("p", empty, ToolPolicy.Everything) is ToolResult.Done)
        assertTrue(registry.call("nothing", empty, alone) is ToolResult.Failed)
    }

    @Test
    fun a_tool_failure_never_repeats_a_message_that_could_hold_a_key() = runBlocking {
        val registry = ToolRegistry(listOf(AppTool("t", "d", ToolRisk.Read) { throw IllegalStateException("https://x?api_key=SECRET") }))
        val failed = registry.call("t", empty, ToolPolicy.Everything) as ToolResult.Failed
        assertFalse(failed.message.contains("SECRET"))
    }

    @Test
    fun every_tool_is_described_for_a_model_and_none_touches_a_key() {
        val tools = bridge().registry.tools
        assertTrue(tools.size >= 25)
        tools.forEach { t ->
            assertTrue(t.name, Regex("[a-z_]+").matches(t.name))
            assertTrue(t.name, t.description.length > 10)
            assertEquals("object", (t.inputSchema()["type"] as JsonPrimitive).content)
            t.params.filter { it.required }.forEach { p -> assertTrue(p.name, JsonPrimitive(p.name) in t.inputSchema()["required"]!!.jsonArray) }
            assertFalse(t.name, Regex("key|token|secret|password|credential").containsMatchIn(t.name + t.description))
            assertFalse("a key cannot be a preference", t.params.any { it.choices.any { c -> Regex("key|token").containsMatchIn(c.lowercase()) } })
        }
        val read = tools.filter { it.risk == ToolRisk.Read }.map { it.name }
        assertTrue("get_state" in read)
        assertFalse("reading must not start anything", "play" in read || "set_preference" in read)
    }

    @Test
    fun the_state_says_what_a_key_is_only_by_yes_or_no_and_holds_no_address() {
        val state = bridge().state()
        assertTrue(state["services"]!!.jsonObject.values.all { (it as JsonPrimitive).content == "true" || it.content == "false" })
        assertFalse(state.toString().contains("http"))
        assertTrue("player" in state && "preferences" in state)
    }

    @Test
    fun preferences_change_through_their_tool_and_refuse_an_unknown_value() = runBlocking {
        val b = bridge()
        val args = buildJsonObject {
            put("name", "start")
            put("value", "Ask")
        }
        assertTrue(b.registry.call("set_preference", args, ToolPolicy.Everything) is ToolResult.Done)
        assertEquals("Ask", (b.state()["preferences"]!!.jsonObject["start"] as JsonPrimitive).content)
        val wrong = buildJsonObject {
            put("name", "start")
            put("value", "Never")
        }
        assertTrue(b.registry.call("set_preference", wrong, ToolPolicy.Everything) is ToolResult.Failed)
    }

    @Test
    fun a_test_play_is_forgotten_but_a_real_viewing_stays() = runBlocking {
        val db = DesktopDb(File(Files.createTempDirectory("sokoul-forget").toFile(), "t.db"))
        val b = testDeps(db, CredentialValidator(OkHttpClient()), FakeCatalog(), engine = FakeEngine()).bridge
        fun resume(episode: Int, positionMs: Long) = com.sokoul.desktop.data.Resume(
            "tv:30991:1:$episode", "tv", 30991, 1, episode, "Cowboy Bebop", null, null, positionMs, 1_500_000, System.currentTimeMillis(), false,
        )
        db.saveResume(resume(1, 20_000))
        db.saveResume(resume(2, 900_000))
        val args = buildJsonObject {
            put("kind", "tv")
            put("id", "30991")
        }
        val done = b.registry.call("forget_watching", args, ToolPolicy.Everything)
        assertTrue(done.toString(), done is ToolResult.Done)
        assertEquals(listOf(2), db.resumeList(10).map { it.episode })
        db.close()
    }

    @Test
    fun tracks_read_as_a_person_would_say_them() {
        assertEquals("French", english { trackLabel("Track 1 - [French]") })
        assertEquals("English · Director's commentary", english { trackLabel("Track 2 - Director's commentary - [English]") })
        assertEquals("DTS 5.1", english { trackLabel("Track 3 - DTS 5.1") })
        assertEquals("Commentary", english { trackLabel("Commentary") })
    }

    private fun <T> english(block: () -> T): T {
        val before = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.ENGLISH)
        return try {
            block()
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test
    fun a_wikipedia_introduction_is_cut_for_a_screen() {
        val wiki = com.sokoul.pc.data.providers.WikipediaClient
        val first = "Star Wars [stɑɹ wɔɹz], en français La Guerre des étoiles, est un univers de science-fiction."
        val list = "Les films : A (1977), B (1980), C (1983), D (1999)."
        val tidy = wiki.tidy("$first\n$list\nAutre chose.")
        assertEquals("Star Wars, en français La Guerre des étoiles, est un univers de science-fiction.", tidy)
        val long = "Une phrase assez longue pour remplir le texte. ".repeat(40)
        assertTrue(wiki.tidy(long).endsWith("."))
        assertTrue(wiki.tidy(long).length <= 720)
    }
}
