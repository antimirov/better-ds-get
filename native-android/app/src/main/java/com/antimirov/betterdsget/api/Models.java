package com.antimirov.betterdsget.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Data models for Synology DSM and Download Station entities.
 */
public final class Models {
    private Models() {}

    public static class ApiInfo {
        public final String path;
        public final int minVersion;
        public final int maxVersion;

        public ApiInfo(String path, int minVersion, int maxVersion) {
            this.path = path;
            this.minVersion = minVersion;
            this.maxVersion = maxVersion;
        }
    }

    public static class LoginResult {
        public final String sid;
        public final String deviceId;
        public final boolean isAdmin;

        public LoginResult(String sid, String deviceId, boolean isAdmin) {
            this.sid = sid;
            this.deviceId = deviceId;
            this.isAdmin = isAdmin;
        }
    }

    public static class StoredCredentials {
        public final String url;
        public final String account;
        public final String password;
        public final String originalAddress;

        public StoredCredentials(String url, String account, String password, String originalAddress) {
            this.url = url;
            this.account = account;
            this.password = password;
            this.originalAddress = originalAddress != null ? originalAddress : url;
        }
    }

    public static class ConnectionInfo {
        public final String baseUrl;
        public final String originalAddress;
        public final boolean isHttps;
        public final String sid;
        public final boolean isQuickConnect;

        public ConnectionInfo(String baseUrl, String originalAddress, boolean isHttps, String sid, boolean isQuickConnect) {
            this.baseUrl = baseUrl;
            this.originalAddress = originalAddress;
            this.isHttps = isHttps;
            this.sid = sid;
            this.isQuickConnect = isQuickConnect;
        }
    }

    public static class TransferStats {
        public final long speedDownload;
        public final long speedUpload;

        public TransferStats(long speedDownload, long speedUpload) {
            this.speedDownload = speedDownload;
            this.speedUpload = speedUpload;
        }
    }

    public static class FolderEntry {
        public final String name;
        public final String path;

        public FolderEntry(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    public static class TrackerItem {
        public final String url;
        public final String status;
        public final int seeds;
        public final int peers;

        public TrackerItem(String url, String status, int seeds, int peers) {
            this.url = url != null ? url : "";
            this.status = status != null ? status : "";
            this.seeds = seeds;
            this.peers = peers;
        }
    }

    public static class PeerItem {
        public final String address;
        public final String agent;
        public final double progress;
        public final long speedDownload;
        public final long speedUpload;

        public PeerItem(String address, String agent, double progress, long speedDownload, long speedUpload) {
            this.address = address != null ? address : "";
            this.agent = agent != null ? agent : "";
            this.progress = progress;
            this.speedDownload = speedDownload;
            this.speedUpload = speedUpload;
        }
    }

    public static class TaskFileItem {
        public final int index;
        public final String name;
        public final long size;
        public final long sizeDownloaded;
        public boolean wanted;
        public final String priority;

        public TaskFileItem(int index, String name, long size, long sizeDownloaded, boolean wanted, String priority) {
            this.index = index;
            this.name = name != null ? name : "";
            this.size = size;
            this.sizeDownloaded = sizeDownloaded;
            this.wanted = wanted;
            this.priority = priority != null ? priority : "normal";
        }
    }

    public static class TaskItem {
        public String id = "";
        public String title = "";
        public String type = "unknown";
        public String status = "unknown";
        public long size = 0;
        public String username = "";

        public String destination = "";
        public String uri = "";
        public long createTime = 0;
        public long completedTime = 0;
        public long startedTime = 0;
        public long waitingSeconds = 0;
        public String priority = "auto";

        public int totalPieces = 0;
        public long pieceLength = 0;

        public int connectedSeeder = 0;
        public int connectedLeecher = 0;
        public int unconnectedSeeder = 0;
        public int unconnectedPeers = 0;
        public int totalPeers = 0;

        public long sizeDownloaded = 0;
        public long sizeUploaded = 0;
        public long speedDownload = 0;
        public long speedUpload = 0;
        public int downloadedPieces = 0;
        public long eta = 0;

        public String errorDetail = "";
        public List<TrackerItem> trackers = new ArrayList<>();
        public List<PeerItem> peers = new ArrayList<>();
    }

    public static class TaskListResult {
        public final int total;
        public final int offset;
        public final List<TaskItem> tasks;

        public TaskListResult(int total, int offset, List<TaskItem> tasks) {
            this.total = total;
            this.offset = offset;
            this.tasks = tasks;
        }
    }

    public static class CreateTaskResult {
        public final List<String> taskIds;
        public final List<String> listIds;

        public CreateTaskResult(List<String> taskIds, List<String> listIds) {
            this.taskIds = taskIds;
            this.listIds = listIds;
        }
    }

    public static class SearchModule {
        public final String id;
        public final String title;
        public final boolean enabled;
        public final boolean canConnect;

        public SearchModule(String id, String title, boolean enabled, boolean canConnect) {
            this.id = id;
            this.title = title != null && !title.isEmpty() ? title : id;
            this.enabled = enabled;
            this.canConnect = canConnect;
        }
    }

    public static class EngineStatus {
        public final String module;
        public final String moduleTitle;
        public String status; // "waiting", "searching", "finished"
        public int items;

        public EngineStatus(String module, String moduleTitle, String status, int items) {
            this.module = module;
            this.moduleTitle = moduleTitle != null && !moduleTitle.isEmpty() ? moduleTitle : module;
            this.status = status;
            this.items = items;
        }

        public boolean isDone() {
            return "finished".equalsIgnoreCase(status) || "complete".equalsIgnoreCase(status);
        }
    }

    public static class SearchResultItem {
        public final String title;
        public final long size;
        public final int seeds;
        public final int peers;
        public final int leechs;
        public final String downloadUri;
        public final String externalLink;
        public final String moduleId;
        public final String moduleTitle;
        public final String date;

        public SearchResultItem(String title, long size, int seeds, int peers, int leechs,
                                String downloadUri, String externalLink, String moduleId,
                                String moduleTitle, String date) {
            this.title = title != null ? title : "";
            this.size = size;
            this.seeds = seeds;
            this.peers = peers;
            this.leechs = leechs;
            this.downloadUri = downloadUri != null ? downloadUri : "";
            this.externalLink = externalLink != null ? externalLink : "";
            this.moduleId = moduleId != null ? moduleId : "";
            this.moduleTitle = moduleTitle != null && !moduleTitle.isEmpty() ? moduleTitle : this.moduleId;
            this.date = date != null ? date : "";
        }
    }

    public static class BtSearchPage {
        public final int total;
        public final int offset;
        public final boolean finished;
        public final List<SearchResultItem> items;

        public BtSearchPage(int total, int offset, boolean finished, List<SearchResultItem> items) {
            this.total = total;
            this.offset = offset;
            this.finished = finished;
            this.items = items;
        }
    }
}

