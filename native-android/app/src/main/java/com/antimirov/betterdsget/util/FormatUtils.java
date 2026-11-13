package com.antimirov.betterdsget.util;

import com.antimirov.betterdsget.api.Models.TaskItem;

import java.util.Locale;

/**
 * Pure formatting and URL utility functions used across UI and background services.
 */
public final class FormatUtils {
    private FormatUtils() {}

    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = (int) (Math.log(bytes) / Math.log(1024));
        unitIndex = Math.min(unitIndex, units.length - 1);
        double value = bytes / Math.pow(1024, unitIndex);
        if (unitIndex == 0) {
            return bytes + " B";
        }
        String formatted = String.format(Locale.US, "%.2f", value);
        // Strip trailing zeroes cleanly like JS parseFloat(toFixed(2))
        if (formatted.indexOf('.') > 0) {
            formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return formatted + " " + units[unitIndex];
    }

    public static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "0 KB/s";
        if (bytesPerSec > 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0);
    }

    public static String formatEta(long seconds) {
        if (seconds < 0) return "Unknown";
        if (seconds == 0) return "Finished";
        long d = seconds / (3600 * 24);
        long h = (seconds % (3600 * 24)) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;

        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0) sb.append(s).append("s");
        String res = sb.toString().trim();
        return res.isEmpty() ? "0s" : res;
    }

    public static String formatPieces(TaskItem info) {
        if ("finished".equals(info.status)) {
            return info.totalPieces > 0
                    ? info.totalPieces + " / " + info.totalPieces
                    : "All complete";
        }
        if (info.downloadedPieces == 0 && info.sizeDownloaded > 0 && !"hash_checking".equals(info.status)) {
            return "Unknown (DSM quirk)";
        }
        String pieceSizeStr = info.pieceLength > 0 ? " x " + formatBytes(info.pieceLength) : "";
        String totalStr = info.totalPieces > 0 ? String.valueOf(info.totalPieces) : "?";
        return info.downloadedPieces + " / " + totalStr + pieceSizeStr;
    }

    public static String formatConnectedPeers(TaskItem info) {
        if ("paused".equals(info.status) || "finished".equals(info.status)) {
            int seeds = Math.max(info.connectedSeeder, info.unconnectedSeeder);
            int peers = Math.max(info.connectedLeecher, info.unconnectedPeers);
            return seeds + " seeds / " + peers + " peers";
        }
        return info.connectedSeeder + " seeds / " + info.connectedLeecher + " leechers";
    }

    public static int computeTotalPeers(TaskItem info) {
        if (info.totalPeers > 0) return info.totalPeers;
        return info.connectedSeeder + info.connectedLeecher;
    }

    public static String maskSid(String sid) {
        if (sid == null || sid.isEmpty()) return "N/A";
        if (sid.length() <= 8) return "****";
        return sid.substring(0, 4) + "..." + sid.substring(sid.length() - 4);
    }

    public static boolean isQuickConnectId(String input) {
        if (input == null) return false;
        String trimmed = input.trim();
        return !trimmed.isEmpty()
                && !trimmed.contains(".")
                && !trimmed.contains(":")
                && !trimmed.contains("/")
                && !trimmed.toLowerCase(Locale.US).startsWith("http");
    }

    public static String normalizeDirectUrl(String input) {
        if (input == null) return "";
        String formatted = input.trim().replaceAll("/+$", "");
        if (formatted.isEmpty()) return "";
        if (!formatted.matches("(?i)^https?://.*")) {
            if (formatted.contains(":5001")) {
                formatted = "https://" + formatted;
            } else {
                formatted = "http://" + formatted;
            }
        }
        return formatted;
    }

    public static String stripLeadingSlash(String path) {
        if (path == null) return null;
        return path.startsWith("/") ? path.substring(1) : path;
    }
}

