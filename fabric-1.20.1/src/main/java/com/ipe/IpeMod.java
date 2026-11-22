package com.ipe;

import com.ipe.config.IpeConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of "Immersive Paintings: Enhanced".
 *
 * <p>This used to be a single-purpose addon that allowed 45 degree paintings. It is now a small
 * enhancement bundle around Immersive Paintings: it keeps the angled placement, and additionally
 * fixes interactions between Immersive Paintings entities and other mods (currently Create's
 * contraptions and trains).</p>
 *
 * <p>No blocks, items or entity types are added. Everything is done by tweaking the behaviour of
 * Immersive Paintings' own entities and items through mixins, which keeps it compatible with
 * existing worlds and with addons that consume {@code ImmersivePaintingEntity}.</p>
 */
public final class IpeMod implements ModInitializer {

    public static final String MOD_ID = "ipe";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Resolving the config here makes sure the file is written on the very first launch.
        IpeConfig config = IpeConfig.get();
        LOGGER.info("Immersive Paintings: Enhanced ready - sneak + right click a wall to place a 45 degree painting");

        if (FabricLoader.getInstance().isModLoaded("create")) {
            LOGGER.info("Create detected - contraption collisions for paintings are {}",
                    config.disableContraptionCollision ? "disabled" : "left untouched");
        }
    }
}

