package dandeib.litesync.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dandeib.litesync.core.FileScanner.ScannedFile;
import dandeib.litesync.core.Manifest.KeyStamp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Syncs an instance's schematics folder and configs with the shared storage.
 *
 * <p>File contents are stored immutably under blobs/&lt;sha256&gt;. The manifest maps paths to hashes and is the only
 * mutable file; it is only written conditionally. So two instances can never overwrite each other's file contents,
 * and deleted files remain available as blobs.
 */
public final class SyncEngine {

    public static final String MANIFEST = "manifest.json";
    public static final String LITEMATICA_CONFIG = "config/litematica.json";
    public static final String MALILIB_CONFIG = "config/malilib.json";
    static final String PREFIX = "schematics/";
    private static final String BLOBS = "blobs/";
    private static final Duration TOMBSTONE_MAX_AGE = Duration.ofDays(90);
    private static final int MAX_ATTEMPTS = 3;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final DateTimeFormatter CONFLICT_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TRASH_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss").withZone(ZoneId.systemDefault());

    /**
     * @param conflicts      conflict copies, relative to schematics/
     * @param configsWritten config files changed locally; they must be reloaded
     * @param newer          downloaded schematics from a newer MC version, relative to schematics/
     * @param hidden         schematics from a newer MC version that were not downloaded
     */
    public record Result(int uploaded, int downloaded, int deletedLocal, int deletedRemote, List<String> conflicts,
                         List<String> configsWritten, List<String> newer, int hidden, List<String> warnings) {
        public boolean changedAnything() {
            return uploaded + downloaded + deletedLocal + deletedRemote + conflicts.size() + configsWritten.size() > 0;
        }
    }

    /**
     * @param configFilter     false for config keys that are not synced
     * @param localDataVersion data version of the running game, 0 = unknown
     * @param manifestBackup   local copy of the last manifest, used if the remote one is corrupt; null = none
     */
    public record Options(Set<String> extensions, List<String> configFiles, Predicate<String> configFilter,
                          int localDataVersion, boolean hideNewerSchematics, Path manifestBackup) {
        public static Options of(Settings s, int localDataVersion, Path manifestBackup) {
            List<String> configs = new ArrayList<>();
            if (s.syncLitematicaConfig) configs.add(LITEMATICA_CONFIG);
            if (s.syncMalilibConfig) configs.add(MALILIB_CONFIG);
            return new Options(Set.copyOf(s.extensions), configs,
                    ConfigMerger.filter(List.copyOf(s.configBlacklist), s.syncHotkeys),
                    localDataVersion, s.hideNewerSchematics, manifestBackup);
        }
    }

    private final StorageProvider remote;
    private final Path gameDir;
    private final Path trashDir;
    private final String uuid;
    private final Options options;
    private final Clock clock;

    /** @param trashDir where files deleted by other instances are moved to */
    public SyncEngine(StorageProvider remote, Path gameDir, Path trashDir, String uuid, Options options, Clock clock) {
        this.remote = remote;
        this.gameDir = gameDir;
        this.trashDir = trashDir;
        this.uuid = uuid;
        this.options = options;
        this.clock = clock;
    }

