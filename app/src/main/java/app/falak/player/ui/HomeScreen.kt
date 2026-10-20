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
import androidx.compose.ui.draw.blur
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
import coil.compose.AsyncImage

/** The start page: feature carousel, then shelves for live, films and series. */
@Composable
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
    val hero = remember(newMovies, newSeries, index) {
        (newMovies.filter { it.logo.isNotBlank() }.take(4) + newSeries.filter { it.logo.isNotBlank() }.take(2))
            .ifEmpty { (index.movies + index.series).filter { it.logo.isNotBlank() && ok(it) }.take(5) }
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

    LazyColumn(Modifier.fillMaxSize(), state = vm.homeState, contentPadding = PaddingValues(bottom = 40.dp)) {
        if (hero.isNotEmpty()) {
            item(key = "hero") {
                FeatureCarousel(
                    items = hero,
                    art = vm.heroArt,
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
                LiveShelf("On now", favLive, epg, now, onPlay) { seeAll(Section.FAVORITES, null, Sort.DEFAULT) }
            }
        }
        if (recentLive.isNotEmpty()) {
            item(key = "recentLive") {
                LiveShelf("Recently watched", recentLive, epg, now, onPlay) { seeAll(Section.RECENT, null, Sort.DEFAULT) }
            }
        }
        if (newMovies.isNotEmpty()) {
            item(key = "newMovies") {
                Shelf("New films", onSeeAll = { seeAll(Section.MOVIES, null, Sort.NEWEST) }) {
                    items(newMovies, key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { openItem(newMovies, ch) }) }
                }
            }
        }
        if (newSeries.isNotEmpty()) {
            item(key = "newSeries") {
                Shelf("New series", onSeeAll = { seeAll(Section.SERIES, null, Sort.NEWEST) }) {
                    items(newSeries, key = { it.id }) { ch -> PosterCard(ch, null, onClick = { openItem(newSeries, ch) }) }
                }
            }
        }
        if (favVod.isNotEmpty()) {
            item(key = "favVod") {
                Shelf("Your list", onSeeAll = { seeAll(Section.FAVORITES, null, Sort.DEFAULT) }) {
                    items(favVod, key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { openItem(favVod, ch) }) }
                }
            }
        }
        items(liveGroups, key = { "live:" + it.first }) { (group, list) ->
            LiveShelf(group, list.take(20), epg, now, { _, ch -> onPlay(list, ch) }, count = list.size) {
                seeAll(Section.LIVE, group, Sort.DEFAULT)
            }
        }
        items(movieGroups, key = { "movies:" + it.first }) { (group, list) ->
            Shelf(group, count = list.size, onSeeAll = { seeAll(Section.MOVIES, group, Sort.DEFAULT) }) {
                items(list.take(20), key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { openItem(list, ch) }) }
            }
        }
        items(seriesGroups, key = { "series:" + it.first }) { (group, list) ->
            Shelf(group, count = list.size, onSeeAll = { seeAll(Section.SERIES, group, Sort.DEFAULT) }) {
                items(list.take(20), key = { it.id }) { ch -> PosterCard(ch, null, onClick = { openItem(list, ch) }) }
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
    count: Int? = null,
    onSeeAll: (() -> Unit)?,
) {
    Shelf(title, count = count, onSeeAll = onSeeAll) {
        items(list, key = { it.id }) { ch ->
            ChannelCard(ch, Epg.current(epg[ch.epgKey], now), now, onClick = { onPlay(list, ch) })
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
    onPrimary: (Channel) -> Unit,
    onDetails: (Channel) -> Unit,
) {
    val size = items.size
    val start = remember(size) { if (size <= 1) 0 else (Int.MAX_VALUE / 2).let { it - it % size } }
    val pager = rememberPagerState(initialPage = start, pageCount = { if (size <= 1) size else Int.MAX_VALUE })
    val progress = remember { Animatable(0f) }
    LaunchedEffect(pager.settledPage, size) {
        progress.snapTo(0f)
        if (size > 1) {
            progress.animateTo(1f, tween(SLIDE_MS, easing = LinearEasing))
            pager.animateScrollToPage(pager.settledPage + 1, animationSpec = tween(900, easing = FastOutSlowInEasing))
        }
    }
    val current = items[(pager.currentPage % size.coerceAtLeast(1)).coerceIn(0, items.lastIndex)]
    val tv = isTv()

    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        val height = (maxWidth * 0.52f).coerceIn(250.dp, 440.dp)
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp)).background(Ink2)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val item = items[page % size]
                val zoom by animateFloatAsState(
                    if (pager.settledPage == page) 1.08f else 1f,
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
                    // Portrait poster only: blurred poster behind, the whole poster sharp on the right.
                    Box(Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = item.logo,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(30.dp)
                                .graphicsLayer {
                                    alpha = 0.45f
                                    scaleX = 1.2f * zoom
                                    scaleY = 1.2f * zoom
                                },
                        )
                        AsyncImage(
                            model = item.logo,
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = if (tv) 48.dp else 18.dp, top = 18.dp, bottom = 18.dp)
                                .fillMaxHeight()
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
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Ink.copy(alpha = 0.95f), 0.55f to Ink.copy(alpha = 0.65f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Ink.copy(alpha = 0.92f))))

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(if (tv) 0.55f else 0.62f)
                    .padding(horizontal = 22.dp, vertical = 20.dp),
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
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(horizontal = 20.dp, vertical = 26.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    repeat(size) { i ->
                        Box(Modifier.width(22.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Paper.copy(alpha = 0.3f))) {
                            val fill = when {
                                i < active -> 1f
                                i == active -> progress.value
                                else -> 0f
                            }
                            Box(Modifier.fillMaxWidth(fill).height(3.dp).background(Paper))
                        }
                    }
                }
            }
        }
    }
}
