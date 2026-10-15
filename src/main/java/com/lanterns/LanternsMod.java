package com.lanterns;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class LanternsMod implements ModInitializer {
    public static final String MOD_ID = "lanterns";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static GameManager game;
    private static final java.util.List<String> TASK_TYPES = java.util.List.of(
            "wires", "keypad", "shields", "download", "focus", "spectrum", "lever", "timing", "calibrate", "scan", "puzzle");

    public static GameManager game() { return game; }

    @Override
    public void onInitialize() {
        Config.load();
        ModItems.init();
        PayloadTypeRegistry.playS2C().register(DisguisePayload.ID, DisguisePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenTaskPayload.ID, OpenTaskPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(TaskListPayload.ID, TaskListPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(WillPayload.ID, WillPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(TaskResultPayload.ID, TaskResultPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TaskResultPayload.ID, (payload, context) -> {
            ServerPlayerEntity sp = context.player();
            context.server().execute(() -> { if (game != null) game.onTaskResult(sp, payload.taskIndex(), payload.success()); });
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> game = new GameManager(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (game != null) game.shutdown(); });
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (game != null) game.tick(); });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> { if (game != null) game.onDisconnect(handler.getPlayer()); });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> { if (game != null) game.onJoin(handler.getPlayer()); });

        // Players only die to Manhunters or votes: block all damage to living participants.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayerEntity sp && game != null && game.isLivingParticipant(sp)));

        // Ghost chat: the dead talk only to the dead, so they can't leak info to the living.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (game != null && game.isGhost(sender)) {
                game.sendGhostChat(sender, message.getContent().getString());
                return false;
            }
            if (game != null && game.disguiseName(sender) != null) { // shapeshifted Manhunters talk as their disguise
                game.broadcastDisguisedChat(sender, message.getContent().getString());
                return false;
            }
            return true;
        });

        // Right-click a body to report it.
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient || game == null || !(player instanceof ServerPlayerEntity sp) || !game.isBody(entity))
                return ActionResult.PASS;
            String err = game.report(sp, entity);
            if (err != null) sp.sendMessage(Text.literal(err).formatted(Formatting.RED), true);
            return ActionResult.SUCCESS;
        });

        // Manhunters kill by punching; nobody can ever deal PvP damage during a game.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient || game == null || !(player instanceof ServerPlayerEntity sp) || !(entity instanceof ServerPlayerEntity target))
                return ActionResult.PASS;
            return game.onAttack(sp, target) ? ActionResult.FAIL : ActionResult.PASS;
        });

        // Task stations: right-click a registered block.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient || game == null || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (!game.isStation(sp, hit.getBlockPos())) return ActionResult.PASS;
            if (hand == Hand.MAIN_HAND) game.onBlockUse(sp, hit.getBlockPos());
            return ActionResult.SUCCESS;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> registerCommands(dispatcher));
    }

    private static int fail(CommandContext<ServerCommandSource> c, String msg) {
        c.getSource().sendError(Text.literal(msg));
        return 0;
    }

    private static int ok(CommandContext<ServerCommandSource> c, String msg) {
        c.getSource().sendFeedback(() -> Text.literal(msg).formatted(Formatting.GREEN), false);
        return 1;
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("lantern")
            .executes(c -> ok(c, "Lanterns: Shadows of the Corps - /lantern start|stop|status|role|tasks|task|forcerole|meeting|report|vote|setmeetingpoint|reload"))

            .then(literal("start").requires(s -> s.hasPermissionLevel(2))
                .executes(c -> doStart(c, false))
                .then(literal("force").executes(c -> doStart(c, true))))

            .then(literal("stop").requires(s -> s.hasPermissionLevel(2)).executes(c -> {
                if (game == null || !game.isRunning()) return fail(c, "No game is running.");
                game.stop("Stopped by an administrator.");
                return ok(c, "Game stopped.");
            }))

            .then(literal("status").executes(c -> {
                if (game == null) return fail(c, "Server not ready.");
                for (Text t : game.status()) c.getSource().sendFeedback(() -> t, false);
                return 1;
            }))

            .then(literal("meeting").executes(c -> {
                ServerPlayerEntity p = playerOrFail(c);
                if (p == null) return 0;
                String err = game == null ? "Server not ready." : game.emergency(p);
                return err == null ? 1 : fail(c, err);
            }))

            .then(literal("report").executes(c -> {
                ServerPlayerEntity p = playerOrFail(c);
                if (p == null) return 0;
                String err = game == null ? "Server not ready." : game.report(p, null);
                return err == null ? 1 : fail(c, err);
            }))

            .then(literal("vote")
                .then(literal("skip").executes(c -> {
                    ServerPlayerEntity p = playerOrFail(c);
                    if (p == null) return 0;
                    String err = game == null ? "Server not ready." : game.vote(p, GameLogic.SKIP);
                    return err == null ? 1 : fail(c, err);
                }))
                .then(argument("target", EntityArgumentType.player()).executes(c -> {
                    ServerPlayerEntity p = playerOrFail(c);
                    if (p == null) return 0;
                    ServerPlayerEntity t = EntityArgumentType.getPlayer(c, "target");
                    String err = game == null ? "Server not ready." : game.vote(p, t.getUuid());
                    return err == null ? 1 : fail(c, err);
                })))

            .then(literal("task").requires(s -> s.hasPermissionLevel(2))
                .then(literal("add").then(argument("type", StringArgumentType.word())
                    .suggests((c, b) -> CommandSource.suggestMatching(TASK_TYPES, b))
                    .executes(LanternsMod::taskAdd)))
                .then(literal("remove").executes(LanternsMod::taskRemove))
                .then(literal("list").executes(LanternsMod::taskList))
                .then(literal("clear").executes(c -> { Config.get().tasks.clear(); Config.save(); return ok(c, "All task stations removed."); })))

            .then(literal("tasks").executes(c -> {
                ServerPlayerEntity p = playerOrFail(c);
                if (p == null) return 0;
                if (game == null) return fail(c, "Server not ready.");
                game.showTasks(p);
                return 1;
            }))

            .then(literal("forcerole").requires(s -> s.hasPermissionLevel(2))
                .then(argument("role", StringArgumentType.word())
                    .suggests((c, b) -> CommandSource.suggestMatching(java.util.List.of("manhunter", "innocent", "lantern", "clear"), b))
                    .executes(c -> doForce(c, null))
                    .then(argument("player", EntityArgumentType.player()).executes(c -> doForce(c, EntityArgumentType.getPlayer(c, "player"))))))

            .then(literal("role").executes(c -> {
                ServerPlayerEntity p = playerOrFail(c);
                if (p == null) return 0;
                if (game == null) return fail(c, "Server not ready.");
                game.showRole(p);
                return 1;
            }))

            .then(literal("setmeetingpoint").requires(s -> s.hasPermissionLevel(2)).executes(c -> {
                ServerPlayerEntity p = playerOrFail(c);
                if (p == null) return 0;
                Config.MeetingPoint mp = Config.get().meetingPoint;
                mp.set = true;
                mp.dimension = p.getWorld().getRegistryKey().getValue().toString();
                mp.x = p.getX(); mp.y = p.getY(); mp.z = p.getZ(); mp.yaw = p.getYaw(); mp.pitch = p.getPitch();
                Config.save();
                return ok(c, String.format("Meeting point saved: %s (%.1f, %.1f, %.1f)", mp.dimension, mp.x, mp.y, mp.z));
            }))

            .then(literal("reload").requires(s -> s.hasPermissionLevel(2)).executes(c -> {
                Config.load();
                return ok(c, "Config reloaded.");
            })));
    }

    private static BlockPos lookedBlock(ServerPlayerEntity p) {
        HitResult h = p.raycast(6.0, 0.0f, false);
        return h.getType() == HitResult.Type.BLOCK ? ((BlockHitResult) h).getBlockPos() : null;
    }

    private static int taskAdd(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = playerOrFail(c);
        if (p == null) return 0;
        String type = StringArgumentType.getString(c, "type").toLowerCase();
        if (!TASK_TYPES.contains(type)) return fail(c, "Unknown task type. Use: " + String.join(", ", TASK_TYPES));
        BlockPos bp = lookedBlock(p);
        if (bp == null) return fail(c, "Look at the block that should become a task station (within 6 blocks).");
        String dim = p.getWorld().getRegistryKey().getValue().toString();
        for (Config.TaskStation s : Config.get().tasks)
            if (s.matches(dim, bp.getX(), bp.getY(), bp.getZ())) return fail(c, "That block is already a task station.");
        Config.TaskStation s = new Config.TaskStation();
        s.dimension = dim; s.x = bp.getX(); s.y = bp.getY(); s.z = bp.getZ(); s.type = type;
        Config.get().tasks.add(s);
        Config.save();
        return ok(c, "Added " + type + " station at " + bp.toShortString() + " (" + Config.get().tasks.size() + " total).");
    }

    private static int taskRemove(CommandContext<ServerCommandSource> c) {
        ServerPlayerEntity p = playerOrFail(c);
        if (p == null) return 0;
        BlockPos bp = lookedBlock(p);
        if (bp == null) return fail(c, "Look at the task station block to remove.");
        String dim = p.getWorld().getRegistryKey().getValue().toString();
        boolean removed = Config.get().tasks.removeIf(s -> s.matches(dim, bp.getX(), bp.getY(), bp.getZ()));
        if (!removed) return fail(c, "That block is not a task station.");
        Config.save();
        return ok(c, "Station removed (" + Config.get().tasks.size() + " left).");
    }

    private static int taskList(CommandContext<ServerCommandSource> c) {
        if (Config.get().tasks.isEmpty()) return ok(c, "No task stations yet. Look at a block and run /lantern task add <wires|keypad|shields|download|focus|spectrum|lever|timing|calibrate>.");
        for (Config.TaskStation s : Config.get().tasks)
            c.getSource().sendFeedback(() -> Text.literal(s.type + " @ " + s.dimension + " " + s.x + " " + s.y + " " + s.z).formatted(Formatting.GRAY), false);
        return ok(c, Config.get().tasks.size() + " station(s).");
    }

    private static int doForce(CommandContext<ServerCommandSource> c, ServerPlayerEntity target) throws CommandSyntaxException {
        if (game == null) return fail(c, "Server not ready.");
        ServerPlayerEntity who = target != null ? target : c.getSource().getPlayer();
        if (who == null) return fail(c, "Specify a player when running from the console.");
        String role = StringArgumentType.getString(c, "role").toLowerCase();
        String msg = game.forceRole(who.getUuid(), who.getName().getString(), role);
        return msg.startsWith("Unknown") ? fail(c, msg) : ok(c, msg);
    }

    private static int doStart(CommandContext<ServerCommandSource> c, boolean force) {
        if (game == null) return fail(c, "Server not ready.");
        String err = game.start(force);
        return err == null ? ok(c, "Game started.") : fail(c, err);
    }

    private static ServerPlayerEntity playerOrFail(CommandContext<ServerCommandSource> c) {
        ServerPlayerEntity p = c.getSource().getPlayer();
        if (p == null) c.getSource().sendError(Text.literal("This command must be run by a player."));
        return p;
    }
}
