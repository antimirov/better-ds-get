import React, { useEffect } from 'react';
import { View, Text, ActivityIndicator, StyleSheet, StatusBar, BackHandler, TouchableOpacity } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { SynologyProvider, useSynology } from './src/hooks/useSynology';
import { NavigationProvider, useNavigation } from './src/hooks/useNavigation';
import { SearchProvider } from './src/hooks/useSearch';
import LoginScreen from './src/screens/LoginScreen';
import TaskListScreen from './src/screens/TaskListScreen';
import TaskDetailScreen from './src/screens/TaskDetailScreen';
import SearchScreen from './src/screens/SearchScreen';
import { Feather } from '@expo/vector-icons';
import * as Linking from 'expo-linking';

// --- App content that respects auth state ---
const MainApp = () => {
  const { isConnected, isInitializing } = useSynology();
  const { currentScreen, params, navigate } = useNavigation();
  const [pendingUrl, setPendingUrl] = React.useState<string | null>(null);

  const handleIncomingUrl = (url: string | null) => {
    if (!url) return;
    console.log('App: Handling incoming URL:', url);

    // Clean up Expo Go wrapping (e.g., exp://.../--/magnet:...)
    let targetUrl = url;
    if (url.includes('/--/')) {
      targetUrl = url.split('/--/')[1];
    }

    // Decode double encoding which often happens with intents/deep links
    try {
      if (targetUrl.includes('%')) {
        targetUrl = decodeURIComponent(targetUrl);
        // If still contains double encoding artifacts like %25 (which is %)
        if (targetUrl.includes('%25') || targetUrl.includes('%3A')) {
          targetUrl = decodeURIComponent(targetUrl);
        }
      }
    } catch (e) {
      console.warn('App: Failed to decode targetUrl:', targetUrl);
    }

    setPendingUrl(targetUrl);
  };

  // Listen for incoming URLs (Deep Linking / Intents)
  useEffect(() => {
    const handleInitialUrl = async () => {
      const url = await Linking.getInitialURL();
      if (url) handleIncomingUrl(url);
    };
    handleInitialUrl();

    const subscription = Linking.addEventListener('url', (event) => {
      handleIncomingUrl(event.url);
    });

    return () => {
      subscription.remove();
    };
  }, []);

  // Sync navigation base state with auth state
  useEffect(() => {
    if (isConnected && currentScreen === 'Login') {
      navigate('TaskList', pendingUrl ? { autoAddUrl: pendingUrl } : undefined);
      if (pendingUrl) setPendingUrl(null);
    } else if (!isConnected && currentScreen !== 'Login') {
      navigate('Login');
    } else if (isConnected && pendingUrl) {
      // If already logged in and we receive a URL, force navigation to TaskList with the URL
      navigate('TaskList', { autoAddUrl: pendingUrl });
      setPendingUrl(null);
    }
  }, [isConnected, currentScreen, pendingUrl]);

  if (isInitializing) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color="#00A1E4" />
        <Text style={styles.loadingText}>Restoring session...</Text>
      </View>
    );
  }

  // Router logic
  const renderScreen = () => {
    if (!isConnected) return <LoginScreen />;
    if (currentScreen === 'TaskDetail') return <TaskDetailScreen route={{ params }} />;
    if (currentScreen === 'Search') return <SearchScreen />;
    // @ts-ignore - TaskListScreen is JS and TS might not see the route prop
    return <TaskListScreen route={{ params }} />;
  };

  return (
    <View style={{ flex: 1 }}>
      {renderScreen()}
      {isConnected && currentScreen !== 'TaskDetail' && (
        <View style={styles.tabBar}>
          <TouchableOpacity
            style={styles.tabItem}
            onPress={() => navigate('TaskList')}
          >
            <Feather
              name="list"
              size={24}
              color={currentScreen === 'TaskList' ? '#00A1E4' : '#888'}
            />
            <Text style={[styles.tabText, currentScreen === 'TaskList' && styles.activeTabText]}>Tasks</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.tabItem}
            onPress={() => navigate('Search')}
          >
            <Feather
              name="search"
              size={24}
              color={currentScreen === 'Search' ? '#00A1E4' : '#888'}
            />
            <Text style={[styles.tabText, currentScreen === 'Search' && styles.activeTabText]}>Search</Text>
          </TouchableOpacity>
        </View>
      )}
    </View>
  );
};

export default function App() {
  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.container} edges={['top', 'right', 'left']}>
        <StatusBar barStyle="light-content" backgroundColor="#121212" />
        <SynologyProvider>
          <SearchProvider>
            <NavigationProvider>
              <MainApp />
            </NavigationProvider>
          </SearchProvider>
        </SynologyProvider>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#121212',
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#121212',
  },
  loadingText: {
    color: '#888',
    marginTop: 16,
    fontSize: 16,
  },
  tabBar: {
    flexDirection: 'row',
    height: 64,
    backgroundColor: '#1E1E1E',
    borderTopWidth: 1,
    borderTopColor: '#333',
    paddingBottom: 8, // space for home indicator
  },
  tabItem: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  tabText: {
    color: '#888',
    fontSize: 12,
    marginTop: 4,
  },
  activeTabText: {
    color: '#00A1E4',
  }
});
