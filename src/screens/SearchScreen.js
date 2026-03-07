import React from 'react';
import { View, Text, StyleSheet, FlatList, TextInput, TouchableOpacity, ActivityIndicator, Alert } from 'react-native';
import { Feather } from '@expo/vector-icons';
import { useSynology } from '../hooks/useSynology';
import { useNavigation } from '../hooks/useNavigation';
import { useSearch } from '../hooks/useSearch';
import FileSelectionModal from '../components/FileSelectionModal';
import FolderPickerModal from '../components/FolderPickerModal';

export default function SearchScreen() {
    const { downloadStation, sessionManager } = useSynology();
    const {
        keyword, setKeyword, results, searching, isFinished, modules, engineStatus,
        sortBy, setSortBy, sortOrder, setSortOrder,
        startSearch, cancelSearch, clearSearch
    } = useSearch();

    const [localKeyword, setLocalKeyword] = React.useState(keyword);
    const [loading, setLoading] = React.useState(false);

    // File Selection state
    const [selectionModalVisible, setSelectionModalVisible] = React.useState(false);
    const [pendingFiles, setPendingFiles] = React.useState([]);
    const [pendingListId, setPendingListId] = React.useState(null);
    const [isConfirmingFiles, setIsConfirmingFiles] = React.useState(false);

    // Destination Folder State
    const [isFolderModalVisible, setFolderModalVisible] = React.useState(false);
    const [selectedDestination, setSelectedDestination] = React.useState('');
    const [defaultDestination, setDefaultDestination] = React.useState('');
    const [showEngineDetails, setShowEngineDetails] = React.useState(false);

    // Fetch default destination on mount
    React.useEffect(() => {
        const fetchConfig = async () => {
            try {
                const config = await sessionManager.execute(() => sessionManager.ds.getConfig());
                if (config && config.default_destination) {
                    setDefaultDestination(config.default_destination);
                }
            } catch (e) {
                console.warn('Failed to fetch default destination for search screen', e);
            }
        };
        if (sessionManager.isConnected) {
            fetchConfig();
        }
    }, [sessionManager.connectionState]);

    // Auto-collapse engine details when search finishes
    React.useEffect(() => {
        if (isFinished && showEngineDetails) {
            setShowEngineDetails(false);
        }
    }, [isFinished]);

    // Keep local keyword in sync if changed from context (e.g. clearSearch)
    React.useEffect(() => {
        setLocalKeyword(keyword);
    }, [keyword]);

    const handleSearch = async () => {
        if (!localKeyword.trim()) return;
        setLoading(true);
        setShowEngineDetails(true); // Auto-expand on search start
        try {
            await startSearch(localKeyword);
        } catch (e) {
            setShowEngineDetails(false);
            Alert.alert('Error', e.message || 'Search failed');
        } finally {
            setLoading(false);
        }
    };

    const handleAddTask = async (item) => {
        try {
            setLoading(true);
            const destination = selectedDestination || defaultDestination || '';
            const result = await downloadStation.createTask(item.download_uri, {
                createList: true,
                destination: destination
            });

            if (result.list_id && result.list_id.length > 0) {
                const listId = result.list_id[0];
                const files = await downloadStation.getFileList(listId);
                setPendingListId(listId);
                setPendingFiles(files);
                setSelectionModalVisible(true);
            } else {
                Alert.alert('Success', 'Download task added: ' + item.title);
            }
        } catch (e) {
            Alert.alert('Error', 'Failed to add task: ' + e.message);
        } finally {
            setLoading(false);
        }
    };

    const handleConfirmSelection = async ({ wanted, unwanted }) => {
        setIsConfirmingFiles(true);
        try {
            // Finalize task creation with selected files
            await sessionManager.execute(() => downloadStation.createTask('', {
                listId: pendingListId,
                selectedIndices: wanted,
                destination: selectedDestination || defaultDestination || ''
            }));

            setSelectionModalVisible(false);
            Alert.alert('Success', 'Download started with selected files');
        } catch (error) {
            Alert.alert('Error', 'Failed to finalize selection: ' + error.message);
        } finally {
            setIsConfirmingFiles(false);
        }
    };

    const formatBytes = (bytes) => {
        if (!bytes || bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    };

    const renderItem = ({ item }) => (
        <View style={styles.resultItem}>
            <View style={styles.resultMain}>
                <Text style={styles.resultTitle} numberOfLines={2}>{item.title}</Text>
                <View style={styles.resultMeta}>
                    <Text style={styles.resultSize}>{formatBytes(item.size)}</Text>
                    <Text style={styles.resultSeeds}>
                        <Feather name="arrow-up" size={12} color="#4CAF50" /> {item.seeds}
                        {'  '}
                        <Feather name="arrow-down" size={12} color="#FF9800" /> {item.peers}
                    </Text>
                </View>
                <Text style={styles.resultEngine}>{item.module_title || item.module}</Text>
            </View>
            <TouchableOpacity style={styles.addButton} onPress={() => handleAddTask(item)} disabled={loading}>
                <Feather name="download" size={24} color="#00A1E4" />
            </TouchableOpacity>
        </View>
    );

    return (
        <View style={styles.container}>
            <View style={styles.header}>
                <View style={styles.searchBar}>
                    <Feather name="search" size={20} color="#888" style={styles.searchIcon} />
                    <TextInput
                        style={styles.input}
                        placeholder="Search for torrents..."
                        placeholderTextColor="#666"
                        value={localKeyword}
                        onChangeText={setLocalKeyword}
                        onSubmitEditing={handleSearch}
                        returnKeyType="search"
                        autoCapitalize="none"
                    />
                    {localKeyword.length > 0 && (
                        <TouchableOpacity onPress={() => setLocalKeyword('')}>
                            <Feather name="x" size={20} color="#888" />
                        </TouchableOpacity>
                    )}
                </View>
                <TouchableOpacity
                    style={styles.searchButton}
                    onPress={handleSearch}
                    disabled={loading || !localKeyword.trim()}
                >
                    <Text style={[styles.searchButtonText, (!localKeyword.trim() || loading) && styles.disabledText]}>Search</Text>
                </TouchableOpacity>
            </View>

            <View style={styles.destinationBar}>
                <Text style={styles.destinationLabel}>Save to:</Text>
                <TouchableOpacity
                    style={styles.destinationSelector}
                    onPress={() => setFolderModalVisible(true)}
                >
                    <Feather name="folder" size={14} color="#00A1E4" style={{ marginRight: 6 }} />
                    <Text style={styles.destinationPath} numberOfLines={1} ellipsizeMode="middle">
                        {selectedDestination || defaultDestination || 'Default Share'}
                    </Text>
                    <Feather name="edit-2" size={12} color="#888" style={{ marginLeft: 'auto' }} />
                </TouchableOpacity>
            </View>

            {searching && (
                <View style={styles.searchingStatusContainer}>
                    <TouchableOpacity 
                        style={styles.searchingStatus} 
                        onPress={() => setShowEngineDetails(!showEngineDetails)}
                        activeOpacity={0.7}
                    >
                        <View style={styles.statusInfo}>
                            {!isFinished ? (
                                <ActivityIndicator size="small" color="#00A1E4" />
                            ) : (
                                <Feather name="check-circle" size={16} color="#4CAF50" />
                            )}
                            <View style={{ flex: 1 }}>
                                <Text style={styles.searchingText}>
                                    {isFinished ? 'Search finished' : 'Searching...'} ({results.length} found)
                                </Text>
                                {!isFinished && (() => {
                                    if (engineStatus.length === 0) {
                                        return (
                                            <Text style={styles.engineStatusText}>
                                                Initializing search engines...
                                            </Text>
                                        );
                                    }
                                    const isDone = (e) => e.status === 'finished' || e.status === 'complete';
                                    const done = engineStatus.filter(isDone).length;
                                    const total = engineStatus.length;
                                    return (
                                        <Text style={styles.engineStatusText} numberOfLines={1}>
                                            {done}/{total} engines done
                                        </Text>
                                    );
                                })()}
                            </View>
                            <Feather 
                                name={showEngineDetails ? "chevron-up" : "chevron-down"} 
                                size={20} 
                                color="#888" 
                                style={{ marginLeft: 8 }}
                            />
                        </View>
                        {!isFinished && (
                            <TouchableOpacity style={styles.stopButton} onPress={(e) => {
                                e.stopPropagation();
                                cancelSearch();
                            }}>
                                <Feather name="stop-circle" size={18} color="#FF6B6B" />
                                <Text style={styles.stopButtonText}>Stop</Text>
                            </TouchableOpacity>
                        )}
                    </TouchableOpacity>

                    {showEngineDetails && (
                        <View style={styles.engineDetailsList}>
                            {engineStatus.map((engine, idx) => (
                                <View key={engine.module || idx} style={styles.engineDetailRow}>
                                    <Text style={styles.engineName}>{engine.module_title || engine.module}</Text>
                                    <View style={styles.engineMeta}>
                                        <Text style={styles.engineResultCount}>{engine.items} found</Text>
                                        <Text style={[
                                            styles.engineStatusTag,
                                            (engine.status === 'finished' || engine.status === 'complete') ? styles.engineDone : styles.enginePending
                                        ]}>
                                            {(engine.status === 'finished' || engine.status === 'complete') ? 'Finished' : 'Searching...'}
                                        </Text>
                                    </View>
                                </View>
                            ))}
                        </View>
                    )}
                </View>
            )}

            {results.length > 0 && (
                <View style={styles.sortBar}>
                    <Text style={styles.sortLabel}>Sort by:</Text>
                    <TouchableOpacity 
                        style={[styles.sortOption, sortBy === 'seeds' && styles.sortOptionActive]}
                        onPress={() => {
                            if (sortBy === 'seeds') setSortOrder(sortOrder === 'desc' ? 'asc' : 'desc');
                            else { setSortBy('seeds'); setSortOrder('desc'); }
                        }}
                    >
                        <Text style={[styles.sortOptionText, sortBy === 'seeds' && styles.sortOptionTextActive]}>Seeds</Text>
                        {sortBy === 'seeds' && (
                            <Feather name={sortOrder === 'desc' ? "chevron-down" : "chevron-up"} size={14} color="#00A1E4" />
                        )}
                    </TouchableOpacity>
                    
                    <TouchableOpacity 
                        style={[styles.sortOption, sortBy === 'size' && styles.sortOptionActive]}
                        onPress={() => {
                            if (sortBy === 'size') setSortOrder(sortOrder === 'desc' ? 'asc' : 'desc');
                            else { setSortBy('size'); setSortOrder('desc'); }
                        }}
                    >
                        <Text style={[styles.sortOptionText, sortBy === 'size' && styles.sortOptionTextActive]}>Size</Text>
                        {sortBy === 'size' && (
                            <Feather name={sortOrder === 'desc' ? "chevron-down" : "chevron-up"} size={14} color="#00A1E4" />
                        )}
                    </TouchableOpacity>
                    
                    <TouchableOpacity style={styles.clearResultsButton} onPress={clearSearch}>
                        <Text style={styles.clearResultsText}>Clear</Text>
                    </TouchableOpacity>
                </View>
            )}

            <FlatList
                data={results}
                keyExtractor={(item, index) => index.toString()}
                renderItem={renderItem}
                ListEmptyComponent={
                    !searching ? (
                        <View style={styles.emptyContainer}>
                            <Feather name="search" size={64} color="#333" />
                            <Text style={styles.emptyText}>Find content to download</Text>
                        </View>
                    ) : loading && results.length === 0 ? (
                        <View style={styles.centered}>
                            <ActivityIndicator size="large" color="#00A1E4" />
                        </View>
                    ) : (
                        <View style={styles.emptyContainer}>
                            <Text style={styles.emptyText}>No results found.</Text>
                        </View>
                    )
                }
                contentContainerStyle={styles.listContent}
                ItemSeparatorComponent={() => <View style={styles.separator} />}
            />

            <FileSelectionModal
                visible={selectionModalVisible}
                files={pendingFiles}
                isConfirming={isConfirmingFiles}
                onConfirm={handleConfirmSelection}
                onCancel={() => setSelectionModalVisible(false)}
            />

            <FolderPickerModal
                visible={isFolderModalVisible}
                onClose={() => setFolderModalVisible(false)}
                onSelect={(path) => {
                    setSelectedDestination(path);
                    setFolderModalVisible(false);
                }}
                currentDefaultFolder={defaultDestination}
                title="Select Task Destination"
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
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 20,
        paddingTop: 12, // Reduced from 60
        paddingBottom: 16,
        backgroundColor: '#1E1E1E',
        // removed borderBottomWidth here, moving it to destinationBar
    },
    destinationBar: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 20,
        paddingBottom: 12,
        backgroundColor: '#1E1E1E',
        borderBottomWidth: 1,
        borderBottomColor: '#333',
    },
    destinationLabel: {
        color: '#888',
        fontSize: 13,
        marginRight: 8,
    },
    destinationSelector: {
        flex: 1,
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: '#2A2A2A',
        paddingHorizontal: 10,
        paddingVertical: 6,
        borderRadius: 6,
    },
    destinationPath: {
        color: '#E0E0E0',
        fontSize: 13,
        flex: 1,
    },
    searchBar: {
        flex: 1,
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: '#2A2A2A',
        borderRadius: 8,
        paddingHorizontal: 12,
        height: 44,
    },
    searchIcon: {
        marginRight: 8,
    },
    input: {
        flex: 1,
        color: '#FFF',
        fontSize: 16,
    },
    searchButton: {
        marginLeft: 12,
        paddingVertical: 8,
    },
    searchButtonText: {
        color: '#00A1E4',
        fontSize: 16,
        fontWeight: 'bold',
    },
    disabledText: {
        opacity: 0.5,
    },
    searchingStatusContainer: {
        backgroundColor: '#1E2A38',
        borderBottomWidth: 1,
        borderBottomColor: '#2C3E50',
    },
    searchingStatus: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingVertical: 12,
        paddingHorizontal: 16,
    },
    engineDetailsList: {
        paddingBottom: 8,
        paddingHorizontal: 16,
        backgroundColor: 'rgba(0, 0, 0, 0.2)',
    },
    engineDetailRow: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        paddingVertical: 6,
        borderTopWidth: 1,
        borderTopColor: 'rgba(255, 255, 255, 0.05)',
    },
    engineName: {
        color: '#E0E0E0',
        fontSize: 13,
        fontWeight: '500',
    },
    engineMeta: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    engineResultCount: {
        color: '#888',
        fontSize: 12,
        marginRight: 10,
    },
    engineStatusTag: {
        fontSize: 11,
        paddingHorizontal: 6,
        paddingVertical: 2,
        borderRadius: 4,
        overflow: 'hidden',
    },
    engineDone: {
        backgroundColor: 'rgba(76, 175, 80, 0.15)',
        color: '#4CAF50',
    },
    enginePending: {
        backgroundColor: 'rgba(255, 193, 7, 0.15)',
        color: '#FFC107',
    },
    sortBar: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingVertical: 10,
        paddingHorizontal: 16,
        backgroundColor: '#1A1A1A',
        borderBottomWidth: 1,
        borderBottomColor: '#333',
    },
    sortLabel: {
        color: '#888',
        fontSize: 12,
        marginRight: 12,
        fontWeight: 'bold',
        textTransform: 'uppercase',
    },
    sortOption: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 10,
        paddingVertical: 4,
        borderRadius: 12,
        backgroundColor: '#2A2A2A',
        marginRight: 8,
    },
    sortOptionActive: {
        backgroundColor: 'rgba(0, 161, 228, 0.1)',
        borderWidth: 1,
        borderColor: '#00A1E4',
    },
    sortOptionText: {
        color: '#888',
        fontSize: 12,
    },
    sortOptionTextActive: {
        color: '#00A1E4',
        fontWeight: 'bold',
        marginRight: 4,
    },
    clearResultsButton: {
        marginLeft: 'auto',
    },
    clearResultsText: {
        color: '#FF6B6B',
        fontSize: 12,
        fontWeight: '600',
    },
    statusInfo: {
        flexDirection: 'row',
        alignItems: 'center',
        flex: 1,
    },
    searchingText: {
        color: '#00A1E4',
        marginLeft: 8,
        fontSize: 13,
        fontWeight: '500',
    },
    engineStatusText: {
        color: '#888',
        marginLeft: 8,
        fontSize: 11,
        marginTop: 2,
    },
    stopButton: {
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: 'rgba(255, 107, 107, 0.1)',
        paddingHorizontal: 8,
        paddingVertical: 4,
        borderRadius: 4,
    },
    stopButtonText: {
        color: '#FF6B6B',
        marginLeft: 4,
        fontSize: 13,
        fontWeight: 'bold',
    },
    listContent: {
        flexGrow: 1,
    },
    resultItem: {
        flexDirection: 'row',
        padding: 16,
        alignItems: 'center',
    },
    resultMain: {
        flex: 1,
    },
    resultTitle: {
        color: '#E0E0E0',
        fontSize: 15,
        fontWeight: '500',
        marginBottom: 6,
    },
    resultMeta: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        marginBottom: 4,
    },
    resultSize: {
        color: '#888',
        fontSize: 13,
    },
    resultSeeds: {
        color: '#888',
        fontSize: 13,
    },
    resultEngine: {
        color: '#555',
        fontSize: 12,
        fontStyle: 'italic',
    },
    addButton: {
        padding: 8,
        marginLeft: 8,
    },
    separator: {
        height: 1,
        backgroundColor: '#222',
    },
    centered: {
        flex: 1,
        justifyContent: 'center',
        alignItems: 'center',
        marginTop: 100,
    },
    emptyContainer: {
        flex: 1,
        justifyContent: 'center',
        alignItems: 'center',
        marginTop: 100,
    },
    emptyText: {
        color: '#444',
        fontSize: 18,
        marginTop: 16,
    },
});

