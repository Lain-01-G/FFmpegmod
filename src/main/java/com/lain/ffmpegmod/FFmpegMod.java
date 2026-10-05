package com.lain.ffmpegmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mod(FFmpegMod.MODID)
public class FFmpegMod {
    public static final String MODID = "ffmpegmod";

    public FFmpegMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, FFmpegConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        // 创建 ffmpeg 文件夹
        try {
            Path ffmpegDir = FMLPaths.GAMEDIR.get().resolve("ffmpeg");
            if (!Files.exists(ffmpegDir)) {
                Files.createDirectories(ffmpegDir);
            }
        } catch (IOException e) {
            System.err.println("[FFmpegMod] 无法创建 ffmpeg 文件夹: " + e.getMessage());
        }

        // 软依赖 CC: Tweaked：只有装了才注册 API，避免类加载错误
        if (ModList.get().isLoaded("computercraft")) {
            registerCCAAPI();
        }
    }

    private static void registerCCAAPI() {
        dan200.computercraft.api.ComputerCraftAPI.registerAPIFactory(
                computer -> new FFmpegAPI());
    }

    // ======================== 命令注册 ========================

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                Commands.literal("ffmpeg")
                        .requires(source -> source.hasPermission(2)) // 仅 OP
                        .then(Commands.literal("help")
                                .executes(this::executeHelp))
                        .then(Commands.literal("version")
                                .executes(this::executeVersion))
                        // /ffmpeg files - 列出实际文件名
                        .then(Commands.literal("files")
                                .executes(this::executeFiles))
                        // /ffmpeg dfpwm "<输入>" "<输出>"
                        .then(Commands.literal("dfpwm")
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .executes(this::executeDfpwm)))
                        // /ffmpeg exec <参数>
                        .then(Commands.literal("exec")
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .executes(this::executeRaw)))
        );
    }

    // ======================== 子命令实现 ========================

    private int executeHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§9===== FFmpeg Mod 帮助 ====="), false);
        source.sendSuccess(() -> Component.literal("§3/ffmpeg version §7- 检查 FFmpeg 是否可用"), false);
        source.sendSuccess(() -> Component.literal("§3/ffmpeg files §7- 列出 ffmpeg 文件夹中的实际文件名（点击复制）"), false);
        source.sendSuccess(() -> Component.literal("§e  ★ 所有文件名必须用双引号包裹（即使没有空格）"), false);
        source.sendSuccess(() -> Component.literal("§3/ffmpeg dfpwm <输入> <输出> §7- 将音频转为 DFPWM"), false);
        source.sendSuccess(() -> Component.literal("§7  示例: /ffmpeg dfpwm \"music.mp3\" \"music.dfpwm\""), false);
        source.sendSuccess(() -> Component.literal("§7  示例: /ffmpeg dfpwm \"江南.flac\" \"江南.dfpwm\""), false);
        source.sendSuccess(() -> Component.literal("§7  输入文件不存在时，会自动列出实际文件名，点击即可复制"), false);
        source.sendSuccess(() -> Component.literal("§3/ffmpeg exec <参数> §7- 执行任意 FFmpeg 命令"), false);
        source.sendSuccess(() -> Component.literal("§7  示例: /ffmpeg exec -i \"江南.flac\" -c:a libmp3lame -q:a 2 \"江南.mp3\""), false);
        source.sendSuccess(() -> Component.literal("§7  exec 会自动检查 -i 后面的输入文件是否存在"), false);
        source.sendSuccess(() -> Component.literal("§7所有文件操作都在游戏目录下的 §6ffmpeg §7文件夹内进行。"), false);
        source.sendSuccess(() -> Component.literal("§9========================="), false);
        return 1;
    }

    private int executeVersion(CommandContext<CommandSourceStack> ctx) {
        runFFmpegAsync(ctx.getSource(), List.of(ffmpegPath(), "-version"));
        return 1;
    }

    private int executeFiles(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Path ffmpegDir = FMLPaths.GAMEDIR.get().resolve("ffmpeg");
        if (!Files.exists(ffmpegDir)) {
            source.sendFailure(Component.literal("§cffmpeg 文件夹不存在。"));
            return 0;
        }
        listActualFiles(source, ffmpegDir);
        return 1;
    }

    private int executeDfpwm(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String args = StringArgumentType.getString(ctx, "args");

        List<String> parts = parseQuotedArgs(args);
        if (parts == null) {
            source.sendFailure(Component.literal("§c所有文件名必须用双引号包裹。"));
            source.sendFailure(Component.literal("§c示例: /ffmpeg dfpwm \"music.mp3\" \"music.dfpwm\""));
            return 0;
        }
        if (parts.size() != 2) {
            source.sendFailure(Component.literal("§c需要两个文件名参数（输入和输出）。"));
            source.sendFailure(Component.literal("§c示例: /ffmpeg dfpwm \"music.mp3\" \"music.dfpwm\""));
            return 0;
        }

        String input  = parts.get(0);
        String output = parts.get(1);

        if (!isSafePath(input) || !isSafePath(output)) {
            source.sendFailure(Component.literal("§c路径包含非法字符。"));
            return 0;
        }
        if (input.equalsIgnoreCase(output)) {
            source.sendFailure(Component.literal("§c输入文件和输出文件不能相同"));
            return 0;
        }

        Path ffmpegDir = FMLPaths.GAMEDIR.get().resolve("ffmpeg");
        Path inputPath  = ffmpegDir.resolve(input).normalize();
        Path outputPath = ffmpegDir.resolve(output).normalize();

        if (!inputPath.startsWith(ffmpegDir) || !outputPath.startsWith(ffmpegDir)) {
            source.sendFailure(Component.literal("§c路径必须位于 ffmpeg 文件夹内"));
            return 0;
        }
        if (!Files.exists(inputPath) || !Files.isRegularFile(inputPath)) {
            source.sendFailure(Component.literal("§c输入文件不存在或不是普通文件: " + input));
            listActualFiles(source, ffmpegDir);
            return 0;
        }

        List<String> command = List.of(
                ffmpegPath(), "-y",
                "-i", inputPath.toString(),
                "-ac", "1", "-ar", "48000", "-f", "dfpwm",
                outputPath.toString()
        );

        runFFmpegAsync(source, command);
        return 1;
    }

    private int executeRaw(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String rawArgs = StringArgumentType.getString(ctx, "args");

        List<String> argList;
        try {
            argList = parseArgs(rawArgs);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("§c参数解析失败: " + e.getMessage()));
            return 0;
        }
        if (argList.isEmpty()) {
            source.sendFailure(Component.literal("§c参数不能为空"));
            return 0;
        }

        // 安全校验：禁止路径穿越、绝对路径、UNC 路径
        for (String arg : argList) {
            for (String part : arg.split("[\\\\/]")) {
                if (part.equals("..")) {
                    source.sendFailure(Component.literal("§c参数中不允许包含路径穿越 '..': " + arg));
                    return 0;
                }
            }
            if (arg.startsWith("/")
                    || arg.startsWith("\\\\")
                    || arg.matches("^[A-Za-z]:[\\\\/].*")) {
                source.sendFailure(Component.literal("§c参数中不允许包含绝对路径: " + arg));
                return 0;
            }
        }

        // ============ 严格预检：找出 -i 后面的输入文件，必须全部存在 ============
        Path ffmpegDir = FMLPaths.GAMEDIR.get().resolve("ffmpeg");
        List<String> inputs = new ArrayList<>();
        boolean nextIsInput = false;
        for (String arg : argList) {
            if (arg.equals("-i")) {
                nextIsInput = true;
                continue;
            }
            if (nextIsInput) {
                inputs.add(arg);
                nextIsInput = false;
            }
        }

        if (!inputs.isEmpty()) {
            if (!Files.exists(ffmpegDir)) {
                source.sendFailure(Component.literal("§cffmpeg 文件夹不存在。"));
                return 0;
            }
            List<String> missing = new ArrayList<>();
            for (String input : inputs) {
                // 跳过 URL（http:// https:// rtmp:// 等）
                if (input.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) continue;
                Path p = ffmpegDir.resolve(input).normalize();
                if (!p.startsWith(ffmpegDir) || !Files.exists(p) || !Files.isRegularFile(p)) {
                    missing.add(input);
                }
            }
            if (!missing.isEmpty()) {
                source.sendFailure(Component.literal("§c以下输入文件在 ffmpeg 文件夹中不存在："));
                for (String m : missing) {
                    source.sendFailure(Component.literal("§c  " + m));
                }
                listActualFiles(source, ffmpegDir);
                return 0;   // 直接拒绝执行
            }
        }
        // ===================================================================

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath());
        command.addAll(argList);

        runFFmpegAsync(source, command);
        return 1;
    }

    // ======================== 工具方法 ========================

    private static String ffmpegPath() {
        String p = FFmpegConfig.INSTANCE.ffmpegPath.get();
        return (p == null || p.isBlank()) ? "ffmpeg" : p;
    }

    /**
     * 路径安全校验：黑名单策略。
     * 只拒绝路径穿越、绝对路径、控制字符、Windows 保留字符。
     * 允许中文标点（如 、）、空格、括号等。
     */
    private static boolean isSafePath(String path) {
        if (path == null || path.isEmpty()) return false;

        for (String part : path.split("[\\\\/]")) {
            if (part.equals("..")) return false;
        }
        if (path.startsWith("/")
                || path.startsWith("\\\\")
                || path.matches("^[A-Za-z]:[\\\\/].*")) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 && c != '\t') return false;
        }
        if (path.matches(".*[<>:\"|?*].*")) return false;

        return true;
    }

    /**
     * 解析带双引号的参数，返回引号内的内容列表。
     * 要求：所有非空白内容都必须被双引号包裹。
     */
    private static List<String> parseQuotedArgs(String input) {
        List<String> result = new ArrayList<>();
        Pattern pattern = Pattern.compile("\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(input);
        int lastEnd = 0;

        while (matcher.find()) {
            String between = input.substring(lastEnd, matcher.start());
            if (!between.isBlank()) {
                return null;
            }
            result.add(matcher.group(1));
            lastEnd = matcher.end();
        }

        String trailing = input.substring(lastEnd);
        if (!trailing.isBlank()) {
            return null;
        }
        return result;
    }

    /**
     * 把带引号的字符串拆分成参数列表（用于 exec 子命令）。
     */
    private static List<String> parseArgs(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (inQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == quoteChar) {
                    inQuote = false;
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"' || c == '\'') {
                    inQuote = true;
                    quoteChar = c;
                } else if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        result.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
        }
        if (inQuote) {
            throw new IllegalArgumentException("引号未闭合");
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * 列出 ffmpeg 文件夹里的实际文件名。
     * 点击文件名会复制「"文件名"」（带引号）到剪贴板。
     */
    private void listActualFiles(CommandSourceStack source, Path ffmpegDir) {
        try (var stream = Files.list(ffmpegDir)) {
            List<String> names = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .forEach(p -> names.add(p.getFileName().toString()));

            if (names.isEmpty()) {
                source.sendFailure(Component.literal("§7ffmpeg 文件夹里没有任何文件。"));
                return;
            }

            source.sendSuccess(() -> Component.literal(
                    "§effmpeg 文件夹里的实际文件名（点击复制到剪贴板）："), false);

            for (String name : names) {
                // 用引号包裹文件名，内部引号转义
                String quoted = "\"" + name.replace("\"", "\\\"") + "\"";

                Component line = Component.literal("§7  §a" + name)
                        .withStyle(style -> style
                                .withClickEvent(new ClickEvent(
                                        ClickEvent.Action.COPY_TO_CLIPBOARD, quoted))
                                .withHoverEvent(new HoverEvent(
                                        HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("§e点击复制：§7" + quoted))));

                source.sendSuccess(() -> line, false);
            }
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c无法列出文件夹: " + e.getMessage()));
        }
    }

    /**
     * 异步执行 FFmpeg 命令，并把结果回显到命令发送者的聊天栏。
     */
    private void runFFmpegAsync(CommandSourceStack source, List<String> command) {
        int timeout = FFmpegConfig.INSTANCE.timeoutSeconds.get();
        int maxLines = FFmpegConfig.INSTANCE.maxOutputLines.get();

        source.sendSuccess(() -> Component.literal(
                "§e[FFmpeg] 执行: §7" + String.join(" ", command)), false);

        CompletableFuture.runAsync(() -> {
            Process process = null;
            List<String> outputLines = Collections.synchronizedList(new ArrayList<>());
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.environment().put("LANG", "zh_CN.UTF-8");
                pb.environment().put("LC_ALL", "zh_CN.UTF-8");
                pb.directory(FMLPaths.GAMEDIR.get().resolve("ffmpeg").toFile());
                pb.redirectErrorStream(true);
                process = pb.start();
                final Process proc = process;

                Thread readerThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        int count = 0;
                        while ((line = reader.readLine()) != null) {
                            if (count < maxLines) {
                                outputLines.add(line);
                                count++;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }, "FFmpeg-Cmd-Reader");
                readerThread.setDaemon(true);
                readerThread.start();

                boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    proc.waitFor(5, TimeUnit.SECONDS);
                    readerThread.join(2000);
                    source.getServer().execute(() -> source.sendFailure(
                            Component.literal("§c[FFmpeg] 执行超时（>" + timeout + "s），已强制终止")));
                    return;
                }

                readerThread.join(5000);

                int exitCode = proc.exitValue();
                List<String> finalLines = new ArrayList<>(outputLines);
                source.getServer().execute(() -> {
                    Component status = exitCode == 0
                            ? Component.literal("§a[FFmpeg] 执行成功 (退出码 0)")
                            : Component.literal("§c[FFmpeg] 执行失败 (退出码 " + exitCode + ")");
                    source.sendSuccess(() -> status, false);

                    for (String line : finalLines) {
                        source.sendSuccess(() -> Component.literal("§7" + line), false);
                    }
                    if (finalLines.size() >= maxLines) {
                        source.sendSuccess(() -> Component.literal("§7... (输出已截断)"), false);
                    }
                });

            } catch (Exception e) {
                if (process != null) process.destroyForcibly();
                source.getServer().execute(() -> source.sendFailure(
                        Component.literal("§c[FFmpeg] 异常: " + e.getMessage())));
            }
        });
    }
}