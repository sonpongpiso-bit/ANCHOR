package com.utilmod;

import com.utilmod.anchor.AnchorMacroModule;
import com.utilmod.anchor.HotbarAnimation;
import com.utilmod.crystal.CrystalModule;
import com.utilmod.hovertotem.HoverTotemModule;
import com.utilmod.module.ModuleManager;
import com.utilmod.safeanchor.SafeAnchorModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UtilityMod implements ClientModInitializer {
    public static final String MOD_ID = "utilmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ModuleManager modules;

    public static ModuleManager modules() {
        return modules;
    }

    @Override
    public void onInitializeClient() {
        modules = new ModuleManager(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json"));

        // ---- Register modules here ----
        modules.register(new AnchorMacroModule());
        modules.register(new HoverTotemModule());
        modules.register(new CrystalModule());
        modules.register(new SafeAnchorModule());
        // -------------------------------

        modules.loadConfig();
        HotbarAnimation.register();

        // Per-frame NumLock poll (draws nothing).
        HudElementRegistry.attachElementBefore(VanillaHudElements.HOTBAR,
                Identifier.fromNamespaceAndPath(MOD_ID, "global_toggle_poll"),
                (graphics, deltaTracker) -> modules.pollToggleKey());

        ClientTickEvents.START_CLIENT_TICK.register(modules::tick);

        LOGGER.info("Utility Mod loaded with {} module(s)", modules.getAll().size());
    }
}
