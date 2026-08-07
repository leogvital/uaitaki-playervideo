package com.example.neonplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.neonplayer.R
import com.example.neonplayer.sources.SortDirection
import com.example.neonplayer.sources.SortField
import com.example.neonplayer.sources.SortOption

fun sortFieldLabel(field: SortField): Int = when (field) {
    SortField.NAME -> R.string.sort_by_name
    SortField.DATE -> R.string.sort_by_date
    SortField.SIZE -> R.string.sort_by_size
}

/**
 * Botão de ordenar com o mesmo comportamento em toda tela que lista vídeos: tocar no mesmo campo
 * inverte a direção, tocar em outro campo troca de campo (começando em ordem ascendente).
 */
@Composable
fun SortMenuButton(
    sortOption: SortOption,
    onSortOptionChange: (SortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(R.string.sort_videos),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortField.entries.forEach { field ->
                DropdownMenuItem(
                    text = { Text(stringResource(sortFieldLabel(field))) },
                    trailingIcon = {
                        if (sortOption.field == field) {
                            Icon(
                                imageVector = if (sortOption.direction == SortDirection.ASCENDING) {
                                    Icons.Filled.ArrowUpward
                                } else {
                                    Icons.Filled.ArrowDownward
                                },
                                contentDescription = null,
                            )
                        }
                    },
                    onClick = {
                        onSortOptionChange(
                            if (sortOption.field == field) {
                                sortOption.copy(
                                    direction = if (sortOption.direction == SortDirection.ASCENDING) {
                                        SortDirection.DESCENDING
                                    } else {
                                        SortDirection.ASCENDING
                                    },
                                )
                            } else {
                                SortOption(field, SortDirection.ASCENDING)
                            },
                        )
                        expanded = false
                    },
                )
            }
        }
    }
}
