import React, { createContext, useContext, useState, useCallback, useRef, useEffect } from 'react';
import { useSynology } from './useSynology';

const SearchContext = createContext(undefined);

export const useSearch = () => {
    const context = useContext(SearchContext);
    if (!context) throw new Error('useSearch must be used within SearchProvider');
    return context;
};

export const SearchProvider = ({ children }) => {
    const { downloadStation } = useSynology();

    const [keyword, setKeyword] = useState('');
    const [results, setResults] = useState([]);
    const [searching, setSearching] = useState(false);
    const [isFinished, setIsFinished] = useState(true);
    const [taskId, setTaskId] = useState(null);
    const [modules, setModules] = useState([]);

    const pollInterval = useRef(null);

    const stopPolling = () => {
        if (pollInterval.current) {
            clearInterval(pollInterval.current);
            pollInterval.current = null;
        }
    };

    const cleanupSearch = async (tid) => {
        if (!tid) return;
        try {
            await downloadStation.btSearchClean(tid);
        } catch (e) {
            console.error('Failed to cleanup search:', e);
        }
    };

    const fetchResults = useCallback(async (tid) => {
        if (!downloadStation) return;
        try {
            const data = await downloadStation.btSearchList(tid, 0, 100);
            if (data && data.items) {
                setResults(data.items);
            }
            if (data.finished) {
                setIsFinished(true);
                stopPolling();
            }
        } catch (e) {
            console.error('Failed to fetch search results:', e);
            stopPolling();
            setIsFinished(true);
        }
    }, [downloadStation]);

    const startSearch = async (query) => {
        if (!query.trim() || !downloadStation) return;

        setKeyword(query);
        setSearching(true);
        setResults([]);
        setIsFinished(false);
        stopPolling();

        if (taskId) {
            await cleanupSearch(taskId);
        }

        try {
            const res = await downloadStation.btSearchStart(query.trim());
            if (res && res.taskid) {
                setTaskId(res.taskid);
                pollInterval.current = setInterval(() => fetchResults(res.taskid), 3000);
                fetchResults(res.taskid);
            } else {
                setSearching(false);
                setIsFinished(true);
                throw new Error('Failed to start search task');
            }
        } catch (e) {
            setSearching(false);
            setIsFinished(true);
            throw e;
        }
    };

    const cancelSearch = async () => {
        stopPolling();
        setIsFinished(true);
        setSearching(false);
        if (taskId) {
            await cleanupSearch(taskId);
            setTaskId(null);
        }
    };

    const clearSearch = () => {
        cancelSearch();
        setKeyword('');
        setResults([]);
        setTaskId(null);
    };

    // Load modules once
    useEffect(() => {
        if (downloadStation) {
            downloadStation.btSearchGetModules().then(data => {
                if (data && data.modules) {
                    setModules(data.modules);
                }
            }).catch(e => console.error('Failed to load search modules:', e));
        }
    }, [downloadStation]);

    // Cleanup on unmount
    useEffect(() => {
        return () => {
            stopPolling();
            // We don't cleanup the task on unmount because we want search to persist in background
            // while the app is running.
        };
    }, []);

    const value = {
        keyword,
        setKeyword,
        results,
        searching,
        isFinished,
        modules,
        startSearch,
        cancelSearch,
        clearSearch,
    };

    return (
        <SearchContext.Provider value={value}>
            {children}
        </SearchContext.Provider>
    );
};
