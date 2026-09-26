package com.fourkplus.tvplayer

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Up and Down through a remote-driven LazyColumn, one row per press, decided by position in the
 * list rather than by Compose's focus search.
 *
 * Left to itself, the focus search looks for the nearest focusable thing below or above on screen.
 * That works while the next row is already laid out. At the edge of a lazy list it is not - the
 * list only composes what is visible - and with a held key the presses arrive faster than the
 * list's animated scroll can bring the next row in. The search then finds nothing in the list and
 * takes the nearest thing outside it instead: the search box above the categories, the season
 * chips above the episodes, the poster grid beside them. That is the jump the owner described as
 * glitchy, and a held Down could also stall while the scroll animation restarted on every press.
 *
 * Here the next row is always index + 1. If it is on screen it is focused at once; if not, the
 * list is scrolled by one row's height without animation, and it is focused on the next frame.
 * Presses that arrive while that frame is pending count from the row being moved to, so a held key
 * never repeats a row or skips one. The last row keeps focus on Down rather than letting it fall
 * out of the list; Up from the first row is the caller's to decide.
 */
internal class DpadListStepper(private val state: LazyListState, private val scope: CoroutineScope) {
    private val requesters = HashMap<Int, FocusRequester>()
    private var focusedIndex = -1
    private var pending: Job? = null
    private var pendingTarget = -1

    private fun requester(index: Int) = requesters.getOrPut(index) { FocusRequester() }

    /** On each row, at [index] in the list. Must sit before the row's own focusable. */
    fun row(index: Int): Modifier = Modifier
        .onFocusChanged { if (it.hasFocus) focusedIndex = index }
        .focusRequester(requester(index))

    /** Focuses [index], scrolling to it first if it is not composed. */
    fun focus(index: Int) {
        pending?.cancel()
        if (state.layoutInfo.visibleItemsInfo.any { it.index == index } &&
            runCatching { requester(index).requestFocus() }.isSuccess
        ) {
            pending = null
            return
        }
        pendingTarget = index
        pending = scope.launch {
            runCatching { state.scrollToItem(index) }
            withFrameNanos { }
            runCatching { requester(index).requestFocus() }
        }
    }

    /**
     * One key. True when it was used. [count] is the number of rows in the list. [nextKey] and
     * [previousKey] are Down and Up for a column; a row passes Right and Left, swapped in a
     * right-to-left language, where the row runs the other way.
     */
    fun onKey(
        event: KeyEvent,
        count: Int,
        onUpFromFirst: (() -> Boolean)? = null,
        nextKey: Key = Key.DirectionDown,
        previousKey: Key = Key.DirectionUp
    ): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val step = when (event.key) {
            nextKey -> 1
            previousKey -> -1
            else -> return false
        }
        val from = if (pending?.isActive == true) pendingTarget else focusedIndex
        if (from < 0 || from >= count) return false
        val target = from + step
        if (target < 0) return onUpFromFirst?.invoke() ?: false
        if (target >= count) return true
        pending?.cancel()
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.any { it.index == target } && runCatching { requester(target).requestFocus() }.isSuccess) {
            pending = null
            return true
        }
        pendingTarget = target
        pending = scope.launch {
            // One row's height (or one item's width, in a row), so the list creeps along with the
            // ring instead of jumping the target to the start the way scrollToItem would.
            val rowHeight = visible.firstOrNull()?.size?.plus(state.layoutInfo.mainAxisItemSpacing)
            if (rowHeight != null) runCatching { state.scrollBy((rowHeight * step).toFloat()) }
            withFrameNanos { }
            if (state.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                // Still out of reach - a long press can outrun even this. Go straight there.
                runCatching { state.scrollToItem(target) }
                withFrameNanos { }
            }
            runCatching { requester(target).requestFocus() }
        }
        return true
    }
}

@Composable
internal fun rememberDpadListStepper(state: LazyListState): DpadListStepper {
    val scope = rememberCoroutineScope()
    return remember(state) { DpadListStepper(state, scope) }
}

/** The list-level half of [DpadListStepper]: put on the LazyColumn itself. Off when [enabled] is false. */
internal fun Modifier.dpadListSteps(
    stepper: DpadListStepper,
    count: Int,
    enabled: Boolean = true,
    onUpFromFirst: (() -> Boolean)? = null
): Modifier = if (!enabled) this else onPreviewKeyEvent { stepper.onKey(it, count, onUpFromFirst) }
