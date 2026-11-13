package com.antimirov.betterdsget.api;

import com.antimirov.betterdsget.api.Models.ApiInfo;
import com.antimirov.betterdsget.api.Models.LoginResult;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Core HTTP/JSON API client for Synology DSM Web API.
 *
 * Improvements over both the original DS Get app and the React Native bridge:
 * - Native SSL socket factory that accepts self-signed NAS certificates without OS hacks
 * - Exact byte-ordered multipart builder in pure Java (zero base64 temp-file workarounds)
 * - Isolated per-request connections tracked in a thread-safe Set so background polling
 *   never clobbers in-flight search or UI requests
 */
public class SynologyClient {
    private static final String SESSION_NAME = "DownloadStation";
    private static final int DEFAULT_TIMEOUT_MS = 15000;
    private static final SSLSocketFactory TRUST_ALL_SSL_FACTORY = createTrustAllSslFactory();

    private volatile String baseUrl = "";
    private volatile String sid = null;
    private volatile boolean apisDiscovered = false;
    private final Map<String, ApiInfo> knownApis = new ConcurrentHashMap<>();
    private final Set<HttpURLConnection> activeConnections = ConcurrentHashMap.newKeySet();

    public SynologyClient(String baseUrl) {
        setBaseUrl(baseUrl);
    }

    public void setBaseUrl(String url) {
        this.baseUrl = url != null ? url.trim().replaceAll("/+$", "") : "";
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public boolean isHttps() {
        return baseUrl.toLowerCase(Locale.US).startsWith("https://");
    }

    public boolean isAuthenticated() {
        return sid != null && !sid.isEmpty();
    }

    public String getSid() {
        return sid;
    }

    public void setSession(String sid) {
        this.sid = sid;
    }

    public boolean isApisDiscovered() {
        return apisDiscovered && !knownApis.isEmpty();
    }

    public List<String> getKnownApiNames() {
        return new ArrayList<>(knownApis.keySet());
    }

    public ApiInfo getApiInfo(String apiName) {
        return knownApis.get(apiName);
    }

    public boolean isApiSupported(String apiName, int version) {
        ApiInfo info = knownApis.get(apiName);
        if (info == null) return false;
        return version >= info.minVersion && version <= info.maxVersion;
    }

    public boolean supportsDS2() {
        return knownApis.containsKey("SYNO.DownloadStation2.Task");
    }

    // ── API Discovery ──────────────────────────────────────────────

    public Map<String, ApiInfo> discoverApis() throws SynologyError, NetworkError {
        String url = baseUrl + "/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=all";
        HttpResult http = executeHttp(url, "GET", null, null, DEFAULT_TIMEOUT_MS);
        JSONObject json = parseJson(http);

        if (!json.optBoolean("success", false)) {
            JSONObject errObj = json.optJSONObject("error");
            int code = errObj != null ? errObj.optInt("code", -1) : -1;
            throw new SynologyError("API discovery failed", code, "SYNO.API.Info", null);
        }

        JSONObject data = json.optJSONObject("data");
        knownApis.clear();
        if (data != null) {
            Iterator<String> keys = data.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONObject info = data.optJSONObject(name);
                if (info != null) {
                    knownApis.put(name, new ApiInfo(
                            info.optString("path", "query.cgi"),
                            info.optInt("minVersion", 1),
                            info.optInt("maxVersion", 1)
                    ));
                }
            }
        }
        apisDiscovered = true;
        return knownApis;
    }

    // ── Authentication ──────────────────────────────────────────────

    public LoginResult login(String account, String password, String otp, String deviceName, String deviceId)
            throws SynologyError, NetworkError {
        if (!apisDiscovered || knownApis.isEmpty()) {
            discoverApis();
        }

        ApiInfo authInfo = getApiInfo("SYNO.API.Auth");
        if (authInfo == null) {
            throw new SynologyError("SYNO.API.Auth not found on this NAS", -1);
        }

        int authVersion = Math.min(authInfo.maxVersion, 6);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("account", account);
        params.put("passwd", password);
        params.put("session", SESSION_NAME);
        params.put("format", "sid");

        if (otp != null && !otp.trim().isEmpty() && authVersion >= 5) {
            params.put("otp_code", otp.trim());
        }
        if (deviceName != null && !deviceName.isEmpty() && authVersion >= 6) {
            params.put("device_name", deviceName);
            params.put("enable_device_token", "yes");
        }
        if (deviceId != null && !deviceId.isEmpty() && authVersion >= 6) {
            params.put("device_id", deviceId);
        }

        JSONObject result = request("SYNO.API.Auth", "login", authVersion, params);
        String returnedSid = result.optString("sid", "");
        if (returnedSid.isEmpty() && result.optJSONObject("data") != null) {
            returnedSid = result.optJSONObject("data").optString("sid", "");
        }
        if (returnedSid.isEmpty()) {
            throw new SynologyError("Login succeeded but no SID returned", -1, "SYNO.API.Auth", null);
        }

        this.sid = returnedSid;
        String retDeviceId = result.optString("device_id", "");
        boolean isAdmin = result.optBoolean("is_admin", false);
        return new LoginResult(returnedSid, retDeviceId, isAdmin);
    }

