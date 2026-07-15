package com.wdtt.client.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun Modifier.reorderable(
    state: LazyListState,
    onMove: (Int, Int) -> Unit,
    onDragEnd: () -> Unit = {}
): Modifier = this.then(Modifier.pointerInput(Unit) {
    var draggedDistance = 0f
    var draggingItemInitial: LazyListItemInfo? = null
    var draggingItemCurrent: LazyListItemInfo? = null
    var overscrollJob: Job? = null

    fun checkForOverScroll(): Float {
        return draggingItemCurrent?.let {
            val startOffset = it.offset + draggedDistance
            val endOffset = it.offset + it.size + draggedDistance
            val viewPortStart = state.layoutInfo.viewportStartOffset
            val viewPortEnd = state.layoutInfo.viewportEndOffset
            when {
                draggedDistance > 0 -> (endOffset - viewPortEnd).coerceAtLeast(0f)
                draggedDistance < 0 -> (startOffset - viewPortStart).coerceAtMost(0f)
                else -> 0f
            }
        } ?: 0f
    }

    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                offset.y.toInt() in item.offset..(item.offset + item.size)
            }?.also {
                draggingItemInitial = it
                draggingItemCurrent = it
            }
        },
        onDragCancel = {
            draggedDistance = 0f
            draggingItemInitial = null
            draggingItemCurrent = null
            overscrollJob?.cancel()
            onDragEnd()
        },
        onDragEnd = {
            draggedDistance = 0f
            draggingItemInitial = null
            draggingItemCurrent = null
            overscrollJob?.cancel()
            onDragEnd()
        },
        onDrag = { change, dragAmount ->
            change.consume()
            draggedDistance += dragAmount.y
            val initial = draggingItemInitial ?: return@detectDragGesturesAfterLongPress
            val current = draggingItemCurrent ?: return@detectDragGesturesAfterLongPress

            val startOffset = initial.offset + draggedDistance
            val endOffset = initial.offset + initial.size + draggedDistance

            val targetItem = state.layoutInfo.visibleItemsInfo.find { item ->
                item.index != current.index &&
                item.index != 0 && // Prevent swapping with the "Create Profile" button if it's index 0
                (startOffset < item.offset + item.size / 2 && endOffset > item.offset + item.size / 2)
            }

            if (targetItem != null) {
                onMove(current.index, targetItem.index)
                draggingItemCurrent = targetItem
                draggedDistance += initial.offset - targetItem.offset
                draggingItemInitial = targetItem
            }

            // Overscroll logic could be implemented here, but keeping it simple for now
        }
    )
})
