const pool = require('../db/pool');

function formatUtcDatetime3(dateInput) {
  const d = dateInput instanceof Date ? dateInput : new Date(dateInput || Date.now());
  if (isNaN(d.getTime())) {
    throw new Error('Invalid date provided');
  }
  return d.toISOString().slice(0, 23).replace('T', ' ');
}

class TaskService {
  async create({ id, assignedTo, assignedBy, title, description, scheduledDate, priority = 'MEDIUM', operationId }) {
    if (!id || !assignedTo || !title || !operationId) {
      const err = new Error('id, assignedTo, title, and operationId are required');
      err.statusCode = 400;
      throw err;
    }

    const [existing] = await pool.execute('SELECT * FROM tasks WHERE operation_id = ?', [operationId]);
    if (existing.length > 0) {
      return { success: true, alreadyProcessed: true, record: existing[0] };
    }

    await pool.execute(
      `INSERT INTO tasks (id, assigned_to, assigned_by, title, description, scheduled_date, priority, status, operation_id)
       VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)`,
      [id, assignedTo, assignedBy, title, description || null, scheduledDate || null, priority, operationId]
    );

    const [rows] = await pool.execute('SELECT * FROM tasks WHERE id = ?', [id]);
    return { success: true, alreadyProcessed: false, record: rows[0] };
  }

  async updateStatus({ id, status }) {
    const [result] = await pool.execute(
      'UPDATE tasks SET status = ? WHERE id = ?',
      [status, id]
    );

    if (result.affectedRows === 0) {
      const err = new Error(`Task ${id} not found`);
      err.statusCode = 404;
      throw err;
    }

    const [rows] = await pool.execute('SELECT * FROM tasks WHERE id = ?', [id]);
    return { success: true, record: rows[0] };
  }

  async getHistory({ userId, since = null }) {
    let query = 'SELECT * FROM tasks WHERE assigned_to = ?';
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

module.exports = new TaskService();
