package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;

public class ReloadSettingsCommand extends Command {
    public ReloadSettingsCommand() {
        super("reload_settings", "Reloads bot settings");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.log("Reloading settings...");
        adris.altoclef.Settings.load(newSettings -> {
            mod.applyLegitMovementSettings(newSettings.isLegitMovement());
            mod.log("Reload successful!");
        });
        finish();
    }
}