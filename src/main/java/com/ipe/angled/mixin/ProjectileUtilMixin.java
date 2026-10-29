package com.ipe.angled.mixin;

import com.ipe.angled.DiagonalGeometry;
import com.ipe.config.IpeConfig;
import immersive_paintings.entity.ImmersivePaintingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
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
 * box is about 41% larger than the picture. Vanilla picking ({@code GameRenderer#pick}) raycasts
 * against exactly that box, which made the corners of the box swallow clicks meant for the blocks
 * around the painting: right clicking a block inside the box opened the painting editor and blocks
 * could not be placed or broken there.</p>
 *
 * <p>Every entity raycast goes through {@code ProjectileUtil}, so the result is re-validated here
 * against the real rotated rectangle and discarded when the ray only clipped the empty corner of
 * the box. Cardinal paintings are not touched at all.</p>
 */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin {

    @Unique
    private static boolean ipe$isAngledPainting(Entity target) {
        if (!(target instanceof ImmersivePaintingEntity painting)) {
            return false;
        }

        Direction facing = painting.getHorizontalFacing();
        return facing.getAxis().isHorizontal() && DiagonalGeometry.isDiagonal(painting.getRotation());
    }

    /**
     * Ray parameter of the hit against the real canvas, or {@link Double#NaN} when the ray does not
     * actually reach the tilted rectangle.
     */
    @Unique
    private static double ipe$canvasHit(Entity target, Vec3d start, Vec3d end) {
        ImmersivePaintingEntity painting = (ImmersivePaintingEntity) target;
        Direction facing = painting.getHorizontalFacing();
        // The entity position is the centre of the canvas slab, see AbstractDecorationMixin.
        return DiagonalGeometry.canvasRayHit(start, end, painting.getPos(),
                DiagonalGeometry.yaw(facing, painting.getRotation()),
                painting.getWidthPixels() / 32.0,
                painting.getHeightPixels() / 32.0);
    }

    @Unique
    private static EntityHitResult ipe$at(Entity target, Vec3d start, Vec3d end, double t) {
        return new EntityHitResult(target, start.add(end.subtract(start).multiply(t)));
    }

    /**
     * Crosshair picking. When the ray only crossed a box corner we redo the search without this
     * painting, so a block (or another entity) behind it is still selectable. When it really does
     * hit the canvas the reported position is moved onto the canvas, because vanilla compares that
     * position against the block hit to decide what the crosshair is on.
     */
    @Inject(
            method = "raycast(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Vec3d;"
                    + "Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Box;"
                    + "Ljava/util/function/Predicate;D)Lnet/minecraft/util/hit/EntityHitResult;",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void ipe$precisePicking(Entity entity, Vec3d start, Vec3d end, Box box,
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
        cir.setReturnValue(ProjectileUtil.raycast(entity, start, end, box,
                predicate.and(candidate -> candidate != target), maxDistance));
    }

    /**
     * Projectile collision (arrows and the like) uses its own loop over the boxes. Discarding the
     * hit makes the projectile fly through the empty corner instead of sticking to thin air.
     */
    @Inject(
            method = "getEntityCollision(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;"
                    + "Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;"
                    + "Lnet/minecraft/util/math/Box;Ljava/util/function/Predicate;F)"
                    + "Lnet/minecraft/util/hit/EntityHitResult;",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void ipe$preciseProjectiles(World world, Entity entity, Vec3d start, Vec3d end,
                                               Box box, Predicate<Entity> predicate, float margin,
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

