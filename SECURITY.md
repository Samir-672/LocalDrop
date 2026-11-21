# Security

## Intended use and limitations

LocalDrop is designed for short-lived sharing on a trusted local network. The Android app serves its browser interface and API over plain HTTP. There is no TLS, transport encryption, certificate validation, or end-to-end encryption.

The six-digit access code is an authentication gate, not encryption. Access codes and session bearer tokens travel over HTTP and can be observed by someone able to monitor the network. A person with access to the same network may also be able to reach the server. Do not use LocalDrop on public, guest, or otherwise untrusted Wi-Fi; do not expose or forward its port to the internet; and do not share sensitive information through it.

Session tokens expire after 30 minutes. Stopping or restarting the server invalidates its active sessions. Shared files and metadata are stored in the app's private storage on the phone; they are not protected by application-level encryption.

## Reporting a concern

No dedicated private security contact is configured in the project files. If GitHub private vulnerability reporting is enabled for the repository, use it. Otherwise, contact the project maintainer through an established private channel. Do not post access codes, session tokens, personal data, or sensitive vulnerability details in a public issue.

## Project handling

Do not commit keystores, signing passwords, local environment files, access codes, session tokens, or real shared data. Keep signing credentials outside the repository and consult [README.md](./README.md) for the environment-based release configuration.
