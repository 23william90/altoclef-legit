package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.tasksystem.Task;

import java.util.List;

public class StatusCommand extends Command {

    public StatusCommand() {
        super("status", "Get the status of currently running tasks");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.log("########## STATUS ##########");
        if (mod.getTaskRunner() != null) {
            mod.log("Status: " + mod.getTaskRunner().statusReport);
            if (mod.getTaskRunner().getCurrentTaskChain() != null) {
                List<Task> tasks = mod.getTaskRunner().getCurrentTaskChain().getTasks();
                if (tasks.isEmpty()) {
                    mod.log(" (no task running)");
                } else {
                    for (int i = 0; i < tasks.size(); i++) {
                        String indent = "  ".repeat(i);
                        mod.log(indent + "- " + tasks.get(i).toString());
                    }
                }
            }
        }
        mod.log("Paused: " + mod.isPaused());
        mod.log("Legit Movement: " + mod.getModSettings().isLegitMovement());
        mod.log("############################");
        finish();
    }
}