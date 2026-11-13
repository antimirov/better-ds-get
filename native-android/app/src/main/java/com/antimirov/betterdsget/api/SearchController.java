package com.antimirov.betterdsget.api;

import com.antimirov.betterdsget.api.Models.BtSearchPage;
import com.antimirov.betterdsget.api.Models.EngineStatus;
import com.antimirov.betterdsget.api.Models.SearchModule;
import com.antimirov.betterdsget.api.Models.SearchResultItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure Java controller for Download Station BT Search state, per-user enabled module filtering,
 * per-engine result synthesis, and sorting.
 */
public class SearchController {
    private final DownloadStation ds;
    private String keyword = "";
    private String activeTaskId = null;
    private boolean searching = false;
    private boolean finished = true;
    private String sortBy = "seeds"; // "seeds" or "size"
    private boolean sortDesc = true;

    private List<SearchModule> modules = new ArrayList<>();
    private List<EngineStatus> engineStatuses = new ArrayList<>();
    private List<SearchResultItem> rawResults = new ArrayList<>();

    public SearchController(DownloadStation ds) {
        this.ds = ds;
    }

    public synchronized List<SearchModule> loadModules() throws SynologyError, NetworkError {
        List<SearchModule> loaded = ds.btSearchGetModules();
        this.modules = new ArrayList<>(loaded);
        List<SearchModule> enabled = getEnabledModules();
        List<EngineStatus> statuses = new ArrayList<>();
        for (SearchModule m : enabled) {
            statuses.add(new EngineStatus(m.id, m.title, "waiting", 0));
        }
        this.engineStatuses = statuses;
        return loaded;
    }

    public synchronized List<SearchModule> getEnabledModules() {
        List<SearchModule> enabled = new ArrayList<>();
        for (SearchModule m : modules) {
            if (m.enabled) {
                enabled.add(m);
            }
        }
        return enabled;
    }

    /**
     * Cleans up any previous search task first (preventing the NAS repeated-search 0-result bug),
     * initializes per-engine statuses for enabled modules only, and starts a new search with module="enabled".
     */
    public String startSearch(String query) throws SynologyError, NetworkError {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Search query cannot be empty");
        }
        String cleanQuery = query.trim();

        String oldTaskId;
        synchronized (this) {
            if (modules.isEmpty()) {
                try {
                    loadModules();
                } catch (Exception ignored) {}
            }
            oldTaskId = this.activeTaskId;
            this.keyword = cleanQuery;
            this.searching = true;
            this.finished = false;
            this.rawResults = new ArrayList<>();

            List<SearchModule> enabled = getEnabledModules();
            List<EngineStatus> initStatuses = new ArrayList<>();
            for (SearchModule m : enabled) {
                initStatuses.add(new EngineStatus(m.id, m.title, "searching", 0));
            }
            this.engineStatuses = initStatuses;
        }

        // Crucial: await cleanup of previous search task BEFORE starting a new one
        if (oldTaskId != null && !oldTaskId.isEmpty()) {
            try {
                ds.btSearchClean(oldTaskId);
            } catch (Exception ignored) {}
        }

        String newTaskId = ds.btSearchStart(cleanQuery, "enabled");
        if (newTaskId == null || newTaskId.isEmpty()) {
            synchronized (this) {
                this.searching = false;
                this.finished = true;
            }
            throw new SynologyError("Failed to start search task", -1);
        }

        synchronized (this) {
            this.activeTaskId = newTaskId;
        }
        return newTaskId;
    }

    /**
     * Polls up to 300 results from the NAS and updates per-engine item counts and completion statuses.
     */
    public BtSearchPage pollOnce() throws SynologyError, NetworkError {
        String tid;
        synchronized (this) {
            tid = this.activeTaskId;
            if (!searching || tid == null || tid.isEmpty()) {
                return new BtSearchPage(rawResults.size(), 0, finished, getSortedResults());
            }
        }

        BtSearchPage page = ds.btSearchList(tid, 0, 300);

        synchronized (this) {
            if (!tid.equals(this.activeTaskId)) {
                // Stale poll response from a cancelled/superseded search
                return page;
            }
            this.rawResults = new ArrayList<>(page.items);

            // Count items per module ID and module title
            Map<String, Integer> counts = new HashMap<>();
            for (SearchResultItem item : page.items) {
                if (!item.moduleId.isEmpty()) {
                    counts.put(item.moduleId, counts.getOrDefault(item.moduleId, 0) + 1);
                }
                if (!item.moduleTitle.isEmpty() && !item.moduleTitle.equals(item.moduleId)) {
                    counts.put(item.moduleTitle, counts.getOrDefault(item.moduleTitle, 0) + 1);
                }
            }

            for (EngineStatus es : engineStatuses) {
                int count = counts.getOrDefault(es.module, counts.getOrDefault(es.moduleTitle, 0));
                es.items = Math.max(es.items, count);
                if (page.finished || es.isDone() || count > 0) {
                    es.status = "finished";
                } else {
                    es.status = "searching";
                }
            }

            if (page.finished) {
                this.finished = true;
            }
        }
        return page;
    }

    public void stopSearch() {
        String tid;
        synchronized (this) {
            tid = this.activeTaskId;
            this.activeTaskId = null;
            this.searching = false;
            this.finished = true;
        }
        if (tid != null && !tid.isEmpty()) {
            try {
                ds.btSearchClean(tid);
            } catch (Exception ignored) {}
        }
    }

    public void clearSearch() {
        stopSearch();
        synchronized (this) {
            this.keyword = "";
            this.rawResults.clear();
        }
    }

    public synchronized void setSort(String sortBy, boolean sortDesc) {
        this.sortBy = sortBy != null ? sortBy : "seeds";
        this.sortDesc = sortDesc;
    }

    public synchronized void toggleSort(String field) {
        if (field.equals(this.sortBy)) {
            this.sortDesc = !this.sortDesc;
        } else {
            this.sortBy = field;
            this.sortDesc = true;
        }
    }

    public synchronized String getSortBy() {
        return sortBy;
    }

    public synchronized boolean isSortDesc() {
        return sortDesc;
    }

    public synchronized boolean isSearching() {
        return searching;
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    public synchronized String getKeyword() {
        return keyword;
    }

    public synchronized String getActiveTaskId() {
        return activeTaskId;
    }

    public synchronized List<EngineStatus> getEngineStatuses() {
        return new ArrayList<>(engineStatuses);
    }

    public synchronized int getDoneEngineCount() {
        int done = 0;
        for (EngineStatus e : engineStatuses) {
            if (e.isDone()) done++;
        }
        return done;
    }

    public synchronized List<SearchResultItem> getSortedResults() {
        List<SearchResultItem> copy = new ArrayList<>(rawResults);
        Comparator<SearchResultItem> cmp;
        if ("size".equalsIgnoreCase(sortBy)) {
            cmp = Comparator.comparingLong(a -> a.size);
        } else {
            cmp = Comparator.comparingInt(a -> a.seeds);
        }
        if (sortDesc) {
            cmp = cmp.reversed();
        }
        Collections.sort(copy, cmp);
        return copy;
    }
}

