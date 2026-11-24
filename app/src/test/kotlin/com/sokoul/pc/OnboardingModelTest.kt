package com.sokoul.pc

import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.Playlist
import com.sokoul.desktop.data.PlaylistType
import com.sokoul.desktop.data.Settings
import com.sokoul.iptv.data.debrid.RealDebridOAuth
import com.sokoul.pc.data.CredentialValidator
import com.sokoul.pc.startup.SetupState
import com.sokoul.pc.startup.StartupRoute
import com.sokoul.pc.startup.onboarding.OnboardingModel
import com.sokoul.pc.startup.onboarding.OnboardingStep
import com.sokoul.pc.startup.onboarding.SourceKind
import com.sokoul.pc.startup.onboarding.SourceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class OnboardingModelTest {
    private fun db() = DesktopDb(Files.createTempDirectory("sokoul-onboarding").toFile().resolve("t.db"))

    private fun model(db: DesktopDb) = OnboardingModel(
        db, CredentialValidator(OkHttpClient()), RealDebridOAuth(OkHttpClient()), CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `server addresses are completed and wrong ones refused`() {
        assertEquals("http://exemple.tv:8080", OnboardingModel.normalizeServer(" exemple.tv:8080/ "))
        assertEquals("https://exemple.tv", OnboardingModel.normalizeServer("https://exemple.tv"))
        assertEquals("http://192.168.1.20:25565", OnboardingModel.normalizeServer("192.168.1.20", defaultPort = 25565))
        assertEquals("http://192.168.1.20:3000", OnboardingModel.normalizeServer("192.168.1.20:3000", defaultPort = 25565))
        assertNull(OnboardingModel.normalizeServer("mon serveur"))
        assertNull(OnboardingModel.normalizeServer("exemple"))
        assertNull(OnboardingModel.normalizeServer(""))
    }

    @Test
    fun `the step is kept so a new launch resumes where the person left`() {
        val db = db()
        model(db).apply { next(); next() }
        assertEquals(OnboardingStep.Preferences, model(db).step)
    }

    @Test
    fun `back from the first step does nothing`() {
        val m = model(db())
        assertFalse(m.back())
        assertEquals(OnboardingStep.Welcome, m.step)
    }

    @Test
    fun `a saved playlist shows as ready and finishing clears the step`() {
        val db = db()
        val id = db.upsertPlaylist(Playlist(0, "IPTV", PlaylistType.XTREAM, "http://exemple.tv", "u", "p", null, null))
        db.putSetting(Settings.ACTIVE_PLAYLIST, id.toString())
        val m = model(db)
        assertEquals(SourceStatus.Ready, m.sources[SourceKind.Iptv]?.status)
        m.goTo(OnboardingStep.Ready)
        m.finish()
        assertEquals(SetupState.Done, StartupRoute.state(db))
        assertNull(db.setting(OnboardingModel.STEP_SETTING))
    }

    @Test
    fun `incomplete or malformed IPTV input is refused before any network call`() = runBlocking {
        val m = model(db())
        assertFalse(m.saveXtream("pas une adresse", "u", "p"))
        assertEquals(SourceStatus.Problem, m.sources[SourceKind.Iptv]?.status)
        assertFalse(m.saveXtream("exemple.tv:8080", "", "p"))
        assertFalse(m.saveM3u("ftp://exemple.tv/list.m3u"))
    }

    @Test
    fun `later opens the catalogue without asking again`() {
        val db = db()
        model(db).later()
        assertEquals(SetupState.Deferred, StartupRoute.state(db))
    }
}
