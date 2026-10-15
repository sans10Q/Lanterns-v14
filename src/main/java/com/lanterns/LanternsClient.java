package com.lanterns;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;

public class LanternsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(DisguisePayload.ID,
                (payload, context) -> context.client().execute(() -> ClientDisguises.apply(payload)));

        ClientPlayNetworking.registerGlobalReceiver(OpenTaskPayload.ID, (payload, context) -> context.client().execute(() -> {
            MinecraftClient mc = context.client();
            if (payload.ui().equals("close")) {
                if (mc.currentScreen instanceof TaskScreen) mc.setScreen(null);
                return;
            }
            mc.setScreen(new TaskScreen(payload.taskIndex(), payload.ui(), payload.seed(), payload.title()));
        }));

        ClientPlayNetworking.registerGlobalReceiver(TaskListPayload.ID,
                (payload, context) -> context.client().execute(() -> TaskHud.apply(payload)));

        ClientPlayNetworking.registerGlobalReceiver(WillPayload.ID,
                (payload, context) -> context.client().execute(() -> WillHud.apply(payload)));

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> { TaskHud.render(ctx); WillHud.render(ctx); });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { ClientDisguises.clear(); TaskHud.clear(); WillHud.clear(); });
    }
}
