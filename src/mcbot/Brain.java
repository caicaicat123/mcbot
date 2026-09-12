package mcbot;

import java.util.List;
import java.util.Locale;

/** 提示词构造与模型回复解析。 */
public final class Brain {

    /** 模型回复：一句话 + 一个可选动作。 */
    public record Reply(String say, String actionType, String actionValue) {
        public boolean hasAction() {
            return actionType != null && !actionType.isBlank() && !"NONE".equalsIgnoreCase(actionType);
        }
    }

    private Brain() {
    }

    public static String systemPrompt(McBot plugin, List<String> allowedSounds, List<String> allowedCommands) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一台《我的世界》生存服务器里的一个看不见的存在，玩家叫你「回声」。\n");
        sb.append("玩家会用 @yl 或 @幽灵 来叫你，这两个词指的就是你自己，不是别的玩家。\n");
        sb.append("定位：偶尔正经帮忙，多数时候嘴贫、爱捉弄人，但从不真正伤害玩家。\n");
        sb.append("性格：有点欠、爱看热闹、偶尔阴阳但不过分，像个住在服务器里的老玩家。\n");
        sb.append("硬性规则：\n");
        sb.append("1. 只用中文，不超过 40 个字，不要用引号包住整句话，不要重复你的名字。\n");
        sb.append("2. 不骂人、不涉及现实政治与人身攻击、不提及其他服务器。\n");
        sb.append("3. 不给玩家发物品、不传送、不透露管理指令。被问到就糊弄过去。\n");
        sb.append("4. 如果玩家问服务器信息（人数、时间、天气、版本），照实回答。\n");
        sb.append("5. 输出只能是下面两行，不要有别的内容、不要用 Markdown。\n");
        sb.append("第一行：你要说的话。\n");
        sb.append("第二行：ACTION: 动作。动作只能是下列之一：\n");
        sb.append("  ACTION: NONE\n");
        if (!allowedSounds.isEmpty()) {
            sb.append("  ACTION: SOUND <音效>，音效只能从这里选：").append(String.join(", ", allowedSounds)).append('\n');
            sb.append("  注意 SOUND 这个词不能省，正确写法例如：ACTION: SOUND ").append(allowedSounds.get(0)).append('\n');
        }
        if (!allowedCommands.isEmpty()) {
            sb.append("  ACTION: TITLE <文字>（在玩家屏幕中央闪一行字）\n");
            sb.append("  ACTION: COMMAND <指令>，指令只能以这些词开头：")
              .append(String.join(", ", allowedCommands)).append('\n');
        }
        sb.append("动作是可选的，多数时候用 NONE。");
        return sb.toString();
    }

    public static String userPrompt(String trigger, String context) {
        return "场景：" + trigger + "\n服务器当前情况：" + context + "\n请给出你的回应。";
    }

    /** 解析模型输出：找到 ACTION: 行，其余内容作为台词。 */
    public static Reply parse(String raw) {
        if (raw == null) {
            return new Reply("", "NONE", "");
        }
        String text = raw.replace("```", "").trim();
        String actionType = "NONE";
        String actionValue = "";
        StringBuilder say = new StringBuilder();

        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (upper.startsWith("ACTION:") || upper.startsWith("动作:") || upper.startsWith("动作：")) {
                String body = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                if (body.isEmpty() && upper.contains("：")) {
                    body = trimmed.substring(trimmed.indexOf('：') + 1).trim();
                }
                int space = body.indexOf(' ');
                if (space < 0) {
                    actionType = body.trim().toUpperCase(Locale.ROOT);
                    actionValue = "";
                } else {
                    actionType = body.substring(0, space).trim().toUpperCase(Locale.ROOT);
                    actionValue = body.substring(space + 1).trim();
                }
            } else {
                if (say.length() > 0) {
                    say.append(' ');
                }
                say.append(trimmed);
            }
        }

        String sayText = say.toString().replaceAll("^[\"“”'']+|[\"“”'']+$", "").trim();
        if (sayText.isEmpty() && "NONE".equalsIgnoreCase(actionType)) {
            sayText = text.replaceAll("\\s+", " ").trim();
        }
        if (sayText.length() > 80) {
            sayText = sayText.substring(0, 80);
        }
        return new Reply(sayText, actionType, actionValue);
    }

    /** 兜底：AI 不可用时，用本地台词 + 随机动作。 */
    public static Reply local(List<String> lines, List<String> sounds, java.util.Random random) {
        String line = lines.isEmpty() ? "……" : lines.get(random.nextInt(lines.size()));
        if (!sounds.isEmpty() && random.nextDouble() < 0.45) {
            return new Reply(line, "SOUND", sounds.get(random.nextInt(sounds.size())));
        }
        return new Reply(line, "NONE", "");
    }
}
