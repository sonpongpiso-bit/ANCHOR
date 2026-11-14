package com.utilmod.anchor;

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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

/**
 * Anchor Macro.
 *
 * One sequence per right-click activation (press -> release):
 *  - Looking at an existing respawn anchor (any held item): [charge if uncharged] -> detonate (slot 9).
 *  - Holding an anchor and looking at a block: place -> charge -> detonate (slot 9).
 * Actions are spaced by a configurable number of ticks (default 1 = 50 ms). Holding right-click
 * never starts a second sequence; releasing and pressing again allows exactly one new one.
 * All actions go through the vanilla interaction path (MultiPlayerGameMode#useItemOn).
 */
public final class AnchorMacroModule extends Module {
    public static final String ID = "anchor_macro";

    private enum Step { PLACE, CHARGE, DETONATE }

    // ---- config ----
    private int detonateSlot = 8;           // hotbar index (config value 9 = index 8)
    private boolean hotbarAnimation = true; // visual-only display of every real slot switch
    private boolean swingHand = true;       // vanilla sends an arm swing per successful interaction
    private int placeToChargeTicks = 2;     // ticks between placing and charging (1 = 50 ms)
    private int chargeToDetonateTicks = 2;  // ticks between charging and detonating

    // ---- state ----
    private boolean triggered;              // a sequence was started during this right-click hold
    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private int nextStepTick;
    private int tickCounter;
    private int anchorSlot = -1;            // PLACE only
    private BlockHitResult placeHit;        // PLACE only
    private BlockPos anchorPos;
    private BlockHitResult anchorHit;
    private boolean requireAim;             // existing-anchor flow: abort if the crosshair leaves the anchor

    public AnchorMacroModule() {
        super(ID, "Anchor Macro",
                "Place, charge and detonate a respawn anchor once per right-click.", true);
    }

    @Override
    protected void onDisable() {
        reset();
    }

    @Override
    public void loadConfig(JsonObject section) {
        if (section.has("detonateSlot") && section.get("detonateSlot").isJsonPrimitive()) {
            int slot = section.get("detonateSlot").getAsInt();
            detonateSlot = Math.max(1, Math.min(9, slot)) - 1;
        }
        if (section.has("hotbarAnimation") && section.get("hotbarAnimation").isJsonPrimitive()) {
            hotbarAnimation = section.get("hotbarAnimation").getAsBoolean();
        }
        if (section.has("swingHand") && section.get("swingHand").isJsonPrimitive()) {
            swingHand = section.get("swingHand").getAsBoolean();
        }
        if (section.has("placeToChargeTicks") && section.get("placeToChargeTicks").isJsonPrimitive()) {
            placeToChargeTicks = Math.max(1, Math.min(10, section.get("placeToChargeTicks").getAsInt()));
        }
        if (section.has("chargeToDetonateTicks") && section.get("chargeToDetonateTicks").isJsonPrimitive()) {
            chargeToDetonateTicks = Math.max(1, Math.min(10, section.get("chargeToDetonateTicks").getAsInt()));
        }
    }

    @Override
    public void saveConfig(JsonObject section) {
        section.addProperty("detonateSlot", detonateSlot + 1);
        section.addProperty("hotbarAnimation", hotbarAnimation);
        section.addProperty("swingHand", swingHand);
        section.addProperty("placeToChargeTicks", placeToChargeTicks);
        section.addProperty("chargeToDetonateTicks", chargeToDetonateTicks);
    }

    @Override
    public void onTick(Minecraft mc) {
        tickCounter++;

        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null || mc.screen != null
                || !mc.options.keyUse.isDown()) {
            reset(); // right-click released (or no longer valid): stop immediately, allow a new activation
            return;
        }

        Inventory inventory = player.getInventory();

        if (triggered) {
            // This hold already used its one sequence: keep vanilla from placing/clicking more,
            // and keep running the remaining steps of the current sequence.
            suppressVanillaUse(mc);
            if (!steps.isEmpty() && tickCounter >= nextStepTick) {
                runNextStep(mc, player, level, inventory);
            }
            return;
        }

