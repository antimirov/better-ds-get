/**
 * SessionManager — Keeps the Synology session alive and handles reconnection.
 *
 * THIS IS THE KEY FIX for the DS Get logout problem.
 *
 * The original app:
 *   - Treated network timeouts as session timeouts → forced logout
 *   - Had no keepalive mechanism → session expired after NAS timeout
 *   - Called shouldLogout() for 10 different error types from 26+ locations
 *   - Destructively cleared all state on logout → required full manual re-login
 *
 * This manager:
 *   - Pings the NAS periodically to keep the session alive
 *   - Auto-reconnects on session timeout using stored credentials
 *   - Retries transient network errors with exponential backoff
 *   - Only forces manual re-login on actual auth failures (wrong password, etc.)
 *   - Emits events so the UI can show connection status without freezing
 */

import { SynologyClient, SynologyError, NetworkError } from './synology-client.js';
import { DownloadStation } from './download-station.js';
// In React Native, we use Async Storage instead of localStorage
import AsyncStorage from '@react-native-async-storage/async-storage';

/**
 * Connection states
 * @enum {string}
 */
export const ConnectionState = {
  DISCONNECTED: 'disconnected',
  CONNECTING: 'connecting',
  CONNECTED: 'connected',
  RECONNECTING: 'reconnecting',
  ERROR: 'error',
};

// React Native EventTarget alternative or polyfill for simple pub/sub
export class EventBus {
  constructor() {
    this.listeners = {};
  }
  addEventListener(type, callback) {
    if (!this.listeners[type]) this.listeners[type] = [];
    this.listeners[type].push(callback);
  }
  removeEventListener(type, callback) {
    if (!this.listeners[type]) return;
    this.listeners[type] = this.listeners[type].filter(cb => cb !== callback);
  }
  dispatchEvent(event) {
    if (!this.listeners[event.type]) return;
    this.listeners[event.type].forEach(cb => cb(event));
  }
}

export class SessionManager extends EventBus {
  /** @type {SynologyClient} */
  #client;

  /** @type {DownloadStation|null} */
  #ds = null;

  /** @type {ConnectionState} */
  #state = ConnectionState.DISCONNECTED;

  /** @type {StoredCredentials|null} */
  #credentials = null;

  /** @type {string|null} The address literally typed by the user */
  #originalAddress = null;

  /** @type {ReturnType<typeof setInterval>|null} Keepalive timer ID */
  #keepaliveTimer = null;

  /** @type {number} Keepalive interval in ms (default: 2 minutes) */
  #keepaliveInterval = 2 * 60 * 1000;

  /** @type {number} Maximum reconnect attempts before giving up */
  #maxReconnectAttempts = 3;

  /** @type {number} Current reconnect attempt */
  #reconnectAttempt = 0;

  /** @type {string} Storage key prefix */
  #storageKey = 'better_ds_get';

  constructor() {
    super();
    this.#client = new SynologyClient('');
  }

  /** @returns {ConnectionState} */
  get state() {
    return this.#state;
  }

  /** @returns {SynologyClient} */
  get client() {
    return this.#client;
  }

  /** @returns {DownloadStation|null} */
  get ds() {
    return this.#ds;
  }

  /** @returns {boolean} */
  get isConnected() {
    return this.#state === ConnectionState.CONNECTED;
  }

  /** @returns {StoredCredentials|null} */
  get credentials() {
    return this.#credentials;
  }

  /**
   * Get technical details about the current connection.
   */
  get connectionInfo() {
    const original = this.#originalAddress || '';
    // QuickConnect is active if the user provided a simple ID (no dots, no slashes, no colons)
    const isQuickConnect = original.length > 0 && !original.includes('.') && !original.includes(':') && !original.includes('/') && !original.includes('http');

    return {
      baseUrl: this.#client.baseUrl,
      originalAddress: original,
      isHttps: this.#client.isHttps,
      sid: this.#client.sid,
      isQuickConnect: isQuickConnect,
    };
  }

