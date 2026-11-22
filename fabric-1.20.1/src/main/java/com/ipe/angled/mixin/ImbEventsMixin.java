package com.ipe.angled.mixin;

import com.ipe.angled.DiagonalGeometry;
import immersive_paintings.entity.ImmersivePaintingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optional compatibility with "Immersive Painting Blocks" (mod id {@code imb}).
 *
 * <p>That mod turns a graffiti entity into a {@code graffiti_block}, but its block renderer only
 * understands the six cardinal facings - it ignores the stored yaw for walls. Converting a 45
 * degree graffiti would therefore leave a block that is drawn flat against a face it does not
 * belong to, so the conversion is skipped instead. Converting angled graffiti could be supported
 * later by also teaching the block renderer about the yaw.</p>
 *
 * <p>The target is a mod class and is referenced by name, so this mod does not need it at compile
 * time. {@link ImbMixinPlugin} disables the whole config when the blocks mod is absent.</p>
 */
@Mixin(targets = "com.imb.events.IMBEvents")
public abstract class ImbEventsMixin {

    @Inject(method = "onUseEntity", at = @At("HEAD"), cancellable = true)
    private static void ipe$keepAngledGraffiti(PlayerEntity player, World world, Hand hand, Entity target,
                                               EntityHitResult hitResult, CallbackInfoReturnable<ActionResult> cir) {
        if (target instanceof ImmersivePaintingEntity painting
                && painting.isGraffiti()
                && DiagonalGeometry.isDiagonal(painting.getRotation())) {
            cir.setReturnValue(ActionResult.PASS);
        }
    }
}

