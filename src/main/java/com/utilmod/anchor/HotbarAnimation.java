package com.utilmod.anchor;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayDeque;

/**
 * Purely visual hotbar display. Real slot changes happen instantly and packets are never delayed.
 * While rendering the vanilla hotbar only, the highlighted slot is temporarily swapped and restored
 * right after, so nothing else ever sees the displayed value.
 *
 * Two sources:
 *  - recordSwitch(slot): replays the exact sequence of real slot switches, one per rendered frame.
 *  - begin(from, to): legacy time-based scroll (used by Anchor Macro).
 */
public final class HotbarAnimation {
    /** Time per slot step for the legacy scroll. */
    private static final long STEP_NANOS = 16_000_000L;
    /** If the replay ever falls behind, oldest entries are dropped so the display never lags. */
    private static final int MAX_BACKLOG = 4;
    private static final int MAX_QUEUE = 32;

    private static final ArrayDeque<Integer> queue = new ArrayDeque<>();

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

    /** Records one real slot switch, in order. Costs nothing and never touches packets. */
    public static void recordSwitch(int slot) {
        if (queue.size() >= MAX_QUEUE) queue.pollFirst();
        queue.addLast(slot);
    }

    /** Legacy: starts (or retargets) a time-based scroll. */
    public static void begin(int fromSlot, int toSlot) {
        if (fromSlot == toSlot) return;
        if (visual == -1) {
            visual = fromSlot;
            lastStep = System.nanoTime();
        }
        target = toSlot;
    }

    private static void beforeHotbar() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            visual = -1;
            queue.clear();
            return;
        }

        // Exact replay of real switches, one per frame, always ending on the real slot.
        if (!queue.isEmpty()) {
            while (queue.size() > MAX_BACKLOG) queue.pollFirst();
            show(mc, queue.pollFirst());
            return;
        }

        if (visual == -1) return;
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
        show(mc, visual);
    }

    private static void show(Minecraft mc, int slot) {
        Inventory inventory = mc.player.getInventory();
        swappedReal = inventory.getSelectedSlot();
        inventory.setSelectedSlot(slot);
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
