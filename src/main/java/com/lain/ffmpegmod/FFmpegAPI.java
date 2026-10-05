package com.lain.ffmpegmod;

import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class FFmpegAPI implements ILuaAPI {

    private static final int TIMEOUT_SECONDS = 300;
    private static final int MAX_OUTPUT_LINES = 100;
    private static final int MAX_CONCURRENT_TASKS = 8;
    private static final long TASK_RETENTION_MS = 5 * 60 * 1000L;
    private static final Object TASK_LOCK = new Object();

    // ==================== 任务信息 ====================

    private static class TaskInfo {
        final String taskId;
        volatile String status = "running";
        volatile List<String> output = List.of();
        final long createdAt = System.currentTimeMillis();
        volatile long completedAt = 0L;

        TaskInfo(String taskId) {
            this.taskId = taskId;
        }
    }

    private static final Map<String, TaskInfo> tasks = new ConcurrentHashMap<>();

    private static TaskInfo createTask() throws LuaException {
        synchronized (TASK_LOCK) {
            cleanupOldTasks();
            long running = tasks.values().stream()
                    .filter(t -> t.completedAt == 0L)
                    .count();
            if (running >= MAX_CONCURRENT_TASKS) {
                throw new LuaException(msg("并发任务过多（上限 " + MAX_CONCURRENT_TASKS + "），请等待现有任务完成"));
            }
            String taskId = UUID.randomUUID().toString();
            TaskInfo info = new TaskInfo(taskId);
            tasks.put(taskId, info);
            return info;
        }
    }

    private static void cleanupOldTasks() {
        long now = System.currentTimeMillis();
        tasks.entrySet().removeIf(e -> {
            TaskInfo t = e.getValue();
            return t.completedAt > 0 && (now - t.completedAt) > TASK_RETENTION_MS;
        });
    }

    private static TaskInfo getTask(String taskId) throws LuaException {
        TaskInfo info = tasks.get(taskId);
        if (info == null) throw new LuaException(msg("任务不存在或已过期"));
        return info;
    }

    // ==================== ILuaAPI ====================

    @Override
    public String getModuleName() { return "ffmpeg"; }

    @Override
    public String[] getNames() { return new String[0]; }

    // ==================== 文件名/消息编码 ====================

    private static String encodeName(String name) {
        // 把 + 再替换回 %20，避免和真实加号混淆
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String msg(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    /**
     * 尝试对字符串做 URL 解码。
     * 只对看起来包含 %XX 的字符串解码；普通字符串（如 -i、libmp3lame、2）原样返回。
     */
    private static String tryUrlDecode(String s) {
        if (s == null || s.isEmpty()) return s;
        // 含 %XX 或 + 都尝试解码
        if (!s.matches(".*(%[0-9A-Fa-f]{2}|\\+).*")) {
            return s;
        }
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static String decodeName(String encoded) throws LuaException {
        if (encoded == null || encoded.isEmpty()) {
            throw new LuaException(msg("文件名不能为空"));
        }
        String name;
        try {
            name = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new LuaException(msg("文件名解码失败: " + e.getMessage()));
        }
        if (name.isEmpty()) {
            throw new LuaException(msg("文件名不能为空"));
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new LuaException(msg("文件名包含非法字符"));
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 && c != '\t') {
                throw new LuaException(msg("文件名包含非法控制字符"));
            }
        }
        return name;
    }

    // ==================== Lua 可调用方法 ====================

    @LuaFunction
    public final String convertAsync(String inputEncoded, String outputEncoded) throws LuaException {
        String inputFile = decodeName(inputEncoded);
        String outputFile = decodeName(outputEncoded);

        if (inputFile.equalsIgnoreCase(outputFile)) {
            throw new LuaException(msg("输入文件和输出文件不能相同"));
        }

        Path ffmpegDir = FMLPaths.GAMEDIR.get().resolve("ffmpeg");
        if (!Files.exists(ffmpegDir)) {
            try {
                Files.createDirectories(ffmpegDir);
            } catch (Exception e) {
                throw new LuaException(msg("无法创建 ffmpeg 文件夹: " + e.getMessage()));
            }
        }

        Path inputPath = ffmpegDir.resolve(inputFile).normalize();
        Path outputPath = ffmpegDir.resolve(outputFile).normalize();

        if (!inputPath.startsWith(ffmpegDir) || !outputPath.startsWith(ffmpegDir)) {
            throw new LuaException(msg("路径必须位于 ffmpeg 文件夹内"));
        }
        if (!Files.exists(inputPath) || !Files.isRegularFile(inputPath)) {
            throw new LuaException(msg("输入文件不存在或不是普通文件: " + inputFile));
        }
        if (Files.exists(outputPath) && Files.isDirectory(outputPath)) {
            throw new LuaException(msg("输出路径已存在且是文件夹: " + outputFile));
        }

        List<String> command = List.of(
                FFmpegConfig.INSTANCE.ffmpegPath.get(),
                "-y", "-i", inputPath.toString(),
                "-ac", "1", "-ar", "48000", "-f", "dfpwm",
                outputPath.toString()
        );

        TaskInfo task = createTask();
        Thread t = new Thread(() -> executeFfmpeg(task, command, ffmpegDir), "FFmpeg-Convert");
        t.setDaemon(true);
        t.start();
        return task.taskId;
    }

    @LuaFunction
    public final String execAsync(String args) throws LuaException {
        if (args == null || args.trim().isEmpty()) {
            throw new LuaException(msg("参数不能为空"));
        }

        List<String> argList = parseArgs(args);
        if (argList.isEmpty()) {
            throw new LuaException(msg("参数不能为空"));
        }

        // 安全校验：对解码前的参数检查路径穿越和绝对路径
        for (String arg : argList) {
            for (String part : arg.split("[\\\\/]")) {
                if (part.equals("..")) {
                    throw new LuaException(msg("参数中不允许包含路径穿越 '..': " + arg));
                }
            }
            if (arg.startsWith("/")
                    || arg.startsWith("\\\\")
                    || arg.matches("^[A-Za-z]:[\\\\/].*")) {
                throw new LuaException(msg("参数中不允许包含绝对路径: " + arg));
            }
        }

        // ★ 新增：对每个参数尝试 URL 解码
        //   Lua 端通过 listFiles() 拿到的是 URL 编码的文件名，
        //   传给 FFmpeg 前必须还原成真实文件名。
        List<String> decodedArgs = new ArrayList<>(argList.size());
        for (String arg : argList) {
            decodedArgs.add(tryUrlDecode(arg));
        }

        // 用解码后的参数重新做一次安全检查（防止 %2E%2E 绕过）
        for (String arg : decodedArgs) {
            for (String part : arg.split("[\\\\/]")) {
                if (part.equals("..")) {
                    throw new LuaException(msg("参数中不允许包含路径穿越 '..': " + arg));
                }
            }
            if (arg.startsWith("/")
                    || arg.startsWith("\\\\")
                    || arg.matches("^[A-Za-z]:[\\\\/].*")) {
                throw new LuaException(msg("参数中不允许包含绝对路径: " + arg));
            }
        }

        // 找出 -i 后面的输入文件，检查是否存在
        Path ffmpegDir = FMLPaths.GAMEDIR.get().resolve("ffmpeg");
        List<String> inputs = new ArrayList<>();
        boolean nextIsInput = false;
        for (String arg : decodedArgs) {
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
                throw new LuaException(msg("ffmpeg 文件夹不存在"));
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
                StringBuilder sb = new StringBuilder("以下输入文件不存在: ");
                sb.append(String.join(", ", missing));
                throw new LuaException(msg(sb.toString()));
            }
        }

        // 用解码后的参数拼命令
        List<String> command = new ArrayList<>();
        command.add(FFmpegConfig.INSTANCE.ffmpegPath.get());
        command.addAll(decodedArgs);

        if (!Files.exists(ffmpegDir)) {
            try {
                Files.createDirectories(ffmpegDir);
            } catch (Exception e) {
                throw new LuaException(msg("无法创建 ffmpeg 文件夹: " + e.getMessage()));
            }
        }

        TaskInfo task = createTask();
        Thread t = new Thread(() -> executeFfmpeg(task, command, ffmpegDir), "FFmpeg-Exec");
        t.setDaemon(true);
        t.start();
        return task.taskId;
    }

    @LuaFunction
    public final String getStatus(String taskId) throws LuaException {
        return getTask(taskId).status;
    }

    @LuaFunction
    public final List<String> getOutput(String taskId) throws LuaException {
        return getTask(taskId).output;
    }

    @LuaFunction
    public final List<String> listFiles() throws LuaException {
        Path ffmpegDir = FMLPaths.GAMEDIR.get().resolve("ffmpeg");
        if (!Files.exists(ffmpegDir)) return List.of();
        try {
            List<String> names = new ArrayList<>();
            try (var stream = Files.list(ffmpegDir)) {
                stream.filter(Files::isRegularFile)
                        .forEach(p -> names.add(encodeName(p.getFileName().toString())));
            }
            names.sort(Comparator.naturalOrder());
            return names;
        } catch (Exception e) {
            throw new LuaException(msg("读取文件夹失败: " + e.getMessage()));
        }
    }

    @LuaFunction
    public final String version() throws LuaException {
        Path ffmpegDir = FMLPaths.GAMEDIR.get().resolve("ffmpeg");
        List<String> command = List.of(FFmpegConfig.INSTANCE.ffmpegPath.get(), "-version");
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(ffmpegDir.toFile());
            pb.redirectErrorStream(true);
            process = pb.start();

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new LuaException(msg("FFmpeg version 查询超时"));
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                    if (sb.length() > 200) break;
                }
            }
            return sb.toString().trim();
        } catch (LuaException e) {
            throw e;
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            throw new LuaException(msg("FFmpeg 不可用: " + e.getMessage()));
        }
    }

    @LuaFunction
    public final byte[] readFileChunk(String filenameEncoded, long offset, int length) throws LuaException {
        String filename = decodeName(filenameEncoded);
        if (length <= 0 || length > 65536) {
            throw new LuaException(msg("length 必须在 1 ~ 65536 之间"));
        }
        if (offset < 0) {
            throw new LuaException(msg("offset 不能为负数"));
        }

        Path ffmpegDir = FMLPaths.GAMEDIR.get().resolve("ffmpeg");
        Path file = ffmpegDir.resolve(filename).normalize();

        if (!file.startsWith(ffmpegDir)) {
            throw new LuaException(msg("路径必须位于 ffmpeg 文件夹内"));
        }
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new LuaException(msg("文件不存在或不是普通文件: " + filename));
        }

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long fileLen = raf.length();
            if (offset >= fileLen) {
                return new byte[0];
            }
            int toRead = (int) Math.min((long) length, fileLen - offset);
            byte[] result = new byte[toRead];
            raf.seek(offset);
            raf.readFully(result);
            return result;
        } catch (Exception e) {
            throw new LuaException(msg("读取文件失败: " + e.getMessage()));
        }
    }

    @LuaFunction
    public final long fileSize(String filenameEncoded) throws LuaException {
        String filename = decodeName(filenameEncoded);
        Path ffmpegDir = FMLPaths.GAMEDIR.get().resolve("ffmpeg");
        Path file = ffmpegDir.resolve(filename).normalize();
        if (!file.startsWith(ffmpegDir) || !Files.exists(file) || !Files.isRegularFile(file)) {
            throw new LuaException(msg("文件不存在或不是普通文件: " + filename));
        }
        try {
            return Files.size(file);
        } catch (Exception e) {
            throw new LuaException(msg("获取文件大小失败: " + e.getMessage()));
        }
    }

    // ==================== 内部执行器 ====================

    private static void executeFfmpeg(TaskInfo task, List<String> command, Path workDir) {
        Process process = null;
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            process = pb.start();
            final Process proc = process;

            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    int count = 0;
                    while ((line = reader.readLine()) != null) {
                        if (count < MAX_OUTPUT_LINES) {
                            lines.add(line);
                            count++;
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "FFmpeg-Reader");
            readerThread.setDaemon(true);
            readerThread.start();

            boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String finalStatus;
            if (!finished) {
                proc.destroyForcibly();
                proc.waitFor(5, TimeUnit.SECONDS);
                finalStatus = "failed: timeout";
            } else {
                finalStatus = proc.exitValue() == 0 ? "success" : "failed";
            }

            readerThread.join(5000);

            task.output = List.copyOf(lines);
            task.status = finalStatus;
            task.completedAt = System.currentTimeMillis();

        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            task.output = List.copyOf(lines);
            task.status = "failed: " + e.getMessage();
            task.completedAt = System.currentTimeMillis();
        }
    }

    // ==================== 参数解析 ====================

    private static List<String> parseArgs(String input) throws LuaException {
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
            throw new LuaException(msg("引号未闭合"));
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }
}