const pool = require('../db/pool');

function formatUtcDatetime3(dateInput) {
  const d = dateInput instanceof Date ? dateInput : new Date(dateInput || Date.now());
  if (isNaN(d.getTime())) {
    throw new Error('Invalid date provided');
  }
  return d.toISOString().slice(0, 23).replace('T', ' ');
}

class VisitService {
  /**
   * Action 1: Check-in with evidence
   */
  async checkIn({ id, customerId, assignedTo, checkInAt = new Date(), operationId }) {
    if (!id || !assignedTo || !operationId) {
      const err = new Error('id, assignedTo, and operationId are required');
      err.statusCode = 400;
      throw err;
    }

    const formattedCheckInAt = formatUtcDatetime3(checkInAt);

    // Idempotency check by operationId
    const [existingByOp] = await pool.execute(
      'SELECT * FROM visits WHERE assigned_to = ? AND check_in_operation_id = ?',
      [assignedTo, operationId]
    );
    if (existingByOp.length > 0) {
      return { success: true, alreadyProcessed: true, record: existingByOp[0] };
    }

    // Check if row exists
    const [existingById] = await pool.execute('SELECT * FROM visits WHERE id = ?', [id]);

    if (existingById.length > 0) {
      // Update existing scheduled visit
      await pool.execute(
        `UPDATE visits
         SET check_in_at = ?,
             check_in_operation_id = ?,
             status = 'IN_PROGRESS'
         WHERE id = ? AND assigned_to = ? AND check_in_operation_id IS NULL`,
        [formattedCheckInAt, operationId, id, assignedTo]
      );
    } else {
      // Insert new visit record
      await pool.execute(
        `INSERT INTO visits (id, customer_id, assigned_to, check_in_at, check_in_operation_id, status)
         VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS')`,
        [id, customerId || id, assignedTo, formattedCheckInAt, operationId]
      );
    }

    const [rows] = await pool.execute('SELECT * FROM visits WHERE id = ?', [id]);
    return { success: true, alreadyProcessed: false, record: rows[0] };
  }

  /**
   * Action 2: Add evidence/notes (never touches status or check_out_at)
   */
  async updateNotes({ id, assignedTo, notes, meetingOutcome, followUpDate = null }) {
    if (!id || !assignedTo) {
      const err = new Error('id and assignedTo are required');
      err.statusCode = 400;
      throw err;
    }

    const [existing] = await pool.execute('SELECT * FROM visits WHERE id = ? AND assigned_to = ?', [id, assignedTo]);
    if (existing.length === 0) {
      const err = new Error(`Visit ${id} not found`);
      err.statusCode = 404;
      throw err;
    }

    // Strict guarantee: NEVER modifies status or check_out_at
    await pool.execute(
      `UPDATE visits 
       SET notes = COALESCE(?, notes),
           meeting_outcome = COALESCE(?, meeting_outcome),
           follow_up_date = COALESCE(?, follow_up_date)
       WHERE id = ? AND assigned_to = ?`,
      [notes || null, meetingOutcome || null, followUpDate || null, id, assignedTo]
    );

    const [rows] = await pool.execute('SELECT * FROM visits WHERE id = ?', [id]);
    return { success: true, record: rows[0] };
  }

  /**
   * Action 3: Complete visit (Terminal action, sets status = 'COMPLETED' and check_out_at)
   */
  async complete({ id, assignedTo, checkOutAt = new Date(), operationId, meetingOutcome = null }) {
    if (!id || !assignedTo || !operationId) {
      const err = new Error('id, assignedTo, and operationId are required');
      err.statusCode = 400;
      throw err;
    }

    // Idempotency check by operationId
    const [existingByOp] = await pool.execute(
      'SELECT * FROM visits WHERE assigned_to = ? AND complete_operation_id = ?',
      [assignedTo, operationId]
    );
    if (existingByOp.length > 0) {
      return { success: true, alreadyProcessed: true, record: existingByOp[0] };
    }

    const formattedCheckOutAt = formatUtcDatetime3(checkOutAt);

    const [result] = await pool.execute(
      `UPDATE visits
       SET check_out_at = ?,
           complete_operation_id = ?,
           status = 'COMPLETED',
           meeting_outcome = COALESCE(?, meeting_outcome)
       WHERE id = ? AND assigned_to = ? AND complete_operation_id IS NULL`,
      [formattedCheckOutAt, operationId, meetingOutcome || null, id, assignedTo]
    );

    if (result.affectedRows === 0) {
      const [rows] = await pool.execute('SELECT * FROM visits WHERE id = ? AND assigned_to = ?', [id, assignedTo]);
      if (rows.length === 0) {
        const err = new Error(`Visit ${id} not found`);
        err.statusCode = 404;
        throw err;
      }
      return { success: true, alreadyProcessed: true, record: rows[0] };
    }

    const [rows] = await pool.execute('SELECT * FROM visits WHERE id = ?', [id]);
    return { success: true, alreadyProcessed: false, record: rows[0] };
  }

  async getHistory({ assignedTo, since = null }) {
    let query = 'SELECT * FROM visits WHERE assigned_to = ?';
    const params = [assignedTo];
    if (since) {
      query += ' AND created_at >= ?';
      params.push(formatUtcDatetime3(since));
    }
    query += ' ORDER BY created_at ASC';
    const [rows] = await pool.execute(query, params);
    return rows;
  }
}

module.exports = new VisitService();
