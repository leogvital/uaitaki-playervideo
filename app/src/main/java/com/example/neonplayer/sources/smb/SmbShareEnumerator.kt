package com.example.neonplayer.sources.smb

import com.example.neonplayer.sources.remote.RemoteServerConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.mssrvs.dto.NetShareInfo1
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Lista os compartilhamentos disponíveis num servidor SMB chamando NetrShareEnum (MS-SRVS) através
 * do pipe nomeado `\PIPE\srvsvc` sobre `IPC$`. smbj (a lib usada neste app para leitura/escrita de
 * vídeo) não expõe essa operação — quem faz isso aqui é o `smbj-rpc` (com.rapid7.client:dcerpc),
 * biblioteca companion do smbj especificamente para chamadas DCE/RPC sobre named pipes SMB. Antes
 * disto o PDU de DCE/RPC era montado/interpretado à mão neste arquivo; a versão hand-rolled nunca
 * tinha sido validada contra a variedade de servidores SMB do mundo real, então trocamos por uma
 * lib testada assim que soubemos que ela existe.
 *
 * A tela de cadastro de servidor sempre mantém a entrada manual do caminho como alternativa — isto
 * é um atalho de UX, nunca uma dependência (servidores que bloqueiam `IPC$`/`srvsvc` por política
 * continuam funcionando via caminho digitado à mão).
 */
sealed interface SmbShareListResult {
    data class Success(val shareNames: List<String>) : SmbShareListResult
    data class Error(val message: String) : SmbShareListResult
}

private const val SHARE_LIST_TIMEOUT_MS = 20_000L

/** Bit STYPE_SPECIAL (MS-SRVS 2.2.2.4) — compartilhamentos administrativos/ocultos (ADMIN$, C$, IPC$, print$). */
private const val SHARE_TYPE_HIDDEN = 0x80000000.toInt()

/** Máscara dos 2 bits baixos de tipo (MS-SRVS 2.2.2.4) — só nos interessam compartilhamentos de disco (STYPE_DISKTREE = 0). */
private const val SHARE_TYPE_MASK = 0x3
private const val SHARE_TYPE_DISKTREE = 0x0

suspend fun listSmbShares(config: RemoteServerConfig, password: String): SmbShareListResult =
    withContext(Dispatchers.IO) {
        try {
            withTimeout(SHARE_LIST_TIMEOUT_MS) { runInterruptible { fetchShareList(config, password) } }
        } catch (timeout: TimeoutCancellationException) {
            SmbShareListResult.Error("Tempo esgotado ao listar compartilhamentos")
        } catch (e: Exception) {
            SmbShareListResult.Error(mapShareListError(e))
        }
    }

private fun fetchShareList(config: RemoteServerConfig, password: String): SmbShareListResult {
    newSmbClient().use { client ->
        val connection = client.connect(config.host, config.port)
        val authContext = AuthenticationContext(config.username, password.toCharArray(), null)
        connection.authenticate(authContext).use { session ->
            val transport = SMBTransportFactories.SRVSVC.getTransport(session)
            val shares = ServerService(transport).getShares1()
            return SmbShareListResult.Success(shares.filter(::isVisibleDiskShare).map(NetShareInfo1::getNetName))
        }
    }
}

private fun isVisibleDiskShare(share: NetShareInfo1): Boolean =
    (share.type and SHARE_TYPE_MASK) == SHARE_TYPE_DISKTREE &&
        (share.type and SHARE_TYPE_HIDDEN) == 0 &&
        !share.netName.endsWith('$')

private fun mapShareListError(error: Exception): String = when (error) {
    is UnknownHostException -> "Servidor não encontrado"
    is ConnectException, is SocketTimeoutException -> "Não foi possível conectar ao servidor"
    else -> "Erro ao listar compartilhamentos: ${error.message ?: error.javaClass.simpleName}"
}
