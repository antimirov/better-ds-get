import React, { useState, useEffect } from 'react';
import { View, Text, StyleSheet, Modal, TouchableOpacity, FlatList, ActivityIndicator, KeyboardAvoidingView, Platform, Alert } from 'react-native';
import { Feather } from '@expo/vector-icons';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useSynology } from '../hooks/useSynology';

const RECENT_FOLDERS_KEY = '@recent_folders';

export default function FolderPickerModal({
    visible,
    onClose,
    onSelect,
    currentDefaultFolder,
    title = 'Select Download Folder'
}) {
    const { sessionManager } = useSynology();
    const [currentFolderPath, setCurrentFolderPath] = useState('');
    const [folderItems, setFolderItems] = useState([]);
    const [isFetchingFolders, setIsFetchingFolders] = useState(false);
    const [recentFolders, setRecentFolders] = useState([]);

    useEffect(() => {
        if (visible) {
            loadRecentFolders();
            loadFolders(''); // Start at root
        }
    }, [visible]);

    const loadRecentFolders = async () => {
        try {
            const stored = await AsyncStorage.getItem(RECENT_FOLDERS_KEY);
            if (stored) {
                setRecentFolders(JSON.parse(stored));
            }
        } catch (e) {
            console.error('Failed to load recent folders', e);
        }
    };

    const saveRecentFolder = async (path) => {
        try {
            let updated = [path, ...recentFolders.filter(f => f !== path)].slice(0, 5); // Keep top 5
            setRecentFolders(updated);
            await AsyncStorage.setItem(RECENT_FOLDERS_KEY, JSON.stringify(updated));
        } catch (e) {
            console.error('Failed to save recent folder', e);
        }
    };

    const loadFolders = async (path = '') => {
        setIsFetchingFolders(true);
        setCurrentFolderPath(path);
        try {
            const list = await sessionManager.execute(() => sessionManager.ds.listFolders(path));
            setFolderItems(list);
        } catch (error) {
            Alert.alert('Error', 'Failed to fetch folders: ' + error.message);
        } finally {
            setIsFetchingFolders(false);
        }
    };

    const handleSelect = async (path) => {
        if (!path) {
            Alert.alert('Error', 'Please navigate into a valid destination folder first.');
            return;
        }
        await saveRecentFolder(path);
        onSelect(path);
    };

    return (
        <Modal
            visible={visible}
            transparent={true}
            animationType="slide"
            onRequestClose={onClose}
        >
            <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : 'height'} style={styles.modalContainer}>
                <View style={[styles.modalContent, { maxHeight: '80%' }]}>
                    <Text style={styles.modalTitle}>{title}</Text>
                    <Text style={styles.currentPathText} numberOfLines={2} ellipsizeMode="middle">
                        {currentFolderPath
                            ? `Path: /${currentFolderPath}`
                            : (currentDefaultFolder ? `Current default: ${currentDefaultFolder}\nSelect a share to navigate:` : 'Root (Select a share first)')}
                    </Text>

                    {isFetchingFolders ? (
                        <ActivityIndicator size="large" color="#00A1E4" style={{ marginVertical: 20 }} />
                    ) : (
                        <FlatList
                            data={folderItems}
                            keyExtractor={(item) => item.path}
                            contentContainerStyle={{ paddingBottom: 20 }}
                            ListEmptyComponent={<Text style={styles.emptyText}>No folders found.</Text>}
                            ListHeaderComponent={() => {
                                return (
                                    <>
                                        {/* Recent Folders Section (Only show at root) */}
                                        {!currentFolderPath && recentFolders.length > 0 && (
                                            <View style={styles.recentSection}>
                                                <Text style={styles.recentTitle}>Recent Folders:</Text>
                                                {recentFolders.map((recentPath, index) => (
                                                    <TouchableOpacity
                                                        key={`recent-${index}`}
                                                        style={styles.folderRow}
                                                        onPress={() => handleSelect(recentPath)}
                                                    >
                                                        <Feather name="clock" size={20} color="#888" style={{ marginRight: 12 }} />
                                                        <Text style={styles.folderName} numberOfLines={1}>{recentPath}</Text>
                                                    </TouchableOpacity>
                                                ))}
                                                <View style={styles.divider} />
                                                <Text style={styles.recentTitle}>All Shares:</Text>
                                            </View>
                                        )}

                                        {/* Go Up button */}
                                        {!!currentFolderPath && (
                                            <TouchableOpacity
                                                style={styles.folderRow}
                                                onPress={() => {
                                                    const parts = currentFolderPath.split('/');
                                                    parts.pop();
                                                    const parent = parts.join('/');
                                                    loadFolders(parent === '' ? '' : parent);
                                                }}
                                            >
                                                <Feather name="corner-left-up" size={24} color="#666" style={{ marginRight: 12 }} />
                                                <View style={{ flex: 1 }}>
                                                    <Text style={[styles.folderName, { color: '#666' }]}>..</Text>
                                                    <Text style={styles.folderPath}>Go up</Text>
                                                </View>
                                            </TouchableOpacity>
                                        )}
                                    </>
                                );
                            }}
                            renderItem={({ item }) => (
                                <TouchableOpacity
                                    style={styles.folderRow}
                                    onPress={() => loadFolders(item.path)}
                                >
                                    <Feather name="folder" size={24} color="#00A1E4" style={{ marginRight: 12 }} />
                                    <View style={{ flex: 1 }}>
                                        <Text style={styles.folderName} numberOfLines={1} ellipsizeMode="tail">{item.name}</Text>
                                        <Text style={styles.folderPath} numberOfLines={1} ellipsizeMode="middle">{item.path}</Text>
                                    </View>
                                </TouchableOpacity>
                            )}
                        />
                    )}

                    {!!currentFolderPath && (
                        <TouchableOpacity
                            style={[styles.modalButton, { marginBottom: 8, marginTop: 8 }]}
                            onPress={() => handleSelect(currentFolderPath)}
                        >
                            <Text style={styles.modalButtonText}>Select "{currentFolderPath.split('/').pop() || currentFolderPath}"</Text>
                        </TouchableOpacity>
                    )}

                    <TouchableOpacity
                        style={[styles.closeModalButton, { marginTop: 0 }]}
                        onPress={onClose}
                    >
                        <Text style={styles.closeModalText}>Cancel</Text>
                    </TouchableOpacity>
                </View>
            </KeyboardAvoidingView>
        </Modal>
    );
}

