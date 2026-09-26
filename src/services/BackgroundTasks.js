import * as Notifications from 'expo-notifications';
import * as TaskManager from 'expo-task-manager';
import * as BackgroundFetch from 'expo-background-fetch';
import BackgroundTimer from 'react-native-background-timer';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { DownloadStation } from '../api/download-station';
import { SynologyClient } from '../api/synology-client';
import notifee, { AndroidImportance, AndroidForegroundServiceType } from '@notifee/react-native';
import { AppState } from 'react-native';

const BACKGROUND_FETCH_TASK = 'BACKGROUND_FETCH_TASK';
const FOREGROUND_NOTIFICATION_ID = 'smart-foreground-service';
const FOREGROUND_CHANNEL_ID = 'smart-downloads';

// Global cache to avoid heavy discoverApis() calls every 3 seconds
let cachedSynologyClient = null;
let cachedNasUrl = null;

// Configure expo-notifications behavior for standard push
Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldPlaySound: true,
    shouldSetBadge: true,
  }),
});

// Handle incoming background events
notifee.onBackgroundEvent(async ({ type, detail }) => {
  // If the user swipes it away (which shouldn't happen with ongoing: true) or interacts
  if (type === 0 /* EventType.DISMISSED */) {
    console.log('[SmartService] Notification dismissed by user in background.');
    await notifee.stopForegroundService();
  }
});

export const notifeeForegroundServiceRunner = async (notification) => {
  return new Promise((resolve) => {
    let shouldRun = true;
    let isChecking = false;
    
    // State for the decoupled UI ticker
    let displayActiveCount = 0;
    let displaySpeedText = '0 KB/s';
    let displayError = null;

    // We MUST handle cancellation gracefully
    notifee.onForegroundEvent(({ type, detail }) => {
      if (type === 0 /* EventType.DISMISSED */ || type === 2 /* EventType.ACTION_PRESS */) {
        console.log('[SmartService] Notification interaction detected (type ' + type + ').');
      }
    });

    const runLoop = () => {
      console.log('[SmartService] Headless task started.');
      
      const doNetworkCheck = async () => {
        if (!shouldRun) return;
        if (isChecking) return;
        isChecking = true;

        try {
          const result = await checkSynologyTasks(false); // pass false so it returns data silently
          
          displayActiveCount = result.activeCount || 0;
          displaySpeedText = result.speedText || '0 KB/s';
          displayError = null;
          
          const lastTimeStr = await AsyncStorage.getItem('last_interaction_time');
          const interactionTime = lastTimeStr ? parseInt(lastTimeStr, 10) : Date.now();
          const timeSinceInteraction = Date.now() - interactionTime;
          const gracePeriodActive = timeSinceInteraction < 5 * 60 * 1000;
          
          if (!result.isActive && !gracePeriodActive) {
            console.log('[SmartService] No active tasks and grace period over. Stopping.');
            shouldRun = false;
          }
        } catch (err) {
          console.error('[SmartService] Network check error:', err);
          displayError = 'Offline/Wait';
        } finally {
          isChecking = false;
        }
      };

      // Run immediately once
      doNetworkCheck();
      
      // Schedule the native-backed background timer every 3 seconds to unconditionally tick the UI
      BackgroundTimer.runBackgroundTimer(async () => {
         if (!shouldRun) {
            BackgroundTimer.stopBackgroundTimer();
            console.log('[SmartService] Headless task loop finished.');
            await notifee.stopForegroundService();
            await notifee.cancelNotification(notification.id || FOREGROUND_NOTIFICATION_ID);
            resolve();
            return;
         }

         const nowStr = new Date().toLocaleTimeString('en-US', { hour12: false });
         let bodyText;
         if (displayError) {
             bodyText = `[${nowStr}] ${displayActiveCount} task(s) (NAS busy...)`;
         } else if (displayActiveCount > 0 || displaySpeedText !== '0 KB/s') {
             bodyText = `[${nowStr}] ${displayActiveCount} active task${displayActiveCount === 1 ? '' : 's'} (${displaySpeedText})`;
         } else {
             bodyText = `[${nowStr}] 0 active tasks (Grace period)`;
         }

         try {
             await updateNotification(notification.id || FOREGROUND_NOTIFICATION_ID, bodyText);
         } catch (e) {
             console.log('[SmartService] Notification update failed completely. Service probably killed by OS. Stopping.');
             shouldRun = false;
         }

         // Fire off the background check (it ignores if already checking)
         doNetworkCheck();
      }, 3000);
    };

    runLoop();
  });
};

