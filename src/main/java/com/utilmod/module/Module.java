package com.utilmod.module;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * Base class for every module. A module is isolated: it owns its own state and config keys,
 * and is only ever ticked while enabled.
 */
public abstract class Module {
    private final String id;
    private final String name;
    private final String description;
    private final boolean enabledByDefault;
    private boolean enabled;

    protected Module(String id, String name, String description, boolean enabledByDefault) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.enabledByDefault = enabledByDefault;
        this.enabled = false;
    }

    public final String getId() { return id; }
    public final String getName() { return name; }
    public final String getDescription() { return description; }
    public final boolean isEnabledByDefault() { return enabledByDefault; }
    public final boolean isEnabled() { return enabled; }

    public final void setEnabled(boolean value) {
        if (this.enabled == value) return;
        this.enabled = value;
        if (value) onEnable(); else onDisable();
    }

    public final void toggle() { setEnabled(!enabled); }

    /** Called when the module becomes enabled. */
    protected void onEnable() {}

    /** Called when the module becomes disabled. Must clean up any state. */
    protected void onDisable() {}

    /** Called at the start of every client tick, only while the module is enabled. */
    public void onTick(Minecraft mc) {}

    /** Read module-specific settings from its config section. Missing keys must keep defaults. */
    public void loadConfig(JsonObject section) {}

    /** Write module-specific settings into its config section. */
    public void saveConfig(JsonObject section) {}
}
