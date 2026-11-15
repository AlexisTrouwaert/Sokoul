package com.sokoul.pc

import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.pc.data.anime.AniParse
import com.sokoul.pc.data.anime.AniTitle
import com.sokoul.pc.data.anime.AnimeCategories
import com.sokoul.pc.data.anime.AnimeMatch
import com.sokoul.pc.data.anime.AnimeSeasons
import com.sokoul.pc.data.anime.CategoryGroup
import com.sokoul.pc.data.anime.MediaKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AnimeTest {
    private fun t(english: String?, romaji: String? = null) = AniTitle(1, MediaKind.ANIME, english, romaji, null, null, null, "TV", null, null, null, null, null, null, null, emptyList(), emptyList(), null, null, null, null, null)
    private fun item(title: String, original: String? = null, kind: String = "tv", genres: List<Int> = listOf(16)) =
        TmdbItem(1, title, original, null, null, null, null, null, genres, mediaType = kind)

    @Test
    fun an_answer_is_read_and_nothing_adult_comes_through() {
        val json = Json.parseToJsonElement(
            """[{"id":1,"title":{"romaji":"Sousou no Frieren","english":"Frieren"},"coverImage":{"extraLarge":"https://c/1"},"format":"TV","averageScore":89,
               "genres":["Adventure","Hentai"],"tags":[{"name":"Time Skip","rank":90},{"name":"Spoiled","rank":90,"isMediaSpoiler":true},{"name":"Rare","rank":10}],
               "description":"A journey.<br><br>(Source: Crunchyroll)","nextAiringEpisode":{"airingAt":100,"episode":5},"studios":{"nodes":[{"name":"Madhouse"}]}},
               {"id":2,"isAdult":true,"title":{"romaji":"No"}}]""",
        )
        val list = AniParse.titles(json, MediaKind.ANIME)
        assertEquals(1, list.size)
        val f = list[0]
        assertEquals("Frieren", f.title)
        assertEquals(listOf("Adventure"), f.genres)
        assertEquals("no spoiler and no weak tag", listOf("Time Skip"), f.tags)
        assertEquals("A journey.", f.description)
        assertEquals(100_000L, f.nextAt)
        assertEquals("Madhouse", f.studio)
    }

    @Test
    fun a_romaji_title_stands_in_for_a_missing_english_one() {
        assertEquals("Romaji", t(null, "Romaji").title)
    }

    @Test
    fun seasons_follow_the_calendar() {
        assertEquals("FALL" to 2026, AnimeSeasons.of(LocalDate.of(2026, 11, 3)))
        assertEquals("next season crosses the year", "WINTER" to 2027, AnimeSeasons.next(LocalDate.of(2026, 11, 3)))
        assertEquals("WINTER" to 2026, AnimeSeasons.of(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun a_category_is_found_without_its_accents_and_in_both_languages() {
        val c = com.sokoul.pc.data.anime.AniCategory("Swordplay", CategoryGroup.Action, false)
        assertTrue(AnimeCategories.matches(c, "sword"))
        assertFalse(AnimeCategories.matches(c, "tennis"))
        assertTrue(AnimeCategories.matches(c, ""))
    }

    @Test
    fun what_should_not_be_offered_is_not() {
        val tags = AniParse.tags(Json.parseToJsonElement("""[{"name":"Isekai","category":"Theme-Fantasy"},{"name":"Incest","category":"Cast-Traits"},{"name":"Adult","category":"X","isAdult":true},{"name":"CGI","category":"Technical"}]"""))
        assertEquals(listOf("Isekai"), tags.map { it.name })
    }

    @Test
    fun every_group_of_the_database_has_its_place() {
        assertEquals(CategoryGroup.Audience, AnimeCategories.groupOf("Demographic"))
        assertEquals(CategoryGroup.Characters, AnimeCategories.groupOf("Cast-Main Cast"))
        assertEquals(CategoryGroup.Worlds, AnimeCategories.groupOf("Setting-Time"))
        assertEquals(CategoryGroup.SciFi, AnimeCategories.groupOf("Theme-Sci-Fi-Mecha"))
        assertEquals(CategoryGroup.Sports, AnimeCategories.groupOf("Theme-Game-Sport"))
        assertEquals("what is unknown is still shown", CategoryGroup.Society, AnimeCategories.groupOf("Something New"))
    }

    @Test
    fun the_same_work_is_found_in_the_catalog_and_a_live_action_one_is_not() {
        val results = listOf(item("One Piece", genres = listOf(10759)), item("One Piece", "ワンピース"))
        assertNotNull(AnimeMatch.pick(results, "tv", t("One Piece")))
        assertNull("live action only", AnimeMatch.pick(listOf(item("One Piece", genres = listOf(10759))), "tv", t("One Piece")))
    }

    @Test
    fun a_later_season_opens_its_series() {
        assertEquals("Haikyu!!", AnimeMatch.pick(listOf(item("Haikyu!!")), "tv", t("Haikyu!! TO THE TOP"))?.title)
        assertNull("a different work", AnimeMatch.pick(listOf(item("Bleach")), "tv", t("Naruto")))
    }

    @Test
    fun html_and_sources_leave_the_description() {
        assertEquals("One two", AniParse.cleanText("<i>One</i><br>two (Source: X)"))
        assertNull(AniParse.cleanText("  "))
    }

    @Test
    fun a_theme_knows_its_episodes() {
        val t = com.sokoul.pc.data.anime.AnimeTheme("OP1", "Song", null, "1-25, 27", "https://v", null)
        assertTrue(t.covers(25))
        assertTrue(t.covers(27))
        assertFalse(t.covers(26))
        assertTrue(t.isOpening)
    }

    @Test
    fun the_chapters_of_an_episode_are_read_from_each_wiki() {
        val p = com.sokoul.pc.data.anime.FanParse
        assertEquals("a page number is no chapter", listOf(1, 2), p.chapters("|adapted from = [[Chapter 2]] (p. 2 - 3)<br>[[Chapter 1]]"))
        assertEquals(listOf(1), p.chapters("|chapters = [[Chapter 1|1]]"))
        assertEquals(listOf(1), p.chapters("|chapters=1"))
        assertEquals(listOf(12, 13, 14), p.chapters("| chapter = 12-14"))
        assertEquals("nothing recorded", emptyList<Int>(), p.chapters("the chapters of the manga differ"))
        assertEquals(12 to 14, p.span(listOf(12, 13, 14)))
    }

    @Test
    fun filler_episodes_are_read_from_the_list() {
        val html = """<tr class="manga_canon odd"><td class="Number">1</td></tr><tr class="filler even"> <td class="Number">2</td></tr><tr class="mixed_canon/filler odd"><td class="Number">3</td></tr>"""
        val k = com.sokoul.pc.data.anime.FanParse.fillers(html)
        assertEquals(com.sokoul.pc.data.anime.EpisodeKind.Canon, k[1])
        assertEquals(com.sokoul.pc.data.anime.EpisodeKind.Filler, k[2])
        assertEquals(com.sokoul.pc.data.anime.EpisodeKind.Mixed, k[3])
        assertEquals("naruto-shippuden", com.sokoul.pc.data.anime.FanParse.slug("Naruto: Shippuden"))
    }

    @Test
    fun one_chapter_per_number_in_the_language_wanted_in_reading_order() {
        fun c(n: String, l: String) = com.sokoul.pc.data.anime.MangaChapter(n + l, n, null, null, l, 10, null)
        val best = com.sokoul.pc.data.anime.FanParse.bestPerChapter(listOf(c("10", "en"), c("2", "en"), c("2", "fr"), c("1.5", "en")), listOf("fr", "en"))
        assertEquals(listOf("1.5en", "2fr", "10en"), best.map { it.id })
    }

    @Test
    fun the_reader_turns_a_book_the_right_way() {
        val r = com.sokoul.pc.data.anime.ReaderLogic
        val m = com.sokoul.pc.data.anime.ReaderMode.Double
        assertEquals("the cover stands alone", listOf(0), r.shown(m, 0, 10, true))
        assertEquals("right to left, the later page is on the left", listOf(4, 3), r.shown(m, 3, 10, true))
        assertEquals(listOf(3, 4), r.shown(m, 3, 10, false))
        assertEquals("the last page alone", listOf(9), r.shown(m, 9, 10, true))
        assertEquals(2, r.step(m, 3))
        assertEquals(1, r.step(m, 0))
        assertEquals(com.sokoul.pc.data.anime.ReaderMode.Strip, r.nextMode(m))
    }

    @Test
    fun a_comic_file_reads_in_natural_order_without_its_extras() {
        val names = listOf("__MACOSX/x.jpg", "ch/10.jpg", "ch/2.jpg", "ch/note.txt", "ch/1.PNG")
        assertEquals(listOf("ch/1.PNG", "ch/2.jpg", "ch/10.jpg"), com.sokoul.pc.data.anime.ReaderLogic.imagesInOrder(names))
    }

    @Test
    fun the_addresses_of_a_chapter_are_built_from_the_answer() {
        val json = Json.parseToJsonElement("""{"baseUrl":"https://m.test","chapter":{"hash":"abc","data":["1.jpg","2.jpg"]}}""")
        assertEquals(listOf("https://m.test/data/abc/1.jpg", "https://m.test/data/abc/2.jpg"), com.sokoul.pc.data.anime.FanParse.pages(json))
        assertEquals("no plain address", emptyList<String>(), com.sokoul.pc.data.anime.FanParse.pages(Json.parseToJsonElement("""{"baseUrl":"http://m.test","chapter":{"hash":"a","data":["1.jpg"]}}""")))
    }

    @Test
    fun a_name_finds_that_work_and_not_a_synonym_or_a_spin_off() {
        val onigiri = t(null, "Onigiri").copy(id = 1, popularity = 500)
        val slayer = t("Demon Slayer: Kimetsu no Yaiba").copy(id = 2, popularity = 900000)
        assertEquals(2, AnimeMatch.best(listOf(onigiri, slayer), "Demon Slayer")?.id)
        val naruto = t("Naruto").copy(id = 3, popularity = 100)
        val shippuden = t("Naruto: Shippuden").copy(id = 4, popularity = 900)
        assertEquals("the exact name beats the most popular", 3, AnimeMatch.best(listOf(shippuden, naruto), "Naruto")?.id)
    }

    @Test
    fun the_book_of_a_work_is_its_original_manga() {
        fun book(id: Int, format: String, pop: Int) = t("B").copy(id = id, kind = MediaKind.MANGA, format = format, popularity = pop)
        val relations = listOf(
            com.sokoul.pc.data.anime.AniRelation("OTHER", book(1, "MANGA", 9000)),
            com.sokoul.pc.data.anime.AniRelation("SOURCE", book(2, "NOVEL", 900)),
            com.sokoul.pc.data.anime.AniRelation("SOURCE", book(3, "MANGA", 500)),
        )
        assertEquals(3, AnimeMatch.bookOf(relations)?.id)
    }

    @Test
    fun a_webtoon_page_is_told_by_its_shape() {
        val r = com.sokoul.pc.data.anime.ReaderLogic
        assertTrue("webtoon slice", r.isStripPage(720, 2400))
        assertFalse("printed page", r.isStripPage(1100, 1600))
        assertFalse("double page", r.isStripPage(2200, 1600))
        assertFalse(r.isStripPage(0, 100))
    }

    @Test
    fun a_book_leads_to_its_series_not_a_special() {
        // One Piece's book on AniList: a special listed first, the series fourth.
        fun anime(id: Int, format: String, pop: Int) = t("One Piece").copy(id = id, format = format, popularity = pop)
        val adaptations = listOf(anime(15323, "SPECIAL", 40000), anime(2107, "MOVIE", 60000), anime(21, "TV", 600000), anime(99302, "SPECIAL", 20000))
        assertEquals(21, AnimeMatch.mainAdaptation(adaptations)?.id)
        assertEquals("no series: the most popular", 2107, AnimeMatch.mainAdaptation(adaptations.filter { it.format != "TV" })?.id)
        assertEquals(null, AnimeMatch.mainAdaptation(emptyList()))
    }

    @Test
    fun a_series_that_is_not_the_one_wanted_is_never_opened() {
        val p = com.sokoul.pc.data.anime.FanParse
        val json = Json.parseToJsonElement("""{"data":[{"id":"wrong","attributes":{"title":{"en":"Berserk of Gluttony"},"altTitles":[],"links":{}}},{"id":"right","attributes":{"title":{"en":"Other"},"altTitles":[{"ja-ro":"Berserk"}],"links":{}}},{"id":"linked","attributes":{"title":{"en":"X"},"altTitles":[],"links":{"al":"7"}}}]}""")
        assertEquals("linked", p.mangadexId(json, 7, listOf("Berserk")))
        assertEquals("right", p.mangadexId(json, 8, listOf("Berserk")))
        assertNull(p.mangadexId(json, 8, listOf("Vagabond")))
    }

    @Test
    fun an_opening_belongs_to_its_season() {
        val p = com.sokoul.pc.data.anime.FanParse
        assertEquals(0 to 24, p.place(listOf(24, 23), 24))
        assertEquals(1 to 6, p.place(listOf(24, 23), 30))
        assertEquals("past the last known season", 1 to 40, p.place(listOf(24, 23), 64))
        assertEquals("1-13", p.merged(listOf("1-2", "3-13")))
        assertEquals("1-5, 8", p.merged(listOf("8", "1-5")))
        assertEquals("64, 79–80", p.chapterRuns(listOf(80, 64, 79)))
    }

    @Test
    fun a_wiki_that_names_its_chapters_is_read_too() {
        val p = com.sokoul.pc.data.anime.FanParse
        assertEquals(listOf(2), p.chapters("| Manga chapters     = [[That Day (Chapter 2)|That Day]]"))
        assertEquals("up to a page is no chapter", listOf(2, 3, 4), p.chapters("|chapters        = [[Chapter 2]], [[Chapter 3]], [[Chapter 4]] (up to page 7)"))
    }
}
