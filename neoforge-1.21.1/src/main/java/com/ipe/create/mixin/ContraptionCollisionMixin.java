package com.ipe.create.mixin;

import com.ipe.config.IpeConfig;
import net.conczin.immersive_paintings.entity.ImmersivePaintingEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides Immersive Paintings entities from Create's contraptions (trains, bearings, pistons,
 * gantries, ...).
 *
 * <p>Create's whole entity collision pipeline funnels through a single predicate:
 * {@code ContraptionCollider#collideEntities} builds a box around the contraption, stretches it
 * 32 blocks upwards and calls {@code level.getEntitiesOfClass(Entity.class, box,
 * contraption::canCollideWith)}. Everything that predicate accepts is then pushed, teleported and
 * damaged. Returning {@code false} here means a painting is never even considered, so no maths is
 * performed on it and no state is written.</p>
 *
 * <p>That matters because paintings cannot be pushed: {@code setPos} makes them re-anchor themselves
 * to a block position (which used to fling them across the station) and {@code hurt} kills them and
 * drops the picture. A train starting from a standstill while a picture sits next to the platform
 * edge could even crash the server from inside Create's movement code.</p>
 *
 * <p>The target is referenced by name and with {@code remap = false}, because Create is not a
 * compile time dependency. Several spellings are given for the same method so the mixin keeps
 * working across Create versions and development environments where the loader remaps names:
 * {@code canCollideWith} (1.20.5+ official name, Create 6.x), {@code collidesWith} (1.18.2 /
 * 1.19.2) and {@code method_30949} (intermediary name used by older Fabric builds).</p>
 */
@Mixin(targets = "com.simibubi.create.content.contraptions.AbstractContraptionEntity", remap = false)
public abstract class ContraptionCollisionMixin {

    /** Every carriage of a Create train lives in this package. */
    @Unique
    private static final String TRAIN_PACKAGE = "com.simibubi.create.content.trains.";

    @Inject(
            method = {"canCollideWith", "collidesWith", "method_30949"},
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void ipe$ignorePaintings(Entity other, CallbackInfoReturnable<Boolean> cir) {
        if (!(other instanceof ImmersivePaintingEntity)) {
            return;
        }

        IpeConfig config = IpeConfig.get();
        if (!config.disableContraptionCollision) {
            return;
        }
        if (config.onlyTrains && !this.getClass().getName().startsWith(TRAIN_PACKAGE)) {
            return;
        }

        cir.setReturnValue(false);
    }
}

