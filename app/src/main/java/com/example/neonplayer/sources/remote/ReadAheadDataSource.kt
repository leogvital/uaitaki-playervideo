package com.example.neonplayer.sources.remote

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val CHUNK_SIZE_BYTES = 256 * 1024

/** ~6 MB de buffer à frente da posição de leitura atual — ordem de grandeza de um buffer de streaming, não um download completo. */
private const val QUEUE_CAPACITY_CHUNKS = 24

/**
 * Decorator de [DataSource] que desacopla a cadência de [read] pedida pelo ExoPlayer da latência de
 * cada round-trip de rede do [delegate]. Hoje [com.example.neonplayer.sources.smb.SmbDataSource],
 * [com.example.neonplayer.sources.sftp.SftpDataSource] e
 * [com.example.neonplayer.sources.ftp.FtpDataSource] fazem 1 leitura de rede por [read] chamado —
 * qualquer variação de latência da rede virava engasgo direto na reprodução. Aqui, uma thread
 * dedicada fica lendo do [delegate] em blocos fixos e enchendo uma fila limitada à frente da
 * posição atual; [read] drena dessa fila, bloqueando brevemente se a rede ainda não entregou o
 * próximo bloco — é exatamente aí que o buffer absorve uma rede lenta em vez de travar o player,
 * fazendo a reprodução remota se comportar como um streaming com buffer.
 */
@OptIn(UnstableApi::class)
class ReadAheadDataSource(private val delegate: DataSource) : DataSource {

    private sealed interface Chunk {
        class Data(val bytes: ByteArray, val length: Int) : Chunk
        data object EndOfInput : Chunk
        class Error(val error: IOException) : Chunk
    }

    private val queue = ArrayBlockingQueue<Chunk>(QUEUE_CAPACITY_CHUNKS)
    private val stopped = AtomicBoolean(false)
    private var readAheadThread: Thread? = null

    private var pendingChunk: Chunk.Data? = null
    private var pendingOffset = 0
    private var terminalError: IOException? = null
    private var endOfInputReached = false

    override fun open(dataSpec: DataSpec): Long {
        val result = delegate.open(dataSpec)

        stopped.set(false)
        terminalError = null
        endOfInputReached = false
        pendingChunk = null
        pendingOffset = 0
        queue.clear()

        val thread = Thread({ readAheadLoop() }, "ReadAheadDataSource")
        thread.isDaemon = true
        readAheadThread = thread
        thread.start()

        return result
    }

    private fun readAheadLoop() {
        val buffer = ByteArray(CHUNK_SIZE_BYTES)
        try {
            while (!stopped.get()) {
                val bytesRead = delegate.read(buffer, 0, buffer.size)
                if (bytesRead == C.RESULT_END_OF_INPUT) {
                    offerUntilStopped(Chunk.EndOfInput)
                    return
                }
                if (bytesRead > 0) {
                    offerUntilStopped(Chunk.Data(buffer.copyOf(bytesRead), bytesRead))
                }
            }
        } catch (e: IOException) {
            offerUntilStopped(Chunk.Error(e))
        } catch (e: InterruptedException) {
            // close() interrompeu a thread propositalmente — não é erro.
        } catch (e: Exception) {
            offerUntilStopped(Chunk.Error(IOException(e)))
        }
    }

    /** Tenta entregar o bloco lido ao consumidor; desiste silenciosamente se [close] já foi chamado. */
    private fun offerUntilStopped(chunk: Chunk) {
        try {
            while (!stopped.get()) {
                if (queue.offer(chunk, 200, TimeUnit.MILLISECONDS)) return
            }
        } catch (e: InterruptedException) {
            // encerrado por close() enquanto esperava espaço na fila — descarta.
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        terminalError?.let { throw it }
        if (endOfInputReached && pendingChunk == null) return C.RESULT_END_OF_INPUT

        val current = pendingChunk ?: run {
            val chunk = try {
                queue.take()
            } catch (e: InterruptedException) {
                throw IOException("Leitura antecipada interrompida", e)
            }
            when (chunk) {
                is Chunk.Error -> {
                    terminalError = chunk.error
                    throw chunk.error
                }

                Chunk.EndOfInput -> {
                    endOfInputReached = true
                    return C.RESULT_END_OF_INPUT
                }

                is Chunk.Data -> {
                    pendingOffset = 0
                    pendingChunk = chunk
                    chunk
                }
            }
        }

        val available = current.length - pendingOffset
        val toCopy = minOf(available, length)
        System.arraycopy(current.bytes, pendingOffset, buffer, offset, toCopy)
        pendingOffset += toCopy
        if (pendingOffset >= current.length) {
            pendingChunk = null
        }
        return toCopy
    }

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun getUri(): Uri? = delegate.getUri()

    override fun close() {
        stopped.set(true)
        readAheadThread?.interrupt()
        // Fechar o delegate desbloqueia prontamente uma leitura de rede pendente na thread de
        // leitura antecipada (o socket subjacente lança IOException quando fechado por outra
        // thread) — sem isso, a thread poderia ficar bloqueada no read() por bem mais que o join abaixo.
        runCatching { delegate.close() }
        queue.clear()
        readAheadThread?.join(300)
        readAheadThread = null
        pendingChunk = null
    }
}
