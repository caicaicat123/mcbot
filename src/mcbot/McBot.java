package mcbot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 住在服务器里的捣蛋鬼 AI。
 *
 * 它没有实体、不会出现在世界里，只靠聊天、音效、标题和少量白名单指令刷存在感。
 * 大部分时间自己找乐子（定时骚扰），玩家喊它时会正经回答几句。
 */
public final class McBot extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Random random = new Random();
    private final Map<UUID, Long> lastReply = new HashMap<>();

    private DeepSeek ai;
    private BukkitTask loopTask;
    private int countdown;

    private boolean mischiefEnabled;
    private int intervalSeconds;
    private int jitterSeconds;
    private int minPlayers;
    private double mischiefChance;
    private boolean targetOps;
    private double privateChance;

    private List<String> mentionTriggers = List.of();
    private int mentionCooldownSeconds;

    private boolean guideEnabled;
    private int guideWindowSeconds;
    private String guideMessage;
    private boolean guideUseAi;
    private long guideWindowUntil;
    private volatile boolean guideUsed;

    private boolean soundsEnabled;
    private boolean titlesEnabled;
    private boolean commandsEnabled;
    private List<String> allowedSounds = List.of();
    private List<String> allowedCommands = List.of();
    private List<String> lines = List.of();

    private double aiChance;
    private int replyCooldownSeconds;
    private String colorPrefix;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("mcbot") != null) {
            getCommand("mcbot").setExecutor(this);
            getCommand("mcbot").setTabCompleter(this);
        }
        startLoop();
        getLogger().info("回声已就位。AI=" + (ai.configured() ? "开启" : "未配置，仅使用本地台词"));
    }

    @Override
    public void onDisable() {
        if (loopTask != null) {
            loopTask.cancel();
            loopTask = null;
        }
    }

    // ---------------------------------------------------------------- 配置

    private void loadSettings() {
        reloadConfig();
        var cfg = getConfig();

        ai = new DeepSeek(
                cfg.getString("deepseek.api-key", ""),
                cfg.getString("deepseek.base-url", "https://api.deepseek.com"),
                cfg.getString("deepseek.model", "deepseek-chat"),
                cfg.getDouble("deepseek.temperature", 1.35),
                cfg.getInt("deepseek.max-tokens", 180),
                cfg.getInt("deepseek.timeout-seconds", 25));

        colorPrefix = cfg.getString("bot.color-prefix", "§5[回声]§r ");
        aiChance = cfg.getDouble("bot.ai-chance", 0.55);
        replyCooldownSeconds = cfg.getInt("bot.reply-cooldown-seconds", 12);

        mischiefEnabled = cfg.getBoolean("mischief.enabled", true);
        intervalSeconds = Math.max(30, cfg.getInt("mischief.interval-seconds", 240));
        jitterSeconds = Math.max(0, cfg.getInt("mischief.jitter-seconds", 180));
        mischiefChance = cfg.getDouble("mischief.chance", 0.7);
        minPlayers = Math.max(1, cfg.getInt("mischief.min-players", 1));
        targetOps = cfg.getBoolean("mischief.target-ops", false);
        privateChance = cfg.getDouble("mischief.private-chance", 0.0);

        mentionTriggers = cfg.getStringList("mention.triggers").stream()
                .filter(s -> !s.isBlank()).collect(Collectors.toList());
        if (mentionTriggers.isEmpty()) {
            // 配置文件是旧版时兜底，否则它会永远不回应
            mentionTriggers = List.of("@yl", "@幽灵");
        }
        mentionCooldownSeconds = Math.max(1, cfg.getInt("mention.cooldown-seconds", 6));

        guideEnabled = cfg.getBoolean("guide.enabled", true);
        guideWindowSeconds = Math.max(10, cfg.getInt("guide.window-seconds", 90));
        guideMessage = cfg.getString("guide.message", "想跟我说话？直接 @yl 你要说的内容，我就听得见。");
        guideUseAi = cfg.getBoolean("guide.use-ai", false);

        soundsEnabled = cfg.getBoolean("actions.sounds.enabled", true);
        titlesEnabled = cfg.getBoolean("actions.titles.enabled", true);
        commandsEnabled = cfg.getBoolean("actions.commands.enabled", true);
        allowedSounds = cfg.getStringList("actions.sounds.allowed").stream()
                .map(s -> s.toLowerCase(Locale.ROOT).trim()).collect(Collectors.toList());
        allowedCommands = cfg.getStringList("actions.commands.allowed").stream()
                .map(s -> s.toLowerCase(Locale.ROOT).trim()).collect(Collectors.toList());

        lines = cfg.getStringList("lines");
        if (lines.isEmpty()) {
            lines = List.of("……", "有人吗。");
        }
    }

    // ------------------------------------------------------------ 定时搞怪

    private void startLoop() {
        if (loopTask != null) {
            loopTask.cancel();
        }
        countdown = nextIntervalSeconds();
        loopTask = getServer().getScheduler().runTaskTimer(this, this::tick, 400L, 400L);
    }

    private int nextIntervalSeconds() {
        int jitter = jitterSeconds > 0 ? random.nextInt(jitterSeconds * 2 + 1) - jitterSeconds : 0;
        return Math.max(20, intervalSeconds + jitter);
    }

    private void tick() {
        if (!mischiefEnabled) {
            return;
        }
        countdown -= 20;
        if (countdown > 0) {
            return;
        }
        countdown = nextIntervalSeconds();
        if (random.nextDouble() > mischiefChance) {
            return;
        }
        List<Player> pool = eligiblePlayers();
        if (pool.size() < minPlayers) {
            return;
        }
        Player target = pool.get(random.nextInt(pool.size()));
        boolean quiet = random.nextDouble() < privateChance;
        speak(target, "服务器里安静了一会儿，你想找点乐子", quiet, false);
        if (!quiet && guideEnabled) {
            // 公屏说完话之后，盯住接下来的第一条玩家发言
            guideWindowUntil = System.currentTimeMillis() + guideWindowSeconds * 1000L;
            guideUsed = false;
        }
    }

    private List<Player> eligiblePlayers() {
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOnline() && (targetOps || !p.isOp())) {
                list.add(p);
            }
        }
        return list;
    }

    // -------------------------------------------------------------- AI 交互

    /** @param forceAi true 时跳过概率过滤，一定走 API（被 @ 和玩家主动提问用这个）。 */
    private void speak(Player target, String trigger, boolean quiet, boolean forceAi) {
        if (!isEnabled()) {
            return;
        }
        if (ai != null && ai.configured() && (forceAi || random.nextDouble() < aiChance)) {
            String system = Brain.systemPrompt(this, allowedSounds, allowedCommands);
            String user = Brain.userPrompt(trigger, context(target));
            ai.chat(system, user).whenComplete((raw, error) -> {
                Brain.Reply reply;
                if (error != null) {
                    getLogger().warning("DeepSeek 调用失败: " + error.getMessage());
                    reply = Brain.local(lines, allowedSounds, random);
                } else {
                    reply = Brain.parse(raw);
                    if (reply.say().isBlank()) {
                        reply = Brain.local(lines, allowedSounds, random);
                    }
                }
                deliver(reply, target, quiet);
            });
        } else {
            deliver(Brain.local(lines, allowedSounds, random), target, quiet);
        }
    }

    private void deliver(Brain.Reply reply, Player target, boolean quiet) {
        getServer().getScheduler().runTask(this, () -> {
            if (!isEnabled()) {
                return;
            }
            Player online = target != null && target.isOnline() ? target : null;
            if (!reply.say().isBlank()) {
                String message = colorize(colorPrefix + reply.say());
                if (quiet || online == null) {
                    if (online != null) {
                        online.sendMessage(message);
                    }
                } else {
                    Bukkit.broadcastMessage(message);
                }
            }
            String result = Actions.run(this, reply, online);
            if (!"none".equals(result) && !"denied".equals(result)) {
                getLogger().info("动作: " + result + (online != null ? " -> " + online.getName() : ""));
            }
        });
    }

    private String context(Player target) {
        List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
        String world = target == null ? "未知" : target.getWorld().getName();
        long time = target == null ? 0L : target.getWorld().getTime();
        String phase = time < 12300 ? "白天" : (time < 23850 ? "傍晚" : "夜晚");
        String weather = target != null && target.getWorld().hasStorm() ? "下雨" : "晴";
        return "在线 " + names.size() + " 人（" + String.join("、", names) + "），"
                + "世界 " + world + "，" + phase + "，" + weather;
    }

    // ---------------------------------------------------------------- 事件

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);

        // 1) 被 @ 才回复：这是唯一会调用 API 的入口
        boolean mentioned = mentionTriggers.stream()
                .anyMatch(t -> lower.contains(t.toLowerCase(Locale.ROOT)));
        if (mentioned) {
            long now = System.currentTimeMillis();
            Long last = lastReply.get(player.getUniqueId());
            if (last != null && now - last < mentionCooldownSeconds * 1000L) {
                return;
            }
            lastReply.put(player.getUniqueId(), now);
            speak(player, player.getName() + " 用 @ 对你说了：" + message, false, true);
            return;
        }

        // 2) 它刚在公屏说完话：接住接下来的第一条玩家发言，告诉对方怎么找它
        if (guideEnabled && !guideUsed && System.currentTimeMillis() < guideWindowUntil) {
            guideUsed = true;
            guideWindowUntil = 0L;
            if (guideUseAi) {
                speak(player, player.getName() + " 接话了：" + message, false, true);
            } else {
                Bukkit.broadcastMessage(colorize(colorPrefix + guideMessage));
            }
            return;
        }

        // 3) 其余聊天一律不处理：不检测、不调用 API，也就没有开销
    }

    // ---------------------------------------------------------------- 命令

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(colorize("§5[回声]§r ask <内容> · poke [玩家] · toggle · reload · status"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "ask" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c这个子命令只能在游戏里用。");
                    return true;
                }
                if (!player.hasPermission("mcbot.talk")) {
                    player.sendMessage(colorize("§c你还没有和它说话的权利。"));
                    return true;
                }
                String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                if (text.isEmpty()) {
                    player.sendMessage(colorize("§7想说什么？例如 /mcbot ask 你好"));
                    return true;
                }
                long now = System.currentTimeMillis();
                Long last = lastReply.get(player.getUniqueId());
                if (last != null && now - last < replyCooldownSeconds * 1000L) {
                    player.sendMessage(colorize("§7它还在消化你上一句话……"));
                    return true;
                }
                lastReply.put(player.getUniqueId(), now);
                player.sendMessage(colorize("§8……它在听着"));
        speak(player, player.getName() + " 对你说：" + text, true, true);
            }
            case "poke" -> {
                if (!sender.hasPermission("mcbot.admin")) {
                    sender.sendMessage("§c需要 mcbot.admin 权限。");
                    return true;
                }
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    List<Player> pool = eligiblePlayers();
                    if (pool.isEmpty()) {
                        sender.sendMessage(colorize("§7现在没人在线，用空目标跑一次做自检。"));
                        speak(null, "被管理员推了一把，服务器里空无一人", true, true);
                        return true;
                    }
                    target = pool.get(random.nextInt(pool.size()));
                }
                sender.sendMessage(colorize("§7正在让回声去找 " + target.getName() + " ……"));
                speak(target, "被管理员推了一把，去找点乐子", false, false);
            }
            case "toggle" -> {
                if (!sender.hasPermission("mcbot.admin")) {
                    sender.sendMessage("§c需要 mcbot.admin 权限。");
                    return true;
                }
                mischiefEnabled = !mischiefEnabled;
                sender.sendMessage(colorize("§7回声的搞怪开关：" + (mischiefEnabled ? "开" : "关")));
            }
            case "reload" -> {
                if (!sender.hasPermission("mcbot.admin")) {
                    sender.sendMessage("§c需要 mcbot.admin 权限。");
                    return true;
                }
                loadSettings();
                startLoop();
                sender.sendMessage(colorize("§7配置已重载。AI=" + (ai.configured() ? "开启" : "未配置")));
            }
            case "status" -> {
                sender.sendMessage(colorize("§5回声§r 状态："));
                sender.sendMessage("§7 搞怪：" + (mischiefEnabled ? "开" : "关")
                        + "  间隔：" + intervalSeconds + "±" + jitterSeconds + " 秒"
                        + "  AI：" + (ai.configured() ? "已配置" : "未配置"));
                sender.sendMessage("§7 只有被 @" + String.join(" / @", mentionTriggers) + " 才回复；"
                        + "引导窗口 " + guideWindowSeconds + " 秒");
                sender.sendMessage("§7 音效 " + allowedSounds.size() + " 个；指令白名单："
                        + String.join(", ", allowedCommands));
            }
            default -> sender.sendMessage(colorize("§c未知子命令。用法：/mcbot ask|poke|toggle|reload|status"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("ask", "poke", "toggle", "reload", "status").stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    // ---------------------------------------------------------------- 工具

    public String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public boolean soundsEnabled() {
        return soundsEnabled;
    }

    public boolean titlesEnabled() {
        return titlesEnabled;
    }

    public boolean commandsEnabled() {
        return commandsEnabled;
    }

    public List<String> allowedSounds() {
        return allowedSounds;
    }

    public List<String> allowedCommands() {
        return allowedCommands;
    }
}
