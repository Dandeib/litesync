package dandeib.litesync.core;

import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Local state after the last sync; never synced itself. */
public final class SyncState {

    public String instanceId;
    /** Path → SHA-256 of files that were identical locally and remotely after the last sync. */
    public Map<String, String> base = new TreeMap<>();
    /** Hash cache for the FileScanner, paths relative to the schematics folder. */
    public Map<String, FileScanner.ScannedFile> scanCache = new HashMap<>();
    /** Config file → local key values after the last sync. */
    public Map<String, Map<String, JsonElement>> configBase = new TreeMap<>();

    public static SyncState load(Path file) throws IOException {
        SyncState s = Json.read(file, SyncState.class);
        if (s == null) s = new SyncState();
        if (s.instanceId == null) {
            byte[] b = new byte[2];
            new SecureRandom().nextBytes(b);
            s.instanceId = "inst-" + HexFormat.of().formatHex(b);
        }
        if (s.base == null) s.base = new TreeMap<>();
        if (s.scanCache == null) s.scanCache = new HashMap<>();
        if (s.configBase == null) s.configBase = new TreeMap<>();
        return s;
    }

    public void save(Path file) throws IOException {
        Json.write(file, this);
    }
}
