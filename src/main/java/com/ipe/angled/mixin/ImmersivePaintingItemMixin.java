package com.ipe.angled.mixin;

import com.ipe.angled.DiagonalGeometry;
import immersive_paintings.item.ImmersivePaintingItem;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
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
 * so the placement context is remembered at the start of {@code useOnBlock} and the rotation
 * argument of {@code newPainting} is replaced afterwards. This keeps all of the surrounding logic
 * (permission checks, entity type selection for glow / graffiti variants, sounds, item damage and
 * the client-server handshake) exactly as it was.</p>
 */
@Mixin(ImmersivePaintingItem.class)
public abstract class ImmersivePaintingItemMixin {

    @Unique
    private static final ThreadLocal<ItemUsageContext> IPE_CONTEXT = new ThreadLocal<>();

    @Inject(method = "useOnBlock", at = @At("HEAD"))
    private void ipe$rememberContext(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        // Kept per thread because the same item instance is used by both the client and the
        // integrated server at the same time.
        IPE_CONTEXT.set(context);
    }

    @ModifyArg(
            method = "useOnBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Limmersive_paintings/item/ImmersivePaintingItem;newPainting("
                            + "Lnet/minecraft/world/World;"
                            + "Lnet/minecraft/util/math/BlockPos;"
                            + "Lnet/minecraft/util/math/Direction;I)"
                            + "Limmersive_paintings/entity/ImmersivePaintingEntity;"
            ),
            index = 3
    )
    private int ipe$angledRotation(int rotation) {
        ItemUsageContext context = IPE_CONTEXT.get();
        if (context == null) {
            return rotation;
        }

        PlayerEntity player = context.getPlayer();
        Direction side = context.getSide();
        if (player == null || !player.isSneaking() || !side.getAxis().isHorizontal()) {
            return rotation;
        }

        BlockPos attachmentPos = context.getBlockPos().offset(side);
        Vec3d hit = context.getHitPos();
        return DiagonalGeometry.chooseRotation(attachmentPos, side, hit.getX(), hit.getZ());
    }

    @Inject(method = "useOnBlock", at = @At("RETURN"))
    private void ipe$clearContext(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        IPE_CONTEXT.remove();
    }
}

