package dandeib.litesync.core;

import java.io.IOException;

/** Shared storage for one player. Paths use "/". Calls block; sync runs on its own thread. */
// shortcut: files are held fully in memory, fine for schematics up to a few 10 MB; switch to streams if needed.
public interface StorageProvider {

    /** Expected revision meaning "the file must not exist yet". */
    String MUST_NOT_EXIST = "";

    /** @return null if the file does not exist */
    RemoteFile stat(String path) throws IOException;

    /** @throws java.nio.file.NoSuchFileException if the file does not exist */
    byte[] download(String path) throws IOException;

    /**
     * @param expectedRevision null = write unconditionally, {@link #MUST_NOT_EXIST}, or the last seen revision
     * @throws RevisionConflictException if the file has a different revision by now
     */
    RemoteFile upload(String path, byte[] data, String expectedRevision) throws IOException;

    void delete(String path) throws IOException;
}
