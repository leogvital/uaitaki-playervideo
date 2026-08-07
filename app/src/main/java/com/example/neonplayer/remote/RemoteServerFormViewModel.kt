package com.example.neonplayer.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.neonplayer.R
import com.example.neonplayer.sources.remote.RemoteCredentialStore
import com.example.neonplayer.sources.remote.RemoteServerConfig
import com.example.neonplayer.sources.remote.RemoteServerRepository
import com.example.neonplayer.sources.remote.ServerProtocol
import com.example.neonplayer.sources.smb.SmbShareListResult
import com.example.neonplayer.sources.smb.listSmbShares
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RemoteServerFormUiState(
    val id: String = "",
    val protocol: ServerProtocol = ServerProtocol.SMB,
    val name: String = "",
    val host: String = "",
    val port: String = ServerProtocol.SMB.defaultPort.toString(),
    val username: String = "",
    val password: String = "",
    val path: String = "",
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val isListingShares: Boolean = false,
    val availableShares: List<String> = emptyList(),
    val shareListError: String? = null,
)

private val DEFAULT_PORTS = ServerProtocol.entries.map { it.defaultPort.toString() }.toSet()

class RemoteServerFormViewModel(application: Application) : AndroidViewModel(application) {

    private val serverRepository = RemoteServerRepository(application)
    private val credentialStore = RemoteCredentialStore(application)

    private val _uiState = MutableStateFlow(RemoteServerFormUiState())
    val uiState: StateFlow<RemoteServerFormUiState> = _uiState.asStateFlow()

    private val _saved = MutableSharedFlow<Unit>()
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    private var loadedServerId: String? = null

    fun load(serverId: String?) {
        if (serverId == null || serverId == loadedServerId) return
        loadedServerId = serverId
        viewModelScope.launch {
            val server = withContext(Dispatchers.IO) { serverRepository.getServer(serverId) } ?: return@launch
            val password = withContext(Dispatchers.IO) { credentialStore.getPassword(serverId) }.orEmpty()
            _uiState.value = RemoteServerFormUiState(
                id = server.id,
                protocol = server.protocol,
                name = server.name,
                host = server.host,
                port = server.port.toString(),
                username = server.username,
                password = password,
                path = server.path,
            )
        }
    }

    fun updateProtocol(protocol: ServerProtocol) {
        val current = _uiState.value
        val newPort = if (current.port.isBlank() || current.port in DEFAULT_PORTS) {
            protocol.defaultPort.toString()
        } else {
            current.port
        }
        _uiState.value = current.copy(protocol = protocol, port = newPort)
    }

    fun updateName(value: String) {
        _uiState.value = _uiState.value.copy(name = value)
    }

    fun updateHost(value: String) {
        _uiState.value = _uiState.value.copy(host = value)
    }

    fun updatePort(value: String) {
        _uiState.value = _uiState.value.copy(port = value.filter { it.isDigit() })
    }

    fun updateUsername(value: String) {
        _uiState.value = _uiState.value.copy(username = value)
    }

    fun updatePassword(value: String) {
        _uiState.value = _uiState.value.copy(password = value)
    }

    fun updatePath(value: String) {
        _uiState.value = _uiState.value.copy(path = value, availableShares = emptyList(), shareListError = null)
    }

    /**
     * Lista os compartilhamentos do servidor via SRVSVC ([listSmbShares]) usando host/porta/usuário
     * já preenchidos no formulário, sem exigir que o servidor já esteja salvo. Best-effort: em caso
     * de erro (servidor bloqueia SRVSVC, protocolo não suportado, etc.) mostra a mensagem e mantém
     * a entrada manual do caminho disponível normalmente.
     */
    fun listShares() {
        val state = _uiState.value
        val port = state.port.toIntOrNull()
        if (state.host.isBlank() || port == null) {
            _uiState.value = state.copy(shareListError = getApplication<Application>().getString(R.string.remote_form_invalid))
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isListingShares = true, shareListError = null, availableShares = emptyList())
            val config = RemoteServerConfig(
                id = state.id,
                protocol = ServerProtocol.SMB,
                name = state.name,
                host = state.host.trim(),
                port = port,
                username = state.username.trim(),
                path = "",
            )
            when (val result = listSmbShares(config, state.password)) {
                is SmbShareListResult.Success -> _uiState.value = _uiState.value.copy(
                    isListingShares = false,
                    availableShares = result.shareNames,
                    shareListError = if (result.shareNames.isEmpty()) {
                        getApplication<Application>().getString(R.string.remote_smb_no_shares_found)
                    } else {
                        null
                    },
                )

                is SmbShareListResult.Error -> _uiState.value = _uiState.value.copy(
                    isListingShares = false,
                    shareListError = result.message,
                )
            }
        }
    }

    fun selectShare(shareName: String) {
        _uiState.value = _uiState.value.copy(path = shareName, availableShares = emptyList())
    }

    fun dismissShareList() {
        _uiState.value = _uiState.value.copy(availableShares = emptyList())
    }

    fun save() {
        val state = _uiState.value
        val port = state.port.toIntOrNull()
        if (state.host.isBlank() || port == null) {
            _uiState.value = state.copy(
                errorMessage = getApplication<Application>().getString(R.string.remote_form_invalid),
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true, errorMessage = null)
            val id = state.id.ifBlank { UUID.randomUUID().toString() }
            val config = RemoteServerConfig(
                id = id,
                protocol = state.protocol,
                name = state.name.ifBlank { state.host }.trim(),
                host = state.host.trim(),
                port = port,
                username = state.username.trim(),
                path = state.path.trim(),
            )
            withContext(Dispatchers.IO) {
                serverRepository.saveServer(config)
                credentialStore.savePassword(id, state.password)
            }
            _uiState.value = _uiState.value.copy(isSaving = false)
            _saved.emit(Unit)
        }
    }
}
