# ☕ CoffeeLib

**CoffeeLib** is a configuration library for Minecraft NeoForge 1.21.1 mods. It allows other mods to define YAML configuration files declaratively using Java annotations.

Each consuming mod gets its own isolated configuration manager, writing to its own subfolder under `config/<modId>/`.

---

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Annotation Reference](#annotation-reference)
- [Supported Types](#supported-types)
- [Serializers](#serializers)
- [Validation](#validation)
- [Hot Reload](#hot-reload)
- [Unknown Key Handling](#unknown-key-handling)
- [Full API](#full-api)
- [Complete Example](#complete-example)

---

## Features

- **Declarative** — define your config structure with Java classes and annotations
- **Auto-generation** — if the config file doesn't exist, it's created with defaults and comments
- **Type-safe** — typed access via `ConfigValue<T>.get()` and `ConfigValue<T>.set(T)`
- **Record support** — serialize Java 16+ records as nested YAML sections
- **Enum support** — enums are automatically serialized as strings
- **Range validation** — `@Range(min, max)` for numeric values
- **Hot reload** — monitor file changes with `WatchServiceManager`
- **"Did you mean?"** — suggests the closest key when an unknown key is found
- **Per-mod isolation** — each mod gets its own `config/<modId>/` folder
- **SnakeYAML** — uses SnakeYAML 2.2 under the hood, bundled via `jarJar`

---

## Installation

### 1. Add the Maven repository

In `build.gradle`:

```gradle
repositories {
    maven {
        url = "https://your-maven-repository"
    }
}
```

### 2. Add the dependency

```gradle
dependencies {
    implementation "net.loyalnetwork:coffeelib:1.0"
}
```

---

## Quick Start

### 1. Create a config class

```java
import net.loyalnetwork.coffeelib.config.annotation.*;
import net.loyalnetwork.coffeelib.config.node.ConfigValue;

@ConfigFile("server.yml")
public class MyConfig {

    @Comment({"Time in seconds between automatic saves.", "Very low values may cause lag."})
    public static final ConfigValue<Integer> AUTO_SAVE_INTERVAL = ConfigValue.of(300);

    @Key("player.default-gamemode")
    @Comment("Default game mode for new players.")
    public static final ConfigValue<String> DEFAULT_GAMEMODE = ConfigValue.of("survival");

    @Range(min = 0.1, max = 10.0)
    public static final ConfigValue<Double> MOVE_SPEED = ConfigValue.of(1.0);
}
```

### 2. Initialize in your mod constructor

```java
import net.loyalnetwork.coffeelib.api.CoffeeConfig;
import net.loyalnetwork.coffeelib.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("mymod")
public class MyMod {

    private static ConfigManager CONFIG;
    private static final Logger LOGGER = LoggerFactory.getLogger("MyMod");

    public MyMod() {
        CONFIG = CoffeeConfig.forMod("mymod", LOGGER)
                .register(MyConfig.class)
                .build();
        CONFIG.load();
    }
}
```

### 3. Use values in your code

```java
public void someMethod() {
    int interval = MyConfig.AUTO_SAVE_INTERVAL.get();     // 300 (or the file value)
    String gamemode = MyConfig.DEFAULT_GAMEMODE.get();    // "survival"

    if (MyConfig.MOVE_SPEED.get() > 5.0) {
        // apply logic
    }
}
```

### 4. Update values at runtime

```java
MyConfig.AUTO_SAVE_INTERVAL.set(600); // Updates both memory and disk
```

### The generated file (`config/mymod/server.yml`)

```yaml
# Time in seconds between automatic saves.
# Very low values may cause lag.
auto-save-interval: 300

player:
  default-gamemode: "survival"

move-speed: 1.0
```

---

## Annotation Reference

### `@ConfigFile("filename.yml")`

**Required.** Defines the config file name.

| Target   | Class |
|----------|-------|
| Value    | `String value()` — file name |

### `@Key("path.to.key")`

**Optional.** Sets the YAML key path. If omitted, the path is derived from the field name: lowercase, underscores become hyphens.

| Target   | `ConfigValue<?>` field |
|----------|------------------------|
| Example | `@Key("player.max-health")` |

| Field name                  | Generated path          |
|-----------------------------|-------------------------|
| `MAX_PLAYERS`               | `max-players`           |
| `playerDefaultGamemode`     | `playerdefaultgamemode` |
| `@Key("player.default-gamemode")` | `player.default-gamemode` |

### `@Comment({"line 1", "line 2"})`

**Optional.** Adds YAML comments above the key.

| Target   | `ConfigValue<?>` field |
|----------|------------------------|
| Value    | `String[] value()` — array of comment lines |

### `@Range(min = 0.0, max = 100.0)`

**Optional.** Validates that the numeric value falls within the specified range. If out of range, a warning is logged and the default is kept.

| Target   | `ConfigValue<? extends Number>` field |
|----------|---------------------------------------|
| Default  | `min = Double.NEGATIVE_INFINITY`, `max = Double.POSITIVE_INFINITY` |

### `@ReloadListener`

**Optional.** Marks a **static** method to be called whenever the config is reloaded (via `ConfigManager.reload()`).

| Target   | `static` method |
|----------|-----------------|

```java
@ReloadListener
public static void onConfigReload() {
    // Rebuild caches, restart scheduled tasks, etc.
}
```

### `@SerializeWith(MySerializer.class)`

**Optional.** Specifies a custom serializer for the field.

| Target   | `ConfigValue<?>` field |
|----------|------------------------|
| Value    | `Class<? extends ConfigSerializer<?>> value()` |

---

## Supported Types

### Primitive and wrapper types

| Java Type  | YAML                | Default Serializer       |
|------------|---------------------|--------------------------|
| `String`   | `"text"`            | `StringSerializer`       |
| `Integer` / `int` | `42`         | `IntegerSerializer`      |
| `Double` / `double` | `3.14`      | `DoubleSerializer`       |
| `Boolean` / `boolean` | `true` / `false` | `BooleanSerializer` |

### Enums

Enums are automatically serialized as strings (using `Enum.name()`).

```java
public enum Difficulty {
    PEACEFUL, EASY, NORMAL, HARD
}

@ConfigFile("game.yml")
public class GameConfig {
    public static final ConfigValue<Difficulty> DIFFICULTY = ConfigValue.of(Difficulty.NORMAL);
}
```

Output:

```yaml
difficulty: "NORMAL"
```

### Records

Java 16+ records are serialized as nested YAML sections.

```java
public record WorldSettings(int maxPlayers, boolean allowFlight, String motd) {}

@ConfigFile("world.yml")
public class WorldConfig {
    public static final ConfigValue<WorldSettings> WORLD_SETTINGS = ConfigValue.of(
        new WorldSettings(20, false, "Welcome!")
    );
}
```

Output:

```yaml
world-settings:
  max-players: 20
  allow-flight: false
  motd: "Welcome!"
```

Records can contain other records or types known to the registry, forming arbitrarily nested structures.

---

## Serializers

### Default Serializers

Globally registered by `ConfigBootstrap.registerDefaults()`:
- `StringSerializer`
- `IntegerSerializer`
- `DoubleSerializer`
- `BooleanSerializer`

### Custom Serializer

Implement the `ConfigSerializer<T>` interface:

```java
import net.loyalnetwork.coffeelib.config.serializer.ConfigSerializer;

public class ColorSerializer implements ConfigSerializer<java.awt.Color> {

    @Override
    public Object serialize(java.awt.Color value) {
        return String.format("#%06X", value.getRGB() & 0xFFFFFF);
    }

    @Override
    public java.awt.Color deserialize(Object value) {
        String hex = value.toString();
        return java.awt.Color.decode(hex);
    }
}
```

Applying it to a field:

```java
@SerializeWith(ColorSerializer.class)
public static final ConfigValue<java.awt.Color> BACKGROUND_COLOR = ConfigValue.of(java.awt.Color.BLACK);
```

### Global Registry

You can register serializers globally if you prefer:

```java
import net.loyalnetwork.coffeelib.config.serializer.SerializerRegistry;

SerializerRegistry.register(java.awt.Color.class, new ColorSerializer());
```

### Serializer Resolution Order

1. `@SerializeWith` on the field
2. `SerializerRegistry.find(type)` — global registry lookup
3. If type is `Enum` → `EnumSerializer`
4. If type is `Record` → `RecordSerializer`
5. No serializer → raw YAML value is used directly

---

## Validation

### Range Validation with `@Range`

```java
@Range(min = 1, max = 100)
public static final ConfigValue<Integer> MAX_PLAYERS = ConfigValue.of(10);
```

If the user sets `max-players: 500` in the YAML, the log will show:

```
WARN: Value 500.0 for 'max-players' in server.yml is out of range [1.0, 100.0]
```

### Custom Validators

Implement the `Validator<T>` interface:

```java
import net.loyalnetwork.coffeelib.config.validation.Validator;
import net.loyalnetwork.coffeelib.config.validation.ValidationException;

public class EvenNumberValidator implements Validator<Integer> {
    @Override
    public void validate(Integer value, String path, String file) throws ValidationException {
        if (value % 2 != 0) {
            throw new ValidationException(
                "Value " + value + " for '" + path + "' in " + file + " must be even."
            );
        }
    }
}
```

> **Note:** Custom validators need to be called manually. Annotation-based integration currently only covers `@Range`.

---

## Hot Reload

`WatchServiceManager` monitors the config directory and runs a callback when files are modified.

```java
import net.loyalnetwork.coffeelib.config.reload.WatchServiceManager;

var watcher = new WatchServiceManager();

// In the mod constructor:
watcher.start(configDir.toPath(), () -> {
    LOGGER.info("Config file changed, reloading...");
    CONFIG.reload();
});

// On mod shutdown:
watcher.close();
```

### `ConfigManager.reload()`

The `reload()` method runs in order:
1. Reloads the YAML for each file from disk
2. Re-reads all bound `ConfigValue`s
3. Invokes all methods annotated with `@ReloadListener`

If the YAML is malformed, the previous in-memory state is preserved.

---

## Unknown Key Handling

When CoffeeLib loads a file, it compares the keys present in the YAML with the expected keys (defined by annotated fields).

If an unknown key is found, the `DidYouMean` system calculates the Levenshtein distance and suggests the closest key:

```
WARN: Unknown key 'auto-save-interval' in server.yml — did you mean 'auto-save-interval'?
```

If no suggestion is found within the threshold:

```
WARN: Unknown key 'foo-bar' in server.yml does not match any known config option.
```

Missing keys are automatically added to the file with their default values.

---

## Full API

### `CoffeeConfig`

Public entry point. Each mod calls `CoffeeConfig.forMod()` and receives its own `ConfigManager.Builder`.

| Method | Description |
|--------|-------------|
| `CoffeeConfig.forMod(String modId, Logger logger)` | Returns a `Builder` configured for `config/<modId>/` |

### `ConfigManager.Builder`

| Method | Description |
|--------|-------------|
| `.register(Class<?> configClass)` | Registers a config class |
| `.build()` | Constructs the `ConfigManager` |

### `ConfigManager`

| Method | Description |
|--------|-------------|
| `.load()` | Loads all config files and binds `ConfigValue`s |
| `.reload()` | Reloads all files from disk, re-reads values and invokes `@ReloadListener`s |

### `ConfigValue<T>`

| Method | Description |
|--------|-------------|
| `ConfigValue.of(T defaultValue)` | Creates an instance with a default value |
| `.get()` | Returns the current cached value |
| `.set(T value)` | Updates the value in memory and persists to disk |
| `.reload()` | Re-reads the value from disk |
| `.defaultValue()` | Returns the original default value |
| `.isBound()` | Returns whether it's already bound to a configuration |

### `WatchServiceManager`

| Method | Description |
|--------|-------------|
| `.start(Path dir, Runnable onFileChange)` | Starts monitoring |
| `.close()` | Stops monitoring |

### `SerializerRegistry`

| Method | Description |
|--------|-------------|
| `register(Class<T> type, ConfigSerializer<T> serializer)` | Registers a global serializer |
| `get(Class<T> type)` | Looks up an exact serializer |
| `find(Class<T> type)` | Looks up a serializer, including superclasses and interfaces |
| `has(Class<?> type)` | Checks if a serializer exists |

### `ConfigSerializer<T>`

| Method | Description |
|--------|-------------|
| `Object serialize(T value)` | Converts a Java value to a YAML-serializable object |
| `T deserialize(Object value)` | Converts a raw YAML object back to a Java type |

---

## Complete Example

Here is an integrated example of a mod using all of CoffeeLib's core features:

```java
// ========== MyModConfig.java ==========
package mymod.config;

import net.loyalnetwork.coffeelib.config.annotation.*;
import net.loyalnetwork.coffeelib.config.node.ConfigValue;

@ConfigFile("mymod.yml")
public class MyModConfig {

    @Comment({"Mod log level.", "Values: DEBUG, INFO, WARN, ERROR"})
    public static final ConfigValue<LogLevel> LOG_LEVEL = ConfigValue.of(LogLevel.INFO);

    @Key("features.enable-fly")
    @Comment("Allows players to fly in the overworld.")
    public static final ConfigValue<Boolean> ENABLE_FLY = ConfigValue.of(false);

    @Range(min = 0.0, max = 1.0)
    @Comment("Special drop chance (0.0 to 1.0).")
    public static final ConfigValue<Double> DROP_CHANCE = ConfigValue.of(0.05);

    @Key("sounds.volume.master")
    @Range(min = 0.0, max = 1.0)
    public static final ConfigValue<Double> MASTER_VOLUME = ConfigValue.of(0.8);

    @Key("sounds.volume.music")
    @Range(min = 0.0, max = 1.0)
    public static final ConfigValue<Double> MUSIC_VOLUME = ConfigValue.of(0.5);

    @ReloadListener
    public static void onReload() {
        System.out.println("Config reloaded! Log level is now: " + LOG_LEVEL.get());
    }
}

// ========== LogLevel.java ==========
package mymod.config;

public enum LogLevel {
    DEBUG, INFO, WARN, ERROR
}

// ========== MyMod.java ==========
package mymod;

import mymod.config.MyModConfig;
import net.loyalnetwork.coffeelib.api.CoffeeConfig;
import net.loyalnetwork.coffeelib.config.ConfigManager;
import net.loyalnetwork.coffeelib.config.reload.WatchServiceManager;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("mymod")
public class MyMod {

    private static ConfigManager CONFIG;
    private static final Logger LOGGER = LoggerFactory.getLogger("mymod");

    public MyMod() {
        CONFIG = CoffeeConfig.forMod("mymod", LOGGER)
                .register(MyModConfig.class)
                .build();
        CONFIG.load();

        // Hot reload
        var watcher = new WatchServiceManager();
        watcher.start(FMLPaths.CONFIGDIR.get().resolve("mymod"), () -> {
            LOGGER.info("File changed, reloading...");
            CONFIG.reload();
        });
    }
}
```

Auto-generated file at `config/mymod/mymod.yml`:

```yaml
# Mod log level.
# Values: DEBUG, INFO, WARN, ERROR
log-level: "INFO"

features:
  enable-fly: false

drop-chance: 0.05

sounds:
  volume:
    master: 0.8

    music: 0.5
```

---

## License

All Rights Reserved.
