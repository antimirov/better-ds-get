package com.antimirov.betterdsget;

import com.antimirov.betterdsget.api.DownloadStation;
import com.antimirov.betterdsget.api.Models.BtSearchPage;
import com.antimirov.betterdsget.api.Models.CreateTaskResult;
import com.antimirov.betterdsget.api.Models.EngineStatus;
import com.antimirov.betterdsget.api.Models.SearchModule;
import com.antimirov.betterdsget.api.Models.SearchResultItem;
import com.antimirov.betterdsget.api.SearchController;
import com.antimirov.betterdsget.api.SynologyClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for {@link DownloadStation} and {@link SearchController}:
 * - Selective file download ("Super Secret Handshake" via SYNO.DownloadStation2.Task.List.Polling)
 * - Leading slash stripping on destination folders
 * - Per-user BT Search plugin status via SYNO.DownloadStation2.Settings.BTSearch
 * - Search lifecycle: awaiting btSearchClean before btSearchStart(module="enabled"),
 *   per-engine progress synthesis, and sorting by seeds/size
 */
public class DownloadStationAndSearchIntegrationTest {
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
                "SYNO.DownloadStation2.Task.List.Polling": { "path": "entry.cgi", "minVersion": 1, "maxVersion": 2 },
                "SYNO.DownloadStation2.Settings.BTSearch": { "path": "entry.cgi", "minVersion": 1, "maxVersion": 1 },
                "SYNO.DownloadStation.BTSearch": { "path": "DownloadStation/btsearch.cgi", "minVersion": 1, "maxVersion": 1 }
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
        client.setSession("SID_TEST");
        server.takeRequest(); // consume discovery request
        ds = new DownloadStation(client);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void testSelectiveDownloadSuperSecretHandshakeAndSlashStripping() throws Exception {
        // 1. Create URL task with createList=true and leading slash in destination
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{ \"success\": true, \"data\": { \"list_id\": [\"LIST_777\"] } }"
        ));

        CreateTaskResult createRes = ds.createTask(
                "magnet:?xt=urn:btih:dexter",
                "/volume1/video/series",
                true,
                null,
                null
        );
        assertEquals(1, createRes.listIds.size());
        assertEquals("LIST_777", createRes.listIds.get(0));

        RecordedRequest req1 = server.takeRequest();
        String decoded1 = URLDecoder.decode(req1.getBody().readUtf8(), StandardCharsets.UTF_8.name());
        assertTrue("Leading slash must be stripped for DS2 create", decoded1.contains("destination=\"volume1/video/series\""));
        assertTrue(decoded1.contains("create_list=true"));

