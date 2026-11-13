package com.antimirov.betterdsget.util;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geolocation utility for mapping peer/tracker IP addresses and hostnames to country flags.
 * Uses ip-api.com with in-memory caching.
 */
public final class GeolocationHelper {
    public static class GeoInfo {
        public final String countryCode;
        public final String country;
        public final String flag;

        public GeoInfo(String countryCode, String country) {
            this.countryCode = countryCode;
            this.country = country;
            this.flag = getFlagEmoji(countryCode);
        }
    }

    private static final Map<String, GeoInfo> CACHE = new ConcurrentHashMap<>();

    private GeolocationHelper() {}

    /**
     * Converts an ISO 3166-1 alpha-2 country code (e.g., "US", "NL") to a regional flag emoji ("🇺🇸", "🇳🇱").
     */
    public static String getFlagEmoji(String countryCode) {
        if (countryCode == null || countryCode.length() != 2) {
            return "";
        }
        String upper = countryCode.toUpperCase(Locale.US);
        char c1 = upper.charAt(0);
        char c2 = upper.charAt(1);
        if (c1 < 'A' || c1 > 'Z' || c2 < 'A' || c2 > 'Z') {
            return "";
        }
        int first = 0x1F1E6 + (c1 - 'A');
        int second = 0x1F1E6 + (c2 - 'A');
        return new String(Character.toChars(first)) + new String(Character.toChars(second));
    }

    public static GeoInfo getCached(String ipOrHost) {
        if (ipOrHost == null) return null;
        return CACHE.get(ipOrHost);
    }

    public static GeoInfo lookupIpCountry(String ipOrHost) {
        if (ipOrHost == null || ipOrHost.isEmpty()
                || "127.0.0.1".equals(ipOrHost) || "::1".equals(ipOrHost) || "localhost".equalsIgnoreCase(ipOrHost)) {
            return null;
        }
        GeoInfo cached = CACHE.get(ipOrHost);
        if (cached != null) return cached;

        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://ip-api.com/json/" + ipOrHost + "?fields=status,message,countryCode,country");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }
                JSONObject json = new JSONObject(sb.toString());
                if ("success".equals(json.optString("status"))) {
                    GeoInfo info = new GeoInfo(
                            json.optString("countryCode", ""),
                            json.optString("country", "")
                    );
                    CACHE.put(ipOrHost, info);
                    return info;
                }
            }
        } catch (Exception ignored) {
            // Non-critical helper
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }
}

