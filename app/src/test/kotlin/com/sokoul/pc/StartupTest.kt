package com.sokoul.pc

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.sokoul.desktop.data.AppDirs
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.Playlist
import com.sokoul.desktop.data.PlaylistType
import com.sokoul.desktop.data.ProviderConfig
import com.sokoul.iptv.data.net.Redact
import com.sokoul.pc.data.CredentialValidator
import com.sokoul.pc.data.LegacyImport
import com.sokoul.pc.data.Verdict
import com.sokoul.pc.i18n.Strings
import com.sokoul.pc.i18n.Text
import com.sokoul.pc.startup.FIELD_PASSWORD
import com.sokoul.pc.startup.FIELD_EPG_URL
import com.sokoul.pc.startup.FIELD_ANIME_BACKEND
import com.sokoul.pc.startup.FIELD_SERVER
import com.sokoul.pc.startup.FIELD_USERNAME
import com.sokoul.pc.startup.GENERAL_ERROR
import com.sokoul.pc.startup.SPLASH_MILLIS
import com.sokoul.pc.startup.SetupController
import com.sokoul.pc.startup.SetupState
import com.sokoul.pc.startup.StartupRoute
import com.sokoul.pc.startup.fieldIsEditableOnTv
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclFileAttributeView
import java.security.MessageDigest
import java.util.Locale

private const val SAMPLE_KEY = "sample-secret-key-abcd"
private const val SAMPLE_PASSWORD = "sample-secret-password"

