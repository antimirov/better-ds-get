import React, { createContext, useContext, useState, ReactNode, useEffect } from 'react';
import { BackHandler } from 'react-native';

export type NavigationContextType = {
    currentScreen: 'Login' | 'TaskList' | 'TaskDetail' | 'Search';
    params: any;
    navigate: (screen: 'Login' | 'TaskList' | 'TaskDetail' | 'Search', params?: any) => void;
    goBack: () => void;
};

const NavigationContext = createContext<NavigationContextType | undefined>(undefined);

export const useNavigation = () => {
    const context = useContext(NavigationContext);
    if (!context) throw new Error('useNavigation must be used within NavigationProvider');
    return context;
};

export const NavigationProvider = ({ children }: { children: ReactNode }) => {
    const [history, setHistory] = useState<{ screen: 'Login' | 'TaskList' | 'TaskDetail' | 'Search', params: any }[]>([
        { screen: 'Login', params: null }
    ]);

    const current = history[history.length - 1];

    const navigate = (screen: 'Login' | 'TaskList' | 'TaskDetail' | 'Search', params?: any) => {
        setHistory(prev => [...prev, { screen, params }]);
    };

    const goBack = () => {
        setHistory(prev => (prev.length > 1 ? prev.slice(0, -1) : prev));
    };

    // Handle hardware back button on Android
    useEffect(() => {
        const onBackPress = () => {
            if (history.length > 1) {
                goBack();
                return true;
            } else {
                // We are at the root screen (TaskList or Login)
                BackHandler.exitApp();
                return true;
            }
        };
        const subscription = BackHandler.addEventListener('hardwareBackPress', onBackPress);
        return () => subscription.remove();
    }, [history]);

    return (
        <NavigationContext.Provider value={{ currentScreen: current.screen, params: current.params, navigate, goBack }}>
            {children}
        </NavigationContext.Provider>
    );
};
