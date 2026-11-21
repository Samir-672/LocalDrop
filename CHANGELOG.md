# Changelog

## v1.0.0

Initial feature set:

- Android foreground service hosts the LocalDrop web app over the local network.
- A generated six-digit access code authenticates browser clients; authenticated sessions use expiring tokens.
- Android and browser clients can share text and files, copy or delete individual items, and clear all shared items.
- Browser uploads support multiple files and display transfer progress; received files can be downloaded.
- Android provides file selection, access-code visibility and copy controls, server status, and light, dark, and system themes.
- Shared item metadata and files persist in the app's private storage, and connected clients refresh the list automatically.
- Responsive browser interface supports desktop and mobile layouts.

The project uses plain HTTP and is intended only for trusted local networks; see [SECURITY.md](./SECURITY.md).

This v1.0.0 release candidate has automated test coverage, but this changelog does not claim verification on physical devices. A signed release artifact has not been generated or published as part of this preparation.
