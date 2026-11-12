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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
 * Home, Films and Series, Marquee style: the featured title's picture across the top of the page
 * (behind the bars too), its name, rating and story with Play and More info; then rows of what to
 * watch. Films and Series: continue, the playlist's top ten, what's new, favorites and the
 * provider's groups, each with "See all"; picking a group (or "All") opens its full grid as before.
 * Home's rows are in HomeScreen.
 */

/** The page area the picture is drawn in: its height, where its top is on screen (px), and
 * whether there is room for the picture (TV, tablets, phones on their side). */
class MarqueeStageInfo(val height: Dp, val wide: Boolean, val portraitArt: Dp, val top: () -> Float)

val LocalMarqueeStage = staticCompositionLocalOf<MarqueeStageInfo?> { null }

/**
 * How much of the page the picture covers: most of a TV, a little more of a short screen. Home's
 * is shorter, so the row under it (Continue watching) is in view at once.
 */
private fun marqueeFraction(height: Dp, section: Section? = null, portrait: Boolean = false, tv: Boolean = false, cinema: Boolean = false): Float = when {
    // A phone held upright: the picture takes the top of the page, the first row in view under it
    // (the Cinema look: more of the page, the Up next row still in view).
    portrait -> if (cinema) 0.68f else 0.56f
    // TV: behind the bar and the spotlight, fading out behind the first row (the Cinema look: the whole screen).
    tv -> if (cinema) 1f else 0.62f
    height < 480.dp -> 0.84f
    section == Section.HOME -> 0.6f
    else -> 0.74f
}

/**
 * The titles a page features: new ones with a picture, else any with one; never from hidden,
 * locked or adult groups. Home: new films and series; Films or Series: new ones of that kind.
 */
@Composable
fun rememberMarqueeFeatured(vm: LibraryViewModel, section: Section, excluded: Set<String>): List<Channel> {
    val index = vm.index
    return remember(index, excluded, section) {
        fun shows(ch: Channel) = ch.logo.isNotBlank() && (excluded.isEmpty() || ch.group !in excluded) && !ADULT_GROUP.containsMatchIn(ch.group)
        when (section) {
            Section.HOME -> (index.newMovies.filter(::shows).take(4) + index.newSeries.filter(::shows).take(2))
                .ifEmpty { (index.movies.asSequence() + index.series.asSequence()).filter(::shows).take(5).toList() }
            Section.MOVIES, Section.SERIES -> {
                val kind = if (section == Section.MOVIES) Kind.MOVIE else Kind.SERIES
                val new = if (kind == Kind.MOVIE) index.newMovies else index.newSeries
                new.filter(::shows).take(6).ifEmpty { index.ofKind(kind).asSequence().filter(::shows).take(6).toList() }
            }
            else -> emptyList()
        }
    }
}

/** Which of [featured] is on show now. */
private fun marqueeSlot(vm: LibraryViewModel, section: Section, featured: List<Channel>): Int =
    if (featured.isEmpty()) 0 else (vm.marqueeIndex[section] ?: 0).mod(featured.size)

/**
 * A page (bars included) that can have the featured picture behind its top: the picture is drawn
 * first, and [content] over it. [landing] is the page showing it, when one is open (Home, or the
 * Films or Series front page: no group picked, nothing searched), else null.
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
    val cinema = isCinema()
    BoxWithConstraints(modifier.onGloballyPositioned { top[0] = it.positionInRoot().y }) {
        val height = maxHeight
        // TV, tablets and phones on their side: a wide picture; a phone held upright: its poster, edge to edge.
        val wide = tv || maxWidth >= 640.dp
        // A phone held upright: never taller than the poster shown across the width can fill (very tall screens).
        val portraitArt = minOf(height * marqueeFraction(height, portrait = true, cinema = cinema), maxWidth * (if (cinema) 1.55f else 1.3f))
        val info = remember(height, wide, portraitArt) { MarqueeStageInfo(height, wide, portraitArt) { top[0] } }
        if (landing != null) {
            MarqueeBackdropFor(
                vm, landing, excluded, portrait = !wide,
                modifier = Modifier.fillMaxWidth().height(if (wide) height * marqueeFraction(height, landing, tv = tv, cinema = cinema) else portraitArt),
            )
        }
        CompositionLocalProvider(LocalMarqueeStage provides info) { content() }
    }
}

/** What the picture behind the top of a page is drawn from (it cross-fades when any of it changes). */
private data class ArtKey(val wide: String?, val tall: String?, val mode: ArtMode, val name: String)

