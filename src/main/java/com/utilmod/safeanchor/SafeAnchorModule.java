package com.utilmod.safeanchor;

import com.google.gson.JsonObject;
import com.utilmod.anchor.HotbarAnimation;
import com.utilmod.module.Module;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;

/**
 * Safe Anchor.
 *
 * One key press (default: the ~ key) runs exactly one sequence, with 3 ticks (150 ms) between actions:
 *   place anchor -> charge (glowstone) -> shield glowstone on the ground behind the anchor (skipped if
 *   there is no valid spot) -> detonate from hotbar slot 9.
 * A failed or skipped shield never cancels the detonation. No sneaking, no custom packets: every action
 * goes through the vanilla MultiPlayerGameMode#useItemOn path.
 */
public final class SafeAnchorModule extends Module {
    public static final String ID = "safe_anchor";

    private enum Step { PLACE, CHARGE, SHIELD, DETONATE }

    // ---- config ----
    private int keyCode = GLFW.GLFW_KEY_GRAVE_ACCENT; // the ~ key
    private int stepDelayTicks = 2;                    // 3 ticks = 150 ms between actions
    private int detonateSlot = 8;                      // hotbar index (config value 9 = index 8)
    private boolean hotbarAnimation = true;
    private boolean swingHand = true;

    // ---- key state (edge-triggered: holding the key never repeats) ----
    private boolean keyDown;
    private boolean pressLatched;

    // ---- sequence state ----
    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private int tickCounter;
    private int nextStepTick;
    private int anchorSlot = -1;
    private BlockHitResult placeHit;
    private BlockPos anchorPos;
    private BlockHitResult anchorHit;

    public SafeAnchorModule() {
        super(ID, "Safe Anchor",
                "Press a key to place, charge, shield and detonate a respawn anchor.", true);
        // Per-frame key poll so a quick tap is never missed (draws nothing).
        HudElementRegistry.attachElementBefore(VanillaHudElements.HOTBAR,
                Identifier.fromNamespaceAndPath("utilmod", "safe_anchor_key_poll"),
                (graphics, deltaTracker) -> pollKey());
    }

    @Override
    protected void onDisable() {
        reset();
        pressLatched = false;
    }

    @Override
    public void loadConfig(JsonObject section) {
        if (section.has("keyCode") && section.get("keyCode").isJsonPrimitive()) {
            keyCode = section.get("keyCode").getAsInt();
        }
        if (section.has("stepDelayTicks") && section.get("stepDelayTicks").isJsonPrimitive()) {
            stepDelayTicks = Math.max(1, Math.min(20, section.get("stepDelayTicks").getAsInt()));
        }
        if (section.has("detonateSlot") && section.get("detonateSlot").isJsonPrimitive()) {
            detonateSlot = Math.max(1, Math.min(9, section.get("detonateSlot").getAsInt())) - 1;
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
        section.addProperty("keyCode", keyCode);
        section.addProperty("stepDelayTicks", stepDelayTicks);
        section.addProperty("detonateSlot", detonateSlot + 1);
        section.addProperty("hotbarAnimation", hotbarAnimation);
        section.addProperty("swingHand", swingHand);
    }

    /** Edge detection on the configured key. Only counts presses while enabled and no screen (e.g. chat) is open. */
    private void pollKey() {
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) return;
        boolean down = GLFW.glfwGetKey(window, keyCode) != GLFW.GLFW_RELEASE;
        if (down && !keyDown) {
            Minecraft mc = Minecraft.getInstance();
            if (isEnabled() && mc.screen == null && mc.player != null) {
                pressLatched = true;
            }
        }
        keyDown = down;
    }

    @Override
    public void onTick(Minecraft mc) {
        tickCounter++;
        pollKey();

        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null || mc.screen != null) {
            reset();
            pressLatched = false;
            return;
        }

        Inventory inventory = player.getInventory();

        if (!steps.isEmpty()) {
            pressLatched = false; // a sequence is running: extra presses are ignored
            if (tickCounter >= nextStepTick) {
                runSteps(mc, player, level, inventory);
            }
            return;
        }

