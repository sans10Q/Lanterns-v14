package com.lanterns;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;

import java.util.UUID;

/** Server -> client: "shifter now looks like target (shown as targetName)". target == (0,0) clears it. */
public record DisguisePayload(UUID shifter, UUID target, String targetName) implements CustomPayload {
    public static final CustomPayload.Id<DisguisePayload> ID = new CustomPayload.Id<>(Identifier.of(LanternsMod.MOD_ID, "disguise"));
    public static final PacketCodec<RegistryByteBuf, DisguisePayload> CODEC = PacketCodec.tuple(
            Uuids.PACKET_CODEC, DisguisePayload::shifter,
            Uuids.PACKET_CODEC, DisguisePayload::target,
            PacketCodecs.STRING, DisguisePayload::targetName,
            DisguisePayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
