import React, { useEffect, useState, useCallback } from 'react';
import { View, Text, FlatList, StyleSheet, Alert, TouchableOpacity, RefreshControl, Modal, TextInput, KeyboardAvoidingView, Platform, ActivityIndicator } from 'react-native';
import { Feather } from '@expo/vector-icons';
import * as DocumentPicker from 'expo-document-picker';
import { useSynology } from '../hooks/useSynology';
import { ConnectionState } from '../api/session-manager';
import { useNavigation } from '../hooks/useNavigation';
import FileSelectionModal from '../components/FileSelectionModal';
import FolderPickerModal from '../components/FolderPickerModal';
import AsyncStorage from '@react-native-async-storage/async-storage';

export default function TaskListScreen({ route }) {
    const { sessionManager, connectionState } = useSynology();
    const { navigate } = useNavigation();
    const [tasks, setTasks] = useState([]);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [stats, setStats] = useState({ speedDownload: 0, speedUpload: 0 });

    // Add Task state
    const [isAddModalVisible, setAddModalVisible] = useState(false);
    const [newTaskUrl, setNewTaskUrl] = useState('');
    const [isAdding, setIsAdding] = useState(false);

    // Settings Modal state
    const [isSettingsModalVisible, setSettingsModalVisible] = useState(false);
    const [isInfoModalVisible, setInfoModalVisible] = useState(false);
    const [isActionLoading, setIsActionLoading] = useState(false);

    // Folder Picker State
    const [isFolderModalVisible, setFolderModalVisible] = React.useState(false);
    const [folderPickerMode, setFolderPickerMode] = React.useState('default'); // 'default' or 'addTask'
    const [currentDefaultFolder, setCurrentDefaultFolder] = React.useState('');
    const [selectedAddFolder, setSelectedAddFolder] = React.useState('');

    // File Selection state
    const [selectionModalVisible, setSelectionModalVisible] = useState(false);
    const [pendingFiles, setPendingFiles] = useState([]);
    const [pendingListId, setPendingListId] = useState(null);
    const [isConfirmingFiles, setIsConfirmingFiles] = useState(false);

    const fetchTasks = useCallback(async () => {
        if (!sessionManager.ds) return;

        try {
            const result = await sessionManager.execute(() => sessionManager.ds.listTasks({ limit: 100 }));
            setTasks(result.tasks);

            // Also fetch stats
            try {
                const statsResult = await sessionManager.execute(() => sessionManager.ds.getStatistics());
                if (statsResult) {
                    setStats({
                        speedDownload: statsResult.speed_download || 0,
                        speedUpload: statsResult.speed_upload || 0,
                    });
                }
            } catch (e) {
                // Ignore stats fetch failure
            }

        } catch (error) {
            // The session manager handles transient errors and logic.
            // If it throws here, it's a permanent network error we should show briefly.
            console.warn('Failed to fetch tasks:', error);
        } finally {
            setLoading(false);
            setRefreshing(false);
        }
    }, [sessionManager]);

    // Fetch default destination once on connect
    useEffect(() => {
        const fetchConfig = async () => {
            try {
                const config = await sessionManager.execute(() => sessionManager.ds.getConfig());
                if (config && config.default_destination) {
                    setCurrentDefaultFolder(config.default_destination);
                    setSelectedAddFolder(prev => prev ? prev : config.default_destination);
                }
            } catch (e) {
                console.warn('Failed to fetch default destination:', e);
            }
        };

        if (sessionManager.isConnected) {
            fetchConfig();
        }
    }, [sessionManager.connectionState]);

    // Polling loop
    useEffect(() => {
        fetchTasks();
        const interval = setInterval(fetchTasks, 3000); // Poll every 3 seconds
        return () => clearInterval(interval);
    }, [fetchTasks]);

    const onRefresh = () => {
        setRefreshing(true);
        fetchTasks();
    };

    const handleAddTaskUrl = async () => {
        if (!newTaskUrl.trim()) {
            Alert.alert('Error', 'Please enter a URL or magnet link');
            return;
        }

        setIsAdding(true);
        try {
            const result = await sessionManager.execute(() => sessionManager.ds.createTask(newTaskUrl.trim(), {
                destination: selectedAddFolder,
                createList: true
            }));

            setNewTaskUrl('');
            setAddModalVisible(false);

            if (result.list_id && result.list_id.length > 0) {
                const listId = result.list_id[0];
                const files = await sessionManager.ds.getFileList(listId);
                setPendingListId(listId);
                setPendingFiles(files);
                setSelectionModalVisible(true);
            } else {
                Alert.alert('Success', 'Task added successfully');
                fetchTasks(); // refresh list
            }
        } catch (error) {
            Alert.alert('Error', 'Failed to add task: ' + error.message);
        } finally {
            setIsAdding(false);
        }
    };

    const handleDirectTorrentUpload = async (rnFile) => {
        setIsAdding(true);
        try {
            const uploadResult = await sessionManager.execute(() => sessionManager.ds.createTaskFromFile(rnFile, {
                destination: selectedAddFolder,
                createList: true
            }));

            if (uploadResult.list_id && uploadResult.list_id.length > 0) {
                const listId = uploadResult.list_id[0];
                const files = await sessionManager.ds.getFileList(listId);
                setPendingListId(listId);
                setPendingFiles(files);
                setSelectionModalVisible(true);
            } else {
                Alert.alert('Success', 'Torrent file uploaded successfully');
                fetchTasks(); // refresh list
            }
        } catch (error) {
            Alert.alert('Error', 'Failed to upload torrent: ' + error.message);
        } finally {
            setIsAdding(false);
        }
    };

    const handleAddTorrentFile = async () => {
        try {
            const result = await DocumentPicker.getDocumentAsync({
                type: ['application/x-bittorrent', '*/*'], // Fallback to all files if mime is weird
                copyToCacheDirectory: true,
            });

            if (result.canceled || !result.assets || result.assets.length === 0) {
                return;
            }

            const file = result.assets[0];

            // Log asset keys to trace C++ HostObject issues
            console.log('DocumentPicker result keys:', Object.keys(file));
            let rnFile = { uri: file.uri };
            try {
                rnFile.name = file.name || 'upload.torrent';
                rnFile.type = file.mimeType || 'application/x-bittorrent';
            } catch (propErr) {
                console.error('Error reading properties from DocumentPicker asset!', propErr);
                rnFile.name = 'upload.torrent';
                rnFile.type = 'application/x-bittorrent';
            }

            setAddModalVisible(false);
            await handleDirectTorrentUpload(rnFile);
        } catch (error) {
            Alert.alert('Error', 'Failed to pick torrent: ' + error.message);
        }
    };

    // Handle deep links passed via navigation
    useEffect(() => {
        const autoAddUrl = route?.params?.autoAddUrl;
        if (autoAddUrl) {
            console.log('Received autoAddUrl:', autoAddUrl);

            // If it's a content URI or file URI, trigger file upload directly
            if (autoAddUrl.startsWith('file:') || autoAddUrl.startsWith('content:')) {
                const rnFile = {
                    uri: autoAddUrl,
                    // Try to guess a name, fallback to generic
                    name: decodeURIComponent(autoAddUrl.split('/').pop() || 'upload.torrent'),
                    type: 'application/x-bittorrent'
                };
                handleDirectTorrentUpload(rnFile);
            } else {
                // Magnet link or custom scheme
                setNewTaskUrl(autoAddUrl);
                setAddModalVisible(true);
            }

            // Clear param
            if (route.params) route.params.autoAddUrl = undefined;
        }
    }, [route?.params?.autoAddUrl, selectedAddFolder]);

    const handleConfirmSelection = async ({ wanted, unwanted }) => {
        setIsConfirmingFiles(true);
        try {
            // Finalize task creation with selected files
            await sessionManager.execute(() => sessionManager.ds.createTask('', {
                listId: pendingListId,
                selectedIndices: wanted,
                destination: selectedAddFolder
            }));

            setSelectionModalVisible(false);
            Alert.alert('Success', 'Download started with selected files');
            fetchTasks();
        } catch (error) {
            Alert.alert('Error', 'Failed to finalize selection: ' + error.message);
        } finally {
            setIsConfirmingFiles(false);
        }
    };

    const handleLogout = async () => {
        Alert.alert('Logout', 'Are you sure you want to disconnect?', [
            { text: 'Cancel', style: 'cancel' },
            {
                text: 'Logout',
                style: 'destructive',
                onPress: () => {
                    setSettingsModalVisible(false);
                    sessionManager.disconnect(false);
                }
            }
        ]);
    };

    const runBulkAction = async (actionFn, successMessage) => {
        setIsActionLoading(true);
        try {
            await sessionManager.execute(actionFn);
            Alert.alert('Success', successMessage);
            fetchTasks();
        } catch (error) {
            Alert.alert('Error', error.message);
        } finally {
            setIsActionLoading(false);
            setSettingsModalVisible(false);
        }
    };

    const handleClearCompleted = () => runBulkAction(() => sessionManager.ds.clearCompletedTasks(), 'Completed tasks cleared');
    const handleClearError = () => runBulkAction(() => sessionManager.ds.clearErrorTasks(), 'Failed tasks cleared');

    const handleResumeAll = () => {
        const pausedIds = tasks.filter(t => t.status === 'paused' || t.status === 'error').map(t => t.id);
        if (pausedIds.length === 0) return Alert.alert('Info', 'No paused tasks to resume');
        runBulkAction(() => sessionManager.ds.resumeTasks(pausedIds), 'Tasks resumed');
    };

    const handlePauseAll = () => {
        const activeIds = tasks.filter(t => t.status === 'downloading' || t.status === 'waiting' || t.status === 'seeding').map(t => t.id);
        if (activeIds.length === 0) return Alert.alert('Info', 'No active tasks to pause');
        runBulkAction(() => sessionManager.ds.pauseTasks(activeIds), 'Tasks paused');
    };

    const handleRemoveAll = () => {
        Alert.alert('Remove All', 'Are you sure you want to remove all tasks?', [
            { text: 'Cancel', style: 'cancel' },
            {
                text: 'Remove',
                style: 'destructive',
                onPress: () => {
                    const allIds = tasks.map(t => t.id);
                    if (allIds.length === 0) return;
                    runBulkAction(() => sessionManager.ds.deleteTasks(allIds, false), 'All tasks removed');
                }
            }
        ]);
    };

    const handleOpenFolderModal = (mode = 'default') => {
        setFolderPickerMode(mode);
        setFolderModalVisible(true);
    };

    const handleSelectFolder = async (path) => {
        if (folderPickerMode === 'addTask') {
            setSelectedAddFolder(path);
            setFolderModalVisible(false);
            return;
        }

        setIsActionLoading(true);
        try {
            // SYNO.DownloadStation2.Settings.Location expects the path WITHOUT a leading slash
            const formattedPath = path.startsWith('/') ? path.substring(1) : path;
            await sessionManager.execute(() => sessionManager.ds.setConfig({ default_destination: formattedPath }));
            setCurrentDefaultFolder(formattedPath);
            Alert.alert('Success', `Default download folder changed to: ${path}`);
            setFolderModalVisible(false);
            setSettingsModalVisible(false);
        } catch (error) {
            Alert.alert('Error', 'Failed to change folder: ' + error.message);
        } finally {
            setIsActionLoading(false);
        }
    };

    const formatBytes = (bytes) => {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    };

    const getStatusStyle = (status) => {
        switch (status) {
            case 'finished':
            case 'seeding':
                return styles.statusFinished;
            case 'downloading':
                return styles.statusDownloading;
            case 'error':
                return styles.statusError;
            case 'paused':
            case 'waiting':
            default:
                return styles.statusPaused;
        }
    };

    const renderInfoModal = () => {
        const info = sessionManager.connectionInfo;
        const maskSid = (sid) => sid ? `${sid.substring(0, 4)}...${sid.substring(sid.length - 4)}` : 'N/A';

        return (
            <Modal
                visible={isInfoModalVisible}
                transparent={true}
                animationType="fade"
                onRequestClose={() => setInfoModalVisible(false)}
            >
                <View style={styles.modalOverlay}>
                    <View style={styles.settingsModalContent}>
                        <Text style={styles.settingsModalTitle}>Connection Details</Text>

                        <View style={styles.infoRowStat}>
                            <Text style={styles.infoLabel}>Account</Text>
                            <Text style={styles.infoValue}>{sessionManager.credentials?.account || 'Unknown'}</Text>
                        </View>

                        {info.originalAddress && info.originalAddress !== info.baseUrl && (
                            <View style={styles.infoRowColumn}>
                                <Text style={styles.infoLabel}>Address You Typed</Text>
                                <Text style={styles.infoValueLeft}>{info.originalAddress}</Text>
                            </View>
                        )}

                        <View style={styles.infoRowColumn}>
                            <Text style={styles.infoLabel}>Resolved NAS Address</Text>
                            <Text style={styles.infoValueLeft}>{info.baseUrl || 'N/A'}</Text>
                        </View>

                        <View style={styles.infoRowStat}>
                            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                                <Text style={styles.infoLabel}>QuickConnect Relay</Text>
                                {info.isQuickConnect && <View style={styles.activeDot} />}
                            </View>
                            <Text style={[styles.infoValue, { color: info.isQuickConnect ? '#4CAF50' : '#888' }]}>
                                {info.isQuickConnect ? 'ACTIVE' : 'NO'}
                            </Text>
                        </View>

                        <View style={styles.infoRowStat}>
                            <Text style={styles.infoLabel}>Encryption (SSL)</Text>
                            <Text style={[styles.infoValue, { color: info.isHttps ? '#4CAF50' : '#FF9800' }]}>
                                {info.isHttps ? 'HTTPS / SECURE' : 'HTTP / INSECURE'}
                            </Text>
                        </View>

                        <View style={styles.infoRowStat}>
                            <Text style={styles.infoLabel}>Session ID</Text>
                            <Text style={styles.infoValue}>{maskSid(info.sid)}</Text>
                        </View>

                        <TouchableOpacity
                            style={styles.settingsCloseButton}
                            onPress={() => setInfoModalVisible(false)}
                        >
                            <Text style={styles.settingsCloseText}>Close</Text>
                        </TouchableOpacity>
                    </View>
                </View>
            </Modal>
        );
    };

    const renderTask = ({ item }) => {
        const isDownloading = item.status === 'downloading';
        const progressText = item.size > 0
            ? `${((item.sizeDownloaded / item.size) * 100).toFixed(1)}%`
            : '0%';

        return (
            <TouchableOpacity
                style={styles.taskCard}
                onPress={() => navigate('TaskDetail', { task: item })}
                activeOpacity={0.7}
            >
                <View style={styles.taskHeader}>
                    <Text style={styles.taskTitle} numberOfLines={2}>{item.title}</Text>
                </View>
                <View style={styles.taskBody}>
                    <Text style={[styles.taskStatus, getStatusStyle(item.status)]}>
                        {String(item.status || 'UNKNOWN').toUpperCase()}
                    </Text>
                    <Text style={styles.taskProgress}>
                        {formatBytes(item.sizeDownloaded)} / {formatBytes(item.size)} ({progressText})
                    </Text>
                </View>
                <View style={styles.taskFooter}>
                    <View style={styles.progressContainer}>
                        <View style={[styles.progressBar, { width: progressText, backgroundColor: getStatusStyle(item.status).color || '#00A1E4' }]} />
                    </View>
                    {isDownloading && (
                        <Text style={styles.taskSpeed}>
                            ↓ {formatBytes(item.speedDownload)}/s  ↑ {formatBytes(item.speedUpload)}/s
                        </Text>
                    )}
                </View>
            </TouchableOpacity>
        );
    };

    return (
        <View style={styles.container}>
            <View style={styles.header}>
                <View style={styles.headerContent}>
                    <Text style={styles.headerTitle}>Downloads: {tasks.length}</Text>
                    <Text style={styles.headerStats}>
                        {connectionState === ConnectionState.RECONNECTING ? '  Reconnecting...' :
                            `  ↓ ${formatBytes(stats.speedDownload)}/s  ↑ ${formatBytes(stats.speedUpload)}/s`}
                    </Text>
                </View>
                <TouchableOpacity onPress={() => setSettingsModalVisible(true)} style={styles.settingsButton}>
                    <Feather name="settings" size={24} color="#888" />
                </TouchableOpacity>
            </View>

            {/* Remove old subHeader Add Task Button here */}

            <FlatList
                data={tasks}
                keyExtractor={item => item.id}
                renderItem={renderTask}
                refreshControl={
                    <RefreshControl refreshing={refreshing} onRefresh={onRefresh} tintColor={'#00A1E4'} />
                }
                contentContainerStyle={styles.listContent}
                ListEmptyComponent={
                    !loading && <Text style={styles.emptyText}>No download tasks active.</Text>
                }
            />

            {/* Floating Action Button (FAB) for Adding Tasks */}
            <TouchableOpacity style={styles.fab} onPress={() => setAddModalVisible(true)}>
                <Text style={styles.fabText}>+</Text>
            </TouchableOpacity>

            {/* Add Task Modal */}
            <Modal
                visible={isAddModalVisible}
                transparent={true}
                animationType="slide"
                onRequestClose={() => setAddModalVisible(false)}
            >
                <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : 'height'} style={styles.modalContainer}>
                    <View style={styles.modalContent}>
                        <Text style={styles.modalTitle}>Add New Task</Text>

                        <Text style={styles.modalLabel}>URL or Magnet Link</Text>
                        <TextInput
                            style={styles.input}
                            placeholder="magnet:?xt=urn:btih:..."
                            placeholderTextColor="#666"
                            value={newTaskUrl}
                            onChangeText={setNewTaskUrl}
                            autoCapitalize="none"
                            autoCorrect={false}
                            editable={!isAdding}
                        />
                        <TouchableOpacity style={[styles.modalButton, isAdding && styles.buttonDisabled]} onPress={handleAddTaskUrl} disabled={isAdding}>
                            {isAdding ? <ActivityIndicator color="#FFF" /> : <Text style={styles.modalButtonText}>Download from URL</Text>}
                        </TouchableOpacity>

                        <Text style={styles.modalLabel}>Download Destination</Text>
                        <TouchableOpacity
                            style={styles.input}
                            onPress={() => handleOpenFolderModal('addTask')}
                            disabled={isAdding}
                        >
                            <Text style={{ color: selectedAddFolder ? '#FFF' : '#666' }}>
                                {selectedAddFolder || 'Select Folder...'}
                            </Text>
                        </TouchableOpacity>

                        <View style={styles.divider} />

                        <TouchableOpacity style={[styles.modalButton, styles.secondaryButton, isAdding && styles.buttonDisabled]} onPress={handleAddTorrentFile} disabled={isAdding}>
                            {isAdding ? <ActivityIndicator color="#FFF" /> : <Text style={styles.modalButtonText}>Upload .torrent File</Text>}
                        </TouchableOpacity>

                        <TouchableOpacity style={styles.closeModalButton} onPress={() => setAddModalVisible(false)} disabled={isAdding}>
                            <Text style={styles.closeModalText}>Cancel</Text>
                        </TouchableOpacity>
                    </View>
                </KeyboardAvoidingView>
            </Modal>

            {/* Settings / Actions Modal */}
            <Modal
                visible={isSettingsModalVisible}
                transparent={true}
                animationType="fade"
                onRequestClose={() => setSettingsModalVisible(false)}
            >
                <View style={styles.modalOverlay}>
                    <View style={styles.settingsModalContent}>
                        <Text style={styles.settingsModalTitle}>Settings & Actions</Text>

                        <View style={styles.settingsUserInfo}>
                            <Feather name="user" size={16} color="#888" style={{ marginRight: 8 }} />
                            <Text style={styles.settingsUserText}>Logged in as: {sessionManager.credentials?.account || 'Unknown'}</Text>
                            <TouchableOpacity
                                style={{ marginLeft: 'auto', padding: 4 }}
                                onPress={() => {
                                    setSettingsModalVisible(false);
                                    setInfoModalVisible(true);
                                }}
                            >
                                <Feather name="info" size={20} color="#00A1E4" />
                            </TouchableOpacity>
                        </View>

                        <View style={styles.settingsDivider} />

                        <TouchableOpacity style={styles.settingsActionRow} onPress={handleResumeAll} disabled={isActionLoading}>
                            <Feather name="play" size={20} color="#E0E0E0" />
                            <Text style={styles.settingsActionText}>Resume All</Text>
                        </TouchableOpacity>

                        <TouchableOpacity style={styles.settingsActionRow} onPress={handlePauseAll} disabled={isActionLoading}>
                            <Feather name="pause" size={20} color="#E0E0E0" />
                            <Text style={styles.settingsActionText}>Pause All</Text>
                        </TouchableOpacity>

                        <TouchableOpacity style={styles.settingsActionRow} onPress={handleClearCompleted} disabled={isActionLoading}>
                            <Feather name="check" size={20} color="#4CAF50" />
                            <Text style={styles.settingsActionText}>Clear Completed</Text>
                        </TouchableOpacity>

                        <TouchableOpacity style={styles.settingsActionRow} onPress={handleClearError} disabled={isActionLoading}>
                            <Feather name="alert-triangle" size={20} color="#FF9800" />
                            <Text style={styles.settingsActionText}>Remove Failed Tasks</Text>
                        </TouchableOpacity>

                        <TouchableOpacity style={styles.settingsActionRow} onPress={handleRemoveAll} disabled={isActionLoading}>
                            <Feather name="trash-2" size={20} color="#FF6B6B" />
                            <Text style={[styles.settingsActionText, { color: '#FF6B6B' }]}>Remove All</Text>
                        </TouchableOpacity>

                        <View style={styles.settingsDivider} />

                        <TouchableOpacity style={styles.settingsActionRow} onPress={handleOpenFolderModal} disabled={isActionLoading}>
                            <Feather name="folder" size={20} color="#00A1E4" />
                            <View>
                                <Text style={[styles.settingsActionText, { color: '#00A1E4' }]}>Change Default Download Folder</Text>
                                {!!currentDefaultFolder && (
                                    <Text style={[styles.settingsActionText, { color: '#888', fontSize: 12, marginTop: 2 }]}>
                                        Current: {currentDefaultFolder}
                                    </Text>
                                )}
                            </View>
                        </TouchableOpacity>

                        <View style={styles.settingsDivider} />

                        <TouchableOpacity style={styles.settingsActionRow} onPress={handleLogout}>
                            <Feather name="log-out" size={20} color="#FF6B6B" />
                            <Text style={[styles.settingsActionText, { color: '#FF6B6B' }]}>Logout</Text>
                        </TouchableOpacity>

                        <TouchableOpacity style={styles.settingsCloseButton} onPress={() => setSettingsModalVisible(false)} disabled={isActionLoading}>
                            <Text style={styles.settingsCloseText}>Close</Text>
                        </TouchableOpacity>
                    </View>
                </View>
            </Modal>

            {renderInfoModal()}

            <FileSelectionModal
                visible={selectionModalVisible}
                files={pendingFiles}
                isConfirming={isConfirmingFiles}
                onConfirm={handleConfirmSelection}
                onCancel={() => setSelectionModalVisible(false)}
            />

            {/* Folder Picker Modal */}
            <FolderPickerModal
                visible={isFolderModalVisible}
                onClose={() => setFolderModalVisible(false)}
                onSelect={handleSelectFolder}
                currentDefaultFolder={currentDefaultFolder}
                title={folderPickerMode === 'addTask' ? 'Select Destination' : 'Set Default Folder'}
            />
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: '#121212',
    },
    header: {
        paddingHorizontal: 20,
        paddingBottom: 16,
        paddingTop: 12,
        backgroundColor: '#1E1E1E',
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        borderBottomWidth: 1,
        borderBottomColor: '#333',
    },
    headerContent: {
        flexDirection: 'row',
        alignItems: 'baseline',
    },
    headerTitle: {
        fontSize: 18, // Slightly smaller to fit on one line
        fontWeight: 'bold',
        color: '#00A1E4',
    },
    headerStats: {
        fontSize: 12,
        color: '#888',
        marginLeft: 16, // Increased from 4
    },
    headerSubtitle: {
        fontSize: 14,
        color: '#888',
        marginTop: 4,
    },
    settingsButton: {
        padding: 8,
        borderRadius: 20,
    },
    fab: {
        position: 'absolute',
        width: 60,
        height: 60,
        alignItems: 'center',
        justifyContent: 'center',
        right: 20,
        bottom: 30, // Above typical safe area
        backgroundColor: '#00A1E4',
        borderRadius: 30,
        elevation: 8, // For Android shadow
        shadowColor: '#000', // For iOS shadow
        shadowOpacity: 0.3,
        shadowRadius: 5,
        shadowOffset: { width: 0, height: 4 },
    },
    fabText: {
        fontSize: 32,
        color: '#FFF',
        fontWeight: 'normal',
        lineHeight: 34,
    },
    listContent: {
        padding: 16,
    },
    taskCard: {
        backgroundColor: '#1E1E1E',
        borderRadius: 10,
        padding: 16,
        marginBottom: 12,
        borderLeftWidth: 4,
        borderLeftColor: '#00A1E4',
    },
    taskHeader: {
        marginBottom: 8,
    },
    taskTitle: {
        color: '#FFF',
        fontSize: 16,
        fontWeight: '500',
    },
    taskBody: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        marginBottom: 8,
    },
    taskStatus: {
        fontSize: 12,
        fontWeight: 'bold',
    },
    statusFinished: {
        color: '#4CAF50', // green
    },
    statusDownloading: {
        color: '#00A1E4', // blue
    },
    statusPaused: {
        color: '#FF9800', // amber
    },
    statusError: {
        color: '#FF6B6B', // red
    },
    taskProgress: {
        color: '#AAA',
        fontSize: 12,
    },
    taskFooter: {
        flexDirection: 'row',
    },
    taskSpeed: {
        color: '#888',
        fontSize: 12,
        marginTop: 4,
    },
    progressContainer: {
        height: 6,
        backgroundColor: '#333',
        borderRadius: 3,
        flex: 1,
        marginRight: 10,
        overflow: 'hidden',
        alignSelf: 'center',
    },
    progressBar: {
        height: '100%',
        borderRadius: 3,
    },
    emptyText: {
        color: '#666',
        textAlign: 'center',
        marginTop: 40,
        fontSize: 16,
    },

    // Modal Styles
    modalContainer: {
        flex: 1,
        backgroundColor: 'rgba(0,0,0,0.6)',
        justifyContent: 'flex-end',
    },
    modalContent: {
        backgroundColor: '#2C2C2C',
        borderTopLeftRadius: 20,
        borderTopRightRadius: 20,
        padding: 24,
        paddingBottom: Platform.OS === 'ios' ? 40 : 24,
    },
    modalTitle: {
        fontSize: 22,
        fontWeight: 'bold',
        color: '#00A1E4',
        marginBottom: 20,
        textAlign: 'center',
    },
    modalLabel: {
        color: '#AAA',
        marginBottom: 8,
        fontWeight: '600',
    },
    input: {
        backgroundColor: '#1E1E1E',
        borderWidth: 1,
        borderColor: '#444',
        borderRadius: 8,
        color: '#FFF',
        padding: 12,
        marginBottom: 16,
        fontSize: 16,
    },
    modalButton: {
        backgroundColor: '#4CAF50',
        borderRadius: 8,
        padding: 16,
        alignItems: 'center',
        marginBottom: 16,
    },
    secondaryButton: {
        backgroundColor: '#00A1E4',
    },
    buttonDisabled: {
        opacity: 0.6,
    },
    modalButtonText: {
        color: '#FFF',
        fontSize: 16,
        fontWeight: 'bold',
    },
    divider: {
        height: 1,
        backgroundColor: '#444',
        marginVertical: 10,
        marginBottom: 26,
    },
    closeModalButton: {
        padding: 16,
        alignItems: 'center',
    },
    closeModalText: {
        color: '#888',
        fontSize: 16,
        fontWeight: '600',
    },
    modalOverlay: {
        flex: 1,
        backgroundColor: 'rgba(0,0,0,0.7)',
        justifyContent: 'center',
        alignItems: 'center',
        padding: 20,
    },
    settingsModalContent: {
        backgroundColor: '#2C2C2C',
        borderRadius: 16,
        width: '100%',
        maxWidth: 400,
        padding: 20,
    },
    settingsModalTitle: {
        fontSize: 20,
        fontWeight: 'bold',
        color: '#FFF',
        marginBottom: 16,
    },
    settingsUserInfo: {
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: 8,
    },
    settingsUserText: {
        color: '#888',
        fontSize: 14,
    },
    settingsDivider: {
        height: 1,
        backgroundColor: '#444',
        marginVertical: 12,
    },
    settingsActionRow: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingVertical: 12,
    },
    settingsActionText: {
        color: '#E0E0E0',
        fontSize: 16,
        marginLeft: 12,
        fontWeight: '500',
    },
    settingsCloseButton: {
        marginTop: 16,
        paddingVertical: 12,
        alignItems: 'center',
        backgroundColor: '#444',
        borderRadius: 8,
    },
    settingsCloseText: {
        color: '#FFF',
        fontSize: 16,
        fontWeight: 'bold',
    },
    folderRow: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingVertical: 14,
        borderBottomWidth: 1,
        borderBottomColor: '#444',
    },
    folderName: {
        color: '#FFF',
        fontSize: 16,
        fontWeight: '600',
    },
    folderPath: {
        color: '#888',
        fontSize: 12,
        marginTop: 2,
    },
    currentPathText: {
        color: '#DDD',
        marginBottom: 16,
        fontSize: 13,
        lineHeight: 18,
    },
    infoRowStat: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        paddingVertical: 12,
        borderBottomWidth: 1,
        borderBottomColor: '#333',
        alignItems: 'center',
    },
    infoRowColumn: {
        paddingVertical: 12,
        borderBottomWidth: 1,
        borderBottomColor: '#333',
    },
    infoLabel: {
        color: '#888',
        fontSize: 13,
        fontWeight: '600',
    },
    infoValue: {
        color: '#E0E0E0',
        fontSize: 13,
        fontWeight: '500',
        textAlign: 'right',
    },
    infoValueLeft: {
        color: '#E0E0E0',
        fontSize: 12, // Slightly smaller for long URLs
        marginTop: 4,
        fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    },
    activeDot: {
        width: 8,
        height: 8,
        borderRadius: 4,
        backgroundColor: '#4CAF50',
        marginLeft: 8,
    }
});
