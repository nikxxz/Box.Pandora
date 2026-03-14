package com.example.boxpandora.ui.components.grid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.boxpandora.data.local.entity.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─── Grid packing algorithm ────────────────────────────────────────────────────

internal data class DynamicGridCell(val col: Int, val row: Int, val span: Int)

/**
 * Greedy bin-packing: places each item at the first free top-left position.
 * Items flagged as big (isFavorite) get a 2×2 slot when space allows.
 */
internal fun computeDynamicGridLayout(
    items: List<Pair<String, Boolean>>, // (uri, isBig)
    columns: Int = 3
): List<DynamicGridCell> {
    val occupied = HashSet<Long>()
    fun key(r: Int, c: Int): Long = r * 1000L + c
    fun isFree(r: Int, c: Int) = key(r, c) !in occupied
    fun markOccupied(r: Int, c: Int, span: Int) {
        for (dr in 0 until span) for (dc in 0 until span) occupied.add(key(r + dr, c + dc))
    }
    fun firstFree1x1(): Pair<Int, Int> {
        var r = 0
        while (true) { for (c in 0 until columns) { if (isFree(r, c)) return r to c }; r++ }
    }
    fun first2x2(fromRow: Int): Pair<Int, Int>? {
        for (r in fromRow until fromRow + 500)
            for (c in 0 until columns - 1)
                if (isFree(r, c) && isFree(r, c + 1) && isFree(r + 1, c) && isFree(r + 1, c + 1))
                    return r to c
        return null
    }
    return items.map { (_, isBig) ->
        if (isBig) {
            val (freeRow, _) = firstFree1x1()
            val pos = first2x2(freeRow)
            if (pos != null) { markOccupied(pos.first, pos.second, 2); DynamicGridCell(pos.second, pos.first, 2) }
            else { val (r, c) = firstFree1x1(); markOccupied(r, c, 1); DynamicGridCell(c, r, 1) }
        } else {
            val (r, c) = firstFree1x1(); markOccupied(r, c, 1); DynamicGridCell(c, r, 1)
        }
    }
}

// ─── Row grouping ─────────────────────────────────────────────────────────────
// Rows that contain a 2×2 block span two grid rows; those two rows are merged
// into a single RowGroup so the LazyColumn can virtualize them as one list item.

internal data class RowGroup(
    val startRow: Int,
    val rowCount: Int,
    val entries: List<Pair<Int, DynamicGridCell>> // itemIndex → cell
)

internal fun computeRowGroups(cells: List<DynamicGridCell>): List<RowGroup> {
    if (cells.isEmpty()) return emptyList()
    val totalRows = cells.maxOf { it.row + it.span }
    val bySRow = cells.mapIndexed { i, c -> i to c }.groupBy { it.second.row }
    val groups = mutableListOf<RowGroup>()
    var r = 0
    while (r < totalRows) {
        val cur = bySRow[r] ?: emptyList()
        if (cur.any { it.second.span == 2 }) {
            groups.add(RowGroup(r, 2, cur + (bySRow[r + 1] ?: emptyList())))
            r += 2
        } else {
            groups.add(RowGroup(r, 1, cur)); r++
        }
    }
    return groups
}

// ─── Composables ─────────────────────────────────────────────────────────────

@Composable
private fun RowGroupItem(
    group: RowGroup,
    allItems: List<MediaItem>,
    selectedUris: Set<String>,
    bigItemUris: Set<String>,
    cellSize: Dp,
    gap: Dp,
    onPress: (MediaItem, Int) -> Unit,
    onLongPress: (MediaItem) -> Unit
) {
    val groupHeight = cellSize * group.rowCount + gap * (group.rowCount - 1)
    Box(modifier = Modifier.fillMaxWidth().height(groupHeight + gap)) {
        group.entries.forEach { (idx, cell) ->
            val item = allItems.getOrNull(idx) ?: return@forEach
            val isFav = item.isFavorite == 1 || item.uri in bigItemUris
            val sz = cellSize * cell.span + gap * (cell.span - 1)
            val x  = (cellSize + gap) * cell.col
            val y  = (cellSize + gap) * (cell.row - group.startRow)
            Box(modifier = Modifier.offset(x = x, y = y).size(sz)) {
                MediaThumbnail(
                    uri         = item.uri,
                    filePath    = item.filePath,
                    mediaType   = item.mediaType,
                    duration    = item.duration,
                    isFavorite  = if (isFav) 1 else 0,
                    isSelected  = item.uri in selectedUris,
                    onPress     = { onPress(item, idx) },
                    onLongPress = { onLongPress(item) },
                    modifier    = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * A virtualized dynamic media grid where any item with [isFavorite == 1]
 * (or whose URI is in [bigItemUris]) is displayed as a 2×2 cell.
 * All other items flow into the remaining 1×1 slots.
 *
 * Layout is computed off the main thread and the LazyColumn renders only
 * the row groups visible on screen, so it handles 1k+ item collections safely.
 *
 * @param bigItemUris  Extra URIs to display as 2×2 independent of DB isFavorite
 *                     (used by the test screen for local-only overrides).
 * @param hint         Optional label rendered below the grid (e.g. a usage tip).
 */
@Composable
fun DynamicMediaGrid(
    items: List<MediaItem>,
    columns: Int = 3,
    gap: Dp = 2.dp,
    modifier: Modifier = Modifier,
    selectedUris: Set<String> = emptySet(),
    bigItemUris: Set<String> = emptySet(),
    hint: String? = null,
    onPress: (item: MediaItem, index: Int) -> Unit,
    onLongPress: (item: MediaItem) -> Unit
) {
    var rowGroups by remember { mutableStateOf(emptyList<RowGroup>()) }

    // Recompute layout off the main thread whenever items or big-set changes.
    LaunchedEffect(items, bigItemUris) {
        rowGroups = withContext(Dispatchers.Default) {
            val cells = computeDynamicGridLayout(
                items.map { it.uri to (it.isFavorite == 1 || it.uri in bigItemUris) },
                columns
            )
            computeRowGroups(cells)
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val cellSize = (maxWidth - gap * (columns - 1)) / columns
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rowGroups, key = { it.startRow }) { group ->
                RowGroupItem(
                    group        = group,
                    allItems     = items,
                    selectedUris = selectedUris,
                    bigItemUris  = bigItemUris,
                    cellSize     = cellSize,
                    gap          = gap,
                    onPress      = onPress,
                    onLongPress  = onLongPress
                )
            }
            if (hint != null) {
                item {
                    Text(
                        text      = hint,
                        style     = MaterialTheme.typography.labelSmall,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                    )
                }
            }
        }
    }
}
