package com.pagebinder.app.storage

import java.io.File
import java.io.IOException
import java.util.UUID

interface ProjectFileStore {
    fun create(projectId: UUID)

    fun delete(projectId: UUID)

    fun sizeBytes(projectId: UUID): Long
}

class FileProjectFileStore(
    filesDirectory: File,
) : ProjectFileStore {
    private val projectsDirectory = File(filesDirectory, PROJECTS_DIRECTORY)

    override fun create(projectId: UUID) {
        val projectDirectory = projectDirectory(projectId)
        if (projectDirectory.exists()) {
            throw IOException("Project file area already exists")
        }

        try {
            REQUIRED_DIRECTORIES.forEach { relativePath ->
                val directory = File(projectDirectory, relativePath)
                if (!directory.mkdirs() && !directory.isDirectory) {
                    throw IOException("Could not create project file area")
                }
            }
        } catch (failure: Exception) {
            projectDirectory.deleteRecursively()
            throw failure
        }
    }

    override fun delete(projectId: UUID) {
        val projectDirectory = projectDirectory(projectId)
        if (projectDirectory.exists() && !projectDirectory.deleteRecursively()) {
            throw IOException("Could not delete project file area")
        }
    }

    override fun sizeBytes(projectId: UUID): Long {
        val projectDirectory = projectDirectory(projectId)
        if (!projectDirectory.exists()) return 0L
        return projectDirectory
            .walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)
    }

    private fun projectDirectory(projectId: UUID) = File(projectsDirectory, projectId.toString())

    private companion object {
        const val PROJECTS_DIRECTORY = "projects"
        val REQUIRED_DIRECTORIES = listOf("images", "temp", "exports-cache")
    }
}
