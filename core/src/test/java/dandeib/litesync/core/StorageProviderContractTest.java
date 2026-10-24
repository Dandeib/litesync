package dandeib.litesync.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Behaviour every StorageProvider must have. */
abstract class StorageProviderContractTest {

    abstract StorageProvider provider() throws IOException;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void missingFileHasNoStat() throws IOException {
        assertNull(provider().stat("nope/file.bin"));
    }

    @Test
    void uploadThenDownload() throws IOException {
        StorageProvider p = provider();
        RemoteFile f = p.upload("dir/sub/file.bin", bytes("hallo"), null);
        assertEquals(5L, f.size());
        assertArrayEquals(bytes("hallo"), p.download("dir/sub/file.bin"));
        assertEquals(f.revision(), p.stat("dir/sub/file.bin").revision());
    }

    @Test
    void downloadOfMissingFileThrows() {
        assertThrows(IOException.class, () -> provider().download("nope.bin"));
    }

    @Test
    void mustNotExistFailsWhenFileExists() throws IOException {
        StorageProvider p = provider();
        p.upload("m.json", bytes("1"), StorageProvider.MUST_NOT_EXIST);
        assertThrows(RevisionConflictException.class, () -> p.upload("m.json", bytes("2"), StorageProvider.MUST_NOT_EXIST));
        assertArrayEquals(bytes("1"), p.download("m.json"));
    }

    @Test
    void staleRevisionFails() throws IOException {
        StorageProvider p = provider();
        String rev1 = p.upload("m.json", bytes("1"), null).revision();
        String rev2 = p.upload("m.json", bytes("2"), rev1).revision();
        assertThrows(RevisionConflictException.class, () -> p.upload("m.json", bytes("3"), rev1));
        p.upload("m.json", bytes("3"), rev2);
        assertArrayEquals(bytes("3"), p.download("m.json"));
    }

    @Test
    void deleteRemovesFile() throws IOException {
        StorageProvider p = provider();
        p.upload("x.bin", bytes("x"), null);
        p.delete("x.bin");
        assertNull(p.stat("x.bin"));
        p.delete("x.bin"); // deleting twice is not an error
    }

    @Test
    void unsafePathsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> provider().upload("../evil.bin", bytes("x"), null));
    }
}