export async function updateInteractionTime() {
  try {
    await AsyncStorage.setItem('last_interaction_time', Date.now().toString());
    await startSmartForegroundService();
  } catch (e) {
    console.warn('Failed to update interaction time', e);
  }
}

export async function startSmartForegroundService() {
  if (AppState.currentState !== 'active') {
    console.log('[SmartService] Skipping Foreground Service launch: App is in background, Android 12+ OS prevents this.');
    return;
  }
  
  console.log('[SmartService] Attempting to start Foreground Service. Current AppState:', AppState.currentState);

  // Create channel for the persistent notification
  await notifee.createChannel({
    id: FOREGROUND_CHANNEL_ID,
    name: 'Active Downloads',
    importance: AndroidImportance.LOW, // LOW so it doesn't pop up or make sound, just sits in status bar
  });

  try {
    const batteryOptimized = await notifee.isBatteryOptimizationEnabled();
    if (batteryOptimized) {
      console.warn('[SmartService] Battery optimization is enabled. This may kill the background service.');
    }

    const dataSyncType = AndroidForegroundServiceType?.DATA_SYNC ?? AndroidForegroundServiceType?.FOREGROUND_SERVICE_TYPE_DATA_SYNC ?? 1;
    await notifee.displayNotification({
      id: FOREGROUND_NOTIFICATION_ID,
      title: 'Better DS Get',
      android: {
        channelId: FOREGROUND_CHANNEL_ID,
        asForegroundService: true,
        ongoing: true,
        foregroundServiceTypes: [dataSyncType],
        pressAction: {
          id: 'default',
        },
      },
    });
  } catch (err) {
    console.error('[SmartService] Failed to start:', err);
  }
}

/**
 * Register the 15m background task (fallback)
 */
export async function registerBackgroundFetchAsync() {
  console.log('Registering background fetch task...');
  try {
    await BackgroundFetch.registerTaskAsync(BACKGROUND_FETCH_TASK, {
      minimumInterval: 15 * 60, // 15 minutes
      stopOnTerminate: false,
      startOnBoot: true,
    });
    console.log('Background fetch task registered.');
  } catch (err) {
    console.warn('Background fetch task registration failed:', err);
  }
}

TaskManager.defineTask(BACKGROUND_FETCH_TASK, async () => {
  console.log('[BackgroundFetch] Running task...');
  
  try {
    const result = await checkSynologyTasks(false);
    return result.newData ? BackgroundFetch.BackgroundFetchResult.NewData : BackgroundFetch.BackgroundFetchResult.NoData;
  } catch (error) {
    console.error('[BackgroundFetch] Error:', error);
    return BackgroundFetch.BackgroundFetchResult.Failed;
  }
});

function formatSpeed(bytesPerSec) {
  if (bytesPerSec === 0) return '0 KB/s';
  if (bytesPerSec > 1024 * 1024) return `${(bytesPerSec / (1024 * 1024)).toFixed(1)} MB/s`;
  return `${(bytesPerSec / 1024).toFixed(1)} KB/s`;
}

