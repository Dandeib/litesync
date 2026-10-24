package dandeib.litesync.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Stores files in a local folder. The revision is the SHA-256 of the content. */
public final class LocalFolderProvider implements StorageProvider {

    private final Path root;

    public LocalFolderProvider(Path root) {
        this.root = root;
    }

    @Override
    public RemoteFile stat(String path) throws IOException {
        Path file = SafePath.resolve(root, path);
        if (!Files.isRegularFile(file)) return null;
        return new RemoteFile(path, Files.size(file), FileScanner.sha256(file));
    }

    @Override
    public byte[] download(String path) throws IOException {
        return Files.readAllBytes(SafePath.resolve(root, path));
    }

    @Override
    public RemoteFile upload(String path, byte[] data, String expectedRevision) throws IOException {
        // FileLock is per process, so threads within one JVM lock on the class.
        synchronized (LocalFolderProvider.class) {
            return lockedUpload(path, data, expectedRevision);
        }
    }

    private RemoteFile lockedUpload(String path, byte[] data, String expectedRevision) throws IOException {
        Path file = SafePath.resolve(root, path);
        Files.createDirectories(root);
        // Several Minecraft instances may use the same folder at once.
        try (FileChannel channel = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            if (expectedRevision != null) {
                RemoteFile current = stat(path);
                String currentRevision = current == null ? MUST_NOT_EXIST : current.revision();
                if (!currentRevision.equals(expectedRevision)) throw new RevisionConflictException(path);
            }
            Json.writeAtomic(file, data);
        }
        return new RemoteFile(path, data.length, FileScanner.sha256(data));
    }

    @Override
    public void delete(String path) throws IOException {
        Files.deleteIfExists(SafePath.resolve(root, path));
    }
}
