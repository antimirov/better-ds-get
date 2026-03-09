const { withAndroidManifest, withProjectBuildGradle, createRunOncePlugin } = require('@expo/config-plugins');

const withNotifeeManifest = (config) => {
  return withAndroidManifest(config, (config) => {
    const manifest = config.modResults.manifest;
    const mainApplication = manifest.application[0];
    
    // Ensure tools namespace exists for replacement
    if (!manifest.$['xmlns:tools']) {
      manifest.$['xmlns:tools'] = 'http://schemas.android.com/tools';
    }

    const services = mainApplication.service || [];
    let notifeeService = services.find(
      (s) => s.$['android:name'] === 'app.notifee.core.ForegroundService'
    );

    if (!notifeeService) {
      notifeeService = {
        $: {
          'android:name': 'app.notifee.core.ForegroundService',
          'android:exported': 'false',
        }
      };
      services.push(notifeeService);
      mainApplication.service = services;
    }

    // Force dataSync and completely overwrite
    notifeeService.$['android:foregroundServiceType'] = 'dataSync';
    notifeeService.$['tools:node'] = 'replace';
    delete notifeeService.$['tools:replace'];

    return config;
  });
};

const withNotifeeMavenPlugin = (config) => {
  return withProjectBuildGradle(config, (config) => {
    const buildGradle = config.modResults.contents;
    
    // Check if it already has been added
    if (!buildGradle.includes('@notifee/react-native/android/libs')) {
      const mavenSnippet = `
allprojects {
    repositories {
        maven {
            url "$rootDir/../node_modules/@notifee/react-native/android/libs"
        }
    }
}
`;
      config.modResults.contents = buildGradle + mavenSnippet;
    }
    
    return config;
  });
};

const withNotifeeForegroundService = (config) => {
  config = withNotifeeManifest(config);
  config = withNotifeeMavenPlugin(config);
  return config;
};

module.exports = createRunOncePlugin(
  withNotifeeForegroundService,
  'withNotifeeForegroundService',
  '1.0.0'
);
