package com.utilmod.anchor;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * Purely visual hotbar scroll. The real selected slot changes instantly (packets are never delayed).
 * While rendering the vanilla hotbar only, the highlighted slot is temporarily swapped to a time-based
 * "scrolling" slot and restored right after, so nothing else ever sees the fake value.
 */
public final class HotbarAnimation {
    /** Time per slot step, similar to fast manual scrolling. */
    private static final long STEP_NANOS = 16_000_000L;

    private static int visual = -1;
    private static int target = -1;
    private static long lastStep;
    private static int swappedReal = -1;

    private HotbarAnimation() {}

    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.HOTBAR,
                Identifier.fromNamespaceAndPath("utilmod", "hotbar_anim_begin"),
                (graphics, deltaTracker) -> beforeHotbar());
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR,
                Identifier.fromNamespaceAndPath("utilmod", "hotbar_anim_end"),
                (graphics, deltaTracker) -> afterHotbar());
    }

    /** Starts (or retargets) the visual scroll. Costs nothing and never touches packets. */
    public static void begin(int fromSlot, int toSlot) {
        if (fromSlot == toSlot) return;
        if (visual == -1) {
            visual = fromSlot;
            lastStep = System.nanoTime();
        }
        target = toSlot;
    }

    private static void beforeHotbar() {
        if (visual == -1) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            visual = -1;
            return;
        }
        long now = System.nanoTime();
        int guard = 0;
        while (visual != target && now - lastStep >= STEP_NANOS && guard++ < 9) {
            visual += Integer.compare(target, visual);
            lastStep += STEP_NANOS;
        }
        if (visual == target) {
            visual = -1; // finished: vanilla renders the real slot, which is the same one
            return;
        }
        Inventory inventory = mc.player.getInventory();
        swappedReal = inventory.getSelectedSlot();
        inventory.setSelectedSlot(visual);
    }

    private static void afterHotbar() {
        if (swappedReal == -1) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.getInventory().setSelectedSlot(swappedReal);
        }
        swappedReal = -1;
    }
}
