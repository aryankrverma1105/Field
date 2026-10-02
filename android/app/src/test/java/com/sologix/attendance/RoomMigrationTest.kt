package com.sologix.attendance

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.MIGRATION_1_2
import com.sologix.attendance.data.local.MIGRATION_2_3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomMigrationTest {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2() {
        // Create database in version 1
        var db = helper.createDatabase(TEST_DB, 1).apply {
            execSQL("""
                INSERT INTO sync_queue (operation_id, entity_type, entity_id, operation_type, payload_json, status, attempt_count, last_attempt_at, created_at)
                VALUES ('op-mig-1', 'ATTENDANCE', 'att-mig-1', 'CHECK_IN', '{}', 'PENDING', 0, NULL, 1000)
            """)
            close()
        }

        // Re-open database with version 2 and provide MIGRATION_1_2
        db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        // Verify that existing data is preserved and new column last_error exists
        val cursor = db.query("SELECT operation_id, last_error FROM sync_queue WHERE operation_id = 'op-mig-1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("op-mig-1", cursor.getString(0))
        assertTrue(cursor.isNull(1))
        cursor.close()
    }

    @Test
    fun migrate2To3() {
        // Create database in version 2
        var db = helper.createDatabase(TEST_DB, 2).apply {
            execSQL("""
                INSERT INTO tasks (id, assigned_to, assigned_by, title, description, scheduled_date, priority, status, operation_id, sync_state, updated_at)
                VALUES ('task-mig-1', 'user-1', 'admin-1', 'Test Task', 'Desc', '2026-10-02', 'HIGH', 'PENDING', 'op-task-1', 'SYNCED', 1000)
            """)
            close()
        }

        // Re-open database with version 3 and provide MIGRATION_2_3
        db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        // Verify existing task data is preserved
        val taskCursor = db.query("SELECT id, title FROM tasks WHERE id = 'task-mig-1'")
        assertTrue(taskCursor.moveToFirst())
        assertEquals("task-mig-1", taskCursor.getString(0))
        assertEquals("Test Task", taskCursor.getString(1))
        taskCursor.close()

        // Verify new expenses table exists and can be written to
        db.execSQL("""
            INSERT INTO expenses (id, user_id, amount, category, receipt_photo_path, status, reviewed_by, operation_id, sync_state, created_at)
            VALUES ('exp-mig-1', 'user-1', 450.50, 'TRAVEL', NULL, 'PENDING', NULL, 'op-exp-1', 'PENDING', 2000)
        """)

        val expCursor = db.query("SELECT id, amount, category FROM expenses WHERE id = 'exp-mig-1'")
        assertTrue(expCursor.moveToFirst())
        assertEquals("exp-mig-1", expCursor.getString(0))
        assertEquals(450.50, expCursor.getDouble(1), 0.001)
        assertEquals("TRAVEL", expCursor.getString(2))
        expCursor.close()
    }
}
