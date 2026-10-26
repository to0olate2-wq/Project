@file:OptIn(ExperimentalFoundationApi::class)

package app.falak.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.mine
import app.falak.player.data.Habits
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.Epg
import app.falak.player.data.Kind
import app.falak.player.data.Program
import app.falak.player.data.progressKey
import app.falak.player.data.ADULT_GROUP
import coil.compose.AsyncImage
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind

/**
 * The start page, Marquee style (see MarqueePage): the featured new film or series over its
 * picture (on a phone held upright, the tall poster card), then Continue watching and the
 * playlist's Top 10, what you watch (For this hour, Recently watched), what's new (New films, New
 * series), then your own favorites and lists. The provider's groups are on the Live, Films and
 * Series pages.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun HomeContent(
    vm: LibraryViewModel,
    state: AppState,
    locked: Set<String>,
    epg: Map<String, List<Program>>,
    now: Long,
    onPlay: (List<Channel>, Channel) -> Unit,
) {
    val p = vm.playlist ?: return
    val index = vm.index
    // This profile's favorites, recents, lists and Continue watching.
    val me = state.mine(p.id)
    val favIds = state.favorites[me].orEmpty()
    val recentIds = state.recents[me].orEmpty()
    fun ok(ch: Channel) = locked.isEmpty() || ch.group !in locked

    val continueWatching = remember(state.progress, index, me, locked) {
        state.progress.entries.toList().asReversed()
            .filter { it.key.startsWith("$me|") }
            .mapNotNull { (key, w) ->
                val id = key.substringAfter('|')
                index.byId[id] ?: if (w.url.isNotBlank()) {
                    Channel(id = id, name = w.title, group = "", url = w.url, logo = w.image, kind = w.kind)
                } else {
                    null
                }
            }
            .filter(::ok)
            .take(20)
    }
    val favLive = remember(favIds, index, locked) { favIds.mapNotNull { index.byId[it] }.filter { it.kind == Kind.LIVE && ok(it) } }
    val favVod = remember(favIds, index, locked) { favIds.mapNotNull { index.byId[it] }.filter { it.kind != Kind.LIVE && ok(it) } }
    // Your own lists get a shelf each (live channels and videos separately).
    val myLists = remember(state.favLists, me, index, locked) {
        state.favLists[me].orEmpty().map { l ->
            val items = l.ids.mapNotNull { index.byId[it] }.filter(::ok)
            Triple(l, items.filter { it.kind == Kind.LIVE }, items.filter { it.kind != Kind.LIVE })
        }.filter { it.second.isNotEmpty() || it.third.isNotEmpty() }
    }
    val sheet: (Channel) -> Unit = { ch -> vm.listSheetFor = ch }
    val recentLive = remember(recentIds, index, locked) {
        recentIds.mapNotNull { index.byId[it] }.filter { it.kind == Kind.LIVE && ok(it) }.take(20)
    }
    // The channels you usually watch around this time of day (learned on this device, see Habits).
    val context = LocalContext.current
    val quarter = now / (15 * 60_000L)
    val forNowIds by produceState(emptyList<String>(), me, quarter) {
        value = withContext(Dispatchers.IO) {
            runCatching { Habits.forNow(Habits.read(context.filesDir), me, System.currentTimeMillis()) }.getOrDefault(emptyList())
        }
    }
    val forThisHour = remember(forNowIds, index, locked) {
        forNowIds.mapNotNull { index.byId[it] }.filter { it.kind == Kind.LIVE && ok(it) }
    }
    val newMovies = remember(index, locked) { index.newMovies.filter(::ok) }
    val newSeries = remember(index, locked) { index.newSeries.filter(::ok) }
    val top = remember(index, locked) { index.trending.filter(::ok).take(10) }
    // The featured titles: only what you can see (never hidden, locked or adult groups).
    val hero = rememberMarqueeFeatured(vm, Section.HOME, locked)
    // Ramadan mode: where today stands, and the provider's Ramadan series.
    val ramadan = rememberRamadan()
    val ramadanTitles = remember(ramadan?.first, ramadan?.second is app.falak.player.data.Ramadan.Phase.Eid, index, locked) {
        val r = ramadan ?: return@remember null
        if (r.second is app.falak.player.data.Ramadan.Phase.Eid) null else app.falak.player.data.Ramadan.titles(index, r.first, locked)
    }
    LaunchedEffect(hero, continueWatching) { vm.loadHeroArt(hero + continueWatching) }

    val openItem: (List<Channel>, Channel) -> Unit = { row, ch ->
        when {
            ch.isSeriesFolder -> vm.openSeries(ch)
            ch.kind == Kind.MOVIE && index.byId.containsKey(ch.id) -> vm.openDetails(ch)
            else -> onPlay(row, ch)
        }
    }
    val seeAll: (Section, String?, Sort) -> Unit = { section, group, sort ->
        vm.section = section
        // Films and Series: every title as a grid, not their front page.
        vm.group = group ?: if (section == Section.MOVIES || section == Section.SERIES) ALL_FILTER else null
        vm.sort = sort
    }
    val watched = { ch: Channel -> state.progress[progressKey(me, ch.id)] }
    val tvHome = isTv()

    MarqueePage(vm, Section.HOME, hero, onPlay) {
        if (ramadan != null) {
            item(key = "ramadan") { RamadanCard(ramadan.second) }
        }
        ramadanTitles?.takeIf { it.first.isNotEmpty() }?.let { (list, group) ->
            item(key = "ramadanTitles") {
                val section = if (list.first().kind == Kind.SERIES) Section.SERIES else Section.MOVIES
                Shelf("Ramadan series", onSeeAll = { seeAll(section, group, Sort.NEWEST) }) {
                    items(list, key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { openItem(list, ch) }, onLongClick = { sheet(ch) }) }
                }
            }
        }
        // Films and episodes you started, then the playlist's most popular.
        if (continueWatching.isNotEmpty()) {
            item(key = "continue") {
                Box(Modifier.padding(top = 16.dp)) {
                    ContinueRow(
                        continueWatching, vm.heroArt, state.progress, me,
                        title = "Continue watching",
                        width = if (tvHome) 220.dp else 180.dp,
                    ) { ch -> onPlay(listOf(ch), ch) }
                }
            }
        }
        if (top.isNotEmpty()) {
            item(key = "top") {
                RankShelf("Top 10 on your playlist", top, watched, { ch -> openItem(top, ch) }, sheet)
            }
        }
        // What you watch.
        if (forThisHour.isNotEmpty()) {
            item(key = "forThisHour") {
                LiveShelf("For this hour", forThisHour, epg, now, onPlay, onLongPress = sheet, onSeeAll = null)
            }
        }
        if (recentLive.isNotEmpty()) {
            item(key = "recentLive") {
                LiveShelf("Recently watched", recentLive, epg, now, onPlay, onLongPress = sheet) { seeAll(Section.RECENT, null, Sort.DEFAULT) }
            }
        }
        // What's new.
        if (newMovies.isNotEmpty()) {
            item(key = "newMovies") {
                Shelf("New films", onSeeAll = { seeAll(Section.MOVIES, null, Sort.NEWEST) }) {
                    items(newMovies, key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { openItem(newMovies, ch) }, onLongClick = { sheet(ch) }) }
                }
            }
        }
        if (newSeries.isNotEmpty()) {
            item(key = "newSeries") {
                Shelf("New series", onSeeAll = { seeAll(Section.SERIES, null, Sort.NEWEST) }) {
                    items(newSeries, key = { it.id }) { ch -> PosterCard(ch, null, onClick = { openItem(newSeries, ch) }, onLongClick = { sheet(ch) }) }
                }
            }
        }
        // Your own picks.
        if (favLive.isNotEmpty()) {
            item(key = "favLive") {
                LiveShelf("Favorite channels", favLive, epg, now, onPlay, onLongPress = sheet) {
                    vm.savedList = app.falak.player.data.DEFAULT_LIST
                    seeAll(Section.FAVORITES, null, Sort.DEFAULT)
                }
            }
        }
        if (favVod.isNotEmpty()) {
            item(key = "favVod") {
                Shelf("Favorite films & series", onSeeAll = { seeAll(Section.FAVORITES, null, Sort.DEFAULT) }) {
                    items(favVod, key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { openItem(favVod, ch) }, onLongClick = { sheet(ch) }) }
                }
            }
        }
        items(myLists, key = { "list:" + it.first.id }) { (l, live, vod) ->
            val open = { vm.section = Section.FAVORITES; vm.savedList = l.id }
            Column {
                if (live.isNotEmpty()) {
                    LiveShelf(l.name, live.take(30), epg, now, { _, ch -> onPlay(live, ch) }, onLongPress = sheet) { open() }
                }
                if (vod.isNotEmpty()) {
                    Shelf(if (live.isNotEmpty()) "${l.name} · films & series" else l.name, onSeeAll = open) {
                        items(vod.take(30), key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { openItem(vod, ch) }, onLongClick = { sheet(ch) }) }
                    }
                }
            }
        }
        // A new playlist with no films and nothing watched yet: say what will appear here.
        val empty = hero.isEmpty() && forThisHour.isEmpty() && recentLive.isEmpty() && continueWatching.isEmpty() &&
            top.isEmpty() && newMovies.isEmpty() && newSeries.isEmpty() && favLive.isEmpty() && favVod.isEmpty() && myLists.isEmpty()
        if (empty) {
            item(key = "empty") {
                EmptyHome(if (index.live.isNotEmpty()) ({ seeAll(Section.LIVE, null, Sort.DEFAULT) }) else null)
            }
        }
    }
}

/** Home before there's anything to show on it. */
@Composable
private fun EmptyHome(onBrowse: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().pinToTop().padding(horizontal = 24.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Your Home fills up as you watch",
            fontFamily = Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            color = Paper,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Channels you've watched, films and series to continue, and what's trending and new will show up here.",
            fontFamily = Sans,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = Muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        )
        if (onBrowse != null) {
            Spacer(Modifier.height(18.dp))
            PaperButton("Browse channels", Icons.Filled.PlayArrow, onClick = onBrowse)
        }
    }
}

@Composable
private fun LiveShelf(
    title: String,
    list: List<Channel>,
    epg: Map<String, List<Program>>,
    now: Long,
    onPlay: (List<Channel>, Channel) -> Unit,
    onLongPress: ((Channel) -> Unit)? = null,
    onSeeAll: (() -> Unit)?,
) {
    Shelf(title, onSeeAll = onSeeAll) {
        items(list, key = { it.id }) { ch ->
            ChannelCard(
                ch, Epg.current(epg[ch.epgKey], now), now, onClick = { onPlay(list, ch) },
                onLongClick = onLongPress?.let { f -> { f(ch) } },
            )
        }
    }
}
