package com.example.neonplayer.sources.remote

sealed interface RemoteDeleteResult {
    data object Success : RemoteDeleteResult

    /** A conta configurada para o servidor não tem permissão de escrita para excluir este arquivo. */
    data object PermissionDenied : RemoteDeleteResult

    data class Error(val message: String) : RemoteDeleteResult
}
