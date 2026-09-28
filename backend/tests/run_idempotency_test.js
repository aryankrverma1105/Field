const assert = require('node:assert/strict');
const crypto = require('crypto');
const pool = require('../src/db/pool');
const attendanceService = require('../src/services/attendanceService');
const migrate = require('../src/db/migrate');

async function run() {
  console.log('=== RUNNING VERIFICATION 5 & FIX 1 TESTS ===');
  await migrate();
  const testUserId = crypto.randomUUID();

  try {
    // Insert test user
    await pool.execute(
      `INSERT INTO users (id, phone_e164, name, role, status) VALUES (?, ?, ?, ?, ?)`,
      [testUserId, '+919999999999', 'Test Employee', 'employee', 'ACTIVE']
    );

    // TEST 1: Check-in idempotency
    console.log('[TEST 1] Testing Check-in idempotency with same check_in_operation_id...');
    const checkInOpId = `op-in-${crypto.randomUUID()}`;
    const attendanceId = crypto.randomUUID();

    const firstIn = await attendanceService.checkIn({
      id: attendanceId,
      userId: testUserId,
      checkInAt: new Date(),
      lat: 28.6139,
      lng: 77.2090,
      photoPath: '/uploads/selfies/first.jpg',
      isMocked: false,
      operationId: checkInOpId,
      geofenceStatus: 'INSIDE'
    });
    assert.equal(firstIn.id, attendanceId);
    assert.equal(firstIn.check_in_operation_id, checkInOpId);

    // Retry with same operationId but different client id (simulating retry)
    const secondIn = await attendanceService.checkIn({
      id: crypto.randomUUID(),
      userId: testUserId,
      checkInAt: new Date(),
      lat: 28.6140,
      lng: 77.2091,
      photoPath: '/uploads/selfies/second.jpg',
      isMocked: false,
      operationId: checkInOpId,
      geofenceStatus: 'INSIDE'
    });
    assert.equal(secondIn.id, attendanceId, 'Expected second checkIn with same operationId to return existing record');
    assert.equal(secondIn.check_in_operation_id, checkInOpId);

    const [countRows] = await pool.execute(
      `SELECT COUNT(*) as total FROM attendance WHERE user_id = ? AND check_in_operation_id = ?`,
      [testUserId, checkInOpId]
    );
    assert.equal(countRows[0].total, 1, 'Expected exactly 1 attendance row in DB for this operationId');
    console.log('✔ [TEST 1 PASSED] Check-in idempotency verified: exactly 1 row persisted, retry returned original record.');

    // TEST 2: Conditional check-out
    console.log('[TEST 2] Testing conditional check-out when check_out_operation_id IS NULL...');
    const checkOutOpId = `op-out-${crypto.randomUUID()}`;
    const firstOut = await attendanceService.checkOut({
      userId: testUserId,
      checkOutAt: new Date(),
      lat: 28.6145,
      lng: 77.2095,
      photoPath: '/uploads/selfies/out.jpg',
      operationId: checkOutOpId
    });
    assert.equal(firstOut.success, true);
    assert.equal(firstOut.alreadyProcessed, false);
    assert.equal(firstOut.record.check_out_operation_id, checkOutOpId);
    console.log('✔ [TEST 2 PASSED] Conditional check-out succeeded on open session.');

    // TEST 3 (VERIFICATION ITEM 5): Retry check-out twice with same check_out_operation_id
    console.log('[TEST 3 / VERIFICATION 5] Retrying check-out with same check_out_operation_id...');
    const retryOut = await attendanceService.checkOut({
      userId: testUserId,
      checkOutAt: new Date(),
      lat: 28.6145,
      lng: 77.2095,
      photoPath: '/uploads/selfies/out.jpg',
      operationId: checkOutOpId
    });
    assert.equal(retryOut.success, true);
    assert.equal(retryOut.alreadyProcessed, true);
    assert.equal(retryOut.record.id, firstOut.record.id);
    assert.equal(retryOut.record.check_out_operation_id, checkOutOpId);
    console.log('✔ [TEST 3 / VERIFICATION 5 PASSED] Retry check-out returned same result without duplicate side-effects (alreadyProcessed: true).');

    // TEST 4: Attempt check-out with a DIFFERENT operationId when session is already closed -> 409 Conflict
    console.log('[TEST 4] Testing checkout attempt with conflicting operationId on already-closed session...');
    let threwConflict = false;
    try {
      await attendanceService.checkOut({
        userId: testUserId,
        checkOutAt: new Date(),
        lat: 28.6150,
        lng: 77.2099,
        operationId: `op-out-conflict-${crypto.randomUUID()}`
      });
    } catch (err) {
      if (err.statusCode === 409) {
        threwConflict = true;
      }
    }
    assert.equal(threwConflict, true, 'Expected 409 conflict when session already checked out with different operationId');
    console.log('✔ [TEST 4 PASSED] Conflicting checkout rejected with 409 Conflict.');

    console.log('\n=== ALL FIX 1 & VERIFICATION 5 TESTS PASSED SUCCESSFULLY ===\n');
  } finally {
    await pool.execute(`DELETE FROM attendance WHERE user_id = ?`, [testUserId]);
    await pool.execute(`DELETE FROM users WHERE id = ?`, [testUserId]);
    await pool.end();
  }
}

run().catch((err) => {
  console.error('FATAL TEST ERROR:', err);
  process.exit(1);
});
