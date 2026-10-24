package dandeib.litesync.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dandeib.litesync.core.Manifest.KeyStamp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigMergerTest {

    private static final Instant T1 = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-22T10:00:00Z");

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static ConfigMerger.Result merge(JsonObject local, Instant t, Map<String, JsonElement> base,
                                             Map<String, JsonElement> remote, Map<String, KeyStamp> stamps) {
        return ConfigMerger.merge(local, t, base, remote, stamps, "inst-a", ConfigMerger.filter(List.of(), true), true);
    }

    @Test
    void firstInstanceUploadsAllKeys() {
        ConfigMerger.Result r = merge(json("{\"Generic\":{\"a\":true,\"b\":1},\"config_version\":3}"), T1, null, Map.of(), Map.of());
        assertTrue(r.remoteChanged());
        assertEquals(Map.of("Generic.a", new JsonPrimitive(true), "Generic.b", new JsonPrimitive(1)), r.remote());
        assertEquals(T1.toString(), r.stamps().get("Generic.a").modifiedAt());
        assertNull(r.newLocal());
    }

    @Test
    void newInstanceAdoptsRemoteInsteadOfOverwritingWithDefaults() {
        ConfigMerger.Result r = merge(json("{\"Generic\":{\"a\":false}}"), T2, null,
                Map.of("Generic.a", new JsonPrimitive(true)), Map.of("Generic.a", new KeyStamp(T1.toString(), "x")));
        assertFalse(r.remoteChanged());
        assertEquals(json("{\"Generic\":{\"a\":true}}"), r.newLocal());
        assertEquals(Map.of("Generic.a", new JsonPrimitive(true)), r.newBase());
    }

    @Test
    void newestChangeWins() {
        Map<String, JsonElement> base = Map.of("Generic.a", new JsonPrimitive(1));
        // Changed locally at T2, remotely at T1: local wins.
        ConfigMerger.Result r = merge(json("{\"Generic\":{\"a\":2}}"), T2, base,
                Map.of("Generic.a", new JsonPrimitive(3)), Map.of("Generic.a", new KeyStamp(T1.toString(), "x")));
        assertTrue(r.remoteChanged());
        assertEquals(new JsonPrimitive(2), r.remote().get("Generic.a"));
        assertNull(r.newLocal());

        // Changed locally at T1, remotely at T2: remote wins and is written locally.
        r = merge(json("{\"Generic\":{\"a\":2}}"), T1, base,
                Map.of("Generic.a", new JsonPrimitive(3)), Map.of("Generic.a", new KeyStamp(T2.toString(), "x")));
        assertFalse(r.remoteChanged());
        assertEquals(json("{\"Generic\":{\"a\":3}}"), r.newLocal());
    }

    @Test
    void unknownKeysStayRemoteButNeverReachLocalFile() {
        ConfigMerger.Result r = merge(json("{\"Generic\":{\"a\":1}}"), T1, Map.of("Generic.a", new JsonPrimitive(1)),
                Map.of("Generic.a", new JsonPrimitive(1), "Generic.onlyNewVersion", new JsonPrimitive(true)), Map.of());
        assertTrue(r.remote().containsKey("Generic.onlyNewVersion"));
        assertNull(r.newLocal());
    }

    @Test
    void differentTypeIsNotApplied() {
        ConfigMerger.Result r = merge(json("{\"Generic\":{\"a\":true}}"), T1, Map.of("Generic.a", new JsonPrimitive(true)),
                Map.of("Generic.a", new JsonPrimitive("text")), Map.of("Generic.a", new KeyStamp(T2.toString(), "x")));
        assertNull(r.newLocal());
        assertEquals(List.of("Generic.a"), r.typeMismatches());
    }

    @Test
    void blacklistAndHotkeysAreIgnoredInBothDirections() {
        var filter = ConfigMerger.filter(List.of("Generic.path", "Colors"), false);
        ConfigMerger.Result r = ConfigMerger.merge(
                json("{\"Generic\":{\"path\":\"C:/a\",\"x\":1},\"Colors\":{\"c\":\"#fff\"},\"Hotkeys\":{\"h\":\"K\"}}"), T2, null,
                Map.of("Generic.path", new JsonPrimitive("D:/b"), "Hotkeys.h", new JsonPrimitive("L")), Map.of(),
                "inst-a", filter, true);
        assertEquals(Map.of("Generic.path", new JsonPrimitive("D:/b"), "Hotkeys.h", new JsonPrimitive("L"),
                "Generic.x", new JsonPrimitive(1)), r.remote());
        assertNull(r.newLocal());
    }

    @Test
    void writeLocalFalseOnlyPushes() {
        ConfigMerger.Result r = ConfigMerger.merge(json("{\"Generic\":{\"a\":1}}"), T1, Map.of("Generic.a", new JsonPrimitive(1)),
                Map.of("Generic.a", new JsonPrimitive(5)), Map.of(), "inst-a", k -> true, false);
        assertNull(r.newLocal());
        assertEquals(Map.of("Generic.a", new JsonPrimitive(1)), r.newBase());
    }

    @Test
    void nestIsInverseOfFlatten() {
        JsonObject o = json("{\"Generic\":{\"a\":1,\"b\":{\"keys\":\"K\"}},\"Visuals\":{\"c\":[1,2]}}");
        assertEquals(o, ConfigMerger.nest(ConfigMerger.flatten(o, k -> true)));
    }
}
