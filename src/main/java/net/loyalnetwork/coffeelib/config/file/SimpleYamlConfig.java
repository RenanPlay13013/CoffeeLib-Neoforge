package net.loyalnetwork.coffeelib.config.file;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class SimpleYamlConfig {

    private static final ThreadLocal<Yaml> YAML = ThreadLocal.withInitial(
            () -> new Yaml(new SafeConstructor(new LoaderOptions()))
    );

    private volatile Map<String, Object> data;

    private SimpleYamlConfig() {
        this.data = Collections.emptyMap();
    }

    public static SimpleYamlConfig load(Path file) {
        SimpleYamlConfig cfg = new SimpleYamlConfig();
        cfg.reload(file);
        return cfg;
    }

    /** Empty config, used as a fallback when the original file could not be read. */
    public static SimpleYamlConfig empty() {
        return new SimpleYamlConfig();
    }

    /**
     * Reloads the content from disk.
     * <p>In case of {@link ConfigParseException}, the previous state of {@code data}
     * is preserved — the assignment only occurs after parsing succeeds — so
     * callers that catch the exception from {@code reload} will still have
     * the old values available.</p>
     */
    public void reload(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = YAML.get().load(reader);
            this.data = toMap(loaded);
        } catch (NoSuchFileException e) {
            this.data = Collections.emptyMap();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read config file: " + file, e);
        } catch (RuntimeException e) {
            // YAMLException and the like from SnakeYAML — does not touch this.data.
            throw new ConfigParseException("Malformed YAML in config file: " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object loaded) {
        if (loaded instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    public boolean contains(String path) {
        Map<String, Object> snapshot = data;
        String[] parts = splitPath(path);
        Object current = snapshot;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return false;
            }
            current = map.get(part);
        }
        return true;
    }

    public Object get(String path) {
        return resolve(data, path);
    }

    public Object get(String path, Object def) {
        Map<String, Object> snapshot = data;
        String[] parts = splitPath(path);
        Object current = snapshot;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return def;
            }
            current = map.get(part);
        }
        return current;
    }

    public synchronized void set(String path, Object value) {
        String[] parts = splitPath(path);
        Map<String, Object> newRoot = deepCopy(data);
        Map<String, Object> current = newRoot;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            Map<String, Object> nextMap;
            if (next instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) map;
                nextMap = casted;
            } else {
                nextMap = new LinkedHashMap<>();
                current.put(parts[i], nextMap);
            }
            current = nextMap;
        }
        current.put(parts[parts.length - 1], value);
        this.data = newRoot;
    }

    public Map<String, Object> getSection(String path) {
        Object v = resolve(data, path);
        if (v instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) map;
            return Collections.unmodifiableMap(new LinkedHashMap<>(casted));
        }
        return null;
    }

    /**
     * Returns all "leaf" paths (final keys, without children) present
     * in the loaded file, in dotted format (e.g. {@code "sounds.volume"}).
     * Used by the unknown key / "did you mean" detection system.
     */
    public Set<String> leafPaths() {
        Set<String> result = new LinkedHashSet<>();
        collectPaths(data, "", result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void collectPaths(Map<String, Object> map, String prefix, Set<String> out) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
                collectPaths((Map<String, Object>) nested, path, out);
            } else {
                out.add(path);
            }
        }
    }

    private static Object resolve(Map<String, Object> root, String path) {
        String[] parts = splitPath(path);
        Object current = root;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private static String[] splitPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Config path must not be null or blank");
        }
        return path.split("\\.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                copy.put(entry.getKey(), deepCopy((Map<String, Object>) nested));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }
}