import * as Notifications from 'expo-notifications';
import * as TaskManager from 'expo-task-manager';
import * as BackgroundFetch from 'expo-background-fetch';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { DownloadStation } from '../api/download-station';
import { SynologyClient } from '../api/synology-client';
import notifee, { AndroidImportance, AndroidForegroundServiceType } from '@notifee/react-native';
import { AppState } from 'react-native';

const BACKGROUND_FETCH_TASK = 'BACKGROUND_FETCH_TASK';
const FOREGROUND_NOTIFICATION_ID = 'smart-foreground-service';
const FOREGROUND_CHANNEL_ID = 'smart-downloads';

// In-memory state for the foreground service removed (Headless JS and UI thread don't share variables reliably)

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

    // We MUST handle cancellation gracefully
    notifee.onForegroundEvent(({ type, detail }) => {
      // If Notifee says the user interacted with the notification or the OS wants it gone
      if (type === 0 /* EventType.DISMISSED */ || type === 2 /* EventType.ACTION_PRESS */) {
        console.log('[SmartService] Notification interaction detected (type ' + type + ').');
        // We only stop if they actually swipe it away (which ongoing:true should prevent anyway)
      }
    });

    const runLoop = async () => {
      console.log('[SmartService] Headless task started.');
      // Loop until conditions stop matching
      while (shouldRun) {
        try {
          let timerId;
          const timeoutPromise = new Promise((_, reject) => {
            timerId = setTimeout(() => reject(new Error('Background task timed out')), 15000);
          });
          
          try {
            const result = await Promise.race([
              checkSynologyTasks(true, notification.id || FOREGROUND_NOTIFICATION_ID),
              timeoutPromise
            ]);
            clearTimeout(timerId);
            
            const lastTimeStr = await AsyncStorage.getItem('last_interaction_time');
            const interactionTime = lastTimeStr ? parseInt(lastTimeStr, 10) : Date.now();
            const timeSinceInteraction = Date.now() - interactionTime;
            const gracePeriodActive = timeSinceInteraction < 5 * 60 * 1000;
            
            // Stay alive if there's active downloading OR we are in the 5m grace period
            // If the notification explicitly failed to start due to Android 14 blocks, result.isActive will be false.
            if (!result.isActive && (!gracePeriodActive || !result.isActive)) {
              console.log('[SmartService] No active tasks or foreground service is dead. Stopping.');
              shouldRun = false;
            }
          } catch (innerErr) {
            clearTimeout(timerId);
            if (innerErr.message && innerErr.message.includes('ForegroundServiceStartNotAllowedException')) {
                console.log('[SmartService] Inner loop terminating: Foreground Service access denied.');
                shouldRun = false;
            } else {
                console.error('[SmartService] Inner loop execution error:', innerErr);
            }
          }
        } catch (err) {
          console.error('[SmartService] Critical loop error:', err);
        }
        
        if (shouldRun) {
          await new Promise(r => setTimeout(r, 3000)); // 3-second polling loop
        }
      }
      
      console.log('[SmartService] Headless task loop finished.');
      // We broke the loop, stop the service and resolve the main Promise to let the OS clean up
      await notifee.stopForegroundService();
      // Crucial: The OS stops the service, but might leave the notification hanging as swipeable.
      // We must explicitly cancel the notification UI so it disappears.
      await notifee.cancelNotification(notification.id || FOREGROUND_NOTIFICATION_ID);
      resolve();
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
    const client = new SynologyClient(settings.nasUrl);
    client.setSession(session.sid);
    await client.discoverApis();
    
    const ds = new DownloadStation(client);
    const result = await ds.listTasks({ limit: -1, additional: ['transfer'] });
    currentTasks = result.tasks || [];
  } catch (apiErr) {
    console.error('[SmartService] Synology API Error during background check:', apiErr);
    if (isForeground && notificationId) {
      await updateNotification(notificationId, `API Error: ${apiErr.message}`);
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

  // Update Foreground Sticky Notification
  let notificationSuccess = true;
  if (isForeground && notificationId) {
    const bodyText = activeCount > 0 
      ? `${activeCount} active task${activeCount === 1 ? '' : 's'} (${formatSpeed(totalDlSpeed)})`
      : `0 active tasks (Grace period)`;
      
    try {
      await updateNotification(notificationId, bodyText);
    } catch (e) {
      if (e.message && e.message.includes('ForegroundServiceStartNotAllowedException')) {
        notificationSuccess = false;
      }
    }
  }

  return { 
    isActive: notificationSuccess && (totalDlSpeed > 0 || activeCount > 0), 
    newData: finished.length > 0 || errored.length > 0 
  };
}

// Helper to quickly update the sticky notification consistently
async function updateNotification(notificationId, bodyText) {
  try {
    const dataSyncType = AndroidForegroundServiceType?.DATA_SYNC ?? AndroidForegroundServiceType?.FOREGROUND_SERVICE_TYPE_DATA_SYNC ?? 1;
    await notifee.displayNotification({
      id: notificationId,
      title: 'Better DS Get',
      body: bodyText,
      android: {
        channelId: FOREGROUND_CHANNEL_ID,
        asForegroundService: true,
        ongoing: true, // Keep it sticky
        foregroundServiceTypes: [dataSyncType],
        pressAction: {
          id: 'default',
        },
      },
    });
  } catch (err) {
    if (err.message && err.message.includes('ForegroundServiceStartNotAllowedException')) {
      console.log('[SmartService] updateNotification skipped: Foreground Service locked/dismissed by OS or User.');
      // We throw so the loop can exit immediately since its UI is gone
      throw err; 
    } else {
      console.error('[SmartService] updateNotification error:', err);
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
