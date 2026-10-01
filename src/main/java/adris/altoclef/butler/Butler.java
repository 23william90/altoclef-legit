package adris.altoclef.butler;

import adris.altoclef.AltoClef;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ChatMessageEvent;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modern Reactive Butler System:
 * Allows authorized players in multiplayer to issue tasks and commands to the bot via whispers or chat.
 */
public class Butler {

    private static final Pattern[] WHISPER_PATTERNS = new Pattern[]{
            Pattern.compile("^(\\w+) whispers to you: (.*)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(\\w+) whispers: (.*)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\[(\\w+) -> me\\] (.*)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\[(\\w+) -> you\\] (.*)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(\\w+) -> you: (.*)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^<(\\w+)> @?bot (.*)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^<(\\w+)> !bot (.*)$", Pattern.CASE_INSENSITIVE)
    };

    private final AltoClef mod;
    private final Set<String> whitelist = new HashSet<>();
    private final Set<String> blacklist = new HashSet<>();
    private boolean useWhitelist = false;
    private boolean useBlacklist = true;
    private String currentUser = null;

    public Butler(AltoClef mod) {
        this.mod = mod;
        loadLists();

        // Listen for task completion to clear active user
        EventBus.subscribe(TaskFinishedEvent.class, evt -> {
            if (currentUser != null) {
                sendReply(currentUser, "Task completed.");
                currentUser = null;
            }
        });

        // Listen for incoming chat messages & whispers
        EventBus.subscribe(ChatMessageEvent.class, evt -> {
            String raw = evt.messageContent();
            if (raw == null || raw.isBlank()) return;
            handleIncomingChat(raw);
        });
    }

    public void handleIncomingChat(String rawMessage) {
        for (Pattern p : WHISPER_PATTERNS) {
            Matcher m = p.matcher(rawMessage.trim());
            if (m.find()) {
                String sender = m.group(1);
                String commandText = m.group(2).trim();

                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && sender.equalsIgnoreCase(mc.player.getName().getString())) {
                    // Ignore messages from self
                    continue;
                }

                executeWhisper(sender, commandText);
                return;
            }
        }
    }

    public void executeWhisper(String sender, String commandText) {
        if (!isAuthorized(sender)) {
            sendReply(sender, "You are not authorized to command this bot.");
            AltoClef.getInstance().log("Rejected Butler command from unauthorized user: " + sender);
            return;
        }

        AltoClef.getInstance().log("Butler received command from " + sender + ": " + commandText);
        currentUser = sender;
        sendReply(sender, "Executing: " + commandText);

        String prefix = "@";
        if (commandText.startsWith("@")) {
            commandText = commandText.substring(1);
        }

        String finalCommandText = commandText;
        AltoClef.getCommandExecutor().execute(prefix + finalCommandText, () -> {
            sendReply(sender, "Command finished: " + finalCommandText);
            currentUser = null;
        }, error -> {
            sendReply(sender, "Command failed: " + error.getMessage());
            currentUser = null;
        });
    }

    public void sendReply(String targetUser, String message) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null && player.connection != null) {
            try {
                // Send whisper via vanilla /msg
                player.connection.sendCommand("msg " + targetUser + " [Butler] " + message);
            } catch (Throwable t) {
                mod.log("[Butler -> " + targetUser + "]: " + message);
            }
        } else {
            mod.log("[Butler -> " + targetUser + "]: " + message);
        }
    }

    public boolean isAuthorized(String username) {
        if (useBlacklist && blacklist.contains(username.toLowerCase())) {
            return false;
        }
        if (useWhitelist) {
            return whitelist.contains(username.toLowerCase());
        }
        return true;
    }

    public void setUseWhitelist(boolean useWhitelist) {
        this.useWhitelist = useWhitelist;
    }

    public void setUseBlacklist(boolean useBlacklist) {
        this.useBlacklist = useBlacklist;
    }

    public void addToWhitelist(String username) {
        whitelist.add(username.toLowerCase());
        saveLists();
    }

    public void addToBlacklist(String username) {
        blacklist.add(username.toLowerCase());
        saveLists();
    }

    private void loadLists() {
        File dir = new File("altoclef");
        if (!dir.exists()) dir.mkdirs();

        File whiteFile = new File(dir, "butler_whitelist.txt");
        if (whiteFile.exists()) {
            try {
                List<String> lines = Files.readAllLines(whiteFile.toPath());
                for (String l : lines) {
                    String clean = l.trim().toLowerCase();
                    if (!clean.isEmpty() && !clean.startsWith("#")) {
                        whitelist.add(clean);
                    }
                }
            } catch (IOException ignored) {}
        }

        File blackFile = new File(dir, "butler_blacklist.txt");
        if (blackFile.exists()) {
            try {
                List<String> lines = Files.readAllLines(blackFile.toPath());
                for (String l : lines) {
                    String clean = l.trim().toLowerCase();
                    if (!clean.isEmpty() && !clean.startsWith("#")) {
                        blacklist.add(clean);
                    }
                }
            } catch (IOException ignored) {}
        }
    }

    private void saveLists() {
        File dir = new File("altoclef");
        if (!dir.exists()) dir.mkdirs();
        try {
            Files.write(new File(dir, "butler_whitelist.txt").toPath(), whitelist);
            Files.write(new File(dir, "butler_blacklist.txt").toPath(), blacklist);
        } catch (IOException ignored) {}
    }

    public String getCurrentUser() {
        return currentUser;
    }
}
