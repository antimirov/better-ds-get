import * as Notifications from 'expo-notifications';
import * as TaskManager from 'expo-task-manager';
import * as BackgroundFetch from 'expo-background-fetch';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { DownloadStation } from '../api/download-station';
import { SynologyClient } from '../api/synology-client';

const BACKGROUND_FETCH_TASK = 'BACKGROUND_FETCH_TASK';

// Configure notification behavior
Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowAlert: true,
    shouldPlaySound: true,
    shouldSetBadge: true,
  }),
});

/**
 * Register the background task
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

/**
 * Define the task logic
 */
TaskManager.defineTask(BACKGROUND_FETCH_TASK, async () => {
  console.log('[BackgroundFetch] Running task...');

  try {
    const sessionStr = await AsyncStorage.getItem('synology_session');
    const settingsStr = await AsyncStorage.getItem('synology_settings');

    if (!sessionStr || !settingsStr) {
      console.log('[BackgroundFetch] No session or settings found, skipping.');
      return BackgroundFetch.BackgroundFetchResult.NoData;
    }

    const session = JSON.parse(sessionStr);
    const settings = JSON.parse(settingsStr);

    if (!session.sid || !settings.nasUrl) {
      return BackgroundFetch.BackgroundFetchResult.NoData;
    }

    const client = new SynologyClient(settings.nasUrl);
    client.setSession(session.sid);
    
    const ds = new DownloadStation(client);
    const { tasks: currentTasks } = await ds.listTasks({ limit: -1, additional: [] });

    const lastTasksStr = await AsyncStorage.getItem('last_known_tasks');
    const lastTasks = lastTasksStr ? JSON.parse(lastTasksStr) : {};

    const finished = [];
    const errored = [];
    const updatedTasks = {};

    for (const task of currentTasks) {
      const prev = lastTasks[task.id];
      updatedTasks[task.id] = { status: task.status, title: task.title };

      if (prev) {
        if (task.status === 'finished' && prev.status !== 'finished') {
          finished.push(task.title);
        } else if (task.status === 'error' && prev.status !== 'error') {
          errored.push(task.title);
        }
      }
    }

    await AsyncStorage.setItem('last_known_tasks', JSON.stringify(updatedTasks));

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

    return BackgroundFetch.BackgroundFetchResult.NewData;
  } catch (error) {
    console.error('[BackgroundFetch] Error:', error);
    return BackgroundFetch.BackgroundFetchResult.Failed;
  }
});
