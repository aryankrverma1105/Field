const pool = require('../db/pool');

class AttendanceService {
  /**
   * Idempotent Check-in
   * Inserts row or no-ops if (user_id, check_in_operation_id) already exists.
   */
  async checkIn({
    id,
    userId,
    checkInAt = new Date(),
    lat,
    lng,
    photoPath = null,
    isMocked = false,
    operationId,
    geofenceStatus = 'INSIDE'
  }) {
    if (!userId || !operationId) {
      throw new Error('userId and operationId are required');
    }

    const recordId = id || require('crypto').randomUUID();
    const formattedCheckInAt = checkInAt instanceof Date ? checkInAt : new Date(checkInAt);

    const query = `
      INSERT INTO attendance (
        id, user_id, check_in_at, check_in_lat, check_in_lng,
        check_in_photo_path, check_in_is_mocked, check_in_operation_id, geofence_status
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE id = id;
    `;

    await pool.execute(query, [
      recordId,
      userId,
      formattedCheckInAt,
      lat !== undefined ? lat : null,
      lng !== undefined ? lng : null,
      photoPath,
      Boolean(isMocked),
      operationId,
      geofenceStatus
    ]);

    // Return current record matching this user and operationId
    const [rows] = await pool.execute(
      `SELECT * FROM attendance WHERE user_id = ? AND check_in_operation_id = ?`,
      [userId, operationId]
    );

    return rows[0];
  }

  /**
   * Idempotent Conditional Check-out (Fix 1)
   * Updates row where check_out_operation_id IS NULL.
   * If affectedRows === 0, inspects whether operationId already matches (success) or mismatch (conflict).
   */
  async checkOut({
    userId,
    checkOutAt = new Date(),
    lat,
    lng,
    photoPath = null,
    operationId
  }) {
    if (!userId || !operationId) {
      throw new Error('userId and operationId are required');
    }

    // 1. Idempotency Check: check if this operationId was ALREADY applied
    const [existingWithOp] = await pool.execute(
      `SELECT * FROM attendance WHERE user_id = ? AND check_out_operation_id = ?`,
      [userId, operationId]
    );

    if (existingWithOp.length > 0) {
      // Already applied! Return success without altering state
      return { success: true, alreadyProcessed: true, record: existingWithOp[0] };
    }

    const formattedCheckOutAt = checkOutAt instanceof Date ? checkOutAt : new Date(checkOutAt);

    // 2. Conditional update targeting the latest open attendance record for this user
    try {
      const [result] = await pool.execute(
        `UPDATE attendance
         SET check_out_at = ?,
             check_out_lat = ?,
             check_out_lng = ?,
             check_out_photo_path = ?,
             check_out_operation_id = ?
         WHERE user_id = ? AND check_out_operation_id IS NULL
         ORDER BY check_in_at DESC
         LIMIT 1`,
        [
          formattedCheckOutAt,
          lat !== undefined ? lat : null,
          lng !== undefined ? lng : null,
          photoPath,
          operationId,
          userId
        ]
      );

      // If 1 row updated, it was successfully applied
      if (result.affectedRows === 1) {
        const [rows] = await pool.execute(
          `SELECT * FROM attendance WHERE user_id = ? AND check_out_operation_id = ?`,
          [userId, operationId]
        );
        return { success: true, alreadyProcessed: false, record: rows[0] };
      }
    } catch (err) {
      if (err.code === 'ER_DUP_ENTRY') {
        // Race condition: concurrent retry already committed
        const [rows] = await pool.execute(
          `SELECT * FROM attendance WHERE user_id = ? AND check_out_operation_id = ?`,
          [userId, operationId]
        );
        if (rows.length > 0) {
          return { success: true, alreadyProcessed: true, record: rows[0] };
        }
      }
      throw err;
    }

    // 3. Otherwise, either no attendance record exists, or it was already checked out via another operation
    const [latestRecord] = await pool.execute(
      `SELECT * FROM attendance WHERE user_id = ? ORDER BY check_in_at DESC LIMIT 1`,
      [userId]
    );

    if (latestRecord.length === 0) {
      const error = new Error('No attendance check-in found to check out from');
      error.statusCode = 404;
      throw error;
    }

    const error = new Error('Attendance session has already been checked out by a different operation');
    error.statusCode = 409;
    error.existingRecord = latestRecord[0];
    throw error;
  }
}

module.exports = new AttendanceService();
