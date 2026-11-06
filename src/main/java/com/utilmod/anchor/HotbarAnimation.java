package com.utilmod.anchor;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayDeque;

/**
 * Purely visual hotbar display. Real slot changes happen instantly and packets are never delayed.
 * Every real switch (from -> to) is queued and replayed as manual-scroll steps through the slots in
 * between. The highlight is only swapped while the vanilla hotbar is drawn, then restored immediately.
 */
public final class HotbarAnimation {
    /** Time per slot step, like a quick wheel scroll. */
    private static final long STEP_NANOS = 16_000_000L;
    /** Above this many pending steps the replay speeds up (never skips) so it stays in sync. */
    private static final int FAST_BACKLOG = 6;
    private static final int MAX_QUEUE = 64;

    private static final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private static int visual = -1;   // slot currently displayed, -1 = not animating (vanilla shows the real slot)
    private static int lastEnd = -1;  // slot the queued steps end on
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

    /** Records one real slot switch, in order. Costs nothing and never touches packets. */
    public static void recordSwitch(int fromSlot, int toSlot) {
        if (fromSlot == toSlot) return;
        if (visual == -1) {
            visual = fromSlot;
            lastEnd = fromSlot;
            lastStep = System.nanoTime();
        }
        int forward = Math.floorMod(toSlot - lastEnd, 9);
        int dir = forward <= 4 ? 1 : -1;
        int steps = forward <= 4 ? forward : 9 - forward;
        int s = lastEnd;
        for (int i = 0; i < steps; i++) {
            s = Math.floorMod(s + dir, 9);
            pending.addLast(s);
        }
        lastEnd = toSlot;
        while (pending.size() > MAX_QUEUE) pending.pollFirst();
    }

    /** Kept for compatibility. */
    public static void begin(int fromSlot, int toSlot) {
        recordSwitch(fromSlot, toSlot);
    }

    private static void reset() {
        pending.clear();
        visual = -1;
        lastEnd = -1;
    }

    private static void beforeHotbar() {
        if (visual == -1) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            reset();
            return;
        }

        long now = System.nanoTime();
        int backlog = pending.size();
        long interval = backlog > FAST_BACKLOG
                ? Math.max(2_000_000L, STEP_NANOS * FAST_BACKLOG / backlog)
                : STEP_NANOS;

        boolean advanced = false;
        while (!pending.isEmpty() && now - lastStep >= interval) {
            visual = pending.pollFirst();
            lastStep += interval;
            advanced = true;
        }
        if (pending.isEmpty() && !advanced) {
            reset(); // the final slot was already shown: vanilla now draws the real slot (same one)
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
