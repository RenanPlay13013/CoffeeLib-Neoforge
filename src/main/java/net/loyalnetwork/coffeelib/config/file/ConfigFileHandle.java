package net.loyalnetwork.coffeelib.config.file;

import net.loyalnetwork.coffeelib.config.suggest.DidYouMean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class ConfigFileHandle {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigFileHandle.class);

    private final File file;
    private final List<ConfigEntry> entries;
    private final Set<String> knownPaths;
    private final YamlNode defaultTree;
    private SimpleYamlConfig config;

    public ConfigFileHandle(File dataFolder, String fileName, List<ConfigEntry> entries) {
        this.file = new File(dataFolder, fileName);
        this.entries = entries;
        this.knownPaths = entries.stream()
                .map(ConfigEntry::path)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        this.defaultTree = YamlTreeBuilder.build(entries);
    }

    public void load() {
        boolean exists = file.exists();
        if (!exists) {
            createFile();
        }
        try {
            config = SimpleYamlConfig.load(file.toPath());
        } catch (ConfigParseException e) {
            LOGGER.error("Failed to parse {}: {}. Using defaults until the file is fixed.",
                    file, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            config = SimpleYamlConfig.empty();
        }
        addMissingKeys();
        warnUnknownKeys();
    }

    public void reload() {
        try {
            config.reload(file.toPath());
            warnUnknownKeys();
        } catch (ConfigParseException e) {
            LOGGER.error("Failed to reload {}: {}. Keeping previously loaded values.",
                    file, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
    }

    public SimpleYamlConfig config() {
        return config;
    }

    public File file() {
        return file;
    }

    public String fileName() {
        return file.getName();
    }

    private void createFile() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            String yaml = CommentYamlWriter.write(defaultTree);
            Files.writeString(file.toPath(), yaml);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create config file: " + file, e);
        }
    }

    private void addMissingKeys() {
        boolean modified = false;
        for (ConfigEntry entry : entries) {
            if (!config.contains(entry.path())) {
                config.set(entry.path(), entry.value());
                modified = true;
            }
        }
        if (modified) {
            String yaml = CommentYamlWriter.write(YamlTreeBuilder.build(
                    entries.stream()
                            .map(e -> new ConfigEntry(
                                    e.path(),
                                    config.get(e.path()),
                                    e.comments()
                            ))
                            .toList()
            ));
            try {
                Files.writeString(file.toPath(), yaml);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to update config file: " + file, e);
            }
        }
    }

    /**
     * Compares the keys actually present in the file with the known keys
     * (defined by {@code @ConfigFile}/{@code @Key}) and warns about possible
     * typos, suggesting the closest key by edit distance.
     */
    private void warnUnknownKeys() {
        for (String actual : config.leafPaths()) {
            if (knownPaths.contains(actual)) {
                continue;
            }
            Optional<String> suggestion = DidYouMean.suggest(actual, knownPaths);
            if (suggestion.isPresent()) {
                LOGGER.warn("Unknown key '{}' in {} — did you mean '{}'?",
                        actual, fileName(), suggestion.get());
            } else {
                LOGGER.warn("Unknown key '{}' in {} does not match any known config option.",
                        actual, fileName());
            }
        }
    }
}