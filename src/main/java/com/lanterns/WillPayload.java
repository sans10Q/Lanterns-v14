package com.lanterns;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server -> client: this player's own Will (0..100) and tier index (0 steady .. 3 broken).
 * will < 0 hides the Will bar and vignette. Never sent to Manhunters (they have no Will).
 */
public record WillPayload(int will, int tier) implements CustomPayload {
    public static final CustomPayload.Id<WillPayload> ID = new CustomPayload.Id<>(Identifier.of(LanternsMod.MOD_ID, "will"));
    public static final PacketCodec<RegistryByteBuf, WillPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, WillPayload::will,
            PacketCodecs.VAR_INT, WillPayload::tier,
            WillPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
