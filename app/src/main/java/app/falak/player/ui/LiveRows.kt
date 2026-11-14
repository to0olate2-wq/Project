package app.falak.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.ADULT_GROUP
import app.falak.player.data.Channel
import app.falak.player.data.Epg
import app.falak.player.data.GroupFolders
import app.falak.player.data.Program
import app.falak.player.data.isDividerName

/*
 * Live TV's front page (8.21; the owner showed another app's page and asked for Live TV "matching
 * movie and series way of showing content"): rows, as on Films and Series. First your favorites
 * and the channels often on at this time, then a row for every group (the ones watched lately
 * first) with how many channels it has and "See all", which opens the group as the list (what's on
 * now, time left). With folders (GroupFolders), they are chips over the rows: one picked, only its
 * groups' rows show. No group picked is this page; "All channels" is the list of every channel.
 */

/** The rows of your own channels, for coming back to a card in them (see [ReturnFocus]). */
const val LIVE_FAV_ROW = "\u0000live-fav"
const val LIVE_OFTEN_ROW = "\u0000live-often"

/** A row shows this many channels; the rest are behind "See all". */
private const val ROW_MAX = 24

@Composable
internal fun LiveRows(
    vm: LibraryViewModel,
    groups: Map<String, List<Channel>>,
    tree: GroupFolders.Tree,
    total: Int,
    favorites: List<Channel>,
    often: List<Channel>,
    /** The groups watched lately: their rows come first. */
    lately: List<String>,
    locked: Set<String>,
    /** Channels that didn't work lately (see DeadChannels): not in the groups' rows. */
    dead: Set<String>,
    /** TV: the channels the top of the page tells about until the remote is on a card. */
    featured: List<Channel>,
    epg: Map<String, List<Program>>,
    now: Long,
    onPlay: (List<Channel>, Channel) -> Unit,
    /** Open a group as the list (null: every channel). */
    onGroup: (String?) -> Unit,
    onFind: (() -> Unit)?,
    onLongPress: (Channel) -> Unit,
) {
    val tv = isTv()
    val folder = vm.liveFolder?.let { f -> tree.folders.firstOrNull { it.name == f } }
    val rows = remember(groups, folder, lately) {
        if (folder != null) {
            folder.groups.map { it.group }.filter { it in groups }
        } else {
            lately.filter { it in groups } + groups.keys.filter { it !in lately }
        }
    }
    val favShown = remember(favorites) { favorites.take(ROW_MAX) }
    // (A phone held upright shows three channels of a row and the edge of the fourth.)
    val width = if (tv) 168.dp else 118.dp
    val card: @Composable (Channel, List<Channel>) -> Unit = { ch, row ->
        ChannelCard(
            ch, Epg.current(epg[ch.epgKey], now), now,
            // (Zapping from it skips the ones that don't work, too.)
            onClick = { onPlay(if (dead.isEmpty()) row else row.filter { it.id !in dead || it.id == ch.id }, ch) },
            width = width,
            onLongClick = { onLongPress(ch) },
            plain = true,
        )
    }
    MarqueePage(vm, Section.LIVE, featured, onPlay, hero = false) {
        item(key = "chips") {
            LiveChips(tree, total, folder?.name, onAll = { onGroup(null) }, onFind = onFind) { vm.liveFolder = it }
        }
        if (folder == null && favShown.isNotEmpty()) {
            item(key = "fav") {
                Shelf("Favorites", onSeeAll = { onGroup(FAV_FILTER) }, key = LIVE_FAV_ROW, count = countLabel(favorites.size)) {
                    items(favShown, key = { it.id }) { ch -> card(ch, favorites) }
                }
            }
        }
        if (folder == null && often.isNotEmpty()) {
            item(key = "often") {
                Shelf("Often on at this time", key = LIVE_OFTEN_ROW) {
                    items(often, key = { it.id }) { ch -> card(ch, often) }
                }
            }
        }
        items(rows, key = { "g:$it" }) { name ->
            val isLocked = name in locked
            GroupRow(
                name = name,
                // Inside a folder its name is on the chip already.
                title = if (folder != null) tree.label(name) else tree.title(name),
                all = groups[name].orEmpty(),
                locked = isLocked,
                // (Adult groups show no logos on the page, as on Films and Series.)
                closed = isLocked || ADULT_GROUP.containsMatchIn(name),
                dead = dead,
                width = width,
                onGroup = onGroup,
                card = card,
            )
        }
    }
}

/** "Find a group", "All channels", then the folders: one picked shows only its groups' rows. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LiveChips(
    tree: GroupFolders.Tree,
    total: Int,
    folder: String?,
    onAll: () -> Unit,
    onFind: (() -> Unit)?,
    onFolder: (String?) -> Unit,
) {
    val scroller = LocalHomeScroller.current
    val rowState = LocalRowStates.current?.invoke(GROUP_ROW) ?: rememberLazyListState()
    CompositionLocalProvider(LocalBringIntoViewSpec provides (scroller?.inner ?: LocalBringIntoViewSpec.current)) {
        LazyRow(
            Modifier.fillMaxWidth().pinToTop().padding(top = 6.dp),
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        ) {
            item(key = "__all") {
                TagChip("All channels · ${countLabel(total)}", false, modifier = Modifier.comeBackHere(GROUP_ROW, ALL_CHIP), onClick = onAll)
            }
            if (onFind != null) {
                item(key = "__find") { FindChip(RoundedCornerShape(50), onFind) }
            }
            items(tree.folders, key = { "f:" + it.name }) { f ->
                TagChip(f.name, folder == f.name) { onFolder(if (folder == f.name) null else f.name) }
            }
        }
    }
}

/** One group: its name, how many channels, "See all", and its first channels (never the provider's divider lines). */
@Composable
private fun GroupRow(
    name: String,
    title: String,
    all: List<Channel>,
    locked: Boolean,
    closed: Boolean,
    dead: Set<String>,
    width: Dp,
    onGroup: (String?) -> Unit,
    card: @Composable (Channel, List<Channel>) -> Unit,
) {
    val shown = remember(all, closed, dead) {
        if (closed) emptyList() else all.asSequence().filterNot { isDividerName(it.name) || it.id in dead }.take(ROW_MAX).toList()
    }
    Shelf(
        if (locked) "🔒 $title" else title,
        onSeeAll = { onGroup(name) },
        key = name,
        count = if (locked) null else countLabel(all.size),
    ) {
        items(shown, key = { it.id }) { ch -> card(ch, all) }
        if (closed || all.size > shown.size) {
            item(key = "__all") {
                OpenTile(if (locked) "Locked" else if (closed) "Open" else "See all ${countLabel(all.size)}", width) { onGroup(name) }
            }
        }
    }
}

/** A tile the size of a channel's that opens the whole group. */
@Composable
private fun OpenTile(text: String, width: Dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.width(width)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).cardFocus(shape).clip(shape).background(Ink2).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$text  →",
                fontFamily = Sans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Paper,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}
