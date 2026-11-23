package com.gongpx.androidacpclient.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/** Reordering is a local preview until release; live chat data is never copied back. */
internal class ChatReorderState(
    val list: LazyListState,
    private val currentIds: () -> List<String>,
    private val commit: (List<String>) -> Unit,
) {
    var draggedId by mutableStateOf<String?>(null)
        private set
    private var preview by mutableStateOf<List<String>?>(null)
    private var top by mutableFloatStateOf(0f)
    private var pointerY by mutableFloatStateOf(0f)
    private var direction = 0f

    val ids: List<String> get() {
        val current = currentIds()
        val known = current.toSet()
        return preview?.let { (it.filter(known::contains) + current.filterNot(it.toSet()::contains)) } ?: current
    }

    fun start(position: Offset): Boolean {
        val available = currentIds()
        val y = position.y - list.layoutInfo.beforeContentPadding
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull {
            y >= it.offset && y < it.offset + it.size && it.key in available
        } ?: return false
        draggedId = item.key as String
        preview = available
        top = item.offset.toFloat()
        pointerY = y
        direction = 0f
        return true
    }

    fun drag(delta: Float) {
        if (draggedId == null) return
        top += delta
        pointerY += delta
        direction = delta
        moveAcrossRows()
    }

    fun translation(id: String): Float =
        if (id == draggedId) top - (list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset?.toFloat() ?: top)
        else 0f

    private fun moveAcrossRows() {
        val id = draggedId ?: return
        val ordered = ids
        val from = ordered.indexOf(id)
        if (from < 0) { finish(cancelled = true); return }
        val visible = list.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.key == id } ?: return
        // Wait for the previous move's layout before using row coordinates again.
        val visibleKeys = visible.mapNotNull { (it.key as? String)?.takeIf(ordered::contains) }
        if (visibleKeys != ordered.filter(visibleKeys.toSet()::contains)) return
        val center = top + item.size / 2f
        val target = visible.filter { it.key != id && it.key in ordered }.lastOrNull {
            val to = ordered.indexOf(it.key)
            if (direction > 0) to > from && center > it.offset + it.size / 2f
            else false
        } ?: visible.firstOrNull {
            direction < 0 && it.key in ordered && ordered.indexOf(it.key) < from && center < it.offset + it.size / 2f
        } ?: return
        val next = ordered.toMutableList()
        next.add(ordered.indexOf(target.key), next.removeAt(from))
        // Keep the viewport, rather than following the moved first-visible item's key.
        list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        preview = next
    }

    suspend fun scrollAtEdge(edge: Float, maxStep: Float) {
        if (draggedId == null) return
        val layout = list.layoutInfo
        val start = layout.viewportStartOffset + layout.beforeContentPadding
        val end = layout.viewportEndOffset - layout.afterContentPadding
        val step = when {
            pointerY < start + edge -> -maxStep * ((start + edge - pointerY) / edge).coerceIn(0f, 1f)
            pointerY > end - edge -> maxStep * ((pointerY - end + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        if (step != 0f) {
            list.scrollBy(step)
            direction = step
        }
        moveAcrossRows()
    }

    fun finish(cancelled: Boolean) {
        val next = ids
        val changed = draggedId != null && draggedId in next && next != currentIds()
        draggedId = null
        preview = null
        if (!cancelled && changed) commit(next)
    }

    fun move(id: String, delta: Int): Boolean {
        if (draggedId != null) return false
        val next = currentIds().toMutableList()
        val from = next.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in next.indices) return false
        next.add(to, next.removeAt(from))
        commit(next)
        return true
    }
}

@Composable
internal fun rememberChatReorderState(
    ids: List<String>,
    list: LazyListState,
    onReorder: (List<String>) -> Unit,
): ChatReorderState {
    val latestIds = rememberUpdatedState(ids)
    val latestCommit = rememberUpdatedState(onReorder)
    val state = remember(list) { ChatReorderState(list, { latestIds.value }, { latestCommit.value(it) }) }
    val density = LocalDensity.current
    LaunchedEffect(ids) {
        if (state.draggedId != null && state.draggedId !in ids) state.finish(cancelled = true)
    }
    LaunchedEffect(state.draggedId, density) {
        if (state.draggedId == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (coroutineContext.isActive) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
            previous = now
            state.scrollAtEdge(with(density) { 64.dp.toPx() }, with(density) { 600.dp.toPx() } * seconds)
        }
    }
    return state
}

@Composable
internal fun Modifier.reorderChats(state: ChatReorderState): Modifier {
    val haptics = LocalHapticFeedback.current
    return pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            if (!state.start(held.position)) return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            try {
                while (state.draggedId != null) {
                    // Claim the held gesture before the list's scroll/swipe handlers.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == held.id } ?: break
                    if (change.isConsumed || event.changes.any { it.id != held.id && it.pressed }) break
                    if (!change.pressed) {
                        change.consume()
                        state.finish(cancelled = false)
                        break
                    }
                    state.drag(change.positionChange().y)
                    change.consume()
                }
            } finally {
                state.finish(cancelled = true)
            }
        }
    }
}
