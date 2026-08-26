package com.pagebinder.app.storage

import com.pagebinder.app.domain.CompletedExportSource
import com.pagebinder.app.domain.ExportDestination
import com.pagebinder.app.domain.ExportStorageErrorCode
import com.pagebinder.app.domain.ExportStorageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

class SafExportStorageGatewayTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `all bytes and normal close are required for success`() =
        runBlocking {
            val bytes = "completed export".toByteArray()
            val output = ByteArrayOutputStream()
            val gateway = gatewayReturning(output)

            val result = gateway.write(source(bytes), destination())

            assertEquals(ExportStorageResult.Succeeded(bytes.size.toLong()), result)
            assertArrayEquals(bytes, output.toByteArray())
        }

    @Test
    fun `mid-write failure leaves partial bytes but never reports success`() =
        runBlocking {
            val bytes = ByteArray(32) { it.toByte() }
            val output = FailingAfterBytesOutputStream(byteLimit = 7)
            val gateway = gatewayReturning(output)

            val result = gateway.write(source(bytes), destination())

            assertEquals(
                ExportStorageResult.Failed(ExportStorageErrorCode.WRITE_FAILED),
                result,
            )
            assertEquals(7, output.bytesWritten)
        }

    @Test
    fun `close failure after full write is not reported as success`() =
        runBlocking {
            val bytes = "all bytes reached provider".toByteArray()
            val output = CloseFailingOutputStream()
            val gateway = gatewayReturning(output)

            val result = gateway.write(source(bytes), destination())

            assertEquals(
                ExportStorageResult.Failed(ExportStorageErrorCode.WRITE_FAILED),
                result,
            )
            assertEquals(bytes.size, output.size())
        }

    @Test
    fun `source length mismatch is incomplete rather than success`() =
        runBlocking {
            val gateway = gatewayReturning(ByteArrayOutputStream())
            val source = source("short".toByteArray(), declaredSize = 99)

            val result = gateway.write(source, destination())

            assertEquals(
                ExportStorageResult.Failed(ExportStorageErrorCode.SOURCE_INCOMPLETE),
                result,
            )
        }

    @Test
    fun `source open failure closes provider stream and does not report success`() =
        runBlocking {
            val output = CloseTrackingOutputStream()
            val gateway = gatewayReturning(output)
            val failingSource =
                object : CompletedExportSource {
                    override val byteCount = 1L

                    override fun openInputStream(): ByteArrayInputStream {
                        throw IOException("injected source failure")
                    }
                }

            val result = gateway.write(failingSource, destination())

            assertEquals(
                ExportStorageResult.Failed(ExportStorageErrorCode.WRITE_FAILED),
                result,
            )
            assertEquals(true, output.closed)
        }

    @Test
    fun `provider permission failure becomes domain error`() =
        runBlocking {
            val gateway =
                SafExportStorageGateway(
                    outputStreamOpener = SafOutputStreamOpener { throw SecurityException("denied") },
                    ioDispatcher = Dispatchers.Unconfined,
                )

            val result = gateway.write(source(byteArrayOf(1)), destination())

            assertEquals(
                ExportStorageResult.Failed(ExportStorageErrorCode.DESTINATION_PERMISSION_DENIED),
                result,
            )
        }

    @Test
    fun `completed cache source accepts only a completed direct child file`() {
        val cache = temporaryFolder.newFolder("exports-cache")
        val completed = File(cache, "book.zip").apply { writeText("complete") }
        val outside = temporaryFolder.newFile("outside.zip").apply { writeText("outside") }

        val source = CompletedCacheExport.open(cache, completed)

        assertEquals(completed.length(), source.byteCount)
        assertThrows(IllegalArgumentException::class.java) {
            CompletedCacheExport.open(cache, outside)
        }
    }

    private fun gatewayReturning(output: OutputStream) =
        SafExportStorageGateway(
            outputStreamOpener = SafOutputStreamOpener { output },
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun source(
        bytes: ByteArray,
        declaredSize: Long = bytes.size.toLong(),
    ) = object : CompletedExportSource {
        override val byteCount = declaredSize

        override fun openInputStream() = ByteArrayInputStream(bytes)
    }

    private fun destination() = ExportDestination("content://provider/document/redacted")

    private class FailingAfterBytesOutputStream(
        private val byteLimit: Int,
    ) : OutputStream() {
        var bytesWritten: Int = 0
            private set

        override fun write(value: Int) {
            if (bytesWritten == byteLimit) throw IOException("injected write failure")
            bytesWritten++
        }
    }

    private class CloseFailingOutputStream : ByteArrayOutputStream() {
        override fun close() {
            throw IOException("injected close failure")
        }
    }

    private class CloseTrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
