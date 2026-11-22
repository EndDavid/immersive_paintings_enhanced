package com.ipe.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.ipe.IpeMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tiny JSON configuration file, living next to the other mod configs.
 *
 * <p>Every mixin only reads the values it needs, so the class is safe to touch from a class
 * transformer thread as well as from the server thread. The file is created with the default
 * values the first time the mod runs.</p>
 */
public final class IpeConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "immersive_paintings_enhanced.json";

    private static volatile IpeConfig instance;

    /**
     * Stop Create contraptions (trains, bearings, pistons, gantries, ...) from treating paintings
     * as entities they can collide with.
     *
     * <p>Create pushes every entity it can "see" inside a box that is stretched 32 blocks upwards.
     * Paintings can neither be pushed nor killed without side effects: they re-anchor themselves to
     * a block position on {@code setPosition} and drop their picture on {@code damage}, which used
     * to fling pictures around and, on a train starting from standstill, crashed the server.</p>
     */
    public boolean disableContraptionCollision = true;

    /** Only ignore paintings for train carriages instead of for every contraption. */
    public boolean onlyTrains = false;

    /**
     * 45 degree paintings never need a support block and never turn into a dropped item, even if
     * the wall they lean against is removed.
     */
    public boolean angledIgnoresSupport = true;

    /**
     * Use the real tilted canvas instead of the (much larger) axis aligned bounding box when the
     * crosshair picks a 45 degree painting, so blocks around it stay usable.
     */
    public boolean preciseAngledPicking = true;

    /** Required by Gson. */
    public IpeConfig() {
    }

    public static IpeConfig get() {
        IpeConfig config = instance;
        if (config == null) {
            synchronized (IpeConfig.class) {
                config = instance;
                if (config == null) {
                    config = read();
                    instance = config;
                }
            }
        }
        return config;
    }

    private static IpeConfig read() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);

        if (Files.exists(path)) {
            try {
                IpeConfig config = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), IpeConfig.class);
                if (config != null) {
                    return config;
                }
            } catch (IOException | JsonParseException exception) {
                IpeMod.LOGGER.warn("Could not read {}, falling back to defaults", FILE_NAME, exception);
                return new IpeConfig();
            }
        }

        IpeConfig config = new IpeConfig();
        config.write(path);
        return config;
    }

    private void write(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            IpeMod.LOGGER.warn("Could not write {}", FILE_NAME, exception);
        }
    }
}

