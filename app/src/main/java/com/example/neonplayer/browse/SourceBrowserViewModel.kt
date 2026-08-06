package com.example.neonplayer.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.neonplayer.sources.remote.RemoteCredentialStore
import com.example.neonplayer.sources.remote.RemoteServerConfig
import com.example.neonplayer.sources.remote.RemoteServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val serverRepository = RemoteServerRepository(application)
    private val credentialStore = RemoteCredentialStore(application)

    val servers: StateFlow<List<RemoteServerConfig>> = serverRepository.serversFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteServer(server: RemoteServerConfig) {
        viewModelScope.launch {
            serverRepository.deleteServer(server.id)
            withContext(Dispatchers.IO) { credentialStore.removePassword(server.id) }
        }
    }
}
