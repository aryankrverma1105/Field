const jwt = require('jsonwebtoken');
const env = require('../config/env');

function authMiddleware(req, res, next) {
  const authHeader = req.headers['authorization'];
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'Unauthorized: Missing or malformed authorization header' });
  }

  const token = authHeader.substring(7).trim();
  if (!token) {
    return res.status(401).json({ error: 'Unauthorized: Empty token provided' });
  }

  // Explicit check for alg: none or invalid header before or during verification
  const decodedHeader = jwt.decode(token, { complete: true });
  if (!decodedHeader || !decodedHeader.header || decodedHeader.header.alg === 'none' || decodedHeader.header.alg !== 'HS256') {
    return res.status(401).json({ error: 'Unauthorized: Invalid token algorithm. HS256 required.' });
  }

  try {
    const verified = jwt.verify(token, env.JWT_SECRET, {
      algorithms: ['HS256']
    });
    req.user = verified;
    next();
  } catch (err) {
    return res.status(401).json({ error: 'Unauthorized: Invalid or expired token', details: err.message });
  }
}

module.exports = authMiddleware;
