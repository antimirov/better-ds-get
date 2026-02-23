import React, { useEffect } from 'react';
import { View, Text, ActivityIndicator, StyleSheet, SafeAreaView, StatusBar, BackHandler } from 'react-native';
import { SynologyProvider, useSynology } from './src/hooks/useSynology';
import { NavigationProvider, useNavigation } from './src/hooks/useNavigation';
import LoginScreen from './src/screens/LoginScreen';
import TaskListScreen from './src/screens/TaskListScreen';
import TaskDetailScreen from './src/screens/TaskDetailScreen';

// --- App content that respects auth state ---
const MainApp = () => {
  const { isConnected, isInitializing } = useSynology();
  const { currentScreen, params, navigate } = useNavigation();

  // Sync navigation base state with auth state
  useEffect(() => {
    if (isConnected && currentScreen === 'Login') {
      navigate('TaskList');
    } else if (!isConnected && currentScreen !== 'Login') {
      navigate('Login');
    }
  }, [isConnected, currentScreen]);

  if (isInitializing) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color="#00A1E4" />
        <Text style={styles.loadingText}>Restoring session...</Text>
      </View>
    );
  }

  // Router logic
  if (!isConnected) return <LoginScreen />;
  if (currentScreen === 'TaskDetail') return <TaskDetailScreen route={{ params }} />;
  return <TaskListScreen />;
};

export default function App() {
  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor="#121212" />
      <SynologyProvider>
        <NavigationProvider>
          <MainApp />
        </NavigationProvider>
      </SynologyProvider>
    </SafeAreaView>
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
  }
});
