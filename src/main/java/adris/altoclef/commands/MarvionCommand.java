package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.tasks.speedrun.MarvionBeatMinecraftTask;

public class MarvionCommand extends Command {

    public MarvionCommand() {
        super("marvion", "Beats the game (Marvion version - Ultra Fast Speedrun)");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.log("Starting Marvion Beat Minecraft Task (Ultra Fast Speedrun Mode)...");
        mod.runUserTask(new MarvionBeatMinecraftTask(mod), this::finish);
    }
}
