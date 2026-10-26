@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.falak.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.ADULT_GROUP
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import app.falak.player.data.mine
import app.falak.player.data.progressKey
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/*
 * The Films and Series pages, Marquee style: the featured title's picture across the top of the
 * page (behind the bars too), its name, rating and story with Play and More info; then rows of
 * what to watch: continue, the playlist's top ten, what's new, favorites and the provider's
 * groups, each with "See all". Picking a group (or "All") opens its full grid as before.
 */

/** The page area the picture is drawn in: its height, where its top is on screen (px), and
 * whether there is room for the picture (TV, tablets, phones on their side). */
class MarqueeStageInfo(val height: Dp, val wide: Boolean, val top: () -> Float)

val LocalMarqueeStage = staticCompositionLocalOf<MarqueeStageInfo?> { null }

/** How much of the page the picture covers: most of a TV, a little more of a short screen. */
private fun marqueeFraction(height: Dp): Float = if (height < 480.dp) 0.84f else 0.74f

/**
 * The titles the Films (or Series) page features: new ones with a picture, else any with one;
 * never from hidden, locked or adult groups.
 */
@Composable
fun rememberMarqueeFeatured(vm: LibraryViewModel, kind: Kind, excluded: Set<String>): List<Channel> {
    val index = vm.index
    return remember(index, excluded, kind) {
        fun shows(ch: Channel) = ch.logo.isNotBlank() && (excluded.isEmpty() || ch.group !in excluded) && !ADULT_GROUP.containsMatchIn(ch.group)
        val new = if (kind == Kind.MOVIE) index.newMovies else index.newSeries
        new.filter(::shows).take(6).ifEmpty { index.ofKind(kind).asSequence().filter(::shows).take(6).toList() }
    }
}

/** Which of [featured] is on show now. */
private fun marqueeSlot(vm: LibraryViewModel, kind: Kind, featured: List<Channel>): Int =
    if (featured.isEmpty()) 0 else (vm.marqueeIndex[kind] ?: 0).mod(featured.size)

/**
 * A page (bars included) that can have the Films or Series picture behind its top: the picture
 * is drawn first, and [content] over it. [landing] is the Films or Series front page when it is
 * the one open (no group picked, nothing searched), else null.
 */
@Composable
fun MarqueeStage(
    vm: LibraryViewModel,
    landing: Section?,
    excluded: Set<String>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val top = remember { floatArrayOf(0f) }
    val tv = isTv()
    BoxWithConstraints(modifier.onGloballyPositioned { top[0] = it.positionInRoot().y }) {
        val height = maxHeight
        // TV, tablets and phones on their side; a phone held upright has the tall poster card instead.
        val wide = tv || maxWidth >= 640.dp
        val info = remember(height, wide) { MarqueeStageInfo(height, wide) { top[0] } }
        val kind = landing?.kind
        if (wide && kind != null) {
            MarqueeBackdropFor(vm, landing, kind, excluded, Modifier.fillMaxWidth().height(height * marqueeFraction(height)))
        }
        CompositionLocalProvider(LocalMarqueeStage provides info) { content() }
    }
}

/** The picture of the title the page is showing. */
@Composable
private fun MarqueeBackdropFor(vm: LibraryViewModel, section: Section, kind: Kind, excluded: Set<String>, modifier: Modifier) {
    val featured = rememberMarqueeFeatured(vm, kind, excluded)
    val item = featured.getOrNull(marqueeSlot(vm, kind, featured)) ?: return
    MarqueeBackdrop(
        backdrop = vm.heroArt[item.id]?.takeIf { it.isNotBlank() },
        poster = item.logo.ifBlank { null },
        list = vm.marqueeStateFor(section),
        modifier = modifier,
    )
}

/**
 * The featured picture: wide art filling the width, or a poster on the right over a soft wash
 * of it. It fades as the page scrolls past it ([list]).
 */
