/**
 * SynologyClient — Core API client for Synology DSM Web API.
 *
 * Handles authentication, session management, API discovery, and request execution.
 * Designed to fix the session management issues found in the original DS Get app:
 *   - Proper session ID tracking
 *   - API path auto-discovery via SYNO.API.Info
 */
import * as FileSystem from 'expo-file-system/legacy';

export class SynologyClient {
    /** @type {string} Base URL of the Synology NAS (e.g. "https://nas.local:5001") */
    #baseUrl = '';

    /** @type {string|null} Session ID obtained from login */
    #sid = null;

    /** @type {Map<string, ApiInfo>} Discovered API paths and version ranges */
    #knownApis = new Map();

    /** @type {boolean} Whether API discovery has been performed */
    #apisDiscovered = false;

    /** @type {AbortController|null} For cancelling in-flight requests */
    #abortController = null;

    /** @type {string} Session name for SYNO.API.Auth */
    #sessionName = 'DownloadStation';

    /**
     * @param {string} baseUrl - NAS URL (with protocol and port)
     */
    constructor(baseUrl) {
        this.setBaseUrl(baseUrl);
    }

    /**
     * Update the base URL. Strips trailing slash.
     * @param {string} url
     */
    setBaseUrl(url) {
        this.#baseUrl = url.replace(/\/+$/, '');
    }

    /** @returns {string} The current base URL */
    get baseUrl() {
        return this.#baseUrl;
    }

    /** @returns {boolean} Whether the connection is over HTTPS */
    get isHttps() {
        return this.#baseUrl.startsWith('https://');
    }

    /** @returns {boolean} Whether we have a valid session */
    get isAuthenticated() {
        return this.#sid !== null;
    }

    /** @returns {string|null} Current session ID */
    get sid() {
        return this.#sid;
    }

    /**
     * Restore a session from a saved SID.
     * @param {string} sid 
     */
    setSession(sid) {
        this.#sid = sid;
    }

    // ── API Discovery ──────────────────────────────────────────────

