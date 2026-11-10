package com.ipe.imb.mixin;

import com.ipe.IpeMod;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies the "Immersive Painting Blocks" compatibility mixin only when that mod is installed, so
 * this addon stays fully usable (and silent) on an instance without it.
 *
 * <p>Same contract as {@link com.ipe.create.mixin.CreateMixinPlugin}: mixin config plugins run before
 * the mod list is finalised, so {@code ModList} is not usable yet - {@link LoadingModList} is the
 * loader side view that is.</p>
 */
public final class ImbMixinPlugin implements IMixinConfigPlugin {

    private static final String IMB_MOD_ID = "imb";

    private boolean imbPresent;

    @Override
    public void onLoad(String mixinPackage) {
        try {
            LoadingModList modList = LoadingModList.get();
            imbPresent = modList != null && modList.getModFileById(IMB_MOD_ID) != null;
        } catch (Throwable throwable) {
            // Never let a detection failure break the game.
            imbPresent = false;
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return imbPresent;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        IpeMod.LOGGER.info("Immersive Painting Blocks detected: angled graffiti conversion guard applied to {}",
                targetClassName);
    }
}

