package com.utilmod.hovertotem;

import com.google.gson.JsonObject;
import com.utilmod.mixin.AbstractContainerScreenAccessor;
import com.utilmod.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Hover Totem.
 * - Opening the inventory selects hotbar slot 9.
 * - Hovering a totem: offhand empty of totems -> totem goes to offhand, then another totem goes to slot 9.
 *                     offhand already has a totem -> the hovered totem goes to slot 9.
 * All moves are vanilla SWAP clicks through MultiPlayerGameMode, so the server gets normal container-click packets.
 */
public final class HoverTotemModule extends Module {
    public static final String ID = "hover_totem";

    private static final int HOTBAR9_MENU_SLOT = 44;
    private static final int OFFHAND_MENU_SLOT = 45;
    private static final int HOTBAR9_BUTTON = 8;
    private static final int OFFHAND_BUTTON = 40;

    private boolean selectSlotOnOpen = true;
    private boolean inventoryWasOpen;

    public HoverTotemModule() {
        super(ID, "Hover Totem",
                "Hover a totem in your inventory to move it to the offhand / hotbar slot 9.", true);
    }

    @Override
    protected void onDisable() {
        inventoryWasOpen = false;
    }

    @Override
    public void loadConfig(JsonObject section) {
        if (section.has("selectSlotOnOpen") && section.get("selectSlotOnOpen").isJsonPrimitive()) {
            selectSlotOnOpen = section.get("selectSlotOnOpen").getAsBoolean();
        }
    }

    @Override
    public void saveConfig(JsonObject section) {
        section.addProperty("selectSlotOnOpen", selectSlotOnOpen);
    }

    @Override
    public void onTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || !(mc.screen instanceof InventoryScreen screen)) {
            inventoryWasOpen = false;
            return;
        }

        if (!inventoryWasOpen) {
            inventoryWasOpen = true;
            if (selectSlotOnOpen) {
                Inventory inventory = player.getInventory();
                if (inventory.getSelectedSlot() != HOTBAR9_BUTTON) {
                    inventory.setSelectedSlot(HOTBAR9_BUTTON); // synced by the game's normal per-tick slot sync
                }
            }
        }

        Slot hovered = ((AbstractContainerScreenAccessor) screen).utilmod$getHoveredSlot();
        if (hovered == null || !isTotem(hovered.getItem())) return;

        int index = hovered.index;
        if (index < 9 || index > 44) return; // main inventory + hotbar only

        InventoryMenu menu = player.inventoryMenu;
        boolean offhandHasTotem = isTotem(menu.getSlot(OFFHAND_MENU_SLOT).getItem());

        if (!offhandHasTotem) {
            swap(mc, player, menu, index, OFFHAND_BUTTON);
            if (!isTotem(menu.getSlot(HOTBAR9_MENU_SLOT).getItem())) {
                int other = findOtherTotem(menu);
                if (other != -1) {
                    swap(mc, player, menu, other, HOTBAR9_BUTTON);
                }
            }
        } else if (index != HOTBAR9_MENU_SLOT && !isTotem(menu.getSlot(HOTBAR9_MENU_SLOT).getItem())) {
            swap(mc, player, menu, index, HOTBAR9_BUTTON);
        }
    }

    private static void swap(Minecraft mc, LocalPlayer player, InventoryMenu menu, int menuSlot, int button) {
        mc.gameMode.handleContainerInput(menu.containerId, menuSlot, button, ContainerInput.SWAP, player);
    }

    private static int findOtherTotem(InventoryMenu menu) {
        for (int i = 9; i < HOTBAR9_MENU_SLOT; i++) {
            if (isTotem(menu.getSlot(i).getItem())) return i;
        }
        return -1;
    }

    private static boolean isTotem(ItemStack stack) {
        return stack.is(Items.TOTEM_OF_UNDYING);
    }
}
