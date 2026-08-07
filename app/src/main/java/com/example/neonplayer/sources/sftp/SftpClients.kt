package com.example.neonplayer.sources.sftp

import com.example.neonplayer.sources.remote.RemoteServerConfig
import java.security.Security
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider

private const val SFTP_TIMEOUT_MS = 15_000

/**
 * O provider "BC" embutido no Android é uma versão reduzida do BouncyCastle sem suporte a
 * X25519/curve25519-sha256, usado por padrão na negociação de key exchange da maioria dos
 * servidores SSH/SFTP modernos. Sem substituí-lo pelo BouncyCastle completo (dependência
 * `bcprov-jdk18on`), a conexão falha com "no such algorithm: X25519 for provider BC".
 */
private fun ensureFullBouncyCastleProvider() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) is BouncyCastleProvider) return
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    Security.insertProviderAt(BouncyCastleProvider(), 1)
}

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
    ensureFullBouncyCastleProvider()
    val client = SSHClient()
    client.addHostKeyVerifier(PromiscuousVerifier())
    client.connectTimeout = SFTP_TIMEOUT_MS
    client.timeout = SFTP_TIMEOUT_MS
    client.connect(config.host, config.port)
    client.authPassword(config.username, password)
    return client
}
