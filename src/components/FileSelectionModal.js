import React, { useState, useEffect } from 'react';
import { View, Text, StyleSheet, Modal, FlatList, TouchableOpacity, Switch, ActivityIndicator, Platform } from 'react-native';
import { Feather } from '@expo/vector-icons';

export default function FileSelectionModal({ visible, files, onConfirm, onCancel, isConfirming }) {
    const [selectedIndices, setSelectedIndices] = useState({});

    useEffect(() => {
        if (visible && files) {
            const initial = {};
            files.forEach(file => {
                initial[file.index] = true; // Default all selected
            });
            setSelectedIndices(initial);
        }
    }, [visible, files]);

    const toggleFile = (index) => {
        setSelectedIndices(prev => ({
            ...prev,
            [index]: !prev[index]
        }));
    };

    const toggleAll = (value) => {
        const next = {};
        files.forEach(file => {
            next[file.index] = value;
        });
        setSelectedIndices(next);
    };

    const formatBytes = (bytes) => {
        if (!bytes || bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    };

    const renderItem = ({ item }) => (
        <TouchableOpacity
            style={styles.fileItem}
            onPress={() => toggleFile(item.index)}
            activeOpacity={0.7}
        >
            <View style={styles.fileInfo}>
                <Text style={styles.fileName} numberOfLines={2} ellipsizeMode="middle">
                    {item.name}
                </Text>
                <Text style={styles.fileSize}>{formatBytes(item.size)}</Text>
            </View>
            <Switch
                value={!!selectedIndices[item.index]}
                onValueChange={() => toggleFile(item.index)}
                thumbColor={selectedIndices[item.index] ? "#00A1E4" : "#f4f3f4"}
                trackColor={{ false: "#333", true: "#00A1E466" }}
            />
        </TouchableOpacity>
    );

    const handleConfirm = () => {
        const wanted = [];
        const unwanted = [];
        files.forEach(file => {
            if (selectedIndices[file.index]) {
                wanted.push(file.index);
            } else {
                unwanted.push(file.index);
            }
        });
        onConfirm({ wanted, unwanted });
    };

    const allSelected = files && files.length > 0 && files.every(f => selectedIndices[f.index]);

    return (
        <Modal
            visible={visible}
            transparent={true}
            animationType="slide"
            onRequestClose={onCancel}
        >
            <View style={styles.modalOverlay}>
                <View style={styles.modalContent}>
                    <View style={styles.modalHeader}>
                        <Text style={styles.modalTitle}>Select Files</Text>
                        <TouchableOpacity onPress={() => toggleAll(!allSelected)}>
                            <Text style={styles.selectAllText}>{allSelected ? 'Deselect All' : 'Select All'}</Text>
                        </TouchableOpacity>
                    </View>

                    <FlatList
                        data={files}
                        keyExtractor={item => item.index.toString()}
                        renderItem={renderItem}
                        style={styles.list}
                        ItemSeparatorComponent={() => <View style={styles.separator} />}
                    />

                    <View style={styles.footer}>
                        <TouchableOpacity
                            style={[styles.footerButton, styles.cancelButton]}
                            onPress={onCancel}
                            disabled={isConfirming}
                        >
                            <Text style={styles.cancelButtonText}>Cancel</Text>
                        </TouchableOpacity>
                        <TouchableOpacity
                            style={[styles.footerButton, styles.confirmButton, isConfirming && styles.buttonDisabled]}
                            onPress={handleConfirm}
                            disabled={isConfirming}
                        >
                            {isConfirming ? (
                                <ActivityIndicator color="#FFF" />
                            ) : (
                                <Text style={styles.confirmButtonText}>Start Download</Text>
                            )}
                        </TouchableOpacity>
                    </View>
                </View>
            </View>
        </Modal>
    );
}

const styles = StyleSheet.create({
    modalOverlay: {
        flex: 1,
        backgroundColor: 'rgba(0,0,0,0.8)',
        justifyContent: 'flex-end',
    },
    modalContent: {
        backgroundColor: '#1E1E1E',
        borderTopLeftRadius: 20,
        borderTopRightRadius: 20,
        height: '80%',
        paddingTop: 20,
    },
    modalHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        paddingHorizontal: 20,
        marginBottom: 15,
    },
    modalTitle: {
        color: '#FFF',
        fontSize: 18,
        fontWeight: 'bold',
    },
    selectAllText: {
        color: '#00A1E4',
        fontSize: 14,
        fontWeight: '500',
    },
    list: {
        flex: 1,
    },
    fileItem: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingVertical: 12,
        paddingHorizontal: 20,
    },
    fileInfo: {
        flex: 1,
        marginRight: 10,
    },
    fileName: {
        color: '#E0E0E0',
        fontSize: 14,
        marginBottom: 2,
    },
    fileSize: {
        color: '#888',
        fontSize: 12,
    },
    separator: {
        height: 1,
        backgroundColor: '#333',
    },
    footer: {
        flexDirection: 'row',
        padding: 20,
        paddingBottom: Platform.OS === 'ios' ? 40 : 20,
        borderTopWidth: 1,
        borderTopColor: '#333',
    },
    footerButton: {
        flex: 1,
        height: 48,
        borderRadius: 8,
        justifyContent: 'center',
        alignItems: 'center',
    },
    cancelButton: {
        backgroundColor: 'transparent',
        marginRight: 10,
    },
    cancelButtonText: {
        color: '#AAA',
        fontSize: 16,
        fontWeight: 'bold',
    },
    confirmButton: {
        backgroundColor: '#00A1E4',
        marginLeft: 10,
    },
    confirmButtonText: {
        color: '#FFF',
        fontSize: 16,
        fontWeight: 'bold',
    },
    buttonDisabled: {
        opacity: 0.5,
    }
});
