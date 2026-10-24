package dandeib.litesync.core;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

/** Reads MinecraftDataVersion from a .litematic file (gzipped NBT) without Minecraft classes. */
public final class SchematicInspector {

    private static final String DATA_VERSION = "MinecraftDataVersion";

    private SchematicInspector() {}

    /** @return the data version, or null if the file is not a readable schematic */
    public static Integer dataVersion(byte[] gzippedNbt) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(gzippedNbt)))) {
            if (in.readByte() != 10) return null; // root must be a compound
            in.readUTF();
            for (byte type; (type = in.readByte()) != 0; ) {
                String name = in.readUTF();
                if (type == 3 && name.equals(DATA_VERSION)) return in.readInt();
                skip(in, type);
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void skip(DataInputStream in, byte type) throws IOException {
        switch (type) {
            case 1 -> in.skipNBytes(1);
            case 2 -> in.skipNBytes(2);
            case 3, 5 -> in.skipNBytes(4);
            case 4, 6 -> in.skipNBytes(8);
            case 7 -> in.skipNBytes(length(in));
            case 8 -> in.skipNBytes(in.readUnsignedShort());
            case 9 -> {
                byte elementType = in.readByte();
                int n = length(in);
                for (int i = 0; i < n; i++) skip(in, elementType);
            }
            case 10 -> {
                for (byte t; (t = in.readByte()) != 0; ) {
                    in.skipNBytes(in.readUnsignedShort());
                    skip(in, t);
                }
            }
            case 11 -> in.skipNBytes(4L * length(in));
            case 12 -> in.skipNBytes(8L * length(in));
            default -> throw new IOException("Unknown NBT type " + type);
        }
    }

    private static int length(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0) throw new IOException("Negative NBT length");
        return n;
    }
}
