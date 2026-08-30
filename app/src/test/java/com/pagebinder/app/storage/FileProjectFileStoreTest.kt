package com.pagebinder.app.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class FileProjectFileStoreTest {
    private val filesDirectory = Files.createTempDirectory("pagebinder-project-store").toFile()
    private val projectId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val store = FileProjectFileStore(filesDirectory)

    @After
    fun tearDown() {
        filesDirectory.deleteRecursively()
    }

    @Test
    fun `create prepares required directories and size includes project files`() {
        store.create(projectId)
        val projectDirectory = filesDirectory.resolve("projects/$projectId")

        assertTrue(projectDirectory.resolve("images").isDirectory)
        assertTrue(projectDirectory.resolve("temp").isDirectory)
        assertTrue(projectDirectory.resolve("exports-cache").isDirectory)

        projectDirectory.resolve("images/page.webp").writeBytes(ByteArray(7))
        projectDirectory.resolve("temp/work.tmp").writeBytes(ByteArray(5))
        assertEquals(12L, store.sizeBytes(projectId))
    }

    @Test
    fun `delete removes the whole project file area`() {
        store.create(projectId)

        store.delete(projectId)

        assertFalse(filesDirectory.resolve("projects/$projectId").exists())
        assertEquals(0L, store.sizeBytes(projectId))
    }
}
