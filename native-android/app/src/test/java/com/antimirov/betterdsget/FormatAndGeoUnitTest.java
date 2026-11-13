package com.antimirov.betterdsget;

import com.antimirov.betterdsget.api.Models.TaskItem;
import com.antimirov.betterdsget.util.FormatUtils;
import com.antimirov.betterdsget.util.GeolocationHelper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for formatting, URL normalization, DSM quirk display logic, and flag emojis.
 */
public class FormatAndGeoUnitTest {

    @Test
    public void testFormatBytes() {
        assertEquals("0 B", FormatUtils.formatBytes(0));
        assertEquals("0 B", FormatUtils.formatBytes(-50));
        assertEquals("512 B", FormatUtils.formatBytes(512));
        assertEquals("1 KB", FormatUtils.formatBytes(1024));
        assertEquals("1.5 KB", FormatUtils.formatBytes(1536));
        assertEquals("2.25 MB", FormatUtils.formatBytes((long) (2.25 * 1024 * 1024)));
        assertEquals("1.5 GB", FormatUtils.formatBytes((long) (1.5 * 1024 * 1024 * 1024)));
    }

    @Test
    public void testFormatSpeed() {
        assertEquals("0 KB/s", FormatUtils.formatSpeed(0));
        assertEquals("500.0 KB/s", FormatUtils.formatSpeed(500 * 1024L));
        assertEquals("2.0 MB/s", FormatUtils.formatSpeed(2 * 1024 * 1024L));
    }

    @Test
    public void testFormatEta() {
        assertEquals("Unknown", FormatUtils.formatEta(-1));
        assertEquals("Finished", FormatUtils.formatEta(0));
        assertEquals("45s", FormatUtils.formatEta(45));
        assertEquals("2m 5s", FormatUtils.formatEta(125));
        assertEquals("1h 1m 1s", FormatUtils.formatEta(3661));
        assertEquals("1d 2h", FormatUtils.formatEta(86400 + 7200));
    }

    @Test
    public void testFormatPiecesAndDsmQuirks() {
        // 1. Finished task where DSM returns downloadedPieces = 0 should show Total / Total
        TaskItem finished = new TaskItem();
        finished.status = "finished";
        finished.totalPieces = 14857;
        finished.downloadedPieces = 0;
        assertEquals("14857 / 14857", FormatUtils.formatPieces(finished));

        // 2. Stalled active task where sizeDownloaded > 0 but downloadedPieces == 0 shows DSM quirk
        TaskItem stalled = new TaskItem();
        stalled.status = "downloading";
        stalled.sizeDownloaded = 1048576;
        stalled.downloadedPieces = 0;
        stalled.totalPieces = 500;
        assertEquals("Unknown (DSM quirk)", FormatUtils.formatPieces(stalled));

        // 3. Normal downloading task with piece length
        TaskItem active = new TaskItem();
        active.status = "downloading";
        active.downloadedPieces = 120;
        active.totalPieces = 400;
        active.pieceLength = 4 * 1024 * 1024L;
        assertEquals("120 / 400 x 4 MB", FormatUtils.formatPieces(active));
    }

    @Test
    public void testPeersFormattingAndFallback() {
        TaskItem active = new TaskItem();
        active.status = "downloading";
        active.connectedSeeder = 12;
        active.connectedLeecher = 5;
        active.totalPeers = 0; // Missing from API
        assertEquals("12 seeds / 5 leechers", FormatUtils.formatConnectedPeers(active));
        assertEquals(17, FormatUtils.computeTotalPeers(active));

        TaskItem paused = new TaskItem();
        paused.status = "paused";
        paused.connectedSeeder = 0;
        paused.unconnectedSeeder = 8;
        paused.connectedLeecher = 0;
        paused.unconnectedPeers = 3;
        assertEquals("8 seeds / 3 peers", FormatUtils.formatConnectedPeers(paused));
    }

    @Test
    public void testUrlAndQuickConnectDetection() {
        assertTrue(FormatUtils.isQuickConnectId("myhome-nas"));
        assertFalse(FormatUtils.isQuickConnectId("192.168.1.100:5000"));
        assertFalse(FormatUtils.isQuickConnectId("https://nas.local:5001"));
        assertFalse(FormatUtils.isQuickConnectId(""));

        assertEquals("http://192.168.1.100:5000", FormatUtils.normalizeDirectUrl("192.168.1.100:5000/"));
        assertEquals("https://192.168.1.100:5001", FormatUtils.normalizeDirectUrl("192.168.1.100:5001"));
        assertEquals("https://nas.example.com:5001", FormatUtils.normalizeDirectUrl("https://nas.example.com:5001///"));

        assertEquals("volume1/downloads", FormatUtils.stripLeadingSlash("/volume1/downloads"));
        assertEquals("volume1/downloads", FormatUtils.stripLeadingSlash("volume1/downloads"));
        assertNull(FormatUtils.stripLeadingSlash(null));
    }

    @Test
    public void testMaskSidAndFlagEmoji() {
        assertEquals("N/A", FormatUtils.maskSid(null));
        assertEquals("****", FormatUtils.maskSid("short"));
        assertEquals("abcd...wxyz", FormatUtils.maskSid("abcd1234567890wxyz"));

        assertEquals("🇺🇸", GeolocationHelper.getFlagEmoji("US"));
        assertEquals("🇳🇱", GeolocationHelper.getFlagEmoji("nl"));
        assertEquals("🇺🇦", GeolocationHelper.getFlagEmoji("UA"));
        assertEquals("", GeolocationHelper.getFlagEmoji(""));
        assertEquals("", GeolocationHelper.getFlagEmoji("USA"));
    }

    @Test
    public void testCleanIntentUriString() {
        String doubleEncoded = "magnet%253A%253Fxt%253Durn%253Abtih%253A12345";
        assertEquals("magnet:?xt=urn:btih:12345", TorrentIntentHelper.cleanUriString(doubleEncoded));

        String wrapped = "exp://127.0.0.1:8081/--/magnet:?xt=urn:btih:abcdef";
        assertEquals("magnet:?xt=urn:btih:abcdef", TorrentIntentHelper.cleanUriString(wrapped));
    }
}

