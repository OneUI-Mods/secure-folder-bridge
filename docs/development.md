# Development

Sources are Kotlin for Android/root control and C for the mount operations. No Xposed hook, network server, boot receiver, or file copy/synchronization service is used.

## Build

Use JDK 21, Android SDK/build-tools 37, and NDK 28.2.13676358. The native mount helper is currently arm64-only. Gradle supports macOS and Linux NDK host layouts.

```sh
SFB_SIGNING_PROPERTIES=/private/path/signing.properties ./gradlew :app:assembleRelease :app:lintRelease
```

The external UTF-8 properties file contains `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Keep it and the signing key outside Git. Only non-debuggable, continuously signed release APKs are distributed.

## Storage and lifecycle

The fixed source is `/data/media/150`. The native helper accepts only target user0 or user10 and maps those to `/data/media/0/SecureFolder` or `/data/media/10/SecureFolder`. An existing unrelated target folder is rejected. Each target has a separate root-owned control subdirectory. The native helper rejects unowned mounts and only detaches a mount whose filesystem root matches the Secure Folder shared-storage root. Work-profile availability is checked separately; locking or pausing it detaches only its keeper, while the main-profile connection stays active.

Read/write uses a bind mount. Read-only uses `open_tree`, `mount_setattr(MOUNT_ATTR_RDONLY)`, and `move_mount` so the mount is read-only before propagation to MediaProvider. A later remount does not reliably update copies in other mount namespaces and must not be used as a read-only boundary.

An app foreground service owns a root controller pipe. The root controller checks the actual TrustManager and UserManager services for user0/user150 and separate native keepers own the user0/user10 mounts. Closing the app controller, including APK replacement or process death, closes the native keeper's input and detaches the mount. SIGPIPE is ignored so a closed output pipe cannot terminate the keeper between mounting and EOF cleanup. While connected, the root controller checks the user/profile lock state every 750ms using Binder. The app also handles screen-off immediately. When disconnected there is no resident controller or polling loop.

The bridge neither clears credentials nor opens encrypted storage before normal unlock. Already-open file descriptors and copies owned by other apps are outside the scope of pathname detachment.

## Why profile entries are bind mounts

The main-profile entry remains `/storage/emulated/0/SecureFolder`; the work-profile entry is `/storage/emulated/10/SecureFolder`. Both resolve to the same underlying shared-storage files, without copying or synchronization. The work entry does not depend on permission to traverse another user's emulated-storage path.

Symbolic links were tested with synthetic files on the Galaxy S26 Ultra / Android 17 build `CP2A.260605.016.S948QOPU1BZI9`. Creating a link through emulated storage returned `Function not implemented`. A link created as root under `/data/media` existed in the backing filesystem, but both same-profile and cross-profile attempts to follow it through emulated storage returned `Permission denied`. Therefore the bridge uses directory bind mounts for file-manager access and does not install unusable symbolic links. This is a result for the tested device build, not a claim about every Android filesystem.

## Verification

Device tests use synthetic files under a uniquely named Secure Folder subdirectory and both users' Download directories. Work-profile command tests use the My Files UID with a storage-access group in the existing root context; they do not prove the actual app's SELinux domain or GUI behavior. Validate normal-UID read/write, edit, rename, create/delete, read-only rejection, and disconnection on pipe closure. Use a separately compiled helper with only its fixed test paths replaced; never add arbitrary source/target path arguments to the release helper. Actual GUI and physical lock transitions must be reported separately from command-level validation.
