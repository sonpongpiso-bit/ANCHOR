package com.utilmod;

import com.utilmod.anchor.AnchorMacroModule;
import com.utilmod.anchor.HotbarAnimation;
import com.utilmod.module.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
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
        // modules.register(new YourNextModule());
        // -------------------------------

        modules.loadConfig();
        HotbarAnimation.register();

        // START tick: runs before vanilla key handling, so the macro can pre-empt vanilla right-click.
        ClientTickEvents.START_CLIENT_TICK.register(modules::tick);

        LOGGER.info("Utility Mod loaded with {} module(s)", modules.getAll().size());
    }
}
