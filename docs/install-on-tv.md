# Installing Rass_player on the Xiaomi TV

This guide is for the Xiaomi TV (model ELA4584EU, Android 11, Android TV / PatchWall).
You install the APK yourself with a USB stick and the TV's file manager. No computer connection is needed on the TV side.

Menu names can differ slightly between PatchWall versions. If a name below is not exact, look for the closest match.

---

## 1. Find the APK after building

Build the release APK on your computer:

```bash
scripts/build-apk.sh
```

The file is written to:

```
dist/Rass_player-<version>.apk
```

For a debug build, use `scripts/build-apk.sh --debug`. That creates `dist/Rass_player-<version>-debug.apk`.
Use the release build unless you are testing.

## 2. Copy the APK to a USB stick

1. Format the USB stick as **FAT32** or **exFAT**. Other formats are not readable by the TV.
2. Copy `Rass_player-<version>.apk` to the top level of the stick, or into a folder named `Rass`.
3. Safely eject the stick from your computer.

## 3. Plug the stick into the TV

1. Plug the USB stick into a USB port on the TV.
2. A notification or a file browser may open by itself. If not, use the steps in section 4.

## 4. Open the APK with a file manager

The TV needs a file manager that can open APK files.

- If you already have one (for example Xiaomi's built-in file manager or **TV File Manager**), open it.
- If you do not, install **File Commander** or a similar file manager from the Play Store on the TV first.

In the file manager:

1. Open the USB storage (it is usually named after the stick, or "USB").
2. Find `Rass_player-<version>.apk` and select it.
3. Choose **Install**.

## 5. Allow installs from the file manager

Android asks for permission the first time you install an APK from an app that is not a store. You allow it once per file manager.

If the TV shows "For your security, your TV is not allowed to install unknown apps from this source", follow these steps:

1. Open **Settings**.
2. Go to **Device Preferences** → **Security & restrictions** → **Unknown sources**.
3. Turn on the entry for your file manager (for example "File Commander" or "TV File Manager").
4. Go back and open the APK again, then choose **Install**.

On some PatchWall versions the path is **Settings** → **Apps** → **Special app access** → **Install unknown apps**. The name is the same either way.

## 6. Open the app

After the installation finishes, choose **Open**. You can also find the app later:

- On the home screen, scroll to the **Apps** row. Choose **See all apps** if you do not see it.
- If PatchWall hides it, use the Android TV home screen, or open **Apps** in the Settings menu.
- You can move it to the home row: highlight the app, press and hold **OK**, and choose the option to add it to the home screen.

## 7. Update to a newer version

1. Copy the new `Rass_player-<newer-version>.apk` to the USB stick.
2. Install it the same way as in sections 4 and 5.
3. Choose **Update** when asked. Your settings and data are kept, because the APK is signed with the same key.

After this first install, the app's built-in updater can install new versions for you.

## 8. Uninstall and troubleshooting

To uninstall: **Settings** → **Apps** → **Rass_player** → **Uninstall**. This also deletes the app's data (settings and cache).

| Message | Cause | What to do |
|---|---|---|
| **App not installed** | A different version with the same name is already installed and signed with another key. Usually this happens when a build from another computer or debug build is installed. | Uninstall the old version first (section 8). This **deletes the app's data**, so settings such as the Source URL must be entered again. |
| **Not enough storage** / "insufficient storage" | The TV is full. | Uninstall unused apps or clear cache in **Settings** → **Apps**, then try again. |
| **There was a problem parsing the package** / **Parse error** | The file is corrupted, incomplete, or not an APK. | Copy the file to the USB stick again. Check that the file name ends in `.apk`. Rebuild with `scripts/build-apk.sh` if needed. |
| The APK does not appear in the file manager | The stick is not FAT32/exFAT, or the file is in a folder the file manager does not show. | Reformat the stick as FAT32/exFAT, or look in the folder list for the USB storage. |
| "Install blocked" / "unknown sources" message | Install permission for the file manager is off. | Follow section 5 again. |

## 9. Publishing an update

1. Raise `versionCode` (and `versionName`) in `app/build.gradle.kts`, then build with `scripts/build-apk.sh`.
2. Compute the APK hash: `sha256sum Rass_player-<version>.apk`.
3. Compute the signing certificate hash: `apksigner verify --print-certs Rass_player-<version>.apk` and copy the
   `certificate SHA-256 digest` line.
4. Write `update.json` (see `docs/feed-examples/update.json` and `docs/feed-spec.md` section 16): `version_code`,
   `version_name`, `min_sdk`, one `apks` entry (`universal`, or one per ABI) with `url`, `sha256`, `size_bytes`,
   plus `signing_cert_sha256` and `release_notes_fa`.
5. Upload the APK and `update.json` to the same host and put the `update.json` URL in Settings.

**Important:** release builds are currently signed with the debug key. Keep using the same key for every build.
If the key changes, the app refuses the update (certificate mismatch) and Android refuses to install over the old app.
