import React, { useState, useEffect } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet, ActivityIndicator, KeyboardAvoidingView, Platform, Alert } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useSynology } from '../hooks/useSynology';
import { ConnectionState } from '../api/session-manager';
import { resolveQuickConnect } from '../api/quickconnect';

export default function LoginScreen() {
    const { sessionManager, connectionState } = useSynology();
    const [url, setUrl] = useState('');
    const [account, setAccount] = useState('');
    const [password, setPassword] = useState('');
    const [otp, setOtp] = useState('');
    const [resolvingQC, setResolvingQC] = useState(false);

    useEffect(() => {
        const loadSavedInput = async () => {
            try {
                const savedUrl = await AsyncStorage.getItem('saved_login_url');
                const savedAccount = await AsyncStorage.getItem('saved_login_account');
                const savedPassword = await AsyncStorage.getItem('saved_login_password');
                if (savedUrl) setUrl(savedUrl);
                if (savedAccount) setAccount(savedAccount);
                if (savedPassword) setPassword(savedPassword);
            } catch (e) {
                // Ignore storage errors
            }
        };
        loadSavedInput();
    }, []);

    const handleLogin = async () => {
        if (!url || !account || !password) {
            Alert.alert('Missing Info', 'Please fill in all required fields.');
            return;
        }

        // Ensure URL has protocol or handle QuickConnect
        let formattedUrl = url.trim();
        let isQuickConnect = false;

        // QuickConnect IDs don't have periods or colons, and aren't IP addresses or standard domains
        if (!formattedUrl.includes('.') && !formattedUrl.includes(':') && !formattedUrl.includes('http')) {
            isQuickConnect = true;
        } else if (!/^https?:\/\//i.test(formattedUrl)) {
            if (formattedUrl.includes(':5001')) {
                formattedUrl = 'https://' + formattedUrl;
            } else {
                formattedUrl = 'http://' + formattedUrl; // Simplistic default
            }
        }

        try {
            await AsyncStorage.setItem('saved_login_url', url); // Save what they literally typed
            await AsyncStorage.setItem('saved_login_account', account);
            await AsyncStorage.setItem('saved_login_password', password);

            let finalUrl = formattedUrl;

            if (isQuickConnect) {
                setResolvingQC(true);
                finalUrl = await resolveQuickConnect(formattedUrl);
                setResolvingQC(false);
            }

            await sessionManager.connect(finalUrl, account, password, {
                otp: otp.trim() || undefined,
                originalAddress: url.trim()
            });
        } catch (error) {
            setResolvingQC(false);
            Alert.alert('Login Failed', error.message || 'Unknown error occurred');
        }
    };

    const isConnecting = connectionState === ConnectionState.CONNECTING || resolvingQC;

    return (
        <KeyboardAvoidingView
            behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
            style={styles.container}
        >
            <View style={styles.card}>
                <Text style={styles.title}>Better DS Get</Text>

                <Text style={styles.label}>Synology URL (IP/QuickConnect)</Text>
                <TextInput
                    style={styles.input}
                    placeholder="e.g. 192.168.1.100:5000"
                    placeholderTextColor="#666"
                    value={url}
                    onChangeText={setUrl}
                    autoCapitalize="none"
                    autoCorrect={false}
                    keyboardType="url"
                    editable={!isConnecting}
                />

                <Text style={styles.label}>Account</Text>
                <TextInput
                    style={styles.input}
                    placeholder="admin"
                    placeholderTextColor="#666"
                    value={account}
                    onChangeText={setAccount}
                    autoCapitalize="none"
                    autoCorrect={false}
                    editable={!isConnecting}
                />

                <Text style={styles.label}>Password</Text>
                <TextInput
                    style={styles.input}
                    placeholder="password"
                    placeholderTextColor="#666"
                    secureTextEntry
                    value={password}
                    onChangeText={setPassword}
                    editable={!isConnecting}
                />

                <Text style={styles.label}>2FA Code (Optional)</Text>
                <TextInput
                    style={styles.input}
                    placeholder="123456"
                    placeholderTextColor="#666"
                    value={otp}
                    onChangeText={setOtp}
                    keyboardType="number-pad"
                    editable={!isConnecting}
                />

                <TouchableOpacity
                    style={[styles.button, isConnecting && styles.buttonDisabled]}
                    onPress={handleLogin}
                    disabled={isConnecting}
                >
                    {isConnecting ? (
                        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                            <ActivityIndicator color="#FFFFFF" style={{ marginRight: 8 }} />
                            <Text style={styles.buttonText}>{resolvingQC ? 'Resolving QuickConnect...' : 'Connecting...'}</Text>
                        </View>
                    ) : (
                        <Text style={styles.buttonText}>Connect to NAS</Text>
                    )}
                </TouchableOpacity>
            </View>
        </KeyboardAvoidingView>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: '#1E1E1E', // Dark mode by default
        justifyContent: 'center',
        padding: 20,
    },
    card: {
        backgroundColor: '#2C2C2C',
        borderRadius: 12,
        padding: 24,
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.3,
        shadowRadius: 8,
        elevation: 5,
    },
    title: {
        fontSize: 28,
        fontWeight: 'bold',
        color: '#00A1E4', // Synology Blue
        textAlign: 'center',
        marginBottom: 30,
    },
    label: {
        color: '#AAAAAA',
        fontSize: 14,
        marginBottom: 6,
        fontWeight: '600',
    },
    input: {
        backgroundColor: '#1E1E1E',
        borderWidth: 1,
        borderColor: '#444',
        borderRadius: 8,
        color: '#FFFFFF',
        padding: 12,
        marginBottom: 16,
        fontSize: 16,
    },
    button: {
        backgroundColor: '#00A1E4',
        borderRadius: 8,
        padding: 16,
        alignItems: 'center',
        marginTop: 10,
    },
    buttonDisabled: {
        backgroundColor: '#005F87',
    },
    buttonText: {
        color: '#FFFFFF',
        fontSize: 18,
        fontWeight: 'bold',
    },
});