        // Sneaking turns block interactions into item placement, which would break charging.
        if (player.isShiftKeyDown()) return;

        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);

        if (state.is(Blocks.RESPAWN_ANCHOR)) {
            // Existing anchors always take priority over placement.
            startExistingAnchor(mc, player, level, inventory, state, pos, hit);
        } else if (player.getMainHandItem().is(Items.RESPAWN_ANCHOR)) {
            startPlacement(mc, player, level, inventory, hit);
        }
    }

    private void startExistingAnchor(Minecraft mc, LocalPlayer player, ClientLevel level, Inventory inventory,
                                     BlockState state, BlockPos pos, BlockHitResult hit) {
        boolean needsCharge = state.getValue(RespawnAnchorBlock.CHARGE) == 0;
        if (needsCharge && findInHotbar(inventory, Items.GLOWSTONE) < 0) return; // cannot charge: leave vanilla alone
        if (!detonatorUsable(inventory)) return;

        steps.clear();
        if (needsCharge) steps.add(Step.CHARGE);
        steps.add(Step.DETONATE);
        anchorPos = pos.immutable();
        anchorHit = hit;
        placeHit = null;
        anchorSlot = -1;
        requireAim = true;
        begin(mc, player, level, inventory);
    }

    private void startPlacement(Minecraft mc, LocalPlayer player, ClientLevel level, Inventory inventory,
                                BlockHitResult hit) {
        int slot = inventory.getSelectedSlot();
        if (findInHotbar(inventory, Items.GLOWSTONE) < 0 || !detonatorUsable(inventory)) return;

        ItemStack anchorStack = inventory.getItem(slot);
        BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, anchorStack, hit);
        if (!context.canPlace()) return;
        BlockPos placedPos = context.getClickedPos();

        // Click the anchor on the same side we place against, like a real player would.
        Direction face = hit.getDirection();
        Vec3 location = Vec3.atCenterOf(placedPos)
                .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);

        steps.clear();
        steps.add(Step.PLACE);
        steps.add(Step.CHARGE);
        steps.add(Step.DETONATE);
        anchorSlot = slot;
        placeHit = hit;
        anchorPos = placedPos.immutable();
        anchorHit = new BlockHitResult(location, face, anchorPos, false);
        requireAim = false;
        begin(mc, player, level, inventory);
    }

    private void begin(Minecraft mc, LocalPlayer player, ClientLevel level, Inventory inventory) {
        triggered = true; // exactly one sequence for this right-click hold
        suppressVanillaUse(mc);
        runNextStep(mc, player, level, inventory);
    }

    private void runNextStep(Minecraft mc, LocalPlayer player, ClientLevel level, Inventory inventory) {
        Step step = steps.pollFirst();
        if (step == null) return;

        // Validity checks before every action; any failure ends this sequence (no retry within the hold).
        if (player.isShiftKeyDown()) { steps.clear(); return; }
        if (step != Step.PLACE) {
            if (!level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) { steps.clear(); return; }
            if (requireAim && !(mc.hitResult instanceof BlockHitResult aim
                    && aim.getType() == HitResult.Type.BLOCK && aim.getBlockPos().equals(anchorPos))) {
                steps.clear();
                return;
            }
        }

        boolean ok = true;
        switch (step) {
            case PLACE -> {
                if (!inventory.getItem(anchorSlot).is(Items.RESPAWN_ANCHOR)) {
                    ok = false;
                } else {
                    useOnBlock(mc, player, inventory, anchorSlot, placeHit);
                    // Local rejection (e.g. entity in the way): do not send any further clicks.
                    ok = level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR);
                }
            }
            case CHARGE -> {
                int glowstoneSlot = findInHotbar(inventory, Items.GLOWSTONE);
                if (glowstoneSlot < 0) {
                    ok = false;
                } else {
                    useOnBlock(mc, player, inventory, glowstoneSlot, anchorHit);
                }
            }
            case DETONATE -> {
                if (!detonatorUsable(inventory)) {
                    ok = false;
                } else {
                    useOnBlock(mc, player, inventory, detonateSlot, anchorHit);
                }
            }
        }

        if (!ok) {
            steps.clear();
            return;
        }
        nextStepTick = tickCounter + (step == Step.PLACE ? placeToChargeTicks : chargeToDetonateTicks);
    }

    /**
     * Selects the slot locally and right-clicks the block through the vanilla path.
     * MultiPlayerGameMode#useItemOn syncs the selected slot (set-carried-item packet) first and then
     * sends the use-item-on packet, so the server sees the exact vanilla sequence.
     */
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

    /** Stops vanilla from also handling this right-click (which would double place/click). */
    private void suppressVanillaUse(Minecraft mc) {
        while (mc.options.keyUse.consumeClick()) {
            // drain queued clicks
        }
        ((MinecraftAccessor) mc).utilmod$setRightClickDelay(4);
    }

    private boolean detonatorUsable(Inventory inventory) {
        // Right-clicking with glowstone would charge instead of detonating.
        return !inventory.getItem(detonateSlot).is(Items.GLOWSTONE);
    }

    private static int findInHotbar(Inventory inventory, Item item) {
        for (int i = 0; i < 9; i++) {
            if (inventory.getItem(i).is(item)) return i;
        }
        return -1;
    }

    private void reset() {
        triggered = false;
        steps.clear();
        anchorSlot = -1;
        placeHit = null;
        anchorPos = null;
        anchorHit = null;
        requireAim = false;
    }
}
