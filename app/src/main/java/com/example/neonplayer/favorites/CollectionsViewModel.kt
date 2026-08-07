package com.example.neonplayer.favorites

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class CollectionsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FavoriteCollectionsRepository(application)

    val collections = repository.collectionsFlow

    fun deleteCollection(id: String) {
        viewModelScope.launch { repository.deleteCollection(id) }
    }
}
