const express = require('express');
const env = require('./config/env');
const authMiddleware = require('./middleware/auth');
const attendanceService = require('./services/attendanceService');
const authService = require('./services/authService');
const visitService = require('./services/visitService');
const expenseService = require('./services/expenseService');
const workforceService = require('./services/workforceService');
const taskService = require('./services/taskService');
const customerService = require('./services/customerService');

const app = express();

// JSON body parser with limit. NO CORS middleware (native Android client only)
app.use(express.json({ limit: '10mb' }));

// Health Check
app.get('/health', (req, res) => {
  res.status(200).json({ status: 'ok', timestamp: new Date().toISOString() });
});

// Public Auth Route: Exchange Firebase ID token for application session JWT
app.post('/api/auth/login', async (req, res, next) => {
  try {
    const { idToken } = req.body;
    const result = await authService.loginWithFirebaseToken(idToken);
    res.status(200).json(result);
  } catch (err) {
    next(err);
  }
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

app.get('/api/attendance/history', authMiddleware, async (req, res, next) => {
  try {
    const userId = req.user.userId || req.user.id;
    const { since } = req.query;

    const records = await attendanceService.getHistory({
      userId,
      since: since || null
    });

    res.status(200).json(records);
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

// Visits Routes
app.post('/api/visits/check-in', authMiddleware, async (req, res, next) => {
  try {
    const assignedTo = req.user.userId || req.user.id;
    const { id, customerId, checkInAt, operationId } = req.body;
    const result = await visitService.checkIn({ id, customerId, assignedTo, checkInAt, operationId });
    res.status(200).json(result);
  } catch (err) {
    next(err);
  }
});

// Safe separate notes update route: NEVER alters status or check_out_at
app.post('/api/visits/notes', authMiddleware, async (req, res, next) => {
  try {
    const assignedTo = req.user.userId || req.user.id;
    const { id, notes, meetingOutcome, followUpDate } = req.body;
    const result = await visitService.updateNotes({ id, assignedTo, notes, meetingOutcome, followUpDate });
    res.status(200).json(result);
  } catch (err) {
    next(err);
  }
});

app.post('/api/visits/complete', authMiddleware, async (req, res, next) => {
  try {
    const assignedTo = req.user.userId || req.user.id;
    const { id, checkOutAt, operationId, meetingOutcome } = req.body;
    const result = await visitService.complete({ id, assignedTo, checkOutAt, operationId, meetingOutcome });
    res.status(200).json(result);
  } catch (err) {
    next(err);
  }
});

app.get('/api/visits/history', authMiddleware, async (req, res, next) => {
  try {
    const assignedTo = req.user.userId || req.user.id;
    const { since } = req.query;
    const records = await visitService.getHistory({ assignedTo, since });
    res.status(200).json(records);
  } catch (err) {
    next(err);
  }
});

// Expenses Routes
app.post('/api/expenses', authMiddleware, async (req, res, next) => {
  try {
    const userId = req.user.userId || req.user.id;
    const { id, amount, category, receiptPhotoPath, operationId } = req.body;
    const result = await expenseService.create({ id, userId, amount, category, receiptPhotoPath, operationId });
    res.status(200).json(result);
  } catch (err) {
    next(err);
  }
});

app.post('/api/expenses/review', authMiddleware, async (req, res, next) => {
  try {
    const reviewerId = req.user.userId || req.user.id;
    const reviewerRole = req.user.role;
    const { id, status } = req.body;
    const result = await expenseService.review({ id, reviewerId, reviewerRole, status });
    res.status(200).json(result);
  } catch (err) {
    next(err);
  }
});

app.get('/api/expenses/history', authMiddleware, async (req, res, next) => {
  try {
    const userId = req.user.userId || req.user.id;
    const role = req.user.role;
    const { since } = req.query;
    const records = await expenseService.getHistory({ userId, role, since });
    res.status(200).json(records);
  } catch (err) {
    next(err);
  }
});

// Workforce Routes (Wage changes are admin-only server-side)
app.post('/api/workforce/wage', authMiddleware, async (req, res, next) => {
  try {
    const requesterRole = req.user.role;
    const { targetUserId, newWage } = req.body;
    const result = await workforceService.updateDailyWage({ targetUserId, newWage, requesterRole });
    res.status(200).json(result);
  } catch (err) {
    next(err);
  }
});

// Tasks Routes
app.post('/api/tasks', authMiddleware, async (req, res, next) => {
  try {
    const assignedBy = req.user.userId || req.user.id;
    const { id, assignedTo, title, description, scheduledDate, priority, operationId } = req.body;
    const result = await taskService.create({ id, assignedTo, assignedBy, title, description, scheduledDate, priority, operationId });
    res.status(200).json(result);
  } catch (err) {
    next(err);
  }
});

app.get('/api/tasks/history', authMiddleware, async (req, res, next) => {
  try {
    const userId = req.user.userId || req.user.id;
    const { since } = req.query;
    const records = await taskService.getHistory({ userId, since });
    res.status(200).json(records);
  } catch (err) {
    next(err);
  }
});

// Customers Routes
app.post('/api/customers', authMiddleware, async (req, res, next) => {
  try {
    const createdBy = req.user.userId || req.user.id;
    const { id, name, phone, address, operationId } = req.body;
    const result = await customerService.create({ id, name, phone, address, createdBy, operationId });
    res.status(200).json(result);
  } catch (err) {
    next(err);
  }
});

app.get('/api/customers/history', authMiddleware, async (req, res, next) => {
  try {
    const { since } = req.query;
    const records = await customerService.getHistory({ since });
    res.status(200).json(records);
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
