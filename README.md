# Better DS Get

A modern, fast, and stable mobile client for Synology's Download Station, built with React Native and Expo.

<img width="466" alt="Better DS Get Preview" src="./docs/images/v1.0.0.jpeg" />

[View Changelog](./CHANGELOG.md)

## Why "Better"?

Original Synology "DS Get" app suffered from several issues:
- **Connectivity**: Frequent logouts and session timeouts.
- **Search**: For search prompts that were too broad, the app would just log you out, breaking the search flow.

## Features

- **Connect to your Synology NAS**: Supports generic DSM connections to local network using HTTPS and HTTP, and **QuickConnect IDs**.
- **Integrated BT Search**: Search directly from the app using all search engines configured on your NAS. Results are persistent and update in the background. Per-engine progress is shown in an **expandable status panel** with individual engine status and result counts. Results are **sortable by Seeds or Size** (toggleable direction).
- **Manage Download Tasks**: View, pause, resume, and delete tasks with **real-time progress updates** (5s auto-refresh on detail screen).
- **Add New Tasks**: 
  - One-tap addition from search results.
  - Submit Magnet links / URLs — the app intercepts links directly from the browser.
  - Upload `.torrent` files directly from your phone.
  - **Dynamic Destination Selection**: Choose specific download folders and use **Recent Folders** for 1-tap quick access.
  - **Selective Download**: Choose individual files *before* adding a torrent to the queue.
- **View Task Details**: Browse files inside BitTorrent tasks, check transfer speeds, **Time Left (ETA)**, and set file priorities (skip/unskip files). All task stats are shown in a single merged Info tab.
- **Advanced Tracker Info**: Expose tracker geolocation flags, protocol security badges (UDP/HTTPS/HTTP), and performance metrics.
- **Technical Transparency**: Use the "Connection Info" overlay to see resolved IPs, protocol security, API counts, and session status.
- **Smart Persistence**: Remembers your URL, account, and **password** for instant re-logging. Supports "Soft Logout" to switch accounts without wiping settings.
- **Stable Background Session**: Periodic NAS pings keep your session alive for as long as you need.
- **MFA / 2FA Support**: OTP code field appears on-demand after the server requests it, matching the original DS Get login flow.
- **Background Notifications**: Periodically polls the NAS in the background every 15 minutes and sends local notifications if a task finishes or fails.
- **About Screen**: App version, author, and contact email link, with a bespoke new app icon.
- **Platform Integrity**: Full support for Android hardware back button (modal dismissal and app exit logic).

## Getting Started

### Prerequisites
- Node.js LTS
- Yarn or npm
- Expo Go app on your device (or an emulator)

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/antimirov/better_ds_get.git
   cd better_ds_get
   ```

2. Install dependencies:
   ```bash
   npm install
   ```

3. Start the Expo development server:
   ```bash
   npx expo start
   ```

4. Scan the QR code with your camera (iOS) or the Expo Go app (Android) to load the app on your device.

## Roadmap

- [x] Implement torrent searching using configured engines.
- [x] Support for QuickConnect and HTTPS.
- [x] Individual file priority management.
- [x] **v1.1.0 Release** (Search, QuickConnect, Password Persistence).
- [x] Support for selecting destination directory before adding a new torrent.
- [x] Add progress bar to each download item in the main window. 
- [x] Add country flags to peers and trackers based on IP geolocation.
- [x] Implement Android hardware back button support for all modals.
- [x] **v1.2.0 Release** (Trackers, Android UX, Layout Optimization, **Selective Download**, **Real-time Stats**).
- [x] Add Intent Filter for magnet links and .torrent files.
- [x] **v1.3.0 Release** (Intent Filters, Deep Linking, Safe Content Handlers).
- [x] Merge General + Transfer tabs into a single Info tab.
- [x] Add About section to Settings.
- [x] MFA/OTP on-demand login flow.
- [x] Fix search polling race condition.
- [x] Add expandable per-engine search status panel.
- [x] Add sortable search results (Seeds / Size with direction toggle).
- [x] Fix repeated-search degradation (cleanup race condition).
- [x] **v1.4.0 Release** (UX Polish, Search Fixes, MFA, Engine Status Panel, Sortable Results, **Background Notifications**, **Custom Icon**).
- [ ] Add a "Remember Me" checkbox to the login screen.
- [ ] Dark mode - ability to switch to light theme.

## License

This project is open-source. Please see the LICENSE file for details.
