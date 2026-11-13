package com.antimirov.betterdsget;

import com.antimirov.betterdsget.api.Models.StoredCredentials;
import com.antimirov.betterdsget.api.Models.TaskListResult;
import com.antimirov.betterdsget.api.SessionManager;
import com.antimirov.betterdsget.api.SessionManager.ConnectionState;
import com.antimirov.betterdsget.api.SessionManager.CredentialStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for {@link SessionManager}:
 * - Auto-reconnect on session timeout (Error 106)
 * - Retry with backoff on transient busy error (Error 117) without logging out
 * - Credential persistence and session restore
 */
public class SessionManagerIntegrationTest {
    private MockWebServer server;
    private InMemoryStore store;
    private SessionManager sessionManager;

    private static class InMemoryStore implements CredentialStore {
        StoredCredentials savedCreds;
        String activeUrl;
        String activeSid;

        @Override
        public void saveCredentials(StoredCredentials credentials) {
            this.savedCreds = credentials;
        }

        @Override
        public StoredCredentials loadCredentials() {
            return savedCreds;
        }

        @Override
        public void clearCredentials() {
            savedCreds = null;
        }

        @Override
        public void saveActiveSession(String nasUrl, String sid) {
            this.activeUrl = nasUrl;
            this.activeSid = sid;
        }

        @Override
        public void clearActiveSession() {
            this.activeUrl = null;
            this.activeSid = null;
        }
    }

    private static final String DISCOVERY_BODY = """
            {
              "success": true,
              "data": {
                "SYNO.API.Auth": { "path": "entry.cgi", "minVersion": 1, "maxVersion": 6 },
                "SYNO.DownloadStation.Info": { "path": "DownloadStation/info.cgi", "minVersion": 1, "maxVersion": 2 },
                "SYNO.DownloadStation2.Task": { "path": "entry.cgi", "minVersion": 1, "maxVersion": 2 },
                "SYNO.Core.Desktop.Timeout": { "path": "entry.cgi", "minVersion": 1, "maxVersion": 1 }
              }
            }
            """;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        store = new InMemoryStore();
        sessionManager = new SessionManager(store);
        sessionManager.setBaseRetryDelayMs(10L); // Fast retries in tests
    }

    @After
    public void tearDown() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{ \"success\": true }"));
        sessionManager.disconnect(true);
        server.shutdown();
    }

    @Test
    public void testConnectAndTransparentAutoReloginOnError106() throws Exception {
        String baseUrl = server.url("/").toString().replaceAll("/+$", "");

        // 1. Initial connect: discovery -> login (SID_1) -> DS.getInfo
        server.enqueue(new MockResponse().setResponseCode(200).setBody(DISCOVERY_BODY));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{ \"success\": true, \"data\": { \"sid\": \"SID_1\" } }"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{ \"success\": true, \"data\": { \"version\": 3000, \"is_manager\": true } }"));

        sessionManager.connect(baseUrl, "expo", "pass123", null, true, baseUrl);
        assertTrue(sessionManager.isConnected());
        assertEquals("SID_1", sessionManager.getClient().getSid());
        assertNotNull(store.savedCreds);

        // 2. Next operation (listTasks) fails with 106 (Session timeout),
        // triggering auto-relogin -> gets SID_2 -> retries listTasks and succeeds!
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{ \"success\": false, \"error\": { \"code\": 106 } }"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{ \"success\": true, \"data\": { \"sid\": \"SID_2\" } }"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{ \"success\": true, \"data\": { \"total\": 1, \"offset\": 0, \"tasks\": [{ \"id\": \"t1\", \"title\": \"Sample.mkv\", \"status\": \"downloading\" }] } }"
        ));

        TaskListResult result = sessionManager.execute(() ->
                sessionManager.getDs().listTasks(0, 100, Collections.singletonList("transfer"), 5000));

        assertEquals(1, result.tasks.size());
        assertEquals("Sample.mkv", result.tasks.get(0).title);
        assertEquals("SID_2", sessionManager.getClient().getSid());
        assertEquals("SID_2", store.activeSid);
        assertEquals(ConnectionState.CONNECTED, sessionManager.getState());
    }

    @Test
    public void testTransientBusyErrorRetriesWithoutDisconnecting() throws Exception {
        String baseUrl = server.url("/").toString().replaceAll("/+$", "");

        // Connect
        server.enqueue(new MockResponse().setResponseCode(200).setBody(DISCOVERY_BODY));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{ \"success\": true, \"data\": { \"sid\": \"SID_A\" } }"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{ \"success\": true, \"data\": { \"version\": 3000 } }"));
        sessionManager.connect(baseUrl, "expo", "pass123", null, true, baseUrl);

        // First call returns 117 (Busy), second call succeeds
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{ \"success\": false, \"error\": { \"code\": 117 } }"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{ \"success\": true, \"data\": { \"total\": 0, \"offset\": 0, \"tasks\": [] } }"
        ));

        TaskListResult res = sessionManager.execute(() ->
                sessionManager.getDs().listTasks(0, 50, Collections.emptyList(), 5000));

        assertEquals(0, res.total);
        assertTrue(sessionManager.isConnected());
        assertEquals("SID_A", sessionManager.getClient().getSid());
    }
}

