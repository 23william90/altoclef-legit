package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;

public class GamerCommand extends Command {
    public GamerCommand() {
        super("gamer", "Beats the game (automated bot progression)");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.log("AltoClef Gamer progression initiated with Legit Movement active.");
        finish();
    }
}