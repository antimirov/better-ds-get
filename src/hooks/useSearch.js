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
    const [engineStatus, setEngineStatus] = useState([]); // [{ module, status, items }]
    const [sortBy, setSortBy] = useState('seeds'); // 'seeds', 'size'
    const [sortOrder, setSortOrder] = useState('desc'); // 'asc', 'desc'
    const startTimeRef = useRef(null);

    // Two separate refs: one boolean "active?" flag, one timer handle.
    // This avoids the race condition where a scheduled timer fires after stopPolling().
    const isPollingActive = useRef(false);
    const pollTimerId = useRef(null);

    const stopPolling = () => {
        isPollingActive.current = false;
        if (pollTimerId.current) {
            clearTimeout(pollTimerId.current);
            pollTimerId.current = null;
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
        // Guard: bail immediately if polling was stopped while this call was in flight
        if (!isPollingActive.current || !downloadStation) return;

        try {
            // Fetch more results at once to improve per-engine counting accuracy
            const data = await downloadStation.btSearchList(tid, 0, 100);

            // Check again after the async gap — a new search might have started
            if (!isPollingActive.current) return;

            if (data?.items) {
                const duration = startTimeRef.current ? Math.round((Date.now() - startTimeRef.current) / 1000) : '?';
                console.log(`[useSearch] Task ${tid} (${duration}s): Found ${data.items.length} items (total: ${data.total}, finished: ${data.finished})`);
                setResults(data.items);
            }
            
            // console.log('[useSearch] Raw list response data:', JSON.stringify(data, null, 2));
            const statusData = data?.module || data?.modules || data?.status || data?.module_status;
            let statusArray = [];
            if (statusData) {
                console.log('[useSearch] Found statusData:', typeof statusData, statusData);
                if (Array.isArray(statusData)) {
                    statusArray = statusData;
                } else if (typeof statusData === 'object') {
                    // Handle object format { "moduleName": { stats... } } or { "moduleName": "status" }
                    statusArray = Object.entries(statusData).map(([key, val]) => {
                        if (typeof val === 'string') return { module: key, status: val };
                        return { module: key, ...val };
                    });
                }
            }
            console.log('[useSearch] Parsed statusArray:', statusArray);

            // If NAS didn't provide explicit module status, synthesize it from current engines + item counts
            setEngineStatus(prevStatus => {
                const nextStatus = prevStatus.length > 0 ? [...prevStatus] : modules.map(m => ({
                    module: m.id || m.name || m.displayname,
                    module_title: m.title || m.displayname || m.name || m.id,
                    status: 'searching',
                    items: 0
                }));

                // Update item counts from current results
                const counts = {};
                (data.items || []).forEach(item => {
                    // Item can have module_id or module
                    const mid = item.module_id || item.module;
                    if (mid) counts[mid] = (counts[mid] || 0) + 1;
                });

                const updatedStatus = nextStatus.map(e => {
                    const count = counts[e.module] || 0;
                    const isTaskFinished = data.finished === true;
                    const engineWasFinished = e.status === 'finished' || e.status === 'complete' || count > 0;
                    
                    return {
                        ...e,
                        items: Math.max(e.items || 0, count),
                        status: isTaskFinished || engineWasFinished ? 'finished' : 'searching'
                    };
                });
                
                const doneCount = updatedStatus.filter(e => e.status === 'finished').length;
                if (Object.keys(counts).length > 0) {
                    console.log('[useSearch] Result counts per engine:', JSON.stringify(counts));
                }
                console.log(`[useSearch] Engine Progress: ${doneCount}/${updatedStatus.length} done`);
                return updatedStatus;
            });

            if (data.finished) {
                isPollingActive.current = false;
                setIsFinished(true);
                return;
            }
        } catch (e) {
            if (!isPollingActive.current) return;

            // Network/timeout from NAS being busy querying trackers — retry
            if (String(e.message).toLowerCase().includes('time')) {
                console.warn('Search poll timeout (NAS busy), will retry...');
            } else {
                console.error('Failed to fetch search results:', e);
                isPollingActive.current = false;
                setIsFinished(true);
                return;
            }
        }

        // Schedule next poll only if still active
        if (isPollingActive.current) {
            const delay = (Date.now() - (startTimeRef.current || 0) < 10000) ? 1500 : 3000;
            pollTimerId.current = setTimeout(() => fetchResults(tid), delay);
        }
    }, [downloadStation, modules]);

    const startSearch = async (query) => {
        if (!query.trim() || !downloadStation) return;

        // Ensure modules are loaded if they haven't been yet
        let currentModules = modules;
        if (currentModules.length === 0) {
            try {
                const data = await downloadStation.btSearchGetModules();
                if (data?.modules) {
                    currentModules = data.modules;
                    setModules(data.modules);
                }
            } catch (e) {
                console.warn('Failed to load modules during startSearch:', e);
            }
        }

        // Capture old taskId before state updates clear it
        const oldTaskId = taskId;

        setKeyword(query);
        setSearching(true);
        setResults([]);
        
        // Reset engine status instead of clearing it, so UI shows "0/N done"
        if (currentModules.length > 0) {
            const enabledModules = currentModules.filter(m => m.enabled !== false);
            setEngineStatus(enabledModules.map(m => ({
                module: m.id || m.name || m.displayname,
                module_title: m.title || m.displayname || m.name || m.id,
                status: 'searching',
                items: 0
            })));
        } else {
            setEngineStatus([]);
        }

        setIsFinished(false);
        stopPolling(); // stops any running poll loop

        if (oldTaskId) {
            console.log(`[useSearch] Awaiting cleanup of old task: ${oldTaskId}`);
            await cleanupSearch(oldTaskId);
            console.log(`[useSearch] Cleanup done, starting fresh search.`);
        }

        try {
            console.log(`[useSearch] Starting new search for: "${query.trim()}"`);
            const startReqTime = Date.now();
            const res = await downloadStation.btSearchStart(query.trim());
            console.log(`[useSearch] Start response in ${Date.now() - startReqTime}ms:`, JSON.stringify(res));
            
            if (res && res.taskid) {
                startTimeRef.current = Date.now();
                if (res.taskid === oldTaskId) {
                    console.warn(`[useSearch] NAS reused taskid: ${res.taskid}`);
                }
                setTaskId(res.taskid);
                // Mark active BEFORE calling fetchResults so its guard passes
                isPollingActive.current = true;
                // Single entry point — fetchResults schedules all subsequent polls itself
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
        const currentTaskId = taskId;
        stopPolling();
        setIsFinished(true);
        setSearching(false);
        if (currentTaskId) {
            setTaskId(null);
            await cleanupSearch(currentTaskId);
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
                console.log('[useSearch] Loaded modules:', JSON.stringify(data?.modules, null, 2));
                if (data && data.modules) {
                    const enabledModules = data.modules.filter(m => m.enabled !== false);
                    setModules(data.modules);
                    // Initialize engine status so we don't stay on "Initializing..."
                    setEngineStatus(enabledModules.map(m => ({ 
                        module: m.id || m.name || m.displayname,
                        module_title: m.title || m.displayname || m.name || m.id,
                        status: 'waiting' 
                    })));
                }
            }).catch(e => console.error('Failed to load search modules:', e));
        }
    }, [downloadStation]);

    // Cleanup on unmount
    useEffect(() => {
        return () => {
            stopPolling();
            // Don't clean the task on unmount — search persists in background while app runs
        };
    }, []);

    const sortedResults = React.useMemo(() => {
        return [...results].sort((a, b) => {
            let valA, valB;
            if (sortBy === 'seeds') {
                valA = parseInt(a.seeds) || 0;
                valB = parseInt(b.seeds) || 0;
            } else if (sortBy === 'size') {
                valA = parseInt(a.size) || 0;
                valB = parseInt(b.size) || 0;
            } else {
                return 0;
            }

            if (sortOrder === 'asc') return valA - valB;
            return valB - valA;
        });
    }, [results, sortBy, sortOrder]);

    const value = {
        keyword,
        setKeyword,
        results: sortedResults, // Return sorted results
        searching,
        isFinished,
        engineStatus,
        modules,
        sortBy,
        setSortBy,
        sortOrder,
        setSortOrder,
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
