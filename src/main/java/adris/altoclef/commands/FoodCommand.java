package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.IntArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.resources.CollectFoodTask;

public class FoodCommand extends Command {

    public FoodCommand() throws CommandException {
        super("food", "Collect food (units of hunger)", new IntArg("units", 20));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        int units = 20;
        try {
            units = parser.get(Integer.class);
        } catch (Throwable ignored) {
        }
        mod.log("Gathering " + units + " food units...");
        mod.runUserTask(new CollectFoodTask(units), this::finish);
    }
}