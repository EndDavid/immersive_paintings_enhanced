package com.ipe.angled.mixin;

import com.ipe.angled.DiagonalGeometry;
import com.ipe.config.IpeConfig;
import immersive_paintings.entity.AbstractImmersiveDecorationEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Teaches Immersive Paintings' decoration entities about the 45 degree rotation.
 *
 * <p>Everything is keyed off {@code rotation}: if it is not a multiple of 90 the entity is placed
 * and oriented diagonally, otherwise the original code runs untouched.</p>
 */
@Mixin(AbstractImmersiveDecorationEntity.class)
public abstract class AbstractDecorationMixin {

    @Shadow
    protected BlockPos attachmentPos;

    @Shadow
    protected Direction facing;

    @Shadow
    protected int rotation;

    /**
     * Immersive Paintings' own implementation rotates the bounding box with integer trigonometry
     * ({@code Math.round(cos(45°)) == 1}), which produces a nonsense box for 45 degrees. Position
     * and box are therefore computed here instead; for cardinal rotations we simply fall through.
     */
    @Inject(method = "updateAttachmentPosition", at = @At("HEAD"), cancellable = true)
    private void ipe$placeAtAnAngle(CallbackInfo ci) {
        DiagonalGeometry.Corner corner = DiagonalGeometry.corner(this.attachmentPos, this.facing, this.rotation);
        if (corner == null) {
            return;
        }

        AbstractImmersiveDecorationEntity self = (AbstractImmersiveDecorationEntity) (Object) this;
        double[] normal = DiagonalGeometry.normal(this.facing, this.rotation);

        int widthPixels = self.getWidthPixels();
        int heightPixels = self.getHeightPixels();

        // Sit right on top of the touched edge, then step out by half the canvas thickness so the
        // picture does not z-fight with the block corner.
        double x = corner.x() + normal[0] * DiagonalGeometry.HALF_THICKNESS;
        double z = corner.z() + normal[1] * DiagonalGeometry.HALF_THICKNESS;
        double y = this.attachmentPos.getY() + 0.5 + (heightPixels % 32 == 0 ? 0.5 : 0.0);

        self.setPos(x, y, z);
        self.setYaw(DiagonalGeometry.yaw(this.facing, this.rotation));
        self.setPitch(0.0f);

        // Axis aligned box around the rotated rectangle: the canvas spans +/- half its width along
        // the tangent (n.z, -n.x) and +/- half its thickness along the normal.
        double halfWidth = widthPixels / 32.0;
        double halfHeight = heightPixels / 32.0;
        double halfX = Math.abs(normal[1]) * halfWidth + Math.abs(normal[0]) * DiagonalGeometry.HALF_THICKNESS;
        double halfZ = Math.abs(normal[0]) * halfWidth + Math.abs(normal[1]) * DiagonalGeometry.HALF_THICKNESS;

        self.setBoundingBox(new Box(x - halfX, y - halfHeight, z - halfZ,
                x + halfX, y + halfHeight, z + halfZ));

        ci.cancel();
    }

    /**
     * Angled paintings are decorative props that lean against a wall corner, so they are allowed to
     * float: the support block is never required and the painting is never dropped. This also means
     * placing one never fails because the (necessarily larger) bounding box overlaps something.
     */
    @Inject(method = "canStayAttached", at = @At("HEAD"), cancellable = true)
    private void ipe$ignoreSupport(CallbackInfoReturnable<Boolean> cir) {
        if (IpeConfig.get().angledIgnoresSupport && DiagonalGeometry.isDiagonal(this.facing, this.rotation)) {
            cir.setReturnValue(true);
        }
    }

    // Note: "collides" is deliberately left alone. The client uses Entity#collides as the predicate
    // for crosshair picking, so returning false would make angled paintings impossible to edit or
    // break. The pick box is therefore the (axis aligned) bounding box, i.e. slightly larger than
    // the tilted canvas - com.ipe.angled.mixin.ProjectileUtilMixin performs the precise test.
}

