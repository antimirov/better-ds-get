import React, { useEffect, useState } from 'react';
import { View, Text, StyleSheet, FlatList, ActivityIndicator, Switch, TouchableOpacity, ScrollView } from 'react-native';
import { useSynology } from '../hooks/useSynology';
import { useNavigation } from '../hooks/useNavigation';

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

    useEffect(() => {
        loadData();
    }, [task.id]);

    const loadData = async () => {
        try {
            setLoading(true);
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
            setError(e.message || 'Failed to load task details');
        } finally {
            setLoading(false);
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

    const renderGeneralTab = (info) => (
        <View style={styles.card}>
            <View style={styles.infoRow}><Text style={styles.infoLabel}>File name:</Text><Text style={styles.infoValue}>{info.title}</Text></View>
            <View style={styles.infoRow}><Text style={styles.infoLabel}>Destination:</Text><Text style={styles.infoValue}>{info.destination}</Text></View>
            <View style={styles.infoRow}><Text style={styles.infoLabel}>File size:</Text><Text style={styles.infoValue}>{formatBytes(info.size)}</Text></View>
            <View style={styles.infoRow}><Text style={styles.infoLabel}>URL:</Text><Text style={styles.infoValue} numberOfLines={1} ellipsizeMode="tail">{info.uri}</Text></View>
            <View style={styles.infoRow}><Text style={styles.infoLabel}>Created time:</Text><Text style={styles.infoValue}>{info.createTime ? new Date(info.createTime * 1000).toLocaleString() : 'Unknown'}</Text></View>
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
            if (info.speedDownload > 0 && info.size > 0) {
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
                    {info.status === 'downloading' && (
                        <View style={styles.infoRow}><Text style={styles.infoLabel}>ETA:</Text><Text style={styles.infoValue}>{etaStr}</Text></View>
                    )}
                    <View style={styles.infoRow}><Text style={styles.infoLabel}>Speed (UL/DL):</Text><Text style={styles.infoValue}>{formatBytes(info.speedUpload)}/s / {formatBytes(info.speedDownload)}/s</Text></View>
                    <View style={styles.infoRow}><Text style={styles.infoLabel}>Connected Peers:</Text><Text style={styles.infoValue}>{peersStr}</Text></View>
                    <View style={styles.infoRow}><Text style={styles.infoLabel}>Total Peers:</Text><Text style={styles.infoValue}>{info.totalPeers || 'Not available'}</Text></View>
                    <View style={styles.infoRow}><Text style={styles.infoLabel}>Downloaded Pieces:</Text><Text style={styles.infoValue}>{pieceStr}</Text></View>
                    <View style={styles.infoRow}><Text style={styles.infoLabel}>Start Time:</Text><Text style={styles.infoValue}>{info.startedTime ? new Date(info.startedTime * 1000).toLocaleString() : 'Unknown'}</Text></View>
                </View>
            </ScrollView>
        );
    };

    const renderTrackerTab = (info) => {
        if (!info.trackers || info.trackers.length === 0) {
            return <Text style={styles.emptyText}>No tracker information available.</Text>;
        }
        return (
            <View style={styles.card}>
                {info.trackers.map((tracker, index) => (
                    <View key={index} style={styles.listItem}>
                        <Text style={styles.listItemTitle} numberOfLines={1} ellipsizeMode="tail">{tracker.url}</Text>
                        <Text style={styles.listItemSub}>Status: {tracker.status} | Seeds: {tracker.seeds} | Peers: {tracker.peers}</Text>
                    </View>
                ))}
            </View>
        );
    };

    const renderPeersTab = (info) => {
        if (!info.peersArray || info.peersArray.length === 0) {
            return <Text style={styles.emptyText}>No peer information available.</Text>;
        }
        return (
            <View style={styles.card}>
                {info.peersArray.map((peer, index) => (
                    <View key={index} style={styles.listItem}>
                        <Text style={styles.listItemTitle}>{peer.address}</Text>
                        <Text style={styles.listItemSub}>Client: {peer.agent} | DL: {formatBytes(peer.speed_download)}/s | UL: {formatBytes(peer.speed_upload)}/s</Text>
                    </View>
                ))}
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

        return (
            <View style={styles.contentContainer}>
                {activeTab === 'General' && renderGeneralTab(info)}
                {activeTab === 'Transfer' && renderTransferTab(info)}
                {activeTab === 'Tracker' && renderTrackerTab(info)}
                {activeTab === 'Peers' && renderPeersTab(info)}
                {activeTab === 'File' && renderFilesTab()}
            </View>
        );
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
            {renderTabs()}
            {renderContent()}
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
        padding: 16,
        paddingTop: 60, // approximate safe area
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
    }
});
