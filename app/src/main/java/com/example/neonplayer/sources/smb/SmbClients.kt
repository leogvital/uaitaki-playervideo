package com.example.neonplayer.sources.smb

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import java.util.concurrent.TimeUnit

private const val SMB_TIMEOUT_SECONDS = 15L

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
