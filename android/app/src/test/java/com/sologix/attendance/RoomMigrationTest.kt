package com.sologix.attendance

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.MIGRATION_1_2
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
}
