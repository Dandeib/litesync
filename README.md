# LiteSync

A Fabric add-on for [Litematica](https://modrinth.com/mod/litematica) that keeps your **schematics** and
**Litematica settings** in sync across all of your Minecraft instances.

Save a schematic in one instance and it shows up in every other one. Change a Litematica setting and every instance
picks it up. No server and no account are needed: everything goes through a shared folder on your PC, and instances
are matched by your Minecraft UUID.

## Features

- **Schematic sync:** `.litematic` files (and optionally `.schem` / `.nbt`), including subfolders, new files, edits
  and deletions.
- **Settings sync:** Litematica's config (and optionally malilib's) is merged **per setting**, so changing different
  settings in two instances never overwrites the other one.
- **Safe by design:** conflicts become copies instead of overwriting, files deleted by another instance go to a
  local trash instead of disappearing, and file contents in the sync folder are never overwritten.
- **Version aware:** schematics saved with a newer Minecraft version can be downloaded with a notice or hidden.
- **Works across PCs:** point the sync folder at a OneDrive, Nextcloud, Dropbox or Syncthing folder.
- **Settings screen** in the vanilla style, reachable from the title screen, Mod Menu or a command.

## Requirements

There is one jar per Minecraft version:

| Minecraft | LiteSync jar | Litematica (tested) | malilib (tested) | Mod Menu (optional) |
|---|---|---|---|---|
| 26.1 – 26.1.2 | `litesync-<version>+26.1.2.jar` | 0.27.14 | 0.28.12 | 18.0.2 |
| 26.2 | `litesync-<version>+26.2.jar` | 0.28.8 | 0.29.6 | 20.0.3 |
| 26.3 | `litesync-<version>+26.3.jar` | 0.29.1 | 0.30.2 | 21.0.0 |

All versions need Fabric Loader 0.20.1 or newer, Fabric API and Java 25 or newer. LiteSync is client-side only.

Instances on different Minecraft versions can share one sync folder. Settings an older Litematica does not know stay
in the sync folder, and schematics from newer versions are handled as described below.

## Installation

1. Install Fabric, Fabric API, Litematica and malilib as usual.
2. Put the LiteSync jar for that instance's Minecraft version into the `mods` folder of **every** instance you want
   to keep in sync.
3. Start the game. LiteSync starts working as soon as the title screen appears.

Every instance that uses the same sync folder and the same Minecraft account shares its schematics and settings.
With the default settings that is every instance on this PC.

## Usage

### Opening the settings

- the sync button (circular arrows) right of **Singleplayer** on the title screen
- Mod Menu → LiteSync → configure
- the `/litesync` command in-game

### Commands

| Command | What it does |
|---|---|
| `/litesync` | Opens the settings screen |
| `/litesync now` | Syncs right away |
| `/litesync status` | Shows folder, status, last sync and the last error in chat |

### When does it sync?

- when the title screen is reached (on game start),
- every 10 seconds, if something changed locally,
- when the game closes (local changes are uploaded; waits at most 10 seconds),
- when you press **Sync now** or run `/litesync now`.

Settings written by a sync are reloaded into Litematica right away, so no restart is needed.

## Settings

All settings are in the settings screen and are stored per instance in `config/litesync/settings.json`.
Hovering over a button shows a description.

| Setting | Default | Meaning |
|---|---|---|
| **Sync folder** | empty (= default folder) | Where the shared data is stored. Instances with the same folder share everything. |
| **Litematica config** | ON | Share Litematica's settings (`config/litematica.json`). |
| **malilib config** | OFF | Also share malilib's settings (`config/malilib.json`). |
| **Hotkeys** | ON | Share key bindings together with the configs. |
| **.schem / .nbt** | OFF | Also sync Sponge/WorldEdit (`.schem`) and vanilla structure (`.nbt`) files. |
| **Newer MC** | Show + notify | What to do with schematics saved in a newer Minecraft version (see below). |
| **Config blacklist** | see below | Config values that are never synced and stay per instance. |

### Config blacklist

