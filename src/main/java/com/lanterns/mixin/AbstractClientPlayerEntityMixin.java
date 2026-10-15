package com.lanterns.mixin;

import com.lanterns.ClientDisguises;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shapeshift: draw a disguised player with the TARGET's skin (and matching slim/wide model). */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin {
    @Inject(method = "getSkinTextures", at = @At("HEAD"), cancellable = true)
    private void lanterns$disguiseSkin(CallbackInfoReturnable<SkinTextures> cir) {
        AbstractClientPlayerEntity self = (AbstractClientPlayerEntity) (Object) this;
        ClientDisguises.Disguise d = ClientDisguises.get(self.getUuid());
        if (d == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(d.target());
        if (entry != null) cir.setReturnValue(entry.getSkinTextures());
    }
}
