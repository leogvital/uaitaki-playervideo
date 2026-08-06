package com.example.neonplayer.sources.remote

/**
 * Configuração de um servidor remoto (SMB, SFTP ou FTP) definida pelo usuário.
 *
 * [path] tem semântica por protocolo: para SMB é "compartilhamento/subcaminho" (separado por
 * [com.example.neonplayer.sources.smb.splitShareAndPath] no momento da conexão); para SFTP/FTP é
 * um caminho de diretório remoto simples. A senha não fica aqui — ver [RemoteCredentialStore].
 */
data class RemoteServerConfig(
    val id: String,
    val protocol: ServerProtocol,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val path: String,
)
