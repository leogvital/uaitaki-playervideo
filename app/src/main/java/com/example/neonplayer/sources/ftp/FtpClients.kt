package com.example.neonplayer.sources.ftp

import com.example.neonplayer.sources.remote.RemoteServerConfig
import java.io.IOException
import java.time.Duration
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply

private const val FTP_TIMEOUT_MS = 15_000

/** Erro de protocolo FTP com o código de resposta do servidor, para mapear mensagens claras. */
internal class FtpCommandException(val replyCode: Int, message: String) : IOException(message)

internal fun connectedFtpClient(config: RemoteServerConfig, password: String): FTPClient {
    val client = FTPClient()
    client.connectTimeout = FTP_TIMEOUT_MS
    client.setDataTimeout(Duration.ofMillis(FTP_TIMEOUT_MS.toLong()))

    client.connect(config.host, config.port)
    if (!FTPReply.isPositiveCompletion(client.replyCode)) {
        val code = client.replyCode
        val message = client.replyString
        runCatching { client.disconnect() }
        throw FtpCommandException(code, "Servidor FTP recusou a conexão: $message")
    }
    client.setSoTimeout(FTP_TIMEOUT_MS)

    if (!client.login(config.username, password)) {
        val code = client.replyCode
        val message = client.replyString
        runCatching { client.disconnect() }
        throw FtpCommandException(code, "Login FTP falhou: $message")
    }

    client.enterLocalPassiveMode()
    client.setFileType(FTP.BINARY_FILE_TYPE)
    return client
}
