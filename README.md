# AltoClef Legit (Fabric 1.21.1)

A modernized Fabric fork of **AltoClef** (powered by **Baritone**) targeting **Minecraft 1.21.1**, featuring a brand-new **Legit Movement** engine. This mod forces the bot to move, turn, look, and interact with the world just like a legitimate human player.

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
- **Java 21 JDK** (Required for Minecraft 1.20.5+ / 1.21+)
- **Fabric Loader** 0.16.2+
- **Minecraft 1.21.1**

### Build the Mod JAR
```bash
./gradlew build -x test
```
The compiled, remapped, and shaded mod JAR will be located at:
```
build/libs/altoclef-1.21.1-0.20-legit.jar
```

### Run in Development
```bash
./gradlew runClient
```

---

## 📜 Credits & Acknowledgments
- **AltoClef** originally created by **TacoTechnica** and developed by **James Green**, **Marvion Kirito**, and **MiranCZ**.
- **Baritone** pathfinding library created by **cabaletta** and maintained by the Baritone and Meteor development teams.
- **FabricMC** modding toolchain and yarn mappings.

Licensed under the [MIT License](LICENSE).
