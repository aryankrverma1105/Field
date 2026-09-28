const pool = require('./pool');

async function migrate() {
  console.log('[MIGRATION] Starting schema migration...');
  const conn = await pool.getConnection();

  try {
    // 1. Users table
    await conn.query(`
      CREATE TABLE IF NOT EXISTS users (
        id VARCHAR(36) PRIMARY KEY,
        firebase_uid VARCHAR(128) UNIQUE,
        phone_e164 VARCHAR(32) NOT NULL,
        role VARCHAR(32) NOT NULL DEFAULT 'employee',
        name VARCHAR(255) NOT NULL,
        daily_wage DECIMAL(10,2) DEFAULT 0.00,
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);

    // 2. Sites table
    await conn.query(`
      CREATE TABLE IF NOT EXISTS sites (
        id VARCHAR(36) PRIMARY KEY,
        name VARCHAR(255) NOT NULL,
        lat DOUBLE NOT NULL,
        lng DOUBLE NOT NULL,
        geofence_radius_m DOUBLE NOT NULL DEFAULT 100.0,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);

    // 3. User Sites junction table
    await conn.query(`
      CREATE TABLE IF NOT EXISTS user_sites (
        user_id VARCHAR(36) NOT NULL,
        site_id VARCHAR(36) NOT NULL,
        PRIMARY KEY (user_id, site_id),
        INDEX idx_site (site_id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);

    // 4. Attendance table with FIX 1: Split check_in_operation_id and check_out_operation_id
    await conn.query(`
      CREATE TABLE IF NOT EXISTS attendance (
        id VARCHAR(36) PRIMARY KEY,
        user_id VARCHAR(36) NOT NULL,
        check_in_at TIMESTAMP NULL,
        check_in_lat DOUBLE NULL,
        check_in_lng DOUBLE NULL,
        check_in_photo_path VARCHAR(512) NULL,
        check_in_is_mocked BOOLEAN DEFAULT FALSE,
        check_in_operation_id VARCHAR(64) NULL,
        check_out_at TIMESTAMP NULL,
        check_out_lat DOUBLE NULL,
        check_out_lng DOUBLE NULL,
        check_out_photo_path VARCHAR(512) NULL,
        check_out_operation_id VARCHAR(64) NULL,
        geofence_status VARCHAR(32) DEFAULT 'UNKNOWN',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uq_user_checkin_op (user_id, check_in_operation_id),
        UNIQUE KEY uq_user_checkout_op (user_id, check_out_operation_id),
        INDEX idx_user_attendance (user_id, check_in_at)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);

    // 5. GPS Points table
    await conn.query(`
      CREATE TABLE IF NOT EXISTS gps_points (
        id VARCHAR(36) PRIMARY KEY,
        user_id VARCHAR(36) NOT NULL,
        lat DOUBLE NOT NULL,
        lng DOUBLE NOT NULL,
        is_mocked BOOLEAN DEFAULT FALSE,
        recorded_at TIMESTAMP NOT NULL,
        operation_id VARCHAR(64) NOT NULL,
        UNIQUE KEY uq_user_gps_op (user_id, operation_id),
        INDEX idx_user_recorded (user_id, recorded_at)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);

    // 6. Tasks table
    await conn.query(`
      CREATE TABLE IF NOT EXISTS tasks (
        id VARCHAR(36) PRIMARY KEY,
        assigned_to VARCHAR(36) NOT NULL,
        assigned_by VARCHAR(36) NOT NULL,
        title VARCHAR(255) NOT NULL,
        description TEXT,
        scheduled_date DATE,
        priority VARCHAR(32) DEFAULT 'MEDIUM',
        status VARCHAR(32) DEFAULT 'PENDING',
        operation_id VARCHAR(64) NULL,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        INDEX idx_assigned_to (assigned_to, scheduled_date)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);

    // 7. Customers table
    await conn.query(`
      CREATE TABLE IF NOT EXISTS customers (
        id VARCHAR(36) PRIMARY KEY,
        name VARCHAR(255) NOT NULL,
        phone VARCHAR(32),
        address TEXT,
        created_by VARCHAR(36) NOT NULL,
        operation_id VARCHAR(64) NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        INDEX idx_created_by (created_by)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);

    // 8. Visits table with split keys for check-in and complete
    await conn.query(`
      CREATE TABLE IF NOT EXISTS visits (
        id VARCHAR(36) PRIMARY KEY,
        customer_id VARCHAR(36) NOT NULL,
        assigned_to VARCHAR(36) NOT NULL,
        scheduled_for TIMESTAMP NULL,
        status VARCHAR(32) DEFAULT 'SCHEDULED',
        check_in_at TIMESTAMP NULL,
        check_in_operation_id VARCHAR(64) NULL,
        check_out_at TIMESTAMP NULL,
        complete_operation_id VARCHAR(64) NULL,
        meeting_outcome TEXT,
        notes TEXT,
        follow_up_date DATE NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uq_visit_checkin_op (assigned_to, check_in_operation_id),
        UNIQUE KEY uq_visit_complete_op (assigned_to, complete_operation_id),
        INDEX idx_visit_assigned (assigned_to, scheduled_for)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);

    // 9. Chat Messages table
    await conn.query(`
      CREATE TABLE IF NOT EXISTS chat_messages (
        id VARCHAR(36) PRIMARY KEY,
        channel_id VARCHAR(36) NOT NULL,
        sender_id VARCHAR(36) NOT NULL,
        body TEXT NOT NULL,
        sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        operation_id VARCHAR(64) NOT NULL,
        UNIQUE KEY uq_chat_op (sender_id, operation_id),
        INDEX idx_channel_sent (channel_id, sent_at)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);

    // 10. Expenses table
    await conn.query(`
      CREATE TABLE IF NOT EXISTS expenses (
        id VARCHAR(36) PRIMARY KEY,
        user_id VARCHAR(36) NOT NULL,
        amount DECIMAL(10,2) NOT NULL,
        category VARCHAR(64) NOT NULL,
        receipt_photo_path VARCHAR(512),
        status VARCHAR(32) DEFAULT 'PENDING',
        reviewed_by VARCHAR(36),
        operation_id VARCHAR(64) NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uq_expense_op (user_id, operation_id),
        INDEX idx_expense_user (user_id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);

    // 11. Notifications table
    await conn.query(`
      CREATE TABLE IF NOT EXISTS notifications (
        id VARCHAR(36) PRIMARY KEY,
        user_id VARCHAR(36) NOT NULL,
        body TEXT NOT NULL,
        read_at TIMESTAMP NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        INDEX idx_notif_user (user_id, read_at)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);

    // 12. Audit Logs table
    await conn.query(`
      CREATE TABLE IF NOT EXISTS audit_logs (
        id VARCHAR(36) PRIMARY KEY,
        actor_id VARCHAR(36) NOT NULL,
        action VARCHAR(64) NOT NULL,
        target_type VARCHAR(64) NOT NULL,
        target_id VARCHAR(36) NOT NULL,
        metadata_json TEXT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        INDEX idx_audit_actor (actor_id, created_at)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);

    console.log('[MIGRATION] Migration completed successfully.');
  } catch (err) {
    console.error('[MIGRATION] Error running migration:', err);
    throw err;
  } finally {
    conn.release();
  }
}

if (require.main === module) {
  migrate().then(() => {
    pool.end();
    process.exit(0);
  }).catch((err) => {
    console.error(err);
    pool.end();
    process.exit(1);
  });
}

module.exports = migrate;
