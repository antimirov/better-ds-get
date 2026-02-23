import React from 'react';
import { View, Text, StyleSheet, FlatList, TextInput, TouchableOpacity, ActivityIndicator, Alert } from 'react-native';
import { Feather } from '@expo/vector-icons';
import { useSynology } from '../hooks/useSynology';
import { useNavigation } from '../hooks/useNavigation';
import { useSearch } from '../hooks/useSearch';

export default function SearchScreen() {
    const { downloadStation } = useSynology();
    const {
        keyword, setKeyword, results, searching, isFinished, modules,
        startSearch, cancelSearch, clearSearch
    } = useSearch();

    const [localKeyword, setLocalKeyword] = React.useState(keyword);
    const [loading, setLoading] = React.useState(false);

    // Keep local keyword in sync if changed from context (e.g. clearSearch)
    React.useEffect(() => {
        setLocalKeyword(keyword);
    }, [keyword]);

    const handleSearch = async () => {
        if (!localKeyword.trim()) return;
        setLoading(true);
        try {
            await startSearch(localKeyword);
        } catch (e) {
            Alert.alert('Error', e.message || 'Search failed');
        } finally {
            setLoading(false);
        }
    };

    const handleAddTask = async (item) => {
        try {
            setLoading(true);
            await downloadStation.createTask(item.download_uri);
            Alert.alert('Success', 'Download task added: ' + item.title);
        } catch (e) {
            Alert.alert('Error', 'Failed to add task: ' + e.message);
        } finally {
            setLoading(false);
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

            {searching && (
                <View style={styles.searchingStatus}>
                    <View style={styles.statusInfo}>
                        {!isFinished ? (
                            <ActivityIndicator size="small" color="#00A1E4" />
                        ) : (
                            <Feather name="check-circle" size={16} color="#4CAF50" />
                        )}
                        <Text style={styles.searchingText}>
                            {isFinished ? 'Search finished' : 'Searching engines...'} ({results.length} found)
                        </Text>
                    </View>
                    {!isFinished && (
                        <TouchableOpacity style={styles.stopButton} onPress={cancelSearch}>
                            <Feather name="stop-circle" size={18} color="#FF6B6B" />
                            <Text style={styles.stopButtonText}>Stop</Text>
                        </TouchableOpacity>
                    )}
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
        paddingHorizontal: 16,
        paddingTop: 60, // approximate safe area
        paddingBottom: 16,
        backgroundColor: '#1E1E1E',
        borderBottomWidth: 1,
        borderBottomColor: '#333',
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
    searchingStatus: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        backgroundColor: '#1A2A3A',
        paddingVertical: 10,
        paddingHorizontal: 16,
    },
    statusInfo: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    searchingText: {
        color: '#00A1E4',
        marginLeft: 8,
        fontSize: 13,
        fontWeight: '500',
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

