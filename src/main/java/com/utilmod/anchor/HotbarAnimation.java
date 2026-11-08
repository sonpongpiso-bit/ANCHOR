package com.utilmod.anchor;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayDeque;

/**
 * Purely visual hotbar display. Real slot changes happen instantly and packets are never delayed.
 *
 * Every real switch (from -> to) recorded by a module is replayed in order, one displayed slot per step,
 * jumping straight to the slots the module actually used. No in-between slots are ever shown.
 * The highlight is only swapped while the vanilla hotbar is drawn, then restored immediately, so the
 * displayed slot always ends on the real selected slot.
 */
public final class HotbarAnimation {
    /** Minimum time each displayed slot stays visible (about one frame at 50 fps). */
    private static final long STEP_NANOS = 20_000_000L;
    /** Above this many pending entries the replay speeds up (never skips) so it stays in sync. */
    private static final int FAST_BACKLOG = 6;
    private static final int MAX_QUEUE = 64;

    private static final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private static boolean active;
    private static int visual = -1;   // slot currently displayed
    private static int lastEnd = -1;  // slot the queued entries end on
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
        if (!active) {
            active = true;
            lastEnd = -1;
            visual = -1;
            pending.clear();
            lastStep = System.nanoTime() - STEP_NANOS; // first entry shows on the very next frame
        }
        if (lastEnd != fromSlot) {
            pending.addLast(fromSlot); // where the highlight was before this switch
        }
        pending.addLast(toSlot);
        lastEnd = toSlot;
        while (pending.size() > MAX_QUEUE) pending.pollFirst();
    }

    /** Kept for compatibility. */
    public static void begin(int fromSlot, int toSlot) {
        recordSwitch(fromSlot, toSlot);
    }

    private static void reset() {
        pending.clear();
        active = false;
        visual = -1;
        lastEnd = -1;
    }

    private static void beforeHotbar() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            reset();
            return;
        }
        if (pending.isEmpty()) {
            reset(); // everything was shown; the last entry is the real slot, so vanilla draws the same one
            return;
        }

        int backlog = pending.size();
        long interval = backlog > FAST_BACKLOG
                ? Math.max(4_000_000L, STEP_NANOS * FAST_BACKLOG / backlog)
                : STEP_NANOS;

        long now = System.nanoTime();
        if (visual == -1 || now - lastStep >= interval) {
            visual = pending.pollFirst(); // exactly one entry per step, never skipped
            lastStep = now;
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
