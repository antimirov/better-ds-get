import React, { createContext, useContext, useEffect, useState, useMemo } from 'react';
import { SessionManager, ConnectionState } from '../api/session-manager';

// Create a singleton instance
const sessionManager = new SessionManager();

const SynologyContext = createContext({
    sessionManager: null,
    connectionState: ConnectionState.DISCONNECTED,
    isConnected: false,
    error: null,
});

export const SynologyProvider = ({ children }) => {
    const [connectionState, setConnectionState] = useState(sessionManager.state);
    const [error, setError] = useState(null);
    const [isInitializing, setIsInitializing] = useState(true);

    useEffect(() => {
        // Attempt auto-login when the provider mounts
        const init = async () => {
            await sessionManager.tryRestore();
            setIsInitializing(false);
        };
        init();

        // Listen to connection state changes
        const handleStateChange = (event) => {
            setConnectionState(event.detail.state);
            setError(event.detail.error || null);
        };

        sessionManager.addEventListener('stateChange', handleStateChange);

        return () => {
            sessionManager.removeEventListener('stateChange', handleStateChange);
        };
    }, []);

    const value = useMemo(
        () => ({
            sessionManager,
            downloadStation: sessionManager.ds,
            connectionState,
            isConnected: connectionState === ConnectionState.CONNECTED,
            error,
            isInitializing,
        }),
        [connectionState, error, isInitializing, sessionManager]
    );

    return (
        <SynologyContext.Provider value={value}>
            {children}
        </SynologyContext.Provider>
    );
};

export const useSynology = () => useContext(SynologyContext);
