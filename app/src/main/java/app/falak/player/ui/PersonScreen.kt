package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.BuildConfig
import app.falak.player.data.AddonItem
import app.falak.player.data.Addons
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import app.falak.player.data.People
import app.falak.player.data.Trakt
import app.falak.player.data.mine
import app.falak.player.data.progressKey
import app.falak.player.repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * An actor's page (8.29; the owner: "click on the actors to see what shows they have"): their photo
 * and story, then what of theirs is in your playlist, then all their films and series (Wikidata and
 * Wikipedia, see People: free, nothing to set up).
 * A title in the playlist opens its page there; the others open with your add-ons, when you have
 * add-ons that bring links (GitHub version).
 */
data class PersonPage(
    val person: People.Person,
    val about: People.About?,
    val films: List<People.Title>,
    val series: List<People.Title>,
    /** Theirs that the playlist has. */
    val yours: List<Pair<People.Title, Channel>>,
)

/** Who the page is about: found, not found, or no answer (offline). */
private data class Who(val person: People.Person? = null, val notFound: Boolean = false, val failed: Boolean = false)

/** Their films and series, or no answer. */
private data class Works(val films: List<People.Title>, val series: List<People.Title>, val failed: Boolean = false)

/** Screen tests: the page as if Wikidata had answered. */
object PersonForTest {
    @Volatile var page: PersonPage? = null
}