@Composable
private fun MarqueeBackdrop(backdrop: String?, poster: String?, list: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier) {
    val context = LocalContext.current
    BoxWithConstraints(modifier) {
        val h = maxHeight
        val fadePx = with(LocalDensity.current) { (h * 0.7f).toPx() }.coerceAtLeast(1f)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Read while drawing: scrolling only redraws the picture.
                    alpha = if (list.firstVisibleItemIndex > 0) 0f else (1f - list.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f)
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                // The lower part fades out into the page (whatever is behind it), with no edge.
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0.45f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
        ) {
            Crossfade(backdrop to poster, animationSpec = tween(450), label = "marqueeArt") { (wide, tall) ->
                Box(Modifier.fillMaxSize()) {
                    if (!wide.isNullOrBlank()) {
                        AsyncImage(
                            model = wide,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (!tall.isNullOrBlank()) {
                        // A poster only: a soft wash of it (decoded tiny, so it costs nothing to draw),
                        // and the whole poster sharp on the right.
                        val wash = remember(tall) {
                            ImageRequest.Builder(context).data(tall).size(24, 36).memoryCacheKey("$tall#wash").build()
                        }
                        AsyncImage(model = wash, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.5f, modifier = Modifier.fillMaxSize())
                        AsyncImage(
                            model = tall,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = h * 0.2f, end = 48.dp)
                                .height(h * 0.62f)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    }
                }
            }
            // Dark on the left for the words, and at the top for the bars.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Ink.copy(alpha = 0.96f), 0.36f to Ink.copy(alpha = 0.78f), 0.66f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Ink.copy(alpha = 0.6f), 0.22f to Color.Transparent)))
        }
    }
}

/** The Films or Series page (no group chosen, nothing searched). */
@Composable
fun MarqueeBrowse(
    vm: LibraryViewModel,
    state: AppState,
    section: Section,
    groups: Map<String, List<Channel>>,
    excluded: Set<String>,
    locked: Set<String>,
    onOpen: (List<Channel>, Channel) -> Unit,
    onPlay: (List<Channel>, Channel) -> Unit,
    onGroup: (String?) -> Unit,
    onGroupOptions: (String) -> Unit,
) {
    val kind = section.kind ?: return
    val p = vm.playlist ?: return
    val index = vm.index
    val me = state.mine(p.id)
    val films = kind == Kind.MOVIE
    fun ok(ch: Channel) = excluded.isEmpty() || ch.group !in excluded

    val newOfKind = remember(index, excluded, kind) { (if (films) index.newMovies else index.newSeries).filter(::ok) }
    val featured = rememberMarqueeFeatured(vm, kind, excluded)
    val continueOfKind = remember(state.progress, index, me, excluded, kind) {
        state.progress.entries.toList().asReversed()
            .filter { it.key.startsWith("$me|") }
            .mapNotNull { (key, w) ->
                val id = key.substringAfter('|')
                index.byId[id] ?: if (w.url.isNotBlank()) Channel(id = id, name = w.title, group = "", url = w.url, logo = w.image, kind = w.kind) else null
            }
            .filter { it.kind == kind && ok(it) }
            .take(20)
    }
    val top = remember(index, excluded, kind) { index.trending.filter { it.kind == kind && ok(it) }.take(10) }
    val favIds = state.favorites[me].orEmpty()
    val favOfKind = remember(favIds, index, excluded, kind) { favIds.mapNotNull { index.byId[it] }.filter { it.kind == kind && ok(it) } }
    // A row for each of the provider's first groups (never locked or adult ones: those open
    // from the chips, with the PIN where there is one).
    val groupRows = remember(groups, locked) {
        groups.entries.asSequence()
            .filter { it.key !in locked && it.value.isNotEmpty() && !ADULT_GROUP.containsMatchIn(it.key) }
            .take(14)
            .map { it.key to it.value.take(24) }
            .toList()
    }
    LaunchedEffect(featured, continueOfKind) { vm.loadHeroArt((if (films) featured else emptyList()) + continueOfKind) }
    val watched = { ch: Channel -> state.progress[progressKey(me, ch.id)] }
    val sheet: (Channel) -> Unit = { ch -> vm.listSheetFor = ch }

    val tv = isTv()
    val listState = vm.marqueeStateFor(section)
    val scope = rememberCoroutineScope()
    val inheritedSpec = LocalBringIntoViewSpec.current
    val scroller = remember(listState, tv) { if (tv) HomeScroller(listState, scope, inheritedSpec) else null }
    val stage = LocalMarqueeStage.current
    val density = LocalDensity.current
    var myTop by remember { mutableFloatStateOf(-1f) }

    Box(Modifier.fillMaxSize().onGloballyPositioned { myTop = it.positionInRoot().y }) {
        // The picture behind the page (drawn by the page frame, see MarqueeStage) needs room: TV,
        // tablets and phones on their side. A phone held upright has the tall poster card instead.
        val wide = stage?.wide == true
        val current = marqueeSlot(vm, kind, featured)
        val item = featured.getOrNull(current)
        // A series' details are a bigger download (every episode), so only the one on show.
        LaunchedEffect(item?.id) { if (item != null && item.isSeriesFolder) vm.loadHeroArt(listOf(item), series = true) }
        // The hero's height: from the top of this list down to where the picture fades out.
        val heroHeight = if (stage == null) 0.dp else {
            val offset = if (myTop < 0) 110.dp else with(density) { (myTop - stage.top()).toDp() }
            (stage.height * marqueeFraction(stage.height) - offset).coerceAtLeast(200.dp)
        }
        if (wide && item != null) {
            // A new title every 7 seconds while the top of the page is in view.
            val heroInView = listState.firstVisibleItemIndex == 0
            LaunchedEffect(current, featured.size, heroInView) {
                if (featured.size > 1 && heroInView) {
                    delay(7_000)
                    vm.marqueeIndex[kind] = (current + 1) % featured.size
                }
            }
        }

        CompositionLocalProvider(
            LocalHomeScroller provides scroller,
            LocalBringIntoViewSpec provides (scroller?.manual ?: inheritedSpec),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().onGloballyPositioned { scroller?.viewport = it },
                state = listState,
                contentPadding = PaddingValues(bottom = 40.dp),
            ) {
                if (wide && item != null) {
                    item(key = "hero") {
                        MarqueeHero(
                            item = item,
                            info = vm.heroInfo[item.id],
                            count = featured.size,
                            current = current,
                            height = heroHeight,
                            onPrimary = { ch -> if (ch.isSeriesFolder) vm.openSeries(ch) else onPlay(listOf(ch), ch) },
                            onDetails = { ch -> vm.openDetails(ch) },
                        )
                    }
                } else if (featured.isNotEmpty()) {
                    item(key = "hero") {
                        FeatureCarousel(
                            items = featured,
                            art = vm.heroArt,
                            heightLimit = 10_000.dp,
                            onPrimary = { ch -> if (ch.isSeriesFolder) vm.openSeries(ch) else onPlay(listOf(ch), ch) },
                            onDetails = { ch -> if (ch.isSeriesFolder) vm.openSeries(ch) else vm.openDetails(ch) },
                        )
                    }
                }
                item(key = "groups") {
                    GroupPicker(
                        all = if (films) "All films" else "All series",
                        total = groups.values.sumOf { it.size },
                        groups = groups,
                        locked = locked,
                        onGroup = onGroup,
                        onOptions = onGroupOptions,
                    )
                }
                if (continueOfKind.isNotEmpty()) {
                    item(key = "continue") {
                        Box(Modifier.padding(top = 16.dp)) {
                            ContinueRow(
                                continueOfKind, vm.heroArt, state.progress, me,
                                title = "Continue watching",
                                width = if (tv) 220.dp else 180.dp,
                            ) { ch -> onPlay(listOf(ch), ch) }
                        }
                    }
                }
                if (top.size >= 3) {
                    item(key = "top") {
                        RankShelf(if (films) "Top 10 films on your playlist" else "Top 10 series on your playlist", top, watched, { ch -> onOpen(top, ch) }, sheet)
                    }
                }
                if (newOfKind.isNotEmpty()) {
                    item(key = "new") {
                        Shelf(if (films) "New films" else "New series", onSeeAll = { vm.sort = Sort.NEWEST; onGroup(null) }) {
                            items(newOfKind, key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { onOpen(newOfKind, ch) }, onLongClick = { sheet(ch) }) }
                        }
                    }
                }
                if (favOfKind.isNotEmpty()) {
                    item(key = "fav") {
                        Shelf(if (films) "Your favorite films" else "Your favorite series") {
                            items(favOfKind, key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { onOpen(favOfKind, ch) }, onLongClick = { sheet(ch) }) }
                        }
                    }
                }
                items(groupRows, key = { "g:" + it.first }) { (name, list) ->
                    Shelf(name, onSeeAll = { onGroup(name) }) {
                        items(list, key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { onOpen(list, ch) }, onLongClick = { sheet(ch) }) }
                    }
                }
            }
        }
    }
}

