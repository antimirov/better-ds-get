# Better DS Get

A modern, fast, and stable mobile client for Synology's Download Station, built with React Native and Expo.

## Features

- **Connect to your Synology NAS**: Supports generic DSM connections and custom API configurations.
- **Manage Download Tasks**: View, pause, resume, and delete tasks easily.
- **Add New Tasks**: Add downloads via standard URLs, Magnet links, or by uploading `.torrent` files directly from your phone.
- **View Task Details**: Browse files inside BitTorrent tasks, check transfer speeds, and set file priorities (skip/unskip files).
- **Modify Settings**: Change your default download destination right from the app.
- **Stable Background Session**: Built-in automatic keep-alive mechanisms to prevent annoying session timeouts.

## Getting Started

### Prerequisites
- Node.js LTS
- Yarn or npm
- Expo Go app on your iOS/Android device (or an emulator)

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/better_ds_get.git
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

- Implement torrent searching using configured search engines from the Download Station WebUI.
- Polishing UI and themes.
- Support for selecting individual files when adding a new torrent.
- Build a standalone `.apk` file for production release.

## License

This project is open-source. Please see the LICENSE file for details.
