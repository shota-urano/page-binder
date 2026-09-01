package com.pagebinder.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
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
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migrationFrom1To2RetainsPagesAndEnforcesProjectForeignKey() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
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
            .runMigrationsAndValidate(DATABASE_NAME, 2, true, PageBinderDatabase.MIGRATION_1_2)
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

    private companion object {
        const val DATABASE_NAME = "pagebinder-migration-test"
        const val PROJECT_ID = "10000000-0000-0000-0000-000000000001"
        const val PAGE_ID = "20000000-0000-0000-0000-000000000002"
        const val INVALID_PAGE_ID = "30000000-0000-0000-0000-000000000003"
        const val UNKNOWN_PROJECT_ID = "40000000-0000-0000-0000-000000000004"
        const val CREATED_AT = "2026-09-01T00:00:00Z"
    }
}
