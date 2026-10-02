const pool = require('../db/pool');

class WorkforceService {
  /**
   * Update daily wage: Admin-only server-side validation
   */
  async updateDailyWage({ targetUserId, newWage, requesterRole }) {
    if (requesterRole !== 'admin') {
      const err = new Error('Forbidden: Only administrators can modify employee wages');
      err.statusCode = 403;
      throw err;
    }

    if (newWage === undefined || newWage === null || isNaN(Number(newWage)) || Number(newWage) < 0) {
      const err = new Error('A valid non-negative daily wage is required');
      err.statusCode = 400;
      throw err;
    }

    const [result] = await pool.execute(
      'UPDATE users SET daily_wage = ? WHERE id = ?',
      [Number(newWage), targetUserId]
    );

    if (result.affectedRows === 0) {
      const err = new Error(`User ${targetUserId} not found`);
      err.statusCode = 404;
      throw err;
    }

    const [rows] = await pool.execute('SELECT id, name, phone_e164, role, daily_wage, status FROM users WHERE id = ?', [targetUserId]);
    return { success: true, user: rows[0] };
  }
}

module.exports = new WorkforceService();
