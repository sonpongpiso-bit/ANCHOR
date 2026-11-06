package com.utilmod.crystal;

import com.google.gson.JsonObject;
import com.utilmod.anchor.HotbarAnimation;
import com.utilmod.mixin.MinecraftAccessor;
import com.utilmod.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * Crystal.
 *
 * While right-click is held (and an end crystal was held when it started), every tick:
 *  - break the crystal ONLY if the crosshair is on it,
 *  - place obsidian at the remembered target if it is missing (500 ms cooldown, only when actually needed),
 *  - place an end crystal ONLY if the crosshair is on the target obsidian and no crystal is there yet,
 *  - always end the tick holding the end crystal.
 * A crystal that is already present (crosshair on it) is broken first, without placing another one.
 * All actions use the vanilla paths (useItemOn / attack), so the server sees normal packets.
 * Every real slot switch is recorded for the hotbar display, in order.
 */
public final class CrystalModule extends Module {
    public static final String ID = "crystal";

    private static final int STALE_ATTACK_TICKS = 2;   // don't re-hit a crystal that is already dying
    private static final double PLACE_RANGE_SQR = 5.0 * 5.0;

    // ---- config ----
    private long obsidianCooldownMs = 500;
    private boolean swingHand = true;
    private boolean hotbarAnimation = true;

    // ---- state ----
    private boolean armed;
    private int armedSlot = -1;
    private BlockPos targetPos;          // where the obsidian is / should be
    private BlockHitResult supportHit;   // real crosshair hit used to place obsidian there (null for existing obsidian)
    private int tickCounter;
    private int lastCrystalPlaceTick = -100;
    private int lastAttackedId = -1;
    private int lastAttackTick = -100;
    private int placeSeenTicks = 2;      // measured delay between placing a crystal and seeing it (adaptive)
    private int pendingPlaceTick = -1;
    // Deliberately NOT reset on release/target change: the cooldown only measures real obsidian attempts.
    private long lastObsidianNanos = System.nanoTime() - 3_600_000_000_000L;

    public CrystalModule() {
        super(ID, "Crystal",
                "Obsidian -> end crystal -> break, repeated while holding right-click with a crystal.", true);
    }

    @Override
    protected void onDisable() {
        release();
    }

    @Override
    public void loadConfig(JsonObject section) {
        if (section.has("obsidianCooldownMs") && section.get("obsidianCooldownMs").isJsonPrimitive()) {
            obsidianCooldownMs = Math.max(0L, section.get("obsidianCooldownMs").getAsLong());
        }
        if (section.has("swingHand") && section.get("swingHand").isJsonPrimitive()) {
            swingHand = section.get("swingHand").getAsBoolean();
        }
        if (section.has("hotbarAnimation") && section.get("hotbarAnimation").isJsonPrimitive()) {
            hotbarAnimation = section.get("hotbarAnimation").getAsBoolean();
        }
    }

    @Override
    public void saveConfig(JsonObject section) {
        section.addProperty("obsidianCooldownMs", obsidianCooldownMs);
        section.addProperty("swingHand", swingHand);
        section.addProperty("hotbarAnimation", hotbarAnimation);
    }

    @Override
    public void onTick(Minecraft mc) {
        tickCounter++;

        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null || mc.screen != null
                || !mc.options.keyUse.isDown()) {
            release(); // right-click released: stop immediately
            return;
        }

        Inventory inventory = player.getInventory();
        if (player.getMainHandItem().is(Items.END_CRYSTAL)) {
            armed = true;
            armedSlot = inventory.getSelectedSlot();
        }
        if (!armed) return;

        int crystalSlot = resolveCrystalSlot(inventory);
        if (crystalSlot < 0) {
            release();
            return;
        }
        int obsidianSlot = findInHotbar(inventory, Items.OBSIDIAN);

        BlockHitResult blockHit = mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
        EntityHitResult entityHit = mc.hitResult instanceof EntityHitResult e ? e : null;

        updateTarget(player, level, inventory, blockHit, entityHit, obsidianSlot);
        if (targetPos == null) return; // nothing to work on yet: leave vanilla behaviour alone

        suppressVanillaUse(mc);
        runSequence(mc, player, level, inventory, crystalSlot, obsidianSlot, blockHit, entityHit);