class StartupTest {
    private lateinit var dir: File
    private lateinit var db: DesktopDb
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        ProviderConfig.builtIn = { emptyMap() }
        dir = Files.createTempDirectory("sokoul-test").toFile()
        db = DesktopDb(File(dir, "target.db"))
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        db.close()
        runCatching { server.shutdown() }
        dir.deleteRecursively()
    }

    private fun base() = server.url("/").toString().trimEnd('/')

    private fun validator() = CredentialValidator(OkHttpClient(), tmdbBase = base(), realDebridBase = base(), otherKey = { _, _ -> true })

    private fun controller() = SetupController(db, validator())

    private fun deps() = testDeps(db, validator())

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun xtreamReply(auth: Int) = MockResponse().setBody("""{"user_info":{"auth":$auth,"status":"Active"},"server_info":{}}""")

    private fun legacySource(serverUrl: String = "http://example.test"): File {
        val file = File(dir, "legacy.db")
        DesktopDb(file).use { legacy ->
            legacy.upsertPlaylist(Playlist(0, "Provider", PlaylistType.XTREAM, serverUrl, "user", SAMPLE_PASSWORD, null, null))
            ProviderConfig.setKey(legacy, "tmdb", SAMPLE_KEY)
            ProviderConfig.setKey(legacy, "realdebrid", "sample-rd-token")
        }
        return file
    }

    // --- Language

    @Test
    fun french_system_gets_french() = assertEquals("Continuer", Strings.resolve(Text.Continue, Locale.FRANCE))

    @Test
    fun other_system_gets_english() = assertEquals("Continue", Strings.resolve(Text.Continue, Locale.GERMANY))

    @Test
    fun every_text_exists_in_both_languages() {
        Text.entries.forEach {
            assertTrue("missing English: $it", Strings.english.containsKey(it))
            assertTrue("missing French: $it", Strings.french.containsKey(it))
        }
    }

    // --- Data folder and access

    @Test
    fun data_folder_is_separate_from_the_existing_app() = assertEquals("SokoulPC", AppDirs.data.name)

    @Test
    fun data_folder_is_restricted_to_the_owner() {
        val target = File(dir, "restricted").also { it.mkdirs() }
        AppDirs.restrictToOwner(target)
        val view = Files.getFileAttributeView(target.toPath(), AclFileAttributeView::class.java) ?: return
        assertEquals(1, view.acl.size)
        assertEquals(view.owner, view.acl.single().principal())
    }

    // --- Import of existing credentials

    @Test
    fun import_finds_credentials_and_leaves_the_source_unchanged() {
        val source = legacySource()
        val before = sha(source)
        val summary = LegacyImport.run(source, db, dir)
        assertEquals(1, summary.playlists)
        assertEquals(2, summary.keys)
        assertEquals(before, sha(source))
        assertEquals(SAMPLE_KEY, ProviderConfig.key(db, "tmdb"))
        assertEquals(SAMPLE_PASSWORD, db.playlists().single().password)
    }

    @Test
    fun import_leaves_no_temporary_copy() {
        val source = legacySource()
        val work = File(dir, "work").also { it.mkdirs() }
        LegacyImport.run(source, db, work)
        assertEquals(emptyList<String>(), work.list()!!.toList())
    }

    @Test
    fun import_with_missing_source_changes_nothing() {
        assertEquals(0, LegacyImport.run(File(dir, "absent.db"), db, dir).keys)
        assertNull(ProviderConfig.key(db, "tmdb"))
    }

    @Test
    fun import_with_unreadable_source_does_not_fail() {
        val garbage = File(dir, "garbage.db").also { it.writeText("not a database") }
        assertEquals(0, LegacyImport.run(garbage, db, dir).playlists)
    }

    @Test
    fun import_with_locked_source_does_not_fail() {
        val source = legacySource()
        FileChannel.open(source.toPath(), StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                assertEquals(0, LegacyImport.run(source, db, dir).keys)
            }
        }
    }

    // --- Validation

    @Test
    fun key_accepted_refused_and_unreachable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(Verdict.Accepted, validator().key("tmdb", SAMPLE_KEY))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(Verdict.Refused, validator().key("tmdb", SAMPLE_KEY))
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(Verdict.Accepted, validator().key("realdebrid", SAMPLE_KEY))
        val v = validator()
        server.shutdown()
        assertEquals(Verdict.Unreachable, v.key("tmdb", SAMPLE_KEY))
    }

    @Test
    fun iptv_accepted_and_refused() = runBlocking {
        server.enqueue(xtreamReply(1))
        assertEquals(Verdict.Accepted, validator().iptv(base(), "user", SAMPLE_PASSWORD, null))
        server.enqueue(xtreamReply(0))
        assertEquals(Verdict.Refused, validator().iptv(base(), "user", SAMPLE_PASSWORD, null))
    }

    @Test
    fun refused_iptv_keeps_typing_and_stores_nothing() = runBlocking {
        val c = controller()
        c.update(FIELD_SERVER, base())
        c.update(FIELD_USERNAME, "user")
        c.update(FIELD_PASSWORD, SAMPLE_PASSWORD)
        server.enqueue(xtreamReply(0))
        assertFalse(c.submit())
        assertEquals(Text.ErrorIptvRefused, c.errors[FIELD_SERVER])
        assertEquals(SAMPLE_PASSWORD, c.edits[FIELD_PASSWORD])
        assertTrue(db.playlists().isEmpty())
        assertEquals(SetupState.None, StartupRoute.state(db))
    }

    @Test
    fun unreachable_iptv_keeps_typing_and_stores_nothing() = runBlocking {
        val c = controller()
        c.update(FIELD_SERVER, base())
        c.update(FIELD_USERNAME, "user")
        c.update(FIELD_PASSWORD, SAMPLE_PASSWORD)
        server.shutdown()
        assertFalse(c.submit())
        assertEquals(base(), c.edits[FIELD_SERVER])
        assertTrue(db.playlists().isEmpty())
    }

    @Test
    fun refused_key_is_not_stored() = runBlocking {
        val c = controller()
        c.update("tmdb", SAMPLE_KEY)
        server.enqueue(MockResponse().setResponseCode(401))
        assertFalse(c.submit())
        assertEquals(Text.ErrorKeyRefused, c.errors["tmdb"])
        assertEquals(SAMPLE_KEY, c.edits["tmdb"])
        assertNull(ProviderConfig.key(db, "tmdb"))
    }

    @Test
    fun valid_key_is_stored_and_setup_is_done() = runBlocking {
        val c = controller()
        c.update("tmdb", SAMPLE_KEY)
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(c.submit())
        assertEquals(SAMPLE_KEY, ProviderConfig.key(db, "tmdb"))
        assertEquals(SetupState.Done, StartupRoute.state(db))
    }

    @Test
    fun imported_credentials_continue_without_typing() = runBlocking {
        LegacyImport.run(legacySource(base()), db, dir)
        val controller = controller()
        assertTrue(controller.hasSavedIptv())
        server.enqueue(xtreamReply(1))
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(controller.submit())
        assertEquals(SetupState.Done, StartupRoute.state(db))
        assertEquals(SAMPLE_PASSWORD, db.playlists().single().password)
    }

    @Test
    fun xmltv_source_can_be_updated_without_retyping_or_changing_iptv_credentials() = runBlocking {
        val original = Playlist(0, "Provider", PlaylistType.XTREAM, base(), "saved-user", SAMPLE_PASSWORD, null, null)
        db.upsertPlaylist(original)
        val guideUrl = "https://guide.example.net/provider.xml.gz"
        val controller = controller()
        controller.update(FIELD_EPG_URL, guideUrl)

        assertTrue(controller.submit())
        val stored = db.playlists().single()
        assertEquals("saved-user", stored.username)
        assertEquals(SAMPLE_PASSWORD, stored.password)
        assertEquals(guideUrl, stored.epgUrl)
        assertTrue(controller.guideSourceChanged)
        assertFalse(controller.playlistChanged)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun xmltv_source_can_be_cleared_without_changing_credentials() = runBlocking {
        db.upsertPlaylist(Playlist(0, "Provider", PlaylistType.XTREAM, base(), "saved-user", SAMPLE_PASSWORD, "https://guide.example.net/guide.xml", null))
        val controller = controller()
        controller.update(FIELD_EPG_URL, "off")

        assertTrue(controller.submit())
        assertNull(db.playlists().single().epgUrl)
        assertEquals(SAMPLE_PASSWORD, db.playlists().single().password)
    }

    @Test
    fun invalid_xmltv_source_is_rejected_without_network_calls() = runBlocking {
        db.upsertPlaylist(Playlist(0, "Provider", PlaylistType.XTREAM, base(), "saved-user", SAMPLE_PASSWORD, null, null))
        val controller = controller()
        controller.update(FIELD_EPG_URL, "file:///tmp/guide.xml")

        assertFalse(controller.submit())
        assertEquals(Text.ErrorEpgUrl, controller.errors[FIELD_EPG_URL])
        assertEquals(0, server.requestCount)
    }

    @Test
    fun saved_credentials_are_never_sent_to_a_new_server() = runBlocking {
        LegacyImport.run(legacySource(), db, dir)
        val controller = controller()
        controller.update(FIELD_SERVER, base())
        assertFalse(controller.submit())
        assertEquals(Text.ErrorIptvIncomplete, controller.errors[FIELD_SERVER])
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anime_backend_url_is_optional_validated_and_separate_from_mainapi() = runBlocking {
        ProviderConfig.setKey(db, "mainapi_url", "https://mainapi.example.net")
        val controller = controller()
        val animeUrl = server.url("/").toString().trimEnd('/')
        controller.update(FIELD_ANIME_BACKEND, animeUrl)

        assertTrue(controller.submit())
        assertEquals(animeUrl, ProviderConfig.animeBackendUrl(db))
        assertEquals("https://mainapi.example.net", ProviderConfig.key(db, "mainapi_url"))
        assertEquals(0, server.requestCount)

        val invalid = controller()
        invalid.update(FIELD_ANIME_BACKEND, "https://example.com")
        assertFalse(invalid.submit())
        assertEquals(Text.ErrorAnimeBackendUrl, invalid.errors[FIELD_ANIME_BACKEND])
        assertEquals(0, server.requestCount)
    }

    @Test
    fun entering_off_clears_only_the_optional_anime_backend() = runBlocking {
        ProviderConfig.setKey(db, "mainapi_url", "https://mainapi.example.net")
        ProviderConfig.setAnimeBackendUrl(db, "https://anime.example.net")
        val controller = controller()
        controller.update(FIELD_ANIME_BACKEND, "off")

        assertTrue(controller.submit())
        assertNull(ProviderConfig.animeBackendUrl(db))
        assertEquals("https://mainapi.example.net", ProviderConfig.key(db, "mainapi_url"))
    }

    @Test
    fun leftover_import_copies_are_purged() {
        val work = File(dir, "work").also { it.mkdirs() }
        File(work, "legacy-import-1.db").writeText("x")
        File(work, "legacy-import-1.db-wal").writeText("x")
        File(work, "sokoul.db").writeText("keep")
        LegacyImport.purgeLeftovers(work)
        assertEquals(listOf("sokoul.db"), work.list()!!.toList())
    }

    @Test
    fun other_credential_forms_are_redacted_from_logs() {
        val cleaned = Redact.text("http://me:$SAMPLE_PASSWORD@host.test/x {\"password\":\"$SAMPLE_PASSWORD\"} http://host.test/me/$SAMPLE_PASSWORD/123")
        assertFalse(cleaned, cleaned.contains(SAMPLE_PASSWORD))
    }

    @Test
    fun nothing_entered_shows_an_error() = runBlocking {
        val c = controller()
        assertFalse(c.submit())
        assertEquals(Text.ErrorNothingEntered, c.errors[GENERAL_ERROR])
    }

    // --- Exposure

    @Test
    fun saved_key_shows_only_its_last_four_characters() {
        ProviderConfig.setKey(db, "tmdb", SAMPLE_KEY)
        val hint = controller().keyHint("tmdb")!!
        assertTrue(hint.endsWith("abcd"))
        assertFalse(hint.contains("sample"))
    }

    @Test
    fun logs_never_contain_keys_or_passwords() {
        val message = "GET https://x.test/3/configuration?api_key=$SAMPLE_KEY&a=1 player_api.php?username=me&password=$SAMPLE_PASSWORD Authorization: Bearer $SAMPLE_KEY"
        val cleaned = Redact.text(message)
        assertFalse(cleaned.contains(SAMPLE_KEY))
        assertFalse(cleaned.contains(SAMPLE_PASSWORD))
    }

    @Test
    fun tv_fields_only_enter_edit_mode_after_explicit_activation() {
        assertFalse(fieldIsEditableOnTv(tv = true, editing = false))
        assertTrue(fieldIsEditableOnTv(tv = true, editing = true))
        assertTrue(fieldIsEditableOnTv(tv = false, editing = false))
    }

    // --- Routing

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun first_launch_goes_from_logo_to_setup() = runComposeUiTest {
        // Manual clock: with auto-advance the test's idle wait would run straight through the logo's delay.
        mainClock.autoAdvance = false
        setContent { SokoulPcApp(deps()) }
        mainClock.advanceTimeByFrame()
        onNodeWithTag("splash").assertExists()
        onNodeWithTag("setup").assertDoesNotExist()
        mainClock.advanceTimeBy(SPLASH_MILLIS + 100)
        onNodeWithTag("setup").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun configure_later_shows_base_icons_only_and_is_remembered() = runComposeUiTest {
        setContent { SokoulPcApp(deps(), skipSplash = true) }
        onNodeWithTag("later").performClick()
        waitForIdle()
        onNodeWithTag("main-empty").assertExists()
        onNodeWithTag("menu-home").assertExists()
        assertEquals(SetupState.Deferred, StartupRoute.state(db))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun next_launch_after_deferring_skips_setup() = runComposeUiTest {
        StartupRoute.set(db, SetupState.Deferred)
        setContent { SokoulPcApp(deps()) }
        mainClock.advanceTimeBy(SPLASH_MILLIS + 100)
        waitForIdle()
        onNodeWithTag("main-empty").assertExists()
        onNodeWithTag("setup").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun navigation_bar_shows_the_base_icons() = runComposeUiTest {
        StartupRoute.set(db, SetupState.Deferred)
        setContent { SokoulPcApp(deps(), skipSplash = true) }
        onNodeWithTag("menu-settings").assertExists()
    }
}
