package com.antimirov.betterdsget.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/**
 * Resolves a Synology QuickConnect ID to the fastest reachable direct HTTP/HTTPS NAS URL,
 * falling back to Synology's QuickConnect Relay tunnel when direct/forwarded ports are unreachable.
 */
public class QuickConnectResolver {
    public interface ProgressListener {
        void onStatus(String status);
    }

    private static final String QUICKCONNECT_API_URL = "https://global.quickconnect.to/Serv.php";
    private static final int DIRECT_PROBE_TIMEOUT_MS = 3000;
    private static final int RELAY_PROBE_TIMEOUT_MS = 6000;

    public static String resolveQuickConnect(String quickConnectId) throws Exception {
        return resolveQuickConnect(quickConnectId, QUICKCONNECT_API_URL, null);
    }

    public static String resolveQuickConnect(String quickConnectId, ProgressListener listener) throws Exception {
        return resolveQuickConnect(quickConnectId, QUICKCONNECT_API_URL, listener);
    }

    public static String resolveQuickConnect(String quickConnectId, String relayUrl) throws Exception {
        return resolveQuickConnect(quickConnectId, relayUrl, null);
    }

    public static String resolveQuickConnect(String quickConnectId, String relayUrl, ProgressListener listener) throws Exception {
        if (quickConnectId == null || quickConnectId.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid QuickConnect ID");
        }
        String cleanId = quickConnectId.trim();

        notifyStatus(listener, "Querying QuickConnect server...");
        JSONObject payload = new JSONObject();
        payload.put("version", 1);
        payload.put("command", "get_server_info");
        payload.put("serverID", cleanId);
        payload.put("id", "dsm_portal_https");
        payload.put("get_ca_fingerprints", true);

        JSONObject data = postQuickConnectCommand(relayUrl, payload);

        int errno = data.optInt("errno", -1);
        if (errno != 0) {
            throw new Exception("QuickConnect failed. The ID might be incorrect or the NAS is offline. (Error code: " + errno + ")");
        }

        JSONObject serverInfo = data.optJSONObject("server");
        if (serverInfo == null) {
            throw new Exception("Unexpected QuickConnect response: Missing server data.");
        }
        JSONObject serviceInfo = data.optJSONObject("service");

        // Stage 1: Direct LAN / WAN / DDNS candidates (prefers local LAN when on the same Wi-Fi)
        List<String> directCandidates = extractDirectCandidates(serverInfo, serviceInfo);
        if (!directCandidates.isEmpty()) {
            notifyStatus(listener, "Probing direct LAN / WAN addresses...");
            try {
                return probeUrls(directCandidates, DIRECT_PROBE_TIMEOUT_MS);
            } catch (Exception ignored) {
                // Direct/port-forwarded endpoints unreachable — fall through to QuickConnect Relay
            }
        }

        // Stage 2a: Use relay endpoint if already provided in get_server_info
        List<String> relayCandidates = extractRelayCandidates(serviceInfo);
        if (!relayCandidates.isEmpty()) {
            notifyStatus(listener, "Connecting via QuickConnect Relay...");
            try {
                return probeUrls(relayCandidates, RELAY_PROBE_TIMEOUT_MS);
            } catch (Exception ignored) {
                // Existing relay endpoint stale or unreachable — request a fresh tunnel below
            }
        }

        // Stage 2b: Request a fresh QuickConnect Relay tunnel via control_host
        notifyStatus(listener, "Requesting QuickConnect Relay tunnel...");
        String tunnelEndpoint = relayUrl;
        if (QUICKCONNECT_API_URL.equals(relayUrl)) {
            JSONObject env = data.optJSONObject("env");
            String controlHost = env != null ? env.optString("control_host", "") : "";
            if (!controlHost.isEmpty()) {
                tunnelEndpoint = "https://" + controlHost + "/Serv.php";
            }
        }

        JSONObject tunnelPayload = new JSONObject();
        tunnelPayload.put("version", 1);
        tunnelPayload.put("command", "request_tunnel");
        tunnelPayload.put("serverID", cleanId);
        tunnelPayload.put("id", "dsm_portal_https");
        tunnelPayload.put("platform", "android");

        try {
            JSONObject tunnelData = postQuickConnectCommand(tunnelEndpoint, tunnelPayload);
            if (tunnelData.optInt("errno", -1) == 0) {
                List<String> freshRelayCandidates = extractRelayCandidates(tunnelData.optJSONObject("service"));
                if (!freshRelayCandidates.isEmpty()) {
                    return probeUrls(freshRelayCandidates, RELAY_PROBE_TIMEOUT_MS);
                }
            }
        } catch (Exception ignored) {
            // Fall through to final error message
        }

        throw new Exception("Could not reach the NAS on any of the resolved QuickConnect addresses (direct or relay). Ensure it is powered on and QuickConnect is enabled.");
    }

    private static void notifyStatus(ProgressListener listener, String status) {
        if (listener != null) {
            listener.onStatus(status);
        }
    }

