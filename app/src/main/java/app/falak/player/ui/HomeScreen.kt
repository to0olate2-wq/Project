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
 * The start page: a greeting, the channel you'd most likely watch now and Tonight on your other
 * channels (or, with no live channels, the slideshow), then Pick up where you left off, what you
 * watch (For this hour, Recently watched), what's popular and new (Trending now, New films, New
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
    /** Sidebar look (a phone held upright shows Tonight as cards). */
    sidebar: Boolean = false,
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
    val trending = remember(index, locked) { index.trending.filter(::ok) }
    // The slideshow features only what you can see: never hidden or locked groups (keyed on them
    // too, so hiding a group takes its films out at once), and never adult groups.
    val hero = remember(newMovies, newSeries, index, locked) {
        fun featured(ch: Channel) = ch.logo.isNotBlank() && ok(ch) && !ADULT_GROUP.containsMatchIn(ch.group)
        (newMovies.filter(::featured).take(4) + newSeries.filter(::featured).take(2))
            .ifEmpty { (index.movies.asSequence() + index.series.asSequence()).filter(::featured).take(5).toList() }
    }
    // Ramadan mode: where today stands, and the provider's Ramadan series.
    val ramadan = rememberRamadan()
    val ramadanTitles = remember(ramadan?.first, ramadan?.second is app.falak.player.data.Ramadan.Phase.Eid, index, locked) {
        val r = ramadan ?: return@remember null
        if (r.second is app.falak.player.data.Ramadan.Phase.Eid) null else app.falak.player.data.Ramadan.titles(index, r.first, locked)
    }
    // The live card: the channel you usually watch at this time, else the last one you watched,
    // a favorite, or one with something on (never from an adult group unless it's yours).
    val guided = remember(index, epg, locked, quarter) {
        index.live.asSequence()
            .filter { ok(it) && !ADULT_GROUP.containsMatchIn(it.group) && Epg.current(epg[it.epgKey], now) != null }
            .take(8)
            .toList()
    }
    val pick = remember(forThisHour, recentLive, favLive, guided, index, locked) {
        forThisHour.firstOrNull()?.let { NowPick(it, NowReason.HABIT) }
            ?: recentLive.firstOrNull()?.let { NowPick(it, NowReason.RECENT) }
            ?: favLive.firstOrNull()?.let { NowPick(it, NowReason.FAVORITE) }
            ?: guided.firstOrNull()?.let { NowPick(it, NowReason.NONE) }
            ?: index.live.firstOrNull { ok(it) && !ADULT_GROUP.containsMatchIn(it.group) }?.let { NowPick(it, NowReason.NONE) }
    }
    // Tonight: your other channels (favorites, recent, this hour's), then others with a guide.
    val tonight = remember(pick, favLive, recentLive, forThisHour, guided, epg, quarter) {
        val skip = pick?.channel?.id
        val mine = (favLive + recentLive + forThisHour).distinctBy { it.id }.filter { it.id != skip }
        val (on, off) = mine.partition { Epg.current(epg[it.epgKey], now) != null }
        (on + off + guided.filter { g -> g.id != skip && mine.none { it.id == g.id } }).take(6)
    }
    LaunchedEffect(hero, continueWatching, pick == null) { vm.loadHeroArt((if (pick == null) hero else emptyList()) + continueWatching) }

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
    val scope = rememberCoroutineScope()
    val inheritedSpec = LocalBringIntoViewSpec.current
    val scroller = remember(vm.homeState, tvHome) {
        if (tvHome) HomeScroller(vm.homeState, scope, inheritedSpec) else null
    }
    CompositionLocalProvider(
        LocalHomeScroller provides scroller,
        LocalBringIntoViewSpec provides (scroller?.manual ?: inheritedSpec),
    ) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // TV: the greeting with the live card (or the slideshow) fits on screen whole, with the next
    // row's title peeking below.
    val greeting = 62.dp
    val topMax = (maxHeight - greeting - (if (tvHome) 72.dp else 24.dp)).coerceAtLeast(220.dp)
    LazyColumn(
        Modifier.fillMaxSize().onGloballyPositioned { scroller?.viewport = it },
        state = vm.homeState,
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        if (pick != null || hero.isNotEmpty()) {
            item(key = "now") {
                Column(Modifier.fillMaxWidth().pinToTop().padding(top = 4.dp)) {
                    HomeGreeting(vm, state, now)
                    Spacer(Modifier.height(12.dp))
                    if (pick != null) {
                        NowRow(vm, pick, tonight, epg, now, heightLimit = topMax, cards = sidebar && !tvHome, onPlay = onPlay, onLongPress = sheet)
                    } else {
                        FeatureCarousel(
                            items = hero,
                            art = vm.heroArt,
                            heightLimit = if (tvHome) topMax else 10_000.dp,
                            onPrimary = { ch -> if (ch.isSeriesFolder) vm.openSeries(ch) else onPlay(listOf(ch), ch) },
                            onDetails = { ch -> if (ch.isSeriesFolder) vm.openSeries(ch) else vm.openDetails(ch) },
                            pin = false,
                        )
                    }
                }
            }
        }
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
        // Films and episodes you started.
        if (continueWatching.isNotEmpty()) {
            item(key = "continue") {
                Box(Modifier.padding(top = 16.dp)) {
                    ContinueRow(
                        continueWatching, vm.heroArt, state.progress, me,
                        title = "Pick up where you left off",
                        width = if (tvHome) 220.dp else 180.dp,
                    ) { ch -> onPlay(listOf(ch), ch) }
                }
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
        // What's popular and new.
        if (trending.isNotEmpty()) {
            item(key = "trending") {
                Shelf("Trending now") {
                    items(trending, key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { openItem(trending, ch) }, onLongClick = { sheet(ch) }) }
                }
            }
        }
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
        val empty = pick == null && hero.isEmpty() && forThisHour.isEmpty() && recentLive.isEmpty() && continueWatching.isEmpty() &&
            trending.isEmpty() && newMovies.isEmpty() && newSeries.isEmpty() && favLive.isEmpty() && favVod.isEmpty() && myLists.isEmpty()
        if (empty) {
            item(key = "empty") {
                EmptyHome(if (index.live.isNotEmpty()) ({ seeAll(Section.LIVE, null, Sort.DEFAULT) }) else null)
            }
        }
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

private const val SLIDE_MS = 7_000

/**
 * Auto-advancing feature carousel: slow zoom on the picture, story-style progress bars,
 * swipeable. The buttons stay put while slides change, so TV-remote focus is never lost.
 */
@Composable
internal fun FeatureCarousel(
    items: List<Channel>,
    art: Map<String, String>,
    heightLimit: androidx.compose.ui.unit.Dp,
    onPrimary: (Channel) -> Unit,
    onDetails: (Channel) -> Unit,
    /** Scrolls itself to the top of a TV page when it takes focus (off when inside a bigger block that does). */
    pin: Boolean = true,
) {
    val size = items.size
    val start = remember(size) { if (size <= 1) 0 else (Int.MAX_VALUE / 2).let { it - it % size } }
    val pager = rememberPagerState(initialPage = start, pageCount = { if (size <= 1) size else Int.MAX_VALUE })
    val progress = remember { Animatable(0f) }
    val tv = isTv()
    // On TV boxes, continuous animations keep the graphics chip busy and make every other screen
    // feel slow, so there the slideshow just changes slides (no slow zoom, no running bars).
    val motion = !tv
    LaunchedEffect(pager.settledPage, size) {
        progress.snapTo(0f)
        if (size > 1) {
            if (motion) progress.animateTo(1f, tween(SLIDE_MS, easing = LinearEasing)) else delay(SLIDE_MS.toLong())
            pager.animateScrollToPage(pager.settledPage + 1, animationSpec = tween(if (motion) 900 else 600, easing = FastOutSlowInEasing))
        }
    }
    val current = items[(pager.currentPage % size.coerceAtLeast(1)).coerceIn(0, items.lastIndex)]
    val context = LocalContext.current

    BoxWithConstraints(Modifier.fillMaxWidth().then(if (pin) Modifier.pinToTop() else Modifier).padding(top = 6.dp)) {
        // Phone layout: a tall poster card; TV (and wide tablets): a wide cinema banner.
        val compact = !tv && maxWidth < 600.dp
        val height = (if (compact) (maxWidth * 1.15f).coerceIn(320.dp, 560.dp) else (maxWidth * 0.52f).coerceIn(250.dp, 440.dp))
            .coerceAtMost(heightLimit)
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp)).background(Ink2)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val item = items[page % size]
                val zoom by animateFloatAsState(
                    if (motion && pager.settledPage == page) 1.08f else 1f,
                    tween(if (pager.settledPage == page) SLIDE_MS + 900 else 300, easing = LinearEasing),
                    label = "kenBurns",
                )
                val backdrop = art[item.id]?.takeIf { it.isNotBlank() }
                if (backdrop != null) {
                    // Wide artwork: fill the frame with a slow zoom.
                    AsyncImage(
                        model = backdrop,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                        },
                    )
                } else {
                    // Portrait poster only: a soft wash of the poster behind, the whole poster sharp on the right.
                    // The wash is the poster decoded tiny and stretched: it looks blurred but costs nothing
                    // per frame (a real blur is recalculated on every frame of the animation).
                    val wash = remember(item.logo) {
                        ImageRequest.Builder(context)
                            .data(item.logo)
                            .size(24, 36)
                            .memoryCacheKey(item.logo + "#wash")
                            .build()
                    }
                    Box(Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = wash,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alpha = 0.5f,
                            modifier = Modifier.fillMaxSize(),
                        )
                        AsyncImage(
                            model = item.logo,
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            modifier = (
                                if (compact) {
                                    Modifier.align(Alignment.TopCenter).padding(top = 20.dp).fillMaxHeight(0.66f)
                                } else {
                                    Modifier.align(Alignment.CenterEnd).padding(end = if (tv) 48.dp else 18.dp, top = 18.dp, bottom = 18.dp).fillMaxHeight()
                                }
                                )
                                .aspectRatio(2f / 3f, matchHeightConstraintsFirst = true)
                                .clip(RoundedCornerShape(8.dp))
                                .graphicsLayer {
                                    scaleX = 0.98f + (zoom - 1f) * 0.3f
                                    scaleY = 0.98f + (zoom - 1f) * 0.3f
                                },
                        )
                    }
                }
            }
            if (compact) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 0.78f to Ink.copy(alpha = 0.85f), 1f to Ink)))
            } else {
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Ink.copy(alpha = 0.95f), 0.55f to Ink.copy(alpha = 0.65f), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Ink.copy(alpha = 0.92f))))
            }

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(if (compact) 1f else if (tv) 0.55f else 0.62f)
                    .padding(horizontal = if (compact) 18.dp else 22.dp, vertical = if (compact) 16.dp else 20.dp),
            ) {
                AnimatedContent(
                    targetState = current,
                    transitionSpec = {
                        (fadeIn(tween(450, delayMillis = 150)) + slideInVertically(tween(450, delayMillis = 150)) { it / 6 })
                            .togetherWith(fadeOut(tween(200)))
                    },
                    label = "heroText",
                ) { ch ->
                    Column {
                        Kicker(
                            when {
                                ch.isNew() && ch.isSeriesFolder -> "New series"
                                ch.isNew() -> "New film"
                                ch.isSeriesFolder -> "Series"
                                else -> "Featured"
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            ch.name,
                            fontFamily = Serif,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = if (tv) 38.sp else 28.sp,
                            lineHeight = if (tv) 42.sp else 32.sp,
                            color = Paper,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = listOfNotNull(ratingLabel(ch.rating), ch.group.takeIf { it.isNotBlank() }).joinToString("   ·   ")
                        if (meta.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(meta, fontFamily = Sans, fontSize = 13.sp, color = Muted, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PaperButton(if (current.isSeriesFolder) "Episodes" else "Play", Icons.Filled.PlayArrow) { onPrimary(current) }
                    if (!current.isSeriesFolder) GhostButton("Details", Icons.Outlined.Info) { onDetails(current) }
                }
            }

            if (size > 1) {
                val active = pager.currentPage % size
                // Phone: along the top, clear of the buttons; TV: bottom right, level with them.
                Row(
                    if (compact) {
                        Modifier.align(Alignment.TopEnd).padding(horizontal = 18.dp, vertical = 16.dp)
                    } else {
                        Modifier.align(Alignment.BottomEnd).padding(horizontal = 22.dp, vertical = 34.dp)
                    },
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    val paper = Paper
                    repeat(size) { i ->
                        Box(
                            Modifier
                                .width(22.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(paper.copy(alpha = 0.3f))
                                // Read in the draw phase: the bar moves without recomposing the carousel.
                                .drawBehind {
                                    val fill = when {
                                        i < active -> 1f
                                        i == active -> if (motion) progress.value else 1f
                                        else -> 0f
                                    }
                                    if (fill > 0f) drawRect(paper, size = Size(this.size.width * fill, this.size.height))
                                },
                        )
                    }
                }
            }
        }
    }
}
