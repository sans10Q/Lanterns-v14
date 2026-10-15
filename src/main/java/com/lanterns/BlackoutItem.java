package com.lanterns;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/** Manhunter-only item. Right-click triggers System Blackout; useless in anyone else's hands. */
public class BlackoutItem extends Item {
    public BlackoutItem(Settings settings) { super(settings); }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity sp && LanternsMod.game() != null) {
            LanternsMod.game().onCoreUse(sp);
        }
        return TypedActionResult.success(stack);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.lanterns.blackout_core.tooltip1").formatted(Formatting.DARK_PURPLE));
        tooltip.add(Text.translatable("item.lanterns.blackout_core.tooltip2").formatted(Formatting.GRAY));
    }
}
