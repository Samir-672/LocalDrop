# LocalDrop v1.0.0

LocalDrop provides local-network text and file sharing from an Android phone to authenticated browser clients.

## Included

- Start a phone-hosted server and connect through a browser using the displayed access code.
- Share text and files between Android and browser clients.
- Upload multiple files, track transfer progress, download files, copy or delete items, and clear all shared items.
- Keep shared item metadata and files in the app's private storage and refresh connected clients automatically.
- Use Android access-code visibility and copy controls, server status, and light, dark, or system themes.
- Use the responsive browser interface on desktop and mobile screens.

## Security note

LocalDrop uses plain HTTP without transport encryption. Use it only on a trusted local network; do not use sensitive data or expose the server to the internet. See [SECURITY.md](./SECURITY.md).

## Validation and release artifact

The project has automated unit, Android UI, and local HTTP API tests. These checks are not a substitute for physical-device and real-network testing; no such testing is claimed by this release preparation.

This is a release-notes draft only. A signed release APK has not been produced because no keystore or signing credentials are configured. Before publishing an APK release, the owner must configure signing and build and verify the APK.
