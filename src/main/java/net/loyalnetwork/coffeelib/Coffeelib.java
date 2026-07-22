package net.loyalnetwork.coffeelib;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Entry class for the CoffeeLib mod. Does not manage any global state —
 * each mod that depends on CoffeeLib calls
 * {@link net.loyalnetwork.coffeelib.api.CoffeeConfig#forMod(String, Logger)}
 * and receives its own isolated ConfigManager.
 */
@Mod(Coffeelib.MODID)
public class Coffeelib {

    public static final String MODID = "coffeelib";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Coffeelib() {
        LOGGER.info("CoffeeLib loaded — use CoffeeConfig.forMod(modId, logger) to manage your mod's config.");
    }
}