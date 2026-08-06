package com.example.neonplayer.sources.smb

/** Junta o subcaminho configurado para um servidor com o nome de um arquivo listado nele. */
internal fun joinSmbPath(basePath: String, fileName: String): String =
    if (basePath.isBlank()) fileName else "${basePath.trim('/')}/$fileName"

/**
 * Separa o campo "Caminho" digitado pelo usuário (ex: "Videos/Filmes") em nome do
 * compartilhamento ("Videos") e subcaminho dentro dele ("Filmes").
 */
internal fun splitShareAndPath(rawPath: String): Pair<String, String> {
    val trimmed = rawPath.trim().trim('/')
    val separatorIndex = trimmed.indexOf('/')
    return if (separatorIndex == -1) {
        trimmed to ""
    } else {
        trimmed.substring(0, separatorIndex) to trimmed.substring(separatorIndex + 1)
    }
}

/** Reconstrói o campo "Caminho" a partir de um [SmbServerConfig] já salvo, para edição. */
internal fun combineShareAndPath(shareName: String, sharePath: String): String =
    if (sharePath.isBlank()) shareName else "$shareName/$sharePath"
