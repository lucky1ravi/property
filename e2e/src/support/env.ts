/** Settings shared by every test. Everything can be overridden with environment variables (see .env.example). */
export const BASE_URL = process.env.E2E_BASE_URL || 'http://localhost:4173';
export const API_URL = (process.env.E2E_API_URL || BASE_URL).replace(/\/$/, '');
export const DEMO_PASSWORD = process.env.E2E_DEMO_PASSWORD || 'PropertyOS@2026';
/** "local": tests may create companies, users and data. "live": read-only checks against a shared demo (never creates accounts). */
export const MODE = (process.env.E2E_MODE || 'local') as 'local' | 'live';
export const CAN_WRITE = MODE === 'local' && process.env.E2E_ALLOW_WRITES !== 'false';
export const SUFFIX = () => Math.random().toString(36).slice(2, 8);
