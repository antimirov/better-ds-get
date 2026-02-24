# Changelog

All notable changes to this project will be documented in this file.

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
