package dandeib.litesync.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Two simulated instances A and B share one sync folder. */
class SyncEngineTest {

    @TempDir
    Path tmp;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC);
    private long mtime = 1_700_000_000_000L;

    private final class Instance {
        final Path gameDir;
        final SyncState state = new SyncState();
        final SyncEngine engine;

        Instance(String name, StorageProvider remote, Set<String> extensions) {
            this(name, remote, new SyncEngine.Options(extensions, List.of(), k -> true, 0, false, null));
        }

        Instance(String name, StorageProvider remote, SyncEngine.Options options) {
            gameDir = tmp.resolve(name);
            state.instanceId = name;
            engine = new SyncEngine(remote, gameDir, gameDir.resolve("config/litesync/trash"), "uuid", options, clock);
        }

        Path config() {
            return gameDir.resolve(SyncEngine.LITEMATICA_CONFIG);
        }

        void writeConfig(String json) throws IOException {
            Files.createDirectories(config().getParent());
            Files.writeString(config(), json);
            Files.setLastModifiedTime(config(), FileTime.fromMillis(mtime += 2000));
        }

        JsonObject readConfig() throws IOException {
            return JsonParser.parseString(Files.readString(config())).getAsJsonObject();
        }

        SyncEngine.Result sync() throws IOException {
            return engine.sync(state, true);
        }

        Path file(String rel) {
            return gameDir.resolve("schematics").resolve(rel);
        }

        void write(String rel, String content) throws IOException {
            Path f = file(rel);
            Files.createDirectories(f.getParent());
            Files.writeString(f, content);
            // Unique mtime so the hash cache reliably detects same-size changes.
            Files.setLastModifiedTime(f, FileTime.fromMillis(mtime += 2000));
        }

        String read(String rel) throws IOException {
            return Files.readString(file(rel));
        }

        List<String> files() throws IOException {
            Path dir = gameDir.resolve("schematics");
            if (!Files.exists(dir)) return List.of();
            try (Stream<Path> s = Files.walk(dir)) {
                return s.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString().replace('\\', '/')).sorted().toList();
            }
        }
    }

    private LocalFolderProvider remote() {
        return new LocalFolderProvider(tmp.resolve("remote"));
    }

    private Instance a() {
        return new Instance("A", remote(), Set.of(".litematic"));
    }

    private Instance b() {
        return new Instance("B", remote(), Set.of(".litematic"));
    }

    @Test
    void newFileReachesOtherInstance() throws IOException {
        Instance a = a(), b = b();
        a.write("base/farm.litematic", "v1");
        assertEquals(1, a.sync().uploaded());
        assertEquals(1, b.sync().downloaded());
        assertEquals("v1", b.read("base/farm.litematic"));
    }

    @Test
    void modificationReachesOtherInstance() throws IOException {
        Instance a = a(), b = b();
        a.write("farm.litematic", "v1");
        a.sync();
        b.sync();
        a.write("farm.litematic", "v2");
        a.sync();
        b.sync();
        assertEquals("v2", b.read("farm.litematic"));
    }

    @Test
    void deletedSchematicsFolderIsRedownloadedNotDeletedEverywhere() throws IOException {
        Instance a = a(), b = b();
        a.write("farm.litematic", "v1");
        a.sync();
        b.sync();
        try (Stream<Path> s = Files.walk(b.gameDir.resolve("schematics"))) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        assertTrue(b.engine.hasLocalChanges(b.state));
        SyncEngine.Result r = b.sync();
        assertEquals(0, r.deletedRemote());
        assertEquals("v1", b.read("farm.litematic"));
        assertEquals(0, a.sync().deletedLocal());
        assertEquals("v1", a.read("farm.litematic"));
    }

    @Test
    void deletionPropagatesAndLandsInTrash() throws IOException {
        Instance a = a(), b = b();
        a.write("farm.litematic", "v1");
        a.sync();
        b.sync();
        Files.delete(a.file("farm.litematic"));
        assertEquals(1, a.sync().deletedRemote());
        assertEquals(1, b.sync().deletedLocal());
        assertEquals(List.of(), b.files());
        try (Stream<Path> trash = Files.walk(b.gameDir.resolve("config/litesync/trash"))) {
            assertTrue(trash.anyMatch(p -> p.endsWith("farm.litematic")));
        }
        // Neither A nor B uploads the file again.
        assertFalse(a.sync().changedAnything());
        assertFalse(b.sync().changedAnything());
    }

    @Test
    void conflictKeepsBothVersions() throws IOException {
        Instance a = a(), b = b();
        a.write("farm.litematic", "v1");
        a.sync();
        b.sync();
        a.write("farm.litematic", "von A");
        b.write("farm.litematic", "von B");
        a.sync();
        SyncEngine.Result r = b.sync();

        assertEquals(1, r.conflicts().size());
        String copy = r.conflicts().get(0);
        assertTrue(copy.startsWith("farm (conflict B "), copy);
        assertTrue(copy.endsWith(").litematic"), copy);
        assertEquals("von A", b.read("farm.litematic"));
        assertEquals("von B", b.read(copy));

        a.sync();
        assertEquals("von B", a.read(copy));
        assertEquals(a.files(), b.files());
    }

    @Test
    void bothCreatedSameNameIsConflict() throws IOException {
        Instance a = a(), b = b();
        a.write("x.litematic", "A");
        b.write("x.litematic", "B");
        a.sync();
        assertEquals(1, b.sync().conflicts().size());
        a.sync();
        assertEquals(2, a.files().size());
        assertEquals(a.files(), b.files());
    }

    @Test
    void localDeleteLosesAgainstRemoteChange() throws IOException {
        Instance a = a(), b = b();
        a.write("farm.litematic", "v1");
        a.sync();
        b.sync();
        a.write("farm.litematic", "v2");
        a.sync();
        Files.delete(b.file("farm.litematic"));
        b.sync();
        assertEquals("v2", b.read("farm.litematic"));
    }

    @Test
    void remoteDeleteLosesAgainstLocalChange() throws IOException {
        Instance a = a(), b = b();
        a.write("farm.litematic", "v1");
        a.sync();
        b.sync();
        Files.delete(a.file("farm.litematic"));
        a.sync();
        b.write("farm.litematic", "v2");
        b.sync();
        assertEquals("v2", b.read("farm.litematic"));
        a.sync();
        assertEquals("v2", a.read("farm.litematic"));
    }

    @Test
    void concurrentManifestWriteIsRetried() throws IOException {
        Instance b = b();
        b.write("b.litematic", "B");
        // B syncs right before A writes the manifest.
        StorageProvider racing = new StorageProvider() {
            final LocalFolderProvider inner = remote();
            boolean raced;

            public RemoteFile stat(String path) throws IOException { return inner.stat(path); }
            public byte[] download(String path) throws IOException { return inner.download(path); }
            public void delete(String path) throws IOException { inner.delete(path); }
            public RemoteFile upload(String path, byte[] data, String rev) throws IOException {
                if (path.equals(SyncEngine.MANIFEST) && !raced) {
                    raced = true;
                    b.sync();
                }
                return inner.upload(path, data, rev);
            }
        };
        Instance a = new Instance("A", racing, Set.of(".litematic"));
        a.write("a.litematic", "A");

        a.sync();
        b.sync();
        assertEquals(List.of("a.litematic", "b.litematic"), a.files());
        assertEquals(List.of("a.litematic", "b.litematic"), b.files());
    }

    @Test
    void unchangedRunChangesNothing() throws IOException {
        Instance a = a();
        a.write("farm.litematic", "v1");
        a.sync();
        assertFalse(a.engine.hasLocalChanges(a.state));
        assertFalse(a.sync().changedAnything());
        a.write("farm.litematic", "v2");
        assertTrue(a.engine.hasLocalChanges(a.state));
    }

    @Test
    void disabledExtensionsAreLeftAlone() throws IOException {
        Instance a = new Instance("A", remote(), Set.of(".litematic", ".schem"));
        Instance b = b();
        a.write("old.schem", "s");
        a.sync();
        b.sync();
        assertEquals(List.of(), b.files());
        a.sync();
        assertTrue(Files.exists(a.file("old.schem")));
        Manifest m = Manifest.parse(remote().download(SyncEngine.MANIFEST));
        assertFalse(m.files.get("schematics/old.schem").deleted());
    }

    @Test
    void unsafeManifestPathsAreIgnored() throws IOException {
        Manifest m = new Manifest("uuid");
        String hash = FileScanner.sha256("evil".getBytes(StandardCharsets.UTF_8));
        m.files.put("schematics/../evil.litematic", Manifest.Entry.file(hash, 4, clock.instant(), null));
        LocalFolderProvider r = remote();
        r.upload("blobs/" + hash, "evil".getBytes(StandardCharsets.UTF_8), null);
        r.upload(SyncEngine.MANIFEST, m.toBytes(), null);

        Instance a = a();
        a.sync();
        assertFalse(Files.exists(a.gameDir.resolve("evil.litematic")));
        assertTrue(Manifest.parse(r.download(SyncEngine.MANIFEST)).files.containsKey("schematics/../evil.litematic"));
    }

    @Test
    void corruptBlobIsNotWritten() throws IOException {
        Instance a = a(), b = b();
        a.write("farm.litematic", "v1");
        a.sync();
        String hash = FileScanner.sha256("v1".getBytes(StandardCharsets.UTF_8));
        remote().upload("blobs/" + hash, "broken".getBytes(StandardCharsets.UTF_8), null);
        assertThrows(IOException.class, b::sync);
        assertFalse(Files.exists(b.file("farm.litematic")));
    }

    private SyncEngine.Options configOptions(int dataVersion, boolean hideNewer, Path backup) {
        Settings s = new Settings();
        s.hideNewerSchematics = hideNewer;
        return SyncEngine.Options.of(s, dataVersion, backup);
    }

    @Test
    void configChangeReachesOtherInstanceWithoutUnknownKeys() throws IOException {
        Instance a = new Instance("A", remote(), configOptions(0, false, null));
        Instance b = new Instance("B", remote(), configOptions(0, false, null));
        a.writeConfig("{\"Generic\":{\"x\":1,\"newKey\":true,\"customSchematicBaseDirectory\":\"C:/A\"},\"config_version\":2}");
        b.writeConfig("{\"Generic\":{\"x\":0,\"customSchematicBaseDirectory\":\"D:/B\"},\"config_version\":1}");
        a.sync();

        SyncEngine.Result r = b.sync();
        assertEquals(List.of(SyncEngine.LITEMATICA_CONFIG), r.configsWritten());
        // New value applied; unknown and blacklisted keys are not; config_version untouched.
        assertEquals(JsonParser.parseString("{\"Generic\":{\"x\":1,\"customSchematicBaseDirectory\":\"D:/B\"},\"config_version\":1}"),
                b.readConfig());
        assertFalse(b.engine.hasLocalChanges(b.state));

        b.writeConfig("{\"Generic\":{\"x\":5,\"customSchematicBaseDirectory\":\"D:/B\"},\"config_version\":1}");
        assertTrue(b.engine.hasLocalChanges(b.state));
        b.sync();
        assertEquals(List.of(SyncEngine.LITEMATICA_CONFIG), a.sync().configsWritten());
        JsonObject generic = a.readConfig().getAsJsonObject("Generic");
        assertEquals(5, generic.get("x").getAsInt());
        assertTrue(generic.get("newKey").getAsBoolean());
        assertEquals("C:/A", generic.get("customSchematicBaseDirectory").getAsString());
    }

    @Test
    void newInstanceWithoutConfigFileGetsSharedConfig() throws IOException {
        Instance a = new Instance("A", remote(), configOptions(0, false, null));
        Instance b = new Instance("B", remote(), configOptions(0, false, null));
        a.writeConfig("{\"Generic\":{\"x\":1,\"customSchematicBaseDirectory\":\"C:/A\"},\"config_version\":1}");
        a.sync();

        assertEquals(List.of(), b.engine.sync(b.state, false).configsWritten());
        assertFalse(Files.exists(b.config()));
        assertEquals(List.of(SyncEngine.LITEMATICA_CONFIG), b.sync().configsWritten());
        assertEquals(JsonParser.parseString("{\"Generic\":{\"x\":1}}"), b.readConfig());
        assertFalse(b.engine.hasLocalChanges(b.state));

        // Litematica later saves its full config; that must not undo the shared value.
        b.writeConfig("{\"Generic\":{\"x\":1,\"y\":7,\"customSchematicBaseDirectory\":\"D:/B\"},\"config_version\":1}");
        b.sync();
        a.sync();
        assertEquals(1, a.readConfig().getAsJsonObject("Generic").get("x").getAsInt());
        assertEquals("C:/A", a.readConfig().getAsJsonObject("Generic").get("customSchematicBaseDirectory").getAsString());
    }

    @Test
    void configIsOnlyPushedWhenWritingIsDisabled() throws IOException {
        Instance a = new Instance("A", remote(), configOptions(0, false, null));
        Instance b = new Instance("B", remote(), configOptions(0, false, null));
        a.writeConfig("{\"Generic\":{\"x\":1}}");
        b.writeConfig("{\"Generic\":{\"x\":0}}");
        a.sync();
        assertEquals(List.of(), b.engine.sync(b.state, false).configsWritten());
        assertEquals(0, b.readConfig().getAsJsonObject("Generic").get("x").getAsInt());
        // The next normal sync applies the remote value, not the old local one.
        b.sync();
        assertEquals(1, b.readConfig().getAsJsonObject("Generic").get("x").getAsInt());
    }

    @Test
    void newerSchematicsAreFlaggedOrHidden() throws IOException {
        Instance a = new Instance("A", remote(), configOptions(5000, false, null));
        Instance withHint = new Instance("B", remote(), configOptions(4000, false, null));
        Instance hiding = new Instance("C", remote(), configOptions(4000, true, null));
        Files.createDirectories(a.file("x").getParent());
        Files.write(a.file("new.litematic"), SchematicInspectorTest.litematic(5000));
        a.sync();
        assertEquals(5000, Manifest.parse(remote().download(SyncEngine.MANIFEST)).files.get("schematics/new.litematic").dataVersion());

        assertEquals(List.of("new.litematic"), withHint.sync().newer());
        assertEquals(List.of("new.litematic"), withHint.files());
        SyncEngine.Result r = hiding.sync();
        assertEquals(1, r.hidden());
        assertEquals(List.of(), hiding.files());
        // Hiding is not deleting.
        assertEquals(1, hiding.sync().hidden());
        assertFalse(Manifest.parse(remote().download(SyncEngine.MANIFEST)).files.get("schematics/new.litematic").deleted());
    }

    @Test
    void corruptManifestIsRestoredFromBackup() throws IOException {
        Path backup = tmp.resolve("backup.json");
        Instance a = new Instance("A", remote(), configOptions(0, false, backup));
        a.write("farm.litematic", "v1");
        a.sync();
        remote().upload(SyncEngine.MANIFEST, "{broken".getBytes(StandardCharsets.UTF_8), null);

        SyncEngine.Result r = a.sync();
        assertEquals(1, r.warnings().size());
        assertTrue(Manifest.parse(remote().download(SyncEngine.MANIFEST)).files.containsKey("schematics/farm.litematic"));
        assertEquals(List.of("farm.litematic"), a.files());
    }

    @Test
    void emptyRemoteNeverTrashesLocalFiles() throws IOException {
        Instance a = a();
        a.write("farm.litematic", "v1");
        a.sync();
        remote().delete(SyncEngine.MANIFEST);
        assertEquals(1, a.sync().uploaded());
        assertEquals(List.of("farm.litematic"), a.files());
    }

    @Test
    void corruptManifestWithoutBackupFailsInsteadOfDeleting() throws IOException {
        Instance a = a();
        a.write("farm.litematic", "v1");
        a.sync();
        remote().upload(SyncEngine.MANIFEST, "{broken".getBytes(StandardCharsets.UTF_8), null);
        assertThrows(IOException.class, a::sync);
        assertEquals(List.of("farm.litematic"), a.files());
    }
}