@Composable
fun PersonScreen(vm: LibraryViewModel, state: AppState) {
    val open = vm.person
    if (open == null) {
        LaunchedEffect(Unit) { vm.screen = Screen.LIBRARY }
        return
    }
    val context = LocalContext.current
    val repo = context.repo
    BackHandler { vm.closePerson() }
    var tries by remember { mutableIntStateOf(0) }
    val index = vm.index
    val test = PersonForTest.page
    // In stages (8.30): who it is (with their photo), then their story and their films as each
    // comes, then what of theirs your playlist has; each shown as soon as it's here.
    // (A state kept from the actor shown before is set back first: these run again for each one.)
    val who by produceState(Who(test?.person ?: open.found), open, tries) {
        val known = test?.person ?: open.found
        value = Who(known)
        if (known != null) return@produceState
        val p = withContext(Dispatchers.IO) { repo.people.find(open.name) }
        value = when {
            p == null -> Who(failed = true)
            p === app.falak.player.data.PeopleClient.NOT_FOUND -> Who(notFound = true)
            else -> Who(p)
        }
    }
    val person = who.person
    val about by produceState(test?.about, person) {
        value = test?.about
        if (test != null || person == null) return@produceState
        value = withContext(Dispatchers.IO) { repo.people.about(person.id) }
    }
    val works by produceState(test?.let { Works(it.films, it.series) }, person, tries) {
        value = test?.let { Works(it.films, it.series) }
        if (test != null || person == null) return@produceState
        val c = withContext(Dispatchers.IO) { repo.people.credits(person.id) }
        value = if (c == null) Works(emptyList(), emptyList(), failed = true) else Works(c.first, c.second)
    }
    val yoursFound by produceState(test?.yours, works, index) {
        value = test?.yours
        val w = works
        if (test != null || w == null || w.failed) return@produceState
        value = withContext(Dispatchers.Default) {
            People.inLibrary(w.films + w.series, repo.people.names(index) { index.movies + index.series })
        }
    }
    val page = person?.let { p ->
        works?.takeIf { !it.failed }?.let { w -> PersonPage(p, about, w.films, w.series, yoursFound.orEmpty()) }
    }
    // Titles that aren't in the playlist open with the add-ons that bring links.
    val links = state.settings.addons
    val m = vm.addons
    LaunchedEffect(links) { if (BuildConfig.ADDONS || Addons.enabledForTest) m.load(links) }
    val canOpenOthers = (BuildConfig.ADDONS || Addons.enabledForTest) && m.addons.any { Addons.hasLinks(it) }

    val tv = isTv()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item(key = "head") {
            Box(Modifier.padding(horizontal = pageMargin())) {
                PageHeader(open.name, onBack = { vm.closePerson() })
            }
        }
        when {
            who.notFound -> item(key = "none") { PersonNote("Nothing is known about “${open.name}” yet.") }
            who.failed || works?.failed == true -> item(key = "fail") {
                Column(Modifier.padding(horizontal = pageMargin())) {
                    PersonNote("Couldn't get their page. Check the internet and try again.", padded = false)
                    GhostButton("Try again") { tries++ }
                }
            }
            person == null -> item(key = "wait") {
                BusyLine(Modifier.padding(horizontal = pageMargin(), vertical = 24.dp).widthIn(max = 420.dp).fillMaxWidth())
            }
            page == null -> {
                item(key = "about") { PersonHead(person, about, tv) }
                item(key = "wait") {
                    BusyLine(Modifier.padding(horizontal = pageMargin(), vertical = 16.dp).widthIn(max = 420.dp).fillMaxWidth())
                }
            }
            else -> {
                item(key = "about") { PersonHead(person, about, tv) }
                val firstKey = when {
                    page.yours.isNotEmpty() -> "yours"
                    page.films.isNotEmpty() -> "films"
                    else -> "series"
                }
                if (page.yours.isNotEmpty()) {
                    item(key = "yours") {
                        Box(Modifier.padding(horizontal = pageMargin() - 4.dp)) {
                            Shelf("In your playlist", count = page.yours.size.toString()) {
                                items(page.yours, key = { "y:" + it.second.id }) { (t, ch) ->
                                    PosterCard(
                                        ch, state.progress[progressKey(state.mine(vm.playlist?.id.orEmpty()), ch.id)],
                                        focusMe = firstKey == "yours" && ch.id == page.yours.first().second.id,
                                        onClick = {
                                            if (ch.kind == Kind.SERIES) vm.openSeries(ch) else vm.openDetails(ch)
                                            vm.titleFromPerson = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                val inPlaylist = page.yours.map { it.first.imdb }.toSet()
                for ((key, title, list) in listOf(Triple("films", "Films", page.films), Triple("series", "Series", page.series))) {
                    if (list.isEmpty()) continue
                    item(key = key) {
                        Box(Modifier.padding(horizontal = pageMargin() - 4.dp)) {
                            Shelf(title, count = list.size.toString()) {
                                items(list.take(60), key = { key + ":" + it.imdb }) { t ->
                                    val yours = page.yours.firstOrNull { it.first.imdb == t.imdb }?.second
                                    PosterCard(
                                        t.asCard(),
                                        null,
                                        focusMe = firstKey == key && t.imdb == list.first().imdb,
                                        onClick = {
                                            when {
                                                yours != null -> {
                                                    if (yours.kind == Kind.SERIES) vm.openSeries(yours) else vm.openDetails(yours)
                                                    vm.titleFromPerson = true
                                                }
                                                canOpenOthers -> {
                                                    m.only = null
                                                    m.returnTo = Screen.PERSON
                                                    m.openItem(AddonItem(Addons.STANDARD_LISTS, t.imdb, t.type, t.name, t.poster, t.year?.toString().orEmpty()))
                                                    vm.screen = Screen.ADDONS
                                                }
                                                else -> android.widget.Toast.makeText(context, "“${t.name}” isn't in your playlist", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (page.films.isEmpty() && page.series.isEmpty()) {
                    item(key = "nothing") { PersonNote("No films or series of theirs are listed yet.") }
                } else if (!canOpenOthers) {
                    item(key = "hint") {
                        PersonNote(
                            if (inPlaylist.isEmpty()) "None of these is in your playlist." else "Titles in your playlist open from here.",
                        )
                    }
                }
            }
        }
    }
}

/** One of their titles as a card ("1999" under it in the sidebar look). */
private fun People.Title.asCard() = Channel(
    id = "person:$type:$imdb",
    name = name,
    group = listOfNotNull(year?.toString(), character.ifBlank { null }).joinToString(" · "),
    url = "",
    logo = poster,
    kind = if (type == "series") Kind.SERIES else Kind.MOVIE,
)

@Composable
private fun PersonHead(person: People.Person, a: People.About?, tv: Boolean) {
    val photo = a?.photo?.ifBlank { null } ?: person.photo
    Row(Modifier.padding(horizontal = pageMargin()).padding(top = 4.dp, bottom = 18.dp).widthIn(max = 900.dp)) {
        Box(
            Modifier.size(if (tv) 132.dp else 104.dp).clip(CircleShape).background(Ink3),
            contentAlignment = Alignment.Center,
        ) {
            LogoOr(photo, null, ContentScale.Crop, Modifier.fillMaxSize()) { Initials(person.name, 30.sp) }
        }
        Spacer(Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            val line = listOfNotNull(
                a?.line?.ifBlank { null },
                a?.born?.ifBlank { null }?.let { b ->
                    "Born " + b.take(4) + (a.place.ifBlank { null }?.let { " in $it" } ?: "")
                },
                a?.died?.ifBlank { null }?.let { "Died " + it.take(4) },
            ).joinToString("  ·  ")
            if (line.isNotEmpty()) Text(line, fontFamily = Sans, fontSize = 14.sp, color = Paper, modifier = Modifier.padding(bottom = 6.dp))
            val bio = a?.bio.orEmpty()
            if (bio.isNotEmpty()) {
                Text(bio, fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, color = Muted, maxLines = if (tv) 5 else 8, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PersonNote(text: String, padded: Boolean = true) {
    Text(
        text,
        fontFamily = Sans,
        fontSize = 14.sp,
        color = Muted,
        modifier = if (padded) Modifier.padding(horizontal = pageMargin(), vertical = 12.dp) else Modifier.padding(vertical = 12.dp),
    )
}

/**
 * The cast of a film or series (8.29): each name opens their page. Plain text in kids mode.
 */
@Composable
fun CastNames(vm: LibraryViewModel, label: String, names: List<String>) {
    if (names.isEmpty()) return
    // The first actors are looked up while the page is read (8.30): tapping one opens at once.
    val context = LocalContext.current
    val repo = context.repo
    val index = vm.index
    LaunchedEffect(names, index) {
        // (Not on mobile data: a few hundred kB each time.)
        if (!vm.kidsActive && !onMobileData(context)) {
            kotlinx.coroutines.delay(800) // (after the page's own pictures and details)
            repo.people.warm(names, index) { index.movies + index.series }
        }
    }
    if (vm.kidsActive) {
        Credit(label, names.joinToString(", "))
        return
    }
    Column(Modifier.padding(bottom = 6.dp)) {
        Text(label, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Paper, modifier = Modifier.padding(bottom = 6.dp))
        ChipFlow {
            names.forEach { n -> TagChip(n, false) { vm.openPerson(n) } }
        }
    }
}

/**
 * "IMDb 8.7  ·  Trakt 87%" for a film or series (8.29), asked for once its IMDb number is known
 * ([imdb] runs off the main thread: a provider's title is looked up by name, see TitleMatch).
 * Null while asking, or when neither has it.
 */
@Composable
fun rememberScores(key: Any?, type: String, imdb: suspend () -> String?): String? {
    val repo = LocalContext.current.repo
    val v by produceState<String?>(null, key) {
        if (key == null || app.falak.player.data.PeopleClient.testing) return@produceState
        value = withContext(Dispatchers.IO) {
            val id = runCatching { imdb() }.getOrNull() ?: return@withContext null
            coroutineScope {
                val i = async { repo.imdbRating(id, type) }
                val t = async { repo.trakt.score(id, type) }
                scoresLine(i.await(), t.await())
            }
        }
    }
    return v
}

fun scoresLine(imdb: String?, trakt: Trakt.Score?): String? =
    listOfNotNull(imdb?.let { "IMDb $it" }, trakt?.let { "Trakt ${it.percent}%" }).joinToString("  ·  ").ifEmpty { null }

/**
 * Search by an actor's name (8.29): the actors and directors found for what is typed, as a row with
 * the search results. Empty while there's nothing.
 */
@Composable
fun rememberPeopleFound(query: String): List<People.Person> {
    val repo = LocalContext.current.repo
    val q = query.trim()
    val found by produceState(emptyList<People.Person>(), q) {
        value = emptyList()
        if (q.length < 3) return@produceState
        kotlinx.coroutines.delay(450) // (while typing, only the last word is asked for)
        value = withContext(Dispatchers.IO) { repo.people.search(q) }.orEmpty()
            // Only people whose name has what was typed (the search also answers with near names).
            .filter { p -> q.split(' ').filter { it.isNotBlank() }.all { w -> app.falak.player.data.TitleMatch.norm(p.name).contains(app.falak.player.data.TitleMatch.norm(w)) } }
    }
    return found
}

@Composable
fun PeopleShelf(vm: LibraryViewModel, people: List<People.Person>) {
    if (people.isEmpty()) return
    Box(Modifier.padding(horizontal = pageMargin() - 4.dp)) {
        Shelf("People", count = people.size.toString()) {
            items(people, key = { "p:" + it.id }) { p -> PersonCard(p) { vm.openPerson(p.name, p) } }
        }
    }
}

@Composable
private fun PersonCard(p: People.Person, onClick: () -> Unit) {
    val tv = isTv()
    val size = if (tv) 112.dp else 92.dp
    Column(Modifier.widthIn(max = size + 12.dp).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).cardFocus(CircleShape).clip(CircleShape).background(Ink3).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            LogoOr(p.photo, null, ContentScale.Crop, Modifier.fillMaxSize()) { Initials(p.name, 26.sp) }
        }
        Text(
            p.name,
            fontFamily = Sans,
            fontSize = 13.sp,
            color = Paper,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
