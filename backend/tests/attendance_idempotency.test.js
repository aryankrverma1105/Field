const { test, describe, before, after } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('crypto');
const pool = require('../src/db/pool');
const attendanceService = require('../src/services/attendanceService');
const migrate = require('../src/db/migrate');

describe('Fix 1 — Split Idempotency Keys & Conditional Checkout Tests', () => {
  before(async () => {
    await migrate();
  });

  after(async () => {
    await pool.end();
  });

  async function createTestUser() {
    const id = crypto.randomUUID();
    await pool.execute(
      `INSERT INTO users (id, phone_e164, name, role, status) VALUES (?, ?, ?, ?, ?)`,
      [id, '+919999999999', 'Test Employee', 'employee', 'ACTIVE']
    );
    return id;
  }

  test('Check-in idempotency: retrying with the same check_in_operation_id does not duplicate rows', async () => {
    const userId = await createTestUser();
    try {
      const checkInOpId = `op-in-${crypto.randomUUID()}`;
      const attendanceId = crypto.randomUUID();

    // First attempt
    const firstResult = await attendanceService.checkIn({
      id: attendanceId,
      userId: userId,
      checkInAt: new Date('2026-09-25T09:00:00Z'),
      lat: 28.6139,
      lng: 77.2090,
      photoPath: '/uploads/selfies/first.jpg',
      isMocked: false,
      operationId: checkInOpId,
      geofenceStatus: 'INSIDE'
    });

    assert.equal(firstResult.id, attendanceId);
    assert.equal(firstResult.check_in_operation_id, checkInOpId);

    // Second attempt (simulating network retry with same operationId)
    const secondResult = await attendanceService.checkIn({
      id: crypto.randomUUID(), // different client ID attempt, same operationId
      userId: userId,
      checkInAt: new Date('2026-09-25T09:01:00Z'),
      lat: 28.6140,
      lng: 77.2091,
      photoPath: '/uploads/selfies/second.jpg',
      isMocked: false,
      operationId: checkInOpId,
      geofenceStatus: 'INSIDE'
    });

    // Must return the exact original record without creating a duplicate
    assert.equal(secondResult.id, attendanceId);
    assert.equal(secondResult.check_in_operation_id, checkInOpId);

    // Verify row count in database for this operationId is exactly 1
    const [countRows] = await pool.execute(
      `SELECT COUNT(*) as total FROM attendance WHERE user_id = ? AND check_in_operation_id = ?`,
      [userId, checkInOpId]
    );
    assert.equal(countRows[0].total, 1);
    } finally {
      await pool.execute(`DELETE FROM attendance WHERE user_id = ?`, [userId]);
      await pool.execute(`DELETE FROM users WHERE id = ?`, [userId]);
    }
  });

  test('Conditional check-out: successfully updates when check_out_operation_id IS NULL', async () => {
    const userId = await createTestUser();
    try {
      const checkInOpId = `op-in-${crypto.randomUUID()}`;
      const checkOutOpId = `op-out-${crypto.randomUUID()}`;
      const attendanceId = crypto.randomUUID();

      // Step 1: Create active check-in
      await attendanceService.checkIn({
        id: attendanceId,
        userId: userId,
        checkInAt: new Date('2026-09-25T10:00:00Z'),
        lat: 28.6139,
        lng: 77.2090,
        operationId: checkInOpId
      });

      // Step 2: Check-out
      const checkoutResult = await attendanceService.checkOut({
        userId: userId,
        checkOutAt: new Date('2026-09-25T18:00:00Z'),
        lat: 28.6145,
        lng: 77.2095,
        photoPath: '/uploads/selfies/out.jpg',
        operationId: checkOutOpId
      });

      assert.equal(checkoutResult.success, true);
      assert.equal(checkoutResult.alreadyProcessed, false);
      assert.equal(checkoutResult.record.check_out_operation_id, checkOutOpId);
      assert.ok(checkoutResult.record.check_out_at);
    } finally {
      await pool.execute(`DELETE FROM attendance WHERE user_id = ?`, [userId]);
      await pool.execute(`DELETE FROM users WHERE id = ?`, [userId]);
    }
  });

  test('Verification 5: Retry a check-out twice with the same check_out_operation_id (simulated timeout-then-retry)', async () => {
    const userId = await createTestUser();
    try {
      const checkInOpId = `op-in-${crypto.randomUUID()}`;
      const checkOutOpId = `op-out-${crypto.randomUUID()}`;

      // Step 1: Check-in
      await attendanceService.checkIn({
        id: crypto.randomUUID(),
        userId: userId,
        checkInAt: new Date('2026-09-25T11:00:00Z'),
        lat: 28.6139,
        lng: 77.2090,
        operationId: checkInOpId
      });

      // Step 2: First check-out attempt
      const firstCall = await attendanceService.checkOut({
        userId: userId,
        checkOutAt: new Date('2026-09-25T19:00:00Z'),
        lat: 28.6150,
        lng: 77.2099,
        operationId: checkOutOpId
      });
      assert.equal(firstCall.success, true);
      assert.equal(firstCall.alreadyProcessed, false);

      // Step 3: Second check-out call (simulated timeout retry)
      const secondCall = await attendanceService.checkOut({
        userId: userId,
        checkOutAt: new Date('2026-09-25T19:00:00Z'),
        lat: 28.6150,
        lng: 77.2099,
        operationId: checkOutOpId
      });

      // Must return success with alreadyProcessed: true and identical record data
      assert.equal(secondCall.success, true);
      assert.equal(secondCall.alreadyProcessed, true);
      assert.equal(secondCall.record.id, firstCall.record.id);
      assert.equal(secondCall.record.check_out_operation_id, checkOutOpId);

      // Step 4: Third attempt with a DIFFERENT operationId must be rejected with 409 Conflict
      const differentOpId = `op-out-${crypto.randomUUID()}`;
      await assert.rejects(
        async () => {
          await attendanceService.checkOut({
            userId: userId,
            checkOutAt: new Date('2026-09-25T19:05:00Z'),
            lat: 28.6150,
            lng: 77.2099,
            operationId: differentOpId
          });
        },
        (err) => {
          assert.equal(err.statusCode, 409);
          assert.match(err.message, /already been checked out by a different operation/);
          return true;
        }
      );
    } finally {
      await pool.execute(`DELETE FROM attendance WHERE user_id = ?`, [userId]);
      await pool.execute(`DELETE FROM users WHERE id = ?`, [userId]);
    }
  });
});
