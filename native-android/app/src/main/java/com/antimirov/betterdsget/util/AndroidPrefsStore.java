package com.antimirov.betterdsget.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.antimirov.betterdsget.api.Models.StoredCredentials;
import com.antimirov.betterdsget.api.SessionManager.CredentialStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Android SharedPreferences implementation of {@link CredentialStore},
 * plus helpers for recent destination folders and background task state.
 */
public class AndroidPrefsStore implements CredentialStore {
    private static final String PREFS_NAME = "better_ds_get_prefs";
    private static final String KEY_CREDS = "better_ds_get_creds";
    private static final String KEY_ACTIVE_URL = "synology_nas_url";
    private static final String KEY_ACTIVE_SID = "synology_sid";
    private static final String KEY_RECENT_FOLDERS = "recent_folders";
    private static final String KEY_LAST_INTERACTION = "last_interaction_time";
    private static final String KEY_LAST_KNOWN_TASKS = "last_known_tasks";

    private final SharedPreferences prefs;

    public AndroidPrefsStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public void saveCredentials(StoredCredentials credentials) {
        if (credentials == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("url", credentials.url);
            obj.put("account", credentials.account);
            obj.put("password", credentials.password);
            obj.put("originalAddress", credentials.originalAddress);
            prefs.edit().putString(KEY_CREDS, obj.toString()).apply();
        } catch (Exception ignored) {}
    }

    @Override
    public StoredCredentials loadCredentials() {
        String raw = prefs.getString(KEY_CREDS, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject obj = new JSONObject(raw);
            return new StoredCredentials(
                    obj.optString("url", ""),
                    obj.optString("account", ""),
                    obj.optString("password", ""),
                    obj.optString("originalAddress", "")
            );
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void clearCredentials() {
        prefs.edit().remove(KEY_CREDS).apply();
    }

    @Override
    public void saveActiveSession(String nasUrl, String sid) {
        prefs.edit()
                .putString(KEY_ACTIVE_URL, nasUrl)
                .putString(KEY_ACTIVE_SID, sid)
                .apply();
    }

    @Override
    public void clearActiveSession() {
        prefs.edit()
                .remove(KEY_ACTIVE_URL)
                .remove(KEY_ACTIVE_SID)
                .apply();
    }

    public String getActiveNasUrl() {
        return prefs.getString(KEY_ACTIVE_URL, null);
    }

    public String getActiveSid() {
        return prefs.getString(KEY_ACTIVE_SID, null);
    }

    public List<String> getRecentFolders() {
        List<String> list = new ArrayList<>();
        String raw = prefs.getString(KEY_RECENT_FOLDERS, null);
        if (raw == null) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String path = arr.optString(i, "");
                if (!path.isEmpty()) list.add(path);
            }
        } catch (Exception ignored) {}
        return list;
    }

    public List<String> addRecentFolder(String folderPath) {
        List<String> current = getRecentFolders();
        List<String> updated = new ArrayList<>();
        if (folderPath != null && !folderPath.isEmpty()) {
            updated.add(folderPath);
        }
        for (String existing : current) {
            if (!existing.equals(folderPath) && updated.size() < 5) {
                updated.add(existing);
            }
        }
        JSONArray arr = new JSONArray(updated);
        prefs.edit().putString(KEY_RECENT_FOLDERS, arr.toString()).apply();
        return updated;
    }

    public void updateInteractionTime() {
        prefs.edit().putLong(KEY_LAST_INTERACTION, System.currentTimeMillis()).apply();
    }

    public long getLastInteractionTime() {
        return prefs.getLong(KEY_LAST_INTERACTION, System.currentTimeMillis());
    }

    public JSONObject getLastKnownTasks() {
        String raw = prefs.getString(KEY_LAST_KNOWN_TASKS, null);
        if (raw == null) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public void saveLastKnownTasks(JSONObject tasksMap) {
        if (tasksMap == null) return;
        prefs.edit().putString(KEY_LAST_KNOWN_TASKS, tasksMap.toString()).apply();
    }
}

