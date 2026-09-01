package com.pagebinder.app.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DomainFrameworkDependencyTest {
    @Test
    fun `domain source imports no Room ML Kit or PDFBox types`() {
        val domainDirectory = Path.of("src/main/java/com/pagebinder/app/domain")
        val forbiddenImports =
            listOf("androidx.room", "com.google.mlkit", "org.apache.pdfbox", "com.tom_roush")
        val violations = mutableListOf<String>()
        Files.walk(domainDirectory).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .forEach { file ->
                    Files.readAllLines(file).forEachIndexed { index, line ->
                        val import = line.trim()
                        if (forbiddenImports.any { prefix -> import.startsWith("import $prefix") }) {
                            violations += "$file:${index + 1}: $import"
                        }
                    }
                }
        }

        assertTrue(
            "Domain layer imports forbidden framework types:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
