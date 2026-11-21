# Contributing

Thanks for helping improve LocalDrop. Keep changes focused on the existing app and preserve established Android, server, and browser behavior.

## Before opening a change

1. Explain the problem and expected behavior.
2. Make the smallest change that addresses it.
3. Add focused tests for changed behavior where practical.
4. Do not include real access codes, shared files or text, personal network details, signing credentials, or generated build outputs.

## Build and validate

Use JDK 17 and Android SDK Platform 36. On Windows, run from the project root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

If you change release build configuration, also run `.\gradlew.bat :app:lintRelease`. A signed release build requires the environment variables documented in [README.md](./README.md); never commit a keystore or credential values.

## Attribution and licensing

This is an AI-assisted project with human review and responsibility. If AI tools assisted a contribution, describe that assistance accurately and do not claim unverified work or independent AI authorship.

LocalDrop is licensed under the MIT License; see [LICENSE](./LICENSE).

For security-sensitive reports, follow [SECURITY.md](./SECURITY.md) instead of posting secrets or exploit details publicly.
