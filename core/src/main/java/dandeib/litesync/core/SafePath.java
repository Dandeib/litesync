package dandeib.litesync.core;

import java.nio.file.Path;

/** Validates paths from the manifest so nothing is written outside the allowed folders. */
public final class SafePath {

    private SafePath() {}

    public static boolean isSafe(String rel) {
        if (rel == null || rel.isEmpty() || rel.startsWith("/") || rel.contains("\\") || rel.contains(":")
                || rel.indexOf('\0') >= 0) {
            return false;
        }
        for (String part : rel.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }

    public static Path resolve(Path root, String rel) {
        if (!isSafe(rel)) throw new IllegalArgumentException("Unsafe path: " + rel);
        return root.resolve(rel);
    }
}
