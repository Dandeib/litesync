package dandeib.litesync.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class Json {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Json() {}

    public static <T> T fromBytes(byte[] data, Class<T> type) throws IOException {
        try {
            T value = GSON.fromJson(new String(data, StandardCharsets.UTF_8), type);
            if (value == null) throw new IOException("Empty JSON file");
            return value;
        } catch (JsonParseException e) {
            throw new IOException("Corrupt JSON file", e);
        }
    }

    public static byte[] toBytes(Object value) {
        return GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    /** @return null if the file does not exist */
    public static <T> T read(Path file, Class<T> type) throws IOException {
        return Files.exists(file) ? fromBytes(Files.readAllBytes(file), type) : null;
    }

    public static void write(Path file, Object value) throws IOException {
        writeAtomic(file, toBytes(value));
    }

    /** Writes to a temp file next to the target and renames it, so a half-written file is never left behind. */
    public static void writeAtomic(Path file, byte[] data) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
