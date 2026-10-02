const pool = require('../db/pool');

function formatUtcDatetime3(dateInput) {
  const d = dateInput instanceof Date ? dateInput : new Date(dateInput || Date.now());
  if (isNaN(d.getTime())) {
    throw new Error('Invalid date provided');
  }
  return d.toISOString().slice(0, 23).replace('T', ' ');
}

function haversineDistanceMeters(lat1, lon1, lat2, lon2) {
  const R = 6371000; // meters
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
    Math.sin(dLon / 2) * Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

class AttendanceService {
  /**
   * Idempotent Check-in
   * - Computes geofenceStatus server-side: 'INSIDE' if within assigned site radius, 'OUTSIDE' if outside, 'UNKNOWN' if no site assigned
   * - Never trusts client-supplied geofenceStatus
   * - Detects PK collision (same id with different operationId -> 409 with existing record, never undefined)
   * - Idempotent retry (same operationId -> 200 with existing record)
   */
  async checkIn({
    id,
    userId,
    checkInAt = new Date(),
    lat,
    lng,
    photoPath = null,
    isMocked = false,
    operationId
  }) {
    if (!id || !userId || !operationId) {
      const err = new Error('id, userId, and operationId are required for check-in');
      err.statusCode = 400;
      throw err;
    }

    const formattedCheckInAt = formatUtcDatetime3(checkInAt);

    // 1. Check if a record with this primary key 'id' already exists
    const [existingById] = await pool.execute(
      'SELECT * FROM attendance WHERE id = ?',
      [id]
    );

    if (existingById.length > 0) {
      const existing = existingById[0];
      if (existing.check_in_operation_id === operationId && existing.user_id === userId) {
        // Idempotent retry of exact same check-in
        return { success: true, alreadyProcessed: true, record: existing };
      }
      // Primary-key collision: same ID with different operationId or different user
      const err = new Error('Primary key collision: attendance record already exists with a different operationId');
      err.statusCode = 409;
      err.existingRecord = existing;
      throw err;
    }

    // 2. Check if this user already applied this operationId with a different ID
    const [existingByOp] = await pool.execute(
      'SELECT * FROM attendance WHERE user_id = ? AND check_in_operation_id = ?',
      [userId, operationId]
    );

    if (existingByOp.length > 0) {
      return { success: true, alreadyProcessed: true, record: existingByOp[0] };
    }

    // 3. Compute geofence status server-side from user_sites
    let computedGeofenceStatus = 'UNKNOWN';
    if (lat !== undefined && lng !== undefined && lat !== null && lng !== null) {
      const [assignedSites] = await pool.execute(
        `SELECT s.id, s.lat, s.lng, s.geofence_radius_m
         FROM user_sites us
         JOIN sites s ON us.site_id = s.id
         WHERE us.user_id = ?`,
        [userId]
      );

      if (assignedSites.length > 0) {
        let insideAny = false;
        for (const site of assignedSites) {
          const dist = haversineDistanceMeters(Number(lat), Number(lng), Number(site.lat), Number(site.lng));
          if (dist <= Number(site.geofence_radius_m)) {
            insideAny = true;
            break;
          }
        }
        computedGeofenceStatus = insideAny ? 'INSIDE' : 'OUTSIDE';
      }
    }

    // 4. Insert record with computed geofence status (never client-supplied)
    const query = `
      INSERT INTO attendance (
        id, user_id, check_in_at, check_in_lat, check_in_lng,
        check_in_photo_path, check_in_is_mocked, check_in_operation_id, geofence_status
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
    `;

    try {
      await pool.execute(query, [
        id,
        userId,
        formattedCheckInAt,
        lat !== undefined ? lat : null,
        lng !== undefined ? lng : null,
        photoPath,
        Boolean(isMocked),
        operationId,
        computedGeofenceStatus
      ]);
    } catch (dbErr) {
      if (dbErr.code === 'ER_DUP_ENTRY') {
        // Concurrent race condition
        const [raceRows] = await pool.execute(
          'SELECT * FROM attendance WHERE id = ?',
          [id]
        );
        if (raceRows.length > 0) {
          const row = raceRows[0];
          if (row.check_in_operation_id === operationId) {
            return { success: true, alreadyProcessed: true, record: row };
          }
          const err = new Error('Primary key collision: attendance record already exists with a different operationId');
          err.statusCode = 409;
          err.existingRecord = row;
          throw err;
        }
      }
      throw dbErr;
    }

    const [createdRows] = await pool.execute(
      'SELECT * FROM attendance WHERE id = ?',
      [id]
    );

    return { success: true, alreadyProcessed: false, record: createdRows[0] };
  }

  /**
   * Idempotent Conditional Check-out targeting a SPECIFIC row
   * WHERE id = ? AND user_id = ? AND check_in_at IS NOT NULL AND check_out_operation_id IS NULL
   */
  async checkOut({
    id,
    userId,
    checkOutAt = new Date(),
    lat,
    lng,
    photoPath = null,
    operationId
  }) {
    if (!id || !userId || !operationId) {
      const err = new Error('id, userId, and operationId are required for check-out');
      err.statusCode = 400;
      throw err;
    }

    // 1. Idempotency Check: check if this operationId was ALREADY applied for this user
    const [existingWithOp] = await pool.execute(
      'SELECT * FROM attendance WHERE user_id = ? AND check_out_operation_id = ?',
      [userId, operationId]
    );

    if (existingWithOp.length > 0) {
      return { success: true, alreadyProcessed: true, record: existingWithOp[0] };
    }

    const formattedCheckOutAt = formatUtcDatetime3(checkOutAt);

    // 2. Conditional update targeting the specific row
    const [result] = await pool.execute(
      `UPDATE attendance
       SET check_out_at = ?,
           check_out_lat = ?,
           check_out_lng = ?,
           check_out_photo_path = ?,
           check_out_operation_id = ?
       WHERE id = ? AND user_id = ? AND check_in_at IS NOT NULL
         AND check_out_operation_id IS NULL`,
      [
        formattedCheckOutAt,
        lat !== undefined ? lat : null,
        lng !== undefined ? lng : null,
        photoPath,
        operationId,
        id,
        userId
      ]
    );

    if (result.affectedRows === 1) {
      const [rows] = await pool.execute(
        'SELECT * FROM attendance WHERE id = ?',
        [id]
      );
      return { success: true, alreadyProcessed: false, record: rows[0] };
    }

    // 3. If 0 rows updated, inspect why:
    const [rowById] = await pool.execute(
      'SELECT * FROM attendance WHERE id = ? AND user_id = ?',
      [id, userId]
    );

    if (rowById.length === 0) {
      const error = new Error(`Attendance record not found for id ${id}`);
      error.statusCode = 404;
      throw error;
    }

    const existing = rowById[0];

    if (existing.check_out_operation_id === operationId) {
      return { success: true, alreadyProcessed: true, record: existing };
    }

    if (existing.check_out_operation_id !== null) {
      const error = new Error('Attendance session has already been checked out by a different operation');
      error.statusCode = 409;
      error.existingRecord = existing;
      throw error;
    }

    if (existing.check_in_at === null) {
      const error = new Error('Attendance session has no check-in recorded');
      error.statusCode = 409;
      error.existingRecord = existing;
      throw error;
    }

    const error = new Error('Check-out failed: unable to update record');
    error.statusCode = 409;
    error.existingRecord = existing;
    throw error;
  }

  /**
   * Idempotent GPS point recording
   */
  async recordGpsPoint({
    id,
    userId,
    lat,
    lng,
    isMocked = false,
    recordedAt = new Date(),
    operationId
  }) {
    if (!id || !userId || !operationId || lat === undefined || lng === undefined) {
      const err = new Error('id, userId, lat, lng, and operationId are required for gps-point');
      err.statusCode = 400;
      throw err;
    }

    const formattedRecordedAt = formatUtcDatetime3(recordedAt);

    // Check if operationId already applied
    const [existingByOp] = await pool.execute(
      'SELECT * FROM gps_points WHERE user_id = ? AND operation_id = ?',
      [userId, operationId]
    );

    if (existingByOp.length > 0) {
      return { success: true, alreadyProcessed: true, record: existingByOp[0] };
    }

    // Check PK collision
    const [existingById] = await pool.execute(
      'SELECT * FROM gps_points WHERE id = ?',
      [id]
    );

    if (existingById.length > 0) {
      const err = new Error('Primary key collision: GPS point already exists with different operationId');
      err.statusCode = 409;
      err.existingRecord = existingById[0];
      throw err;
    }

    await pool.execute(
      `INSERT INTO gps_points (id, user_id, lat, lng, is_mocked, recorded_at, operation_id)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
      [id, userId, lat, lng, Boolean(isMocked), formattedRecordedAt, operationId]
    );

    const [rows] = await pool.execute('SELECT * FROM gps_points WHERE id = ?', [id]);
    return { success: true, alreadyProcessed: false, record: rows[0] };
  }

  /**
   * Fetch attendance history for the requesting user ordered by created_at ASC.
   * Supports optional `since` ISO8601 query parameter for incremental hydration.
   */
  async getHistory({ userId, since = null }) {
    if (!userId) {
      const err = new Error('userId is required');
      err.statusCode = 400;
      throw err;
    }

    let query = 'SELECT * FROM attendance WHERE user_id = ?';
    const params = [userId];

    if (since) {
      query += ' AND created_at >= ?';
      params.push(formatUtcDatetime3(since));
    }

    query += ' ORDER BY created_at ASC';

    const [rows] = await pool.execute(query, params);
    return rows;
  }
}

module.exports = new AttendanceService();