        if (!pressLatched) return;
        pressLatched = false;
        start(mc, player, level, inventory);
    }

    private void start(Minecraft mc, LocalPlayer player, ClientLevel level, Inventory inventory) {
        // Sneaking would turn charging into block placement; never use sneak-based placement.
        if (player.isShiftKeyDown()) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        if (findInHotbar(inventory, Items.GLOWSTONE) < 0 || !detonatorUsable(inventory)) return;

        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);

        steps.clear();
        placeHit = null;
        anchorSlot = -1;

        if (state.is(Blocks.RESPAWN_ANCHOR)) {
            // Already an anchor under the crosshair: never place another one, just continue the sequence.
            anchorPos = pos.immutable();
            anchorHit = hit;
            if (state.getValue(RespawnAnchorBlock.CHARGE) == 0) steps.add(Step.CHARGE);
        } else {
            int slot = findInHotbar(inventory, Items.RESPAWN_ANCHOR);
            if (slot < 0) return;
            BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND,
                    inventory.getItem(slot), hit);
            if (!context.canPlace()) return;
            BlockPos placedPos = context.getClickedPos();

            Direction face = hit.getDirection();
            Vec3 location = Vec3.atCenterOf(placedPos)
                    .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);

            anchorSlot = slot;
            placeHit = hit;
            anchorPos = placedPos.immutable();
            anchorHit = new BlockHitResult(location, face, anchorPos, false);
            steps.add(Step.PLACE);
            steps.add(Step.CHARGE);
        }
        steps.add(Step.SHIELD);
        steps.add(Step.DETONATE);

        runSteps(mc, player, level, inventory); // first action right away, the rest every stepDelayTicks
    }

    /** Runs exactly one real action; a skipped shield falls through to the detonation without extra delay. */
    private void runSteps(Minecraft mc, LocalPlayer player, ClientLevel level, Inventory inventory) {
        while (true) {
            Step step = steps.pollFirst();
            if (step == null) return;

            if (player.isShiftKeyDown()) { steps.clear(); return; }
            if (step != Step.PLACE && !level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) {
                steps.clear();
                return;
            }

            switch (step) {
                case PLACE -> {
                    if (!inventory.getItem(anchorSlot).is(Items.RESPAWN_ANCHOR)) { steps.clear(); return; }
                    useOnBlock(mc, player, inventory, anchorSlot, placeHit);
                    if (!level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) { // rejected locally
                        steps.clear();
                        return;
                    }
                }
                case CHARGE -> {
                    int glowstoneSlot = findInHotbar(inventory, Items.GLOWSTONE);
                    if (glowstoneSlot < 0) { steps.clear(); return; }
                    useOnBlock(mc, player, inventory, glowstoneSlot, anchorHit);
                }
                case SHIELD -> {
                    BlockHitResult shield = findShield(player, level);
                    int glowstoneSlot = findInHotbar(inventory, Items.GLOWSTONE);
                    if (shield == null || glowstoneSlot < 0) continue; // skip: go straight on to the detonation
                    useOnBlock(mc, player, inventory, glowstoneSlot, shield);
                }
                case DETONATE -> {
                    if (!detonatorUsable(inventory)) { steps.clear(); return; }
                    useOnBlock(mc, player, inventory, detonateSlot, anchorHit);
                }
            }
            nextStepTick = tickCounter + stepDelayTicks;
            return;
        }
    }

    /**
     * Ground block on the player's side of the anchor (same height as the anchor), clicked on the top face
     * of the block underneath, so the glowstone is placed on the ground, never floating or on a side face.
     */
    private BlockHitResult findShield(LocalPlayer player, ClientLevel level) {
        Direction toward = Direction.getApproximateNearest(
                player.getX() - (anchorPos.getX() + 0.5), 0.0,
                player.getZ() - (anchorPos.getZ() + 0.5));
        BlockPos shieldPos = anchorPos.relative(toward);
        BlockPos ground = shieldPos.below();

        if (!level.getBlockState(shieldPos).canBeReplaced()) return null;
        if (!level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) return null;
        if (!level.getEntities((Entity) null, new AABB(shieldPos)).isEmpty()) return null; // someone is standing there

        Vec3 location = new Vec3(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5);
        if (player.getEyePosition().distanceToSqr(location) > 5.0 * 5.0) return null;
        return new BlockHitResult(location, Direction.UP, ground, false);
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
        steps.clear();
        anchorSlot = -1;
        placeHit = null;
        anchorPos = null;
        anchorHit = null;
    }
}
