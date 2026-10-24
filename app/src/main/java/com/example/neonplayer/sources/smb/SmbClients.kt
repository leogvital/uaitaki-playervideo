package com.example.neonplayer.sources.smb

import com.example.neonplayer.sources.remote.RemoteConnectionPool
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import java.util.concurrent.TimeUnit

private const val SMB_TIMEOUT_SECONDS = 15L

/**
 * [SMBClient] reaproveitado entre seeks (ver [RemoteConnectionPool]) — o próprio [SMBClient] já
 * mantém em cache a conexão TCP/negociação SMB por host:port internamente e reconecta sozinho se
 * ela cair, então basta não descartar a instância a cada [SmbDataSource.open]; `isAlive` sempre
 * `true` porque o objeto em si não fica inválido, só a conexão que ele gerencia por baixo.
 */
internal val smbClientPool = RemoteConnectionPool<SMBClient>(
    isAlive = { true },
    disconnect = { it.close() },
)

/**
 * O timeout padrão do smbj para leitura/escrita/transação é infinito (SO_TIMEOUT = 0), o que
 * faria uma conexão travada (host inalcançável, firewall descartando pacotes) bloquear
 * indefinidamente. Configuramos um timeout finito para que erros de rede sejam reportados de
 * forma clara em vez de travar a UI ou a reprodução.
 */
internal fun newSmbClient(): SMBClient {
    val config = SmbConfig.builder()
        .withTimeout(SMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withSoTimeout(SMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    return SMBClient(config)
}
