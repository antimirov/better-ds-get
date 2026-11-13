package com.antimirov.betterdsget;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Native Android Intent helper for magnet links, synodownload:// links, and .torrent files
 * (including permission-gated content:// URIs from Firefox, Chrome, Gmail, and file managers).
 */
public class TorrentIntentHelper {
    private static final String TAG = "TorrentIntentHelper";

    public static class IncomingPayload {
        public final boolean isTorrentFile;
        public final String urlOrMagnet;
        public final String fileName;
        public final byte[] fileBytes;

        public IncomingPayload(boolean isTorrentFile, String urlOrMagnet, String fileName, byte[] fileBytes) {
            this.isTorrentFile = isTorrentFile;
            this.urlOrMagnet = urlOrMagnet;
            this.fileName = fileName;
            this.fileBytes = fileBytes;
        }
    }

    public static IncomingPayload extractPayload(Activity activity, Intent intent) {
        if (activity == null || intent == null) {
            return null;
        }
        if (!Intent.ACTION_VIEW.equals(intent.getAction()) && !Intent.ACTION_SEND.equals(intent.getAction())) {
            return null;
        }

        Uri uri = intent.getData();
        if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
            uri = intent.getClipData().getItemAt(0).getUri();
        }
        if (uri == null && intent.getParcelableExtra(Intent.EXTRA_STREAM) instanceof Uri) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri == null) {
            return null;
        }
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.US) : "";
        Log.d(TAG, "Inspecting incoming intent URI: " + uri + " (scheme=" + scheme + ")");

        if ("magnet".equals(scheme) || "synodownload".equals(scheme) || "synodownloads".equals(scheme)) {
            String decoded = cleanUriString(uri.toString());
            return new IncomingPayload(false, decoded, null, null);
        }

        if ("content".equals(scheme) || "file".equals(scheme)) {
            return readTorrentUri(activity, uri);
        }

        if ("http".equals(scheme) || "https".equals(scheme)) {
            return new IncomingPayload(false, uri.toString(), null, null);
        }

        return null;
    }

    public static IncomingPayload readTorrentUri(Activity activity, Uri uri) {
        if (activity == null || uri == null) return null;
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.US) : "";

        try {
            if ("content".equals(scheme)) {
                ContentResolver resolver = activity.getContentResolver();
                String resolvedName = queryDisplayName(resolver, uri);
                if (resolvedName == null || resolvedName.trim().isEmpty()) {
                    resolvedName = "intent_upload_" + System.currentTimeMillis() + ".torrent";
                } else if (!resolvedName.toLowerCase(Locale.US).endsWith(".torrent")) {
                    resolvedName = resolvedName + ".torrent";
                }
                resolvedName = new File(resolvedName).getName();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (InputStream is = resolver.openInputStream(uri)) {
                    if (is == null) return null;
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = is.read(buffer)) > 0) {
                        baos.write(buffer, 0, n);
                    }
                }
                byte[] bytes = baos.toByteArray();
                // Also cache on disk for safety
                File cacheFile = new File(activity.getCacheDir(), resolvedName);
                try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    fos.write(bytes);
                }
                return new IncomingPayload(true, cacheFile.getAbsolutePath(), resolvedName, bytes);
            } else if ("file".equals(scheme)) {
                String path = uri.getPath();
                if (path == null) return null;
                File f = new File(path);
                if (!f.exists()) return null;
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (InputStream is = new FileInputStream(f)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = is.read(buffer)) > 0) {
                        baos.write(buffer, 0, n);
                    }
                }
                return new IncomingPayload(true, f.getAbsolutePath(), f.getName(), baos.toByteArray());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read torrent URI: " + e.getMessage(), e);
        }
        return null;
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return cursor.getString(idx);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not query OpenableColumns.DISPLAY_NAME: " + e.getMessage());
        }
        return null;
    }

    public static String cleanUriString(String url) {
        if (url == null) return "";
        String target = url;
        if (target.contains("/--/")) {
            target = target.split("/--/")[1];
        }
        try {
            if (target.contains("%")) {
                target = URLDecoder.decode(target, StandardCharsets.UTF_8.name());
                if (target.contains("%25") || target.contains("%3A")) {
                    target = URLDecoder.decode(target, StandardCharsets.UTF_8.name());
                }
            }
        } catch (Exception ignored) {}
        return target;
    }
}

