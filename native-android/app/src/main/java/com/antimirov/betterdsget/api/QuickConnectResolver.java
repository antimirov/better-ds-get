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
 * Resolves a Synology QuickConnect ID to the fastest reachable direct HTTP/HTTPS NAS URL.
 */
public class QuickConnectResolver {
    private static final String QUICKCONNECT_API_URL = "https://global.quickconnect.to/Serv.php";

    public static String resolveQuickConnect(String quickConnectId) throws Exception {
        return resolveQuickConnect(quickConnectId, QUICKCONNECT_API_URL);
    }

    public static String resolveQuickConnect(String quickConnectId, String relayUrl) throws Exception {
        if (quickConnectId == null || quickConnectId.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid QuickConnect ID");
        }

        JSONObject payload = new JSONObject();
        payload.put("version", 1);
        payload.put("command", "get_server_info");
        payload.put("serverID", quickConnectId.trim());
        payload.put("id", "dsm_portal_https");
        payload.put("get_ca_fingerprints", true);

        HttpURLConnection conn = null;
        JSONObject data;
        try {
            URL url = new URL(relayUrl);
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
            data = new JSONObject(sb.toString());
        } finally {
            if (conn != null) conn.disconnect();
        }

        int errno = data.optInt("errno", -1);
        if (errno != 0) {
            throw new Exception("QuickConnect failed. The ID might be incorrect or the NAS is offline. (Error code: " + errno + ")");
        }

        JSONObject serverInfo = data.optJSONObject("server");
        if (serverInfo == null) {
            throw new Exception("Unexpected QuickConnect response: Missing server data.");
        }
        JSONObject serviceInfo = data.optJSONObject("service");
        int httpPort = serviceInfo != null ? serviceInfo.optInt("port", 5000) : 5000;
        int httpsPort = serviceInfo != null ? serviceInfo.optInt("ext_port", 5001) : 5001;
        if (httpPort <= 0) httpPort = 5000;
        if (httpsPort <= 0) httpsPort = 5001;

        Set<String> candidates = new LinkedHashSet<>();

        JSONArray interfaces = serverInfo.optJSONArray("interface");
        if (interfaces != null) {
            for (int i = 0; i < interfaces.length(); i++) {
                JSONObject iface = interfaces.optJSONObject(i);
                if (iface != null) {
                    String ip = iface.optString("ip", "");
                    if (!ip.isEmpty()) {
                        candidates.add("http://" + ip + ":" + httpPort);
                        candidates.add("https://" + ip + ":" + httpsPort);
                    }
                }
            }
        }

        JSONObject external = serverInfo.optJSONObject("external");
        if (external != null) {
            String extIp = external.optString("ip", "");
            if (!extIp.isEmpty()) {
                candidates.add("http://" + extIp + ":" + httpPort);
                candidates.add("https://" + extIp + ":" + httpsPort);
            }
        }

        String ddns = serverInfo.optString("ddns", "");
        if (!ddns.isEmpty() && !"null".equalsIgnoreCase(ddns)) {
            candidates.add("http://" + ddns + ":" + httpPort);
            candidates.add("https://" + ddns + ":" + httpsPort);
        }

        if (candidates.isEmpty()) {
            throw new Exception("QuickConnect returned no valid IP addresses or hostnames.");
        }

        return probeUrls(new ArrayList<>(candidates), 5000);
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

