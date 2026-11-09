package com.ipe.angled.mixin;

import com.ipe.angled.DiagonalGeometry;
import net.conczin.immersive_paintings.item.ImmersivePaintingItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes sneaking place paintings at 45 degrees.
 *
 * <p>Immersive Paintings computes the rotation argument itself and only ever uses multiples of 90,
 * so the placement context is remembered at the start of {@code useOn} and the rotation argument of
 * {@code setDirection} is replaced afterwards. This keeps all of the surrounding logic (permission
 * checks, entity type selection for glow / graffiti variants, sounds, item stack shrinking and the
 * client-server handshake) exactly as it was.</p>
 *
 * <p>1.18.2 note: there the same trick hooked a {@code newPainting(World, BlockPos, Direction,
 * int)} helper. That helper no longer exists - the 1.21.1 implementation inlines
 * {@code setPos(BlockPos)} plus {@code setDirection(Direction, int)} - so the hook moved one method
 * down the call chain. All glow / graffiti / normal variants inherit this {@code useOn}, so a single
 * injection covers every painting item.</p>
 */
@Mixin(ImmersivePaintingItem.class)
public abstract class ImmersivePaintingItemMixin {

    @Unique
    private static final ThreadLocal<UseOnContext> IPE_CONTEXT = new ThreadLocal<>();

    @Inject(method = "useOn", at = @At("HEAD"))
    private void ipe$rememberContext(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        // Kept per thread because the same item instance is used by both the client and the
        // integrated server at the same time.
        IPE_CONTEXT.set(context);
    }

    @ModifyArg(
            method = "useOn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/conczin/immersive_paintings/entity/ImmersivePaintingEntity;"
                            + "setDirection(Lnet/minecraft/core/Direction;I)V"
            ),
            index = 1
    )
    private int ipe$angledRotation(int rotation) {
        UseOnContext context = IPE_CONTEXT.get();
        if (context == null) {
            return rotation;
        }

        Player player = context.getPlayer();
        Direction side = context.getClickedFace();
        if (player == null || !player.isShiftKeyDown() || !side.getAxis().isHorizontal()) {
            return rotation;
        }

        BlockPos attachmentPos = context.getClickedPos().relative(side);
        Vec3 hit = context.getHitResult().getLocation();
        return DiagonalGeometry.chooseRotation(attachmentPos, side, hit.x, hit.z);
    }

    @Inject(method = "useOn", at = @At("RETURN"))
    private void ipe$clearContext(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        IPE_CONTEXT.remove();
    }
}

