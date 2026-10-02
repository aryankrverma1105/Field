const pool = require('../db/pool');

function formatUtcDatetime3(dateInput) {
  const d = dateInput instanceof Date ? dateInput : new Date(dateInput || Date.now());
  if (isNaN(d.getTime())) {
    throw new Error('Invalid date provided');
  }
  return d.toISOString().slice(0, 23).replace('T', ' ');
}

class CustomerService {
  async create({ id, name, phone, address, createdBy, operationId }) {
    if (!id || !name || !createdBy || !operationId) {
      const err = new Error('id, name, createdBy, and operationId are required');
      err.statusCode = 400;
      throw err;
    }

    const [existing] = await pool.execute('SELECT * FROM customers WHERE operation_id = ?', [operationId]);
    if (existing.length > 0) {
      return { success: true, alreadyProcessed: true, record: existing[0] };
    }

    await pool.execute(
      `INSERT INTO customers (id, name, phone, address, created_by, operation_id)
       VALUES (?, ?, ?, ?, ?, ?)`,
      [id, name, phone || null, address || null, createdBy, operationId]
    );

    const [rows] = await pool.execute('SELECT * FROM customers WHERE id = ?', [id]);
    return { success: true, alreadyProcessed: false, record: rows[0] };
  }

  async getHistory({ since = null }) {
    let query = 'SELECT * FROM customers WHERE 1=1';
    const params = [];

    if (since) {
      query += ' AND created_at >= ?';
      params.push(formatUtcDatetime3(since));
    }
    query += ' ORDER BY created_at ASC';

    const [rows] = await pool.execute(query, params);
    return rows;
  }
}

module.exports = new CustomerService();
