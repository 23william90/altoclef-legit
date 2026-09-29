package adris.altoclef.ui;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

public class CommandStatusOverlay {

    private final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.from(ZoneOffset.of("+00:00")));
    private long runningSince;
    private long lastTime = 0;
    private long pausedTime = -1;
    private boolean paused = false;

    public void render(AltoClef mod, GuiGraphicsExtractor extractor) {
        if (mod == null || extractor == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.font == null) return;

        List<Task> tasks = Collections.emptyList();
        if (mod.getTaskRunner() != null && mod.getTaskRunner().getCurrentTaskChain() != null) {
            tasks = mod.getTaskRunner().getCurrentTaskChain().getTasks();
        }

        if (paused && !mod.isPaused()) {
            runningSince = Instant.now().minusMillis(pausedTime).toEpochMilli();
            lastTime = Instant.now().toEpochMilli();
            paused = false;
        }

        Font font = mc.font;
        int x = 6;
        int y = 6;
        int addX = 8;
        int addY = font.lineHeight + 2;

        int whiteColor = 0xFFFFFFFF;
        int grayColor = 0xFFAAAAAA;
        int yellowColor = 0xFFFFFF55;
        int greenColor = 0xFF55FF55;
        int cyanColor = 0xFF55FFFF;
        int goldColor = 0xFFFFAA00;
        int redColor = 0xFFFF5555;

        // Background box estimation
        int boxWidth = 260;
        int estimatedLines = 3 + (tasks.isEmpty() ? 1 : Math.min(tasks.size(), 8));
        int boxHeight = estimatedLines * addY + 8;

        // Draw translucent dark background & accent top line
        extractor.fill(x - 4, y - 4, x + boxWidth, y + boxHeight, 0x95000000);
        extractor.fill(x - 4, y - 4, x + boxWidth, y - 2, 0xFF00AA00); // Green accent border line

        // 1. Header: Timer / Mod info
        String timerStr;
        if (mod.isPaused() && mod.getStoredTask() != null) {
            if (!paused) {
                paused = true;
                pausedTime = Instant.now().minusMillis(runningSince).toEpochMilli();
            }
            timerStr = "<" + DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(pausedTime)) + "> (Paused)";
            extractor.text(font, timerStr, x, y, yellowColor, true);
        } else if (mod.getTaskRunner() != null && mod.getTaskRunner().isActive()) {
            lastTime = Instant.now().toEpochMilli();
            timerStr = "<" + DATE_TIME_FORMATTER.format(Instant.now().minusMillis(runningSince)) + ">  AltoClef 26.3";
            extractor.text(font, timerStr, x, y, greenColor, true);
        } else {
            timerStr = "[Alto Clef 26.3 | Marvion Edition]";
            extractor.text(font, timerStr, x, y, cyanColor, true);
        }

        y += addY;

        // 2. Active Chain Status & Indicator
        String status = (mod.getTaskRunner() != null) ? mod.getTaskRunner().statusReport : "(idle)";
        int statusColor = grayColor;
        if (status.contains("Mob Defense")) {
            statusColor = redColor;
        } else if (status.contains("Eating") || status.contains("Food")) {
            statusColor = goldColor;
        } else if (status.contains("MLG")) {
            statusColor = cyanColor;
        } else if (status.contains("User Tasks")) {
            statusColor = greenColor;
        }
        extractor.text(font, status, x, y, statusColor, true);
        y += addY;

        // Subtle separator line
        extractor.fill(x, y - 1, x + boxWidth - 10, y, 0x40FFFFFF);

        // 3. Task Tree Hierarchy
        if (tasks.isEmpty()) {
            if (mod.getStoredTask() != null && mod.isPaused()) {
                renderTask(mod.getStoredTask(), extractor, font, x + addX, y, cyanColor);
            } else if (mod.getTaskRunner() != null && mod.getTaskRunner().isActive()) {
                extractor.text(font, " (no active task) ", x + addX, y, whiteColor, true);
            }
            if (lastTime + 10000 < Instant.now().toEpochMilli()) {
                runningSince = Instant.now().toEpochMilli();
            }
            return;
        }

        lastTime = Instant.now().toEpochMilli();

        int maxLines = 8;
        if (tasks.size() <= maxLines) {
            for (int i = 0; i < tasks.size(); i++) {
                int color = (i == 0) ? cyanColor : whiteColor;
                renderTask(tasks.get(i), extractor, font, x, y, color);
                x += addX;
                y += addY;
            }
        } else {
            for (int i = 0; i < tasks.size(); ++i) {
                if (i == 1) {
                    x += addX * 2;
                    extractor.text(font, "...", x, y, whiteColor, true);
                } else if (i == 0 || i > tasks.size() - maxLines) {
                    int color = (i == 0) ? cyanColor : whiteColor;
                    renderTask(tasks.get(i), extractor, font, x, y, color);
                } else {
                    continue;
                }
                x += addX;
                y += addY;
            }
        }
    }

    private void renderTask(Task task, GuiGraphicsExtractor extractor, Font font, int x, int y, int taskNameColor) {
        if (task == null) return;
        String taskName = "• " + task.getClass().getSimpleName() + " ";
        extractor.text(font, taskName, x, y, taskNameColor, true);
        extractor.text(font, task.toString(), x + font.width(taskName), y, 0xFFFFFFFF, true);
    }

    public void resetTimer() {
        runningSince = Instant.now().toEpochMilli();
        lastTime = 0;
        paused = false;
        pausedTime = -1;
    }
}