    private static JSONObject postQuickConnectCommand(String endpointUrl, JSONObject payload) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpointUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            if (conn.getResponseCode() != 200) {
                throw new Exception("QuickConnect API error: HTTP " + conn.getResponseCode());
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return new JSONObject(sb.toString());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static List<String> extractDirectCandidates(JSONObject serverInfo, JSONObject serviceInfo) {
        // Because we query id="dsm_portal_https", service.port is DSM's HTTPS port (typically 5001)
        // and service.ext_port is the external mapped HTTPS port (0 if port forwarding is disabled).
        int lanHttpsPort = (serviceInfo != null && serviceInfo.optInt("port", 0) > 0)
                ? serviceInfo.optInt("port")
                : 5001;
        int extHttpsPort = (serviceInfo != null && serviceInfo.optInt("ext_port", 0) > 0)
                ? serviceInfo.optInt("ext_port")
                : lanHttpsPort;

        Set<String> candidates = new LinkedHashSet<>();

        JSONArray interfaces = serverInfo.optJSONArray("interface");
        if (interfaces != null) {
            for (int i = 0; i < interfaces.length(); i++) {
                JSONObject iface = interfaces.optJSONObject(i);
                if (iface != null) {
                    addHostCandidates(candidates, iface.optString("ip", ""), lanHttpsPort);
                }
            }
        }

        JSONObject external = serverInfo.optJSONObject("external");
        if (external != null) {
            addHostCandidates(candidates, external.optString("ip", ""), extHttpsPort);
        }

        addHostCandidates(candidates, serverInfo.optString("ddns", ""), extHttpsPort);
        return new ArrayList<>(candidates);
    }

    private static void addHostCandidates(Set<String> candidates, String host, int httpsPort) {
        if (host == null || host.isEmpty() || "null".equalsIgnoreCase(host)) return;
        candidates.add("https://" + host + ":" + httpsPort);
        if (httpsPort != 5000) {
            candidates.add("http://" + host + ":5000");
        }
    }

    public static List<String> extractRelayCandidates(JSONObject serviceInfo) {
        Set<String> candidates = new LinkedHashSet<>();
        if (serviceInfo == null) return new ArrayList<>();
        int relayPort = serviceInfo.optInt("relay_port", 0);
        if (relayPort <= 0) return new ArrayList<>();

        String relayDn = serviceInfo.optString("relay_dn", "");
        if (!relayDn.isEmpty() && !"null".equalsIgnoreCase(relayDn)) {
            candidates.add("https://" + relayDn + ":" + relayPort);
        }
        String relayDual = serviceInfo.optString("relay_dualstack", "");
        if (!relayDual.isEmpty() && !"null".equalsIgnoreCase(relayDual)) {
            candidates.add("https://" + relayDual + ":" + relayPort);
        }
        String relayIp = serviceInfo.optString("relay_ip", "");
        if (!relayIp.isEmpty() && !"null".equalsIgnoreCase(relayIp)) {
            candidates.add("https://" + relayIp + ":" + relayPort);
        }
        return new ArrayList<>(candidates);
    }

    public static String probeUrls(List<String> urls, int timeoutMs) throws Exception {
        if (urls == null || urls.isEmpty()) {
            throw new Exception("No URLs to probe.");
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(urls.size(), 8));
        CompletionService<String> completionService = new ExecutorCompletionService<>(executor);
        List<Future<String>> futures = new ArrayList<>();

        try {
            for (String candidate : urls) {
                futures.add(completionService.submit(() -> {
                    String cleanUrl = candidate.replaceAll("/+$", "");
                    String pingUrl = cleanUrl + "/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=SYNO.API.Auth";
                    HttpURLConnection conn = null;
                    try {
                        conn = (HttpURLConnection) new URL(pingUrl).openConnection();
                        if (conn instanceof HttpsURLConnection) {
                            SynologyClient.configureTrustAllHttps((HttpsURLConnection) conn);
                        }
                        conn.setConnectTimeout(timeoutMs);
                        conn.setReadTimeout(timeoutMs);
                        conn.setRequestMethod("GET");
                        if (conn.getResponseCode() == 200) {
                            StringBuilder sb = new StringBuilder();
                            try (BufferedReader r = new BufferedReader(
                                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = r.readLine()) != null) sb.append(line);
                            }
                            JSONObject json = new JSONObject(sb.toString());
                            if (json.optBoolean("success", false)) {
                                return cleanUrl;
                            }
                        }
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                    throw new Exception("Probe failed for " + cleanUrl);
                }));
            }

            long deadline = System.currentTimeMillis() + timeoutMs;
            for (int i = 0; i < urls.size(); i++) {
                long remaining = Math.max(100, deadline - System.currentTimeMillis());
                Future<String> completed = completionService.poll(remaining, TimeUnit.MILLISECONDS);
                if (completed == null) break;
                try {
                    String winner = completed.get();
                    if (winner != null) {
                        return winner;
                    }
                } catch (Exception ignored) {
                    // Try next completed probe
                }
            }
            throw new Exception("Could not reach the NAS on any of the resolved QuickConnect addresses. Ensure it is powered on and accessible.");
        } finally {
            for (Future<String> f : futures) {
                f.cancel(true);
            }
            executor.shutdownNow();
        }
    }
}

