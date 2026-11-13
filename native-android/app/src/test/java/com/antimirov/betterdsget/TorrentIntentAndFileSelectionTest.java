package com.antimirov.betterdsget;

import com.antimirov.betterdsget.TorrentIntentHelper.IncomingPayload;
import com.antimirov.betterdsget.api.DownloadStation;
import com.antimirov.betterdsget.api.Models.CreateTaskResult;
import com.antimirov.betterdsget.api.Models.TaskFileItem;
import com.antimirov.betterdsget.api.SynologyClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit & Integration tests for .torrent file intent handling, multipart upload,
 * large multi-file list retrieval (e.g. 4,000+ files), and selective download finalization.
 */
public class TorrentIntentAndFileSelectionTest {
    private MockWebServer server;
    private SynologyClient client;
    private DownloadStation ds;

    private static final String DISCOVERY_BODY = """
            {
              "success": true,
              "data": {
                "SYNO.API.Auth": { "path": "entry.cgi", "minVersion": 1, "maxVersion": 6 },
                "SYNO.DownloadStation2.Task": { "path": "entry.cgi", "minVersion": 1, "maxVersion": 2 },
                "SYNO.DownloadStation2.Task.List": { "path": "entry.cgi", "minVersion": 1, "maxVersion": 2 },
                "SYNO.DownloadStation2.Task.List.Polling": { "path": "entry.cgi", "minVersion": 1, "maxVersion": 2 }
              }
            }
            """;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/").toString().replaceAll("/+$", "");
        client = new SynologyClient(baseUrl);
        server.enqueue(new MockResponse().setResponseCode(200).setBody(DISCOVERY_BODY));
        client.discoverApis();
        client.setSession("SID_TEST_SESSION");
        server.takeRequest(); // consume discovery request
        ds = new DownloadStation(client);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void testTorrentIntentHelperCleanUriString() {
        // 1. Plain magnet link
        String plain = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=Test";
        assertEquals(plain, TorrentIntentHelper.cleanUriString(plain));

        // 2. Encoded magnet link
        String encoded = "magnet%3A%3Fxt%3Durn%3Abtih%3A140F9BA5D98979DAC06EEB47";
        String decoded = TorrentIntentHelper.cleanUriString(encoded);
        assertTrue(decoded.startsWith("magnet:?xt=urn:btih:"));

        // 3. Double-encoded magnet link
        String doubleEncoded = "magnet%253A%253Fxt%253Durn%253Abtih%253A140F9BA5D98979DAC06EEB47";
        String doubleDecoded = TorrentIntentHelper.cleanUriString(doubleEncoded);
        assertTrue(doubleDecoded.startsWith("magnet:?xt=urn:btih:"));

        // 4. Expo-style wrapped deep link
        String expoWrapped = "exp://192.168.1.50:8081/--/magnet:?xt=urn:btih:dexter";
        assertEquals("magnet:?xt=urn:btih:dexter", TorrentIntentHelper.cleanUriString(expoWrapped));

        // 5. Null and empty
        assertEquals("", TorrentIntentHelper.cleanUriString(null));
        assertEquals("", TorrentIntentHelper.cleanUriString(""));
    }

    @Test
    public void testIncomingPayloadModel() {
        byte[] fakeBytes = new byte[]{1, 2, 3, 4, 5};
        IncomingPayload filePayload = new IncomingPayload(true, "/cache/test.torrent", "test.torrent", fakeBytes);
        assertTrue(filePayload.isTorrentFile);
        assertEquals("test.torrent", filePayload.fileName);
        assertEquals("/cache/test.torrent", filePayload.urlOrMagnet);
        assertEquals(5, filePayload.fileBytes.length);

        IncomingPayload magnetPayload = new IncomingPayload(false, "magnet:?xt=urn:btih:123", null, null);
        assertTrue(!magnetPayload.isTorrentFile);
        assertEquals("magnet:?xt=urn:btih:123", magnetPayload.urlOrMagnet);
    }