    /**
     * Query the NAS for all available APIs, their paths, and version ranges.
     * Must be called before any other API calls (login does this automatically).
     *
     * Uses SYNO.API.Info which is always at /webapi/query.cgi
     */
    async discoverApis() {
        const url = `${this.#baseUrl}/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=all`;
        const response = await this.#fetchWithTimeout(url);
        const json = await this.#parseJson(response);

        if (!json.success) {
            throw new SynologyError('API discovery failed', json.error?.code ?? -1);
        }

        this.#knownApis.clear();
        for (const [name, info] of Object.entries(json.data)) {
            this.#knownApis.set(name, {
                path: info.path,
                minVersion: info.minVersion,
                maxVersion: info.maxVersion,
            });
        }

        this.#apisDiscovered = true;
        return this.#knownApis;
    }

    /**
     * Get a list of all discovered API names.
     * @returns {string[]}
     */
    get knownApiNames() {
        return Array.from(this.#knownApis.keys());
    }

    /**
     * @param {string} apiName
     * @returns {ApiInfo|null}
     */
    getApiInfo(apiName) {
        return this.#knownApis.get(apiName) ?? null;
    }

    /**
     * Check if an API is available at a given version.
     * @param {string} apiName
     * @param {number} version
     * @returns {boolean}
     */
    isApiSupported(apiName, version = 1) {
        const info = this.#knownApis.get(apiName);
        if (!info) return false;
        return version >= info.minVersion && version <= info.maxVersion;
    }

    /**
     * Whether DownloadStation2.Task is available (newer NAS firmware).
     * @returns {boolean}
     */
    get supportsDS2() {
        return this.#knownApis.has('SYNO.DownloadStation2.Task');
    }

    // ── Authentication ──────────────────────────────────────────────

    /**
     * Login to the Synology NAS.
     * Automatically discovers APIs first if not done yet.
     *
     * @param {string} account - Username
     * @param {string} password - Password
     * @param {object} [options]
     * @param {string} [options.otp] - One-time password for 2FA
     * @param {string} [options.deviceName] - Device name for "remember device"
     * @param {string} [options.deviceId] - Device ID from previous "remember device"
     * @returns {Promise<LoginResult>}
     */
    async login(account, password, options = {}) {
        // Step 1: Discover APIs if needed
        if (!this.#apisDiscovered) {
            await this.discoverApis();
        }

        // Step 2: Get encryption info (if available)
        let encryptedPassword = password;
        let extraParams = {};

        if (this.isApiSupported('SYNO.API.Encryption', 1)) {
            try {
                const encInfo = await this.#getEncryptionInfo();
                if (encInfo) {
                    encryptedPassword = this.#encryptPassword(password, encInfo);
                    extraParams = {
                        client_time: Math.floor(Date.now() / 1000),
                        ...encInfo.extraParams,
                    };
                }
            } catch {
                // Fall back to plaintext if encryption unavailable
            }
        }

        // Step 3: Determine auth API version
        const authInfo = this.getApiInfo('SYNO.API.Auth');
        if (!authInfo) {
            throw new SynologyError('SYNO.API.Auth not found on this NAS', -1);
        }

        // Use highest available version (up to 6 for device token / OTP support)
        const authVersion = Math.min(authInfo.maxVersion, 6);

        // Step 4: Build login params
        const params = {
            account,
            passwd: encryptedPassword,
            session: this.#sessionName,
            format: 'sid',
            ...extraParams,
        };

        if (options.otp && authVersion >= 5) {
            params.otp_code = options.otp;
        }
        if (options.deviceName && authVersion >= 6) {
            params.device_name = options.deviceName;
            params.enable_device_token = 'yes';
        }
        if (options.deviceId && authVersion >= 6) {
            params.device_id = options.deviceId;
        }

        // Step 5: Execute login
        const result = await this.request('SYNO.API.Auth', 'login', authVersion, params);

        this.#sid = result.sid || result.data?.sid;
        if (!this.#sid) {
            throw new SynologyError('Login succeeded but no SID returned', -1);
        }

        return {
            sid: this.#sid,
            deviceId: result.device_id || result.data?.device_id,
            isAdmin: result.is_admin || result.data?.is_admin,
        };
    }

    /**
     * Logout and clear the session.
     */
    async logout() {
        if (!this.#sid) return;

        try {
            await this.request('SYNO.API.Auth', 'logout', 1, {
                session: this.#sessionName,
            });
        } catch {
            // Best-effort logout
        } finally {
            this.#sid = null;
        }
    }

    /**
     * Test if the current session is still alive.
     * Uses SYNO.Core.Desktop.Timeout if available, falls back to SYNO.API.Auth.
     *
     * @returns {Promise<boolean>} true if session is valid
     */
    async testSession() {
        if (!this.#sid) return false;

        try {
            if (this.isApiSupported('SYNO.Core.Desktop.Timeout', 1)) {
                await this.request('SYNO.Core.Desktop.Timeout', 'check', 1, {});
            } else {
                // Fallback: try an innocuous API call
                await this.request('SYNO.API.Info', 'query', 1, { query: 'SYNO.API.Auth' });
            }
            return true;
        } catch (e) {
            if (e instanceof SynologyError && e.isSessionError) {
                return false;
            }
            // Network error — session status unknown, don't assume dead
            throw e;
        }
    }

    // ── Generic Request ──────────────────────────────────────────────

    /**
     * Make an API request to the Synology NAS.
     *
     * @param {string} apiName - API name (e.g. "SYNO.DownloadStation.Task")
     * @param {string} method - Method name (e.g. "list")
     * @param {number} version - API version
     * @param {Record<string, any>} [params={}] - Additional parameters
     * @param {Record<string, any>} [options={}] - Request options like timeoutMs
     * @returns {Promise<any>} Response data
     */
    async request(apiName, method, version, params = {}, options = {}) {
        // Look up API path
        const apiInfo = this.#knownApis.get(apiName);
        if (!apiInfo && apiName !== 'SYNO.API.Info') {
            throw new SynologyError(`Unknown API: ${apiName}. Have you called discoverApis()?`, -1);
        }

        const path = apiInfo?.path ?? 'query.cgi';
        const url = `${this.#baseUrl}/webapi/${path}`;

        // Build form data
        const formData = new URLSearchParams();
        formData.set('api', apiName);
        formData.set('method', method);
        formData.set('version', String(version));

        // Inject session ID
        if (this.#sid) {
            formData.set('_sid', this.#sid);
        }

        // Add extra params
        for (const [key, value] of Object.entries(params)) {
            if (value !== undefined && value !== null) {
                formData.set(key, String(value));
            }
        }

        // Execute request
        const response = await this.#fetchWithTimeout(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
            },
            body: formData.toString(),
        }, options.timeoutMs);

        const json = await this.#parseJson(response);

        if (!json.success) {
            const code = json.error?.code ?? -1;
            const errors = json.error?.errors;
            throw SynologyError.fromCode(code, apiName, errors);
        }

        if (apiName === 'SYNO.DownloadStation.BTSearch' && method === 'list') {
            console.log('--- BTSearch List RAW ---', JSON.stringify(json, null, 2));
        }

        return json.data ?? json;
    }

    /**
     * Make a multipart request (for file uploads).
     *
     * @param {string} apiName - API name
     * @param {string} method - Method name
     * @param {number} version - API version
     * @param {Record<string, any>} params - Text parameters
     * @param {Array<{name: string, file: File}>} files - Files to upload
     * @returns {Promise<any>} Response data
     */
    async requestMultipart(apiName, method, version, params = {}, files = []) {
        const apiInfo = this.#knownApis.get(apiName);
        if (!apiInfo) {
            throw new SynologyError(`Unknown API: ${apiName}`, -1);
        }

        const url = `${this.#baseUrl}/webapi/${apiInfo.path}`;

        // Prepare the upload parameters (mixed text headers and form data fields)
        const uploadParams = {
            api: apiName,
            version: String(version),
            method: method,
        };

        let uploadUrl = url;
        if (this.#sid) {
            uploadUrl += `?_sid=${encodeURIComponent(this.#sid)}`;
        }

        if (files.length !== 1) {
            throw new Error(`requestMultipart currently only supports exactly 1 file via expo-file-system. Received ${files.length}.`);
        }

        const { name, file } = files[0];
        const filename = file.name || 'upload.torrent';
        const mimetype = file.type || 'application/x-bittorrent';

        const boundary = '----WebKitFormBoundary7MA4YWxkTrZu0gW';

        // 1. Build the text-based parts of the multipart body IN EXACT LOGICAL ORDER
        let bodyText = '';
        const orderedParams = [
            ['api', uploadParams.api],
            ['version', uploadParams.version],
            ['method', uploadParams.method],
        ];

        // Add arbitrary extra params
        for (const [key, value] of Object.entries(params)) {
            orderedParams.push([key, value]);
        }

        for (const [key, value] of orderedParams) {
            if (value !== undefined && value !== null) {
                bodyText += `--${boundary}\r\n`;
                bodyText += `Content-Disposition: form-data; name="${key}"\r\n\r\n`;
                bodyText += `${String(value)}\r\n`;
            }
        }

        bodyText += `--${boundary}\r\n`;
        bodyText += `Content-Disposition: form-data; name="${name}"; filename="${filename}"\r\n`;
        bodyText += `Content-Type: ${mimetype}\r\n\r\n`;

        const footerText = `\r\n--${boundary}--\r\n`;

        console.log('--- MULTIPART UPLOAD ORDERED RAW BYTES ---', uploadUrl);
        console.log('File:', name, file.uri);

        try {
            // For content:// URIs from 3rd-party apps (e.g. Total Commander), expo-file-system
            // cannot read them directly. We must first copy the file into our own cache dir.
            let readableUri = file.uri;
            if (readableUri.startsWith('content://')) {
                const ext = (file.name || 'upload.torrent').split('.').pop() || 'torrent';
                const cacheUri = `${FileSystem.cacheDirectory}upload_${Date.now()}.${ext}`;
                console.log('Content URI detected, copying to cache:', cacheUri);
                await FileSystem.copyAsync({ from: readableUri, to: cacheUri });
                readableUri = cacheUri;
            }

            // Read file as base64 using literal 'base64' string to avoid undefined enums
            const fileBase64 = await FileSystem.readAsStringAsync(readableUri, { encoding: 'base64' });

            // To construct the raw byte string, we use atob to decode the base64 characters
            // Since React Native Hermes engine DOES NOT have atob/btoa built-in typically,
            // we will implement a polyfill inline just in case! 

            const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
            const atobPolyfill = (input) => {
                let str = input.replace(/=+$/, '');
                let output = '';
                if (str.length % 4 == 1) throw new Error("'atob' failed: The string to be decoded is not correctly encoded.");
                for (let bc = 0, bs = 0, buffer, i = 0; buffer = str.charAt(i++); ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
                    buffer = chars.indexOf(buffer);
                }
                return output;
            };

            const btoaPolyfill = (input) => {
                let str = input;
                let output = '';
                for (let block = 0, charCode, i = 0, map = chars; str.charAt(i | 0) || (map = '=', i % 1); output += map.charAt(63 & block >> 8 - i % 1 * 8)) {
                    charCode = str.charCodeAt(i += 3 / 4);
                    if (charCode > 0xFF) throw new Error("'btoa' failed: The string to be encoded contains characters outside of the Latin1 range.");
                    block = block << 8 | charCode;
                }
                return output;
            };

            const decodeBase64 = typeof atob === 'function' ? atob : atobPolyfill;
            const encodeBase64 = typeof btoa === 'function' ? btoa : btoaPolyfill;

            // Transliterate string to UTF-8 bytes represented as a Latin1 "byte string"
            // This allows btoa to process Unicode characters (like Cyrillic) safely.
            const utf8Encode = (str) => {
                return unescape(encodeURIComponent(str));
            };

            const fileRawBytes = decodeBase64(fileBase64);
            const fullMultipartBodyRawString = utf8Encode(bodyText) + fileRawBytes + utf8Encode(footerText);
            const fullMultipartBodyBase64 = encodeBase64(fullMultipartBodyRawString);

            const tempFileUri = FileSystem.cacheDirectory + 'multipart_temp_' + Date.now() + '.tmp';

            await FileSystem.writeAsStringAsync(tempFileUri, fullMultipartBodyBase64, {
                encoding: 'base64',
            });

            const response = await FileSystem.uploadAsync(uploadUrl, tempFileUri, {
                httpMethod: 'POST',
                uploadType: FileSystem.FileSystemUploadType.BINARY_CONTENT,
                headers: {
                    'Content-Type': `multipart/form-data; boundary=${boundary}`,
                    'Accept': 'application/json',
                },
            });

            await FileSystem.deleteAsync(tempFileUri, { idempotent: true });

            let json;
            try {
                json = JSON.parse(response.body);
            } catch (e) {
                console.error('Invalid JSON from server. Raw text:', response.body);
                throw new Error('Invalid JSON response from server during upload');
            }

            if (!json.success) {
                const code = json.error?.code ?? -1;
                throw SynologyError.fromCode(code, apiName);
            }

            return json.data ?? json;

        } catch (error) {
            console.error('Multipart upload Error:', error);
            throw error;
        }
    }

    /**
     * Cancel all in-flight requests.
     */
    abort() {
        this.#abortController?.abort();
        this.#abortController = null;
    }

    // ── Private Helpers ──────────────────────────────────────────────

    /**
     * Fetch with timeout and abort support.
     * @param {string} url
     * @param {RequestInit} [options]
     * @param {number} [timeoutMs=15000]
     * @returns {Promise<Response>}
     */
    async #fetchWithTimeout(url, options = {}, timeoutMs = 15000) {
        this.#abortController = new AbortController();
        const { signal } = this.#abortController;

        const timeoutId = setTimeout(() => this.#abortController?.abort(), timeoutMs);

        try {
            const response = await fetch(url, { ...options, signal });
            clearTimeout(timeoutId);
            return response;
        } catch (error) {
            clearTimeout(timeoutId);
            if (error.name === 'AbortError') {
                throw new NetworkError('Request timed out', 'TIMEOUT');
            }
            throw new NetworkError(error.message, 'NETWORK');
        }
    }

    /**
     * Safely parse JSON response and give context on HTML errors.
     */
    async #parseJson(response) {
        const text = await response.text();
        try {
            return JSON.parse(text);
        } catch (e) {
            if (text.toLowerCase().includes('400 bad request')) {
                throw new SynologyError('400 Bad Request: You might be sending an HTTP request to an HTTPS port (5001). Try using https:// or port 5000.', -1);
            }
            throw new SynologyError(`Server returned non-JSON response. Check your URL and port. Response start: ${text.substring(0, 40)}`, -1);
        }
    }

    /**
     * Get encryption info for login.
     * @returns {Promise<EncryptionInfo|null>}
     */
    async #getEncryptionInfo() {
        try {
            const data = await this.request('SYNO.API.Encryption', 'getinfo', 1, {
                format: 'module',
            });
            if (data?.cipherkey && data?.public_key) {
                return {
                    cipherKey: data.cipherkey,
                    cipherToken: data.ciphertoken,
                    publicKey: data.public_key,
                    serverTime: data.server_time,
                    extraParams: {
                        client_time: Math.floor(Date.now() / 1000),
                    },
                };
            }
        } catch {
            // Encryption not available
        }
        return null;
    }

    /**
     * Encrypt password using the server's public key.
     * Note: In a browser environment, we'd use SubtleCrypto or a library.
     * For now, falls back to plaintext (many NAS setups work over HTTPS anyway).
     *
     * @param {string} password
     * @param {EncryptionInfo} encInfo
     * @returns {string}
     */
    #encryptPassword(password, encInfo) {
        // TODO: Implement RSA encryption using Web Crypto API
        // For HTTPS connections, plaintext password over TLS is acceptable
        // The original app uses a custom RSA implementation for HTTP connections
        return password;
    }
}

