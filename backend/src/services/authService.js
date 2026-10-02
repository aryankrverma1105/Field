const admin = require('firebase-admin');
const jwt = require('jsonwebtoken');
const pool = require('../db/pool');
const env = require('../config/env');

let firebaseApp = null;
if (admin.apps.length === 0) {
  try {
    if (process.env.FIREBASE_SERVICE_ACCOUNT_JSON) {
      const sa = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON);
      firebaseApp = admin.initializeApp({
        credential: admin.credential.cert(sa)
      });
    } else if (process.env.FIREBASE_PROJECT_ID) {
      firebaseApp = admin.initializeApp({
        projectId: process.env.FIREBASE_PROJECT_ID
      });
    } else {
      firebaseApp = admin.initializeApp();
    }
  } catch (_) {
    // In local dev/testing environments without GCP credentials, initialization can fail gracefully
  }
}

class AuthService {
  /**
   * Overridable token verifier (allows mocking in test environments)
   */
  async verifyIdToken(idToken) {
    if (!idToken || typeof idToken !== 'string') {
      const err = new Error('Invalid or missing idToken');
      err.statusCode = 401;
      throw err;
    }

    // Support test mock tokens in non-production environments
    if (process.env.NODE_ENV !== 'production' && idToken.startsWith('test-fb-token:')) {
      const parts = idToken.split(':');
      if (parts[1] === 'expired' || parts[1] === 'invalid') {
        const err = new Error('Firebase ID token has expired or is invalid');
        err.statusCode = 401;
        throw err;
      }
      return {
        uid: parts[1] || 'mock_uid',
        phone_number: parts[2] || '+919999999999'
      };
    }

    if (!firebaseApp) {
      const err = new Error('Firebase Admin SDK is not configured');
      err.statusCode = 500;
      throw err;
    }

    try {
      return await admin.auth().verifyIdToken(idToken);
    } catch (err) {
      const authErr = new Error('Invalid or expired Firebase ID token');
      authErr.statusCode = 401;
      throw authErr;
    }
  }

  async loginWithFirebaseToken(idToken) {
    if (!idToken) {
      const err = new Error('idToken is required');
      err.statusCode = 400;
      throw err;
    }

    const decoded = await this.verifyIdToken(idToken);
    const firebaseUid = decoded.uid;
    const phone = decoded.phone_number;

    // Look up provisioned user in MySQL
    const [rows] = await pool.execute(
      `SELECT * FROM users 
       WHERE (firebase_uid = ? OR (phone_e164 = ? AND phone_e164 IS NOT NULL AND phone_e164 != ''))
         AND status = 'ACTIVE'`,
      [firebaseUid, phone || '']
    );

    if (rows.length === 0) {
      // User is either not found or inactive.
      // Strict rule: DO NOT create an account implicitly.
      const err = new Error('Account not provisioned or inactive. Contact administrator.');
      err.statusCode = 403;
      throw err;
    }

    const user = rows[0];

    // Link firebase_uid if not already linked
    if (!user.firebase_uid && firebaseUid) {
      await pool.execute(
        'UPDATE users SET firebase_uid = ? WHERE id = ?',
        [firebaseUid, user.id]
      );
      user.firebase_uid = firebaseUid;
    }

    // Issue app JWT signed with JWT_SECRET
    const token = jwt.sign(
      { userId: user.id, role: user.role },
      env.JWT_SECRET,
      { algorithm: 'HS256', expiresIn: '7d' }
    );

    return {
      token,
      user: {
        id: user.id,
        name: user.name,
        phone: user.phone_e164,
        role: user.role,
        status: user.status
      }
    };
  }
}

module.exports = new AuthService();
