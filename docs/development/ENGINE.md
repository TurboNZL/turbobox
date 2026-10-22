# Engine modules in TurboBox

Pinned in `.turboengine.json`. What was taken, from where, and what was deliberately not.

| Module | Taken | Where it lives here | Differences |
|---|---|---|---|
| `release-pipeline` | yes | `.github/workflows/auto-pr.yml`, `check.yml`, `release.yml` | Only the APK job (no desktop, no server). `check.yml` also runs on pull requests (this app was written without a Gradle run). Signing: the four `ANDROID_*` secrets when present, else the committed keystore. |
| `updaters` | yes (Android part) | `AppUpdater.java`, `Releases.java`, `SettingsActivity` card, `file_paths.xml`, FileProvider and `REQUEST_INSTALL_PACKAGES` in the manifest | **A Java port** of the Kotlin files (this app has no Kotlin plugin). Same rules: the release list (pre-releases count, drafts skipped), the token never goes to the redirect target, first release with an `.apk`. `ReleasesTest` is the Kotlin `Releases` tested on the JVM. |
| `guides-and-docs` | yes | `docs/guides/` (bundled by Gradle `copyGuides`), `HelpActivity` | The engine's reader is a React tab; this one is a small Spannable renderer. |
| `logos` | yes | `branding/logo.json`, `branding/emblems/box.json`, `scripts/` | Emblem `box`, colour `#a6e22e`. The engine's `logos` module lists it. |
| `appearance`, themes | **no** | | No web client. `themes/turbobox` exists in the engine anyway. |
| `android-shell`, `termux-server`, `cloudflare-access`, `device-pairing`, `self-hosted-server`, `docker-self-host` | **no** | | TurboBox hosts other people's pages in a WebView, not its own web client, and has no server. |

## Going the other way
Candidates for the engine **once they have run on a device** (the engine takes code that already runs): a `web-session` module (Session, session.js, VirtualMic, InputRoute, MicTester, MicPreview), a `buffer-recorder` module (BufferRecorder, BufferStore), and `page-workflows` (WorkflowParser and the `T.step` half of session.js). Until then they live only here.