/** The featured title over its picture: kicker, name, rating and story, Play and More info. */
@Composable
private fun MarqueeHero(
    item: Channel,
    info: app.falak.player.data.VodInfo?,
    count: Int,
    current: Int,
    height: Dp,
    onPrimary: (Channel) -> Unit,
    onDetails: (Channel) -> Unit,
) {
    val tv = isTv()
    Box(Modifier.fillMaxWidth().heightIn(min = height).pinToTop()) {
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(if (tv) 0.54f else 0.6f).padding(start = 4.dp, bottom = 16.dp)) {
            AnimatedContent(
                targetState = item to info,
                transitionSpec = { fadeIn(tween(if (tv) 200 else 350)).togetherWith(fadeOut(tween(150))) },
                label = "marqueeText",
            ) { (ch, details) ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Kicker(
                            when {
                                ch.isNew() && ch.isSeriesFolder -> "New series"
                                ch.isNew() -> "New film"
                                ch.isSeriesFolder -> "Series"
                                else -> "Film"
                            },
                        )
                        qualityTag(ch)?.let {
                            Spacer(Modifier.width(10.dp))
                            CardBadge(it, Paper)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        ch.name,
                        fontFamily = Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (tv) 44.sp else 32.sp,
                        lineHeight = if (tv) 48.sp else 36.sp,
                        color = Paper,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(
                        ratingLabel(details?.rating?.takeIf { it.isNotBlank() } ?: ch.rating),
                        details?.year?.takeIf { it.isNotBlank() }?.take(4),
                        (details?.genre?.takeIf { it.isNotBlank() }?.substringBefore(',') ?: ch.group.takeIf { it.isNotBlank() }),
                        details?.duration?.takeIf { it.isNotBlank() },
                    ).joinToString("   ·   ")
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(meta, fontFamily = Sans, fontSize = if (tv) 15.sp else 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    details?.plot?.takeIf { it.isNotBlank() }?.let { plot ->
                        Spacer(Modifier.height(8.dp))
                        Text(plot, fontFamily = Sans, fontSize = if (tv) 15.sp else 13.sp, lineHeight = if (tv) 22.sp else 19.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PaperButton(if (item.isSeriesFolder) "Episodes" else "Play", Icons.Filled.PlayArrow) { onPrimary(item) }
                if (!item.isSeriesFolder) GhostButton("More info", Icons.Outlined.Info) { onDetails(item) }
            }
        }
        if (count > 1) {
            Row(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(count) { i ->
                    Box(
                        Modifier
                            .width(if (i == current) 22.dp else 10.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (i == current) Paper else Paper.copy(alpha = 0.35f)),
                    )
                }
            }
        }
    }
}

/** "All films" and the provider's groups as chips; a locked group asks for the PIN when picked. */
@Composable
private fun GroupPicker(
    all: String,
    total: Int,
    groups: Map<String, List<Channel>>,
    locked: Set<String>,
    onGroup: (String?) -> Unit,
    onOptions: (String) -> Unit,
) {
    val names = remember(groups) { groups.keys.toList() }
    val scroller = LocalHomeScroller.current
    CompositionLocalProvider(LocalBringIntoViewSpec provides (scroller?.inner ?: LocalBringIntoViewSpec.current)) {
        LazyRow(
            Modifier.fillMaxWidth().pinToTop().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        ) {
            item(key = "__all") { TagChip("$all · ${countLabel(total)}", false) { onGroup(null) } }
            items(names, key = { "g:$it" }) { g ->
                TagChip(if (g in locked) "🔒 $g" else g, false, onLongClick = { onOptions(g) }) { onGroup(g) }
            }
        }
    }
}

/** The playlist's top ten: big outlined numbers with the posters over their edge. */
@Composable
private fun RankShelf(
    title: String,
    list: List<Channel>,
    watched: (Channel) -> app.falak.player.data.Watch?,
    onOpen: (Channel) -> Unit,
    onLongPress: (Channel) -> Unit,
) {
    val tv = isTv()
    val posterWidth = if (tv) 112.dp else 96.dp
    val numberSize = if (tv) 112.sp else 92.sp
    val stroke = with(LocalDensity.current) { 2.dp.toPx() }
    Shelf(title) {
        itemsIndexed(list, key = { _, ch -> ch.id }) { i, ch ->
            Row {
                // The number tucks under the poster's left edge.
                Box(
                    Modifier.height(posterWidth * 1.5f).layout { m, c ->
                        val pl = m.measure(c)
                        val tuck = 14.dp.roundToPx()
                        layout((pl.width - tuck).coerceAtLeast(0), pl.height) { pl.place(0, 0) }
                    },
                ) {
                    Text(
                        "${i + 1}",
                        style = TextStyle(
                            fontFamily = Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = numberSize,
                            lineHeight = numberSize,
                            color = Paper.copy(alpha = 0.55f),
                            drawStyle = Stroke(width = stroke),
                        ),
                        softWrap = false,
                        modifier = Modifier.align(Alignment.BottomStart).offset(y = numberSize.value.dp * 0.16f),
                    )
                }
                PosterCard(ch, watched(ch), onClick = { onOpen(ch) }, width = posterWidth, onLongClick = { onLongPress(ch) })
            }
        }
    }
}
