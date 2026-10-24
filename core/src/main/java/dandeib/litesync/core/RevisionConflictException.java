package dandeib.litesync.core;

import java.io.IOException;

/** Another instance changed the file since it was last read. */
public class RevisionConflictException extends IOException {
    public RevisionConflictException(String path) {
        super("Revision conflict: " + path);
    }
}
