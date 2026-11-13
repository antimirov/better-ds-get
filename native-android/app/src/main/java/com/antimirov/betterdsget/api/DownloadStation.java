package com.antimirov.betterdsget.api;

import com.antimirov.betterdsget.api.Models.BtSearchPage;
import com.antimirov.betterdsget.api.Models.CreateTaskResult;
import com.antimirov.betterdsget.api.Models.FolderEntry;
import com.antimirov.betterdsget.api.Models.PeerItem;
import com.antimirov.betterdsget.api.Models.SearchModule;
import com.antimirov.betterdsget.api.Models.SearchResultItem;
import com.antimirov.betterdsget.api.Models.TaskFileItem;
import com.antimirov.betterdsget.api.Models.TaskItem;
import com.antimirov.betterdsget.api.Models.TaskListResult;
import com.antimirov.betterdsget.api.Models.TrackerItem;
import com.antimirov.betterdsget.api.Models.TransferStats;
import com.antimirov.betterdsget.util.FormatUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * High-level API for Synology Download Station (supporting both legacy DS1 and modern DS2 APIs).
 */
public class DownloadStation {
    private final SynologyClient client;

    public static final Map<Integer, String> STATUS_MAP = new HashMap<>();

    static {
        STATUS_MAP.put(1, "waiting");
        STATUS_MAP.put(2, "downloading");
        STATUS_MAP.put(3, "paused");
        STATUS_MAP.put(4, "finishing");
        STATUS_MAP.put(5, "finished");
        STATUS_MAP.put(6, "hash_checking");
        STATUS_MAP.put(8, "seeding");
        STATUS_MAP.put(9, "filehosting_waiting");
        STATUS_MAP.put(10, "extracting");
        STATUS_MAP.put(11, "extracting");
        STATUS_MAP.put(12, "extracting");
        STATUS_MAP.put(13, "extracting");
        STATUS_MAP.put(14, "extracting");
        STATUS_MAP.put(15, "extracting");
        STATUS_MAP.put(100, "error");
        STATUS_MAP.put(101, "network_error");
        STATUS_MAP.put(102, "permission_denied");
        STATUS_MAP.put(103, "destination_error");
        STATUS_MAP.put(104, "destination_is_out_of_space");
        STATUS_MAP.put(105, "file_not_exist");
        STATUS_MAP.put(106, "account_error");
        STATUS_MAP.put(107, "torrent_invalid");
        STATUS_MAP.put(108, "torrent_duplicate");
        STATUS_MAP.put(109, "quota_reached");
        STATUS_MAP.put(110, "not_supported");
        STATUS_MAP.put(111, "bt_max_task");
        STATUS_MAP.put(112, "emule_max_task");
        STATUS_MAP.put(113, "nzb_max_task");
        STATUS_MAP.put(114, "http_max_task");
        STATUS_MAP.put(115, "bad_request");
        STATUS_MAP.put(116, "need_unzip_password");
    }

    public DownloadStation(SynologyClient client) {
        this.client = client;
    }

    public SynologyClient getClient() {
        return client;
    }

    // ── Info & Config ──────────────────────────────────────────────

    public JSONObject getInfo() throws SynologyError, NetworkError {
        return client.request("SYNO.DownloadStation.Info", "getinfo", 1, Collections.emptyMap());
    }

    public String getDefaultDestination() throws SynologyError, NetworkError {
        JSONObject res;
        if (client.isApiSupported("SYNO.DownloadStation2.Settings.Location", 1)) {
            res = client.request("SYNO.DownloadStation2.Settings.Location", "get", 1, Collections.emptyMap());
        } else {
            res = client.request("SYNO.DownloadStation.Info", "getconfig", 1, Collections.emptyMap());
        }
        return res.optString("default_destination", "");
    }

