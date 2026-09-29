package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.tasks.speedrun.BeatMinecraftTask;

public class GamerCommand extends Command {
    public GamerCommand() {
        super("gamer", "Beats the game (Miran version)");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.log("Starting Beat Minecraft Task (Gamer Mode)...");
        mod.runUserTask(new BeatMinecraftTask(mod), this::finish);
    }
}