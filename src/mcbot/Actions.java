package mcbot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** 动作执行。模型只能触发白名单内的动作，危险指令在此被硬拦截。 */
public final class Actions {

    /** 无论配置怎么写，这些指令永远不会被执行。 */
    // 用 LinkedHashSet 而不是 Set.of：后者遇到重复元素会在类初始化时直接抛异常
    private static final Set<String> HARD_BLOCKED = new LinkedHashSet<>(List.of(
            "op", "deop", "ban", "ban-ip", "banlist", "pardon", "pardon-ip", "kick", "stop", "restart",
            "whitelist", "reload", "rl", "plugman", "execute", "tp", "teleport", "give", "clear", "kill",
            "fill", "setblock", "clone", "summon", "gamemode", "difficulty", "worldborder", "gamerule",
            "save-off", "save-all", "datapack", "function", "recipe", "advancement", "spreadplayers",
            "forceload", "bukkit:reload", "minecraft:stop", "paper", "spark", "plugins",
            "version", "seed", "debug", "jfr", "perf", "mspt", "tps"));

    private Actions() {
    }

    public static String run(McBot plugin, Brain.Reply reply, Player target) {
        if (!reply.hasAction()) {
            return "none";
        }
        String type = reply.actionType().toUpperCase(Locale.ROOT);
        String value = reply.actionValue() == null ? "" : reply.actionValue().trim();
        if (value.isEmpty()) {
            return "empty";
        }
        try {
            switch (type) {
                case "SOUND" -> {
                    if (!plugin.soundsEnabled() || target == null || !target.isOnline()) {
                        return "denied";
                    }
                    String name = value.toLowerCase(Locale.ROOT).trim();
                    if (!plugin.allowedSounds().contains(name)) {
                        return "sound-not-allowed";
                    }
                    target.playSound(target.getLocation(), name, 0.9f, 1.0f);
                    return "sound:" + name;
                }
                case "TITLE" -> {
                    if (!plugin.titlesEnabled() || target == null || !target.isOnline()) {
                        return "denied";
                    }
                    String text = value.length() > 60 ? value.substring(0, 60) : value;
                    target.sendTitle(plugin.colorize("§d" + text), plugin.colorize("§7—— 回声"), 8, 45, 12);
                    return "title";
                }
                case "COMMAND" -> {
                    if (!plugin.commandsEnabled()) {
                        return "denied";
                    }
                    String command = value.startsWith("/") ? value.substring(1) : value;
                    String head = command.split("\\s+")[0].toLowerCase(Locale.ROOT);
                    String bare = head.contains(":") ? head.substring(head.indexOf(':') + 1) : head;
                    if (HARD_BLOCKED.contains(head) || HARD_BLOCKED.contains(bare)
                            || !plugin.allowedCommands().contains(bare)) {
                        plugin.getLogger().warning("拦截了模型指令: " + command);
                        return "command-blocked";
                    }
                    if (command.length() > 160) {
                        return "command-too-long";
                    }
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                    return "command:" + head;
                }
                default -> {
                    return "unknown:" + type;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("执行动作失败 (" + type + "): " + e.getMessage());
            return "error";
        }
    }

    public static List<String> hardBlocked() {
        return List.copyOf(HARD_BLOCKED);
    }
}
