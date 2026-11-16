package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wikidata's and Wikipedia's answers, as their services send them, read by People (8.29). */
class PeopleTest {
    @Test
    fun findingSomeone() {
        val search = """{"searchinfo":{"search":"keanu reeves"},"search":[
          {"id":"Q43416","title":"Q43416","label":"Keanu Reeves","description":"Canadian actor (born 1964)","match":{"type":"label","language":"en","text":"Keanu Reeves"}},
          {"id":"Q115820","title":"Q115820","label":"Keanu Reeves filmography","description":"Wikimedia list article"},
          {"id":"Q43416","label":"Keanu Reeves"},
          {"id":"P345","label":"not an item"}
        ],"success":1}"""
        val found = People.parseSearch(search)
        assertEquals(listOf("Q43416", "Q115820"), found.map { it.id })
        assertTrue(People.whoQuery(found.map { it.id }).contains("VALUES ?p { wd:Q43416 wd:Q115820 }"))
        val who = """{"head":{"vars":["p","image"]},"results":{"bindings":[
          {"p":{"type":"uri","value":"http://www.wikidata.org/entity/Q43416"},
           "image":{"type":"uri","value":"http://commons.wikimedia.org/wiki/Special:FilePath/Reeves%20in%202019.jpg"}}]}}"""
        val people = People.keepPeople(found, who)
        assertEquals(listOf("Keanu Reeves"), people.map { it.name })
        assertEquals("https://commons.wikimedia.org/wiki/Special:FilePath/Reeves%20in%202019.jpg?width=360", people[0].photo)
        assertEquals("Q43416", People.pick(people, "keanu  REEVES")?.id)
        assertNull(People.pick(emptyList(), "x"))
        // An Arabic name is asked for in Arabic.
        assertTrue(People.searchUrl("عادل إمام").contains("language=ar"))
        assertTrue(People.searchUrl("Adel Emam").contains("language=en"))
    }

    @Test
    fun aPersonsPage() {
        val about = """{"head":{"vars":[]},"results":{"bindings":[{
          "pLabel":{"xml:lang":"en","type":"literal","value":"Keanu Reeves"},
          "pDescription":{"xml:lang":"en","type":"literal","value":"canadian actor (born 1964)"},
          "born":{"datatype":"http://www.w3.org/2001/XMLSchema#dateTime","type":"literal","value":"1964-09-02T00:00:00Z"},
          "placeLabel":{"xml:lang":"en","type":"literal","value":"Beirut"},
          "image":{"type":"uri","value":"http://commons.wikimedia.org/wiki/Special:FilePath/Keanu.jpg"},
          "enwiki":{"type":"uri","value":"https://en.wikipedia.org/wiki/Keanu_Reeves"}}]}}"""
        val a = People.parseAbout(about)!!
        assertEquals("Canadian actor (born 1964)", a.line)
        assertEquals("1964-09-02", a.born)
        assertEquals("", a.died)
        assertEquals("Beirut", a.place)
        assertEquals("https://en.wikipedia.org/api/rest_v1/page/summary/Keanu_Reeves", People.summaryUrl(a.wiki))
        assertEquals("Keanu Charles Reeves is a Canadian actor.", People.parseSummary("""{"title":"Keanu Reeves","extract":"Keanu Charles Reeves is a Canadian actor. "}"""))
        // A place without a name in English or Arabic comes as its number: left out.
        assertEquals("", People.parseAbout(about.replace("Beirut", "Q123456"))!!.place)
        assertNull(People.parseAbout("""{"results":{"bindings":[]}}"""))
    }

