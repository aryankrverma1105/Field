const pool = require('../db/pool');

function formatUtcDatetime3(dateInput) {
  const d = dateInput instanceof Date ? dateInput : new Date(dateInput || Date.now());
  if (isNaN(d.getTime())) {
    throw new Error('Invalid date provided');
  }
  return d.toISOString().slice(0, 23).replace('T', ' ');
}

class ExpenseService {
  async create({ id, userId, amount, category, receiptPhotoPath = null, operationId }) {
    if (!id || !userId || !amount || !category || !operationId) {
      const err = new Error('id, userId, amount, category, and operationId are required');
      err.statusCode = 400;
      throw err;
    }

    // Idempotency check by operationId
    const [existing] = await pool.execute(
      'SELECT * FROM expenses WHERE user_id = ? AND operation_id = ?',
      [userId, operationId]
    );
    if (existing.length > 0) {
      return { success: true, alreadyProcessed: true, record: existing[0] };
    }

    await pool.execute(
      `INSERT INTO expenses (id, user_id, amount, category, receipt_photo_path, status, operation_id)
       VALUES (?, ?, ?, ?, ?, 'PENDING', ?)`,
      [id, userId, amount, category, receiptPhotoPath, operationId]
    );

    const [rows] = await pool.execute('SELECT * FROM expenses WHERE id = ?', [id]);
    return { success: true, alreadyProcessed: false, record: rows[0] };
  }

  /**
   * Review Expense: Server-enforced role restriction (manager or admin only)
   */
  async review({ id, reviewerId, reviewerRole, status }) {
    if (reviewerRole !== 'manager' && reviewerRole !== 'admin') {
      const err = new Error('Forbidden: Only managers and admins can review expenses');
      err.statusCode = 403;
      throw err;
    }

    if (!['APPROVED', 'REJECTED'].includes(status)) {
      const err = new Error('Invalid status. Must be APPROVED or REJECTED');
      err.statusCode = 400;
      throw err;
    }

    const [result] = await pool.execute(
      `UPDATE expenses 
       SET status = ?, reviewed_by = ?
       WHERE id = ?`,
      [status, reviewerId, id]
    );

    if (result.affectedRows === 0) {
      const err = new Error(`Expense ${id} not found`);
      err.statusCode = 404;
      throw err;
    }

    const [rows] = await pool.execute('SELECT * FROM expenses WHERE id = ?', [id]);
    return { success: true, record: rows[0] };
  }

  async getHistory({ userId, role, since = null }) {
    let query;
    let params;
    if (role === 'manager' || role === 'admin') {
      query = 'SELECT * FROM expenses WHERE 1=1';
      params = [];
    } else {
      query = 'SELECT * FROM expenses WHERE user_id = ?';
      params = [userId];
    }

    if (since) {
      query += ' AND created_at >= ?';
      params.push(formatUtcDatetime3(since));
    }
    query += ' ORDER BY created_at ASC';

    const [rows] = await pool.execute(query, params);
    return rows;
  }
}

module.exports = new ExpenseService();
