package com.example.neonplayer.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TOUCH_SLOP_PX = 18f
private const val DOUBLE_TAP_TIMEOUT_MS = 300L

private enum class DragAxis { NONE, HORIZONTAL, VERTICAL }

/**
 * Detector de gestos do player, implementado à mão em vez de empilhar
 * detectTapGestures/detectDragGestures/detectTransformGestures porque os quatro gestos (duplo
 * toque, arraste horizontal de scrub, arraste vertical de brilho/volume e pinça de zoom) nascem do
 * mesmo toque na tela e precisam decidir entre si qual são, em vez de competir por consumir o
 * mesmo evento. A decisão de eixo (horizontal vs vertical) só acontece depois que o dedo passa de
 * [TOUCH_SLOP_PX], para não atrapalhar o duplo toque.
 */
fun Modifier.playerGestures(
    isZoomed: () -> Boolean,
    onSingleTap: () -> Unit,
    onDoubleTap: (isLeftSide: Boolean) -> Unit,
    onSeekDrag: (deltaPx: Float) -> Unit,
    onSeekDragEnd: () -> Unit,
    onVerticalDrag: (isLeftSide: Boolean, deltaPx: Float) -> Unit,
    onVerticalDragEnd: () -> Unit,
    onPan: (delta: Offset) -> Unit,
    onPinchZoom: (zoomChange: Float) -> Unit,
): Modifier = this.pointerInput(Unit) {
    coroutineScope {
    var lastTapTime = 0L
    var lastTapWasLeft = false
    var pendingSingleTapJob: Job? = null

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val isLeftSide = down.position.x < size.width / 2f
        var axis = DragAxis.NONE
        var totalDx = 0f
        var totalDy = 0f
        var pinching = false
        var prevDistance = 0f
        var prevMidpoint = Offset.Zero

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }

            if (pressed.size >= 2) {
                val sorted = pressed.sortedBy { it.id.value }
                val p0 = sorted[0]
                val p1 = sorted[1]
                val distance = (p0.position - p1.position).getDistance()
                val midpoint = (p0.position + p1.position) / 2f
                if (!pinching) {
                    pinching = true
                    axis = DragAxis.NONE
                } else {
                    if (prevDistance > 0f) onPinchZoom(distance / prevDistance)
                    onPan(midpoint - prevMidpoint)
                }
                prevDistance = distance
                prevMidpoint = midpoint
                event.changes.forEach { it.consume() }
            } else if (pressed.size == 1) {
                pinching = false
                val change = pressed[0]
                val delta = change.positionChange()

                if (isZoomed()) {
                    if (delta != Offset.Zero) {
                        onPan(delta)
                        change.consume()
                    }
                } else if (axis == DragAxis.NONE) {
                    totalDx += delta.x
                    totalDy += delta.y
                    if (abs(totalDx) > TOUCH_SLOP_PX || abs(totalDy) > TOUCH_SLOP_PX) {
                        axis = if (abs(totalDx) > abs(totalDy)) DragAxis.HORIZONTAL else DragAxis.VERTICAL
                        if (axis == DragAxis.HORIZONTAL) onSeekDrag(totalDx) else onVerticalDrag(isLeftSide, totalDy)
                        change.consume()
                    }
                } else if (axis == DragAxis.HORIZONTAL) {
                    if (delta.x != 0f) onSeekDrag(delta.x)
                    change.consume()
                } else if (axis == DragAxis.VERTICAL) {
                    if (delta.y != 0f) onVerticalDrag(isLeftSide, delta.y)
                    change.consume()
                }
            }

            if (pressed.isEmpty()) {
                when {
                    pinching -> Unit
                    axis == DragAxis.HORIZONTAL -> onSeekDragEnd()
                    axis == DragAxis.VERTICAL -> onVerticalDragEnd()
                    !isZoomed() -> {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < DOUBLE_TAP_TIMEOUT_MS && lastTapWasLeft == isLeftSide) {
                            pendingSingleTapJob?.cancel()
                            pendingSingleTapJob = null
                            lastTapTime = 0L
                            onDoubleTap(isLeftSide)
                        } else {
                            lastTapTime = now
                            lastTapWasLeft = isLeftSide
                            pendingSingleTapJob?.cancel()
                            pendingSingleTapJob = launch {
                                delay(DOUBLE_TAP_TIMEOUT_MS)
                                lastTapTime = 0L
                                onSingleTap()
                            }
                        }
                    }
                }
                break
            }
        }
    }
    }
}