    @Test
    fun theirFilmsAndSeries() {
        fun row(imdb: String, en: String, date: String, kinds: String, ar: String = "", eps: String = "") =
            """{"w":{"type":"uri","value":"http://www.wikidata.org/entity/Q1"},"imdb":{"type":"literal","value":"$imdb"},
               "en":{"xml:lang":"en","type":"literal","value":"$en"},""" +
                (if (ar.isNotEmpty()) """"ar":{"xml:lang":"ar","type":"literal","value":"$ar"},""" else "") +
                (if (eps.isNotEmpty()) """"episodes":{"type":"literal","value":"$eps"},""" else "") +
                """"date":{"type":"literal","value":"$date"},"kinds":{"type":"literal","value":"$kinds"}}"""
        val text = """{"results":{"bindings":[
          ${row("tt0133093", "The Matrix", "1999-03-31T00:00:00Z", "Q11424", ar = "ماتريكس")},
          ${row("tt2911666", "John Wick", "2014-10-24T00:00:00Z", "Q11424,Q24869")},
          ${row("tt0101393", "Swedish Dicks", "2016-01-01T00:00:00Z", "Q5398426")},
          ${row("tt9999999", "Some show", "", "Q15416", eps = "10")},
          ${row("tt5555555", "An episode", "2010-01-01T00:00:00Z", "Q21191270")},
          ${row("tt7777777", "Unknown kind with episodes", "2012-01-01T00:00:00Z", "Q1", eps = "8")},
          {"imdb":{"type":"literal","value":"tt15398776"},"mul":{"xml:lang":"mul","type":"literal","value":"Oppenheimer"},
           "ar":{"xml:lang":"ar","type":"literal","value":"أوبنهايمر"},"date":{"type":"literal","value":"2023-07-21T00:00:00Z"},"kinds":{"type":"literal","value":"Q11424"}}
        ]}}"""
        val (films, series) = People.parseCredits(text)
        // A name kept as "mul" (no English one) is used as the English.
        assertEquals(listOf("Oppenheimer", "John Wick", "The Matrix"), films.map { it.name })
        assertEquals("أوبنهايمر", films[0].altName)
        assertEquals("ماتريكس", films[2].altName)
        assertEquals(1999, films[2].year)
        assertEquals("https://images.metahub.space/poster/small/tt0133093/img", films[2].poster)
        assertEquals(listOf("Swedish Dicks", "Unknown kind with episodes", "Some show"), series.map { it.name })
        assertTrue(People.creditsQuery("Q43416").contains("wdt:P161 wd:Q43416"))
    }

    @Test
    fun theirTitlesInYourPlaylist() {
        val library = listOf(
            Channel("m1", "EN - The Matrix (1999) 4K", "Action", "http://x/1.mkv", kind = Kind.MOVIE),
            Channel("m2", "John Wick", "Action", "http://x/2.mkv", kind = Kind.MOVIE),
            Channel("m3", "Dune (1984)", "Sci-Fi", "http://x/3.mkv", kind = Kind.MOVIE),
            Channel("s1", "|AR| The Matrix", "Series", "", kind = Kind.SERIES),
            Channel("m4", "AR - الإرهاب والكباب (1992)", "Arabic", "http://x/4.mkv", kind = Kind.MOVIE),
        )
        val theirs = listOf(
            People.Title("movie", "The Matrix", 1999, "tt0133093", ""),
            People.Title("movie", "John Wick", 2014, "tt2911666", ""),
            People.Title("movie", "Dune", 2021, "tt1160419", ""),
            People.Title("movie", "Terrorism and Kebab", 1992, "tt0105525", "", altName = "الإرهاب والكباب"),
        )
        val found = People.inLibrary(theirs, library).associate { it.first.imdb to it.second.id }
        // The film, not the series of the same name; a name with no year counts; another year's remake
        // doesn't; an Arabic name finds the Arabic one.
        assertEquals(mapOf("tt0133093" to "m1", "tt2911666" to "m2", "tt0105525" to "m4"), found)
    }

    @Test
    fun namesTidiedTheQuickWay() {
        // 8.30: plain English names are tidied by hand; the same words as before.
        assertEquals("tom and jerry", TitleMatch.norm(" Tom & Jerry "))
        assertEquals("and co", TitleMatch.norm("&Co"))
        assertEquals("r and and b", TitleMatch.norm("R&&B"))
        assertEquals("spider man no way home 2021", TitleMatch.norm("Spider-Man: No Way Home (2021)"))
        assertEquals("amelie", TitleMatch.norm("Amélie"))
        assertEquals("", TitleMatch.norm(" -:() "))
        // Tags at the end go, a word that only ends like one stays.
        assertEquals("The Matrix" to 1999, TitleMatch.clean("EN - The Matrix (1999) 4K", 2026))
        assertEquals("Film." to null, TitleMatch.clean("Film.HD", 2026))
        assertEquals("Shadow4k" to null, TitleMatch.clean("Shadow4k", 2026))
        // The playlist's names are made once and give the same matches.
        val lib = listOf(Channel("m1", "EN - The Matrix (1999) 4K", "A", "u", kind = Kind.MOVIE), Channel("l1", "The Matrix", "Live", "u", kind = Kind.LIVE))
        val names = People.Names(lib)
        assertEquals(listOf("m1"), People.inLibrary(listOf(People.Title("movie", "The Matrix", 1999, "tt0133093", "")), names).map { it.second.id })
    }

    @Test
    fun aProvidersCastLine() {
        assertEquals(listOf("Keanu Reeves", "Laurence Fishburne", "Carrie-Anne Moss"), People.castNames("Keanu Reeves, Laurence Fishburne ,Carrie-Anne Moss,"))
        assertEquals(listOf("عادل إمام", "يسرا"), People.castNames("عادل إمام، يسرا"))
        assertEquals(2, People.castNames("A b, C d, E f", max = 2).size)
    }
}
