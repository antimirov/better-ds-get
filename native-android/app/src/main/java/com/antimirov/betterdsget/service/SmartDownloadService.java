package com.antimirov.betterdsget.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.antimirov.betterdsget.MainActivity;
import com.antimirov.betterdsget.R;
import com.antimirov.betterdsget.api.DownloadStation;
import com.antimirov.betterdsget.api.Models.TaskItem;
import com.antimirov.betterdsget.api.Models.TaskListResult;
import com.antimirov.betterdsget.api.SynologyClient;
import com.antimirov.betterdsget.util.AndroidPrefsStore;
import com.antimirov.betterdsget.util.FormatUtils;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native Android Foreground Service ("Smart Service") that shows a live download speedometer
 * in the notification bar and alerts the user when downloads complete or fail.
 */
public class SmartDownloadService extends Service {
    private static final String TAG = "SmartDownloadService";
    public static final String CHANNEL_FOREGROUND = "smart_downloads_channel";
    public static final String CHANNEL_ALERTS = "download_alerts_channel";
    private static final int NOTIFICATION_ID_FOREGROUND = 1001;
    private static int alertNotificationCounter = 2000;

    private ScheduledExecutorService scheduler;
    private final AtomicBoolean isChecking = new AtomicBoolean(false);
    private SynologyClient cachedClient;
    private String cachedNasUrl;
    private AndroidPrefsStore prefsStore;

    private volatile int displayActiveCount = 0;
    private volatile String displaySpeedText = "0 KB/s";
    private volatile String displayError = null;

    public static void startOrKick(Context context) {
        try {
            AndroidPrefsStore store = new AndroidPrefsStore(context);
            store.updateInteractionTime();
            Intent intent = new Intent(context, SmartDownloadService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not start SmartDownloadService (likely in background): " + e.getMessage());
        }
    }

    public static boolean sendTestNotification(Context context) {
        try {
            ensureChannels(context);
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) {
                return false;
            }
            Notification n = new Notification.Builder(context, CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.app_icon)
                    .setContentTitle("🔔 Test Notification")
                    .setContentText("Notifications are working correctly!")
                    .setAutoCancel(true)
                    .build();
            nm.notify(alertNotificationCounter++, n);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to send test notification", e);
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefsStore = new AndroidPrefsStore(this);
        ensureChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification initial = buildStickyNotification("Monitoring downloads...");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID_FOREGROUND, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID_FOREGROUND, initial);
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        startLoop();
        return START_STICKY;
    }

