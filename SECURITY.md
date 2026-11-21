# Security notes

## Release signing
Never publish an APK signed with the debug key. Provide either `keystore.properties` (git-ignored) at the repo root:

```
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

or the environment variables `FLUXA_KEYSTORE_FILE`, `FLUXA_KEYSTORE_PASSWORD`, `FLUXA_KEY_ALIAS`, `FLUXA_KEY_PASSWORD`.
In GitHub Actions add the secrets `FLUXA_KEYSTORE_BASE64` (`base64 -w0 release.jks`), `FLUXA_KEYSTORE_PASSWORD`,
`FLUXA_KEY_ALIAS`, `FLUXA_KEY_PASSWORD`. Tagged releases fail without them.

## Hardening in place
- Manifest: no backup, no cleartext traffic, system-CA-only network config, internal components not exported,
  system services protected by their bind permissions.
- Native: stack protector, FORTIFY_SOURCE, stack-clash protection, zero-init locals, PAC/BTI (arm64) / CET (x86_64),
  full RELRO, non-executable stack; JNI inputs bounds-checked.
- Release build: R8 full mode, log stripping, v2/v3 signing only.
- CI: read-only token by default, Gradle wrapper validation, signing secrets never exposed to pull requests.

## Verify your download
Official release APKs are signed with the developer's release key. After installing, open
**About > Verify this app** (ตรวจสอบความถูกต้องของแอพ) and compare the SHA-256 shown there with the value below.
If it differs, or the screen warns about the debug key, do not use that build.

Release signing certificate SHA-256: `TODO: paste the value printed by the CI "Release signing certificate" summary`

You can also check an APK on a computer:

```
apksigner verify --print-certs app-release.apk
sha256sum app-release.apk      # compare with SHA256SUMS.txt attached to the GitHub release
```

## Reporting a vulnerability
Please report security problems privately (GitHub "Report a vulnerability" on the repository's Security tab, or
through the contact links in the app's About screen) instead of opening a public issue. Include the app version,
Android version, and steps to reproduce.
