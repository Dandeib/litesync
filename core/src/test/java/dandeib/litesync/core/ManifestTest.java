package dandeib.litesync.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManifestTest {

    @TempDir
    Path dir;

    @Test
    void roundTrip() throws IOException {
        Instant now = Instant.parse("2026-10-24T10:00:00Z");
        Manifest m = new Manifest("uuid-1");
        m.updatedBy = "inst-a";
        m.files.put("schematics/farm.litematic", Manifest.Entry.file("ab", 12, now, null));
        m.files.put("schematics/old.litematic", Manifest.Entry.tombstone(now));

        Manifest back = Manifest.parse(m.toBytes());

        assertEquals("uuid-1", back.uuid);
        assertEquals("inst-a", back.updatedBy);
        assertEquals(m.files, back.files);
        assertTrue(back.files.get("schematics/old.litematic").deleted());
    }

    @Test
    void newerFormatIsRejected() {
        byte[] json = "{\"formatVersion\": 99, \"files\": {}}".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> Manifest.parse(json));
    }

    @Test
    void brokenJsonIsRejected() {
        assertThrows(IOException.class, () -> Manifest.parse("{broken".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void oldTombstonesArePruned() {
        Instant now = Instant.parse("2026-10-24T10:00:00Z");
        Manifest m = new Manifest("u");
        m.files.put("schematics/old.litematic", Manifest.Entry.tombstone(now.minus(Duration.ofDays(91))));
        m.files.put("schematics/new.litematic", Manifest.Entry.tombstone(now.minus(Duration.ofDays(5))));
        m.files.put("schematics/file.litematic", Manifest.Entry.file("ab", 1, now.minus(Duration.ofDays(400)), null));

        assertTrue(m.pruneTombstones(now, Duration.ofDays(90)));
        assertEquals(List.of("schematics/file.litematic", "schematics/new.litematic"), List.copyOf(m.files.keySet()));
        assertFalse(m.pruneTombstones(now, Duration.ofDays(90)));
    }

    @Test
    void stateRoundTripKeepsInstanceId() throws IOException {
        Path file = dir.resolve("state.json");
        SyncState s = SyncState.load(file);
        assertTrue(s.instanceId.startsWith("inst-"));
        s.base.put("schematics/a.litematic", "ab");
        s.scanCache.put("a.litematic", new FileScanner.ScannedFile("a.litematic", 1, 2, "ab"));
        s.save(file);

        SyncState back = SyncState.load(file);
        assertEquals(s.instanceId, back.instanceId);
        assertEquals(s.base, back.base);
        assertEquals(s.scanCache, back.scanCache);
    }

    @Test
    void settingsAreCreatedWithDefaults() throws IOException {
        Path file = dir.resolve("settings.json");
        Settings s = Settings.load(file);
        assertEquals("", s.localSyncFolder);
        assertEquals(List.of(".litematic"), s.extensions);
        assertTrue(file.toFile().exists());
    }

    @Test
    void unsafePathsAreRejected() {
        for (String bad : List.of("", "/abs", "a/../b", "..", "a//b", "C:/x", "a\\b", "./a")) {
            assertFalse(SafePath.isSafe(bad), bad);
        }
        assertTrue(SafePath.isSafe("schematics/base/farm (1).litematic"));
    }
}
