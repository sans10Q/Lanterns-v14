package com.lanterns;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;

public final class ModItems {
    private ModItems() {}

    public static final Item POWER_RING = Registry.register(Registries.ITEM,
            Identifier.of("lanterns", "power_ring"),
            new RingItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC)
                    .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)));

    public static final Item BLACKOUT_CORE = Registry.register(Registries.ITEM,
            Identifier.of("lanterns", "blackout_core"),
            new BlackoutItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE)));

    public static void init() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(e -> { e.add(POWER_RING); e.add(BLACKOUT_CORE); });
    }
}
