package com.ipe.create.mixin;

import com.ipe.IpeMod;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies the Create compatibility mixin only when Create is installed, so the addon stays fully
 * usable (and silent) on a vanilla-only instance.
 */
public final class CreateMixinPlugin implements IMixinConfigPlugin {

    private static final String CREATE_MOD_ID = "create";

    private boolean createPresent;

    @Override
    public void onLoad(String mixinPackage) {
        createPresent = FabricLoader.getInstance().isModLoaded(CREATE_MOD_ID);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return createPresent;
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
        IpeMod.LOGGER.info("Create detected: contraption collision bypass applied to {}", targetClassName);
    }
}

