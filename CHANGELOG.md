# Changelog

All notable changes to this project will be documented in this file.

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
