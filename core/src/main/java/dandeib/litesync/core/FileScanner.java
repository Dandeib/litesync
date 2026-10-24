package dandeib.litesync.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Recursively scans a folder and hashes each file; unchanged files are taken from the cache. */
public final class FileScanner {

    /** path is relative to the scanned folder and always uses "/". */
    public record ScannedFile(String path, long size, long modifiedMillis, String sha256) {}

    private FileScanner() {}

    /**
     * @param extensions allowed extensions including the dot, case-insensitive
     * @param previous   last scan result; files with the same path, size and mtime are not rehashed
     */
    public static Map<String, ScannedFile> scan(Path root, Set<String> extensions, Map<String, ScannedFile> previous)
            throws IOException {
        Map<String, ScannedFile> result = new HashMap<>();
        if (!Files.isDirectory(root)) return result;

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                if (!hasExtension(file.getFileName().toString(), extensions)) continue;
                String rel = toRelative(root, file);
                try {
                    long size = Files.size(file);
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    ScannedFile old = previous.get(rel);
                    String hash = old != null && old.size() == size && old.modifiedMillis() == mtime
                            ? old.sha256()
                            : sha256(file);
                    result.put(rel, new ScannedFile(rel, size, mtime, hash));
                } catch (IOException e) {
                    // Locked or just deleted: skip it this round.
                }
            }
        }
        return result;
    }

    static String toRelative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    public static boolean hasExtension(String name, Set<String> extensions) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (lower.endsWith(ext.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) digest.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(byte[] data) {
        return HexFormat.of().formatHex(newDigest().digest(data));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JVM must provide SHA-256
        }
    }
}
