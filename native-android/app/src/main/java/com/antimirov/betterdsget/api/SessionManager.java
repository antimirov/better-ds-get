package com.antimirov.betterdsget.api;

import com.antimirov.betterdsget.api.Models.ConnectionInfo;
import com.antimirov.betterdsget.api.Models.LoginResult;
import com.antimirov.betterdsget.api.Models.StoredCredentials;
import com.antimirov.betterdsget.util.FormatUtils;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the Synology session alive and handles transparent auto-reconnection.
 *
 * Key guarantees:
 * - Periodic keepalive heartbeat (every 2 minutes by default) via SYNO.Core.Desktop.Timeout
 * - Auto-reconnects on session timeout (106/107/119) using stored credentials
 * - Retries transient network errors with exponential backoff without logging the user out
 * - Pluggable {@link CredentialStore} so unit/integration tests can run on the JVM without Android Context
 */
public class SessionManager {
    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        RESOLVING_QC,
        AUTHORIZING,
        FETCHING_INFO,
        CONNECTED,
        RECONNECTING,
        ERROR
    }

    public interface CredentialStore {
        void saveCredentials(StoredCredentials credentials);
        StoredCredentials loadCredentials();
        void clearCredentials();
        void saveActiveSession(String nasUrl, String sid);
        void clearActiveSession();
    }

    public interface StateListener {
        void onStateChanged(ConnectionState newState, ConnectionState previousState, Exception error);
    }

    private final SynologyClient client;
    private final CredentialStore store;
    private volatile DownloadStation ds;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile StoredCredentials credentials;
    private volatile String originalAddress = "";
    private volatile int reconnectAttempt = 0;
    private int maxReconnectAttempts = 3;
    private long keepaliveIntervalMs = 2 * 60 * 1000L;
    private long baseRetryDelayMs = 1000L;

    private final List<StateListener> listeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService keepaliveExecutor;
    private ScheduledFuture<?> keepaliveFuture;

    public SessionManager(CredentialStore store) {
        this(new SynologyClient(""), store);
    }

    public SessionManager(SynologyClient client, CredentialStore store) {
        this.client = client;
        this.store = store;
    }

    public SynologyClient getClient() {
        return client;
    }

    public DownloadStation getDs() {
        return ds;
    }

    public ConnectionState getState() {
        return state;
    }

    public boolean isConnected() {
        return state == ConnectionState.CONNECTED;
    }

    public StoredCredentials getCredentials() {
        return credentials;
    }

    public ConnectionInfo getConnectionInfo() {
        String orig = originalAddress != null ? originalAddress : "";
        boolean isQc = FormatUtils.isQuickConnectId(orig);
        return new ConnectionInfo(
                client.getBaseUrl(),
                orig,
                client.isHttps(),
                client.getSid(),
                isQc
        );
    }

    public void setBaseRetryDelayMs(long baseRetryDelayMs) {
        this.baseRetryDelayMs = Math.max(10L, baseRetryDelayMs);
    }

    public void addStateListener(StateListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeStateListener(StateListener listener) {
        listeners.remove(listener);
    }

    // ── Connection Lifecycle ──────────────────────────────────────

    public LoginResult connect(String url, String account, String password, String otp, boolean rememberMe, String rawAddress)
            throws Exception {
        setState(ConnectionState.CONNECTING, null);
        this.originalAddress = (rawAddress != null && !rawAddress.isEmpty()) ? rawAddress : url;

        try {
            client.setBaseUrl(url);
            setState(ConnectionState.AUTHORIZING, null);
            LoginResult loginResult = client.login(account, password, otp, null, null);

            this.credentials = new StoredCredentials(url, account, password, this.originalAddress);
            if (rememberMe && store != null) {
                store.saveCredentials(this.credentials);
            }

            this.ds = new DownloadStation(client);

            setState(ConnectionState.FETCHING_INFO, null);
            try {
                this.ds.getInfo();
            } catch (Exception e) {
                throw new SynologyError(
                        "Connection failed: " + (e.getMessage() != null ? e.getMessage() : "No Download Station privileges"),
                        e instanceof SynologyError ? ((SynologyError) e).getCode() : -1
                );
            }

            this.reconnectAttempt = 0;
            startKeepalive();
            setState(ConnectionState.CONNECTED, null);

            if (store != null) {
                store.saveActiveSession(url, loginResult.sid);
            }
            return loginResult;
        } catch (Exception e) {
            setState(ConnectionState.ERROR, e);
            throw e;
        }
    }

    public void disconnect(boolean clearSaved) {
        stopKeepalive();
        try {
            client.logout();
        } catch (Exception ignored) {}

        this.ds = null;
        if (clearSaved) {
            this.credentials = null;
            if (store != null) {
                store.clearCredentials();
            }
        }
        if (store != null) {
            store.clearActiveSession();
        }
        setState(ConnectionState.DISCONNECTED, null);
    }

    public boolean tryRestore() {
        if (store == null) return false;
        StoredCredentials saved = store.loadCredentials();
        if (saved == null || saved.url == null || saved.url.isEmpty()) {
            return false;
        }
        try {
            connect(saved.url, saved.account, saved.password, null, true, saved.originalAddress);
            return true;
        } catch (NetworkError netErr) {
            if (FormatUtils.isQuickConnectId(saved.originalAddress)) {
                try {
                    String resolvedUrl = QuickConnectResolver.resolveQuickConnect(saved.originalAddress);
                    connect(resolvedUrl, saved.account, saved.password, null, true, saved.originalAddress);
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Resilient Request Wrapper ──────────────────────────────────

    public <T> T execute(Callable<T> operation) throws Exception {
        return execute(operation, 2);
    }

    public <T> T execute(Callable<T> operation, int maxRetries) throws Exception {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                T result = operation.call();
                reconnectAttempt = 0;
                return result;
            } catch (NetworkError netErr) {
                if (attempt < maxRetries) {
                    long delay = Math.min(baseRetryDelayMs * (1L << attempt), 10000L);
                    sleepQuietly(delay);
                    continue;
                }
                // Exhausted retries — propagate error WITHOUT logging out!
                throw netErr;
            } catch (SynologyError synErr) {
                if (synErr.isSessionError()) {
                    if (attempt < maxRetries && tryReconnect()) {
                        continue;
                    }
                    setState(ConnectionState.DISCONNECTED, synErr);
                    throw synErr;
                }
                if (synErr.isAuthError()) {
                    throw synErr;
                }
                if (synErr.isTransient() && attempt < maxRetries) {
                    long delay = Math.min(baseRetryDelayMs * (1L << attempt), 5000L);
                    sleepQuietly(delay);
                    continue;
                }
                throw synErr;
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    private synchronized boolean tryReconnect() {
        if (credentials == null || reconnectAttempt >= maxReconnectAttempts) {
            return false;
        }
        reconnectAttempt++;
        setState(ConnectionState.RECONNECTING, null);

        try {
            return loginAndUpdateSession(credentials.url, credentials.account, credentials.password, credentials.originalAddress, false);
        } catch (NetworkError netErr) {
            if (FormatUtils.isQuickConnectId(credentials.originalAddress)) {
                try {
                    String resolvedUrl = QuickConnectResolver.resolveQuickConnect(credentials.originalAddress);
                    return loginAndUpdateSession(resolvedUrl, credentials.account, credentials.password, credentials.originalAddress, true);
                } catch (Exception ignored) {
                    // Fall through to normal retry/backoff
                }
            }
            if (reconnectAttempt >= maxReconnectAttempts) {
                setState(ConnectionState.ERROR, netErr);
                return false;
            }
            long delay = Math.min(baseRetryDelayMs * 2L * (1L << reconnectAttempt), 30000L);
            sleepQuietly(delay);
            return tryReconnect();
        } catch (Exception e) {
            if (reconnectAttempt >= maxReconnectAttempts) {
                setState(ConnectionState.ERROR, e);
                return false;
            }
            long delay = Math.min(baseRetryDelayMs * 2L * (1L << reconnectAttempt), 30000L);
            sleepQuietly(delay);
            return tryReconnect();
        }
    }

    private boolean loginAndUpdateSession(String targetUrl, String account, String password, String origAddress, boolean updateStoredCreds)
            throws SynologyError, NetworkError {
        client.setBaseUrl(targetUrl);
        LoginResult res = client.login(account, password, null, null, null);
        this.ds = new DownloadStation(client);
        if (updateStoredCreds) {
            this.credentials = new StoredCredentials(targetUrl, account, password, origAddress);
            if (store != null) {
                store.saveCredentials(this.credentials);
            }
        }
        if (store != null) {
            store.saveActiveSession(targetUrl, res.sid);
        }
        setState(ConnectionState.CONNECTED, null);
        reconnectAttempt = 0;
        return true;
    }

    // ── Keepalive ──────────────────────────────────────────────────

    private synchronized void startKeepalive() {
        stopKeepalive();
        keepaliveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SynologyKeepalive");
            t.setDaemon(true);
            return t;
        });
        keepaliveFuture = keepaliveExecutor.scheduleAtFixedRate(() -> {
            try {
                boolean alive = client.testSession();
                if (!alive) {
                    tryReconnect();
                }
            } catch (NetworkError ignored) {
                // Transient network warning, retry next tick
            }
        }, keepaliveIntervalMs, keepaliveIntervalMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopKeepalive() {
        if (keepaliveFuture != null) {
            keepaliveFuture.cancel(true);
            keepaliveFuture = null;
        }
        if (keepaliveExecutor != null) {
            keepaliveExecutor.shutdownNow();
            keepaliveExecutor = null;
        }
    }

    private void setState(ConnectionState newState, Exception error) {
        ConnectionState prev = this.state;
        this.state = newState;
        for (StateListener l : listeners) {
            try {
                l.onStateChanged(newState, prev, error);
            } catch (Exception ignored) {}
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

