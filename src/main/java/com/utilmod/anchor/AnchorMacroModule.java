package com.utilmod.anchor;

import com.google.gson.JsonObject;
import com.utilmod.mixin.MinecraftAccessor;
import com.utilmod.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
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

/**
 * Anchor Macro.
 *
 * Runs only while right-click is held:
 *  - Looking at an existing respawn anchor (any held item): charge (only if uncharged) -> slot 9 -> detonate.
 *  - Otherwise, once an anchor was held while right-click was down: place -> charge -> slot 9 -> detonate.
 * Everything happens in a single tick through the vanilla interaction path
 * (MultiPlayerGameMode#useItemOn), which sends the real held-slot and use-item-on packets.
 */
public final class AnchorMacroModule extends Module {
    public static final String ID = "anchor_macro";

    /** Ticks to ignore a position we just used, so a not-yet-updated client world cannot cause duplicate clicks. */
    private static final int STALE_TICKS = 5;

    // ---- config ----
    private int detonateSlot = 8;           // hotbar index (config value 9 = index 8)
    private boolean hotbarAnimation = true; // visual-only scroll to the detonate slot
    private boolean swingHand = true;       // vanilla sends an arm swing per successful interaction

    // ---- state ----
    private boolean armed;
    private int armedSlot = -1;
    private BlockPos lastPos;
    private int lastTick;
    private int tickCounter;

    public AnchorMacroModule() {
        super(ID, "Anchor Macro",
                "Place, charge and detonate respawn anchors while holding right-click.", true);
    }

    @Override
    protected void onDisable() {
        disarm();
        lastPos = null;
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
    }

    @Override
    public void saveConfig(JsonObject section) {
        section.addProperty("detonateSlot", detonateSlot + 1);
        section.addProperty("hotbarAnimation", hotbarAnimation);
        section.addProperty("swingHand", swingHand);
    }

    @Override
    public void onTick(Minecraft mc) {
        tickCounter++;

        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        MultiPlayerGameMode gameMode = mc.gameMode;
        if (player == null || level == null || gameMode == null || mc.screen != null
                || !mc.options.keyUse.isDown()) {
            disarm(); // right-click released (or no longer valid): stop immediately
            return;
        }

        Inventory inventory = player.getInventory();
        if (player.getMainHandItem().is(Items.RESPAWN_ANCHOR)) {
            armed = true;
            armedSlot = inventory.getSelectedSlot();
        }

        // Sneaking turns block interactions into item placement, which would break charging.
        if (player.isShiftKeyDown()) return;

        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);

        if (state.is(Blocks.RESPAWN_ANCHOR)) {
            // Existing anchors always take priority over placement.
            handleExistingAnchor(mc, player, inventory, state, pos, hit);
        } else if (armed) {
            handlePlacement(mc, player, level, inventory, hit);
        }
    }

    private void handleExistingAnchor(Minecraft mc, LocalPlayer player, Inventory inventory,
                                      BlockState state, BlockPos pos, BlockHitResult hit) {
        if (isStale(pos)) {
            suppressVanillaUse(mc);
            return;
        }
        boolean needsCharge = state.getValue(RespawnAnchorBlock.CHARGE) == 0;
        int glowstoneSlot = -1;
        if (needsCharge) {
            glowstoneSlot = findInHotbar(inventory, Items.GLOWSTONE);
            if (glowstoneSlot < 0) return; // cannot charge: leave vanilla behaviour alone
        }
        if (!detonatorUsable(inventory)) return;

        suppressVanillaUse(mc);
        if (needsCharge) {
            useOnBlock(mc, player, inventory, glowstoneSlot, hit);
        }
        useOnBlock(mc, player, inventory, detonateSlot, hit);
        markUsed(pos);
    }

    private void handlePlacement(Minecraft mc, LocalPlayer player, ClientLevel level,
                                 Inventory inventory, BlockHitResult hit) {
        int anchorSlot = resolveAnchorSlot(inventory);
        if (anchorSlot < 0) {
            disarm();
            return;
        }
        int glowstoneSlot = findInHotbar(inventory, Items.GLOWSTONE);
        if (glowstoneSlot < 0 || !detonatorUsable(inventory)) return;

        ItemStack anchorStack = inventory.getItem(anchorSlot);
        BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, anchorStack, hit);
        if (!context.canPlace()) return;
        BlockPos anchorPos = context.getClickedPos();

        suppressVanillaUse(mc);

        useOnBlock(mc, player, inventory, anchorSlot, hit);
        if (!level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) {
            return; // placement was rejected locally (e.g. entity in the way): do not send further clicks
        }

        // Click the anchor on the same side we placed against, like a real player would.
        Direction face = hit.getDirection();
        Vec3 location = Vec3.atCenterOf(anchorPos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        BlockHitResult anchorHit = new BlockHitResult(location, face, anchorPos, false);

        useOnBlock(mc, player, inventory, glowstoneSlot, anchorHit);
        useOnBlock(mc, player, inventory, detonateSlot, anchorHit);
        markUsed(anchorPos);
    }

    /**
     * Selects the slot locally and right-clicks the block through the vanilla path.
     * MultiPlayerGameMode#useItemOn syncs the selected slot (set-carried-item packet) first and then
     * sends the use-item-on packet, so the server sees the exact vanilla sequence.
     */
    private void useOnBlock(Minecraft mc, LocalPlayer player, Inventory inventory, int slot, BlockHitResult hit) {
        int previous = inventory.getSelectedSlot();
        if (previous != slot) {
            inventory.setSelectedSlot(slot);
            if (hotbarAnimation && slot == detonateSlot) {
                HotbarAnimation.begin(previous, slot);
            }
        }
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (swingHand && result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
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

    private int resolveAnchorSlot(Inventory inventory) {
        if (armedSlot >= 0 && inventory.getItem(armedSlot).is(Items.RESPAWN_ANCHOR)) {
            return armedSlot;
        }
        int found = findInHotbar(inventory, Items.RESPAWN_ANCHOR);
        armedSlot = found;
        return found;
    }

    private static int findInHotbar(Inventory inventory, Item item) {
        for (int i = 0; i < 9; i++) {
            if (inventory.getItem(i).is(item)) return i;
        }
        return -1;
    }

    private boolean isStale(BlockPos pos) {
        return lastPos != null && lastPos.equals(pos) && tickCounter - lastTick < STALE_TICKS;
    }

    private void markUsed(BlockPos pos) {
        lastPos = pos.immutable();
        lastTick = tickCounter;
    }

    private void disarm() {
        armed = false;
        armedSlot = -1;
    }
}
