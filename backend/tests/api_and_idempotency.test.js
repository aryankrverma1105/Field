const { test, describe, before, after } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('crypto');
const http = require('node:http');
const { spawn } = require('node:child_process');
const jwt = require('jsonwebtoken');

// Import PRODUCTION code
const app = require('../src/server');
const pool = require('../src/db/pool');
const env = require('../src/config/env');
const migrate = require('../src/db/migrate');
const attendanceService = require('../src/services/attendanceService');

describe('Backend Hardening, Idempotency & HTTP API Tests', () => {
  let server;
  let baseUrl;
  let testUserId;
  let validToken;

  before(async () => {
    await migrate();

    // Start HTTP server on an ephemeral port
    await new Promise((resolve) => {
      server = app.listen(0, '127.0.0.1', () => {
        const port = server.address().port;
        baseUrl = `http://127.0.0.1:${port}`;
        resolve();
      });
    });

    testUserId = crypto.randomUUID();
    // Seed test user in DB
    await pool.execute(
      `INSERT INTO users (id, phone_e164, name, role, status) VALUES (?, ?, ?, ?, ?)
       ON DUPLICATE KEY UPDATE id = id`,
      [testUserId, '+919999999999', 'Audit Test Employee', 'employee', 'ACTIVE']
    );

    // Create valid test JWT signed directly with env.JWT_SECRET
    validToken = jwt.sign(
      { userId: testUserId, role: 'employee' },
      env.JWT_SECRET,
      { algorithm: 'HS256', expiresIn: '1h' }
    );
  });

  after(async () => {
    if (server) {
      await new Promise((resolve) => server.close(resolve));
    }
    // Clean up test data
    if (testUserId) {
      await pool.execute('DELETE FROM gps_points WHERE user_id = ?', [testUserId]);
      await pool.execute('DELETE FROM attendance WHERE user_id = ?', [testUserId]);
      await pool.execute('DELETE FROM users WHERE id = ?', [testUserId]);
    }
    await pool.end();
  });

  // --- Part A Item 1: Refusing to boot on missing env vars ---
  test('Server refuses to boot and exits with code 1 if required env vars are missing', async () => {
    const child = spawn(process.execPath, ['src/config/env.js'], {
      cwd: process.cwd(),
      env: {
        PATH: process.env.PATH,
        DOTENV_CONFIG_PATH: 'nonexistent.env'
      }
    });

    let stderr = '';
    child.stderr.on('data', (d) => { stderr += d.toString(); });

    const exitCode = await new Promise((resolve) => {
      child.on('close', resolve);
    });

    assert.equal(exitCode, 1, 'Expected process to exit with code 1 when env vars are missing');
    assert.match(stderr, /Missing required environment variable/i);
    assert.match(stderr, /DATABASE_PASSWORD/);
  });

  // --- Part A Item 2: Health check ---
  test('GET /health returns 200 OK with status ok', async () => {
    const res = await fetch(`${baseUrl}/health`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.status, 'ok');
    assert.ok(body.timestamp);
  });

  // --- Part A Item 2: JWT middleware tests ---
  test('POST /api/attendance/check-in rejects missing token with 401', async () => {
    const res = await fetch(`${baseUrl}/api/attendance/check-in`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: crypto.randomUUID(), operationId: 'op-1' })
    });
    assert.equal(res.status, 401);
  });

  test('POST /api/attendance/check-in rejects expired token with 401', async () => {
    const expiredToken = jwt.sign(
      { userId: testUserId },
      env.JWT_SECRET,
      { algorithm: 'HS256', expiresIn: -10 }
    );
    const res = await fetch(`${baseUrl}/api/attendance/check-in`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${expiredToken}`
      },
      body: JSON.stringify({ id: crypto.randomUUID(), operationId: 'op-2' })
    });
    assert.equal(res.status, 401);
  });

  test('POST /api/attendance/check-in rejects alg:none token with 401', async () => {
    // Construct unverified token with alg: none
    const header = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url');
    const payload = Buffer.from(JSON.stringify({ userId: testUserId })).toString('base64url');
    const noneToken = `${header}.${payload}.`;

    const res = await fetch(`${baseUrl}/api/attendance/check-in`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${noneToken}`
      },
      body: JSON.stringify({ id: crypto.randomUUID(), operationId: 'op-3' })
    });
    assert.equal(res.status, 401);
    const body = await res.json();
    assert.match(body.error, /HS256 required/i);
  });

  // --- Part A Item 3: Fix checkOut to target a specific row & stale-row prevention ---
  test('Check-out targets specific row and does NOT close an older open session (stale-row fix)', async () => {
    const oldSessionId = crypto.randomUUID();
    const oldCheckInOpId = `op-old-${crypto.randomUUID()}`;
    const newSessionId = crypto.randomUUID();
    const newCheckInOpId = `op-new-${crypto.randomUUID()}`;
    const checkOutOpId = `op-out-${crypto.randomUUID()}`;

    // 1. Create older 2-day-old open session (check_out_operation_id IS NULL)
    const twoDaysAgo = new Date(Date.now() - 2 * 24 * 60 * 60 * 1000);
    await attendanceService.checkIn({
      id: oldSessionId,
      userId: testUserId,
      checkInAt: twoDaysAgo,
      lat: 28.61,
      lng: 77.20,
      operationId: oldCheckInOpId
    });

    // 2. Create today's open session
    await attendanceService.checkIn({
      id: newSessionId,
      userId: testUserId,
      checkInAt: new Date(),
      lat: 28.61,
      lng: 77.20,
      operationId: newCheckInOpId
    });

    // 3. Perform check-out specifically targeting newSessionId over HTTP
    const res = await fetch(`${baseUrl}/api/attendance/check-out`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: newSessionId,
        checkOutAt: new Date().toISOString(),
        lat: 28.615,
        lng: 77.205,
        operationId: checkOutOpId
      })
    });

    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.success, true);
    assert.equal(body.alreadyProcessed, false);
    assert.equal(body.record.id, newSessionId);
    assert.equal(body.record.check_out_operation_id, checkOutOpId);

    // 4. Verify that the older 2-day-old session was NOT closed!
    const [oldRows] = await pool.execute(
      'SELECT * FROM attendance WHERE id = ?',
      [oldSessionId]
    );
    assert.equal(oldRows.length, 1);
    assert.equal(oldRows[0].check_out_operation_id, null, 'Old session must remain open and not be silently closed!');
    assert.equal(oldRows[0].check_out_at, null);
  });

  // --- Part A Item 3: Retry check-out twice (idempotency) ---
  test('Retry check-out twice with same check_out_operation_id returns alreadyProcessed:true and 200', async () => {
    const sessionId = crypto.randomUUID();
    const inOp = `op-in-${crypto.randomUUID()}`;
    const outOp = `op-out-${crypto.randomUUID()}`;

    // Create session
    await attendanceService.checkIn({
      id: sessionId,
      userId: testUserId,
      checkInAt: new Date(),
      lat: 28.6,
      lng: 77.2,
      operationId: inOp
    });

    // First checkout
    const res1 = await fetch(`${baseUrl}/api/attendance/check-out`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: sessionId,
        operationId: outOp,
        lat: 28.61,
        lng: 77.21
      })
    });
    assert.equal(res1.status, 200);
    const body1 = await res1.json();
    assert.equal(body1.alreadyProcessed, false);

    // Second checkout with same operationId
    const res2 = await fetch(`${baseUrl}/api/attendance/check-out`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: sessionId,
        operationId: outOp,
        lat: 28.61,
        lng: 77.21
      })
    });
    assert.equal(res2.status, 200);
    const body2 = await res2.json();
    assert.equal(body2.alreadyProcessed, true);
    assert.equal(body2.record.id, sessionId);
    assert.equal(body2.record.check_out_operation_id, outOp);
  });

  // --- Part A Item 3: Out-of-order check-out / 404 / 409 ---
  test('Check-out on nonexistent attendance id returns 404', async () => {
    const nonExistentId = crypto.randomUUID();
    const res = await fetch(`${baseUrl}/api/attendance/check-out`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: nonExistentId,
        operationId: `op-${crypto.randomUUID()}`
      })
    });
    assert.equal(res.status, 404);
  });

  test('Check-out with conflicting operationId on already-closed row returns 409', async () => {
    const sessionId = crypto.randomUUID();
    const inOp = `op-in-${crypto.randomUUID()}`;
    const outOp1 = `op-out-1-${crypto.randomUUID()}`;
    const outOp2 = `op-out-2-${crypto.randomUUID()}`;

    // Create session & close with outOp1
    await attendanceService.checkIn({
      id: sessionId,
      userId: testUserId,
      checkInAt: new Date(),
      operationId: inOp
    });
    await attendanceService.checkOut({
      id: sessionId,
      userId: testUserId,
      operationId: outOp1
    });

    // Try closing with different outOp2
    const res = await fetch(`${baseUrl}/api/attendance/check-out`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: sessionId,
        operationId: outOp2
      })
    });
    assert.equal(res.status, 409);
    const body = await res.json();
    assert.match(body.error, /already been checked out by a different operation/i);
    assert.ok(body.existingRecord);
  });

  // --- Part A Item 4: Primary-key collision on check-in returns 409, never undefined ---
  test('Check-in with same id but different operationId returns 409 with existing record (PK collision)', async () => {
    const collisionId = crypto.randomUUID();
    const op1 = `op-in-1-${crypto.randomUUID()}`;
    const op2 = `op-in-2-${crypto.randomUUID()}`;

    // Initial check-in
    const res1 = await fetch(`${baseUrl}/api/attendance/check-in`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: collisionId,
        operationId: op1,
        lat: 28.5,
        lng: 77.1
      })
    });
    assert.equal(res1.status, 200);

    // Collision check-in with same ID but different operationId
    const res2 = await fetch(`${baseUrl}/api/attendance/check-in`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: collisionId,
        operationId: op2,
        lat: 28.51,
        lng: 77.11
      })
    });
    assert.equal(res2.status, 409);
    const body2 = await res2.json();
    assert.match(body2.error, /Primary key collision/i);
    assert.ok(body2.existingRecord, 'Existing record must be returned on 409, never undefined');
    assert.equal(body2.existingRecord.id, collisionId);
    assert.equal(body2.existingRecord.check_in_operation_id, op1);
  });

  // --- Part A Item 5: geofence_status is NOT client-controlled ---
  test('Check-in ignores client-supplied geofenceStatus and stores UNKNOWN', async () => {
    const sessionId = crypto.randomUUID();
    const op = `op-${crypto.randomUUID()}`;

    const res = await fetch(`${baseUrl}/api/attendance/check-in`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: sessionId,
        operationId: op,
        geofenceStatus: 'INSIDE' // Client tries to spoof geofence status
      })
    });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.record.geofence_status, 'UNKNOWN', 'Must store UNKNOWN regardless of client input');

    // Double check direct in MySQL
    const [rows] = await pool.execute('SELECT geofence_status FROM attendance WHERE id = ?', [sessionId]);
    assert.equal(rows[0].geofence_status, 'UNKNOWN');
  });

  // --- GPS Points route ---
  test('POST /api/gps-points inserts GPS point and retries idempotently', async () => {
    const pointId = crypto.randomUUID();
    const op = `op-gps-${crypto.randomUUID()}`;

    const res1 = await fetch(`${baseUrl}/api/gps-points`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: pointId,
        lat: 28.6139,
        lng: 77.2090,
        isMocked: false,
        recordedAt: new Date().toISOString(),
        operationId: op
      })
    });
    assert.equal(res1.status, 200);
    const body1 = await res1.json();
    assert.equal(body1.alreadyProcessed, false);
    assert.equal(body1.record.id, pointId);

    // Retry same GPS op
    const res2 = await fetch(`${baseUrl}/api/gps-points`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: pointId,
        lat: 28.6139,
        lng: 77.2090,
        isMocked: false,
        recordedAt: new Date().toISOString(),
        operationId: op
      })
    });
    assert.equal(res2.status, 200);
    const body2 = await res2.json();
    assert.equal(body2.alreadyProcessed, true);
  });
});
