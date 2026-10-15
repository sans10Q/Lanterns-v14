package com.lanterns;

import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import net.minecraft.entity.player.PlayerEntity;

import java.util.List;

/**
 * ONE identical ring for EVERY player. What it does is decided purely server-side by the
 * holder's secret role, so equipment/tooltips/models can never leak who the Lantern is.
 */
public class RingItem extends Item {
    public RingItem(Settings settings) { super(settings); }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity sp && LanternsMod.game() != null) {
            LanternsMod.game().onRingUse(sp);
        }
        return TypedActionResult.success(stack);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.lanterns.power_ring.tooltip1").formatted(Formatting.GREEN));
        tooltip.add(Text.translatable("item.lanterns.power_ring.tooltip2").formatted(Formatting.DARK_GREEN, Formatting.ITALIC));
        tooltip.add(Text.translatable("item.lanterns.power_ring.tooltip3").formatted(Formatting.GRAY));
    }
}
