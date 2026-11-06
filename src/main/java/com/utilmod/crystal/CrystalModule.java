package com.utilmod.crystal;

import com.google.gson.JsonObject;
import com.utilmod.mixin.MinecraftAccessor;
import com.utilmod.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Crystal.
 *
 * While right-click is held (and an end crystal was held when it started), every tick:
 *  1. break the crystal sitting on the target obsidian (if the client can see one),
 *  2. place obsidian at the target if it is missing (500 ms cooldown, only when actually needed),
 *  3. (re)place an end crystal on the obsidian, always ending the tick holding the crystal.
 * The target is remembered, so obsidian keeps being attempted even if the crosshair leaves it.
 * All actions use the vanilla paths (useItemOn / attack), so the server sees normal packets.
 */
public final class CrystalModule extends Module {
    public static final String ID = "crystal";

    private static final int STALE_ATTACK_TICKS = 4;     // don't re-hit a crystal that is already dying
    private static final int CRYSTAL_RETRY_TICKS = 2;    // wait for a placed crystal to appear before retrying
    private static final double ATTACK_RANGE_SQR = 3.0 * 3.0;
    private static final double PLACE_RANGE_SQR = 5.0 * 5.0;

    // ---- config ----
    private long obsidianCooldownMs = 500;
    private boolean swingHand = true;

    // ---- state ----
    private boolean armed;
    private int armedSlot = -1;
    private BlockPos targetPos;          // where the obsidian is / should be
    private BlockHitResult supportHit;   // how to place obsidian there (null if only an existing block was targeted)
    private int tickCounter;
    private int lastCrystalPlaceTick = -100;
    private int lastAttackedId = -1;
    private int lastAttackTick = -100;
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
    }

    @Override
    public void saveConfig(JsonObject section) {
        section.addProperty("obsidianCooldownMs", obsidianCooldownMs);
        section.addProperty("swingHand", swingHand);
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

        updateTarget(mc, player, level, inventory, obsidianSlot);
        if (targetPos == null) return; // nothing to work on yet: leave vanilla behaviour alone

        suppressVanillaUse(mc);
        runSequence(mc, player, level, inventory, crystalSlot, obsidianSlot);

        // Always finish the tick holding the end crystal (covers failed/skipped obsidian attempts).
        if (inventory.getSelectedSlot() != crystalSlot) {
            inventory.setSelectedSlot(crystalSlot);
        }
    }

    /** Remembers what we are working on, so it keeps going when the crosshair moves away. */
    private void updateTarget(Minecraft mc, LocalPlayer player, ClientLevel level,
                              Inventory inventory, int obsidianSlot) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos hitPos = hit.getBlockPos();
        if (level.getBlockState(hitPos).is(Blocks.OBSIDIAN)) {
            // Existing obsidian: use it directly (never place over it). Keep the support info if same spot.
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

    private void runSequence(Minecraft mc, LocalPlayer player, ClientLevel level,
                             Inventory inventory, int crystalSlot, int obsidianSlot) {
        BlockPos obsidianPos = targetPos;
        BlockPos above = obsidianPos.above();

        // 1) Break the crystal on the obsidian, if the client can see one.
        EndCrystal crystal = findCrystal(level, above);
        boolean attacked = false;
        if (crystal != null) {
            boolean stale = crystal.getId() == lastAttackedId && tickCounter - lastAttackTick < STALE_ATTACK_TICKS;
            if (!stale && crystal.getBoundingBox().distanceToSqr(player.getEyePosition()) <= ATTACK_RANGE_SQR) {
                mc.gameMode.attack(player, crystal);
                if (swingHand) player.swing(InteractionHand.MAIN_HAND);
                lastAttackedId = crystal.getId();
                lastAttackTick = tickCounter;
                attacked = true;
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

        // 3) Crystal on top of the obsidian.
        if (!level.getBlockState(above).isAir()) return;
        boolean canPlace = attacked
                || (crystal == null && tickCounter - lastCrystalPlaceTick >= CRYSTAL_RETRY_TICKS);
        if (!canPlace) return;

        BlockHitResult top = new BlockHitResult(
                new Vec3(obsidianPos.getX() + 0.5, obsidianPos.getY() + 1.0, obsidianPos.getZ() + 0.5),
                Direction.UP, obsidianPos, false);
        useOnBlock(mc, player, inventory, crystalSlot, top);
        lastCrystalPlaceTick = tickCounter;
    }

    /** Selects the slot locally and right-clicks through the vanilla path (slot sync + use-item-on packets). */
    private void useOnBlock(Minecraft mc, LocalPlayer player, Inventory inventory, int slot, BlockHitResult hit) {
        if (inventory.getSelectedSlot() != slot) {
            inventory.setSelectedSlot(slot);
        }
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (swingHand && result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
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
    }
}
