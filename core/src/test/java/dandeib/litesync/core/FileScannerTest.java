package dandeib.litesync.core;

import dandeib.litesync.core.FileScanner.ScannedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileScannerTest {

    private static final Set<String> LITEMATIC = Set.of(".litematic");

    @TempDir
    Path root;

    @Test
    void findsNestedFilesWithForwardSlashesAndFiltersExtensions() throws IOException {
        write("farm.litematic", "a");
        write("base/sub/Tower.LITEMATIC", "b");
        write("notes.txt", "c");
        write("old.schem", "d");

        Map<String, ScannedFile> result = FileScanner.scan(root, LITEMATIC, Map.of());

        assertEquals(Set.of("farm.litematic", "base/sub/Tower.LITEMATIC"), result.keySet());
        assertEquals(1L, result.get("farm.litematic").size());
        assertEquals("ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb",
                result.get("farm.litematic").sha256());
    }

    @Test
    void extraExtensionsCanBeEnabled() throws IOException {
        write("old.schem", "d");
        assertTrue(FileScanner.scan(root, Set.of(".litematic", ".schem"), Map.of()).containsKey("old.schem"));
    }

    @Test
    void unchangedFilesComeFromCache() throws IOException {
        write("farm.litematic", "a");
        Map<String, ScannedFile> first = FileScanner.scan(root, LITEMATIC, Map.of());

        // A wrong hash in the cache proves that files with the same size and mtime are not rehashed.
        ScannedFile f = first.get("farm.litematic");
        Map<String, ScannedFile> cache = Map.of(f.path(), new ScannedFile(f.path(), f.size(), f.modifiedMillis(), "cached"));

        assertEquals("cached", FileScanner.scan(root, LITEMATIC, cache).get("farm.litematic").sha256());
    }

    @Test
    void changedFilesAreRehashed() throws IOException {
        Path file = write("farm.litematic", "a");
        Map<String, ScannedFile> first = FileScanner.scan(root, LITEMATIC, Map.of());

        Files.writeString(file, "bb");
        Files.setLastModifiedTime(file, FileTime.fromMillis(first.get("farm.litematic").modifiedMillis() + 2000));
        ScannedFile second = FileScanner.scan(root, LITEMATIC, first).get("farm.litematic");

        assertNotEquals(first.get("farm.litematic").sha256(), second.sha256());
        assertEquals(2L, second.size());
    }

    @Test
    void missingRootGivesEmptyResult() throws IOException {
        assertTrue(FileScanner.scan(root.resolve("gibtsnicht"), LITEMATIC, Map.of()).isEmpty());
    }

    private Path write(String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, content);
    }
}
