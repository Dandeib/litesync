package dandeib.litesync.core;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/** The shared manifest.json: path → file state. */
public final class Manifest {

    public static final int FORMAT_VERSION = 1;

    public int formatVersion = FORMAT_VERSION;
    public String uuid;
    public String updatedAt;
    public String updatedBy;
    public Map<String, Entry> files = new TreeMap<>();

    /**
     * A tombstone (deleted = true) records a deletion so other instances don't upload the file again.
     *
     * @param dataVersion schematics only: MinecraftDataVersion from the NBT header
     * @param keys        configs only: last change per key
     */
    public record Entry(String sha256, long size, String modifiedAt, boolean deleted, Integer dataVersion,
                        Map<String, KeyStamp> keys) {
        public static Entry file(String sha256, long size, Instant at, Integer dataVersion) {
            return new Entry(sha256, size, at.toString(), false, dataVersion, null);
        }

        public static Entry config(String sha256, long size, Instant at, Map<String, KeyStamp> keys) {
            return new Entry(sha256, size, at.toString(), false, null, keys);
        }

        public static Entry tombstone(Instant at) {
            return new Entry(null, 0, at.toString(), true, null, null);
        }
    }

    public record KeyStamp(String modifiedAt, String by) {}

    public static final class NewerFormatException extends IOException {
        NewerFormatException(int format) {
            super("Manifest was written by a newer LiteSync version (format " + format + ")");
        }
    }

    public Manifest() {}

    public Manifest(String uuid) {
        this.uuid = uuid;
    }

    public static Manifest parse(byte[] data) throws IOException {
        Manifest m = Json.fromBytes(data, Manifest.class);
        if (m.formatVersion > FORMAT_VERSION) {
            throw new NewerFormatException(m.formatVersion);
        }
        if (m.files == null) m.files = new TreeMap<>();
        return m;
    }

    public byte[] toBytes() {
        return Json.toBytes(this);
    }

    /** @return true if anything was removed */
    public boolean pruneTombstones(Instant now, Duration maxAge) {
        Instant limit = now.minus(maxAge);
        return files.values().removeIf(e -> e.deleted() && parseOrEpoch(e.modifiedAt()).isBefore(limit));
    }

    static Instant parseOrEpoch(String s) {
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }
}
