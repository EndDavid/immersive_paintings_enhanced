package com.ipe.imb.mixin;

import com.ipe.angled.DiagonalGeometry;
import net.conczin.immersive_paintings.entity.ImmersivePaintingEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optional compatibility with "Immersive Painting Blocks" (mod id {@code imb}).
 *
 * <p>That mod replaces a graffiti entity with a {@code graffiti_block} and stores the original
 * {@code yaw} on the block entity. Its renderer only uses that yaw for floor and ceiling graffiti
 * ({@code case UP} / {@code case DOWN}); for the four horizontal facings it rotates by a fixed
 * 0/90/180/270 degrees and ignores the stored yaw entirely. A 45 degree graffiti converted to a
 * block would therefore be drawn flat against the wall, i.e. visibly wrong, so the conversion is
 * refused instead and the graffiti stays an entity.</p>
 *
 * <p>1.18.2 note: the Fabric build hooked {@code onUseEntity}, which limited the guard to the
 * graffiti converter item. Since 1.21.1 the mod funnels every conversion through
 * {@code IMBEvents#convertGraffitiToBlock(Entity)} - the item event handler and both
 * {@code /imb} command paths all call it - so guarding that one method covers every entry point
 * with a single injection.</p>
 *
 * <p>The target is a mod class referenced by name, so {@code imb} is not a compile time dependency.
 * {@link ImbMixinPlugin} removes this config entirely when the blocks mod is absent, and
 * {@code require = 0} keeps a future rename in that mod from crashing the game.</p>
 */
@Mixin(targets = "com.imb.events.IMBEvents")
public abstract class ImbEventsMixin {

    @Inject(method = "convertGraffitiToBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ipe$keepAngledGraffiti(Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (!(target instanceof ImmersivePaintingEntity painting) || !painting.isGraffiti()) {
            return;
        }

        // Rotation is private in Immersive Paintings, but the yaw the entity renders with is public
        // and diagonals are exactly the ones that are not a multiple of 90 degrees.
        if (DiagonalGeometry.isDiagonalYaw(painting.getYRot())) {
            cir.setReturnValue(false);
        }
    }
}
