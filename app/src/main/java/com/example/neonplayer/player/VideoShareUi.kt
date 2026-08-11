package com.example.neonplayer.player

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.neonplayer.R
import com.example.neonplayer.sources.ShareUiState

/**
 * Observa [state] ([com.example.neonplayer.sources.VideoShareController]) e reage: mostra um
 * diálogo de progresso enquanto um vídeo remoto é baixado para compartilhar, dispara o seletor de
 * apps do Android (`ACTION_SEND`) assim que o arquivo está pronto (local é imediato, remoto só
 * depois do download) e mostra um diálogo de erro se o download falhar.
 */
@Composable
fun ShareStateHandler(state: ShareUiState, onConsume: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current

    when (state) {
        is ShareUiState.Preparing -> {
            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text(stringResource(R.string.share_preparing_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.share_preparing_message))
                        val progress = state.progress
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
            )
        }

        is ShareUiState.Ready -> {
            LaunchedEffect(state) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = state.mimeType
                    putExtra(Intent.EXTRA_STREAM, state.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, state.displayName))
                onConsume()
            }
        }

        is ShareUiState.Failed -> {
            AlertDialog(
                onDismissRequest = onConsume,
                title = { Text(stringResource(R.string.share_error_title)) },
                text = { Text(state.message) },
                confirmButton = { TextButton(onClick = onConsume) { Text(stringResource(R.string.ok)) } },
            )
        }

        ShareUiState.Idle -> {}
    }
}
