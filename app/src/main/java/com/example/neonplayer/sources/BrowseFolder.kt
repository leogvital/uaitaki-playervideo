package com.example.neonplayer.sources

/**
 * Uma subpasta navegável dentro de uma fonte (local ou remota). [path] é o caminho a passar de
 * volta ao repositório da fonte para listar o conteúdo dessa pasta — a mesma convenção de caminho
 * usada por [PlayableVideo.videoId] daquela fonte (relativo à raiz configurada, não ao caminho
 * absoluto do sistema de arquivos).
 */
data class BrowseFolder(val name: String, val path: String)
