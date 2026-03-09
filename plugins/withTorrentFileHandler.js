const { withMainActivity, withDangerousMod, createRunOncePlugin } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

/**
 * This plugin fixes Firefox's "Permission Denial" when opening .torrent files.
 * It intercepts the Intent in MainActivity (native), copies the protected content:// file
 * to a local app cache, and swaps the Intent URI for a safe file:// URI.
 */

const withTorrentFileHandler = (config) => {
    // 1. Inject the helper Java code into the native project
    config = withTorrentIntentHelper(config);

    // 2. Patch MainActivity to use the helper
    config = withMainActivity(config, (config) => {
        let content = config.modResults.contents;

        // Determine if it's Kotlin or Java (Expo modern default is Kotlin)
        const isKotlin = config.modResults.language === 'kt';

        if (isKotlin) {
            // Kotlin patch
            if (!content.includes('import com.antimirov.betterdsget.TorrentIntentHelper')) {
                content = content.replace(
                    /package .*/,
                    `$&\n\nimport android.content.Intent\nimport com.antimirov.betterdsget.TorrentIntentHelper`
                );
            }

            // Patch onCreate - Move to the top of the function
            if (!content.includes('TorrentIntentHelper.handleIntent(this, intent)')) {
                // Match the whole function body if possible or just inject after {
                content = content.replace(
                    /override fun onCreate\(savedInstanceState: Bundle\?\) \{/,
                    `$&\n    TorrentIntentHelper.handleIntent(this, intent)`
                );
            }

            // Patch onNewIntent - Ensure it's BEFORE super.onNewIntent
            if (content.includes('override fun onNewIntent')) {
                if (!content.includes('TorrentIntentHelper.handleIntent(this, intent)')) {
                    // Inject at the beginning of the function body
                    content = content.replace(
                        /override fun onNewIntent\(intent: Intent.*\)? \{/,
                        `$&\n    TorrentIntentHelper.handleIntent(this, intent)`
                    );
                }
            } else {
                // Add onNewIntent if missing
                content = content.replace(
                    /}\n$/,
                    `\n  override fun onNewIntent(intent: Intent) {\n    TorrentIntentHelper.handleIntent(this, intent)\n    super.onNewIntent(intent)\n  }\n}`
                );
            }
        } else {
            // Java patch
            if (!content.includes('import com.antimirov.betterdsget.TorrentIntentHelper;')) {
                content = content.replace(
                    /package .*;/,
                    `$&\n\nimport android.content.Intent;\nimport com.antimirov.betterdsget.TorrentIntentHelper;`
                );
            }

            // Patch onCreate
            if (!content.includes('TorrentIntentHelper.handleIntent(this, getIntent())')) {
                content = content.replace(
                    /super\.onCreate\(savedInstanceState\);/,
                    `TorrentIntentHelper.handleIntent(this, getIntent());\n    $&`
                );
            }

            // Patch onNewIntent
            if (content.includes('public void onNewIntent')) {
                if (!content.includes('TorrentIntentHelper.handleIntent(this, intent)')) {
                    content = content.replace(
                        /public void onNewIntent\(Intent intent\) \{/,
                        `$&\n    TorrentIntentHelper.handleIntent(this, intent);`
                    );
                }
            } else {
                // Add onNewIntent if missing
                content = content.replace(
                    /}\n$/,
                    `\n  @Override\n  public void onNewIntent(Intent intent) {\n    TorrentIntentHelper.handleIntent(this, intent);\n    super.onNewIntent(intent);\n  }\n}`
                );
            }
        }

        config.modResults.contents = content;
        return config;
    });

    return config;
};

const withTorrentIntentHelper = (config) => {
    return withDangerousMod(config, [
        'android',
        async (config) => {
            const projectRoot = config.modRequest.projectRoot;
            const packagePath = 'com/antimirov/betterdsget';
            const folder = path.join(projectRoot, 'android/app/src/main/java', packagePath);

            if (!fs.existsSync(folder)) {
                fs.mkdirSync(folder, { recursive: true });
            }

            const helperFile = path.join(folder, 'TorrentIntentHelper.java');
            const helperContent = `package com.antimirov.betterdsget;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class TorrentIntentHelper {
    private static final String TAG = "TorrentIntentHelper";

    public static void handleIntent(Activity activity, Intent intent) {
        if (activity == null || intent == null || intent.getData() == null) return;

        Uri uri = intent.getData();
        String scheme = uri.getScheme();
        
        Log.d(TAG, "Checking intent URI: " + uri.toString() + " | Scheme: " + scheme);

        // We only care about content:// URIs that look like torrents
        if ("content".equalsIgnoreCase(scheme)) {
            String type = intent.getType();
            String path = uri.getPath();
            boolean isTorrent = (path != null && path.toLowerCase().endsWith(".torrent")) || 
                               (type != null && type.toLowerCase().contains("bittorrent"));

            if (isTorrent) {
                Log.i(TAG, "Intercepting torrent content URI: " + uri.toString());
                
                try {
                    File localFile = copyToCache(activity, uri);
                    if (localFile != null) {
                        Log.i(TAG, "Swapping Intent data to: file://" + localFile.getAbsolutePath());
                        intent.setData(Uri.fromFile(localFile));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to copy torrent to cache: " + e.getMessage(), e);
                }
            }
        }
    }

    private static File copyToCache(Activity activity, Uri contentUri) throws Exception {
        ContentResolver resolver = activity.getContentResolver();
        String fileName = "intent_upload_" + System.currentTimeMillis() + ".torrent";
        File cacheFile = new File(activity.getCacheDir(), fileName);

        try (InputStream is = resolver.openInputStream(contentUri);
             FileOutputStream os = new FileOutputStream(cacheFile)) {
            
            byte[] buffer = new byte[8192];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
            return cacheFile;
        }
    }
}
`;
            fs.writeFileSync(helperFile, helperContent);
            return config;
        },
    ]);
};

module.exports = createRunOncePlugin(withTorrentFileHandler, 'withTorrentFileHandler', '1.0.0');
