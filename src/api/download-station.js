/**
 * DownloadStation — High-level API for Synology Download Station.
 *
 * Wraps SynologyClient with Download Station-specific methods.
 * Supports both legacy DS1 and newer DS2 APIs where available.
 */

import { SynologyClient } from './synology-client.js';

export class DownloadStation {
  /** @type {SynologyClient} */
  #client;

  /**
   * @param {SynologyClient} client - An authenticated SynologyClient instance
   */
  constructor(client) {
    this.#client = client;
  }

  // ── Info & Config ──────────────────────────────────────────────

  /**
   * Get Download Station info (version, is_manager, etc.)
   * @returns {Promise<DSInfo>}
   */
  async getInfo() {
    return this.#client.request('SYNO.DownloadStation.Info', 'getinfo', 1);
  }

  /**
   * Get Download Station config (default destination, emule, etc.)
   * @returns {Promise<any>}
   */
  async getConfig() {
    if (this.#client.isApiSupported('SYNO.DownloadStation2.Settings.Location', 1)) {
      return this.#client.request('SYNO.DownloadStation2.Settings.Location', 'get', 1);
    }
    return this.#client.request('SYNO.DownloadStation.Info', 'getconfig', 1);
  }

  /**
   * Set Download Station config (e.g. default_destination)
   * @param {object} params - Key-value map of config to change
   * @returns {Promise<any>}
   */
  async setConfig(params) {
    if (this.#client.isApiSupported('SYNO.DownloadStation2.Settings.Location', 1)) {
      return this.#client.request('SYNO.DownloadStation2.Settings.Location', 'set', 1, params);
    }
    // Fallback just in case
    return this.#client.request('SYNO.DownloadStation.Info', 'setserverconfig', 1, params);
  }

