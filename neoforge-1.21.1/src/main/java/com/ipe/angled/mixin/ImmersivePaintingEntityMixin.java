package com.ipe.angled.mixin;

import com.ipe.angled.DiagonalGeometry;
import com.ipe.config.IpeConfig;
import net.conczin.immersive_paintings.entity.ImmersivePaintingEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Teaches Immersive Paintings' painting entities about the 45 degree rotation.
 *
 * <p>Everything is keyed off {@code rotation}: if it is not a multiple of 90 the entity is placed
 * and oriented diagonally, otherwise the original code runs untouched.</p>
 *
 * <p>On 1.18.2 the equivalent code had to compute position and box itself and call
 * {@code setPos}/{@code setBoundingBox}. Since 1.19 Immersive Paintings derives both from
 * {@link net.minecraft.world.entity.decoration.HangingEntity#recalculateBoundingBox()}, which does
 *
 * <pre>
 *   AABB box = calculateBoundingBox(pos, direction);
 *   setPosRaw(box.getCenter());
 *   setBoundingBox(box);
 * </pre>
 *
 * so returning a different box is enough - position, tracking range and the collision box all
 * follow the same centre. That makes this port noticeably smaller than the 1.18.2 one.</p>
 */
@Mixin(ImmersivePaintingEntity.class)
public abstract class ImmersivePaintingEntityMixin {

    /** Still a plain field in Immersive Paintings 0.8.0, only moved from the abstract base class. */
    @Shadow
    private int rotation;

    /**
     * Replaces the axis aligned box of a diagonal painting with one that is centred on the diagonal
     * canvas, and yaws the entity so the renderer draws it tilted.
     *
     * <p>Immersive Paintings' own implementation rotates the box with integer trigonometry
     * ({@code Math.round(cos(45)) == 1}), which produces a nonsense box for 45 degrees, so the whole
     * box is computed here instead. For cardinal rotations we simply fall through.</p>
     */
    @Inject(method = "calculateBoundingBox", at = @At("HEAD"), cancellable = true)
    private void ipe$angledBox(BlockPos attachmentPos, Direction facing, CallbackInfoReturnable<AABB> cir) {
        DiagonalGeometry.Corner corner = DiagonalGeometry.corner(attachmentPos, facing, this.rotation);
        if (corner == null) {
            return;
        }

        ImmersivePaintingEntity self = (ImmersivePaintingEntity) (Object) this;
        double[] normal = DiagonalGeometry.normal(facing, this.rotation);

        // Sit right on top of the touched edge, then step out by half the canvas thickness so the
        // picture does not z-fight with the block corner.
        double x = corner.x() + normal[0] * DiagonalGeometry.HALF_THICKNESS;
        double z = corner.z() + normal[1] * DiagonalGeometry.HALF_THICKNESS;

        // Immersive Paintings centres paintings on the block edge: an even amount of blocks hangs
        // half a block higher so that it is symmetric around the clicked block.
        double y = attachmentPos.getY() + 0.5 + (self.getPaintingHeight() % 2 == 0 ? 0.5 : 0.0);

        // The renderer rotates by -yaw, so the entity yaw is all that is needed for the canvas,
        // frame and normals to follow the diagonal.
        self.absRotateTo(DiagonalGeometry.yaw(facing, this.rotation), 0.0f);

        // Axis aligned box around the rotated rectangle: the canvas spans +/- half its width along
        // the tangent (n.z, -n.x) and +/- half its thickness along the normal.
        double halfWidth = self.getPaintingWidth() / 2.0;
        double halfHeight = self.getPaintingHeight() / 2.0;
        double halfX = Math.abs(normal[1]) * halfWidth + Math.abs(normal[0]) * DiagonalGeometry.HALF_THICKNESS;
        double halfZ = Math.abs(normal[0]) * halfWidth + Math.abs(normal[1]) * DiagonalGeometry.HALF_THICKNESS;

        cir.setReturnValue(AABB.ofSize(new Vec3(x, y, z), halfX * 2.0, halfHeight * 2.0, halfZ * 2.0));
    }

    /**
     * Angled paintings are decorative props that lean against a wall corner, so they are allowed to
     * float: the support block is never required and the painting is never dropped. This also means
     * placing one never fails because the (necessarily larger) bounding box overlaps something.
     */
    @Inject(method = "survives", at = @At("HEAD"), cancellable = true)
    private void ipe$ignoreSupport(CallbackInfoReturnable<Boolean> cir) {
        ImmersivePaintingEntity self = (ImmersivePaintingEntity) (Object) this;
        if (IpeConfig.get().angledIgnoresSupport
                && DiagonalGeometry.isDiagonal(self.getDirection(), this.rotation)) {
            cir.setReturnValue(true);
        }
    }

    // Note: "canBeCollidedWith" is deliberately left alone. The client uses it as the predicate for
    // crosshair picking, so returning false would make angled paintings impossible to edit or
    // break. The pick box is therefore the (axis aligned) bounding box, i.e. slightly larger than
    // the tilted canvas - com.ipe.angled.mixin.ProjectileUtilMixin performs the precise test.
}

