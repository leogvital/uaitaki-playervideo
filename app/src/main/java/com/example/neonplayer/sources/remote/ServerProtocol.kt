package com.example.neonplayer.sources.remote

enum class ServerProtocol(val defaultPort: Int, val label: String) {
    SMB(defaultPort = 445, label = "SMB"),
    SFTP(defaultPort = 22, label = "SFTP"),
    FTP(defaultPort = 21, label = "FTP"),
}
