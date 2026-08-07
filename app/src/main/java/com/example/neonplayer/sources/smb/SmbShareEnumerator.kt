package com.example.neonplayer.sources.smb

import com.example.neonplayer.sources.remote.RemoteServerConfig
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.PipeShare
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Lista os compartilhamentos disponíveis num servidor SMB chamando NetrShareEnum (MS-SRVS, opnum
 * 15, nível 0) através do pipe nomeado `\PIPE\srvsvc` sobre `IPC$` — smbj (a lib usada neste app)
 * não expõe essa operação como API pronta, então o PDU de DCE/RPC é montado e decodificado à mão
 * aqui (bind da interface SRVSVC + sintaxe de transferência NDR, depois o request/response de
 * NetrShareEnum).
 *
 * **Isto não é uma implementação testada contra a variedade de servidores SMB do mundo real** —
 * é esperado funcionar contra Windows/Samba com o pipe `srvsvc` acessível, mas pode falhar (de
 * forma controlada, como [SmbShareListResult.Error]) em servidores/NAS que bloqueiam `IPC$`/
 * `srvsvc` por política, ou best-effort para variações de protocolo não cobertas aqui. Por isso a
 * tela de cadastro de servidor sempre mantém a entrada manual do caminho como alternativa — isto é
 * um atalho de UX, nunca uma dependência.
 */
sealed interface SmbShareListResult {
    data class Success(val shareNames: List<String>) : SmbShareListResult
    data class Error(val message: String) : SmbShareListResult
}

private const val SHARE_LIST_TIMEOUT_MS = 20_000L
private const val SRVSVC_PIPE_NAME = "srvsvc"

private const val SRVSVC_INTERFACE_UUID = "4b324fc8-1670-01d3-1278-5a47bf6ee188"
private const val SRVSVC_INTERFACE_VERSION_MAJOR = 3
private const val SRVSVC_INTERFACE_VERSION_MINOR = 0
private const val NDR_TRANSFER_SYNTAX_UUID = "8a885d04-1ceb-11c9-9fe8-08002b104860"
private const val NDR_TRANSFER_SYNTAX_VERSION = 2

private const val PTYPE_REQUEST = 0
private const val PTYPE_RESPONSE = 2
private const val PTYPE_FAULT = 3
private const val PTYPE_BIND = 11
private const val PTYPE_BIND_ACK = 12

private const val NETR_SHARE_ENUM_OPNUM = 15
private const val MAX_SANE_SHARE_COUNT = 10_000

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
            val share = session.connectShare("IPC$") as? PipeShare
                ?: return SmbShareListResult.Error("IPC\$ não respondeu como pipe nomeado neste servidor")
            share.use { pipeShare ->
                val namedPipe = pipeShare.open(
                    SRVSVC_PIPE_NAME,
                    SMB2ImpersonationLevel.Impersonation,
                    EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                    EnumSet.noneOf(FileAttributes::class.java),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.noneOf(SMB2CreateOptions::class.java),
                )
                namedPipe.use { pipe ->
                    val bindAck = pipe.transact(buildBindPdu())
                    if (bindAck.size < 3 || bindAck[2].toInt() != PTYPE_BIND_ACK) {
                        return SmbShareListResult.Error("Servidor recusou a interface SRVSVC")
                    }
                    val response = pipe.transact(buildNetrShareEnumRequest())
                    return parseNetrShareEnumResponse(response)
                }
            }
        }
    }
}

private fun mapShareListError(error: Exception): String = when (error) {
    is UnknownHostException -> "Servidor não encontrado"
    is ConnectException, is SocketTimeoutException -> "Não foi possível conectar ao servidor"
    else -> "Erro ao listar compartilhamentos: ${error.message ?: error.javaClass.simpleName}"
}

// --- Codificação/decodificação DCE/RPC + NDR (ver MS-RPCE e MS-SRVS) ---

private fun buildPduHeader(ptype: Int, callId: Int, bodyLength: Int): ByteArray {
    val total = 16 + bodyLength
    val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(5) // rpc_vers
    buffer.put(0) // rpc_vers_minor
    buffer.put(ptype.toByte())
    buffer.put(0x03) // pfc_flags: PFC_FIRST_FRAG | PFC_LAST_FRAG
    buffer.put(byteArrayOf(0x10, 0, 0, 0)) // packed_drep: little-endian/ASCII/IEEE
    buffer.putShort(total.toShort())
    buffer.putShort(0) // auth_length
    buffer.putInt(callId)
    return buffer.array()
}

/** UUID no formato binário "misto" do DCE/RPC (partes iniciais little-endian, clock_seq/node como estão). */
private fun dceUuidBytes(uuid: String): ByteArray {
    val parsed = UUID.fromString(uuid)
    val msb = parsed.mostSignificantBits
    val lsb = parsed.leastSignificantBits
    val buffer = ByteBuffer.allocate(16)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt((msb ushr 32).toInt())
    buffer.putShort(((msb ushr 16) and 0xFFFF).toShort())
    buffer.putShort((msb and 0xFFFF).toShort())
    buffer.order(ByteOrder.BIG_ENDIAN)
    buffer.putShort(((lsb ushr 48) and 0xFFFF).toShort())
    val node = lsb and 0xFFFFFFFFFFFFL
    for (shift in intArrayOf(40, 32, 24, 16, 8, 0)) {
        buffer.put(((node ushr shift) and 0xFF).toByte())
    }
    return buffer.array()
}

