package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.tasksystem.Task;

import java.util.List;

public class PauseCommand extends Command {

    public PauseCommand() {
        super("pause", "Pause current bot tasks");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        if (!mod.isPaused()) {
            if (mod.getTaskRunner() != null && mod.getTaskRunner().getCurrentTaskChain() != null) {
                List<Task> tasks = mod.getTaskRunner().getCurrentTaskChain().getTasks();
                if (!tasks.isEmpty()) {
                    mod.setStoredTask(tasks.get(0));
                }
            }
            mod.setPaused(true);
            try {
                if (mod.getClientBaritone() != null) {
                    mod.getClientBaritone().getCommandManager().execute("pause");
                }
            } catch (Throwable ignored) {
            }
            mod.log("Paused.");
        }
        finish();
    }
}