    public void logout() {
        if (sid == null) return;
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("session", SESSION_NAME);
            request("SYNO.API.Auth", "logout", 1, params);
        } catch (Exception ignored) {
            // Best-effort logout
        } finally {
            sid = null;
        }
    }

    public boolean testSession() throws NetworkError {
        if (sid == null || sid.isEmpty()) return false;
        try {
            if (isApiSupported("SYNO.Core.Desktop.Timeout", 1)) {
                request("SYNO.Core.Desktop.Timeout", "check", 1, new LinkedHashMap<>());
            } else {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("query", "SYNO.API.Auth");
                request("SYNO.API.Info", "query", 1, params);
            }
            return true;
        } catch (SynologyError e) {
            if (e.isSessionError()) {
                return false;
            }
            return true;
        }
    }

    // ── Generic Request ──────────────────────────────────────────────

    public JSONObject request(String apiName, String method, int version, Map<String, String> params)
            throws SynologyError, NetworkError {
        return request(apiName, method, version, params, DEFAULT_TIMEOUT_MS);
    }

    public JSONObject request(String apiName, String method, int version, Map<String, String> params, int timeoutMs)
            throws SynologyError, NetworkError {
        ApiInfo apiInfo = knownApis.get(apiName);
        if (apiInfo == null && !"SYNO.API.Info".equals(apiName)) {
            throw new SynologyError("Unknown API: " + apiName + ". Have you called discoverApis()?", -1, apiName, null);
        }

        String cgiPath = apiInfo != null ? apiInfo.path : "query.cgi";
        String url = baseUrl + "/webapi/" + cgiPath;

        StringBuilder formBody = new StringBuilder();
        appendFormParam(formBody, "api", apiName);
        appendFormParam(formBody, "method", method);
        appendFormParam(formBody, "version", String.valueOf(version));

        if (sid != null && !sid.isEmpty()) {
            appendFormParam(formBody, "_sid", sid);
        }

        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    appendFormParam(formBody, entry.getKey(), entry.getValue());
                }
            }
        }

        byte[] bodyBytes = formBody.toString().getBytes(StandardCharsets.UTF_8);
        HttpResult http = executeHttp(
                url,
                "POST",
                "application/x-www-form-urlencoded; charset=UTF-8",
                bodyBytes,
                timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS
        );

        JSONObject json = parseJson(http);
        if (!json.optBoolean("success", false)) {
            JSONObject err = json.optJSONObject("error");
            int code = err != null ? err.optInt("code", -1) : -1;
            Object details = err != null ? err.opt("errors") : null;
            throw SynologyError.fromCode(code, apiName, details);
        }

        JSONObject data = json.optJSONObject("data");
        return data != null ? data : json;
    }

    /**
     * Executes a strict byte-ordered multipart upload (required by SYNO.DownloadStation2.Task.create v2
     * where the 'torrent' file field MUST come after 'api', 'version', 'method', and 'size').
     */
    public JSONObject requestMultipart(
            String apiName,
            String method,
            int version,
            LinkedHashMap<String, String> orderedParams,
            String fileFieldName,
            String fileName,
            String mimeType,
            byte[] fileBytes
    ) throws SynologyError, NetworkError {
        ApiInfo apiInfo = knownApis.get(apiName);
        if (apiInfo == null) {
            throw new SynologyError("Unknown API: " + apiName, -1, apiName, null);
        }

        String uploadUrl = baseUrl + "/webapi/" + apiInfo.path;
        if (sid != null && !sid.isEmpty()) {
            uploadUrl += "?_sid=" + urlEncode(sid);
        }

        byte[] multipartBody = buildOrderedMultipartBody(
                apiName,
                method,
                version,
                orderedParams,
                fileFieldName,
                fileName != null ? fileName : "upload.torrent",
                mimeType != null ? mimeType : "application/x-bittorrent",
                fileBytes != null ? fileBytes : new byte[0]
        );

        HttpResult http = executeHttp(
                uploadUrl,
                "POST",
                "multipart/form-data; boundary=" + MULTIPART_BOUNDARY,
                multipartBody,
                30000
        );

        JSONObject json = parseJson(http);
        if (!json.optBoolean("success", false)) {
            JSONObject err = json.optJSONObject("error");
            int code = err != null ? err.optInt("code", -1) : -1;
            Object details = err != null ? err.opt("errors") : null;
            throw SynologyError.fromCode(code, apiName, details);
        }

        JSONObject data = json.optJSONObject("data");
        return data != null ? data : json;
    }

    public static final String MULTIPART_BOUNDARY = "----WebKitFormBoundary7MA4YWxkTrZu0gW";

    public static byte[] buildOrderedMultipartBody(
            String apiName,
            String method,
            int version,
            LinkedHashMap<String, String> orderedParams,
            String fileFieldName,
            String fileName,
            String mimeType,
            byte[] fileBytes
    ) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            StringBuilder textPart = new StringBuilder();

            LinkedHashMap<String, String> allTextFields = new LinkedHashMap<>();
            allTextFields.put("api", apiName);
            allTextFields.put("version", String.valueOf(version));
            allTextFields.put("method", method);
            if (orderedParams != null) {
                allTextFields.putAll(orderedParams);
            }

            for (Map.Entry<String, String> entry : allTextFields.entrySet()) {
                if (entry.getValue() != null) {
                    textPart.append("--").append(MULTIPART_BOUNDARY).append("\r\n");
                    textPart.append("Content-Disposition: form-data; name=\"").append(entry.getKey()).append("\"\r\n\r\n");
                    textPart.append(entry.getValue()).append("\r\n");
                }
            }

            textPart.append("--").append(MULTIPART_BOUNDARY).append("\r\n");
            textPart.append("Content-Disposition: form-data; name=\"")
                    .append(fileFieldName)
                    .append("\"; filename=\"")
                    .append(fileName)
                    .append("\"\r\n");
            textPart.append("Content-Type: ").append(mimeType).append("\r\n\r\n");

            out.write(textPart.toString().getBytes(StandardCharsets.UTF_8));
            out.write(fileBytes);
            out.write(("\r\n--" + MULTIPART_BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build multipart body", e);
        }
    }

    public void abortAll() {
        for (HttpURLConnection conn : activeConnections) {
            try {
                conn.disconnect();
            } catch (Exception ignored) {}
        }
        activeConnections.clear();
    }

    public int getActiveConnectionCount() {
        return activeConnections.size();
    }

    // ── Internal HTTP & JSON Helpers ───────────────────────────────

    private static class HttpResult {
        final int statusCode;
        final String body;
        final String url;

        HttpResult(int statusCode, String body, String url) {
            this.statusCode = statusCode;
            this.body = body;
            this.url = url;
        }
    }

    private HttpResult executeHttp(String urlStr, String method, String contentType, byte[] body, int timeoutMs)
            throws NetworkError {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection) {
                configureTrustAllHttps((HttpsURLConnection) conn);
            }

            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");

            activeConnections.add(conn);

            if (body != null && ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))) {
                conn.setDoOutput(true);
                if (contentType != null) {
                    conn.setRequestProperty("Content-Type", contentType);
                }
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                    os.flush();
                }
            }

            int status = conn.getResponseCode();
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseText = readStream(stream);
            return new HttpResult(status, responseText, urlStr);
        } catch (SocketTimeoutException e) {
            throw new NetworkError("Request timed out", NetworkError.Category.TIMEOUT, e);
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Network error";
            if (msg.toLowerCase(Locale.US).contains("timed out") || msg.toLowerCase(Locale.US).contains("timeout")) {
                throw new NetworkError("Request timed out", NetworkError.Category.TIMEOUT, e);
            }
            throw new NetworkError(msg, NetworkError.Category.NETWORK, e);
        } finally {
            if (conn != null) {
                activeConnections.remove(conn);
                conn.disconnect();
            }
        }
    }

    private JSONObject parseJson(HttpResult http) throws SynologyError {
        String text = http.body != null ? http.body.trim() : "";
        try {
            if (text.isEmpty()) {
                throw new JSONException("Empty response body");
            }
            return new JSONObject(text);
        } catch (JSONException e) {
            String lower = text.toLowerCase(Locale.US);
            if (lower.contains("400 bad request")) {
                throw new SynologyError(
                        "400 Bad Request: You might be sending an HTTP request to an HTTPS port (5001). Try using https:// or port 5000.",
                        -1
                );
            }
            if (http.statusCode == 504 || lower.contains("gateway time-out")) {
                throw new SynologyError(
                        "504 Gateway Time-out: NAS search engine took too long to respond.",
                        -1
                );
            }
            String preview = text.length() > 40 ? text.substring(0, 40) : text;
            throw new SynologyError(
                    "Server returned non-JSON response (HTTP " + http.statusCode + "). Response start: " + preview,
                    -1
            );
        }
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private static void appendFormParam(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) sb.append('&');
        sb.append(urlEncode(key)).append('=').append(urlEncode(value));
    }

    private static String urlEncode(String val) {
        return URLEncoder.encode(val != null ? val : "", StandardCharsets.UTF_8);
    }

    public static void configureTrustAllHttps(HttpsURLConnection https) {
        if (https == null) return;
        if (TRUST_ALL_SSL_FACTORY != null) {
            https.setSSLSocketFactory(TRUST_ALL_SSL_FACTORY);
        }
        https.setHostnameVerifier((hostname, session) -> true);
    }

    private static SSLSocketFactory createTrustAllSslFactory() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            return null;
        }
    }
}