private fun buildBindPdu(): ByteArray {
    val srvsvcUuid = dceUuidBytes(SRVSVC_INTERFACE_UUID)
    val ndrUuid = dceUuidBytes(NDR_TRANSFER_SYNTAX_UUID)

    val body = ByteBuffer.allocate(12 + 44).order(ByteOrder.LITTLE_ENDIAN)
    body.putShort(4280) // max_xmit_frag
    body.putShort(4280) // max_recv_frag
    body.putInt(0) // assoc_group_id (nova associação)
    body.put(1) // num_ctx_items
    body.put(byteArrayOf(0, 0, 0)) // reserved

    // Único contexto de apresentação: interface SRVSVC v3.0 + sintaxe de transferência NDR v2.0.
    body.putShort(0) // context_id
    body.put(1) // num_trans_items
    body.put(0) // reserved2
    body.put(srvsvcUuid)
    body.putShort(SRVSVC_INTERFACE_VERSION_MAJOR.toShort())
    body.putShort(SRVSVC_INTERFACE_VERSION_MINOR.toShort())
    body.put(ndrUuid)
    body.putInt(NDR_TRANSFER_SYNTAX_VERSION)

    return buildPduHeader(PTYPE_BIND, callId = 1, bodyLength = body.array().size) + body.array()
}

/** NetrShareEnum(ServerName=null, InfoStruct{Level=0}, PreferredMaximumLength=0xFFFFFFFF, ResumeHandle=0). */
private fun buildNetrShareEnumRequest(): ByteArray {
    val stub = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
    // ServerName: ponteiro único -> string NDR conformant-varying vazia (só o terminador nulo) —
    // o nome do servidor é dispensável já que a conexão já está estabelecida com ele.
    stub.putInt(0x00020000) // referent id
    stub.putInt(1) // max_count
    stub.putInt(0) // offset
    stub.putInt(1) // actual_count
    stub.putShort(0) // caractere nulo (UTF-16LE)
    stub.putShort(0) // padding para alinhar em 4 bytes

    // InfoStruct: ponteiro [ref] (sem referent id), nível 0, tudo zerado (é o servidor quem preenche).
    stub.putInt(0) // Level
    stub.putInt(0) // seletor da union (== Level)
    stub.putInt(0) // EntriesRead
    stub.putInt(0) // Buffer = NULL

    stub.putInt(-1) // PreferredMaximumLength = 0xFFFFFFFF (sem limite)

    stub.putInt(0x00020004) // ResumeHandle: referent id
    stub.putInt(0) // ResumeHandle: valor inicial

    val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    header.putInt(stub.array().size) // alloc_hint
    header.putShort(0) // context_id
    header.putShort(NETR_SHARE_ENUM_OPNUM.toShort())

    val body = header.array() + stub.array()
    return buildPduHeader(PTYPE_REQUEST, callId = 2, bodyLength = body.size) + body
}

private class NdrReader(private val buffer: ByteBuffer) {
    fun readUInt32(): Long = buffer.int.toLong() and 0xFFFFFFFFL
    fun skip(n: Int) {
        buffer.position(buffer.position() + n)
    }
    fun align4() {
        val remainder = buffer.position() % 4
        if (remainder != 0) skip(4 - remainder)
    }
    fun readUtf16(charCount: Int): String {
        val chars = CharArray(charCount) { buffer.short.toInt().toChar() }
        return String(chars).trimEnd('\u0000')
    }
}

private fun parseNetrShareEnumResponse(pdu: ByteArray): SmbShareListResult {
    if (pdu.size < 16) return SmbShareListResult.Error("Resposta SRVSVC incompleta")
    when (val ptype = pdu[2].toInt() and 0xFF) {
        PTYPE_FAULT -> return SmbShareListResult.Error("Servidor rejeitou NetrShareEnum")
        PTYPE_RESPONSE -> Unit
        else -> return SmbShareListResult.Error("Resposta SRVSVC inesperada (tipo=$ptype)")
    }

    return try {
        val buffer = ByteBuffer.wrap(pdu, 16, pdu.size - 16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.int // alloc_hint
        buffer.short // context_id
        buffer.get() // cancel_count
        buffer.get() // reserved

        val reader = NdrReader(buffer)
        val level = reader.readUInt32()
        reader.skip(4) // seletor da union (redundante, repete o Level)
        if (level != 0L) {
            return SmbShareListResult.Error("Servidor retornou um formato de resposta inesperado")
        }
        val entriesRead = reader.readUInt32()
        val bufferReferentId = reader.readUInt32()

        if (bufferReferentId == 0L || entriesRead <= 0L || entriesRead > MAX_SANE_SHARE_COUNT) {
            return SmbShareListResult.Success(emptyList())
        }

        val maxCount = reader.readUInt32()
        val count = minOf(entriesRead, maxCount).toInt().coerceAtLeast(0)
        val referentIds = LongArray(count) { reader.readUInt32() }

        val names = mutableListOf<String>()
        for (i in 0 until count) {
            if (referentIds[i] == 0L) continue
            reader.skip(4) // max_count da string
            reader.skip(4) // offset
            val actualCount = reader.readUInt32().toInt().coerceIn(0, 4096)
            val name = reader.readUtf16(actualCount)
            reader.align4()
            if (name.isNotBlank()) names += name
        }
        SmbShareListResult.Success(names)
    } catch (e: Exception) {
        SmbShareListResult.Error("Não foi possível interpretar a lista de compartilhamentos")
    }
}