// ── Error Types ──────────────────────────────────────────────────

/**
 * Synology API error — returned by the NAS in the response body.
 * This is DIFFERENT from a network error.
 */
export class SynologyError extends Error {
    /** @type {number} Synology error code */
    code;

    /** @type {string} Which API returned the error */
    apiName;

    /** @type {any} Additional error details */
    details;

    constructor(message, code, apiName = '', details = null) {
        super(message);
        this.name = 'SynologyError';
        this.code = code;
        this.apiName = apiName;
        this.details = details;
    }

    get isSessionError() {
        return this.code === 106 || // Session timeout
            this.code === 107 || // Session interrupted
            this.code === 119;   // SID not found
    }

    /** True if this error is transient and the request might succeed on retry */
    get isTransient() {
        return this.code === 100 || // Unknown (could be temporary)
            this.code === 117;   // Busy
    }

    get isAuthError() {
        // Download Station uses 400-408 for task/file errors, not auth errors.
        if (this.apiName.includes('DownloadStation')) {
            return this.code === 105;
        }
        return this.code === 105 || (this.code >= 400 && this.code <= 408);
        // 105 = permission denied for API
        // 400 = no such account
        // 401 = disabled account
        // 402 = permission denied
        // 403 = 2FA required
    }

    /**
     * Create a SynologyError from an error code.
     * @param {number} code
     * @param {string} apiName
     * @param {any} details
     * @returns {SynologyError}
     */
    static fromCode(code, apiName = '', details = null) {
        let message = SynologyError.#codeMessages[code] ?? `API error ${code}`;

        // Download Station specific error codes
        if (apiName.includes('DownloadStation')) {
            const dsError = SynologyError.#dsCodeMessages[code];
            if (dsError) {
                message = dsError;
            }
        }

        return new SynologyError(message, code, apiName, details);
    }

