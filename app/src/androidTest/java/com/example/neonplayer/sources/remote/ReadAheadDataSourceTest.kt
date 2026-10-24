package com.example.neonplayer.sources.remote

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ReadAheadDataSourceTest {
    @Test
    fun preservesBytesAcrossChunksAndReopensAtRequestedPosition() {
        val bytes = ByteArray(700_000) { (it % 251).toByte() }
        val source = ReadAheadDataSource(MemorySource(bytes))
        for (position in listOf(0L, 400_000L, 12_345L)) {
            try {
                assertEquals(bytes.size.toLong() - position, source.open(spec(position)))
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8191)
                while (true) {
                    val count = source.read(buffer, 0, buffer.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    assertTrue(count > 0)
                    output.write(buffer, 0, count)
                }
                assertArrayEquals(bytes.copyOfRange(position.toInt(), bytes.size), output.toByteArray())
                assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun propagatesNetworkFailureAfterBufferedBytes() {
        val failure = IOException("connection lost")
        val delegate = object : MemorySource(byteArrayOf(1, 2, 3)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= 3) throw failure
                return super.read(buffer, offset, length)
            }
        }
        val source = ReadAheadDataSource(delegate)
        try {
            source.open(spec())
            val buffer = ByteArray(10)
            assertEquals(3, source.read(buffer, 0, buffer.size))
            try {
                source.read(buffer, 0, buffer.size)
                fail("Expected network error")
            } catch (error: IOException) {
                assertSame(failure, error)
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun rejectsEmptyReadsInsteadOfSpinning() {
        val source = ReadAheadDataSource(object : MemorySource(byteArrayOf(1)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
        })
        try {
            source.open(spec())
            try {
                source.read(ByteArray(1), 0, 1)
                fail("Expected empty read error")
            } catch (_: IOException) {
                // Expected: a broken source must not spin indefinitely.
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun closeUnblocksConsumerWaitingForNetwork() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = ReadAheadDataSource(object : MemorySource(byteArrayOf(1)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                release.await()
                throw IOException("closed")
            }
            override fun close() { release.countDown() }
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            source.open(spec())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val result = executor.submit<Boolean> {
                try {
                    source.read(ByteArray(1), 0, 1)
                    false
                } catch (_: IOException) {
                    true
                }
            }
            source.close()
            assertTrue(result.get(2, TimeUnit.SECONDS))
        } finally {
            source.close()
            executor.shutdownNow()
        }
    }

    private fun spec(position: Long = 0) = DataSpec.Builder()
        .setUri(Uri.parse("memory://video"))
        .setPosition(position)
        .build()

    private open class MemorySource(private val bytes: ByteArray) : DataSource {
        protected var position = 0
        override fun open(dataSpec: DataSpec): Long {
            position = dataSpec.position.toInt()
            return (bytes.size - position).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        override fun getUri(): Uri = Uri.parse("memory://video")
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun close() = Unit
    }
}
