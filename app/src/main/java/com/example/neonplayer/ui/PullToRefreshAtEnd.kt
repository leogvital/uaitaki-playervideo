package com.example.neonplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

private const val PULL_THRESHOLD_DP = 80

/**
 * Detecta o gesto de "puxar para atualizar" ao CHEGAR NO FIM da lista, não no topo (padrão usual
 * de pull-to-refresh, que já não se aplica aqui). O usuário rola até o último item e continua
 * arrastando além dele; isso dispara [onRefresh] uma única vez por gesto.
 *
 * Não existe um componente pronto do Compose/Material3 para esse gesto (o pull-to-refresh padrão
 * é ancorado no topo), então implementamos via [NestedScrollConnection]: acumulamos o quanto do
 * arraste (só [NestedScrollSource.Drag], não fling/momento) fica sem ser consumido pela lista
 * enquanto [canScrollForward] já é `false` — ou seja, exatamente o overscroll além do fim. Ao
 * ultrapassar [PULL_THRESHOLD_DP], chama [onRefresh] e zera o acumulador; o acumulador também
 * zera assim que a lista deixa de estar no fim ou quando o gesto termina (fling/solta o dedo).
 */
fun Modifier.pullToRefreshAtEnd(
    canScrollForward: () -> Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
): Modifier = composed {
    val density = LocalDensity.current
    val thresholdPx = remember(density) { with(density) { PULL_THRESHOLD_DP.dp.toPx() } }
    val canScrollForwardState = rememberUpdatedState(canScrollForward)
    val enabledState = rememberUpdatedState(enabled)
    val onRefreshState = rememberUpdatedState(onRefresh)
    var accumulatedPx by remember { mutableFloatStateOf(0f) }
    var triggered by remember { mutableStateOf(false) }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val atEnd = enabledState.value && source == NestedScrollSource.Drag && !canScrollForwardState.value()
                if (!atEnd) {
                    accumulatedPx = 0f
                    triggered = false
                    return Offset.Zero
                }
                if (available.y.absoluteValue > 0f) {
                    accumulatedPx += available.y.absoluteValue
                    if (!triggered && accumulatedPx >= thresholdPx) {
                        triggered = true
                        onRefreshState.value()
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                accumulatedPx = 0f
                triggered = false
                return Velocity.Zero
            }
        }
    }

    Modifier.nestedScroll(connection)
}