    /**
     * Pull and push. Updates state but does not save it.
     *
     * @param writeConfigs false = only upload configs. Needed on shutdown, because Litematica saves its in-memory
     *                     state afterwards and would overwrite a freshly written file.
     */
    public Result sync(SyncState state, boolean writeConfigs) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return attempt(state, writeConfigs);
            } catch (RevisionConflictException e) {
                // Another instance just wrote the manifest: read it again and retry.
                if (attempt >= MAX_ATTEMPTS) throw e;
            }
        }
    }

    /** Cheap check whether anything changed locally since the last sync. */
    public boolean hasLocalChanges(SyncState state) throws IOException {
        if (!scanLocal(state).equals(managed(state.base))) return true;
        for (String rel : options.configFiles()) {
            Path file = gameDir.resolve(rel);
            if (!Files.isRegularFile(file)) continue;
            Map<String, JsonElement> base = state.configBase.get(rel);
            if (base == null) return true;
            try {
                if (!ConfigMerger.flatten(readConfig(file), options.configFilter()).equals(base)) return true;
            } catch (RuntimeException e) {
                // Being written right now; check again next round.
            }
        }
        return false;
    }

    private Result attempt(SyncState state, boolean writeConfigs) throws IOException {
        Instant now = clock.instant();
        List<String> warnings = new ArrayList<>();
        RemoteFile manifestFile = remote.stat(MANIFEST);
        String expectedRevision = manifestFile == null ? StorageProvider.MUST_NOT_EXIST : manifestFile.revision();
        boolean forceWrite = manifestFile == null;
        Manifest manifest;
        if (manifestFile == null) {
            manifest = new Manifest(uuid);
            // Empty storage (new or deleted folder): the old base no longer applies and would otherwise
            // move every local file to the trash as "deleted remotely".
            state.base = new TreeMap<>();
            state.configBase = new TreeMap<>();
        } else {
            byte[] data = remote.download(MANIFEST);
            try {
                manifest = Manifest.parse(data);
            } catch (Manifest.NewerFormatException e) {
                throw e;
            } catch (IOException e) {
                manifest = readBackup(e);
                warnings.add("Restored corrupt remote manifest from local backup");
                forceWrite = true;
            }
        }

        // A missing schematics folder means a reset, not "delete everything everywhere": download it all again.
        if (!Files.isDirectory(gameDir.resolve("schematics"))) state.base = new TreeMap<>();

        Map<String, String> local = scanLocal(state);
        Set<String> keys = new TreeSet<>(local.keySet());
        keys.addAll(manifest.files.keySet());
        keys.addAll(state.base.keySet());

        Actions a = new Actions(manifest, state.instanceId, now, warnings);
        for (String key : keys) {
            if (!isManaged(key)) continue;
            Manifest.Entry entry = manifest.files.get(key);
            if (entry != null && !entry.deleted() && !hasValidHash(entry)) continue;
            String l = local.get(key);
            String r = entry == null || entry.deleted() ? null : entry.sha256();
            String b = state.base.get(key);
            if (Objects.equals(l, r)) continue; // identical, or deleted on both sides

            boolean localChanged = !Objects.equals(l, b);
            boolean remoteChanged = !Objects.equals(r, b);
            if (!localChanged) {
                if (r == null) a.trashLocal(key); else a.pull(key, entry, l == null);
            } else if (!remoteChanged) {
                if (l == null) a.tombstone(key); else a.upload(key);
            } else if (l == null) {
                a.pull(key, entry, true); // deleted locally, changed remotely: the change wins
            } else if (r == null) {
                a.upload(key); // deleted remotely, changed locally
            } else {
                a.conflict(key, r);
            }
        }

        List<PendingConfig> configs = new ArrayList<>();
        for (String rel : options.configFiles()) {
            PendingConfig p = a.mergeConfig(rel, state.configBase.get(rel), writeConfigs);
            if (p != null) configs.add(p);
        }

        boolean pruned = manifest.pruneTombstones(now, TOMBSTONE_MAX_AGE);
        if (a.manifestChanged || pruned || forceWrite) {
            manifest.uuid = uuid;
            manifest.updatedAt = now.toString();
            manifest.updatedBy = state.instanceId;
            remote.upload(MANIFEST, manifest.toBytes(), expectedRevision);
        }
        if (options.manifestBackup() != null) Json.writeAtomic(options.manifestBackup(), manifest.toBytes());

        // Write local configs only after the manifest is stored, otherwise a retry would see them as local changes.
        List<String> configsWritten = new ArrayList<>();
        for (PendingConfig p : configs) {
            if (p.merge().newLocal() != null) {
                Json.writeAtomic(gameDir.resolve(p.rel()), Json.toBytes(p.merge().newLocal()));
                configsWritten.add(p.rel());
            }
            state.configBase.put(p.rel(), p.merge().newBase());
        }

        // New base: everything that is now identical locally and remotely.
        Map<String, String> after = scanLocal(state);
        Map<String, String> base = new TreeMap<>();
        Map<String, Manifest.Entry> files = manifest.files;
        after.forEach((key, hash) -> {
            Manifest.Entry e = files.get(key);
            if (e != null && !e.deleted() && hash.equals(e.sha256())) base.put(key, hash);
        });
        state.base = base;
        return new Result(a.uploaded, a.downloaded, a.deletedLocal, a.deletedRemote, a.conflicts,
                configsWritten, a.newer, a.hidden, warnings);
    }

    private Manifest readBackup(IOException cause) throws IOException {
        Path backup = options.manifestBackup();
        if (backup == null || !Files.isRegularFile(backup)) {
            throw new IOException("Remote manifest is corrupt and there is no local backup", cause);
        }
        return Manifest.parse(Files.readAllBytes(backup));
    }

    private static boolean hasValidHash(Manifest.Entry entry) {
        return entry.sha256() != null && SHA256.matcher(entry.sha256()).matches();
    }

    private static JsonObject readConfig(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    /** Scans locally and updates the hash cache; keys are prefixed with "schematics/". */
    private Map<String, String> scanLocal(SyncState state) throws IOException {
        Map<String, ScannedFile> scanned = FileScanner.scan(gameDir.resolve("schematics"), options.extensions(), state.scanCache);
        state.scanCache = scanned;
        Map<String, String> result = new HashMap<>();
        scanned.forEach((rel, f) -> {
            String key = PREFIX + rel;
            if (isManaged(key)) result.put(key, f.sha256());
        });
        return result;
    }

    private Map<String, String> managed(Map<String, String> map) {
        Map<String, String> result = new HashMap<>();
        map.forEach((k, v) -> { if (isManaged(k)) result.put(k, v); });
        return result;
    }

    /** Only schematics with an enabled extension; other manifest entries are left untouched. */
    private boolean isManaged(String key) {
        return key.startsWith(PREFIX) && SafePath.isSafe(key) && FileScanner.hasExtension(key, options.extensions());
    }

    private Path localPath(String key) {
        return SafePath.resolve(gameDir, key);
    }

    private record PendingConfig(String rel, ConfigMerger.Result merge) {}

    private final class Actions {
        final Manifest manifest;
        final String instanceId;
        final Instant now;
        final List<String> warnings;
        boolean manifestChanged;
        int uploaded, downloaded, deletedLocal, deletedRemote, hidden;
        final List<String> conflicts = new ArrayList<>();
        final List<String> newer = new ArrayList<>();

        Actions(Manifest manifest, String instanceId, Instant now, List<String> warnings) {
            this.manifest = manifest;
            this.instanceId = instanceId;
            this.now = now;
            this.warnings = warnings;
        }

        void upload(String key) throws IOException {
            byte[] data = Files.readAllBytes(localPath(key));
            String hash = uploadBlob(data); // hash the current content, the file may have changed since the scan
            Integer dataVersion = key.endsWith(".litematic") ? SchematicInspector.dataVersion(data) : null;
            manifest.files.put(key, Manifest.Entry.file(hash, data.length, now, dataVersion));
            manifestChanged = true;
            uploaded++;
        }

        private String uploadBlob(byte[] data) throws IOException {
            String hash = FileScanner.sha256(data);
            String blob = BLOBS + hash;
            if (remote.stat(blob) == null) remote.upload(blob, data, null);
            return hash;
        }

        /** Only schematics missing locally are hidden; existing copies are kept. */
        void pull(String key, Manifest.Entry entry, boolean missingLocally) throws IOException {
            Integer dv = entry.dataVersion();
            boolean isNewer = dv != null && options.localDataVersion() > 0 && dv > options.localDataVersion();
            if (isNewer && options.hideNewerSchematics() && missingLocally) {
                hidden++;
                return;
            }
            download(key, entry.sha256());
            if (isNewer) newer.add(key.substring(PREFIX.length()));
        }

        void download(String key, String hash) throws IOException {
            byte[] data = remote.download(BLOBS + hash);
            if (!FileScanner.sha256(data).equals(hash)) throw new IOException("Corrupt blob for " + key);
            Json.writeAtomic(localPath(key), data);
            downloaded++;
        }

        void trashLocal(String key) throws IOException {
            Path file = localPath(key);
            if (Files.exists(file)) {
                Path target = SafePath.resolve(trashDir.resolve(TRASH_STAMP.format(now)), key);
                Files.createDirectories(target.getParent());
                Files.move(file, target);
            }
            deletedLocal++;
        }

        void tombstone(String key) {
            manifest.files.put(key, Manifest.Entry.tombstone(now));
            manifestChanged = true;
            deletedRemote++;
        }

        /** Changed on both sides: the local version becomes a conflict copy, the remote one takes the original name. */
        void conflict(String key, String remoteHash) throws IOException {
            String copyKey = conflictName(key);
            Files.move(localPath(key), localPath(copyKey));
            upload(copyKey);
            download(key, remoteHash);
            conflicts.add(copyKey.substring(PREFIX.length()));
        }

        /** @return null if there is nothing to merge or the config is unreadable */
        PendingConfig mergeConfig(String rel, Map<String, JsonElement> base, boolean writeLocal) throws IOException {
            Path file = gameDir.resolve(rel);
            boolean exists = Files.isRegularFile(file);
            if (!exists && !writeLocal) return null;
            JsonObject local = null;
            Instant localTime = null;
            if (exists) {
                try {
                    localTime = Files.getLastModifiedTime(file).toInstant();
                    local = readConfig(file);
                } catch (IOException | RuntimeException e) {
                    warnings.add(rel + " could not be read");
                    return null;
                }
            }

            Manifest.Entry entry = manifest.files.get(rel);
            Map<String, JsonElement> remoteValues = Map.of();
            Map<String, KeyStamp> stamps = Map.of();
            if (entry != null && !entry.deleted() && hasValidHash(entry)) {
                byte[] data = remote.download(BLOBS + entry.sha256());
                try {
                    if (!FileScanner.sha256(data).equals(entry.sha256())) throw new IOException("Hash mismatch");
                    JsonObject remoteConfig = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
                    remoteValues = ConfigMerger.flatten(remoteConfig, key -> true);
                } catch (IOException | RuntimeException e) {
                    warnings.add("Remote copy of " + rel + " is corrupt, config not synced");
                    return null;
                }
                if (entry.keys() != null) stamps = entry.keys();
            }

            if (!exists) {
                // Litematica only writes its config later, so a new instance has none yet: create it from the shared state.
                Map<String, JsonElement> values = new TreeMap<>(remoteValues);
                values.keySet().removeIf(options.configFilter().negate());
                if (values.isEmpty()) return null;
                return new PendingConfig(rel, new ConfigMerger.Result(remoteValues, stamps, false,
                        ConfigMerger.nest(values), values, List.of()));
            }

            ConfigMerger.Result m = ConfigMerger.merge(local, localTime, base, remoteValues, stamps, instanceId,
                    options.configFilter(), writeLocal);
            for (String key : m.typeMismatches()) {
                warnings.add(rel + ": " + key + " has a different type remotely, skipped");
            }
            if (m.remoteChanged()) {
                byte[] data = Json.toBytes(ConfigMerger.nest(m.remote()));
                String hash = uploadBlob(data);
                manifest.files.put(rel, Manifest.Entry.config(hash, data.length, now, m.stamps()));
                manifestChanged = true;
            }
            return new PendingConfig(rel, m);
        }

        private String conflictName(String key) {
            int slash = key.lastIndexOf('/');
            int dot = key.lastIndexOf('.');
            String stem = dot > slash ? key.substring(0, dot) : key;
            String ext = dot > slash ? key.substring(dot) : "";
            return stem + " (conflict " + instanceId + " " + CONFLICT_STAMP.format(now) + ")" + ext;
        }
    }
}
