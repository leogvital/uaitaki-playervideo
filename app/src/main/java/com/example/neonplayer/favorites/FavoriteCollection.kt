package com.example.neonplayer.favorites

/** Referência a uma pasta (de qualquer fonte) dentro de uma [FavoriteCollection]. */
data class CollectionFolderRef(val sourceId: String, val path: String, val label: String)

/**
 * Um "favorito de conjunto": várias pastas (de uma ou mais fontes) agrupadas sob um nome dado
 * pelo usuário. Ao contrário do favorito por vídeo ([FavoritesRepository]), aqui o que é salvo são
 * referências de pasta — os vídeos são resolvidos (recursivamente) toda vez que a coleção é
 * aberta, então incluem automaticamente qualquer vídeo novo adicionado depois às pastas.
 */
data class FavoriteCollection(val id: String, val name: String, val folders: List<CollectionFolderRef>)