  /**
   * Replace Event interface with simple object structure for React Native
   */
  #emit(type, detail) {
    this.dispatchEvent({ type, detail });
  }

  // ── Connection Lifecycle ──────────────────────────────────────

  /**
   * Connect to a Synology NAS.
   *
   * @param {string} url - NAS URL
   * @param {string} account - Username
   * @param {string} password - Password
   * @param {object} [options]
   * @param {string} [options.otp] - 2FA code
   * @param {boolean} [options.rememberMe=true] - Store credentials for auto-reconnect
   * @returns {Promise<{sid: string, dsInfo: any}>}
   */
  async connect(url, account, password, options = {}) {
    const { otp, rememberMe = true } = options;

    this.#setState(ConnectionState.CONNECTING);
    this.#originalAddress = options.originalAddress || url;

    try {
      // Configure client
      this.#client.setBaseUrl(url);

      // Login
      const loginResult = await this.#client.login(account, password, { otp });

      // Store credentials for auto-reconnect
      this.#credentials = { url, account, password, originalAddress: this.#originalAddress };

      if (rememberMe) {
        await this.#saveCredentials();
      }

      // Create Download Station wrapper
      this.#ds = new DownloadStation(this.#client);

      // Get DS info
      let dsInfo = null;
      try {
        dsInfo = await this.#ds.getInfo();
      } catch (error) {
        // If we can't get basic info, the user likely doesn't have privileges for DS
        throw new Error('Connection failed: ' + (error.message || 'You may not have privileges for Download Station on this account.'));
      }

      // Start keepalive
      this.#reconnectAttempt = 0;
      this.#startKeepalive();
      this.#setState(ConnectionState.CONNECTED);

      return { sid: loginResult.sid, dsInfo };
    } catch (error) {
      this.#setState(ConnectionState.ERROR, error);
      throw error;
    }
  }

  /**
   * Disconnect from the NAS.
   * @param {boolean} [clearSaved=false] - Also clear saved credentials
   */
  async disconnect(clearSaved = false) {
    this.#stopKeepalive();

    try {
      await this.#client.logout();
    } catch {
      // Best-effort
    }

    this.#ds = null;

    if (clearSaved) {
      await this.#clearCredentials();
      this.#credentials = null;
    }

    this.#setState(ConnectionState.DISCONNECTED);
  }

  /**
   * Try to restore a previous session from saved credentials.
   * @returns {Promise<boolean>} true if reconnection succeeded
   */
  async tryRestore() {
    const saved = await this.#loadCredentials();
    if (!saved) return false;

    try {
      await this.connect(saved.url, saved.account, saved.password, {
        rememberMe: true,
        originalAddress: saved.originalAddress || saved.url,
      });
      return true;
    } catch {
      return false;
    }
  }

  // ── Resilient Request Wrapper ──────────────────────────────────

  /**
   * Execute an API operation with automatic retry and reconnection.
   *
   * @template T
   * @param {() => Promise<T>} operation - The API call to execute
   * @param {number} [maxRetries=2] - Max retries for transient errors
   * @returns {Promise<T>}
   */
  async execute(operation, maxRetries = 2) {
    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        const result = await operation();
        // Success — reset reconnect counter
        this.#reconnectAttempt = 0;
        return result;
      } catch (error) {
        // ── Network error → retry with backoff ──
        if (error instanceof NetworkError) {
          if (attempt < maxRetries) {
            const delay = Math.min(1000 * Math.pow(2, attempt), 10000);
            this.#emit('retry', { attempt: attempt + 1, maxRetries, delay, error });
            await this.#sleep(delay);
            continue;
          }
          // All retries exhausted — show error but DON'T logout
          this.#emit('networkError', { error });
          throw error;
        }

        // ── Session error → auto-reconnect ──
        if (error instanceof SynologyError && error.isSessionError) {
          if (attempt < maxRetries) {
            const reconnected = await this.#tryReconnect();
            if (reconnected) {
              // Retry the operation with the new session
              continue;
            }
          }
          // Reconnection failed or max retries reached — force manual login
          this.#setState(ConnectionState.DISCONNECTED);
          this.#emit('sessionExpired', { error });
          throw error;
        }

        // ── Auth error → don't retry, show error ──
        if (error instanceof SynologyError && error.isAuthError) {
          throw error;
        }

        // ── Transient API error → retry ──
        if (error instanceof SynologyError && error.isTransient && attempt < maxRetries) {
          const delay = Math.min(1000 * Math.pow(2, attempt), 5000);
          await this.#sleep(delay);
          continue;
        }

        // ── Unknown error → don't logout, just propagate ──
        throw error;
      }
    }
  }

  // ── Keepalive ──────────────────────────────────────────────────

  /**
   * Start the keepalive timer.
   */
  #startKeepalive() {
    this.#stopKeepalive();
    this.#keepaliveTimer = setInterval(async () => {
      try {
        const alive = await this.#client.testSession();
        if (!alive) {
          // Session died — try to reconnect silently
          await this.#tryReconnect();
        }
      } catch (error) {
        if (error instanceof NetworkError) {
          // Network issue — don't panic, will retry next interval
          this.#emit('networkWarning', { error });
        }
      }
    }, this.#keepaliveInterval);
  }

  /**
   * Stop the keepalive timer.
   */
  #stopKeepalive() {
    if (this.#keepaliveTimer) {
      clearInterval(this.#keepaliveTimer);
      this.#keepaliveTimer = null;
    }
  }

  /**
   * Set the keepalive interval.
   * @param {number} ms - Interval in milliseconds (min 30s, max 10min)
   */
  setKeepaliveInterval(ms) {
    this.#keepaliveInterval = Math.max(30000, Math.min(ms, 600000));
    if (this.#keepaliveTimer) {
      this.#startKeepalive(); // Restart with new interval
    }
  }

  // ── Auto-Reconnect ──────────────────────────────────────────────

  /**
   * Attempt to reconnect using stored credentials.
   * @returns {Promise<boolean>} true if reconnection succeeded
   */
  async #tryReconnect() {
    if (!this.#credentials || this.#reconnectAttempt >= this.#maxReconnectAttempts) {
      return false;
    }

    this.#reconnectAttempt++;
    this.#setState(ConnectionState.RECONNECTING);
    this.#emit('reconnecting', { attempt: this.#reconnectAttempt });

    try {
      const { url, account, password, originalAddress } = this.#credentials;
      this.#client.setBaseUrl(url);
      await this.#client.login(account, password);
      this.#ds = new DownloadStation(this.#client);
      this.#setState(ConnectionState.CONNECTED);
      this.#emit('reconnected', { attempt: this.#reconnectAttempt });
      this.#reconnectAttempt = 0;
      return true;
    } catch (error) {
      if (this.#reconnectAttempt >= this.#maxReconnectAttempts) {
        this.#setState(ConnectionState.ERROR, error);
        return false;
      }
      // Wait before next attempt
      const delay = Math.min(2000 * Math.pow(2, this.#reconnectAttempt), 30000);
      await this.#sleep(delay);
      return this.#tryReconnect();
    }
  }

  // ── Credential Storage ──────────────────────────────────────────

  async #saveCredentials() {
    if (!this.#credentials) return;
    try {
      const dataToSave = {
        ...this.#credentials,
        originalAddress: this.#originalAddress
      };
      const data = JSON.stringify(dataToSave);
      await AsyncStorage.setItem(`${this.#storageKey}_creds`, data);
    } catch {
      // Ignoring storage error
    }
  }

  async #loadCredentials() {
    try {
      const data = await AsyncStorage.getItem(`${this.#storageKey}_creds`);
      if (!data) return null;
      return JSON.parse(data); // No atob needed in simple implementation
    } catch {
      return null;
    }
  }

  async #clearCredentials() {
    try {
      await AsyncStorage.removeItem(`${this.#storageKey}_creds`);
    } catch {
      // Ignore
    }
  }

  // ── State & Events ──────────────────────────────────────────────

  /**
   * Update connection state and emit event.
   * @param {ConnectionState} state
   * @param {Error} [error]
   */
  #setState(state, error = null) {
    const previousState = this.#state;
    this.#state = state;
    this.#emit('stateChange', { state, previousState, error });
  }

  /**
   * @param {number} ms
   * @returns {Promise<void>}
   */
  #sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}

/**
 * @typedef {Object} StoredCredentials
 * @property {string} url - NAS URL
 * @property {string} account - Username
 * @property {string} password - Password
 * @property {string} [originalAddress] - What the user literally typed
 */
