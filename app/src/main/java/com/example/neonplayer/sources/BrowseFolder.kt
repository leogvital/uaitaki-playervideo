package com.example.neonplayer.sources

/**
 * Uma subpasta navegável dentro de uma fonte (local ou remota). [path] é o caminho a passar de
 * volta ao repositório da fonte para listar o conteúdo dessa pasta — a mesma convenção de caminho
 * usada por [PlayableVideo.videoId] daquela fonte (relativo à raiz configurada, não ao caminho
 * absoluto do sistema de arquivos).
 *
 * [isParent] marca uma entrada sintética "..", injetada pelo repositório (não retornada pelo
 * servidor) para permitir subir acima da raiz configurada do servidor (SFTP/FTP) — não é uma pasta
 * real, então a UI não deve deixá-la entrar no modo de seleção de pastas (criação de coleções).
 */
data class BrowseFolder(val name: String, val path: String, val isParent: Boolean = false)
