package com.pagebinder.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageBinderDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            PageBinderDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @After
    fun tearDown() {
        DATABASE_NAMES.forEach(InstrumentationRegistry.getInstrumentation().targetContext::deleteDatabase)
    }

    @Test
    fun version1SchemaMatchesExportedDefinition() {
        helper.createDatabase(VERSION_1_DATABASE_NAME, VERSION_1).use { database ->
            assertEquals(VERSION_1_SCHEMA.keys, database.userTableNames())
            VERSION_1_SCHEMA.forEach { (tableName, expectedTable) ->
                assertEquals(expectedTable.columns, database.columnDefinitions(tableName))
                assertEquals(expectedTable.primaryKeys, database.primaryKeyDefinitions(tableName))
                assertEquals(expectedTable.indices, database.indexDefinitions(tableName))
                assertEquals(expectedTable.foreignKeys, database.foreignKeyDefinitions(tableName))
            }
        }
    }

    @Test
    fun migrationFrom1To2RetainsPagesAndEnforcesProjectForeignKey() {
        helper.createDatabase(MIGRATION_DATABASE_NAME, VERSION_1).apply {
            execSQL(
                """
                INSERT INTO book_projects (id, title, author, note, created_at, updated_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(PROJECT_ID, "Migrated project", null, null, CREATED_AT, CREATED_AT, null),
            )
            execSQL(
                """
                INSERT INTO pages (
                    id, project_id, sequence, original_image_path, width, height, rotation,
                    crop_left, crop_top, crop_right, crop_bottom, captured_at, content_hash,
                    perceptual_hash, quality_state, ocr_state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    PAGE_ID,
                    PROJECT_ID,
                    1,
                    "projects/$PROJECT_ID/images/$PAGE_ID.webp",
                    1080,
                    1920,
                    0,
                    0f,
                    0f,
                    1f,
                    1f,
                    CREATED_AT,
                    "content-hash",
                    "perceptual-hash",
                    "normal",
                    "pending",
                ),
            )
            close()
        }

        helper
            .runMigrationsAndValidate(MIGRATION_DATABASE_NAME, VERSION_2, true, PageBinderDatabase.MIGRATION_1_2)
            .use { database ->
                database.query("SELECT project_id, sequence FROM pages WHERE id = ?", arrayOf(PAGE_ID)).use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(PROJECT_ID, cursor.getString(0))
                    assertEquals(1, cursor.getInt(1))
                }
                database.execSQL("PRAGMA foreign_keys = ON")
                assertThrows(SQLiteConstraintException::class.java) {
                    database.execSQL(
                        """
                        INSERT INTO pages (
                            id, project_id, sequence, original_image_path, width, height, rotation,
                            crop_left, crop_top, crop_right, crop_bottom, captured_at, content_hash,
                            perceptual_hash, quality_state, ocr_state
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf<Any?>(
                            INVALID_PAGE_ID,
                            UNKNOWN_PROJECT_ID,
                            1,
                            "projects/$UNKNOWN_PROJECT_ID/images/$INVALID_PAGE_ID.webp",
                            1080,
                            1920,
                            0,
                            0f,
                            0f,
                            1f,
                            1f,
                            CREATED_AT,
                            "content-hash",
                            "perceptual-hash",
                            "normal",
                            "pending",
                        ),
                    )
                }
            }
    }

    private fun SupportSQLiteDatabase.userTableNames(): Set<String> =
        query(
            """
            SELECT name FROM sqlite_master
            WHERE type = 'table'
              AND name NOT LIKE 'android_%'
              AND name NOT LIKE 'sqlite_%'
              AND name != 'room_master_table'
            """.trimIndent(),
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun SupportSQLiteDatabase.columnDefinitions(tableName: String): List<ColumnDefinition> =
        query("PRAGMA table_info(`$tableName`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ColumnDefinition(
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                            notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                        ),
                    )
                }
            }
        }

    private fun SupportSQLiteDatabase.primaryKeyDefinitions(tableName: String): List<String> =
        query("PRAGMA table_info(`$tableName`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val primaryKeyPosition = cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                    if (primaryKeyPosition > 0) {
                        add(
                            primaryKeyPosition to
                                cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        )
                    }
                }
            }
                .sortedBy { (position, _) -> position }
                .map { (_, name) -> name }
        }

    private fun SupportSQLiteDatabase.indexDefinitions(tableName: String): List<IndexDefinition> =
        query("PRAGMA index_list(`$tableName`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val indexName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    if (cursor.getString(cursor.getColumnIndexOrThrow("origin")) == "c") {
                        add(
                            IndexDefinition(
                                name = indexName,
                                unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 1,
                                columns = indexColumns(indexName),
                            ),
                        )
                    }
                }
            }
        }

    private fun SupportSQLiteDatabase.indexColumns(indexName: String): List<String> =
        query("PRAGMA index_info(`$indexName`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

    private fun SupportSQLiteDatabase.foreignKeyDefinitions(tableName: String): List<ForeignKeyDefinition> =
        query("PRAGMA foreign_key_list(`$tableName`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ForeignKeyDefinition(
                            column = cursor.getString(cursor.getColumnIndexOrThrow("from")),
                            referencedTable = cursor.getString(cursor.getColumnIndexOrThrow("table")),
                            referencedColumn = cursor.getString(cursor.getColumnIndexOrThrow("to")),
                        ),
                    )
                }
            }
        }

    private companion object {
        const val VERSION_1 = 1
        const val VERSION_2 = 2
        const val VERSION_1_DATABASE_NAME = "pagebinder-v1-schema-test"
        const val MIGRATION_DATABASE_NAME = "pagebinder-migration-test"
        const val PROJECT_ID = "10000000-0000-0000-0000-000000000001"
        const val PAGE_ID = "20000000-0000-0000-0000-000000000002"
        const val INVALID_PAGE_ID = "30000000-0000-0000-0000-000000000003"
        const val UNKNOWN_PROJECT_ID = "40000000-0000-0000-0000-000000000004"
        const val CREATED_AT = "2026-09-01T00:00:00Z"

        val DATABASE_NAMES = setOf(VERSION_1_DATABASE_NAME, MIGRATION_DATABASE_NAME)

        val VERSION_1_SCHEMA =
            mapOf(
                "book_projects" to
                    TableDefinition(
                        columns =
                            listOf(
                                ColumnDefinition("id", "TEXT", true),
                                ColumnDefinition("title", "TEXT", true),
                                ColumnDefinition("author", "TEXT", false),
                                ColumnDefinition("note", "TEXT", false),
                                ColumnDefinition("created_at", "TEXT", true),
                                ColumnDefinition("updated_at", "TEXT", true),
                                ColumnDefinition("deleted_at", "TEXT", false),
                            ),
                        primaryKeys = listOf("id"),
                    ),
                "pages" to
                    TableDefinition(
                        columns =
                            listOf(
                                ColumnDefinition("id", "TEXT", true),
                                ColumnDefinition("project_id", "TEXT", true),
                                ColumnDefinition("sequence", "INTEGER", true),
                                ColumnDefinition("original_image_path", "TEXT", true),
                                ColumnDefinition("width", "INTEGER", true),
                                ColumnDefinition("height", "INTEGER", true),
                                ColumnDefinition("rotation", "INTEGER", true),
                                ColumnDefinition("crop_left", "REAL", true),
                                ColumnDefinition("crop_top", "REAL", true),
                                ColumnDefinition("crop_right", "REAL", true),
                                ColumnDefinition("crop_bottom", "REAL", true),
                                ColumnDefinition("captured_at", "TEXT", true),
                                ColumnDefinition("content_hash", "TEXT", true),
                                ColumnDefinition("perceptual_hash", "TEXT", true),
                                ColumnDefinition("quality_state", "TEXT", true),
                                ColumnDefinition("ocr_state", "TEXT", true),
                            ),
                        primaryKeys = listOf("id"),
                        indices =
                            listOf(
                                IndexDefinition(
                                    "index_pages_project_id_sequence",
                                    true,
                                    listOf("project_id", "sequence"),
                                ),
                            ),
                    ),
                "ocr_results" to
                    TableDefinition(
                        columns =
                            listOf(
                                ColumnDefinition("page_id", "TEXT", true),
                                ColumnDefinition("full_text", "TEXT", true),
                                ColumnDefinition("blocks_json", "TEXT", true),
                                ColumnDefinition("edited_text", "TEXT", false),
                                ColumnDefinition("engine_version", "TEXT", true),
                                ColumnDefinition("source_image_hash", "TEXT", true),
                                ColumnDefinition("processed_at", "TEXT", true),
                            ),
                        primaryKeys = listOf("page_id"),
                    ),
                "export_records" to
                    TableDefinition(
                        columns =
                            listOf(
                                ColumnDefinition("id", "TEXT", true),
                                ColumnDefinition("project_id", "TEXT", true),
                                ColumnDefinition("type", "TEXT", true),
                                ColumnDefinition("target_uri", "TEXT", false),
                                ColumnDefinition("state", "TEXT", true),
                                ColumnDefinition("created_at", "TEXT", true),
                                ColumnDefinition("completed_at", "TEXT", false),
                                ColumnDefinition("error_code", "TEXT", false),
                            ),
                        primaryKeys = listOf("id"),
                    ),
            )
    }

    private data class TableDefinition(
        val columns: List<ColumnDefinition>,
        val primaryKeys: List<String> = emptyList(),
        val indices: List<IndexDefinition> = emptyList(),
        val foreignKeys: List<ForeignKeyDefinition> = emptyList(),
    )

    private data class ColumnDefinition(
        val name: String,
        val type: String,
        val notNull: Boolean,
    )

    private data class IndexDefinition(
        val name: String,
        val unique: Boolean,
        val columns: List<String>,
    )

    private data class ForeignKeyDefinition(
        val column: String,
        val referencedTable: String,
        val referencedColumn: String,
    )
}
