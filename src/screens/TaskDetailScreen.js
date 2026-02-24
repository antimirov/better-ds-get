import React, { useEffect, useState, useCallback } from 'react';
import { View, Text, StyleSheet, FlatList, ActivityIndicator, Switch, TouchableOpacity, ScrollView, Alert } from 'react-native';
import { Feather } from '@expo/vector-icons';
import { useSynology } from '../hooks/useSynology';
import { useNavigation } from '../hooks/useNavigation';
import { getIpCountry, getFlagEmoji } from '../utils/geolocation';

export default function TaskDetailScreen({ route }) {
    // If route is passed directly (from App.tsx router), use it; 
    // otherwise fallback to our context if needed.
    const { task } = route.params;
    const { downloadStation } = useSynology();
    const { goBack } = useNavigation();

    const [files, setFiles] = useState([]);
    const [taskInfo, setTaskInfo] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [activeTab, setActiveTab] = useState('General'); // General, Transfer, Tracker, Peers, Files
    const [isActionLoading, setIsActionLoading] = useState(false);
    const [peerGeo, setPeerGeo] = useState({}); // { [ip]: { countryCode, flag } }
    const [trackerGeo, setTrackerGeo] = useState({}); // { [host]: { countryCode, flag } }

    useEffect(() => {
        loadData();

        // Background polling for stats
        const interval = setInterval(() => {
            loadData(true);
        }, 5000);

        return () => clearInterval(interval);
    }, [task.id]);

    useEffect(() => {
        if (activeTab === 'Peers' && taskInfo?.peersArray?.length > 0) {
            updatePeerGeo();
        } else if (activeTab === 'Tracker' && taskInfo?.trackers?.length > 0) {
            updateTrackerGeo();
        }
    }, [activeTab, taskInfo?.peersArray, taskInfo?.trackers]);

    const updateTrackerGeo = async () => {
        const trackers = taskInfo.trackers;
        const newGeo = { ...trackerGeo };
        let changed = false;

        for (const tracker of trackers) {
            try {
                const url = new URL(tracker.url);
                const host = url.hostname;
                if (host && !newGeo[host]) {
                    const info = await getIpCountry(host);
                    if (info) {
                        newGeo[host] = {
                            countryCode: info.countryCode,
                            flag: getFlagEmoji(info.countryCode)
                        };
                        changed = true;
                    }
                }
            } catch (e) {
                // Ignore invalid URLs
            }
        }

        if (changed) {
            setTrackerGeo(newGeo);
        }
    };

    const updatePeerGeo = async () => {
        const peers = taskInfo.peersArray;
        const newGeo = { ...peerGeo };
        let changed = false;

        for (const peer of peers) {
            if (peer.address && !newGeo[peer.address]) {
                const info = await getIpCountry(peer.address);
                if (info) {
                    newGeo[peer.address] = {
                        countryCode: info.countryCode,
                        flag: getFlagEmoji(info.countryCode),
                        country: info.country
                    };
                    changed = true;
                }
            }
        }

        if (changed) {
            setPeerGeo(newGeo);
        }
    };

    const loadData = async (silent = false) => {
        try {
            if (!silent) setLoading(true);
            setError(null);

            // Execute both API calls concurrently
            const [infoData, filesData] = await Promise.allSettled([
                downloadStation.getTaskInfo([task.id]),
                downloadStation.getTaskFiles(task.id)
            ]);

            if (infoData.status === 'fulfilled' && infoData.value && infoData.value.length > 0) {
                setTaskInfo(infoData.value[0]);
            }

            if (filesData.status === 'fulfilled') {
                const data = filesData.value;
                if (data && data.items) {
                    setFiles(data.items);
                } else {
                    setFiles([]);
                }
            } else if (filesData.reason) {
                const e = filesData.reason;
                if (e.code === 1913) {
                    setError("Task files cannot be viewed. The task is paused, inactive, or not a BitTorrent task.");
                } else {
                    setError(e.message || 'Failed to load files');
                }
            }

        } catch (e) {
            if (!silent) setError(e.message || 'Failed to load task details');
        } finally {
            if (!silent) setLoading(false);
        }
    };

    const handleToggleWanted = async (file, currentValue) => {
        // Optimistic update
        const newValue = !currentValue;
        setFiles(prev => prev.map(f => f.index === file.index ? { ...f, wanted: newValue } : f));

        try {
            await downloadStation.setTaskFileWanted(task.id, [file.index], newValue);
        } catch (e) {
            console.error('Failed to change file priority:', e);
            // Revert on failure
            setFiles(prev => prev.map(f => f.index === file.index ? { ...f, wanted: currentValue } : f));
            alert('Failed to update file priority.');
        }
    };

    const renderFileItem = ({ item }) => {
        const isWanted = item.wanted;

        // Convert bytes to a readable format
        const formatBytes = (bytes) => {
            if (bytes === 0) return '0 B';
            const k = 1024;
            const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
            const i = Math.floor(Math.log(bytes) / Math.log(k));
            return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
        };

        const downloadedStr = formatBytes(item.size_downloaded);
        const totalStr = formatBytes(item.size);
        const progress = item.size > 0 ? (item.size_downloaded / item.size) * 100 : 0;

        return (
            <View style={styles.fileItem}>
                <View style={styles.fileInfo}>
                    <Text style={styles.fileName} numberOfLines={2} ellipsizeMode="middle">
                        {item.name}
                    </Text>
                    <Text style={styles.fileStats}>
                        {downloadedStr} / {totalStr} ({progress.toFixed(1)}%)
                    </Text>
                </View>
                <Switch
                    value={isWanted}
                    onValueChange={() => handleToggleWanted(item, isWanted)}
                    thumbColor={isWanted ? "#00A1E4" : "#f4f3f4"}
                    trackColor={{ false: "#767577", true: "#81b0ff" }}
                    style={styles.switch}
                />
            </View>
        );
    };

    const renderTabs = () => {
        const tabs = ['General', 'Transfer', 'Tracker', 'Peers', 'File'];
        return (
            <View style={styles.tabContainer}>
                {tabs.map(tab => (
                    <TouchableOpacity
                        key={tab}
                        style={[styles.tab, activeTab === tab && styles.activeTab]}
                        onPress={() => setActiveTab(tab)}
                    >
                        <Text style={[styles.tabText, activeTab === tab && styles.activeTabText]}>
                            {tab}
                        </Text>
                    </TouchableOpacity>
                ))}
            </View>
        );
    };

    const formatBytes = (bytes) => {
        if (!bytes || bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    };

    const formatEta = (seconds) => {
        if (!isFinite(seconds) || seconds < 0) return 'Unknown';
        if (seconds === 0) return 'Finished';
        const d = Math.floor(seconds / (3600 * 24));
        const h = Math.floor(seconds % (3600 * 24) / 3600);
        const m = Math.floor(seconds % 3600 / 60);
        const s = Math.floor(seconds % 60);

        const dDisplay = d > 0 ? d + "d " : "";
        const hDisplay = h > 0 ? h + "h " : "";
        const mDisplay = m > 0 ? m + "m " : "";
        const sDisplay = s > 0 ? s + "s" : "";
        return (dDisplay + hDisplay + mDisplay + sDisplay).trim() || '0s';
    };

    const handlePauseTask = async () => {
        setIsActionLoading(true);
        try {
            await downloadStation.pauseTasks([task.id]);
            loadData();
        } catch (e) {
            Alert.alert('Error', 'Failed to pause task: ' + e.message);
        } finally {
            setIsActionLoading(false);
        }
    };

    const handleResumeTask = async () => {
        setIsActionLoading(true);
        try {
            await downloadStation.resumeTasks([task.id]);
            loadData();
        } catch (e) {
            Alert.alert('Error', 'Failed to resume task: ' + e.message);
        } finally {
            setIsActionLoading(false);
        }
    };

    const handleDeleteTask = () => {
        Alert.alert('Remove Task', `Are you sure you want to remove "${task.title || taskInfo?.title}"?`, [
            { text: 'Cancel', style: 'cancel' },
            {
                text: 'Remove',
                style: 'destructive',
                onPress: async () => {
                    setIsActionLoading(true);
                    try {
                        await downloadStation.deleteTasks([task.id], false);
                        goBack();
                    } catch (e) {
                        Alert.alert('Error', 'Failed to remove task: ' + e.message);
                        setIsActionLoading(false);
                    }
                }
            },
            {
                text: 'Remove & Delete Data',
                style: 'destructive',
                onPress: async () => {
                    setIsActionLoading(true);
                    try {
                        await downloadStation.deleteTasks([task.id], true);
                        goBack();
                    } catch (e) {
                        Alert.alert('Error', 'Failed to remove task and data: ' + e.message);
                        setIsActionLoading(false);
                    }
                }
            }
        ]);
    };

    const renderActionBar = (info) => {
        const isPaused = info.status === 'paused' || info.status === 'error';
        const isFinished = info.status === 'finished';

        return (
            <View style={styles.actionBar}>
                {isPaused ? (
                    <TouchableOpacity
                        style={[styles.actionButton, styles.resumeButton, isActionLoading && styles.buttonDisabled]}
                        onPress={handleResumeTask}
                        disabled={isActionLoading}
                    >
                        <Feather name="play" size={20} color="#FFF" />
                        <Text style={styles.actionButtonText}>Resume</Text>
                    </TouchableOpacity>
                ) : !isFinished && (
                    <TouchableOpacity
                        style={[styles.actionButton, styles.pauseButton, isActionLoading && styles.buttonDisabled]}
                        onPress={handlePauseTask}
                        disabled={isActionLoading}
                    >
                        <Feather name="pause" size={20} color="#FFF" />
                        <Text style={styles.actionButtonText}>Pause</Text>
                    </TouchableOpacity>
                )}
                <TouchableOpacity
                    style={[styles.actionButton, styles.deleteButton, isActionLoading && styles.buttonDisabled]}
                    onPress={handleDeleteTask}
                    disabled={isActionLoading}
                >
                    <Feather name="trash-2" size={20} color="#FFF" />
                    <Text style={styles.actionButtonText}>Delete</Text>
                </TouchableOpacity>
            </View>
        );
    };

    const renderGeneralTab = (info) => (
        <View style={styles.card}>
            <View style={styles.infoRow}><Text style={styles.infoLabel}>File name:</Text><Text style={styles.infoValue}>{info.title}</Text></View>
            <View style={styles.infoRow}><Text style={styles.infoLabel}>Destination:</Text><Text style={styles.infoValue}>{info.destination}</Text></View>
            <View style={styles.infoRow}><Text style={styles.infoLabel}>File size:</Text><Text style={styles.infoValue}>{formatBytes(info.size)}</Text></View>
            <View style={styles.infoRow}><Text style={styles.infoLabel}>URL:</Text><Text style={styles.infoValue} numberOfLines={1} ellipsizeMode="tail">{info.uri}</Text></View>
            <View style={styles.infoRow}><Text style={styles.infoLabel}>Created time:</Text><Text style={styles.infoValue}>{info.createTime ? new Date(info.createTime * 1000).toLocaleString() : 'Unknown'}</Text></View>
            <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>Estimated Wait Time:</Text>
                <Text style={styles.infoValue}>
                    {info.status === 'downloading' ? 'Not available' : formatEta(info.waitingSeconds)}
                </Text>
            </View>
            <View style={styles.infoRow}><Text style={styles.infoLabel}>Completed Time:</Text><Text style={styles.infoValue}>{info.completedTime ? new Date(info.completedTime * 1000).toLocaleString() : 'Not available'}</Text></View>
        </View>
    );

    const renderTransferTab = (info) => {
        const progress = info.size > 0 ? (info.sizeDownloaded / info.size) * 100 : 0;

        // Piece layout
        const pieceSizeStr = info.pieceLength ? ` x ${formatBytes(info.pieceLength)}` : '';
        const pieceStr = `${info.downloadedPieces} / ${info.totalPieces || 'Not available'}${pieceSizeStr}`;

        // Peers layout
        let peersStr;
        if (info.status === 'paused' || info.status === 'finished') {
            // Unconnected peers are shown when paused/finished in DSM
            const seeds = Math.max(info.connectedSeeder, info.unconnectedSeeder);
            const peers = Math.max(info.connectedLeecher, info.unconnectedPeers);
            peersStr = `${seeds} seeds / ${peers} peers`;
        } else {
            peersStr = `${info.connectedSeeder} seeds / ${info.connectedLeecher} leechers`;
        }

        let etaStr = '-';
        if (info.status === 'downloading') {
            if (info.eta > 0) {
                etaStr = formatEta(info.eta);
            } else if (info.speedDownload > 0 && info.size > 0) {
                const remainingBytes = info.size - info.sizeDownloaded;
                const seconds = remainingBytes / info.speedDownload;
                etaStr = formatEta(seconds);
            } else {
                etaStr = 'Unknown';
            }
        }

        return (
            <ScrollView style={styles.tabContent}>
                <View style={styles.card}>
                    <View style={styles.infoRow}><Text style={styles.infoLabel}>Status:</Text><Text style={styles.infoValue}>{info.status}</Text></View>
                    <View style={styles.infoRow}><Text style={styles.infoLabel}>Transferred (UL/DL):</Text><Text style={styles.infoValue}>{formatBytes(info.sizeUploaded)} / {formatBytes(info.sizeDownloaded)} ({progress.toFixed(1)}%)</Text></View>
                    <View style={styles.infoRow}><Text style={styles.infoLabel}>Progress:</Text><Text style={styles.infoValue}>{progress.toFixed(1)}%</Text></View>
                    <View style={styles.infoRow}><Text style={styles.infoLabel}>Speed (UL/DL):</Text><Text style={styles.infoValue}>{formatBytes(info.speedUpload)}/s / {formatBytes(info.speedDownload)}/s</Text></View>
                    <View style={styles.infoRow}><Text style={styles.infoLabel}>Connected Peers:</Text><Text style={styles.infoValue}>{peersStr}</Text></View>
                    <View style={styles.infoRow}><Text style={styles.infoLabel}>Total Peers:</Text><Text style={styles.infoValue}>{info.totalPeers || 'Not available'}</Text></View>
                    <View style={styles.infoRow}><Text style={styles.infoLabel}>Downloaded Pieces:</Text><Text style={styles.infoValue}>{pieceStr}</Text></View>
                    {info.status === 'downloading' && (
                        <View style={styles.infoRow}><Text style={styles.infoLabel}>Time Left:</Text><Text style={styles.infoValue}>{etaStr}</Text></View>
                    )}
                    <View style={styles.infoRow}><Text style={styles.infoLabel}>Start Time:</Text><Text style={styles.infoValue}>{info.startedTime ? new Date(info.startedTime * 1000).toLocaleString() : 'Unknown'}</Text></View>
                </View>
            </ScrollView>
        );
    };

    const renderTrackerTab = (info) => {
        if (!info.trackers || info.trackers.length === 0) {
            return <Text style={styles.emptyText}>No tracker information available.</Text>;
        }

        const sortedTrackers = [...info.trackers].sort((a, b) => {
            const aS = (a.status || '').toLowerCase();
            const bS = (b.status || '').toLowerCase();
            const aW = aS.includes('success') || aS.includes('working') || aS.includes('alive');
            const bW = bS.includes('success') || bS.includes('working') || bS.includes('alive');
            if (aW && !bW) return -1;
            if (!aW && bW) return 1;
            return (b.seeds || 0) - (a.seeds || 0);
        });

        return (
            <View style={styles.card}>
                {sortedTrackers.map((tracker, index) => {
                    let host = '';
                    let protocol = '';
                    let port = '';
                    try {
                        const url = new URL(tracker.url);
                        host = url.hostname;
                        protocol = url.protocol.replace(':', '').toUpperCase();
                        port = url.port || (protocol === 'HTTPS' ? '443' : protocol === 'HTTP' ? '80' : '');
                    } catch (e) {
                        host = tracker.url;
                    }
                    const geo = trackerGeo[host];

                    return (
                        <View key={index} style={styles.listItem}>
                            <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 4 }}>
                                {geo && <Text style={{ fontSize: 14, marginRight: 6 }}>{geo.flag}</Text>}
                                <Text style={[styles.listItemTitle, { flex: 1 }]} numberOfLines={1} ellipsizeMode="tail">
                                    {host}{port ? `:${port}` : ''}
                                </Text>
                                <View style={[styles.protocolBadge,
                                protocol === 'UDP' ? styles.udpBadge :
                                    protocol === 'HTTPS' ? styles.httpsBadge : styles.httpBadge
                                ]}>
                                    <Text style={styles.protocolText}>{protocol}</Text>
                                </View>
                            </View>
                            <Text style={styles.listItemSub}>
                                Status: <Text style={tracker.status?.toLowerCase().includes('success') ? { color: '#4CAF50' } : {}}>{tracker.status}</Text>
                                {'  '}|{'  '}<Feather name="arrow-up" size={10} color="#4CAF50" /> {tracker.seeds}
                                {'  '}|{'  '}<Feather name="arrow-down" size={10} color="#FF9800" /> {tracker.peers}
                            </Text>
                        </View>
                    );
                })}
            </View>
        );
    };

    const renderPeersTab = (info) => {
        if (!info.peersArray || info.peersArray.length === 0) {
            return <Text style={styles.emptyText}>No peer information available.</Text>;
        }
        return (
            <View style={styles.card}>
                {info.peersArray.map((peer, index) => {
                    const geo = peerGeo[peer.address];
                    return (
                        <View key={index} style={styles.listItem}>
                            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                                {geo && <Text style={{ fontSize: 16, marginRight: 8 }}>{geo.flag}</Text>}
                                <Text style={styles.listItemTitle}>{peer.address}</Text>
                            </View>
                            <Text style={styles.listItemSub}>
                                Client: {peer.agent} | DL: {formatBytes(peer.speed_download)}/s | UL: {formatBytes(peer.speed_upload)}/s
                            </Text>
                        </View>
                    );
                })}
            </View>
        );
    };

    const renderFilesTab = () => {
        if (error) {
            return <View style={styles.errorContainer}><Text style={styles.errorText}>{error}</Text></View>;
        }
        if (files.length === 0) {
            return <View style={styles.emptyContainer}><Text style={styles.emptyText}>No files found in this task.</Text></View>;
        }
        return (
            <FlatList
                data={files}
                keyExtractor={(item, index) => item.index ? item.index.toString() : index.toString()}
                renderItem={renderFileItem}
                contentContainerStyle={styles.listContainer}
                ItemSeparatorComponent={() => <View style={styles.separator} />}
            />
        );
    };

    const renderContent = () => {
        if (loading) {
            return (
                <View style={styles.centered}>
                    <ActivityIndicator size="large" color="#00A1E4" />
                </View>
            );
        }

        const info = taskInfo || task;

        switch (activeTab) {
            case 'General': return renderGeneralTab(info);
            case 'Transfer': return renderTransferTab(info);
            case 'Tracker': return renderTrackerTab(info);
            case 'Peers': return renderPeersTab(info);
            case 'File': return renderFilesTab();
            default: return null;
        }
    };

    return (
        <View style={styles.container}>
            <View style={styles.header}>
                <TouchableOpacity onPress={goBack} style={styles.backButton}>
                    <Text style={styles.backButtonText}>← Back</Text>
                </TouchableOpacity>
                <View style={styles.headerTitleContainer}>
                    <Text style={styles.headerTitle} numberOfLines={1} ellipsizeMode="tail">
                        {task.title}
                    </Text>
                </View>
            </View>
            {renderActionBar(taskInfo || task)}
            {renderTabs()}
            <View style={styles.contentContainer}>
                {renderContent()}
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: '#121212',
    },
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 20,
        paddingTop: 12, // Reduced from 60
        backgroundColor: '#1E1E1E',
        borderBottomWidth: 1,
        borderBottomColor: '#333',
    },
    backButton: {
        marginRight: 12,
        padding: 8,
    },
    backButtonText: {
        color: '#00A1E4',
        fontSize: 16,
        fontWeight: 'bold',
    },
    headerTitleContainer: {
        flex: 1,
        justifyContent: 'center',
    },
    headerTitle: {
        color: '#fff',
        fontSize: 16,
        fontWeight: 'bold',
    },
    tabContainer: {
        flexDirection: 'row',
        backgroundColor: '#1E1E1E',
        borderBottomWidth: 1,
        borderBottomColor: '#333',
    },
    tab: {
        flex: 1,
        paddingVertical: 12,
        alignItems: 'center',
    },
    activeTab: {
        borderBottomWidth: 2,
        borderBottomColor: '#00A1E4',
    },
    tabText: {
        color: '#888',
        fontSize: 14,
        fontWeight: 'bold',
    },
    activeTabText: {
        color: '#00A1E4',
    },
    contentContainer: {
        flex: 1,
    },
    card: {
        backgroundColor: '#1E1E1E',
        margin: 16,
        borderRadius: 8,
        padding: 16,
    },
    listItem: {
        paddingVertical: 8,
        borderBottomWidth: 1,
        borderBottomColor: '#333',
    },
    listItemTitle: {
        color: '#E0E0E0',
        fontSize: 14,
        marginBottom: 4,
    },
    listItemSub: {
        color: '#888',
        fontSize: 12,
    },
    centered: {
        flex: 1,
        justifyContent: 'center',
        alignItems: 'center',
        padding: 20,
    },
    errorContainer: {
        padding: 16,
        backgroundColor: 'rgba(255, 107, 107, 0.1)',
        borderRadius: 8,
        marginVertical: 16,
    },
    errorText: {
        color: '#ff6b6b',
        fontSize: 14,
        textAlign: 'center',
        lineHeight: 20,
    },
    emptyContainer: {
        padding: 32,
        alignItems: 'center',
    },
    emptyText: {
        color: '#888',
        fontSize: 16,
    },
    listContainer: {
        padding: 16,
    },
    infoRow: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        marginBottom: 8,
    },
    infoLabel: {
        color: '#888',
        fontSize: 14,
        flex: 1,
    },
    infoValue: {
        color: '#E0E0E0',
        fontSize: 14,
        flex: 2,
        textAlign: 'right',
    },
    fileItem: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingVertical: 12,
    },
    fileInfo: {
        flex: 1,
        marginRight: 16,
    },
    fileName: {
        color: '#E0E0E0',
        fontSize: 14,
        marginBottom: 4,
    },
    fileStats: {
        color: '#888',
        fontSize: 12,
    },
    separator: {
        height: 1,
        backgroundColor: '#333',
    },
    switch: {
        transform: [{ scaleX: 0.9 }, { scaleY: 0.9 }],
    },
    actionBar: {
        flexDirection: 'row',
        padding: 16,
        backgroundColor: '#1E1E1E',
        borderBottomWidth: 1,
        borderBottomColor: '#333',
        justifyContent: 'space-between',
    },
    actionButton: {
        flex: 1,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        paddingVertical: 12,
        borderRadius: 8,
        marginHorizontal: 4,
    },
    resumeButton: {
        backgroundColor: '#4CAF50',
    },
    pauseButton: {
        backgroundColor: '#FF9800',
    },
    deleteButton: {
        backgroundColor: '#FF6B6B',
    },
    actionButtonText: {
        color: '#FFF',
        fontSize: 16,
        fontWeight: 'bold',
        marginLeft: 8,
    },
    buttonDisabled: {
        opacity: 0.5,
    },
    protocolBadge: {
        paddingHorizontal: 6,
        paddingVertical: 2,
        borderRadius: 4,
        marginLeft: 8,
    },
    udpBadge: {
        backgroundColor: '#673AB7',
    },
    httpsBadge: {
        backgroundColor: '#4CAF50',
    },
    httpBadge: {
        backgroundColor: '#757575',
    },
    protocolText: {
        color: '#FFF',
        fontSize: 10,
        fontWeight: 'bold',
    }
});