A comma-separated list of either whole categories (`Generic`) or single settings (`Generic.someSetting`).
By default it contains

- `Generic.customSchematicBaseDirectory`
- `Generic.customSchematicBaseDirectoryEnabled`

because the custom schematic folder is a path on one machine.

The **Hotkeys** switch only covers the category named `Hotkeys`. malilib keeps its two hotkeys under `Generic`; add
them to the blacklist if you sync the malilib config but want per-instance key bindings.

## How syncing works

### Schematics

LiteSync compares three states for every file: this instance, the sync folder, and the state after the last sync.

| Situation | Result |
|---|---|
| New or changed here | Uploaded |
| New or changed in another instance | Downloaded |
| Deleted here | Deleted in all other instances (moved to their trash) |
| Changed in two instances before they synced | **Conflict:** the other version keeps the name, yours is kept as `name (conflict <instance> <date>).litematic` and synced too |
| Deleted here, changed in another instance | The change wins, the file comes back |

A conflict shows a notification. Nothing is ever lost.

### Settings

Each setting is merged on its own. If two instances changed the same setting, the newest change wins.

- A **new instance** takes over the shared settings on its first sync instead of overwriting them with defaults,
  even if Litematica has not written its config file yet.
- Settings an older Litematica version does not know are **not** written into its config, but are kept in the sync
  folder for newer versions.
- A setting whose type differs between versions is skipped and logged.
- Values on the blacklist are never uploaded or written.

### Schematics from newer Minecraft versions

When a schematic is uploaded, LiteSync stores the Minecraft data version it was saved with.

- **Show + notify:** it is downloaded anyway and a notification tells you. Blocks that don't exist in this version
  may be missing.
- **Hide:** it is not downloaded into this instance. Copies that are already there are kept.

### Deleting schematics

Delete the file in any instance, either in the game or in the file explorer. The deletion reaches the other
instances on their next sync, and they move their copy to `config/litesync/trash/<date>/schematics/…`.

If you delete a file while the game is closed, the deletion is picked up the next time **that** instance starts.

Deleting the **whole** `schematics` folder is treated as a reset of that instance: everything is downloaded again
and nothing is deleted elsewhere. To delete schematics everywhere, delete the files themselves.

### Restoring a deleted schematic

Move it from `config/litesync/trash/…` of any instance back into that instance's `schematics` folder. It is uploaded
again as a new file and reaches every instance.

The trash is never emptied automatically; clean up `config/litesync/trash` yourself if it gets large.

## Storage

Default sync folder:

| OS | Path |
|---|---|
| Windows | `%APPDATA%\LiteSync` |
| macOS / Linux | `~/.litesync` |

Layout:

```
<sync folder>/
└── <minecraft uuid>/
    ├── manifest.json        list of all files, their hashes and deletions
    └── blobs/<sha256>       file contents, one file per version, never overwritten
```

Per instance, in `config/litesync/`:

| File | Purpose |
|---|---|
| `settings.json` | LiteSync settings of this instance |
| `state-<uuid>-<folder>.json` | what was in sync after the last run (one per sync folder) |
| `manifest-backup-<uuid>-<folder>.json` | copy of the last manifest, used if the shared one gets corrupted |
| `trash/` | schematics deleted by other instances |

### Syncing between PCs

Set the sync folder on every PC to a folder that a cloud client already syncs (OneDrive, Nextcloud, Dropbox,
Syncthing, a network drive …). LiteSync only ever writes new blobs and replaces `manifest.json` in one step, which
copes well with such clients. Avoid running the game on two PCs at the exact same moment, because the cloud client
may need a moment to copy changes over.

## Troubleshooting

