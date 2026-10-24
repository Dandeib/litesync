package dandeib.litesync.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dandeib.litesync.core.Manifest.KeyStamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Merges a malilib-style config (category → key → value) per key. Keys are named "Category.key";
 * top-level values such as config_version are never synced.
 */
public final class ConfigMerger {

    /**
     * @param remote         new remote state: union of all known keys
     * @param newLocal       new content of the local file, or null if it stays unchanged
     * @param newBase        local values after the sync, used to detect the next local change
     * @param typeMismatches keys skipped because the remote value has a different type
     */
    public record Result(Map<String, JsonElement> remote, Map<String, KeyStamp> stamps, boolean remoteChanged,
                         JsonObject newLocal, Map<String, JsonElement> newBase, List<String> typeMismatches) {}

    private ConfigMerger() {}

    /**
     * @param localTime  mtime of the local file, used as the change time of locally changed keys
     * @param base       local values after the last sync, or null on this instance's first sync
     * @param synced     false for blacklisted keys
     * @param writeLocal false = only upload, never touch the local file (e.g. on shutdown)
     */
    public static Result merge(JsonObject local, Instant localTime, Map<String, JsonElement> base,
                               Map<String, JsonElement> remote, Map<String, KeyStamp> stamps,
                               String instanceId, Predicate<String> synced, boolean writeLocal) {
        Map<String, JsonElement> l = flatten(local, synced);
        Map<String, JsonElement> r = new TreeMap<>(remote);
        Map<String, KeyStamp> s = new TreeMap<>(stamps);
        boolean remoteChanged = false;

        for (Map.Entry<String, JsonElement> e : l.entrySet()) {
            String key = e.getKey();
            JsonElement value = e.getValue();
            // On the first sync, local defaults must not overwrite the shared state.
            boolean changed = base != null ? !value.equals(base.get(key)) : !r.containsKey(key);
            if (!changed || value.equals(r.get(key))) continue;
            KeyStamp stamp = s.get(key);
            if (stamp == null || !r.containsKey(key) || localTime.isAfter(Manifest.parseOrEpoch(stamp.modifiedAt()))) {
                r.put(key, value.deepCopy());
                s.put(key, new KeyStamp(localTime.toString(), instanceId));
                remoteChanged = true;
            }
        }

        JsonObject newLocal = null;
        List<String> mismatches = new ArrayList<>();
        if (writeLocal) {
            JsonObject copy = local.deepCopy();
            for (Map.Entry<String, JsonElement> e : l.entrySet()) {
                JsonElement rv = r.get(e.getKey());
                if (rv == null || rv.equals(e.getValue())) continue;
                if (!sameType(rv, e.getValue())) {
                    mismatches.add(e.getKey());
                    continue;
                }
                int dot = e.getKey().indexOf('.');
                copy.getAsJsonObject(e.getKey().substring(0, dot)).add(e.getKey().substring(dot + 1), rv.deepCopy());
                newLocal = copy;
            }
        }
        Map<String, JsonElement> newBase = newLocal == null ? l : flatten(newLocal, synced);
        return new Result(r, s, remoteChanged, newLocal, newBase, mismatches);
    }

    /** Category objects → "Category.key", keeping only keys accepted by synced. */
    public static Map<String, JsonElement> flatten(JsonObject config, Predicate<String> synced) {
        Map<String, JsonElement> result = new TreeMap<>();
        for (Map.Entry<String, JsonElement> category : config.entrySet()) {
            if (!category.getValue().isJsonObject()) continue;
            for (Map.Entry<String, JsonElement> e : category.getValue().getAsJsonObject().entrySet()) {
                String key = category.getKey() + "." + e.getKey();
                if (synced.test(key)) result.put(key, e.getValue());
            }
        }
        return result;
    }

    /** Inverse of {@link #flatten}. */
    public static JsonObject nest(Map<String, JsonElement> flat) {
        JsonObject result = new JsonObject();
        flat.forEach((key, value) -> {
            int dot = key.indexOf('.');
            if (dot <= 0) return;
            String category = key.substring(0, dot);
            if (!result.has(category)) result.add(category, new JsonObject());
            result.getAsJsonObject(category).add(key.substring(dot + 1), value);
        });
        return result;
    }

    /** A blacklist entry "Category.key" matches that key, "Category" the whole category. */
    public static Predicate<String> filter(List<String> blacklist, boolean syncHotkeys) {
        return key -> {
            if (!syncHotkeys && key.startsWith("Hotkeys.")) return false;
            for (String entry : blacklist) {
                if (key.equals(entry) || key.startsWith(entry + ".")) return false;
            }
            return true;
        };
    }

    static boolean sameType(JsonElement a, JsonElement b) {
        if (a.isJsonPrimitive() && b.isJsonPrimitive()) {
            JsonPrimitive pa = a.getAsJsonPrimitive(), pb = b.getAsJsonPrimitive();
            return pa.isBoolean() == pb.isBoolean() && pa.isNumber() == pb.isNumber() && pa.isString() == pb.isString();
        }
        return a.isJsonObject() == b.isJsonObject() && a.isJsonArray() == b.isJsonArray() && a.isJsonNull() == b.isJsonNull();
    }
}
