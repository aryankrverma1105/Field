require('dotenv').config({ path: process.env.DOTENV_CONFIG_PATH || undefined });

const requiredEnvVars = [
  'DATABASE_USER',
  'DATABASE_PASSWORD',
  'DATABASE_NAME',
  'JWT_SECRET'
];

const missing = [];

for (const key of requiredEnvVars) {
  if (!process.env[key] || process.env[key].trim() === '') {
    missing.push(key);
  }
}

if (process.env.JWT_SECRET && process.env.JWT_SECRET.length < 32) {
  console.error('[CONFIG ERROR] JWT_SECRET must be at least 32 characters long.');
  process.exit(1);
}

if (missing.length > 0) {
  console.error(`[CONFIG ERROR] Missing required environment variable(s): ${missing.join(', ')}`);
  process.exit(1);
}

module.exports = {
  PORT: parseInt(process.env.PORT || '3000', 10),
  DATABASE_HOST: process.env.DATABASE_HOST || '127.0.0.1',
  DATABASE_PORT: parseInt(process.env.DATABASE_PORT || '3306', 10),
  DATABASE_USER: process.env.DATABASE_USER,
  DATABASE_PASSWORD: process.env.DATABASE_PASSWORD,
  DATABASE_NAME: process.env.DATABASE_NAME,
  JWT_SECRET: process.env.JWT_SECRET,
  NODE_ENV: process.env.NODE_ENV || 'development'
};
