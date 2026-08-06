package com.example.neonplayer.sources.sftp

import com.example.neonplayer.sources.remote.RemoteServerConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier

private const val SFTP_TIMEOUT_MS = 15_000

/**
 * O app não oferece gerenciamento de known_hosts/verificação de fingerprint para os servidores
 * que o usuário configura, então aceitamos qualquer chave de host ([PromiscuousVerifier]) — troca
 * deliberada de proteção contra man-in-the-middle por usabilidade "aponte e conecte", igual à
 * maioria dos apps SFTP para usuário final sem tela de gerenciamento de host keys.
 *
 * Também definimos timeouts explícitos: o padrão do sshj para timeout de conexão/socket pode
 * bloquear indefinidamente num host que não responde, o que travaria a UI ou a reprodução.
 */
internal fun connectedSshClient(config: RemoteServerConfig, password: String): SSHClient {
    val client = SSHClient()
    client.addHostKeyVerifier(PromiscuousVerifier())
    client.connectTimeout = SFTP_TIMEOUT_MS
    client.timeout = SFTP_TIMEOUT_MS
    client.connect(config.host, config.port)
    client.authPassword(config.username, password)
    return client
}
