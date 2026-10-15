package com.lanterns;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> client: open the task screen {@code ui} (or close it when ui == "close"). */
public record OpenTaskPayload(int taskIndex, String ui, int seed, String title) implements CustomPayload {
    public static final CustomPayload.Id<OpenTaskPayload> ID = new CustomPayload.Id<>(Identifier.of(LanternsMod.MOD_ID, "open_task"));
    public static final PacketCodec<RegistryByteBuf, OpenTaskPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, OpenTaskPayload::taskIndex,
            PacketCodecs.STRING, OpenTaskPayload::ui,
            PacketCodecs.INTEGER, OpenTaskPayload::seed,
            PacketCodecs.STRING, OpenTaskPayload::title,
            OpenTaskPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