const styles = StyleSheet.create({
    modalContainer: {
        flex: 1,
        justifyContent: 'flex-end',
        backgroundColor: 'rgba(0,0,0,0.7)',
    },
    modalContent: {
        backgroundColor: '#1E1E1E',
        borderTopLeftRadius: 20,
        borderTopRightRadius: 20,
        padding: 24,
        paddingBottom: 40,
        width: '100%',
    },
    modalTitle: {
        fontSize: 22,
        fontWeight: 'bold',
        color: '#FFF',
        marginBottom: 8,
        textAlign: 'center',
    },
    currentPathText: {
        fontSize: 14,
        color: '#A0A0A0',
        marginBottom: 16,
        textAlign: 'center',
        fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    },
    emptyText: {
        color: '#888',
        textAlign: 'center',
        marginTop: 20,
        fontSize: 16,
    },
    folderRow: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingVertical: 12,
        borderBottomWidth: 1,
        borderBottomColor: '#2A2A2A',
    },
    folderName: {
        fontSize: 16,
        color: '#E0E0E0',
        fontWeight: '500',
    },
    folderPath: {
        fontSize: 12,
        color: '#888',
        marginTop: 2,
    },
    recentSection: {
        marginBottom: 8,
    },
    recentTitle: {
        fontSize: 14,
        color: '#00A1E4',
        fontWeight: 'bold',
        marginTop: 12,
        marginBottom: 8,
        textTransform: 'uppercase',
    },
    divider: {
        height: 1,
        backgroundColor: '#333',
        marginVertical: 12,
    },
    modalButton: {
        backgroundColor: '#00A1E4',
        paddingVertical: 14,
        borderRadius: 8,
        alignItems: 'center',
    },
    modalButtonText: {
        color: '#FFF',
        fontSize: 16,
        fontWeight: 'bold',
    },
    closeModalButton: {
        paddingVertical: 14,
        alignItems: 'center',
        marginTop: 12,
    },
    closeModalText: {
        color: '#FF6B6B',
        fontSize: 16,
        fontWeight: 'bold',
    },
});
