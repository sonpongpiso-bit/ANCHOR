# Utility Mod (Fabric, Minecraft 26.1.2)

Modular client-side utility mod. Currently contains one module: **Anchor Macro**.

## Build
Requires JDK 25. Minecraft 26.1.2 is unobfuscated, so no mappings are used.

- GitHub: push the project; `.github/workflows/build.yml` builds it and uploads the JAR as an artifact
  (it installs Gradle 9.4.0 itself, so no wrapper JAR is needed).
- Locally: install Gradle 9.x, run `gradle wrapper --gradle-version 9.4.0` once, then `./gradlew build`.
  Output: `build/libs/utilmod-1.0.0.jar`.

## Anchor Macro
Only active while right-click is held (no GUI open, not sneaking).

- Looking at an existing anchor (any item in hand): charge if uncharged -> slot 9 -> detonate.
- Holding an anchor and looking at a block: place -> charge -> slot 9 -> detonate, repeating while held.
  After the first cycle you stay "armed" until right-click is released.
- You need glowstone in the hotbar. Slot 9 should hold a non-glowstone item (totem, sword, anything).

All clicks go through the vanilla `MultiPlayerGameMode#useItemOn` path, so the server receives the normal
slot-change + use-item-on packet sequence, all within one tick.

## Config
`config/utilmod.json` (hot-reloaded when edited):

```json
{
  "modules": {
    "anchor_macro": {
      "enabled": true,
      "detonateSlot": 9,
      "hotbarAnimation": true,
      "swingHand": true
    }
  }
}
```

## Adding a module
1. Create a class extending `com.utilmod.module.Module` (own package, e.g. `com.utilmod.yourmodule`).
2. Override `onTick`, `onEnable`/`onDisable`, `loadConfig`/`saveConfig` as needed.
3. Register it in `UtilityMod#onInitializeClient` with `modules.register(new YourModule());`.
Its config section appears automatically under `modules.<id>` in `utilmod.json`.

## Icon / assets
Replace `src/main/resources/assets/utilmod/icon.png` (128x128 PNG recommended). The path is already
referenced in `fabric.mod.json`. A placeholder icon is included.
