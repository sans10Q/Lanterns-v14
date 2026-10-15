package com.lanterns;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server -> client: this player's own task list for the HUD tracker.
 * data = entries separated by ';', each "title|x|y|z|dimension|done(0/1)". Empty data hides the HUD.
 */
public record TaskListPayload(String data, int done, int total, int team) implements CustomPayload {
    public static final CustomPayload.Id<TaskListPayload> ID = new CustomPayload.Id<>(Identifier.of(LanternsMod.MOD_ID, "task_list"));
    public static final PacketCodec<RegistryByteBuf, TaskListPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, TaskListPayload::data,
            PacketCodecs.VAR_INT, TaskListPayload::done,
            PacketCodecs.VAR_INT, TaskListPayload::total,
            PacketCodecs.VAR_INT, TaskListPayload::team,
            TaskListPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
