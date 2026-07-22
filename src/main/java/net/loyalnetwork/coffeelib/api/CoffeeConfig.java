package net.loyalnetwork.coffeelib.api;

import net.loyalnetwork.coffeelib.config.ConfigManager;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.File;

/**
 * Public entry point for CoffeeLib. Each consuming mod calls
 * {@link #forMod(String, Logger)} to obtain its own {@link ConfigManager},
 * isolated from other mods — each one writes to its own subfolder under
 * {@code config/<modId>/}.
 */
public final class CoffeeConfig {

    private CoffeeConfig() {
    }

    /**
     * @param modId  the mod id of the consuming mod (used as subfolder name under {@code config/})
     * @param logger the consuming mod's own logger — config warnings
     *               (unknown key, "did you mean", parse failure) appear
     *               with that mod's identity in the log, not CoffeeLib's.
     */
    public static ConfigManager.Builder forMod(String modId, Logger logger) {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId must not be null or blank");
        }
        File configDir = FMLPaths.CONFIGDIR.get().resolve(modId).toFile();
        return ConfigManager.builder(logger, configDir);
    }
}