        // Always finish the tick holding the end crystal (covers failed/skipped obsidian attempts).
        selectSlot(inventory, crystalSlot);
    }

    /** Remembers what we are working on. Only ever updated from what the crosshair is really on. */
    private void updateTarget(LocalPlayer player, ClientLevel level, Inventory inventory,
                              BlockHitResult hit, EntityHitResult entityHit, int obsidianSlot) {
        // A crystal that is already there (crosshair on it): target the block below it, so it gets broken.
        if (entityHit != null && entityHit.getEntity() instanceof EndCrystal aimedCrystal) {
            BlockPos below = aimedCrystal.blockPosition().below();
            if (!below.equals(targetPos)) {
                targetPos = below.immutable();
                supportHit = null;
            }
            return;
        }
        if (hit == null) return;

        BlockPos hitPos = hit.getBlockPos();
        if (level.getBlockState(hitPos).is(Blocks.OBSIDIAN)) {
            // Existing obsidian: use it directly (never place over it).
            if (!hitPos.equals(targetPos)) {
                targetPos = hitPos.immutable();
                supportHit = null;
            }
            return;
        }
        if (obsidianSlot < 0) return;

        BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND,
                inventory.getItem(obsidianSlot), hit);
        if (!context.canPlace()) return;
        targetPos = context.getClickedPos().immutable();
        supportHit = hit;
    }

    private void runSequence(Minecraft mc, LocalPlayer player, ClientLevel level, Inventory inventory,
                             int crystalSlot, int obsidianSlot, BlockHitResult blockHit, EntityHitResult entityHit) {
        BlockPos obsidianPos = targetPos;
        BlockPos above = obsidianPos.above();

        // Adaptive: measure how long a placed crystal takes to show up on this client.
        if (pendingPlaceTick >= 0 && tickCounter - pendingPlaceTick > 6) pendingPlaceTick = -1;
        if (pendingPlaceTick >= 0 && findCrystal(level, above) != null) {
            placeSeenTicks = Math.max(1, Math.min(4, tickCounter - pendingPlaceTick));
            pendingPlaceTick = -1;
        }

        // 1) Break: only when the crosshair is actually on the crystal sitting on our obsidian.
        if (entityHit != null && entityHit.getEntity() instanceof EndCrystal aimed
                && aimed.blockPosition().equals(above)) {
            boolean stale = aimed.getId() == lastAttackedId && tickCounter - lastAttackTick < STALE_ATTACK_TICKS;
            if (!stale) {
                mc.gameMode.attack(player, aimed);
                if (swingHand) player.swing(InteractionHand.MAIN_HAND);
                lastAttackedId = aimed.getId();
                lastAttackTick = tickCounter;
            }
        }

        // 2) Obsidian: only when it is actually missing, and never more often than the cooldown.
        boolean obsidianPresent = level.getBlockState(obsidianPos).is(Blocks.OBSIDIAN);
        if (!obsidianPresent && supportHit != null && obsidianSlot >= 0 && cooldownElapsed()
                && player.getEyePosition().distanceToSqr(supportHit.getLocation()) <= PLACE_RANGE_SQR) {
            lastObsidianNanos = System.nanoTime();
            useOnBlock(mc, player, inventory, obsidianSlot, supportHit);
            obsidianPresent = level.getBlockState(obsidianPos).is(Blocks.OBSIDIAN);
        }
        if (!obsidianPresent) return; // keep retrying on later ticks (after the cooldown)

        // 3) Crystal: only when the crosshair is on the target obsidian and no crystal is there yet.
        if (blockHit == null || !blockHit.getBlockPos().equals(obsidianPos)) return;
        if (!level.getBlockState(above).isAir()) return;
        if (findCrystal(level, above) != null) return;
        if (tickCounter - lastCrystalPlaceTick < placeSeenTicks) return;

        useOnBlock(mc, player, inventory, crystalSlot, blockHit);
        lastCrystalPlaceTick = tickCounter;
        pendingPlaceTick = tickCounter;
    }

    /** Selects the slot locally and right-clicks through the vanilla path (slot sync + use-item-on packets). */
    private void useOnBlock(Minecraft mc, LocalPlayer player, Inventory inventory, int slot, BlockHitResult hit) {
        selectSlot(inventory, slot);
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (swingHand && result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** The single place where this module changes the real slot; every change is recorded for the display. */
    private void selectSlot(Inventory inventory, int slot) {
        int previous = inventory.getSelectedSlot();
        if (previous == slot) return;
        inventory.setSelectedSlot(slot);
        if (hotbarAnimation) {
            HotbarAnimation.recordSwitch(previous, slot);
        }
    }

    private void suppressVanillaUse(Minecraft mc) {
        while (mc.options.keyUse.consumeClick()) {
            // drain queued clicks so vanilla does not also place a crystal
        }
        ((MinecraftAccessor) mc).utilmod$setRightClickDelay(4);
    }

    private static EndCrystal findCrystal(ClientLevel level, BlockPos above) {
        List<EndCrystal> crystals = level.getEntitiesOfClass(EndCrystal.class, new AABB(above));
        for (EndCrystal c : crystals) {
            if (c.blockPosition().equals(above)) return c;
        }
        return null;
    }

    private boolean cooldownElapsed() {
        return System.nanoTime() - lastObsidianNanos >= obsidianCooldownMs * 1_000_000L;
    }

    private int resolveCrystalSlot(Inventory inventory) {
        if (armedSlot >= 0 && inventory.getItem(armedSlot).is(Items.END_CRYSTAL)) return armedSlot;
        armedSlot = findInHotbar(inventory, Items.END_CRYSTAL);
        return armedSlot;
    }

    private static int findInHotbar(Inventory inventory, Item item) {
        for (int i = 0; i < 9; i++) {
            if (inventory.getItem(i).is(item)) return i;
        }
        return -1;
    }

    private void release() {
        armed = false;
        armedSlot = -1;
        targetPos = null;
        supportHit = null;
        pendingPlaceTick = -1;
    }
}
