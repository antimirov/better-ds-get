package com.antimirov.betterdsget;

import com.antimirov.betterdsget.api.Models.LoginResult;
import com.antimirov.betterdsget.api.QuickConnectResolver;
import com.antimirov.betterdsget.api.SynologyClient;
import com.antimirov.betterdsget.api.SynologyError;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Integration tests for {@link SynologyClient} against a local {@link MockWebServer}
 * simulating Synology DSM WebAPI endpoints.
 */
public class SynologyClientIntegrationTest {
    private MockWebServer server;
    private SynologyClient client;

    private static final String MOCK_DISCOVERY_JSON = """
            {
              "success": true,
              "data": {
                "SYNO.API.Auth": { "path": "entry.cgi", "minVersion": 1, "maxVersion": 6 },
                "SYNO.DownloadStation2.Task": { "path": "entry.cgi", "minVersion": 1, "maxVersion": 2 },
                "SYNO.Core.Desktop.Timeout": { "path": "entry.cgi", "minVersion": 1, "maxVersion": 1 }
              }
            }
            """;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/").toString().replaceAll("/+$", "");
        client = new SynologyClient(baseUrl);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void testApiDiscoveryAndLoginWithOtp() throws Exception {
        // 1. Discovery response
        server.enqueue(new MockResponse().setResponseCode(200).setBody(MOCK_DISCOVERY_JSON));
        // 2. First login returns 403 OTP required
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{ \"success\": false, \"error\": { \"code\": 403 } }"
        ));
        // 3. Second login with OTP succeeds
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{ \"success\": true, \"data\": { \"sid\": \"SID_998877\", \"device_id\": \"DEV_1\", \"is_admin\": true } }"
        ));

        try {
            client.login("turist", "secret", null, null, null);
            fail("Expected SynologyError 403 for OTP");
        } catch (SynologyError e) {
            assertEquals(403, e.getCode());
            assertTrue(e.isOtpRequired());
        }

        LoginResult loginResult = client.login("turist", "secret", "654321", "Pixel", null);
        assertEquals("SID_998877", loginResult.sid);
        assertTrue(client.isAuthenticated());
        assertTrue(client.supportsDS2());

        // Inspect recorded requests: 1st = discovery, 2nd = login without OTP, 3rd = login with otp_code
        server.takeRequest(); // discovery
        server.takeRequest(); // 1st login
        RecordedRequest otpLoginReq = server.takeRequest();
        String body = otpLoginReq.getBody().readUtf8();
        assertTrue(body.contains("otp_code=654321"));
        assertTrue(body.contains("version=6"));
    }

    @Test
    public void testStrictByteOrderingInMultipartTorrentUpload() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(MOCK_DISCOVERY_JSON));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{ \"success\": true, \"data\": { \"task_id\": [\"dbid_1\"] } }"
        ));

        client.discoverApis();
        client.setSession("SID_UPLOAD_TEST");

        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("file", "[\"torrent\"]");
        params.put("type", "\"file\"");
        params.put("destination", "\"downloads\"");
        params.put("create_list", "true");
        params.put("size", "11");

        byte[] torrentBytes = "d8:announce".getBytes(StandardCharsets.UTF_8);
        client.requestMultipart(
                "SYNO.DownloadStation2.Task",
                "create",
                2,
                params,
                "torrent",
                "linux.torrent",
                "application/x-bittorrent",
                torrentBytes
        );

        server.takeRequest(); // discovery
        RecordedRequest uploadReq = server.takeRequest();
        assertTrue(uploadReq.getPath().contains("_sid=SID_UPLOAD_TEST"));

        String rawMultipart = uploadReq.getBody().readUtf8();
        int idxApi = rawMultipart.indexOf("name=\"api\"");
        int idxVersion = rawMultipart.indexOf("name=\"version\"");
        int idxMethod = rawMultipart.indexOf("name=\"method\"");
        int idxSize = rawMultipart.indexOf("name=\"size\"");
        int idxTorrentFile = rawMultipart.indexOf("name=\"torrent\"; filename=\"linux.torrent\"");

        assertTrue("api field must be present", idxApi >= 0);
        assertTrue("api must precede version", idxApi < idxVersion);
        assertTrue("version must precede method", idxVersion < idxMethod);
        assertTrue("method must precede size", idxMethod < idxSize);
        assertTrue("file field MUST come last after all metadata fields", idxSize < idxTorrentFile);
        assertTrue("binary payload must be intact", rawMultipart.contains("d8:announce"));
    }

    @Test
    public void testHtml504GatewayTimeoutAnd400BadRequestDetection() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(MOCK_DISCOVERY_JSON));
        client.discoverApis();

        // Simulate nginx 504 HTML response when a search scraper hangs
        server.enqueue(new MockResponse()
                .setResponseCode(504)
                .setBody("<!DOCTYPE html><html><head><title>504 Gateway Time-out</title></head><body>nginx</body></html>"));

        try {
            client.request("SYNO.DownloadStation2.Task", "list", 2, new LinkedHashMap<>());
            fail("Expected SynologyError on 504 HTML");
        } catch (SynologyError e) {
            assertTrue(e.getMessage().contains("504 Gateway Time-out"));
        }

        // Simulate 400 Bad Request HTML when sending HTTP to HTTPS port 5001
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setBody("<html><head><title>400 Bad Request</title></head><body>Plain HTTP sent to HTTPS port</body></html>"));

        try {
            client.request("SYNO.DownloadStation2.Task", "list", 2, new LinkedHashMap<>());
            fail("Expected SynologyError on 400 HTML");
        } catch (SynologyError e) {
            assertTrue(e.getMessage().contains("400 Bad Request"));
        }
    }

    @Test
    public void testConfigureTrustAllHttpsAndQuickConnectResolverSslWiring() throws Exception {
        HttpsURLConnection httpsConn = (HttpsURLConnection) new URL("https://localhost:5001/webapi/query.cgi").openConnection();
        SynologyClient.configureTrustAllHttps(httpsConn);

        assertNotNull("Custom trust-all SSLSocketFactory must be installed", httpsConn.getSSLSocketFactory());
        assertTrue("HostnameVerifier must accept QuickConnect LAN/DDNS certificates",
                httpsConn.getHostnameVerifier().verify("192-168-1-50.myqcid.direct.quickconnect.to", null));

        String qcResolverContent = new String(Files.readAllBytes(
                new File("src/main/java/com/antimirov/betterdsget/api/QuickConnectResolver.java").toPath()));
        assertTrue("QuickConnectResolver must configure trust-all HTTPS on HttpsURLConnection probes",
                qcResolverContent.contains("SynologyClient.configureTrustAllHttps((HttpsURLConnection) conn)"));

        String sessionMgrContent = new String(Files.readAllBytes(
                new File("src/main/java/com/antimirov/betterdsget/api/SessionManager.java").toPath()));
        assertTrue("SessionManager must re-resolve QuickConnect on restore NetworkError",
                sessionMgrContent.contains("FormatUtils.isQuickConnectId(saved.originalAddress)"));
        assertTrue("SessionManager must re-resolve QuickConnect on reconnect NetworkError",
                sessionMgrContent.contains("FormatUtils.isQuickConnectId(credentials.originalAddress)"));
    }

    @Test
    public void testQuickConnectDirectAndRelayCandidateExtractionAndTunnelFallback() throws Exception {
        JSONObject serverInfo = new JSONObject("""
                {
                  "interface": [{"ip": "192.168.1.14"}],
                  "external": {"ip": "87.209.243.155"},
                  "ddns": "nas.synology.me"
                }
                """);
        JSONObject serviceInfo = new JSONObject("""
                {
                  "port": 5001,
                  "ext_port": 0,
                  "relay_dn": "synr-de7.NAS.direct.quickconnect.to",
                  "relay_ip": "195.181.170.81",
                  "relay_port": 28582
                }
                """);

        List<String> direct = QuickConnectResolver.extractDirectCandidates(serverInfo, serviceInfo);
        assertTrue(direct.contains("https://192.168.1.14:5001"));
        assertTrue(direct.contains("http://192.168.1.14:5000"));
        assertTrue("Must not send plain HTTP to HTTPS port 5001", !direct.contains("http://192.168.1.14:5001"));
        assertTrue(direct.contains("https://87.209.243.155:5001"));
        assertTrue(direct.contains("https://nas.synology.me:5001"));

        List<String> relay = QuickConnectResolver.extractRelayCandidates(serviceInfo);
        assertEquals(2, relay.size());
        assertEquals("https://synr-de7.NAS.direct.quickconnect.to:28582", relay.get(0));
        assertEquals("https://195.181.170.81:28582", relay.get(1));

        // Verify request_tunnel is called when get_server_info has no active relay_port and direct fails
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {
                  "errno": 0,
                  "env": {"control_host": "dec.quickconnect.to"},
                  "server": {"interface": [], "external": {}, "ddns": "null"},
                  "service": {"port": 5001, "ext_port": 0, "relay_port": 0}
                }
                """));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {
                  "errno": 0,
                  "service": {"relay_dn": "", "relay_port": 0}
                }
                """));

        String mockServUrl = server.url("/Serv.php").toString();
        try {
            QuickConnectResolver.resolveQuickConnect("testqcid", mockServUrl);
            fail("Expected exception when both direct and relay fail");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("direct or relay"));
        }

        RecordedRequest infoReq = server.takeRequest();
        assertTrue(infoReq.getBody().readUtf8().contains("\"command\":\"get_server_info\""));
        RecordedRequest tunnelReq = server.takeRequest();
        assertTrue(tunnelReq.getBody().readUtf8().contains("\"command\":\"request_tunnel\""));
    }
}

