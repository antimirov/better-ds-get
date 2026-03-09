import React, { useState, useEffect } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet, ActivityIndicator, KeyboardAvoidingView, Platform, Alert } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useSynology } from '../hooks/useSynology';
import { ConnectionState } from '../api/session-manager';
import { resolveQuickConnect } from '../api/quickconnect';

export default function LoginScreen() {
    const { sessionManager, connectionState, isInitializing } = useSynology();
    const [url, setUrl] = useState('');
    const [account, setAccount] = useState('');
    const [password, setPassword] = useState('');
    const [otp, setOtp] = useState('');
    const [needsOtp, setNeedsOtp] = useState(false); // shown only when server demands 2FA
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

    const doConnect = async (withOtp) => {
        let formattedUrl = url.trim();
        let isQuickConnect = false;

        if (!formattedUrl.includes('.') && !formattedUrl.includes(':') && !formattedUrl.includes('http')) {
            isQuickConnect = true;
        } else if (!/^https?:\/\//i.test(formattedUrl)) {
            if (formattedUrl.includes(':5001')) {
                formattedUrl = 'https://' + formattedUrl;
            } else {
                formattedUrl = 'http://' + formattedUrl;
            }
        }

        let finalUrl = formattedUrl;
        if (isQuickConnect) {
            setResolvingQC(true);
            finalUrl = await resolveQuickConnect(formattedUrl);
            setResolvingQC(false);
        }

        await sessionManager.connect(finalUrl, account, password, {
            otp: withOtp || undefined,
            originalAddress: url.trim()
        });
    };

    const handleLogin = async () => {
        if (!url || !account || !password) {
            Alert.alert('Missing Info', 'Please fill in all required fields.');
            return;
        }

        try {
            await AsyncStorage.setItem('saved_login_url', url);
            await AsyncStorage.setItem('saved_login_account', account);
            await AsyncStorage.setItem('saved_login_password', password);

            await doConnect(needsOtp ? otp.trim() : undefined);
        } catch (error) {
            setResolvingQC(false);
            // If error signals OTP is required, show the OTP field
            const msg = (error.message || '').toLowerCase();
            const code = error.code;
            // Synology returns error code 403 with otp_enforce or otp_mismatch
            if (code === 403 || msg.includes('otp') || msg.includes('2-step') || msg.includes('two-factor') || msg.includes('mismatch')) {
                setNeedsOtp(true);
                setOtp('');
                Alert.alert(
                    '2FA Required',
                    needsOtp
                        ? 'The code you entered was incorrect. Please try again.'
                        : 'This account requires two-factor authentication. Please enter the code from your authenticator app.',
                    [{ text: 'OK' }]
                );
            } else {
                Alert.alert('Login Failed', error.message || 'Unknown error occurred');
            }
        }
    };

    const isConnecting = [
        ConnectionState.CONNECTING,
        ConnectionState.RESOLVING_QC,
        ConnectionState.AUTHORIZING,
        ConnectionState.FETCHING_INFO
    ].includes(connectionState) || resolvingQC;

    let connectionText = 'Connecting...';
    if (resolvingQC || connectionState === ConnectionState.RESOLVING_QC) {
        connectionText = 'Resolving QuickConnect...';
    } else if (connectionState === ConnectionState.AUTHORIZING) {
        connectionText = 'Authorizing...';
    } else if (connectionState === ConnectionState.FETCHING_INFO) {
        connectionText = 'Fetching config...';
    }

    if (isInitializing) {
        return (
            <View style={styles.container}>
                <View style={styles.card}>
                    <ActivityIndicator size="large" color="#00A1E4" style={{ marginBottom: 16 }} />
                    <Text style={[styles.title, { marginBottom: 10, fontSize: 22 }]}>Better DS Get</Text>
                    <Text style={[styles.label, { textAlign: 'center' }]}>Restoring session...</Text>
                </View>
            </View>
        );
    }

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

                {needsOtp && (
                    <>
                        <Text style={[styles.label, styles.otpLabel]}>🔐 Authenticator Code</Text>
                        <TextInput
                            style={[styles.input, styles.otpInput]}
                            placeholder="123456"
                            placeholderTextColor="#666"
                            value={otp}
                            onChangeText={setOtp}
                            keyboardType="number-pad"
                            editable={!isConnecting}
                            autoFocus
                            maxLength={8}
                        />
                    </>
                )}

                <TouchableOpacity
                    style={[styles.button, isConnecting && styles.buttonDisabled]}
                    onPress={handleLogin}
                    disabled={isConnecting}
                >
                    {isConnecting ? (
                        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                            <ActivityIndicator color="#FFFFFF" style={{ marginRight: 8 }} />
                            <Text style={styles.buttonText}>{connectionText}</Text>
                        </View>
                    ) : (
                        <Text style={styles.buttonText}>{needsOtp ? 'Verify & Connect' : 'Connect to NAS'}</Text>
                    )}
                </TouchableOpacity>
            </View>
        </KeyboardAvoidingView>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: '#1E1E1E',
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
        color: '#00A1E4',
        textAlign: 'center',
        marginBottom: 30,
    },
    label: {
        color: '#AAAAAA',
        fontSize: 14,
        marginBottom: 6,
        fontWeight: '600',
    },
    otpLabel: {
        color: '#FFB300',
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
    otpInput: {
        borderColor: '#FFB300',
        letterSpacing: 4,
        fontSize: 20,
        textAlign: 'center',
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
