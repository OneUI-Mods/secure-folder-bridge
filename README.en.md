# Secure Folder Bridge

[日本語](README.md) · English

A rooted Galaxy app that exposes Secure Folder's shared files as ordinary folders in main-profile and work-profile file managers. It connects **Internal storage / SecureFolder** to the original shared storage without copying files.

## Automatic startup after boot

Automatic startup after device boot can be switched on or off in settings. The default is on when no choice has been saved; an existing off choice is preserved. Startup only restores configured behavior and does not override feature switches, permissions, or lock-state restrictions.

## Interface and settings

Material 3 components and colors follow the device's light/dark theme. The top card shows connection state and read/write mode. Each profile's destination card can copy its path. Connection options are locked while connected; errors appear in a separate card. Licenses are in the top-right menu.

The app supports nine languages: Japanese, English, Simplified/Traditional Chinese, Korean, German, French, Spanish, and Brazilian Portuguese. Choose **Language** from the top-right menu to change only this app's language. The default follows the device.

## Usage

1. Install the app and grant root in KernelSU.
2. Unlock the phone and Secure Folder.
3. Enable **Connect folder**.
4. Open **Internal storage / SecureFolder** in Samsung My Files or another file manager.

**Show in work profile** is ON by default. It adds `SecureFolder` to the internal storage of both the main user (0) and work user (10). Both connect to the same original files and share edits. Locking or pausing the work profile disconnects only that side; it reconnects when available. Without a work profile, only the main side operates and the work-profile setting is disabled.

Each profile's `SecureFolder` is an entry point to the same data, not a duplicate. Symbolic links cannot be opened through shared storage on the tested device, so the bridge uses a connection that ordinary file managers can read and write as a directory.

The default is **read/write**. Creating, editing, moving, and deleting files affects the originals inside Secure Folder directly. Enable **Read only** before connecting if you need to prevent changes.

Normally, a notification and root monitor run only while connected. Screen-off, phone/Secure Folder locking, or controller termination disconnects the folders.

Enable **Connect after startup** to wait for the phone and Secure Folder to unlock after boot, then connect once. It defaults ON; an existing OFF choice is preserved. Grant root beforehand. A notification remains visible while waiting; turning the connection OFF cancels waiting. The app never unlocks the phone or profile for you. After screen-off/lock disconnects an established connection, it does not automatically reconnect again before the next boot.

## Supported scope

- KernelSU with root and access to the global mount namespace is required.
- The current target is a Galaxy whose Secure Folder runs as user 150.
- Built with SDK 37 for Android 16+. Physical command-level tests used Galaxy S26 Ultra / Android 17; Android 16 has not been revalidated on hardware.
- Only shared storage such as photos and Downloads is connected. Private app-internal data is excluded.
- The file manager needs access to its own profile's shared storage. Paths are `/storage/emulated/0/SecureFolder` and `/storage/emulated/10/SecureFolder`.
- Read-only mode uses Linux detached-mount APIs. Missing APIs cause a failure, never a silent fallback to read/write.

While connected, apps with shared-storage access in the enabled profiles can see these files. Disconnecting closes new pathname access; it does not erase already-open files, thumbnails, or copies saved by other apps. The bridge does not change credentials, screen locks, encryption, original-file ownership, or SELinux policy.

## Verification

Synthetic temporary folders were used to verify reading/writing, creation, rename, deletion, shared edits between profiles, rejection of writes in read-only mode, and disconnection after controller exit. File-manager GUI behavior and physical screen-off/lock operations are separate verification items.

See the [developer documentation](docs/development.md) for source, build instructions, and verification scope.

## License

The app uses the MIT License. Material Components, AndroidX, and Kotlin Apache 2.0 texts are bundled in the app. Signing keys and passwords are excluded from source and distributions.

[Build environment / ビルド環境](docs/build-environment.md)