| Problem | Cause / fix |
|---|---|
| Status says "Disabled: offline account" | Offline accounts have no stable UUID, so LiteSync turns itself off. Use a Microsoft account. |
| Status says "Invalid sync folder" | The path in the settings is not valid. Clear the field to use the default folder. |
| Nothing syncs to a new instance | Check that both instances use the same sync folder and account (`/litesync status`). |
| A schematic came back after deleting it | Another instance had changed it before it learned about the deletion; the change wins. Delete it again. |
| A mouse-button hotkey is lost or changes back after switching versions | See the limitation above: older versions cannot read the mouse button names of 26.3. Use keyboard keys or blacklist the hotkey. |
| A file named `… (conflict …)` appeared | Two instances changed the same schematic. Keep the version you want and delete the other. |
| Errors | Shown in the settings screen, in a notification and in `logs/latest.log` (search for `litesync`). |

## Limitations

- Only local folders are supported as storage. Google Drive is planned; a new backend only needs a new
  `StorageProvider` implementation in the core.
- Files are held in memory while syncing, which is fine for schematics up to a few tens of MB.
- Only the default `schematics` folder is synced, not a custom schematic folder set in Litematica.
- **Hotkeys that use mouse buttons are not portable between Minecraft 26.3 and older versions.** malilib 0.30 (26.3)
  names mouse buttons differently (`MOUSE_BACK`, `BUTTON_4`, …) than malilib 0.27–0.29 (1.21.11, 26.1, 26.2). An older
  version cannot read the new names, drops them and saves the hotkey without the mouse button, which is then synced
  back. Use keyboard keys for hotkeys you share across these versions, or add the hotkey to the config blacklist
  (for example `Hotkeys.pickBlockToggle`, or `Hotkeys` for all of them).

## Development

### Build

Requires JDK 25 or newer.

```bash
./gradlew build
```

This builds every Minecraft version. The jars are written to `build/libs/litesync-<version>+<minecraft>.jar`, with
the core bundled inside. A single version is built with `./gradlew :26.2:build`.

### Multiple Minecraft versions

The project uses [Stonecutter](https://stonecutter.kikugie.dev/). All versions share the same sources; code that
differs between versions is switched with comments:

```java
//? if >=26.2 {
client.gui.setScreen(screen);
//?} else
/*client.setScreen(screen);*/
```

| File | Purpose |
|---|---|
| `settings.gradle` | List of Minecraft versions |
| `stonecutter.gradle` | Active version (the one the sources in `src/` are written for) |
| `versions/<minecraft>/gradle.properties` | Minecraft, Fabric API, Litematica, malilib and Mod Menu versions |
| `gradle.properties` | Settings shared by all versions (mod version, loader, Loom) |

To work on another version in the IDE, run the Gradle task `Set active project to 26.1.2`. Run
`Reset active project` before committing, so `src/` is always committed in the 26.3 state.

To add a Minecraft version, add it to `settings.gradle`, create `versions/<minecraft>/gradle.properties` and fix
whatever fails to compile with version comments.

### Run and test

| Command | Purpose |
|---|---|
| `./gradlew :26.3:runClient` | Starts a test client (any version works in place of `26.3`) |
| `./gradlew :26.3:runClient2` | Starts a second test client with the same player but its own game folder (`run2/`) |
| `./gradlew :core:test` | Runs the core unit tests |

### Project structure

| Path | Contents |
|---|---|
| `core/` | Sync logic in plain Java 21, no Minecraft dependencies, with unit tests |
| `src/client/` | Fabric client: lifecycle, commands, title screen button, settings screen, Mod Menu entry |
| `versions/` | Per-version dependency versions; build output of each version |
| `src/main/resources/` | `fabric.mod.json`, icon and GUI sprite |

Key classes in the core:

| Class | Role |
|---|---|
| `SyncEngine` | Three-way sync of schematics and configs against the manifest |
| `ConfigMerger` | Per-key merge of malilib-style config files |
| `Manifest` | The shared file list, including deletions and per-key timestamps |
| `StorageProvider` / `LocalFolderProvider` | Storage backend interface and the local folder implementation |
| `FileScanner` | Scans the schematics folder and caches hashes |
| `SchematicInspector` | Reads the Minecraft data version from a `.litematic` file |
| `Settings` / `SyncState` | Per-instance settings and sync state |

## License

[MIT](LICENSE)
