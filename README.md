# AltoClef Legit (Fabric 26.3)

A modernized Fabric fork of **AltoClef** targeting **Minecraft 26.3**, powered by our modern fork of **[Baritone 26.3](https://github.com/23william90/baritone-26.3)** and featuring the **Legit Movement** engine. This mod forces the bot to move, turn, look, and interact with the world like a real human player.

---

## 🚀 Minecraft 26.3 Modernization & Research

In porting AltoClef and Baritone to **Minecraft 26.3**, we analyzed the evolutionary path of modern Minecraft modding across the last major transitions:

### 1. The Modern Baritone Ecosystem (26.3)
- Baritone has moved from legacy Gradle Loom setups to **Unimined** with Mojang official mappings (`mojmap`).
- We created a dedicated modern fork: **[23william90/baritone-26.3](https://github.com/23william90/baritone-26.3)**.
- Integrated `AltoClefSettings` natively into Baritone's API (`baritone.altoclef.AltoClefSettings`) to allow custom block break/place avoiders, portal navigation, and tool safety predicates.
- Added native `legitMovement` setting into Baritone's `Settings.java`.
- Updated Nether Pathfinder to `v1.6` and Fabric Loader to `v0.19.5+`.

### 2. Multi-Version Adaptation Layer in AltoClef
In modern Minecraft, Mojang transitioned many classic systems:
- **Components over NBT**: Food components (`FoodComponentWrapper`), item enchantments (`EnchantmentHelperVer`), and tool attributes have moved to static Data Components.
- **Client & Interaction Abstraction**: `MinecraftClientVer` and `InteractionManagerVer` abstract the differences across modern client method signatures.
- **Raytracing**: `RaycastContext` now uses explicit `ShapeType.OUTLINE` with `FluidHandling` to perform accurate occlusion detection.

---

## ✨ What's New: Legit Movement Engine

The new `legitMovement` mode eliminates snappy, robotic, and illegitimate bot behaviors:

1. **👀 Looks in the Direction of Movement**
   - While traversing paths or moving, the bot smoothly faces its movement vector and path heading rather than holding a fixed robotic stare.
   - Automatically slows down sprinting on sharp turns (angle > 45°) to navigate corners realistically without unnatural sideways/backward strafe-drifting.

2. **🧱 Cannot Break Things or Interact Through Walls**
   - Incorporates strict line-of-sight raycasting (`RaycastContext.ShapeType.OUTLINE`).
   - If an obstacle or opaque wall blocks the path between the player's eyes and the target block, the block is treated as unreachable.
   - The bot will pathfind around walls to establish a legitimate, clear line of sight before swinging or right-clicking.
   - Waits for the crosshairs to align with the block face before initiating tool swings.

3. **🖱️ Slower, Smooth, Human-like Rotation**
   - Completely removes 1-tick instant snapping of player yaw and pitch.
   - Replaces snapping with an ease-in/ease-out rotational kinematics model with configurable turn speed limits (default 18°/tick).
   - Synchronizes seamlessly with Baritone's `smoothLook`, `antiCheatCompatibility`, and `legitMine` options.

---

## ⚙️ Configuration & Settings

You can toggle and customize Legit Movement via chat commands or configuration file:

### In-Game Commands
- `@legit` - Toggles legit movement mode on/off.
- `@legit on` / `@legit off` - Explicitly enables or disables legit movement.
- `@reload_settings` - Reloads all configuration settings from disk.

### Settings File (`altoclef_settings.json`)
```json
{
  "legitMovement": true,
  "legitRotationSpeed": 18.0
}
```
- `legitMovement` (boolean): Enables the legit movement and line-of-sight protection engine.
- `legitRotationSpeed` (float): Maximum camera rotation speed in degrees per tick (lower values like `12.0`–`18.0` produce smooth, human-like turns).

When `legitMovement` is activated, the following Baritone settings are synchronized:
- `legitMine` = `true` (Disallows mining ores through solid geometry without line of sight)
- `smoothLook` = `true` (Smooths camera transitions towards path waypoints)
- `antiCheatCompatibility` = `true` (Guarantees client/server rotational consistency)
- `remainWithExistingLookDirection` = `false` (Forces gaze towards path heading)
- `freeLook` = `false`

---

## 🛠️ Building & Running

### Prerequisites
- **Java 21 / 25 JDK**
- **Fabric Loader** 0.19.5+
- **Minecraft 26.3**

### Associated Baritone Fork
This branch connects with the modern Baritone 26.3 repository:
👉 **[23william90/baritone-26.3](https://github.com/23william90/baritone-26.3)**

### Build the Mod JAR
```bash
./gradlew build -x test
```

---

## 📜 Credits & Acknowledgments
- **AltoClef** originally created by **TacoTechnica** and developed by **James Green**, **Marvion Kirito**, and **MiranCZ**.
- **Baritone** pathfinding library created by **cabaletta** and maintained by the Meteor development team & Baritone contributors.
- **FabricMC** modding toolchain and yarn mappings.

Licensed under the [MIT License](LICENSE).
