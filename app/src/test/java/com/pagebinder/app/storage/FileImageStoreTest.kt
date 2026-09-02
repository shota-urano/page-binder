package com.pagebinder.app.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

class FileImageStoreTest {
    private val filesDirectory = Files.createTempDirectory("pagebinder-image-store").toFile()
    private val projectId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val pageId = UUID.fromString("20000000-0000-0000-0000-000000000001")
    private val store = FileImageStore(filesDirectory)

    @After
    fun tearDown() {
        filesDirectory.deleteRecursively()
    }

    @Test
    fun `failed write leaves no incomplete file in images`() {
        createProjectFileArea()

        val failure =
            runCatching {
                store.saveOriginalAtomically(projectId, pageId) { output ->
                    output.write(byteArrayOf(1, 2, 3))
                    throw IOException("simulated writer failure")
                }
            }.exceptionOrNull()

        val imageDirectory = filesDirectory.resolve("projects/$projectId/images")
        val temporaryDirectory = filesDirectory.resolve("projects/$projectId/temp")
        assertTrue(failure is IOException)
        assertFalse(imageDirectory.resolve("$pageId.webp").exists())
        assertTrue(imageDirectory.listFiles().isNullOrEmpty())
        assertTrue(temporaryDirectory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `saved paths are relative and resolve only within app storage`() {
        createProjectFileArea()

        val saved =
            store.saveOriginalAtomically(projectId, pageId) { output ->
                output.write(byteArrayOf(4, 5, 6))
                "written"
            }

        assertEquals("projects/$projectId/images/$pageId.webp", saved.relativePath)
        assertEquals("written", saved.result)
        assertEquals(saved.file.canonicalFile, store.resolve(saved.relativePath).canonicalFile)
    }

    @Test
    fun `temporary cleanup never removes original images`() {
        createProjectFileArea()
        val original = filesDirectory.resolve("projects/$projectId/images/$pageId.webp")
        original.writeBytes(byteArrayOf(7))
        val temporaryDirectory = filesDirectory.resolve("projects/$projectId/temp")
        temporaryDirectory.resolve("capture.part").writeBytes(byteArrayOf(8))
        temporaryDirectory.resolve("nested").mkdir()
        temporaryDirectory.resolve("nested/export.part").writeBytes(byteArrayOf(9))

        store.clearTemporaryFiles(projectId)

        assertTrue(original.isFile)
        assertTrue(temporaryDirectory.isDirectory)
        assertTrue(temporaryDirectory.listFiles().isNullOrEmpty())
    }

    private fun createProjectFileArea() {
        FileProjectFileStore(filesDirectory).create(projectId)
    }
}