// Shared logic used by both 15m fetch and 7s foreground
async function checkSynologyTasks(isForeground, notificationId = null) {
  const sessionStr = await AsyncStorage.getItem('synology_session');
  const settingsStr = await AsyncStorage.getItem('synology_settings');

  if (!sessionStr || !settingsStr) {
    if (isForeground && notificationId) {
      await updateNotification(notificationId, `Waiting for Login...`);
    }
    return { isActive: false, newData: false };
  }

  const session = JSON.parse(sessionStr);
  const settings = JSON.parse(settingsStr);

  if (!session.sid || !settings.nasUrl) {
    if (isForeground && notificationId) {
      await updateNotification(notificationId, `[${new Date().toLocaleTimeString()}] Missing SID/URL`);
    }
    return { isActive: false, newData: false };
  }

  let currentTasks = [];
  try {
    if (!cachedSynologyClient || cachedNasUrl !== settings.nasUrl) {
      cachedSynologyClient = new SynologyClient(settings.nasUrl);
      cachedNasUrl = settings.nasUrl;
    }
    cachedSynologyClient.setSession(session.sid);

    // Only discover APIs once to save 50% of the network overhead
    if (cachedSynologyClient.knownApiNames.length === 0) {
      await cachedSynologyClient.discoverApis();
    }
    
    const ds = new DownloadStation(cachedSynologyClient);
    const result = await ds.listTasks({ limit: -1, additional: ['transfer'], timeoutMs: 15000 });
    currentTasks = result.tasks || [];
  } catch (apiErr) {
    console.error('[SmartService] Synology API Error during background check:', apiErr);
    if (isForeground && notificationId) {
      const nowStr = new Date().toLocaleTimeString('en-US', { hour12: false });
      await updateNotification(notificationId, `[${nowStr}] API Error: ${apiErr.message}`);
    }
    return { isActive: false, newData: false };
  }

  const lastTasksStr = await AsyncStorage.getItem('last_known_tasks');
  const lastTasks = lastTasksStr ? JSON.parse(lastTasksStr) : {};

  const finished = [];
  const errored = [];
  const updatedTasks = {};
  
  let totalDlSpeed = 0;
  let activeCount = 0;

  for (const task of currentTasks) {
    const prev = lastTasks[task.id];
    updatedTasks[task.id] = { status: task.status, title: task.title };
    
    // Transfer logic
    const speed = task.speedDownload || 0;
    totalDlSpeed += speed;
    
    // Count as active unless it's paused, finished, or errored
    const status = task.status || 'unknown';
    const isTerminal = ['finished', 'paused', 'error'].includes(status) || status.startsWith('error_');
    if (!isTerminal) {
      activeCount++;
    }

    if (prev) {
      if (task.status === 'finished' && prev.status !== 'finished') {
        finished.push(task.title);
      } else if (task.status === 'error' && prev.status !== 'error') {
        errored.push(task.title);
      }
    }
  }

  await AsyncStorage.setItem('last_known_tasks', JSON.stringify(updatedTasks));

  // Send Standard Notifications for completions
  if (finished.length > 0) {
    await Notifications.scheduleNotificationAsync({
      content: {
        title: 'Download Finished',
        body: finished.length === 1 ? `"${finished[0]}" completed.` : `${finished.length} tasks completed.`,
      },
      trigger: null,
    });
  }

  if (errored.length > 0) {
    await Notifications.scheduleNotificationAsync({
      content: {
        title: 'Download Error',
        body: errored.length === 1 ? `"${errored[0]}" failed.` : `${errored.length} tasks failed.`,
        color: '#FF6B6B',
      },
      trigger: null,
    });
  }

  return { 
    isActive: totalDlSpeed > 0, 
    newData: finished.length > 0 || errored.length > 0,
    activeCount: activeCount,
    speedText: formatSpeed(totalDlSpeed)
  };
}

// Helper to quickly update the sticky notification consistently
async function updateNotification(notificationId, bodyText) {
  const dataSyncType = AndroidForegroundServiceType?.DATA_SYNC ?? AndroidForegroundServiceType?.FOREGROUND_SERVICE_TYPE_DATA_SYNC ?? 1;
  const notificationOptions = {
    id: notificationId,
    title: 'Better DS Get',
    body: bodyText,
    android: {
      channelId: FOREGROUND_CHANNEL_ID,
      asForegroundService: true,
      ongoing: true, // Keep it sticky
      foregroundServiceTypes: [dataSyncType],
      pressAction: { id: 'default' },
    },
  };

  try {
    await notifee.displayNotification(notificationOptions);
  } catch (err) {
    if (err.message && err.message.includes('ForegroundServiceStartNotAllowedException')) {
      console.log('[SmartService] Caught ForegroundServiceStartNotAllowedException updating from background. Retrying as standard update.');
      notificationOptions.android.asForegroundService = false;
      await notifee.displayNotification(notificationOptions);
    } else {
      console.error('[SmartService] updateNotification error:', err);
      throw err;
    }
  }
}

/**
 * Fire a test notification immediately — useful for debugging without a background task.
 */
export async function sendTestNotification() {
  try {
    const { status } = await Notifications.getPermissionsAsync();
    if (status !== 'granted') {
      const { status: newStatus } = await Notifications.requestPermissionsAsync();
      if (newStatus !== 'granted') {
        console.warn('[TestNotification] Permission denied');
        return false;
      }
    }
    await Notifications.scheduleNotificationAsync({
      content: {
        title: '🔔 Test Notification',
        body: 'Notifications are working correctly!',
      },
      trigger: null,
    });
    console.log('[TestNotification] Fired successfully');
    return true;
  } catch (e) {
    console.error('[TestNotification] Failed:', e);
    return false;
  }
}
