package dandeib.litesync.core;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class LocalFolderProviderTest extends StorageProviderContractTest {

    @TempDir
    Path root;

    @Override
    StorageProvider provider() {
        return new LocalFolderProvider(root);
    }
}