    @Test
    public void testCreateTaskFromFileMultipartRequest() throws Exception {
        byte[] mockTorrentBytes = "d8:announce31:http://tracker.example.com/announce13:creation datei1794126932e4:infod6:lengthi1048576e4:name12:test_file.mkbee".getBytes(StandardCharsets.ISO_8859_1);

        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {
                  "success": true,
                  "data": {
                    "list_id": ["LIST_ASIMOV_4056"]
                  }
                }
                """));

        CreateTaskResult result = ds.createTaskFromFile(
                "14Isaac_Asimov.torrent",
                mockTorrentBytes,
                "/volume1/downloads/books",
                true
        );

        assertNotNull(result);
        assertEquals(1, result.listIds.size());
        assertEquals("LIST_ASIMOV_4056", result.listIds.get(0));

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        String contentType = req.getHeader("Content-Type");
        assertNotNull(contentType);
        assertTrue(contentType.startsWith("multipart/form-data; boundary="));

        String bodyString = req.getBody().readUtf8();
        assertTrue(bodyString.contains("name=\"api\"\r\n\r\nSYNO.DownloadStation2.Task"));
        assertTrue(bodyString.contains("name=\"method\"\r\n\r\ncreate"));
        assertTrue(bodyString.contains("name=\"version\"\r\n\r\n2"));
        assertEquals("SID_TEST_SESSION", req.getRequestUrl().queryParameter("_sid"));
        assertTrue(bodyString.contains("name=\"file\"\r\n\r\n[\"torrent\"]"));
        assertTrue(bodyString.contains("name=\"type\"\r\n\r\n\"file\""));
        assertTrue(bodyString.contains("name=\"destination\"\r\n\r\n\"volume1/downloads/books\""));
        assertTrue(bodyString.contains("name=\"create_list\"\r\n\r\ntrue"));
        assertTrue(bodyString.contains("name=\"size\"\r\n\r\n" + mockTorrentBytes.length));
        assertTrue(bodyString.contains("filename=\"14Isaac_Asimov.torrent\""));
        assertTrue(bodyString.contains("Content-Type: application/x-bittorrent"));
    }

    @Test
    public void testGetFileListWith4056Files() throws Exception {
        // Build JSON response with 4,056 file entries simulating large multi-file torrent
        final int TOTAL_FILES = 4056;
        JSONObject root = new JSONObject();
        root.put("success", true);
        JSONObject data = new JSONObject();
        JSONArray fileArr = new JSONArray();

        for (int i = 0; i < TOTAL_FILES; i++) {
            JSONObject fileObj = new JSONObject();
            fileObj.put("filename", "Isaac Asimov/Book_" + i + "/Chapter_" + (i % 20) + ".mp3");
            fileObj.put("size", 1024 * 1024 * (i + 1L));
            fileArr.put(fileObj);
        }
        data.put("file", fileArr);
        root.put("data", data);

        server.enqueue(new MockResponse().setResponseCode(200).setBody(root.toString()));

        List<TaskFileItem> items = ds.getFileList("LIST_ASIMOV_4056");

        assertEquals(TOTAL_FILES, items.size());
        assertEquals("Isaac Asimov/Book_0/Chapter_0.mp3", items.get(0).name);
        assertEquals(0, items.get(0).index);
        assertEquals(1024 * 1024L, items.get(0).size);

        assertEquals("Isaac Asimov/Book_4055/Chapter_15.mp3", items.get(4055).name);
        assertEquals(4055, items.get(4055).index);
        assertEquals(1024 * 1024L * 4056, items.get(4055).size);

        RecordedRequest req = server.takeRequest();
        String decoded = URLDecoder.decode(req.getBody().readUtf8(), StandardCharsets.UTF_8.name());
        assertTrue(decoded.contains("api=SYNO.DownloadStation2.Task.List"));
        assertTrue(decoded.contains("method=get"));
        assertTrue(decoded.contains("list_id=\"LIST_ASIMOV_4056\""));
    }

    @Test
    public void testFinalizeSelectiveDownloadWithLargeSelection() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {
                  "success": true,
                  "data": {
                    "task_id": ["dbid_999"]
                  }
                }
                """));

        List<Integer> selectedIndices = Arrays.asList(0, 10, 50, 500, 4055);
        CreateTaskResult res = ds.createTask(
                "",
                "/volume1/downloads/books",
                false,
                "LIST_ASIMOV_4056",
                selectedIndices
        );

        assertNotNull(res);
        assertEquals(1, res.taskIds.size());
        assertEquals("dbid_999", res.taskIds.get(0));

        RecordedRequest req = server.takeRequest();
        String body = URLDecoder.decode(req.getBody().readUtf8(), StandardCharsets.UTF_8.name());
        assertTrue(body.contains("api=SYNO.DownloadStation2.Task.List.Polling"));
        assertTrue(body.contains("method=download"));
        assertTrue(body.contains("version=2"));
        assertTrue(body.contains("list_id=\"LIST_ASIMOV_4056\""));
        assertTrue(body.contains("destination=\"volume1/downloads/books\""));
        assertTrue(body.contains("selected=[0,10,50,500,4055]"));
        assertTrue(body.contains("create_subfolder=true"));
    }
}