    private synchronized void startLoop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SmartDownloadServiceTicker");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            try {
                boolean keepRunning = checkNasTasks();
                updateStickyStatus();
                if (!keepRunning) {
                    Log.i(TAG, "No active downloads and 5-minute grace period elapsed. Stopping service.");
                    stopForeground(true);
                    stopSelf();
                }
            } catch (Exception e) {
                Log.w(TAG, "Ticker error: " + e.getMessage());
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

    private boolean checkNasTasks() {
        if (!isChecking.compareAndSet(false, true)) {
            return true;
        }
        try {
            String nasUrl = prefsStore.getActiveNasUrl();
            String sid = prefsStore.getActiveSid();
            if (nasUrl == null || nasUrl.isEmpty() || sid == null || sid.isEmpty()) {
                displayError = "Waiting for Login...";
                return false;
            }

            if (cachedClient == null || !nasUrl.equals(cachedNasUrl)) {
                cachedClient = new SynologyClient(nasUrl);
                cachedNasUrl = nasUrl;
            }
            cachedClient.setSession(sid);

            if (!cachedClient.isApisDiscovered()) {
                cachedClient.discoverApis();
            }

            DownloadStation ds = new DownloadStation(cachedClient);
            TaskListResult result = ds.listTasks(0, -1, Collections.singletonList("transfer"), 15000);
            List<TaskItem> currentTasks = result.tasks != null ? result.tasks : Collections.emptyList();

            JSONObject lastTasks = prefsStore.getLastKnownTasks();
            JSONObject updatedTasks = new JSONObject();
            List<String> newlyFinished = new ArrayList<>();
            List<String> newlyErrored = new ArrayList<>();

            long totalDlSpeed = 0;
            int activeCount = 0;

            for (TaskItem task : currentTasks) {
                JSONObject prev = lastTasks.optJSONObject(task.id);
                JSONObject entry = new JSONObject();
                entry.put("status", task.status);
                entry.put("title", task.title);
                updatedTasks.put(task.id, entry);

                totalDlSpeed += Math.max(0, task.speedDownload);
                String st = task.status != null ? task.status : "unknown";
                boolean isTerminal = "finished".equals(st) || "paused".equals(st)
                        || "error".equals(st) || st.startsWith("error_");
                if (!isTerminal) {
                    activeCount++;
                }

                if (prev != null) {
                    String prevStatus = prev.optString("status", "");
                    if ("finished".equals(st) && !"finished".equals(prevStatus)) {
                        newlyFinished.add(task.title);
                    } else if ("error".equals(st) && !"error".equals(prevStatus)) {
                        newlyErrored.add(task.title);
                    }
                }
            }

            prefsStore.saveLastKnownTasks(updatedTasks);

            if (!newlyFinished.isEmpty()) {
                String body = newlyFinished.size() == 1
                        ? "\"" + newlyFinished.get(0) + "\" completed."
                        : newlyFinished.size() + " tasks completed.";
                fireAlertNotification("Download Finished", body);
            }

            if (!newlyErrored.isEmpty()) {
                String body = newlyErrored.size() == 1
                        ? "\"" + newlyErrored.get(0) + "\" failed."
                        : newlyErrored.size() + " tasks failed.";
                fireAlertNotification("Download Error", body);
            }

            displayActiveCount = activeCount;
            displaySpeedText = FormatUtils.formatSpeed(totalDlSpeed);
            displayError = null;

            long timeSinceInteraction = System.currentTimeMillis() - prefsStore.getLastInteractionTime();
            boolean gracePeriodActive = timeSinceInteraction < 5 * 60 * 1000L;
            return totalDlSpeed > 0 || activeCount > 0 || gracePeriodActive;
        } catch (Exception e) {
            displayError = "Offline/Wait";
            long timeSinceInteraction = System.currentTimeMillis() - prefsStore.getLastInteractionTime();
            return timeSinceInteraction < 5 * 60 * 1000L;
        } finally {
            isChecking.set(false);
        }
    }

    private void updateStickyStatus() {
        String nowStr = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String bodyText;
        if (displayError != null) {
            bodyText = "[" + nowStr + "] " + displayActiveCount + " task(s) (NAS busy...)";
        } else if (displayActiveCount > 0 || !"0 KB/s".equals(displaySpeedText)) {
            String plural = displayActiveCount == 1 ? "" : "s";
            bodyText = "[" + nowStr + "] " + displayActiveCount + " active task" + plural + " (" + displaySpeedText + ")";
        } else {
            bodyText = "[" + nowStr + "] 0 active tasks (Grace period)";
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID_FOREGROUND, buildStickyNotification(bodyText));
        }
    }

    private Notification buildStickyNotification(String bodyText) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_FOREGROUND)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle("Better DS Get")
                .setContentText(bodyText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    private void fireAlertNotification(String title, String body) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification n = new Notification.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .build();
        nm.notify(alertNotificationCounter++, n);
    }

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel fg = new NotificationChannel(
                    CHANNEL_FOREGROUND,
                    "Active Downloads",
                    NotificationManager.IMPORTANCE_LOW
            );
            fg.setDescription("Live download speed status bar indicator");
            nm.createNotificationChannel(fg);

            NotificationChannel alerts = new NotificationChannel(
                    CHANNEL_ALERTS,
                    "Download Completions & Errors",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            alerts.setDescription("Alerts when downloads finish or encounter an error");
            nm.createNotificationChannel(alerts);
        }
    }

    @Override
    public void onDestroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

