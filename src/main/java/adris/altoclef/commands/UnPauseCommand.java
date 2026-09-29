package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;

public class UnPauseCommand extends Command {
    public UnPauseCommand() {
        super("unpause", "Unpauses the bot");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        if (!mod.isPaused()) {
            mod.log("Bot is already running!");
        } else {
            mod.setPaused(false);
            mod.log("Resuming Bot");
        }
        finish();
    }
}