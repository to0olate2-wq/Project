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

/** The start page: feature carousel, then shelves for live, films and series. */
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
    val favIds = state.favorites[p.id].orEmpty()
    val recentIds = state.recents[p.id].orEmpty()
    fun ok(ch: Channel) = locked.isEmpty() || ch.group !in locked

    val continueWatching = remember(state.progress, index, p.id, locked) {
        state.progress.entries.toList().asReversed()
            .filter { it.key.startsWith("${p.id}|") }
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
    val myLists = remember(state.favLists, p.id, index, locked) {
        state.favLists[p.id].orEmpty().map { l ->
            val items = l.ids.mapNotNull { index.byId[it] }.filter(::ok)
            Triple(l, items.filter { it.kind == Kind.LIVE }, items.filter { it.kind != Kind.LIVE })
        }.filter { it.second.isNotEmpty() || it.third.isNotEmpty() }
    }
    val sheet: (Channel) -> Unit = { ch -> vm.listSheetFor = ch }
    val recentLive = remember(recentIds, index, locked) {
        recentIds.mapNotNull { index.byId[it] }.filter { it.kind == Kind.LIVE && ok(it) }.take(20)
    }
    val newMovies = remember(index, locked) { index.newMovies.filter(::ok) }
    val newSeries = remember(index, locked) { index.newSeries.filter(::ok) }
    val liveGroups = remember(index, locked) {
        index.groupsOf(Kind.LIVE).entries.filter { it.key !in locked }.take(6).map { it.key to it.value }
    }
    val movieGroups = remember(index, locked) {
        index.groupsOf(Kind.MOVIE).entries.filter { it.key !in locked && it.value.size >= 3 }.take(5).map { it.key to it.value }
    }
    val seriesGroups = remember(index, locked) {
        index.groupsOf(Kind.SERIES).entries.filter { it.key !in locked && it.value.size >= 3 }.take(4).map { it.key to it.value }
    }
    // The slideshow features only what you can see: never hidden or locked groups (keyed on them
    // too, so hiding a group takes its films out at once), and never adult groups.
    val hero = remember(newMovies, newSeries, index, locked) {
        fun featured(ch: Channel) = ch.logo.isNotBlank() && ok(ch) && !ADULT_GROUP.containsMatchIn(ch.group)
        (newMovies.filter(::featured).take(4) + newSeries.filter(::featured).take(2))
            .ifEmpty { (index.movies.asSequence() + index.series.asSequence()).filter(::featured).take(5).toList() }
    }
    LaunchedEffect(hero) { vm.loadHeroArt(hero) }

    val openItem: (List<Channel>, Channel) -> Unit = { row, ch ->
        when {
            ch.isSeriesFolder -> vm.openSeries(ch)
            ch.kind == Kind.MOVIE && index.byId.containsKey(ch.id) -> vm.openDetails(ch)
            else -> onPlay(row, ch)
        }
    }
    val seeAll: (Section, String?, Sort) -> Unit = { section, group, sort ->
        vm.section = section
        vm.group = group
        vm.sort = sort
    }
    val watched = { ch: Channel -> state.progress[progressKey(p.id, ch.id)] }

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
    // TV: the slideshow always fits on screen whole, with the next row's title peeking below it.
    val heroMax = if (tvHome) (maxHeight - 72.dp).coerceAtLeast(220.dp) else 10_000.dp
    LazyColumn(
        Modifier.fillMaxSize().onGloballyPositioned { scroller?.viewport = it },
        state = vm.homeState,
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        if (hero.isNotEmpty()) {
            item(key = "hero") {
                FeatureCarousel(
                    items = hero,
                    art = vm.heroArt,
                    heightLimit = heroMax,
                    onPrimary = { ch -> if (ch.isSeriesFolder) vm.openSeries(ch) else onPlay(listOf(ch), ch) },
                    onDetails = { ch -> if (ch.isSeriesFolder) vm.openSeries(ch) else vm.openDetails(ch) },
                )
            }
        }
        if (continueWatching.isNotEmpty()) {
            item(key = "continue") {
                Shelf("Continue watching") {
                    items(continueWatching, key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { onPlay(listOf(ch), ch) }) }
                }
            }
        }
        if (favLive.isNotEmpty()) {
            item(key = "favLive") {
                LiveShelf("Favorite channels", favLive, epg, now, onPlay, onLongPress = sheet) {
                    vm.savedList = app.falak.player.data.DEFAULT_LIST
                    seeAll(Section.FAVORITES, null, Sort.DEFAULT)
                }
            }
        }
        if (recentLive.isNotEmpty()) {
            item(key = "recentLive") {
                LiveShelf("Recently watched", recentLive, epg, now, onPlay, onLongPress = sheet) { seeAll(Section.RECENT, null, Sort.DEFAULT) }
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
        items(liveGroups, key = { "live:" + it.first }) { (group, list) ->
            LiveShelf(group, list.take(20), epg, now, { _, ch -> onPlay(list, ch) }, onLongPress = sheet) {
                seeAll(Section.LIVE, group, Sort.DEFAULT)
            }
        }
        items(movieGroups, key = { "movies:" + it.first }) { (group, list) ->
            Shelf(group, onSeeAll = { seeAll(Section.MOVIES, group, Sort.DEFAULT) }) {
                items(list.take(20), key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { openItem(list, ch) }, onLongClick = { sheet(ch) }) }
            }
        }
        items(seriesGroups, key = { "series:" + it.first }) { (group, list) ->
            Shelf(group, onSeeAll = { seeAll(Section.SERIES, group, Sort.DEFAULT) }) {
                items(list.take(20), key = { it.id }) { ch -> PosterCard(ch, null, onClick = { openItem(list, ch) }, onLongClick = { sheet(ch) }) }
            }
        }
    }
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
private fun FeatureCarousel(
    items: List<Channel>,
    art: Map<String, String>,
    heightLimit: androidx.compose.ui.unit.Dp,
    onPrimary: (Channel) -> Unit,
    onDetails: (Channel) -> Unit,
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

    BoxWithConstraints(Modifier.fillMaxWidth().pinToTop().padding(top = 6.dp)) {
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
