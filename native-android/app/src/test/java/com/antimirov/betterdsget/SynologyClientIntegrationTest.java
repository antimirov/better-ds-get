package com.antimirov.betterdsget;

import com.antimirov.betterdsget.api.Models.LoginResult;
import com.antimirov.betterdsget.api.SynologyClient;
import com.antimirov.betterdsget.api.SynologyError;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
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
}

