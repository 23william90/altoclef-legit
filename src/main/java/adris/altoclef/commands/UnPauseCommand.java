package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;

public class UnPauseCommand extends Command {

    public UnPauseCommand() {
        super(java.util.List.of("unpause", "resume"), "Resume bot tasks");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        if (mod.isPaused()) {
            mod.setPaused(false);
            try {
                if (mod.getClientBaritone() != null) {
                    mod.getClientBaritone().getCommandManager().execute("resume");
                }
            } catch (Throwable ignored) {
            }
            mod.log("Resumed.");
        }
        finish();
    }
}