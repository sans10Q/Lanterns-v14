package com.lanterns.mixin;

import com.lanterns.ClientDisguises;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shapeshift: a disguised player's nametag shows the TARGET's name. The table is empty on a dedicated server. */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void lanterns$disguiseName(CallbackInfoReturnable<Text> cir) {
        ClientDisguises.Disguise d = ClientDisguises.get(((PlayerEntity) (Object) this).getUuid());
        if (d != null) cir.setReturnValue(Text.literal(d.name()));
    }
}