/** How the picture behind the top of a page is drawn. */
private enum class ArtMode {
    /** Wide art filling the width; with a poster only, the poster standing on the right. */
    POSTER,

    /** TV: with a poster only, a wide slice of it on the right. */
    BAND,

    /** A phone held upright: the poster, edge to edge. */
    PORTRAIT,

    /** A channel: its logo on a tile on the right. */
    LOGO,
}

/** The picture of the title the page is showing (TV: of the card the remote is on). */
@Composable
private fun MarqueeBackdropFor(vm: LibraryViewModel, section: Section, excluded: Set<String>, portrait: Boolean, modifier: Modifier) {
    val tv = isTv()
    val featured = rememberMarqueeFeatured(vm, section, excluded)
    val item = (if (tv) vm.spotlight[section] else null) ?: featured.getOrNull(marqueeSlot(vm, section, featured)) ?: return
    MarqueeBackdrop(
        backdrop = vm.heroArt[item.id]?.takeIf { it.isNotBlank() },
        poster = item.logo.ifBlank { null },
        name = item.name,
        list = vm.marqueeStateFor(section),
        mode = when {
            item.kind == Kind.LIVE -> ArtMode.LOGO
            portrait -> ArtMode.PORTRAIT
            tv -> ArtMode.BAND
            else -> ArtMode.POSTER
        },
        // TV: the spotlight stays put, so its picture does too.
        scrolls = !tv,
        modifier = modifier,
    )
}

/**
 * The picture behind the top of the page (see [ArtMode]). Where the featured title scrolls with
 * the page ([scrolls]), it fades as the page scrolls past it ([list]).
 */
