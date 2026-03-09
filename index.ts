import notifee from '@notifee/react-native';
import { registerRootComponent } from 'expo';

import App from './App';
import { notifeeForegroundServiceRunner } from './src/services/BackgroundTasks';

// Register background handlers early in the root context
notifee.onBackgroundEvent(async ({ type, detail }) => {
  if (type === 0 /* EventType.DISMISSED */) {
    console.log('[index] Notification dismissed by user in background.');
    await notifee.stopForegroundService();
  }
});

// Register the headless foreground service worker
notifee.registerForegroundService(notifeeForegroundServiceRunner);

// registerRootComponent calls AppRegistry.registerComponent('main', () => App);
// It also ensures that whether you load the app in Expo Go or in a native build,
// the environment is set up appropriately
registerRootComponent(App);