    static #dsCodeMessages = {
        400: 'File upload failed',
        401: 'Max number of tasks reached',
        402: 'Destination denied',
        403: 'Destination does not exist',
        404: 'Invalid task ID',
        405: 'Invalid task action',
        406: 'No default destination',
        407: 'Set destination failed',
        408: 'File does not exist',
    };

    static #codeMessages = {
        100: 'Unknown error',
        101: 'Bad request',
        102: 'No such API',
        103: 'No such method',
        104: 'API version not supported',
        105: 'No permission',
        106: 'Session timeout',
        107: 'Session interrupted (duplicate login)',
        119: 'Missing SID',
        150: 'IP blocked due to too many failed attempts',
        160: 'Insufficient application privilege',
        400: 'Account not found',
        401: 'Account disabled',
        402: 'Permission denied',
        403: 'OTP code required',
        404: 'OTP authentication failed',
        406: 'OTP enforcement required',
        407: 'Blocked IP — too many login failures',
        408: 'Expired password, cannot change',
        409: 'Expired password',
        410: 'Password must be changed',
    };
}

/**
 * Network-level error — the request never reached the NAS or got a response.
 * This is DIFFERENT from a SynologyError (API-level error).
 *
 * KEY FIX: The original DS Get app treated all network errors as session
 * timeouts, which caused unnecessary logouts. We separate them properly.
 */
export class NetworkError extends Error {
    /** @type {'TIMEOUT'|'NETWORK'|'OFFLINE'} Error category */
    category;

    constructor(message, category = 'NETWORK') {
        super(message);
        this.name = 'NetworkError';
        this.category = category;
    }

    /** Network errors are ALWAYS transient — never force a logout for these */
    get isTransient() {
        return true;
    }

    /** Network errors are NEVER session errors */
    get isSessionError() {
        return false;
    }
}

/**
 * @typedef {Object} ApiInfo
 * @property {string} path - API CGI path
 * @property {number} minVersion - Minimum supported version
 * @property {number} maxVersion - Maximum supported version
 */

/**
 * @typedef {Object} LoginResult
 * @property {string} sid - Session ID
 * @property {string} [deviceId] - Device token for "remember me"
 * @property {boolean} [isAdmin] - Whether user is admin
 */

/**
 * @typedef {Object} EncryptionInfo
 * @property {string} cipherKey
 * @property {string} cipherToken
 * @property {string} publicKey
 * @property {number} serverTime
 * @property {Object} extraParams
 */
