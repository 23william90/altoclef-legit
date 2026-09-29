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
        int addX = 6;
        int addY = font.lineHeight + 2;
        int whiteColor = 0xFFFFFFFF;
        int grayColor = 0xFFAAAAAA;
        int yellowColor = 0xFFFFFF55;
        int greenColor = 0xFF55FF55;

        // Background box estimation
        int boxWidth = 220;
        int estimatedLines = 2 + (tasks.isEmpty() ? 1 : Math.min(tasks.size(), 8));
        int boxHeight = estimatedLines * addY + 6;
        extractor.fill(x - 3, y - 3, x + boxWidth, y + boxHeight, 0x90000000);

        // Header: Timer / Mod tag
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
            timerStr = "<" + DATE_TIME_FORMATTER.format(Instant.now().minusMillis(runningSince)) + ">";
            extractor.text(font, timerStr, x, y, greenColor, true);
        } else {
            timerStr = "[Alto Clef 26.3]";
            extractor.text(font, timerStr, x, y, greenColor, true);
        }

        y += addY;

        // Status report
        String status = (mod.getTaskRunner() != null) ? mod.getTaskRunner().statusReport : "(idle)";
        extractor.text(font, status, x, y, grayColor, true);
        y += addY;

        if (tasks.isEmpty()) {
            if (mod.getStoredTask() != null && mod.isPaused()) {
                renderTask(mod.getStoredTask(), extractor, font, x + addX, y);
            } else if (mod.getTaskRunner() != null && mod.getTaskRunner().isActive()) {
                extractor.text(font, " (no task running) ", x + addX, y, whiteColor, true);
            }
            if (lastTime + 10000 < Instant.now().toEpochMilli()) {
                runningSince = Instant.now().toEpochMilli();
            }
            return;
        }

        lastTime = Instant.now().toEpochMilli();

        int maxLines = 8;
        if (tasks.size() <= maxLines) {
            for (Task task : tasks) {
                renderTask(task, extractor, font, x, y);
                x += addX;
                y += addY;
            }
        } else {
            for (int i = 0; i < tasks.size(); ++i) {
                if (i == 1) {
                    x += addX * 2;
                    extractor.text(font, "...", x, y, whiteColor, true);
                } else if (i == 0 || i > tasks.size() - maxLines) {
                    renderTask(tasks.get(i), extractor, font, x, y);
                } else {
                    continue;
                }
                x += addX;
                y += addY;
            }
        }
    }

    private void renderTask(Task task, GuiGraphicsExtractor extractor, Font font, int x, int y) {
        if (task == null) return;
        String taskName = task.getClass().getSimpleName() + " ";
        extractor.text(font, taskName, x, y, 0xFF55FFFF, true);
        extractor.text(font, task.toString(), x + font.width(taskName), y, 0xFFFFFFFF, true);
    }

    public void resetTimer() {
        runningSince = Instant.now().toEpochMilli();
        lastTime = 0;
        paused = false;
        pausedTime = -1;
    }
}
