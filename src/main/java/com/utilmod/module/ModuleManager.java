package com.utilmod.module;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utilmod.UtilityMod;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registers modules, ticks the enabled ones, and persists their config in a single JSON file.
 * The file is hot-reloaded when it changes on disk, so modules can be toggled by editing it.
 */
public final class ModuleManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int RELOAD_CHECK_INTERVAL_TICKS = 20;

    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final Set<String> failed = new HashSet<>();
    private final Path configPath;
    private long lastKnownModified = -1L;
    private int ticks;

    public ModuleManager(Path configPath) {
        this.configPath = configPath;
    }

    public void register(Module module) {
        if (modules.putIfAbsent(module.getId(), module) != null) {
            throw new IllegalStateException("Duplicate module id: " + module.getId());
        }
    }

    public Module get(String id) { return modules.get(id); }

    public Collection<Module> getAll() { return Collections.unmodifiableCollection(modules.values()); }

    public void tick(Minecraft mc) {
        if (++ticks % RELOAD_CHECK_INTERVAL_TICKS == 0) {
            checkReload();
        }
        for (Module module : new ArrayList<>(modules.values())) {
            if (!module.isEnabled() || failed.contains(module.getId())) continue;
            try {
                module.onTick(mc);
            } catch (Throwable t) {
                // A broken module must never take the client down or affect other modules.
                failed.add(module.getId());
                UtilityMod.LOGGER.error("Module '{}' crashed and was disabled until the config is reloaded", module.getId(), t);
            }
        }
    }

    public void loadConfig() {
        failed.clear();
        if (!Files.exists(configPath)) {
            for (Module m : modules.values()) m.setEnabled(m.isEnabledByDefault());
            saveConfig();
            return;
        }
        JsonObject sections = new JsonObject();
        try (Reader reader = Files.newBufferedReader(configPath)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root.isJsonObject() && root.getAsJsonObject().has("modules")
                    && root.getAsJsonObject().get("modules").isJsonObject()) {
                sections = root.getAsJsonObject().getAsJsonObject("modules");
            }
        } catch (Exception e) {
            UtilityMod.LOGGER.error("Could not read config, using defaults", e);
        }
        for (Module m : modules.values()) {
            JsonObject section = sections.has(m.getId()) && sections.get(m.getId()).isJsonObject()
                    ? sections.getAsJsonObject(m.getId()) : new JsonObject();
            try {
                m.loadConfig(section);
            } catch (Exception e) {
                UtilityMod.LOGGER.error("Bad config for module '{}', keeping defaults", m.getId(), e);
            }
            boolean enabled = section.has("enabled") && section.get("enabled").isJsonPrimitive()
                    ? section.get("enabled").getAsBoolean() : m.isEnabledByDefault();
            m.setEnabled(enabled);
        }
        rememberModified();
    }

    public void saveConfig() {
        JsonObject sections = new JsonObject();
        for (Module m : modules.values()) {
            JsonObject section = new JsonObject();
            section.addProperty("enabled", m.isEnabled());
            m.saveConfig(section);
            sections.add(m.getId(), section);
        }
        JsonObject root = new JsonObject();
        root.add("modules", sections);
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.toJson(root, writer);
            }
            rememberModified();
        } catch (IOException e) {
            UtilityMod.LOGGER.error("Could not save config", e);
        }
    }

    private void checkReload() {
        try {
            if (Files.exists(configPath) && Files.getLastModifiedTime(configPath).toMillis() != lastKnownModified) {
                UtilityMod.LOGGER.info("Config changed on disk, reloading");
                loadConfig();
            }
        } catch (IOException ignored) {
        }
    }

    private void rememberModified() {
        try {
            lastKnownModified = Files.getLastModifiedTime(configPath).toMillis();
        } catch (IOException e) {
            lastKnownModified = -1L;
        }
    }
}
