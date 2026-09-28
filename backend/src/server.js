const express = require('express');
const env = require('./config/env');
const authMiddleware = require('./middleware/auth');
const attendanceService = require('./services/attendanceService');

const app = express();

// JSON body parser with limit. NO CORS middleware (native Android client only)
app.use(express.json({ limit: '10mb' }));

// Health Check
app.get('/health', (req, res) => {
  res.status(200).json({ status: 'ok', timestamp: new Date().toISOString() });
});

// Protected API Routes
app.post('/api/attendance/check-in', authMiddleware, async (req, res, next) => {
  try {
    const userId = req.user.userId || req.user.id;
    const { id, checkInAt, lat, lng, photoPath, isMocked, operationId } = req.body;

    const result = await attendanceService.checkIn({
      id,
      userId,
      checkInAt,
      lat,
      lng,
      photoPath,
      isMocked,
      operationId
    });

    res.status(200).json(result);
  } catch (err) {
    next(err);
  }
});

app.post('/api/attendance/check-out', authMiddleware, async (req, res, next) => {
  try {
    const userId = req.user.userId || req.user.id;
    const { id, checkOutAt, lat, lng, photoPath, operationId } = req.body;

    const result = await attendanceService.checkOut({
      id,
      userId,
      checkOutAt,
      lat,
      lng,
      photoPath,
      operationId
    });

    res.status(200).json(result);
  } catch (err) {
    next(err);
  }
});

app.post('/api/gps-points', authMiddleware, async (req, res, next) => {
  try {
    const userId = req.user.userId || req.user.id;
    const { id, lat, lng, isMocked, recordedAt, operationId } = req.body;

    const result = await attendanceService.recordGpsPoint({
      id,
      userId,
      lat,
      lng,
      isMocked,
      recordedAt,
      operationId
    });

    res.status(200).json(result);
  } catch (err) {
    next(err);
  }
});

// Centralized Error Handler
// DB errors must produce HTTP 5xx, never fake success.
// Map statusCode 400, 404, 409 from service/validation to HTTP.
app.use((err, req, res, next) => {
  const statusCode = err.statusCode || (err.name === 'ValidationError' ? 400 : 500);

  if (statusCode >= 500) {
    console.error('[SERVER ERROR]', err);
  }

  const responseBody = {
    error: err.message || 'Internal Server Error'
  };

  if (err.existingRecord) {
    responseBody.existingRecord = err.existingRecord;
  }

  res.status(statusCode).json(responseBody);
});

if (require.main === module) {
  const server = app.listen(env.PORT, () => {
    console.log(`[SERVER] Sologix Attendance Backend listening on port ${env.PORT}`);
  });

  const shutdown = () => {
    console.log('[SERVER] Shutting down gracefully...');
    server.close(() => {
      process.exit(0);
    });
  };

  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
}

module.exports = app;
