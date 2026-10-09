# ALPHA NEW (Android Local Deploy Manager)

> **"PM2 + cPanel บนมือถือ Android"** — Natively deploy and manage Discord bots (Node.js/Python), PHP web servers, MariaDB, and static websites behind Caddy reverse proxy without root.

- **Package Name:** `com.alphanew.deploy`
- **Application Label:** `ALPHA NEW`
- **Runtime Prefix:** `/data/data/com.alphanew.deploy/files/usr`

---

## 🌟 Key Features

- **No Root Required:** Operates within Android application sandbox with `targetSdk 28` allowing direct binary execution.
- **4-Layer Anti-Kill Engine:**
  - Layer 1: Dedicated Foreground Service (`:supervisor` process), Partial WakeLock, WifiLock, Battery Optimization bypass, OEM Autostart helpers.
  - Layer 2: Process Watchdog with Exponential Backoff (2s, 4s, 8s... max 5m).
  - Layer 3: Health Checker (HTTP, TCP, Log/File Heartbeat).
  - Layer 4: System Boot Auto-recovery (`BOOT_COMPLETED`) with staggered start.
- **Smart Project Detector & Wizard:** Detects `discord.js`, `discord.py`, PHP, WordPress, and Static HTML projects automatically from directories or ZIP archives.
- **Single Front Door (Caddy Reverse Proxy):** All services accessible under unified `http://localhost:8080/` with dynamic Caddyfile generation and hot reloads.
- **cPanel-Style Management Panel:**
  - Real-time CPU% and RAM (RSS) metrics from `/proc/<pid>/stat`
  - Live auto-scrolling log console with rolling logs (2MB x 5 files)
  - Interactive `.env` variable manager with secret masking
  - AES-GCM encrypted single-file backup & restore
  - Local port manager (8000–8999 safe allocation)

---

## 🏗️ Architecture & Modules

The project is structured with Clean Multi-Module Architecture:

| Module | Description |
|---|---|
| `:core-model` | Domain models, enums (`ProjectStatus`, `RuntimeType`, `HealthCheckType`), and DTOs |
| `:core-database` | Persistent database storage for projects, metrics, and event audit trails |
| `:core-supervisor` | Process lifecycle (`ProcessBuilder`), procfs metrics reader, Watchdog, LogRotator |
| `:core-projects` | Smart project type detector, ZIP unpacker with Zip-Slip protection, `.env` parser |
| `:core-proxy` | Dynamic Caddyfile generation and zero-downtime hot-reload API integration |
| `:core-packages` | Native runtime package manager, streaming Tar.gz extractor, SHA-256 verifier |
| `:core-backup` | Backup archive creator, AES-256-GCM encryption/decryption, and safe restore |
| `:core-antikill` | Keep-alive ecosystem: Battery optimization, OEM autostart intents, and Phantom Process Killer ADB guide |
| `:ui` | Jetpack Compose + Material 3 UI (4 main tabs + Detail panel + 4-step Wizard + Onboarding) |
| `:app` | Android Application entry, Manifest, Foreground Service (`:supervisor`), and BootReceiver |

---

## 🚀 Building & Testing

### Prerequisites
- JDK 17
- Android SDK (API 34 compileSdk, API 28 targetSdk)
- Gradle 8.7+

### Run All Unit Tests
```bash
./gradlew test
```

### Build Debug APK
```bash
./gradlew assembleDebug
```
The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📜 License
MIT License.