# FastScanner

<img src="icon-512.png" width="128" alt="FastScanner icon"/>

Android document scanner that uploads straight to a **WebDAV** server **or** an **SMB (SMB2/SMB3)** network share — no file-name prompt, no extra questions.

## Features

- **Google ML Kit Document Scanner** (same engine as "WebDAV Scan"): automatic edge detection, auto-capture, crop and perspective correction, filters (color, grayscale, B/W), shadow / stain / finger removal.
- **Single scan**: tap → frame → shoot → *Fine* → uploaded.
- **Multi scan**: tap → shoot as many pages as you want → *Fine* → uploaded (one multi-page PDF and/or numbered JPGs).
- **Automatic file name**: `<prefix>yyyy-MM-dd_HH-mm-ss` (e.g. `scan_2026-10-28_15-47-12.pdf`).
  Two scans in the same second get `-2`, `-3`; the upload uses `If-None-Match: *` so an existing remote file is **never overwritten**.
- **Output format** (settings): PDF, JPG, or both. Multi-page JPGs are named `..._01.jpg`, `..._02.jpg`.
- **Reliable upload queue** (WorkManager): waits for network, retries with backoff, survives app close/reboot; live status on the main screen.
- **WebDAV**: server URL, port, remote folder (created automatically if missing), Basic auth, password encrypted with Android Keystore, optional **certificate pinning** for self-signed servers (trust-on-first-use, SHA-256), http allowed for LAN/NAS with an explicit warning.
- **SMB2 / SMB3** (alternative to WebDAV, selected with a toggle in settings):
  - dialects SMB 3.1.1 → 2.0.2 only (SMB1 never used), message signing required by default, optional mandatory SMB3 AES encryption, optional DFS;
  - **local accounts**: user + password, domain empty;
  - **domain accounts**: domain field (e.g. `CONTOSO`), or `CONTOSO\user`, or UPN `user@contoso.local`;
  - authentication NTLMv2 (Kerberos is not available on Android);
  - target folder tree created automatically, files created with `FILE_CREATE` so nothing is ever overwritten.
- **Languages**: Italian on Italian devices, English everywhere else.
- **About** screen with credits, license and third-party components.
- **Launcher shortcuts**: long-press the icon → *Scansione singola* / *Scansione multipla* to open the camera directly.

## Settings examples

| Server | Server field | Remote folder |
|---|---|---|
| Nextcloud | `https://cloud.example.com` | `/remote.php/dav/files/USER/Scans` |
| ownCloud | `https://oc.example.com` | `/remote.php/webdav/Scans` |
| Synology (WebDAV pkg) | `https://nas.local` port `5006` | `/home/Scans` |
| Apache / nginx | `http://192.168.1.10` port `8080` | `/dav/scans` |

### SMB examples

| Case | Host | Share | Subfolder | Domain | User |
|---|---|---|---|---|---|
| NAS, local account | `192.168.1.10` | `Scansioni` | | | `mario` |
| Windows PC, local account | `PC-UFFICIO` | `Scan` | `In` | | `mario` |
| File server, domain account | `fs01.contoso.local` | `Dati` | `Scansioni/2026` | `CONTOSO` | `mario` |
| Pasted UNC path | `\\fs01\Dati\Scansioni` | | | | `CONTOSO\mario` |

Use **Prova connessione** to verify credentials and create the folder.

## Security notes

- Passwords (WebDAV and SMB) are encrypted with an AES-256-GCM key stored in the Android Keystore; backup and device-to-device transfer are disabled.
- No "trust all certificates" mode. Self-signed / local https servers (NAS, routers, IP addresses): *Test connection* shows the certificate (subject, issuer, expiry, SHA-256) and lets you trust it; it is then pinned. If the certificate changes (e.g. renewal), uploads stop and *Test connection* shows old/new fingerprints so you can accept the new one. The certificate probe sends no credentials.
- HTTP redirects are not followed, so credentials are never sent to another host.
- `http://` with credentials requires explicit confirmation (Basic auth would travel in clear text).
- SMB: SMB1 disabled, signing required by default, optional mandatory SMB3 encryption.
- Remote paths containing `.` / `..` are rejected; generated file names are sanitized.
- Storage: scanner output is deleted from the cache as soon as it is queued; each local file is deleted right after its successful upload. Failed uploads keep their files for 3 days (tap the item → *Retry* / *Delete*), then they are deleted automatically. Orphan files and scanner leftovers are cleaned at every app start; *Clear list* deletes everything that is not waiting to upload.
- User-installed CAs are trusted (to support private/corporate CAs); remove them from the device if not needed.

## Build

Requirements: JDK 17, Android SDK 35 (Android Studio Ladybug or newer).

```bash
./gradlew assembleRelease     # app/build/outputs/apk/release/FastScanner-<version>-release.apk
```

Or push the repo to GitHub: the included workflow (`.github/workflows/build.yml`) builds debug and release APKs and attaches them as artifacts (and to a GitHub Release when you push a `v*` tag).

The release build is signed with the debug key so it installs directly; configure your own `signingConfig` for distribution.

Requires Google Play Services on the device (the ML Kit scanner module is downloaded automatically on first use). Min Android 7.0 (API 24).

## License

GPL-3.0 — see [LICENSE](LICENSE).

## Author

https://github.com/Leproide
