# Changelog

All notable changes to this project will be documented in this file.

## [1.4.0] - 2026-03-08

### Added
- **Expandable Engine Status Panel**: Tapping the search status bar now reveals a per-engine breakdown showing each engine's name, number of results found, and status (Searching / Finished). Auto-expands on search start, auto-collapses when all engines finish.
- **Sortable Search Results**: Results can now be sorted by **Seeds** (default, descending) or **Size**. Tapping the same sort option again flips the direction (toggle). Active sort key is highlighted with a direction chevron.
- **Clear Results Button**: A "Clear" button in the sort bar allows quickly resetting the search without navigating away.
- **About Section in Settings**: Displays app version, author name, contact email link, and the new app icon. The entire Settings modal is now scrollable to accommodate smaller screens and dismissible by tapping the backdrop.
- **MFA / 2FA On-Demand**: The OTP code field in the Login screen is now hidden by default and only appears when the server requires two-factor authentication, matching the original DS Get behavior.
- **Background Notifications**: App now uses a modern background task service (`expo-background-task`) to check the Download Station state every 15 minutes while the app is closed, sending a local notification if a task finishes or fails.
- **Custom App Icon**: Added a sleek, bespoke app icon featuring green (download) and red (upload) arrows spanning the screen.

### Changed
- **Search Timeout**: Reduced from default to 30 seconds for `btSearchStart` and `btSearchList`, with retry-on-timeout logic. Poll interval is 1.5s for the first 10 seconds, then 3s.
- **Task Detail: Merged "Info" Tab**: The "General" and "Transfer" tabs have been merged into a single flat "Info" tab with logical ordering — no dividers or duplicated fields.
- **Task Detail: Smart ETA Display**: "Time Left" now shows "Unknown" instead of "Calculating..." when a torrent is stalled with 0 speed.
- **Task Detail: Piece Count for Finished Tasks**: Shows "All complete" instead of "0 / N" for finished torrents (DSM doesn't populate this field once complete).
- **Task Detail: Piece Count for Stalled Tasks**: Shows "Unknown (DSM quirk)" instead of "0 / N" when download has data but API reports 0 pieces.
- **Task Detail: "Clear" vs "Delete"**: The action button for finished tasks now shows "Clear" (x-circle icon) instead of "Delete" (trash icon) to avoid alarming users.
- **Task Detail: Scrollable Tracker & Peers Tabs**: Fixed lists being cut off — both tabs now scroll correctly.
- **Add Task: Intent-Aware Modal**: Opening a magnet link from an external app now hides the unrelated "Upload .torrent File" button.
- **Back Button**: Pressing Android Back on the main screen now exits the app to the home screen rather than doing nothing.

### Fixed
- **Repeated Search Degradation**: Searching the same query multiple times progressively returned fewer results (down to 0). Root cause: `btSearchClean` was fire-and-forgotten concurrently with `btSearchStart`, causing a NAS-side race. Cleanup is now `await`-ed before starting a new search.
- **Per-Engine Progress Always "0/N done"**: A field-name mismatch — the app looked up engines by `name`, but the NAS uses `id` (e.g. `the_pirate_bay`). Fixed with `id → name → displayname` fallback.
- **Engine Status Synthesis**: For NAS versions that don't return per-engine status, the app now synthesizes it by counting `module_id` occurrences in results. An engine is marked "Finished" as soon as it returns ≥1 result.
- **Search Race Condition — Invalid task ID**: Rewrote the search polling loop with a proper abort-ref pattern (`isPollingActive` + `pollTimerId` refs). Eliminated the double-poll bug that caused "Invalid task ID" errors and results resetting from 57 to 0.
- **Search Status Row Overflow**: The "Stop" button was cut off the right edge of the screen due to a missing `flex: 1` on the status info container.
- **Expo Dev URL Triggering "Add Task"**: The app was treating Expo Go's own `exp://…` startup URL as a magnet link intent, opening the Add Task modal on every launch in development mode.
- **Total Peers "Not Available"**: The Total Peers field now falls back to the sum of connected seeders + leechers when the API doesn't provide a dedicated total.
- **Delete Confirmation**: Removed the dangerous "Delete Task AND Data" option from the removal confirmation dialog. The only option is now "Remove" which always keeps the downloaded file on the NAS.
- **Hardware Back Button (Exit App)**: Explicitly call `BackHandler.exitApp()` on the root navigation screen so the Android back button reliably minimizes/exits the app instead of doing nothing.
- **Hardware Back Button (Popups)**: Made the "Remove Task" alert `{ cancelable: true }` so the Android back button successfully dismisses the popup instead of ignoring it.

## [1.3.0] - 2026-02-25

### Added
- **Native Intent Filters**: The app now officially intercepts `magnet:` links and `.torrent` files from third-party apps like Firefox and Chrome.
- **Deep Linking**: Incoming URL support for "opening" links into the app from the browser or other platforms.
- **Safe Content Handlers**: Implemented a native Android plugin to safely copy protected `content://` URIs (like from Firefox) to a readable cache before processing.

## [1.2.0] - 2026-02-24

### Added
- **Selective Download (Polling API v2)**: Finalize torrent file selection before adding to queue, using the robust `SYNO.DownloadStation2.Task.List.Polling` (v2) API found in the official WebUI.
- **Torrent Upload Upgrade**: Enhanced `.torrent` file submission using `SYNO.DownloadStation2.Task.create` (v2) with explicit file size parameters and strict multipart ordering.
- **Real-Time Task Monitoring**: 5-second background polling in the Task Detail screen for live speed, progress, and seeder updates.
- **"Time Left" (ETA)**: Integrated time-to-completion estimates in the Transfer tab.
- **Estimated Wait Time**: General tab now shows the position/time in queue for waiting tasks.
- **"Tech Nerd" Tracker Tab**: Advanced tracker metadata including geolocation flags, protocol badges (UDP/HTTPS/HTTP), port numbers, and seed/peer icons.
- **Intelligent Tracker Sorting**: Trackers are now automatically sorted by status (Working first) and then by seed count.
- **Visual Progress Bars**: Sleek, status-colored progress bars in the main task list.
- **Peer Geolocation**: Country flags in the peer list based on IP addresses (using ip-api.com).
- **Target Folder Selection**: Choose a download destination at the time of task creation across all screens (Search, URL, File).
- **Recent Folders History**: The app remembers your last 5 used destinations for 1-tap access using local storage.
- **Android Back Button Support**: Added hardware back button support to all modals (Add Task, Settings, Folder Picker, etc.) for better platform parity.

### Changed
- **Layout Optimization**: Eliminated 60px of redundant header padding to maximize screen space using global `SafeAreaView`.

### Fixed
- **Deprecation Clean-up**: Migrated from `SafeAreaView` to `react-native-safe-area-context` to resolve build warnings.

## [1.1.0] - 2026-02-23

### Added
- **Integrated BT Search**: Directly search for torrents from the app using NAS-configured search engines.
- **QuickConnect Support**: Resolve Synology QuickConnect IDs to IP addresses automatically.
- **Connection Details Overlay**: Technical popup showing resolved URLs, SSL status, and session metadata.
- **Password Persistence**: Option to remember the login password for faster sessions.
- **Soft Logout**: Disconnect without wiping "Remember Me" credentials.
- **Improved UI**: Monospaced font for technical details, better layout for long URLs, and background search persistence.

### Fixed
- **API Error 120**: Fixed multipart field scrambling in torrent uploads using a custom-ordered raw byte builder.
- **Smart Fallback**: Automatic fallback to V1 API if V2 createTask fails with reserved error codes.
- **Session Timeouts**: Implemented periodic keep-alive pings to prevent annoying logouts.

## [1.0.0] - 2026-02-20

### Added
- Initial release with core Download Station functionality.
- Task listing, pausing, resuming, and deletion.
- Magnet link and URL task creation.
- File listing inside tasks with priority support (skip/unskip).
- Destination folder selection and persistence.
- Robust Synology API client with session management.