    public void setDefaultDestination(String destinationPath) throws SynologyError, NetworkError {
        String cleanPath = FormatUtils.stripLeadingSlash(destinationPath != null ? destinationPath : "");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("default_destination", cleanPath);
        if (client.isApiSupported("SYNO.DownloadStation2.Settings.Location", 1)) {
            client.request("SYNO.DownloadStation2.Settings.Location", "set", 1, params);
        } else {
            client.request("SYNO.DownloadStation.Info", "setserverconfig", 1, params);
        }
    }

    public List<FolderEntry> listFolders(String folderPath) throws SynologyError, NetworkError {
        List<FolderEntry> entries = new ArrayList<>();
        if (folderPath == null || folderPath.isEmpty()) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("additional", "real_path,owner,time");
            JSONObject res = client.request("SYNO.FileStation.List", "list_share", 2, params);
            JSONArray shares = res.optJSONArray("shares");
            if (shares != null) {
                for (int i = 0; i < shares.length(); i++) {
                    JSONObject s = shares.optJSONObject(i);
                    if (s != null) {
                        entries.add(new FolderEntry(s.optString("name", ""), s.optString("path", "")));
                    }
                }
            }
        } else {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("folder_path", JSONObject.quote(folderPath));
            params.put("filetype", "\"dir\"");
            params.put("additional", "[\"real_path\",\"owner\",\"time\"]");
            params.put("status_filter", "\"valid\"");
            JSONObject res = client.request("SYNO.Core.File", "list", 1, params);
            JSONArray files = res.optJSONArray("files");
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.optJSONObject(i);
                    if (f != null) {
                        entries.add(new FolderEntry(f.optString("name", ""), f.optString("path", "")));
                    }
                }
            }
        }
        return entries;
    }

    public TransferStats getStatistics() throws SynologyError, NetworkError {
        JSONObject res = client.request("SYNO.DownloadStation.Statistic", "getinfo", 1, Collections.emptyMap());
        return new TransferStats(
                res.optLong("speed_download", 0),
                res.optLong("speed_upload", 0)
        );
    }

    // ── Task Management ──────────────────────────────────────────────

    public TaskListResult listTasks(int offset, int limit, List<String> additional, int timeoutMs)
            throws SynologyError, NetworkError {
        List<String> fields = additional != null ? additional : Arrays.asList("detail", "transfer");
        if (client.supportsDS2()) {
            JSONArray arr = new JSONArray(fields);
            Map<String, String> params = new LinkedHashMap<>();
            params.put("offset", String.valueOf(offset));
            params.put("limit", String.valueOf(limit));
            params.put("additional", arr.toString());
            JSONObject data = client.request("SYNO.DownloadStation2.Task", "list", 2, params, timeoutMs);
            JSONArray rawTasks = data.optJSONArray("tasks");
            if (rawTasks == null) rawTasks = data.optJSONArray("task");
            return new TaskListResult(
                    data.optInt("total", 0),
                    data.optInt("offset", 0),
                    parseTaskArray(rawTasks)
            );
        } else {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("offset", String.valueOf(offset));
            params.put("limit", String.valueOf(limit));
            params.put("additional", String.join(",", fields));
            JSONObject data = client.request("SYNO.DownloadStation.Task", "list", 1, params, timeoutMs);
            return new TaskListResult(
                    data.optInt("total", 0),
                    data.optInt("offset", 0),
                    parseTaskArray(data.optJSONArray("tasks"))
            );
        }
    }

    public List<TaskItem> getTaskInfo(List<String> ids) throws SynologyError, NetworkError {
        return getTaskInfo(ids, Arrays.asList("detail", "transfer", "file", "tracker", "peer"));
    }

    public List<TaskItem> getTaskInfo(List<String> ids, List<String> additional) throws SynologyError, NetworkError {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", String.join(",", ids));
        params.put("additional", String.join(",", additional));
        JSONObject data = client.request("SYNO.DownloadStation.Task", "getinfo", 1, params);
        return parseTaskArray(data.optJSONArray("tasks"));
    }

    /**
     * Creates a task from URL/magnet link, or finalizes a selective-download task when listId is provided.
     */
    public CreateTaskResult createTask(
            String url,
            String destination,
            boolean createList,
            String listId,
            List<Integer> selectedIndices
    ) throws SynologyError, NetworkError {
        String cleanUrl = url != null ? url.trim() : "";
        String dest = (destination != null && !destination.trim().isEmpty())
                ? FormatUtils.stripLeadingSlash(destination.trim())
                : null;

        if (client.supportsDS2()) {
            if (listId != null && !listId.isEmpty()) {
                // Official WebUI selective download confirmation ("Super Secret Handshake"):
                // SYNO.DownloadStation2.Task.List.Polling / download / v2 with JSON-quoted params
                JSONArray selArray = new JSONArray();
                if (selectedIndices != null) {
                    for (Integer idx : selectedIndices) {
                        selArray.put(idx);
                    }
                }
                Map<String, String> finalParams = new LinkedHashMap<>();
                finalParams.put("list_id", JSONObject.quote(listId));
                finalParams.put("destination", JSONObject.quote(dest != null ? dest : "home/download"));
                finalParams.put("selected", selArray.toString());
                finalParams.put("create_subfolder", "true");
                JSONObject res = client.request("SYNO.DownloadStation2.Task.List.Polling", "download", 2, finalParams);
                return parseCreateTaskResult(res);
            } else {
                JSONArray urlArray = new JSONArray();
                urlArray.put(cleanUrl);
                Map<String, String> params = new LinkedHashMap<>();
                params.put("type", "\"url\"");
                params.put("url", urlArray.toString());
                params.put("create_list", createList ? "true" : "false");
                if (dest != null) {
                    params.put("destination", JSONObject.quote(dest));
                }
                JSONObject res = client.request("SYNO.DownloadStation2.Task", "create", 2, params);
                return parseCreateTaskResult(res);
            }
        }

        // Legacy V1 fallback
        Map<String, String> legacyParams = new LinkedHashMap<>();
        legacyParams.put("uri", cleanUrl);
        if (dest != null) {
            legacyParams.put("destination", dest);
        }
        JSONObject res = client.request("SYNO.DownloadStation.Task", "create", 1, legacyParams);
        return parseCreateTaskResult(res);
    }

    /**
     * Uploads a .torrent file via SYNO.DownloadStation2.Task.create (v2) with strict multipart field ordering.
     */
    public CreateTaskResult createTaskFromFile(
            String fileName,
            byte[] fileBytes,
            String destination,
            boolean createList
    ) throws SynologyError, NetworkError {
        String dest = (destination != null && !destination.trim().isEmpty())
                ? FormatUtils.stripLeadingSlash(destination.trim())
                : null;

        LinkedHashMap<String, String> orderedParams = new LinkedHashMap<>();
        orderedParams.put("file", "[\"torrent\"]");
        orderedParams.put("type", "\"file\"");
        orderedParams.put("destination", dest != null ? JSONObject.quote(dest) : "\"\"");
        orderedParams.put("create_list", createList ? "true" : "false");
        orderedParams.put("size", String.valueOf(fileBytes != null ? fileBytes.length : 0));

        JSONObject res = client.requestMultipart(
                "SYNO.DownloadStation2.Task",
                "create",
                2,
                orderedParams,
                "torrent",
                fileName,
                "application/x-bittorrent",
                fileBytes
        );
        return parseCreateTaskResult(res);
    }

    public List<TaskFileItem> getFileList(String listId) throws SynologyError, NetworkError {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("list_id", JSONObject.quote(listId));
            JSONObject res = client.request("SYNO.DownloadStation2.Task.List", "get", 2, params);
            JSONArray arr = extractFileArray(res);
            if (arr != null) {
                return parseTaskFileArray(arr);
            }
            throw new SynologyError("No files found in response", -1, "SYNO.DownloadStation2.Task.List", null);
        } catch (SynologyError | NetworkError primaryErr) {
            String[][] backupCalls = {
                    {"SYNO.DownloadStation2.Task.BT.File", "list"},
                    {"SYNO.DownloadStation2.Task", "get"}
            };
            int[] versions = {2, 1};
            for (String[] call : backupCalls) {
                for (int version : versions) {
                    try {
                        Map<String, String> backupParams = new LinkedHashMap<>();
                        backupParams.put("list_id", listId);
                        backupParams.put("additional", "file");
                        JSONObject res = client.request(call[0], call[1], version, backupParams);
                        JSONArray arr = extractFileArray(res);
                        if (arr != null) {
                            return parseTaskFileArray(arr);
                        }
                    } catch (Exception ignored) {
                        // Try next fallback
                    }
                }
            }
            throw primaryErr;
        }
    }

    private static JSONArray extractFileArray(JSONObject res) {
        if (res == null) return null;
        JSONArray arr = res.optJSONArray("file");
        if (arr == null) arr = res.optJSONArray("files");
        if (arr == null) arr = res.optJSONArray("items");
        if (arr == null) {
            JSONObject nested = res.optJSONObject("data");
            if (nested != null) {
                arr = nested.optJSONArray("file");
                if (arr == null) arr = nested.optJSONArray("files");
                if (arr == null) arr = nested.optJSONArray("items");
            }
        }
        return arr;
    }

    public List<TaskFileItem> getTaskFiles(String taskId) throws SynologyError, NetworkError {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("task_id", taskId);
        JSONObject res = client.request("SYNO.DownloadStation2.Task.BT.File", "list", 2, params);
        JSONArray arr = res.optJSONArray("items");
        if (arr == null) arr = res.optJSONArray("files");
        return parseTaskFileArray(arr);
    }

    public void setTaskFileWanted(String taskId, List<Integer> fileIndices, boolean wanted)
            throws SynologyError, NetworkError {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fileIndices.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(fileIndices.get(i));
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("task_id", taskId);
        params.put("index", sb.toString());
        params.put("wanted", wanted ? "true" : "false");
        client.request("SYNO.DownloadStation2.Task.BT.File", "set", 2, params);
    }

    public void pauseTasks(List<String> ids) throws SynologyError, NetworkError {
        if (ids == null || ids.isEmpty()) return;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", String.join(",", ids));
        client.request("SYNO.DownloadStation.Task", "pause", 1, params);
    }

    public void resumeTasks(List<String> ids) throws SynologyError, NetworkError {
        if (ids == null || ids.isEmpty()) return;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", String.join(",", ids));
        client.request("SYNO.DownloadStation.Task", "resume", 1, params);
    }

    public void deleteTasks(List<String> ids, boolean forceComplete) throws SynologyError, NetworkError {
        if (ids == null || ids.isEmpty()) return;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", String.join(",", ids));
        params.put("force_complete", String.valueOf(forceComplete));
        client.request("SYNO.DownloadStation.Task", "delete", 1, params);
    }

    public int clearCompletedTasks() throws SynologyError, NetworkError {
        TaskListResult all = listTasks(0, -1, Collections.emptyList(), 15000);
        List<String> finishedIds = new ArrayList<>();
        for (TaskItem t : all.tasks) {
            if ("finished".equals(t.status)) {
                finishedIds.add(t.id);
            }
        }
        if (!finishedIds.isEmpty()) {
            deleteTasks(finishedIds, false);
        }
        return finishedIds.size();
    }

    public int clearErrorTasks() throws SynologyError, NetworkError {
        TaskListResult all = listTasks(0, -1, Collections.emptyList(), 15000);
        List<String> errorIds = new ArrayList<>();
        for (TaskItem t : all.tasks) {
            if ("error".equals(t.status) || (t.status != null && t.status.startsWith("error_"))) {
                errorIds.add(t.id);
            }
        }
        if (!errorIds.isEmpty()) {
            deleteTasks(errorIds, false);
        }
        return errorIds.size();
    }

    // ── BT Search ──────────────────────────────────────────────────

    /**
     * Starts a BT search on the NAS.
     * Always defaults module to "enabled" so DSM only queries engines enabled for the current user
     * and avoids 504 gateway timeouts caused by passing raw internal plugin IDs.
     */
    public String btSearchStart(String keyword, String module) throws SynologyError, NetworkError {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("keyword", keyword);
        params.put("module", (module != null && !module.isEmpty()) ? module : "enabled");
        JSONObject res = client.request("SYNO.DownloadStation.BTSearch", "start", 1, params, 120000);
        return res.optString("taskid", "");
    }

    public BtSearchPage btSearchList(String taskId, int offset, int limit) throws SynologyError, NetworkError {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("taskid", taskId);
        params.put("offset", String.valueOf(offset));
        params.put("limit", String.valueOf(limit));
        params.put("sort_by", "title");
        params.put("sort_direction", "asc");
        params.put("filter_category", "");
        params.put("additional", "module");

        JSONObject res = client.request("SYNO.DownloadStation.BTSearch", "list", 1, params, 120000);
        boolean finished = res.optBoolean("finished", false);
        int total = res.optInt("total", 0);
        int resOffset = res.optInt("offset", 0);

        List<SearchResultItem> items = new ArrayList<>();
        JSONArray rawItems = res.optJSONArray("items");
        if (rawItems != null) {
            for (int i = 0; i < rawItems.length(); i++) {
                JSONObject obj = rawItems.optJSONObject(i);
                if (obj != null) {
                    String modId = obj.optString("module_id", obj.optString("module", ""));
                    String modTitle = obj.optString("module_title", modId);
                    items.add(new SearchResultItem(
                            obj.optString("title", ""),
                            obj.optLong("size", 0L),
                            obj.optInt("seeds", 0),
                            obj.optInt("peers", 0),
                            obj.optInt("leechs", 0),
                            obj.optString("download_uri", ""),
                            obj.optString("external_link", ""),
                            modId,
                            modTitle,
                            obj.optString("date", "")
                    ));
                }
            }
        }
        return new BtSearchPage(total, resOffset, finished, items);
    }

    public void btSearchClean(String taskId) throws SynologyError, NetworkError {
        if (taskId == null || taskId.isEmpty()) return;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("taskid", taskId);
        client.request("SYNO.DownloadStation.BTSearch", "clean", 1, params);
    }

    /**
     * Queries SYNO.DownloadStation2.Settings.BTSearch (v1, method=list) first to get per-user
     * enabled/disabled plugin status, falling back to legacy SYNO.DownloadStation.BTSearch (getModule).
     */
    public List<SearchModule> btSearchGetModules() throws SynologyError, NetworkError {
        if (client.isApiSupported("SYNO.DownloadStation2.Settings.BTSearch", 1)) {
            try {
                JSONObject ds2 = client.request("SYNO.DownloadStation2.Settings.BTSearch", "list", 1, Collections.emptyMap());
                JSONArray plugins = ds2.optJSONArray("plugins");
                if (plugins == null && ds2.optJSONObject("data") != null) {
                    plugins = ds2.optJSONObject("data").optJSONArray("plugins");
                }
                if (plugins != null) {
                    List<SearchModule> list = new ArrayList<>();
                    for (int i = 0; i < plugins.length(); i++) {
                        JSONObject p = plugins.optJSONObject(i);
                        if (p != null) {
                            String id = p.optString("name", "");
                            String title = p.optString("displayname", id);
                            boolean enabled = p.optBoolean("is_enable", false);
                            boolean canConnect = p.optBoolean("can_connect", true);
                            list.add(new SearchModule(id, title, enabled, canConnect));
                        }
                    }
                    return list;
                }
            } catch (Exception ignored) {
                // Fallback to legacy getModule
            }
        }

        JSONObject legacy = client.request("SYNO.DownloadStation.BTSearch", "getModule", 1, Collections.emptyMap());
        JSONArray modules = legacy.optJSONArray("modules");
        List<SearchModule> list = new ArrayList<>();
        if (modules != null) {
            for (int i = 0; i < modules.length(); i++) {
                JSONObject m = modules.optJSONObject(i);
                if (m != null) {
                    String id = m.optString("id", m.optString("name", ""));
                    String title = m.optString("title", m.optString("displayname", id));
                    boolean enabled = !m.has("enabled") || m.optBoolean("enabled", true);
                    list.add(new SearchModule(id, title, enabled, true));
                }
            }
        }
        return list;
    }

    // ── Normalization Helpers ──────────────────────────────────────

    public static TaskItem normalizeTask(JSONObject raw) {
        TaskItem item = new TaskItem();
        if (raw == null) return item;

        JSONObject additional = raw.optJSONObject("additional");
        JSONObject detail = (additional != null && additional.optJSONObject("detail") != null)
                ? additional.optJSONObject("detail")
                : (raw.optJSONObject("detail") != null ? raw.optJSONObject("detail") : new JSONObject());
        JSONObject transfer = (additional != null && additional.optJSONObject("transfer") != null)
                ? additional.optJSONObject("transfer")
                : (raw.optJSONObject("transfer") != null ? raw.optJSONObject("transfer") : new JSONObject());
        JSONObject statusExtra = raw.optJSONObject("status_extra");

        item.id = raw.optString("id", "");
        item.title = raw.optString("title", "");
        item.type = raw.optString("type", "unknown");

        Object statusObj = raw.opt("status");
        if (statusObj instanceof Number) {
            int code = ((Number) statusObj).intValue();
            item.status = STATUS_MAP.getOrDefault(code, "unknown_" + code);
        } else {
            item.status = raw.optString("status", "unknown");
        }

        item.size = raw.has("size") ? raw.optLong("size", 0) : detail.optLong("total_size", 0);
        item.username = raw.optString("username", "");

        item.destination = detail.has("destination")
                ? detail.optString("destination", "")
                : raw.optString("destination", "");
        item.uri = detail.has("uri")
                ? detail.optString("uri", "")
                : raw.optString("uri", "");
        item.createTime = detail.has("create_time")
                ? detail.optLong("create_time", 0)
                : raw.optLong("create_time", 0);
        item.completedTime = detail.optLong("completed_time", 0);
        item.startedTime = detail.optLong("started_time", 0);
        item.waitingSeconds = detail.optLong("waiting_seconds", 0);
        item.priority = detail.optString("priority", "auto");

        item.totalPieces = detail.optInt("total_pieces", 0);
        item.pieceLength = detail.optLong("piece_length", 0);

        item.connectedSeeder = firstPositiveInt(
                detail.optInt("connected_seeder", -1),
                transfer.optInt("connected_seeder", -1),
                raw.optInt("connected_seeder", -1),
                detail.optInt("connected_seeders", -1),
                transfer.optInt("connected_seeders", -1),
                raw.optInt("connected_seeders", -1),
                detail.optInt("seeders", -1),
                transfer.optInt("seeders", -1),
                statusExtra != null ? statusExtra.optInt("connected_seeder", -1) : -1
        );

        item.connectedLeecher = firstPositiveInt(
                detail.optInt("connected_leecher", -1),
                transfer.optInt("connected_leecher", -1),
                raw.optInt("connected_leecher", -1),
                detail.optInt("connected_leechers", -1),
                transfer.optInt("connected_leechers", -1),
                raw.optInt("connected_leechers", -1),
                detail.optInt("leechers", -1),
                transfer.optInt("leechers", -1),
                statusExtra != null ? statusExtra.optInt("connected_leecher", -1) : -1
        );

        item.unconnectedSeeder = firstPositiveInt(
                detail.optInt("unconnected_seeder", -1),
                transfer.optInt("unconnected_seeder", -1),
                raw.optInt("unconnected_seeder", -1)
        );

        item.unconnectedPeers = firstPositiveInt(
                detail.optInt("unconnected_peers", -1),
                transfer.optInt("unconnected_peers", -1),
                raw.optInt("unconnected_peers", -1)
        );

        item.totalPeers = firstPositiveInt(
                detail.optInt("total_peers", -1),
                transfer.optInt("total_peers", -1),
                raw.optInt("total_peers", -1),
                detail.optInt("connected_peers", -1),
                transfer.optInt("connected_peers", -1)
        );

        item.sizeDownloaded = transfer.has("size_downloaded")
                ? transfer.optLong("size_downloaded", 0)
                : raw.optLong("size_downloaded", 0);
        item.sizeUploaded = transfer.has("size_uploaded")
                ? transfer.optLong("size_uploaded", 0)
                : raw.optLong("size_uploaded", 0);
        item.speedDownload = transfer.has("speed_download")
                ? transfer.optLong("speed_download", 0)
                : raw.optLong("speed_download", 0);
        item.speedUpload = transfer.has("speed_upload")
                ? transfer.optLong("speed_upload", 0)
                : raw.optLong("speed_upload", 0);
        item.downloadedPieces = transfer.optInt("downloaded_pieces", 0);
        item.eta = transfer.has("eta") ? transfer.optLong("eta", 0) : transfer.optLong("time_left", 0);

        item.errorDetail = statusExtra != null ? statusExtra.optString("error_detail", "") : "";

        if (additional != null) {
            JSONArray trackerArr = additional.optJSONArray("tracker");
            if (trackerArr != null) {
                for (int i = 0; i < trackerArr.length(); i++) {
                    JSONObject t = trackerArr.optJSONObject(i);
                    if (t != null) {
                        item.trackers.add(new TrackerItem(
                                t.optString("url", ""),
                                t.optString("status", ""),
                                t.optInt("seeds", 0),
                                t.optInt("peers", 0)
                        ));
                    }
                }
            }
            JSONArray peerArr = additional.optJSONArray("peer");
            if (peerArr != null) {
                for (int i = 0; i < peerArr.length(); i++) {
                    JSONObject p = peerArr.optJSONObject(i);
                    if (p != null) {
                        item.peers.add(new PeerItem(
                                p.optString("address", ""),
                                p.optString("agent", ""),
                                p.optDouble("progress", 0.0),
                                p.optLong("speed_download", 0),
                                p.optLong("speed_upload", 0)
                        ));
                    }
                }
            }
        }

        return item;
    }

    private static int firstPositiveInt(int... values) {
        for (int v : values) {
            if (v >= 0) return v;
        }
        return 0;
    }

    private static List<TaskItem> parseTaskArray(JSONArray arr) {
        List<TaskItem> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj != null) {
                list.add(normalizeTask(obj));
            }
        }
        return list;
    }

    private static List<TaskFileItem> parseTaskFileArray(JSONArray arr) {
        List<TaskFileItem> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject f = arr.optJSONObject(i);
            if (f != null) {
                int idx = f.optInt("index", i);
                String name = f.optString("name", f.optString("filename", "File " + i));
                long size = f.optLong("size", 0);
                long downloaded = f.optLong("size_downloaded", 0);
                boolean wanted = !f.has("wanted") || f.optBoolean("wanted", true);
                String priority = f.optString("priority", "normal");
                list.add(new TaskFileItem(idx, name, size, downloaded, wanted, priority));
            }
        }
        return list;
    }

    private static CreateTaskResult parseCreateTaskResult(JSONObject res) {
        List<String> taskIds = new ArrayList<>();
        List<String> listIds = new ArrayList<>();
        if (res != null) {
            JSONArray tArr = res.optJSONArray("task_id");
            if (tArr != null) {
                for (int i = 0; i < tArr.length(); i++) {
                    taskIds.add(tArr.optString(i));
                }
            }
            JSONArray lArr = res.optJSONArray("list_id");
            if (lArr != null) {
                for (int i = 0; i < lArr.length(); i++) {
                    listIds.add(lArr.optString(i));
                }
            }
        }
        return new CreateTaskResult(taskIds, listIds);
    }
}