@Composable
private fun MarqueeBackdrop(
    backdrop: String?,
    poster: String?,
    /** A channel's name, for its tile when it has no logo (or its link doesn't load). */
    name: String,
    list: androidx.compose.foundation.lazy.LazyListState,
    mode: ArtMode,
    scrolls: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier) {
        val h = maxHeight
        val fadePx = with(LocalDensity.current) { (h * 0.7f).toPx() }.coerceAtLeast(1f)
        // Where the picture starts fading into the page (a channel's tile sits whole above it).
        val fadeFrom = when (mode) {
            ArtMode.LOGO -> 0.64f
            ArtMode.PORTRAIT -> 0.5f
            else -> 0.45f
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Read while drawing: scrolling only redraws the picture.
                    alpha = when {
                        !scrolls -> 1f
                        list.firstVisibleItemIndex > 0 -> 0f
                        else -> (1f - list.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f)
                    }
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                // The lower part fades out into the page (whatever is behind it), with no edge.
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(fadeFrom to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
        ) {
            Crossfade(ArtKey(backdrop, poster, mode, name), animationSpec = tween(450), label = "marqueeArt") { (wide, tall, m, title) ->
                Box(Modifier.fillMaxSize()) {
                    // Decoded very small, so only its colours show (not smears of its lettering).
                    val wash = remember(tall) {
                        tall?.takeIf { it.isNotBlank() }?.let { ImageRequest.Builder(context).data(it).size(6, 9).memoryCacheKey("$it#tint").build() }
                    }
                    when {
                        m == ArtMode.LOGO -> {
                            // Logos are small and often see-through: on a tile, clear of the bar above it.
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 74.dp, end = 40.dp)
                                    .height(118.dp)
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Ink3),
                            ) {
                                LogoOr(tall.orEmpty(), null, ContentScale.Fit, Modifier.align(Alignment.Center).fillMaxSize(0.62f)) {
                                    Text(
                                        title,
                                        fontFamily = Serif,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 20.sp,
                                        lineHeight = 25.sp,
                                        color = Paper,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.align(Alignment.Center).padding(14.dp),
                                    )
                                }
                            }
                        }
                        // (A little down from its top: a poster's faces are rarely at the very top.)
                        m == ArtMode.PORTRAIT && !tall.isNullOrBlank() -> AsyncImage(
                            model = tall,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = BiasAlignment(0f, -0.5f),
                            modifier = Modifier.fillMaxSize(),
                        )
                        !wide.isNullOrBlank() -> AsyncImage(
                            model = wide,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = if (m == ArtMode.PORTRAIT) Alignment.Center else Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize(),
                        )
                        !tall.isNullOrBlank() && m == ArtMode.BAND -> {
                            // A poster only: a soft wash of it, and a wide slice of it on the right,
                            // fading in from the left so it has no edge.
                            AsyncImage(model = wash, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.5f, modifier = Modifier.fillMaxSize())
                            AsyncImage(
                                model = tall,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                alignment = BiasAlignment(0f, -0.5f),
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .fillMaxWidth(0.5f)
                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                    .drawWithContent {
                                        drawContent()
                                        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.4f to Color.Black), blendMode = BlendMode.DstIn)
                                    },
                            )
                        }
                        !tall.isNullOrBlank() -> {
                            // A poster only: a soft wash of it, and the whole poster sharp on the right.
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
            }
            if (mode == ArtMode.PORTRAIT) {
                // Dark at the top for the bar, and under the name and buttons.
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Ink.copy(alpha = 0.6f), 0.2f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.22f to Color.Transparent, 0.62f to Ink.copy(alpha = 0.94f))))
            } else {
                // Dark on the left for the words, and at the top for the bars.
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Ink.copy(alpha = 0.96f), 0.36f to Ink.copy(alpha = 0.78f), 0.66f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Ink.copy(alpha = 0.6f), 0.22f to Color.Transparent)))
            }
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
    val featured = rememberMarqueeFeatured(vm, section, excluded)
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
    val cinema = isCinema()
    MarqueePage(vm, section, featured, onPlay) {
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
                Box(Modifier.padding(top = if (tv) 10.dp else 16.dp)) {
                    ContinueRow(
                        continueOfKind, vm.heroArt, state.progress, me,
                        title = if (cinema) UP_NEXT else "Continue watching",
                        width = upNextWidth(cinema, tv),
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

/**
 * The page under a featured picture (drawn by the page frame, see [MarqueeStage]), then [rows].
 * TV: the spotlight (the card the remote is on, see Spotlight.kt) stays at the top and the rows
 * move under it, each sliding to the top as the remote reaches it. A tablet or a phone on its
 * side: the featured title over its picture, scrolling with the rows. A phone held upright: the
 * same over its poster, edge to edge.
 */
@Composable
fun MarqueePage(
    vm: LibraryViewModel,
    section: Section,
    featured: List<Channel>,
    onPlay: (List<Channel>, Channel) -> Unit,
    rows: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val tv = isTv()
    val cinema = isCinema()
    val listState = vm.marqueeStateFor(section)
    val scope = rememberCoroutineScope()
    val inheritedSpec = LocalBringIntoViewSpec.current
    val scroller = remember(listState, tv) { if (tv) HomeScroller(listState, scope, inheritedSpec) else null }
    val stage = LocalMarqueeStage.current
    val density = LocalDensity.current
    var myTop by remember { mutableFloatStateOf(-1f) }
    // The remote is on the featured title: it stays put (pressing OK plays what you're reading).
    var heroFocused by remember { mutableStateOf(false) }
    val onPrimary: (Channel) -> Unit = { ch ->
        when {
            ch.isSeriesFolder -> vm.openSeries(ch)
            // A channel (the Cinema panel's Watch): with its group, so the next and previous channel work.
            ch.kind == Kind.LIVE -> onPlay(vm.index.groupsOf(Kind.LIVE)[ch.group]?.takeIf { l -> l.any { it.id == ch.id } } ?: listOf(ch), ch)
            else -> onPlay(listOf(ch), ch)
        }
    }
    // TV: back from a film, its details or episodes, the remote goes back to the card picked
    // (only just set: an old one belongs to another visit).
    val returning = vm.returnFocus?.takeIf { tv && System.currentTimeMillis() - it.at < 5_000 }
    LaunchedEffect(vm.returnFocus) {
        if (vm.returnFocus != null) {
            delay(2_500)
            vm.returnFocus = null
        }
    }
    val rowStates = remember(vm, section) { { key: String -> vm.rowState(section, key) } }
    val tapMemory = remember(vm) { { row: String?, id: String -> vm.lastCard = ReturnFocus(row, id) } }
    // TV: the card the remote is on shows in the spotlight.
    val spotter: ((Channel) -> Unit)? = remember(vm, section, tv) {
        if (tv) ({ ch: Channel -> vm.spotlight[section] = ch }) else null
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { myTop = it.positionInRoot().y }) {
        val wide = stage?.wide == true
        val current = marqueeSlot(vm, section, featured)
        val item = featured.getOrNull(current)
        val spot = if (tv) vm.spotlight[section] else null
        // A series' details are a bigger download (every episode), so only the one on show.
        LaunchedEffect(item?.id) { if (item != null && item.isSeriesFolder) vm.loadHeroArt(listOf(item), series = true) }
        // TV: the story and wide picture of the card the remote rests on, once it has stayed a moment.
        LaunchedEffect(spot?.id) {
            val ch = spot ?: return@LaunchedEffect
            delay(if (ch.isSeriesFolder) 900 else 500)
            vm.loadHeroArt(listOf(ch), series = ch.isSeriesFolder)
        }
        // The hero's height: from the top of this list down to where the picture fades out.
        val heroHeight = if (stage == null) 0.dp else {
            val offset = if (myTop < 0) 110.dp else with(density) { (myTop - stage.top()).toDp() }
            val art = if (wide) stage.height * marqueeFraction(stage.height, section) else stage.portraitArt
            (art - offset).coerceAtLeast(200.dp)
        }
        if (item != null) {
            // A new title every 7 seconds while the featured one is in view: on a TV until the
            // remote is on a card, elsewhere unless its buttons have the focus.
            val heroInView = tv || listState.firstVisibleItemIndex == 0
            LaunchedEffect(current, featured.size, heroInView, heroFocused, spot == null) {
                if (featured.size > 1 && heroInView && !heroFocused && spot == null) {
                    delay(7_000)
                    vm.marqueeIndex[section] = (current + 1) % featured.size
                }
            }
        }

        CompositionLocalProvider(
            LocalHomeScroller provides scroller,
            LocalBringIntoViewSpec provides (scroller?.manual ?: inheritedSpec),
            LocalRowStates provides rowStates,
            LocalCardTap provides tapMemory,
            LocalReturnFocus provides returning,
            LocalSpotlight provides spotter,
        ) {
            if (tv) {
                Column(Modifier.fillMaxSize()) {
                    if (cinema) {
                        CinemaPanel(vm, spot ?: item, onPrimary, onFocus = { heroFocused = it })
                    } else {
                        SpotlightPanel(vm, spot ?: item)
                    }
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { scroller?.viewport = it },
                        state = listState,
                        contentPadding = PaddingValues(bottom = 40.dp),
                    ) {
                        rows()
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 40.dp),
                ) {
                    if (item != null) {
                        item(key = "hero") {
                            if (wide) {
                                MarqueeHero(
                                    item = item,
                                    info = vm.heroInfo[item.id],
                                    count = featured.size,
                                    current = current,
                                    height = heroHeight,
                                    onPrimary = onPrimary,
                                    onDetails = { ch -> vm.openDetails(ch) },
                                    onFocus = { heroFocused = it },
                                    compact = section == Section.HOME && (stage?.height ?: 0.dp) >= 480.dp,
                                )
                            } else {
                                PhoneHero(
                                    item = item,
                                    info = vm.heroInfo[item.id],
                                    count = featured.size,
                                    current = current,
                                    height = heroHeight,
                                    onCurrent = { vm.marqueeIndex[section] = it },
                                    onPrimary = onPrimary,
                                    onDetails = { ch -> vm.openDetails(ch) },
                                    onList = if (cinema) ({ ch -> vm.listSheetFor = ch }) else null,
                                )
                            }
                        }
                    }
                    rows()
                }
            }
        }
    }
}

/**
 * A phone held upright: the featured title's name, story and buttons over the lower part of its
 * picture (the page frame draws it edge to edge, behind the top bar too). A new title every 7
 * seconds, or swipe sideways for the next or the one before.
 */
@Composable
private fun PhoneHero(
    item: Channel,
    info: app.falak.player.data.VodInfo?,
    count: Int,
    current: Int,
    height: Dp,
    onCurrent: (Int) -> Unit,
    onPrimary: (Channel) -> Unit,
    onDetails: (Channel) -> Unit,
    /** The Cinema look: a My list button too (the buttons then have no icons but Play's, to fit). */
    onList: ((Channel) -> Unit)? = null,
) {
    val swipe = with(LocalDensity.current) { 56.dp.toPx() }
    val shown by rememberUpdatedState(current)
    val change by rememberUpdatedState(onCurrent)
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(count) {
                if (count > 1) {
                    var moved = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { moved = 0f },
                        onDragEnd = {
                            if (moved <= -swipe) {
                                change((shown + 1) % count)
                            } else if (moved >= swipe) {
                                change((shown - 1 + count) % count)
                            }
                        },
                    ) { _, dx -> moved += dx }
                }
            },
    ) {
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 4.dp)) {
            AnimatedContent(
                targetState = item to info,
                transitionSpec = { fadeIn(tween(300)).togetherWith(fadeOut(tween(150))) },
                label = "phoneHeroText",
            ) { (ch, details) ->
                Column(Modifier.fillMaxWidth()) {
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
                            Spacer(Modifier.width(8.dp))
                            CardBadge(it, Paper)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        ch.name,
                        fontFamily = Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                        lineHeight = 32.sp,
                        color = Paper,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(
                        ratingLabel(details?.rating?.takeIf { it.isNotBlank() } ?: ch.rating),
                        details?.year?.takeIf { it.isNotBlank() }?.take(4),
                        (details?.genre?.takeIf { it.isNotBlank() }?.substringBefore(',') ?: ch.group.takeIf { it.isNotBlank() }),
                        details?.duration?.takeIf { it.isNotBlank() },
                    ).joinToString("  ·  ")
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(meta, fontFamily = Sans, fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    details?.plot?.takeIf { it.isNotBlank() }?.let { plot ->
                        Spacer(Modifier.height(6.dp))
                        Text(plot, fontFamily = Sans, fontSize = 13.sp, lineHeight = 19.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // The buttons stay put while the titles change; they act on the one shown.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PaperButton(if (item.isSeriesFolder) "Episodes" else "Play", Icons.Filled.PlayArrow, Modifier.weight(1f)) { onPrimary(item) }
                if (onList == null) {
                    if (!item.isSeriesFolder) GhostButton("More info", Icons.Outlined.Info, Modifier.weight(1f)) { onDetails(item) }
                } else {
                    if (!item.isSeriesFolder) GhostButton("More info") { onDetails(item) }
                    GhostButton("My list") { onList(item) }
                }
            }
            if (count > 1) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(count) { i ->
                        Box(
                            Modifier
                                .width(if (i == current) 16.dp else 6.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (i == current) Paper else Paper.copy(alpha = 0.35f)),
                        )
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
    /** The remote is on the featured title's buttons (it stops changing meanwhile). */
    onFocus: (Boolean) -> Unit,
    /** Home: a shorter picture, so the name takes one line. */
    compact: Boolean = false,
) {
    val tv = isTv()
    val tap = LocalCardTap.current
    // Back from it: the remote goes to Play again.
    val back = LocalReturnFocus.current?.row == HERO_ROW
    Box(Modifier.fillMaxWidth().heightIn(min = height).pinToTop().onFocusChanged { onFocus(it.hasFocus) }) {
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
                        fontSize = if (compact) 36.sp else if (tv) 44.sp else 32.sp,
                        lineHeight = if (compact) 40.sp else if (tv) 48.sp else 36.sp,
                        color = Paper,
                        maxLines = if (compact) 1 else 2,
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
                PaperButton(
                    if (item.isSeriesFolder) "Episodes" else "Play",
                    Icons.Filled.PlayArrow,
                    Modifier.comeBackHere(HERO_ROW, item.id, also = back),
                ) {
                    tap?.invoke(HERO_ROW, item.id)
                    onPrimary(item)
                }
                if (!item.isSeriesFolder) {
                    GhostButton("More info", Icons.Outlined.Info) {
                        tap?.invoke(HERO_ROW, item.id)
                        onDetails(item)
                    }
                }
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

/** The Cinema look's name for the Continue watching row. */
const val UP_NEXT = "Up next"

/** How wide the Continue watching cards are: bigger in the Cinema look ("Up next"). */
fun upNextWidth(cinema: Boolean, tv: Boolean): Dp = when {
    cinema && tv -> 280.dp
    cinema -> 252.dp
    tv -> 220.dp
    else -> 180.dp
}

/** The group chips' row, and the "All" chip in it, for coming back to the one picked (see [ReturnFocus]). */
const val GROUP_ROW = "\u0000groups"
const val ALL_CHIP = "\u0000all"

/** The featured title's buttons, for coming back to them (see [ReturnFocus]). */
const val HERO_ROW = "\u0000hero"

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
    val rowState = LocalRowStates.current?.invoke(GROUP_ROW) ?: androidx.compose.foundation.lazy.rememberLazyListState()
    CompositionLocalProvider(LocalBringIntoViewSpec provides (scroller?.inner ?: LocalBringIntoViewSpec.current)) {
        LazyRow(
            Modifier.fillMaxWidth().pinToTop().padding(top = 6.dp),
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        ) {
            item(key = "__all") {
                TagChip("$all · ${countLabel(total)}", false, modifier = Modifier.comeBackHere(GROUP_ROW, ALL_CHIP)) { onGroup(null) }
            }
            items(names, key = { "g:$it" }) { g ->
                TagChip(if (g in locked) "🔒 $g" else g, false, onLongClick = { onOptions(g) }, modifier = Modifier.comeBackHere(GROUP_ROW, g)) { onGroup(g) }
            }
        }
    }
}

/** The playlist's top ten: big outlined numbers with the posters over their edge. */
@Composable
internal fun RankShelf(
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
