package dandeib.litesync.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SchematicInspectorTest {

    /** Root compound with nested tags before MinecraftDataVersion, as in real files. */
    static byte[] litematic(int dataVersion) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(10);
            out.writeUTF("");
            out.writeByte(10);
            out.writeUTF("Metadata");
            out.writeByte(8);
            out.writeUTF("Name");
            out.writeUTF("farm");
            out.writeByte(9);
            out.writeUTF("List");
            out.writeByte(3);
            out.writeInt(2);
            out.writeInt(1);
            out.writeInt(2);
            out.writeByte(12);
            out.writeUTF("Longs");
            out.writeInt(1);
            out.writeLong(7);
            out.writeByte(0);
            out.writeByte(3);
            out.writeUTF("Version");
            out.writeInt(7);
            out.writeByte(3);
            out.writeUTF("MinecraftDataVersion");
            out.writeInt(dataVersion);
            out.writeByte(0);
        }
        return bytes.toByteArray();
    }

    @Test
    void readsDataVersionBehindNestedTags() throws IOException {
        assertEquals(4325, SchematicInspector.dataVersion(litematic(4325)));
    }

    @Test
    void garbageGivesNull() {
        assertNull(SchematicInspector.dataVersion(new byte[] {1, 2, 3}));
    }
}