  /**
   * List folders (used for selecting destinations).
   * If folderPath is empty, lists top-level shared folders.
   * If folderPath is provided, lists subfolders inside it.
   * @param {string} folderPath 
   * @returns {Promise<any[]>}
   */
  async listFolders(folderPath = '') {
    if (!folderPath) {
      const res = await this.#client.request('SYNO.FileStation.List', 'list_share', 2, {
        additional: 'real_path,owner,time'
      });
      return res.shares || [];
    } else {
      const res = await this.#client.request('SYNO.Core.File', 'list', 1, {
        folder_path: JSON.stringify(folderPath),
        filetype: JSON.stringify("dir"),
        additional: JSON.stringify(["real_path", "owner", "time"]),
        status_filter: JSON.stringify("valid")
      });
      return res.files || [];
    }
  }

  /**
   * Get transfer statistics (current speeds).
   * @returns {Promise<{speed_download: number, speed_upload: number}>}
   */
  async getStatistics() {
    return this.#client.request('SYNO.DownloadStation.Statistic', 'getinfo', 1);
  }

  // ── Task Management ──────────────────────────────────────────────

  /**
   * List all download tasks.
   * Uses DS2 API if available, falls back to DS1.
   *
   * @param {object} [options]
   * @param {number} [options.offset=0] - Pagination offset
   * @param {number} [options.limit=-1] - Max results (-1 = all)
   * @param {string[]} [options.additional] - Extra info fields: "detail", "transfer", "file", "tracker", "peer"
   * @returns {Promise<TaskListResult>}
   */
  async listTasks(options = {}) {
    const { offset = 0, limit = -1, additional = ['detail', 'transfer'] } = options;

    if (this.#client.supportsDS2) {
      return this.#listTasksDS2(offset, limit, additional);
    }
    return this.#listTasksDS1(offset, limit, additional);
  }

  /**
   * List tasks using legacy DS1 API.
   */
  async #listTasksDS1(offset, limit, additional) {
    const data = await this.#client.request('SYNO.DownloadStation.Task', 'list', 1, {
      offset,
      limit,
      additional: additional.join(','),
    });

    return {
      total: data.total,
      offset: data.offset,
      tasks: (data.tasks ?? []).map(t => this.#normalizeTask(t)),
    };
  }

  /**
   * List tasks using newer DS2 API.
   */
  async #listTasksDS2(offset, limit, additional) {
    const data = await this.#client.request('SYNO.DownloadStation2.Task', 'list', 2, {
      offset,
      limit,
      additional: JSON.stringify(additional), // DS2 API may sometimes expect JSON array, but we'll try string format if it fails later
    });

    return {
      total: data.total,
      offset: data.offset,
      tasks: (data.tasks ?? data.task ?? []).map(t => this.#normalizeTask(t)),
    };
  }

  /**
   * Get details for specific tasks.
   * @param {string[]} ids - Task IDs
   * @param {string[]} [additional] - Extra info fields
   * @returns {Promise<Task[]>}
   */
  async getTaskInfo(ids, additional = ['detail', 'transfer', 'file', 'tracker', 'peer']) {
    const data = await this.#client.request('SYNO.DownloadStation.Task', 'getinfo', 1, {
      id: ids.join(','),
      additional: additional.join(','),
    });

    return (data.tasks ?? []).map(t => this.#normalizeTask(t));
  }

  /**
   * Get details for specific tasks WITHOUT normalizing them (for debugging).
   * @param {string} id - Task ID
   * @returns {Promise<any>}
   */
  async getRawTaskInfo(id) {
    const data = await this.#client.request('SYNO.DownloadStation.Task', 'getinfo', 1, {
      id,
      additional: 'detail,transfer,file,tracker,peer',
    });
    return data.tasks?.[0] ?? { error: 'No task found' };
  }

  /**
   * Create a new download task.
   * @param {string} url - URL or magnet link
   * @param {object} [options]
   * @param {string} [options.destination] - Share/folder path
   * @param {string} [options.username]
   * @param {string} [options.password]
   * @param {string} [options.unzipPassword]
   */
  async createTask(url, options = {}) {
    const cleanUrl = url.trim();

    // Try newer DS2 API if supported
    if (this.#client.supportsDS2) {
      const params = {};
      // DS2 create takes 'type' as raw string and 'url' as JSON array of strings
      params.type = 'url';
      params.url = JSON.stringify([cleanUrl]);

      if (options.destination) {
        // Destination in DS2 is also JSON stringified
        params.destination = JSON.stringify(options.destination);
      }
      if (options.username) params.username = JSON.stringify(options.username);
      if (options.password) params.password = JSON.stringify(options.password);
      if (options.unzipPassword) params.unzip_password = JSON.stringify(options.unzipPassword);

      try {
        return await this.#client.request('SYNO.DownloadStation2.Task', 'create', 2, params);
      } catch (error) {
        // If it's a "Reserved" error (120) or unknown (100), try falling back to legacy V1 API
        // which sometimes handles magnet/search links more reliably on some DSM versions.
        console.warn('DS2 createTask failed, trying legacy V1 fallback:', error.message);
        if (error.code !== 120 && error.code !== 100) {
          throw error;
        }
      }
    }

    // Fallback to legacy SYNO.DownloadStation.Task (V1)
    const legacyParams = { uri: cleanUrl };
    if (options.destination) legacyParams.destination = options.destination;
    if (options.username) legacyParams.username = options.username;
    if (options.password) legacyParams.password = options.password;
    if (options.unzipPassword) legacyParams.unzip_password = options.unzipPassword;

    return this.#client.request('SYNO.DownloadStation.Task', 'create', 1, legacyParams);
  }

  /**
   * Create a download task from a file (torrent).
   *
   * @param {File|Blob|any} file - .torrent file or equivalent object
   * @param {object} [options]
   * @param {string} [options.destination] - Download folder path
   * @returns {Promise<any>}
   */
  async createTaskFromFile(file, options = {}) {
    const { name, size, type, uri } = file;
    const fileObj = { name, size, type, uri };

    const params = {
      // The WebUI sends these exactly as JSON stringified values
      file: JSON.stringify(['torrent']),
      type: JSON.stringify('file'),
      destination: options.destination ? JSON.stringify(options.destination) : '""',
      create_list: 'false'
    };

    return this.#client.requestMultipart(
      'SYNO.DownloadStation2.Task', 'create', 2,
      params, [{ name: 'torrent', file: fileObj }]
    );
  }
  /**
   * Pause task(s).
   * @param {string[]} ids - Task IDs
   */
  async pauseTasks(ids) {
    return this.#client.request('SYNO.DownloadStation.Task', 'pause', 1, {
      id: ids.join(','),
    });
  }

  /**
   * Resume task(s).
   * @param {string[]} ids - Task IDs
   */
  async resumeTasks(ids) {
    return this.#client.request('SYNO.DownloadStation.Task', 'resume', 1, {
      id: ids.join(','),
    });
  }

  /**
   * Delete task(s).
   * @param {string[]} ids - Task IDs
   * @param {boolean} force - If true, also delete downloaded files
   */
  async deleteTasks(ids, force = false) {
    return this.#client.request('SYNO.DownloadStation.Task', 'delete', 1, {
      id: ids.join(','),
      force_complete: force ? 'true' : 'false',
    });
  }

  /**
   * Get the list of individual files inside a BitTorrent task.
   * @param {string} taskId - The ID of the BT task
   * @returns {Promise<any>}
   */
  async getTaskFiles(taskId) {
    return this.#client.request('SYNO.DownloadStation2.Task.BT.File', 'list', 2, {
      task_id: taskId,
    });
  }

  /**
   * Set the wanted (download) priority for specific files in a BT task.
   * @param {string} taskId - The ID of the BT task
   * @param {number[]} fileIndices - Array of zero-based indices of the files
   * @param {boolean} wanted - Whether to download (true) or skip (false) these files
   * @returns {Promise<any>}
   */
  async setTaskFileWanted(taskId, fileIndices, wanted) {
    return this.#client.request('SYNO.DownloadStation2.Task.BT.File', 'set', 2, {
      task_id: taskId,
      index: fileIndices.join(','),
      wanted: wanted ? 'true' : 'false'
    });
  }

  /**
   * Resume task(s).
   * @param {string[]} ids - Task IDs
   */
  async resumeTasks(ids) {
    return this.#client.request('SYNO.DownloadStation.Task', 'resume', 1, {
      id: ids.join(','),
    });
  }

  /**
   * Delete task(s).
   * @param {string[]} ids - Task IDs
   * @param {boolean} [forceComplete=false] - Also delete finished tasks' files
   */
  async deleteTasks(ids, forceComplete = false) {
    return this.#client.request('SYNO.DownloadStation.Task', 'delete', 1, {
      id: ids.join(','),
      force_complete: String(forceComplete),
    });
  }

  /**
   * Edit task(s) — change destination.
   * @param {string[]} ids - Task IDs
   * @param {string} destination - New destination folder
   */
  async editTasks(ids, destination) {
    return this.#client.request('SYNO.DownloadStation.Task', 'edit', 1, {
      id: ids.join(','),
      destination,
    });
  }

  /**
   * Clear all finished tasks.
   * @returns {Promise<any>}
   */
  async clearCompletedTasks() {
    // To clear completed tasks we actually fetch all, find finished, and delete
    const data = await this.listTasks({ limit: -1, additional: [] });
    const finishedIds = data.tasks.filter(t => t.status === 'finished').map(t => t.id);
    if (finishedIds.length > 0) {
      return this.deleteTasks(finishedIds, false);
    }
    return { success: true, count: 0 };
  }

  /**
   * Clear all errored tasks.
   * @returns {Promise<any>}
   */
  async clearErrorTasks() {
    const data = await this.listTasks({ limit: -1, additional: [] });
    const errorIds = data.tasks.filter(t => t.status === 'error').map(t => t.id);
    if (errorIds.length > 0) {
      return this.deleteTasks(errorIds, false);
    }
    return { success: true, count: 0 };
  }

  // ── Schedule ──────────────────────────────────────────────────

  /**
   * Get schedule settings.
   */
  async getSchedule() {
    return this.#client.request('SYNO.DownloadStation.Schedule', 'getconfig', 1);
  }

  /**
   * Set schedule settings.
   */
  async setSchedule(enabled, emuleEnabled = false) {
    return this.#client.request('SYNO.DownloadStation.Schedule', 'setconfig', 1, {
      enabled: String(enabled),
      emule_enabled: String(emuleEnabled),
    });
  }

  // ── RSS ──────────────────────────────────────────────────────

  /**
   * List RSS feeds.
   * @param {number} [offset=0]
   * @param {number} [limit=-1]
   */
  async listRSSFeeds(offset = 0, limit = -1) {
    return this.#client.request('SYNO.DownloadStation.RSS.Site', 'list', 1, {
      offset, limit,
    });
  }

  // ── BT Search ──────────────────────────────────────────────────

  /**
   * Start a BT search.
   * @param {string} keyword - Search keyword
   * @param {string} [module='all'] - Search module
   */
  async btSearchStart(keyword, module = 'all') {
    return this.#client.request('SYNO.DownloadStation.BTSearch', 'start', 1, {
      keyword, module,
    });
  }

  /**
   * List BT search results.
   * @param {string} taskid - Search task ID
   * @param {number} [offset=0]
   * @param {number} [limit=50]
   */
  async btSearchList(taskid, offset = 0, limit = 50) {
    return this.#client.request('SYNO.DownloadStation.BTSearch', 'list', 1, {
      taskid, offset, limit,
      sort_by: 'seeds',
      sort_direction: 'desc',
    });
  }

  /**
   * Stop and clean up a BT search task.
   * @param {string} taskid - Search task ID
   */
  async btSearchClean(taskid) {
    return this.#client.request('SYNO.DownloadStation.BTSearch', 'clean', 1, {
      taskid,
    });
  }

  /**
   * Get available BT search modules (engines).
   */
  async btSearchGetModules() {
    return this.#client.request('SYNO.DownloadStation.BTSearch', 'getModule', 1);
  }

  // ── Helpers ──────────────────────────────────────────────────

  /**
   * Internal mapping of numeric status codes used by Synology.
   */
  static STATUS_MAP = {
    1: 'waiting',
    2: 'downloading',
    3: 'paused',
    4: 'finishing',
    5: 'finished',
    6: 'hash_checking',
    8: 'seeding',
    9: 'filehosting_waiting',
    10: 'extracting',
    100: 'error',
    101: 'network_error',
    102: 'permission_denied',
    103: 'destination_error',
    104: 'destination_is_out_of_space',
    105: 'file_not_exist',
    106: 'account_error',
    107: 'torrent_invalid',
    108: 'torrent_duplicate',
    109: 'quota_reached',
    110: 'not_supported',
    111: 'bt_max_task',
    112: 'emule_max_task',
    113: 'nzb_max_task',
    114: 'http_max_task',
    115: 'bad_request',
    116: 'need_unzip_password'
  };

  /**
   * Normalize a task object from either DS1 or DS2 format.
   * @param {any} raw
   * @returns {Task}
   */
  #normalizeTask(raw) {
    const detail = raw.additional?.detail ?? raw.detail ?? {};
    const transfer = raw.additional?.transfer ?? raw.transfer ?? {};

    // Determine status string (Synology API sometimes returns it as an integer)
    let normalizedStatus = raw.status;
    if (typeof raw.status === 'number') {
      normalizedStatus = DownloadStation.STATUS_MAP[raw.status] || `unknown_${raw.status}`;
    }

    return {
      id: raw.id,
      title: raw.title,
      type: raw.type ?? 'unknown', // http, ftp, bt, nzb, emule, https
      status: normalizedStatus ?? 'unknown', // downloading, paused, finished, error, etc.
      statusExtra: raw.status_extra,
      size: raw.size ?? detail.total_size ?? 0,
      username: raw.username ?? '',

      // Detail info
      destination: detail.destination ?? raw.destination ?? '',
      uri: detail.uri ?? raw.uri ?? '',
      createTime: detail.create_time ?? raw.create_time ?? 0,
      completedTime: detail.completed_time ?? 0,
      startedTime: detail.started_time ?? 0,
      waitingSeconds: detail.waiting_seconds ?? 0,
      priority: detail.priority ?? 'auto',

      // Pieces
      totalPieces: detail.total_pieces ?? 0,
      pieceLength: detail.piece_length ?? 0,

      // Peers
      connectedSeeder: detail.connected_seeder ?? 0,
      connectedLeecher: detail.connected_leecher ?? 0,
      unconnectedSeeder: detail.unconnected_seeder ?? 0,
      unconnectedPeers: detail.unconnected_peers ?? 0,
      totalPeers: detail.total_peers ?? 0,

      // Transfer info
      sizeDownloaded: transfer.size_downloaded ?? raw.size_downloaded ?? 0,
      sizeUploaded: transfer.size_uploaded ?? raw.size_uploaded ?? 0,
      speedDownload: transfer.speed_download ?? raw.speed_download ?? 0,
      speedUpload: transfer.speed_upload ?? raw.speed_upload ?? 0,
      downloadedPieces: transfer.downloaded_pieces ?? 0,

      // Error info
      errorDetail: raw.status_extra?.error_detail ?? '',

      // Arrays
      trackers: raw.additional?.tracker ?? [],
      peersArray: raw.additional?.peer ?? [],
    };
  }
}

/**
 * @typedef {Object} DSInfo
 * @property {string} version - DS version string
 * @property {string} version_string
 * @property {boolean} is_manager
 */

/**
 * @typedef {Object} TaskListResult
 * @property {number} total - Total task count
 * @property {number} offset - Current offset
 * @property {Task[]} tasks - Task list
 */

/**
 * @typedef {Object} Task
 * @property {string} id - Task ID
 * @property {string} title - Display name
 * @property {string} type - Protocol type
 * @property {string} status - Current status
 * @property {number} size - Total size in bytes
 * @property {number} sizeDownloaded - Downloaded bytes
 * @property {number} sizeUploaded - Uploaded bytes
 * @property {number} speedDownload - Download speed (bytes/sec)
 * @property {number} speedUpload - Upload speed (bytes/sec)
 * @property {string} destination - Download folder
 * @property {string} uri - Source URL
 * @property {number} createTime - Unix timestamp
 * @property {number} completedTime - Unix timestamp
 * @property {string} priority - Task priority
 * @property {string} errorDetail - Error description if errored
 */
