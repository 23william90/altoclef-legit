package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;

public class WhisperCommand extends Command {

    public WhisperCommand() throws CommandException {
        super("whisper", "Simulates an incoming whisper command to the Butler",
                new StringArg("sender"),
                new StringArg("command"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String sender = parser.get(String.class);
        String command = parser.get(String.class);
        if (sender == null || command == null) {
            mod.log("Usage: @whisper <sender> <command>");
            finish();
            return;
        }

        if (mod.getButler() != null) {
            mod.getButler().executeWhisper(sender, command);
        } else {
            mod.log("Butler system is not active.");
        }
        finish();
    }
}
