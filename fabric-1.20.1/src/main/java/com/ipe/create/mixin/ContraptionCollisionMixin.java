package com.ipe.create.mixin;

import com.ipe.config.IpeConfig;
import immersive_paintings.entity.AbstractImmersiveDecorationEntity;
import net.minecraft.entity.Entity;
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
 * 32 blocks upwards and calls {@code world.getEntitiesByClass(Entity.class, box,
 * contraption::collidesWith)}. Everything that predicate accepts is then pushed, teleported and
 * damaged. Returning {@code false} here means a painting is never even considered, so no maths is
 * performed on it and no state is written.</p>
 *
 * <p>That matters because paintings cannot be pushed: {@code setPosition} makes them re-anchor
 * themselves to a block position (which used to fling them across the station) and {@code damage}
 * kills them and drops the picture. A train starting from a standstill while a picture sits next to
 * the platform edge could even crash the server from inside Create's movement code.</p>
 *
 * <p>The target is referenced by name and with {@code remap = false}, because Create is not a
 * compile time dependency. Two spellings are given for the same method: the runtime name in a
 * production instance ({@code method_30949}, Create overrides {@code Entity#collidesWith}) and the
 * plain name used when Create is remapped to yarn/ Mojang names in a development environment.</p>
 */
@Mixin(targets = "com.simibubi.create.content.contraptions.AbstractContraptionEntity", remap = false)
public abstract class ContraptionCollisionMixin {

    /** Every carriage of a Create train lives in this package. */
    @Unique
    private static final String TRAIN_PACKAGE = "com.simibubi.create.content.trains.";

    @Inject(
            method = {"method_30949", "collidesWith"},
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void ipe$ignorePaintings(Entity other, CallbackInfoReturnable<Boolean> cir) {
        if (!(other instanceof AbstractImmersiveDecorationEntity)) {
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