        // 2. Finalize selective download via SYNO.DownloadStation2.Task.List.Polling
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{ \"success\": true, \"data\": { \"task_id\": [\"dbid_101\"] } }"
        ));

        ds.createTask("", "/volume1/video/series", false, "LIST_777", Arrays.asList(0, 2, 5));
        RecordedRequest req2 = server.takeRequest();
        String decoded2 = URLDecoder.decode(req2.getBody().readUtf8(), StandardCharsets.UTF_8.name());
        assertTrue(decoded2.contains("api=SYNO.DownloadStation2.Task.List.Polling"));
        assertTrue(decoded2.contains("method=download"));
        assertTrue(decoded2.contains("list_id=\"LIST_777\""));
        assertTrue(decoded2.contains("destination=\"volume1/video/series\""));
        assertTrue(decoded2.contains("selected=[0,2,5]"));
    }

    @Test
    public void testPerUserBtSearchModulesAndSearchControllerLifecycle() throws Exception {
        // 1. Mock SYNO.DownloadStation2.Settings.BTSearch returning 1 enabled plugin (TPB) and 2 disabled plugins
        String ds2SettingsJson = """
                {
                  "success": true,
                  "data": {
                    "plugins": [
                      { "name": "thepiratebay", "displayname": "The Pirate Bay", "is_enable": true, "can_connect": true },
                      { "name": "1337x", "displayname": "1337x", "is_enable": false, "can_connect": false },
                      { "name": "eztv", "displayname": "EZTV", "is_enable": false, "can_connect": false }
                    ]
                  }
                }
                """;
        server.enqueue(new MockResponse().setResponseCode(200).setBody(ds2SettingsJson));

        SearchController controller = new SearchController(ds);
        List<SearchModule> allModules = controller.loadModules();
        assertEquals(3, allModules.size());
        assertEquals(1, controller.getEnabledModules().size());
        assertEquals("thepiratebay", controller.getEnabledModules().get(0).id);
        server.takeRequest(); // consume Settings.BTSearch

        // 2. Start first search -> verify module="enabled"
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{ \"success\": true, \"data\": { \"taskid\": \"SEARCH_TASK_1\" } }"
        ));
        String tid1 = controller.startSearch("dexter resurrection");
        assertEquals("SEARCH_TASK_1", tid1);

        RecordedRequest startReq1 = server.takeRequest();
        String startBody1 = URLDecoder.decode(startReq1.getBody().readUtf8(), StandardCharsets.UTF_8.name());
        assertTrue(startBody1.contains("keyword=dexter resurrection"));
        assertTrue("Must pass module=enabled", startBody1.contains("module=enabled"));

        // 3. Poll results -> verify per-engine synthesis and sorting
        String listJson = """
                {
                  "success": true,
                  "data": {
                    "total": 2,
                    "offset": 0,
                    "finished": true,
                    "items": [
                      { "title": "Dexter 720p", "size": 1000000, "seeds": 50, "peers": 10, "download_uri": "magnet:?1", "module_id": "thepiratebay", "module_title": "The Pirate Bay" },
                      { "title": "Dexter 2160p", "size": 9000000, "seeds": 200, "peers": 25, "download_uri": "magnet:?2", "module_id": "thepiratebay", "module_title": "The Pirate Bay" }
                    ]
                  }
                }
                """;
        server.enqueue(new MockResponse().setResponseCode(200).setBody(listJson));

        BtSearchPage page = controller.pollOnce();
        assertTrue(page.finished);
        assertTrue(controller.isFinished());
        assertEquals(1, controller.getDoneEngineCount());
        EngineStatus tpbStatus = controller.getEngineStatuses().get(0);
        assertEquals(2, tpbStatus.items);
        assertTrue(tpbStatus.isDone());
        server.takeRequest(); // consume list

        // Default sort is seeds DESC (200 seeds first, then 50 seeds)
        List<SearchResultItem> sortedBySeedsDesc = controller.getSortedResults();
        assertEquals("Dexter 2160p", sortedBySeedsDesc.get(0).title);
        assertEquals("Dexter 720p", sortedBySeedsDesc.get(1).title);

        // Toggle sort to seeds ASC
        controller.toggleSort("seeds");
        assertFalse(controller.isSortDesc());
        assertEquals("Dexter 720p", controller.getSortedResults().get(0).title);

        // 4. Start second search -> verify old task SEARCH_TASK_1 is cleaned BEFORE starting SEARCH_TASK_2
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{ \"success\": true }")); // clean
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{ \"success\": true, \"data\": { \"taskid\": \"SEARCH_TASK_2\" } }"
        ));

        String tid2 = controller.startSearch("ubuntu");
        assertEquals("SEARCH_TASK_2", tid2);

        RecordedRequest cleanReq = server.takeRequest();
        String cleanBody = URLDecoder.decode(cleanReq.getBody().readUtf8(), StandardCharsets.UTF_8.name());
        assertTrue("btSearchClean must be awaited before starting next search",
                cleanBody.contains("method=clean") && cleanBody.contains("taskid=SEARCH_TASK_1"));

        RecordedRequest startReq2 = server.takeRequest();
        String startBody2 = URLDecoder.decode(startReq2.getBody().readUtf8(), StandardCharsets.UTF_8.name());
        assertTrue(startBody2.contains("method=start") && startBody2.contains("keyword=ubuntu"));
    }
}

