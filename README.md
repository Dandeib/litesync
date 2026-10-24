# LiteSync

Fabric add-on for Litematica: syncs Litematica settings and schematics between all of a player's instances through a
shared folder on the PC. No server; instances are matched by Minecraft UUID.

## Usage

- **GUI:** sync button right of "Singleplayer" on the title screen, Mod Menu → LiteSync, or the `/litesync` command
- **Commands:** `/litesync now` (pull + push), `/litesync status`
- Sync runs automatically when the title screen is reached, every 10 s when there are local changes, and on exit.

What gets synced:

| What | Local | Method |
|---|---|---|
| Schematics | `schematics/**/*.litematic` (optionally `.schem`, `.nbt`) | File sync with conflict copies |
| Litematica config | `config/litematica.json` | Merged per key, newest value wins |
| malilib config | `config/malilib.json` (off by default) | Same as above |

Older Litematica versions only take keys they know; unknown keys are kept in the sync folder.
Excluded by default: `Generic.customSchematicBaseDirectory` and `Generic.customSchematicBaseDirectoryEnabled`
(local paths).

## Storage

Default folder: `%APPDATA%\LiteSync` (Windows) or `~/.litesync` (elsewhere). It can be changed in the GUI, e.g. to a
folder that a cloud client (OneDrive, Nextcloud, …) already syncs.

Inside it, `<uuid>/` holds `manifest.json` and `blobs/<sha256>`. File contents are stored as immutable blobs and the
manifest maps paths to them. Deleted files keep their blob and can be restored from it.
Files deleted locally go to `config/litesync/trash/`.

Google Drive is planned for later; another storage backend only needs a new `StorageProvider` implementation.

## Testing

The Gradle runs `runClient` and `runClient2` start two instances with the same UUID and separate game directories.

## Build

```
./gradlew build
```

The core (`core/`) is plain Java 21 without Minecraft dependencies and has its own tests (`./gradlew :core:test`).

## License

[MIT](LICENSE)
