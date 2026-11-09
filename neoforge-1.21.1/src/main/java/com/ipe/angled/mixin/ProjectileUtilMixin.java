package com.ipe.angled.mixin;

import com.ipe.angled.DiagonalGeometry;
import com.ipe.config.IpeConfig;
import net.conczin.immersive_paintings.entity.ImmersivePaintingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Replaces the axis aligned picking box of angled paintings with the actual tilted canvas.
 *
 * <p>An angled painting needs an axis aligned bounding box that covers the whole diagonal, so its
 * box is about 41% larger than the picture. Vanilla picking raycasts against exactly that box, which
 * made the corners of the box swallow clicks meant for the blocks around the painting: right
 * clicking a block inside the box opened the painting editor and blocks could not be placed or
 * broken there.</p>
 *
 * <p>Every entity raycast funnels through {@code ProjectileUtil}, so the result is re-validated here
 * against the real rotated rectangle and discarded when the ray only clipped the empty corner of
 * the box. Cardinal paintings are not touched at all.</p>
 *
 * <p>1.18.2 note: the two hooks were {@code raycast(...)} for the crosshair and
 * {@code getEntityCollision(...)} for arrows. Both were folded into the {@code getEntityHitResult}
 * overloads in 1.20.5+, which is why the descriptors below differ from the Fabric build.</p>
 */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin {

    @Unique
    private static boolean ipe$isAngledPainting(Entity target) {
        if (!(target instanceof ImmersivePaintingEntity painting)) {
            return false;
        }
        return painting.getDirection().getAxis().isHorizontal() && DiagonalGeometry.isDiagonalYaw(painting.getYRot());
    }

    /**
     * Ray parameter of the hit against the real canvas, or {@link Double#NaN} when the ray does not
     * actually reach the tilted rectangle.
     */
    @Unique
    private static double ipe$canvasHit(Entity target, Vec3 start, Vec3 end) {
        ImmersivePaintingEntity painting = (ImmersivePaintingEntity) target;
        // The entity position is the centre of the canvas slab, see ImmersivePaintingEntityMixin.
        return DiagonalGeometry.canvasRayHit(start, end, painting.position(),
                painting.getYRot(),
                painting.getPaintingWidth() / 2.0,
                painting.getPaintingHeight() / 2.0);
    }

    @Unique
    private static EntityHitResult ipe$at(Entity target, Vec3 start, Vec3 end, double t) {
        double x = start.x + (end.x - start.x) * t;
        double y = start.y + (end.y - start.y) * t;
        double z = start.z + (end.z - start.z) * t;
        return new EntityHitResult(target, new Vec3(x, y, z));
    }

    /**
     * Crosshair picking. When the ray only crossed a box corner we redo the search without this
     * painting, so a block (or another entity) behind it is still selectable. When it really does
     * hit the canvas the reported position is moved onto the canvas, because vanilla compares that
     * position against the block hit to decide what the crosshair is on.
     */
    @Inject(
            method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;"
                    + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;"
                    + "Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void ipe$precisePicking(Entity entity, Vec3 start, Vec3 end, AABB box,
                                           Predicate<Entity> predicate, double maxDistance,
                                           CallbackInfoReturnable<EntityHitResult> cir) {
        EntityHitResult result = cir.getReturnValue();
        if (result == null
                || !IpeConfig.get().preciseAngledPicking
                || !ipe$isAngledPainting(result.getEntity())) {
            return;
        }

        Entity target = result.getEntity();
        double t = ipe$canvasHit(target, start, end);
        if (!Double.isNaN(t)) {
            cir.setReturnValue(ipe$at(target, start, end, t));
            return;
        }

        // Re-entering this method is safe: the predicate now excludes the painting we just rejected,
        // so the recursion strictly shrinks the candidate set.
        cir.setReturnValue(ProjectileUtil.getEntityHitResult(entity, start, end, box,
                predicate.and(candidate -> candidate != target), maxDistance));
    }

    /**
     * Projectile collision (arrows and the like) uses its own overload and loop over the boxes.
     * Discarding the hit makes the projectile fly through the empty corner instead of sticking to
     * thin air.
     */
    @Inject(
            method = "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;"
                    + "Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)"
                    + "Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void ipe$preciseProjectiles(Level level, Entity projectile, Vec3 start, Vec3 end,
                                               AABB box, Predicate<Entity> predicate, float margin,
                                               CallbackInfoReturnable<EntityHitResult> cir) {
        EntityHitResult result = cir.getReturnValue();
        if (result == null
                || !IpeConfig.get().preciseAngledPicking
                || !ipe$isAngledPainting(result.getEntity())) {
            return;
        }

        Entity target = result.getEntity();
        double t = ipe$canvasHit(target, start, end);
        cir.setReturnValue(Double.isNaN(t) ? null : ipe$at(target, start, end, t));
    }
}

