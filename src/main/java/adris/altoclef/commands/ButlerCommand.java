package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.butler.Butler;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;

public class ButlerCommand extends Command {

    public ButlerCommand() throws CommandException {
        super("butler", "Configures the Butler system whitelist, blacklist, and permissions",
                new StringArg("action", "status"),
                new StringArg("target", ""));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        Butler butler = mod.getButler();
        if (butler == null) {
            mod.log("Butler system is not active.");
            finish();
            return;
        }

        String action = parser.get(String.class).toLowerCase();
        String target = "";
        try {
            target = parser.get(String.class).toLowerCase();
        } catch (Throwable ignored) {
        }

        switch (action) {
            case "whitelist" -> {
                if (target.equals("on") || target.equals("enable")) {
                    butler.setUseWhitelist(true);
                    mod.log("Butler: Whitelist enabled.");
                } else if (target.equals("off") || target.equals("disable")) {
                    butler.setUseWhitelist(false);
                    mod.log("Butler: Whitelist disabled.");
                } else if (!target.isEmpty()) {
                    butler.addToWhitelist(target);
                    mod.log("Butler: Added " + target + " to whitelist.");
                } else {
                    mod.log("Usage: @butler whitelist <on/off/player_name>");
                }
            }
            case "blacklist" -> {
                if (target.equals("on") || target.equals("enable")) {
                    butler.setUseBlacklist(true);
                    mod.log("Butler: Blacklist enabled.");
                } else if (target.equals("off") || target.equals("disable")) {
                    butler.setUseBlacklist(false);
                    mod.log("Butler: Blacklist disabled.");
                } else if (!target.isEmpty()) {
                    butler.addToBlacklist(target);
                    mod.log("Butler: Added " + target + " to blacklist.");
                } else {
                    mod.log("Usage: @butler blacklist <on/off/player_name>");
                }
            }
            default -> {
                String current = butler.getCurrentUser();
                mod.log("Butler Status: " + (current != null ? "Serving " + current : "Idle"));
            }
        }
        finish();
    }
}
