package com.lain.ffmpegmod;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class FFmpegConfig {
    public static final FFmpegConfig INSTANCE;
    public static final ModConfigSpec SPEC;

    public final ModConfigSpec.ConfigValue<String> ffmpegPath;
    public final ModConfigSpec.IntValue timeoutSeconds;
    public final ModConfigSpec.IntValue maxOutputLines;

    static {
        Pair<FFmpegConfig, ModConfigSpec> pair =
                new ModConfigSpec.Builder().configure(FFmpegConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    private FFmpegConfig(ModConfigSpec.Builder builder) {
        ffmpegPath = builder
                .comment("FFmpeg 可执行文件路径。可以是 'ffmpeg'（如果在系统 PATH 中），",
                        "或完整路径，例如 'C:/ffmpeg/bin/ffmpeg.exe' 或 '/usr/bin/ffmpeg'")
                .define("ffmpegPath", "ffmpeg");

        timeoutSeconds = builder
                .comment("FFmpeg 执行超时时间（秒）。超时后会强制终止进程。")
                .defineInRange("timeoutSeconds", 300, 10, 3600);

        maxOutputLines = builder
                .comment("最多回显到聊天栏的输出行数，避免刷屏。")
                .defineInRange("maxOutputLines", 50, 5, 500);
    }
}
