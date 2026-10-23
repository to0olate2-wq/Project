package app.falak.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.R
import kotlinx.coroutines.delay
import java.util.Date

/** The page: theme background, a soft pool of light at the top, and a faint film grain. */
@Composable
fun Backdrop(modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val grain = ImageBitmap.imageResource(R.drawable.grain)
    val brush = remember(grain) { ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated)) }
    val glow = if (pal.dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.7f)
    // The grain is a full-screen layer drawn on every frame: skip it where the graphics chip is weak.
    val context = LocalContext.current
    val tv = isTv()
    val withGrain = remember(tv, pal) {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        pal.grain > 0f && !tv && !am.isLowRamDevice
    }
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        drawRect(pal.ink)
        drawRect(
            Brush.radialGradient(
                listOf(glow, Color.Transparent),
                center = Offset(size.width * 0.25f, 0f),
                radius = maxOf(size.width, size.height) * 0.75f,
            ),
        )
        if (withGrain) drawRect(brush, alpha = pal.grain)
    }
}

/** The "P" mark (counter = play button) on its ink tile. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
            .background(Color(0xFF211E1C))
            .border(1.dp, Hairline, RoundedCornerShape(size * 0.24f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "The Player",
            modifier = Modifier.size(size * 1.55f),
        )
    }
}

/** "The Player•" — italic article, upright name, a signal-red full stop. */
@Composable
fun Wordmark(size: Int = 22) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            "The ",
            fontFamily = Serif,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Medium,
            fontSize = (size * 0.66f).sp,
            color = Muted,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(bottom = (size * 0.12f).dp),
        )
        Text("Player", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = size.sp, color = Paper, maxLines = 1, softWrap = false)
        Box(
            Modifier
                .padding(start = (size * 0.08f).dp, bottom = (size * 0.24f).dp)
                .size((size * 0.2f).dp)
                .background(Signal, CircleShape),
        )
    }
}

@Composable
fun Clock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val format = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    Text(format.format(Date(now)), style = Tabular.copy(fontSize = 15.sp), color = Muted, maxLines = 1, softWrap = false, modifier = modifier)
}

/** Small red caps line above titles, like a newspaper kicker. */
@Composable
fun Kicker(text: String, color: Color = Signal) {
    Text(
        text.uppercase(),
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 1.6.sp,
        color = color,
    )
}

/** Text tabs; a short red rule slides under the selected one. */
@Composable
fun NavTabs(items: List<Section>, selected: Section, onSelect: (Section) -> Unit, large: Boolean = false) {
    val density = LocalDensity.current
    val positions = remember { mutableStateMapOf<Section, Pair<Dp, Dp>>() }
    val target = positions[selected]
    val x by animateDpAsState(target?.first ?: 0.dp, tween(320, easing = FastOutSlowInEasing), label = "tabX")
    val w by animateDpAsState(target?.second ?: 0.dp, tween(320, easing = FastOutSlowInEasing), label = "tabW")
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(if (large) 30.dp else 22.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            items.forEach { s ->
                val sel = s == selected
                val color by animateColorAsState(if (sel) Paper else Muted, tween(250), label = "tabColor")
                Text(
                    s.label,
                    fontFamily = Sans,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = if (large) 18.sp else 15.sp,
                    color = color,
                    modifier = Modifier
                        .onGloballyPositioned { c ->
                            with(density) { positions[s] = c.positionInParent().x.toDp() to c.size.width.toDp() }
                        }
                        .focusRing(RoundedCornerShape(6.dp))
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSelect(s) }
                        .padding(horizontal = 2.dp, vertical = 8.dp),
                )
            }
        }
        if (target != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = x + w / 2 - 9.dp)
                    .width(18.dp)
                    .height(3.dp)
                    .background(Signal, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** Main action: paper on ink. */
@Composable
fun PaperButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .focusRing(shape)
            .clip(shape)
            .background(Paper)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = Ink, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** Secondary action: hairline outline. */
@Composable
fun GhostButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .focusRing(shape)
            .clip(shape)
            .border(1.dp, Paper.copy(alpha = 0.35f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Paper, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = Paper, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    }
}

/** Filter chip: outline, or filled paper when selected. Long-press for options. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TagChip(text: String, selected: Boolean, onLongClick: (() -> Unit)? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val bg by animateColorAsState(if (selected) Paper else Color.Transparent, tween(220), label = "chipBg")
    val fg by animateColorAsState(if (selected) Ink else Paper, tween(220), label = "chipFg")
    Text(
        text,
        color = fg,
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = modifier
            .focusRing(shape)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else Paper.copy(alpha = 0.28f), shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Serif section title with an optional count and "See all". */
@Composable
fun SectionHeader(title: String, onSeeAll: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Always one line: a long provider group name ("TOP MOVIES 4K 3840P DOLBY AUDIO") is cut
        // short instead of squeezing "See all" into a tall column that left a gap under the row.
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = Paper,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onSeeAll != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                "See all  →",
                color = Muted,
                fontFamily = Sans,
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .focusRing(RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/** Thin rule used to separate list rows. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Hairline))
}

@Composable
fun LiveDot(size: Dp = 7.dp) {
    Box(Modifier.size(size).background(Signal, CircleShape))
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/**
 * A page's title row: back arrow, title (and a small line under it), optional actions on the
 * right. The arrow and the action icons sit on the page's edges (their touch areas reach past
 * them), so the header lines up with the content below it.
 */
@Composable
fun PageHeader(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Paper,
            )
        }
        Column(Modifier.weight(1f).offset(x = (-10).dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = Paper,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(subtitle, fontFamily = Sans, fontSize = 12.sp, color = Faint, maxLines = 1)
        }
        Row(Modifier.offset(x = 12.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Side margin of full-width pages: wide on TV (TV safe area), narrow on phones. */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
fun pageMargin(): androidx.compose.ui.unit.Dp = if (isTv()) 40.dp else 16.dp

/** Choice chips that wrap onto more lines when they don't fit (every option stays in view). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) { content() }
}
