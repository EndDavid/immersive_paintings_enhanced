package com.ipe.angled.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Enables the optional "Immersive Painting Blocks" compatibility mixin only when that mod is
 * actually installed, so nothing breaks - and nothing is logged - without it.
 */
public final class ImbMixinPlugin implements IMixinConfigPlugin {

    private static final String BLOCKS_MOD_ID = "imb";

    private boolean blocksModPresent;

    @Override
    public void onLoad(String mixinPackage) {
        blocksModPresent = FabricLoader.getInstance().isModLoaded(BLOCKS_MOD_ID);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return blocksModPresent;
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
    }
}

