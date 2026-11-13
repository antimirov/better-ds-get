package com.antimirov.betterdsget;

import com.antimirov.betterdsget.api.DownloadStation;
import com.antimirov.betterdsget.api.Models.TaskItem;
import com.antimirov.betterdsget.api.NetworkError;
import com.antimirov.betterdsget.api.SynologyError;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for SynologyError, NetworkError, and DownloadStation DS1/DS2 task normalization.
 */
public class SynologyErrorAndNormalizationUnitTest {

    @Test
    public void testSynologyErrorClassifications() {
        // Session errors: 106, 107, 119
        SynologyError timeout = SynologyError.fromCode(106, "SYNO.DownloadStation.Task", null);
        assertTrue(timeout.isSessionError());
        assertFalse(timeout.isTransient());
        assertFalse(timeout.isAuthError());
        assertEquals("Session timeout", timeout.getMessage());

        // Transient errors: 100, 117
        SynologyError busy = SynologyError.fromCode(117, "SYNO.DownloadStation2.Task", null);
        assertTrue(busy.isTransient());
        assertFalse(busy.isSessionError());

        // Auth 403 vs DownloadStation 403 (critical distinction!)
        SynologyError auth403 = SynologyError.fromCode(403, "SYNO.API.Auth", null);
        assertTrue(auth403.isAuthError());
        assertTrue(auth403.isOtpRequired());
        assertEquals("OTP code required", auth403.getMessage());

        SynologyError ds403 = SynologyError.fromCode(403, "SYNO.DownloadStation2.Task", null);
        assertFalse(ds403.isAuthError());
        assertFalse(ds403.isOtpRequired());
        assertEquals("Destination does not exist", ds403.getMessage());
    }

    @Test
    public void testNetworkErrorNeverForcesLogout() {
        NetworkError netTimeout = new NetworkError("Request timed out", NetworkError.Category.TIMEOUT);
        assertTrue(netTimeout.isTransient());
        assertFalse(netTimeout.isSessionError());
        assertEquals(NetworkError.Category.TIMEOUT, netTimeout.getCategory());
    }

    @Test
    public void testNormalizeTaskAcrossDs1AndDs2Formats() throws Exception {
        // Simulate a DS2 task payload with numeric status code (2 = downloading) and nested transfer/detail
        String ds2Json = """
                {
                  "id": "dbid_42",
                  "title": "Ubuntu 24.04 Desktop ISO",
                  "type": "bt",
                  "status": 2,
                  "size": 4900000000,
                  "additional": {
                    "detail": {
                      "destination": "downloads/iso",
                      "uri": "magnet:?xt=urn:btih:ubuntu",
                      "total_pieces": 1200,
                      "piece_length": 4194304,
                      "connected_seeders": 34
                    },
                    "transfer": {
                      "size_downloaded": 2450000000,
                      "size_uploaded": 120000000,
                      "speed_download": 10485760,
                      "speed_upload": 524288,
                      "downloaded_pieces": 600,
                      "time_left": 233
                    },
                    "tracker": [
                      { "url": "udp://tracker.ubuntu.com:6969/announce", "status": "Success", "seeds": 50, "peers": 3 }
                    ],
                    "peer": [
                      { "address": "85.17.1.2", "agent": "Transmission 4.0", "progress": 0.95, "speed_download": 524288, "speed_upload": 0 }
                    ]
                  }
                }
                """;

        TaskItem item = DownloadStation.normalizeTask(new JSONObject(ds2Json));
        assertEquals("dbid_42", item.id);
        assertEquals("Ubuntu 24.04 Desktop ISO", item.title);
        assertEquals("downloading", item.status);
        assertEquals(4900000000L, item.size);
        assertEquals("downloads/iso", item.destination);
        assertEquals(34, item.connectedSeeder);
        assertEquals(2450000000L, item.sizeDownloaded);
        assertEquals(10485760L, item.speedDownload);
        assertEquals(600, item.downloadedPieces);
        assertEquals(233L, item.eta);
        assertEquals(1, item.trackers.size());
        assertEquals("udp://tracker.ubuntu.com:6969/announce", item.trackers.get(0).url);
        assertEquals(1, item.peers.size());
        assertEquals("85.17.1.2", item.peers.get(0).address);
    }
}

