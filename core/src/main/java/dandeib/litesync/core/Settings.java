package dandeib.litesync.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** config/litesync/settings.json; never synced. */
public final class Settings {

    public static final List<String> SCHEMATIC_EXTENSIONS = List.of(".litematic");
    public static final List<String> EXTRA_EXTENSIONS = List.of(".schem", ".nbt");
    /** Machine-specific: the custom schematic folder is a local path. */
    public static final List<String> DEFAULT_BLACKLIST =
            List.of("Generic.customSchematicBaseDirectory", "Generic.customSchematicBaseDirectoryEnabled");

    /** Folder shared by all instances; empty = {@link #defaultSyncFolder()}. */
    public String localSyncFolder = "";
    public List<String> extensions = new ArrayList<>(SCHEMATIC_EXTENSIONS);
    public boolean syncLitematicaConfig = true;
    public boolean syncMalilibConfig = false;
    public boolean syncHotkeys = true;
    public List<String> configBlacklist = new ArrayList<>(DEFAULT_BLACKLIST);
    /** Schematics from newer MC versions: false = download with a notice, true = hide in this instance. */
    public boolean hideNewerSchematics = false;

    /** Loads the file, or creates it with defaults. */
    public static Settings load(Path file) throws IOException {
        Settings s = Json.read(file, Settings.class);
        if (s == null) {
            s = new Settings();
            Json.write(file, s);
        }
        if (s.localSyncFolder == null) s.localSyncFolder = "";
        if (s.extensions == null || s.extensions.isEmpty()) s.extensions = new ArrayList<>(SCHEMATIC_EXTENSIONS);
        if (s.configBlacklist == null) s.configBlacklist = new ArrayList<>(DEFAULT_BLACKLIST);
        return s;
    }

    /** %APPDATA%\LiteSync on Windows, ~/.litesync elsewhere: outside of every instance. */
    public static Path defaultSyncFolder() {
        String appData = System.getenv("APPDATA");
        return appData != null && !appData.isBlank()
                ? Path.of(appData, "LiteSync")
                : Path.of(System.getProperty("user.home"), ".litesync");
    }

    /** @throws java.nio.file.InvalidPathException if the configured folder is not a valid path */
    public Path syncFolder() {
        return localSyncFolder.isBlank() ? defaultSyncFolder() : Path.of(localSyncFolder).toAbsolutePath();
    }

    public void save(Path file) throws IOException {
        Json.write(file, this);
    }

    public boolean extraExtensions() {
        return extensions.containsAll(EXTRA_EXTENSIONS);
    }

    public void setExtraExtensions(boolean on) {
        extensions = new ArrayList<>(SCHEMATIC_EXTENSIONS);
        if (on) extensions.addAll(EXTRA_EXTENSIONS);
    }
}
