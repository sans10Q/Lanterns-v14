package com.lanterns;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: the player finished (or abandoned) the task screen. The server re-validates everything. */
public record TaskResultPayload(int taskIndex, boolean success) implements CustomPayload {
    public static final CustomPayload.Id<TaskResultPayload> ID = new CustomPayload.Id<>(Identifier.of(LanternsMod.MOD_ID, "task_result"));
    public static final PacketCodec<RegistryByteBuf, TaskResultPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, TaskResultPayload::taskIndex,
            PacketCodecs.BOOL, TaskResultPayload::success,
            TaskResultPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
