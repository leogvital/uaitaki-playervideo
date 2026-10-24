package com.example.neonplayer.sources.remote

/**
 * Mantém viva, entre chamadas sucessivas de [androidx.media3.datasource.DataSource.open], uma
 * conexão de rede autenticada por servidor remoto (SMB/SFTP/FTP) usada para reprodução. O
 * ExoPlayer fecha e reabre a fonte de dados a cada seek que caia fora do trecho já baixado — sem
 * reaproveitar a conexão aqui, cada avanço/retrocesso de vídeo remoto pagava um handshake de rede +
 * autenticação inteiro (vários round-trips), o que trava a reprodução em redes mais lentas e pode
 * levar o servidor a recusar conexões repetidas em sequência rápida (limite de sessões/handshakes
 * concorrentes por usuário, comum em servidores SSH/FTP).
 *
 * Uma conexão por [key] (id do servidor) é suficiente: só um vídeo toca por vez.
 */
class RemoteConnectionPool<T : Any>(
    private val isAlive: (T) -> Boolean,
    private val disconnect: (T) -> Unit,
) {
    private val connections = mutableMapOf<String, T>()

    /** Retorna a conexão em cache para [key] se ainda estiver viva; senão conecta uma nova via [connect]. */
    @Synchronized
    fun acquire(key: String, connect: () -> T): T {
        connections[key]?.let { existing ->
            if (isAlive(existing)) return existing
            runCatching { disconnect(existing) }
            connections.remove(key)
        }
        val fresh = connect()
        connections[key] = fresh
        return fresh
    }

    /** Descarta a conexão em cache para [key] (ex: falhou no meio do uso) — a próxima [acquire] reconecta do zero. */
    @Synchronized
    fun invalidate(key: String) {
        connections.remove(key)?.let { runCatching { disconnect(it) } }
    }

    /** Fecha todas as conexões em cache — chamado ao sair da tela do player. */
    @Synchronized
    fun closeAll() {
        connections.values.forEach { runCatching { disconnect(it) } }
        connections.clear()
    }
}
