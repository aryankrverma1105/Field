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
const { AuthService } = require('../src/services/authService');

describe('Backend Hardening, Idempotency & HTTP API Tests', () => {
  let server;
  let baseUrl;
  let testUserId;
  let testPhone;
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
    testPhone = `+91${Math.floor(1000000000 + Math.random() * 9000000000)}`;
    // Seed test user in DB
    await pool.execute(
      `INSERT INTO users (id, phone_e164, name, role, status) VALUES (?, ?, ?, ?, ?)
       ON DUPLICATE KEY UPDATE id = id`,
      [testUserId, testPhone, 'Audit Test Employee', 'employee', 'ACTIVE']
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
      await pool.execute('DELETE FROM user_sites WHERE user_id = ?', [testUserId]);
      await pool.execute('DELETE FROM users WHERE id = ?', [testUserId]);
      await pool.execute('DELETE FROM sites WHERE name = ?', ['Audit Test Site']);
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

  // --- Part A.1: GET /api/attendance/history ---
  test('GET /api/attendance/history returns authorized rows ordered by created_at and supports since filter', async () => {
    const res = await fetch(`${baseUrl}/api/attendance/history`, {
      headers: {
        'Authorization': `Bearer ${validToken}`
      }
    });
    assert.equal(res.status, 200);
    const records = await res.json();
    assert.ok(Array.isArray(records));
    assert.ok(records.length > 0, 'Should return records seeded in previous tests for this user');

    // Verify ordering
    for (let i = 1; i < records.length; i++) {
      const prev = new Date(records[i - 1].created_at).getTime();
      const curr = new Date(records[i].created_at).getTime();
      assert.ok(curr >= prev, 'History must be ordered by created_at ASC');
    }

    // Verify ?since filter excludes earlier items
    const futureSince = '2099-01-01T00:00:00.000Z';
    const resFiltered = await fetch(`${baseUrl}/api/attendance/history?since=${futureSince}`, {
      headers: {
        'Authorization': `Bearer ${validToken}`
      }
    });
    assert.equal(resFiltered.status, 200);
    const filteredRecords = await resFiltered.json();
    assert.equal(filteredRecords.length, 0, 'Future since filter should return 0 records');
  });

  // --- Part B.2: Server Geofencing evaluation ---
  test('Check-in computes geofenceStatus INSIDE when inside assigned site radius', async () => {
    const siteId = crypto.randomUUID();
    const siteLat = 12.9716;
    const siteLng = 77.5946;
    const siteRadius = 250.0;

    await pool.execute(
      'INSERT INTO sites (id, name, lat, lng, geofence_radius_m) VALUES (?, ?, ?, ?, ?)',
      [siteId, 'Audit Test Site', siteLat, siteLng, siteRadius]
    );
    await pool.execute(
      'INSERT INTO user_sites (user_id, site_id) VALUES (?, ?)',
      [testUserId, siteId]
    );

    const sessionId = crypto.randomUUID();
    const op = `op-${crypto.randomUUID()}`;

    // Inside site (distance ~15 meters)
    const res = await fetch(`${baseUrl}/api/attendance/check-in`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: sessionId,
        operationId: op,
        lat: 12.9717,
        lng: 77.5947
      })
    });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.record.geofence_status, 'INSIDE', 'Must compute INSIDE when within site radius');

    // Clean up user site mapping
    await pool.execute('DELETE FROM user_sites WHERE user_id = ? AND site_id = ?', [testUserId, siteId]);
    await pool.execute('DELETE FROM sites WHERE id = ?', [siteId]);
  });

  test('Check-in computes geofenceStatus OUTSIDE when outside assigned site radius, overriding client spoofing', async () => {
    const siteId = crypto.randomUUID();
    const siteLat = 12.9716;
    const siteLng = 77.5946;
    const siteRadius = 100.0;

    await pool.execute(
      'INSERT INTO sites (id, name, lat, lng, geofence_radius_m) VALUES (?, ?, ?, ?, ?)',
      [siteId, 'Audit Test Site', siteLat, siteLng, siteRadius]
    );
    await pool.execute(
      'INSERT INTO user_sites (user_id, site_id) VALUES (?, ?)',
      [testUserId, siteId]
    );

    const sessionId = crypto.randomUUID();
    const op = `op-${crypto.randomUUID()}`;

    // Outside site (~350 km away), client attempts to spoof 'INSIDE'
    const res = await fetch(`${baseUrl}/api/attendance/check-in`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: sessionId,
        operationId: op,
        lat: 13.0827,
        lng: 80.2707,
        geofenceStatus: 'INSIDE' // Client spoof attempt
      })
    });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.record.geofence_status, 'OUTSIDE', 'Must compute OUTSIDE server-side regardless of client body');

    // Clean up
    await pool.execute('DELETE FROM user_sites WHERE user_id = ? AND site_id = ?', [testUserId, siteId]);
    await pool.execute('DELETE FROM sites WHERE id = ?', [siteId]);
  });

  test('Check-in computes geofenceStatus UNKNOWN for user with no assigned site', async () => {
    const sessionId = crypto.randomUUID();
    const op = `op-${crypto.randomUUID()}`;

    // User has no assigned site in user_sites table
    const res = await fetch(`${baseUrl}/api/attendance/check-in`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: sessionId,
        operationId: op,
        lat: 12.9716,
        lng: 77.5946
      })
    });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.record.geofence_status, 'UNKNOWN', 'Must store UNKNOWN if user has no assigned site');
  });

  // --- Part B.1: Firebase Phone Auth exchange -> App JWT ---
  test('POST /api/auth/login with valid Firebase token issues application JWT for active user', async () => {
    const fakeVerifier = async (token) => {
      if (token === 'real-looking-firebase-id-token-valid-abc') {
        return { uid: `fb_uid_${testUserId}`, phone_number: testPhone };
      }
      throw new Error('Unexpected token');
    };
    app.set('authService', new AuthService(fakeVerifier));

    const res = await fetch(`${baseUrl}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        idToken: 'real-looking-firebase-id-token-valid-abc'
      })
    });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(body.token, 'Must return signed app JWT');
    assert.equal(body.user.id, testUserId);
    assert.equal(body.user.role, 'employee');

    // Verify the returned token is valid against JWT_SECRET
    const decoded = jwt.verify(body.token, env.JWT_SECRET);
    assert.equal(decoded.userId, testUserId);
    assert.equal(decoded.role, 'employee');
  });

  test('POST /api/auth/login with invalid or expired Firebase token returns 401', async () => {
    const fakeExpiredVerifier = async () => {
      const err = new Error('Firebase ID token has expired');
      err.statusCode = 401;
      throw err;
    };
    app.set('authService', new AuthService(fakeExpiredVerifier));

    const res = await fetch(`${baseUrl}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        idToken: 'real-looking-firebase-id-token-expired'
      })
    });
    assert.equal(res.status, 401);
    const body = await res.json();
    assert.match(body.error, /expired|invalid/i);
  });

  test('POST /api/auth/login with valid token but inactive user returns 403', async () => {
    const inactiveUserId = crypto.randomUUID();
    const inactivePhone = `+91${Math.floor(1000000000 + Math.random() * 9000000000)}`;
    await pool.execute(
      `INSERT INTO users (id, phone_e164, name, role, status) VALUES (?, ?, ?, ?, ?)`,
      [inactiveUserId, inactivePhone, 'Inactive Employee', 'employee', 'INACTIVE']
    );

    const fakeInactiveVerifier = async () => ({
      uid: `fb_${inactiveUserId}`,
      phone_number: inactivePhone
    });
    app.set('authService', new AuthService(fakeInactiveVerifier));

    const res = await fetch(`${baseUrl}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        idToken: 'real-looking-firebase-id-token-for-inactive-user'
      })
    });
    assert.equal(res.status, 403);
    const body = await res.json();
    assert.match(body.error, /not provisioned or inactive/i);

    // Clean up
    await pool.execute('DELETE FROM users WHERE id = ?', [inactiveUserId]);
  });

  test('POST /api/auth/login with unknown Firebase UID returns 403 and never creates an account implicitly', async () => {
    const unknownUid = `unknown_uid_${crypto.randomUUID()}`;
    const unknownPhone = `+91${Math.floor(1000000000 + Math.random() * 9000000000)}`;

    const fakeUnknownVerifier = async () => ({
      uid: unknownUid,
      phone_number: unknownPhone
    });
    app.set('authService', new AuthService(fakeUnknownVerifier));

    const res = await fetch(`${baseUrl}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        idToken: 'real-looking-firebase-id-token-for-unknown-uid'
      })
    });
    assert.equal(res.status, 403);
    const body = await res.json();
    assert.match(body.error, /not provisioned or inactive/i);

    // Assert that NO user row was created for unknownUid
    const [rows] = await pool.execute('SELECT * FROM users WHERE firebase_uid = ? OR phone_e164 = ?', [unknownUid, unknownPhone]);
    assert.equal(rows.length, 0, 'Must NOT create an account implicitly on login failure');
  });

  // --- Part B.3: Specific Domain Rules & Role Protections ---

  test('Visits: checkIn sets IN_PROGRESS, updateNotes NEVER touches status/checkout, complete is terminal', async () => {
    const visitId = crypto.randomUUID();
    const customerId = crypto.randomUUID();
    const checkInOp = `op-visit-in-${crypto.randomUUID()}`;
    const completeOp = `op-visit-out-${crypto.randomUUID()}`;

    // 1. Check in to visit
    const checkInRes = await fetch(`${baseUrl}/api/visits/check-in`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: visitId,
        customerId,
        operationId: checkInOp
      })
    });
    assert.equal(checkInRes.status, 200);
    const checkInBody = await checkInRes.json();
    assert.equal(checkInBody.record.status, 'IN_PROGRESS');
    assert.equal(checkInBody.record.check_out_at, null);

    // 2. Mid-visit notes save - must NEVER complete visit or modify check_out_at
    const notesRes = await fetch(`${baseUrl}/api/visits/notes`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: visitId,
        notes: 'Mid-visit client discussion about contract renewal',
        meetingOutcome: 'Client requested revised quote'
      })
    });
    assert.equal(notesRes.status, 200);
    const notesBody = await notesRes.json();
    assert.equal(notesBody.record.notes, 'Mid-visit client discussion about contract renewal');
    assert.equal(notesBody.record.status, 'IN_PROGRESS', 'Notes update must NEVER alter status');
    assert.equal(notesBody.record.check_out_at, null, 'Notes update must NEVER set check_out_at');

    // 3. Complete visit (terminal action)
    const completeRes = await fetch(`${baseUrl}/api/visits/complete`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}`
      },
      body: JSON.stringify({
        id: visitId,
        operationId: completeOp,
        meetingOutcome: 'Final signed contract collected'
      })
    });
    assert.equal(completeRes.status, 200);
    const completeBody = await completeRes.json();
    assert.equal(completeBody.record.status, 'COMPLETED');
    assert.ok(completeBody.record.check_out_at, 'Must set check_out_at on completion');

    // Clean up
    await pool.execute('DELETE FROM visits WHERE id = ?', [visitId]);
  });

  test('Expenses: employee cannot review expenses (403), manager can review (200)', async () => {
    const expenseId = crypto.randomUUID();
    const op = `op-exp-${crypto.randomUUID()}`;

    // 1. Employee creates expense
    const createRes = await fetch(`${baseUrl}/api/expenses`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}` // role: employee
      },
      body: JSON.stringify({
        id: expenseId,
        amount: 250.50,
        category: 'Travel / Fuel',
        operationId: op
      })
    });
    assert.equal(createRes.status, 200);

    // 2. Employee tries to approve own expense -> 403 Forbidden
    const empReviewRes = await fetch(`${baseUrl}/api/expenses/review`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}` // role: employee
      },
      body: JSON.stringify({
        id: expenseId,
        status: 'APPROVED'
      })
    });
    assert.equal(empReviewRes.status, 403);
    const empReviewBody = await empReviewRes.json();
    assert.match(empReviewBody.error, /Only managers and admins/i);

    // 3. Manager reviews expense -> 200 OK
    const managerToken = jwt.sign(
      { userId: testUserId, role: 'manager' },
      env.JWT_SECRET,
      { algorithm: 'HS256', expiresIn: '1h' }
    );
    const mgrReviewRes = await fetch(`${baseUrl}/api/expenses/review`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${managerToken}`
      },
      body: JSON.stringify({
        id: expenseId,
        status: 'APPROVED'
      })
    });
    assert.equal(mgrReviewRes.status, 200);
    const mgrReviewBody = await mgrReviewRes.json();
    assert.equal(mgrReviewBody.record.status, 'APPROVED');

    // Clean up
    await pool.execute('DELETE FROM expenses WHERE id = ?', [expenseId]);
  });

  test('Wages: employee cannot update wage (403), admin updates wage server-side (200)', async () => {
    const targetUserId = crypto.randomUUID();
    await pool.execute(
      `INSERT INTO users (id, phone_e164, name, role, daily_wage, status) VALUES (?, '+916666666666', 'Wage Test User', 'employee', 500.00, 'ACTIVE')`,
      [targetUserId]
    );

    // 1. Employee tries to modify wage -> 403 Forbidden
    const empWageRes = await fetch(`${baseUrl}/api/workforce/wage`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${validToken}` // role: employee
      },
      body: JSON.stringify({
        targetUserId,
        newWage: 1200.00
      })
    });
    assert.equal(empWageRes.status, 403);
    const empWageBody = await empWageRes.json();
    assert.match(empWageBody.error, /Only administrators/i);

    // 2. Admin updates wage -> 200 OK
    const adminToken = jwt.sign(
      { userId: testUserId, role: 'admin' },
      env.JWT_SECRET,
      { algorithm: 'HS256', expiresIn: '1h' }
    );
    const adminWageRes = await fetch(`${baseUrl}/api/workforce/wage`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${adminToken}`
      },
      body: JSON.stringify({
        targetUserId,
        newWage: 1200.00
      })
    });
    assert.equal(adminWageRes.status, 200);
    const adminWageBody = await adminWageRes.json();
    assert.equal(Number(adminWageBody.user.daily_wage), 1200.00);

    // Verify in DB directly
    const [rows] = await pool.execute('SELECT daily_wage FROM users WHERE id = ?', [targetUserId]);
    assert.equal(Number(rows[0].daily_wage), 1200.00);

    // Clean up
    await pool.execute('DELETE FROM users WHERE id = ?', [targetUserId]);
  });

  test('Tasks and Customers: creation and history listing endpoints return 200', async () => {
    const taskId = crypto.randomUUID();
    const taskOp = `op-task-${crypto.randomUUID()}`;
    const custId = crypto.randomUUID();
    const custOp = `op-cust-${crypto.randomUUID()}`;

    // Create task
    const taskRes = await fetch(`${baseUrl}/api/tasks`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${validToken}` },
      body: JSON.stringify({
        id: taskId,
        assignedTo: testUserId,
        title: 'Audit Inspection Task',
        operationId: taskOp
      })
    });
    assert.equal(taskRes.status, 200);

    // List tasks
    const taskListRes = await fetch(`${baseUrl}/api/tasks/history`, {
      headers: { 'Authorization': `Bearer ${validToken}` }
    });
    assert.equal(taskListRes.status, 200);
    const tasks = await taskListRes.json();
    assert.ok(tasks.some(t => t.id === taskId));

    // Create customer
    const custRes = await fetch(`${baseUrl}/api/customers`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${validToken}` },
      body: JSON.stringify({
        id: custId,
        name: 'Acme Global Corp',
        phone: '+919876543210',
        operationId: custOp
      })
    });
    assert.equal(custRes.status, 200);

    // List customers
    const custListRes = await fetch(`${baseUrl}/api/customers/history`, {
      headers: { 'Authorization': `Bearer ${validToken}` }
    });
    assert.equal(custListRes.status, 200);
    const customers = await custListRes.json();
    assert.ok(customers.some(c => c.id === custId));

    // Clean up
    await pool.execute('DELETE FROM tasks WHERE id = ?', [taskId]);
    await pool.execute('DELETE FROM customers WHERE id = ?', [custId]);
  });
});
