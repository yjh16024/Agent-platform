package com.agentplatform.core.tool.action;

import com.agentplatform.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 命令执行器 —— 「项目动作」这条链路的**唯一执行出口**。
 *
 * <h3>为什么与"自由 shell"不同（这是本次设计的关键）</h3>
 * 命令行**完全由平台或用户在配置里写好**，模型只能选择跑哪个动作、最多填几个受限参数。
 * 也就是说 risks 不是被"隔离"掉的，而是**根本不产生** ——
 * 没有"模型即兴拼一条命令"这个环节，也就没有"白名单挡不住参数"那个问题。
 *
 * <h3>⚠️ 但它不是零风险，这一点必须清楚</h3>
 * 动作执行的是**项目里已有的构建/测试配置**：{@code mvn test} 会编译并运行仓库里的测试代码，
 * {@code npm test} 会执行 {@code package.json} 里的 script。那些同样是"代码"。
 * 所以它的真实边界是：**把执行面限定在"已经在仓库里的东西"**，
 * 而不是"模型这一刻想到的命令"。要执行陌生人给的仓库，仍需更硬的隔离。
 *
 * <h3>执行层面的四件事（照 {@code SandboxMcpClient} 的成熟做法）</h3>
 * <ul>
 *   <li><b>超时强杀</b>：构建命令卡住是常事（等待输入、端口占用），必须能终止进程而不是干等；</li>
 *   <li><b>输出截断</b>：一次失败的构建能吐几万行，原样回灌会把模型上下文冲掉 ——
 *       而真正有用的信息通常在**末尾**（错误摘要、失败用例列表），所以截断保留**尾部**；</li>
 *   <li><b>环境最小化</b>：只保留 PATH 等必要变量，不把宿主的密钥带进子进程；</li>
 *   <li><b>stdout 与 stderr 合并</b>：构建工具的错误常走 stderr，分开收会让模型看不到关键报错。</li>
 * </ul>
 */
@Slf4j
@Component
public class CommandRunner {

    /** 单次输出的字符上限（截断保留**尾部**，见类注释）。 */
    @Value("${agent-platform.tool.actions.max-output-chars:16000}")
    private int maxOutputChars = 16000;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /**
     * 执行一条命令。
     *
     * @param commandLine 命令与参数（**已由平台/配置确定**，不含模型自由输入）
     * @param workingDir  工作目录（调用方负责确认它是工作区根）
     * @param timeoutSec  超时秒数
     */
    public Outcome run(List<String> commandLine, Path workingDir, long timeoutSec) {
        if (commandLine == null || commandLine.isEmpty()) {
            throw BizException.badRequest("动作没有可执行的命令");
        }
        if (workingDir == null || !Files.isDirectory(workingDir)) {
            throw BizException.badRequest("工作目录不存在：" + workingDir
                    + "（请先配置 agent-platform.agent.workspace.root 指向你的项目）");
        }

        List<String> actual = wrapForPlatform(commandLine);
        ProcessBuilder pb = new ProcessBuilder(actual);
        pb.directory(workingDir.toFile());
        // 合并 stderr：构建失败的关键信息经常只在 stderr 上
        pb.redirectErrorStream(true);
        minimizeEnvironment(pb);

        long timeout = timeoutSec <= 0 ? 300L : timeoutSec;
        long startedAt = System.currentTimeMillis();
        try {
            Process process = pb.start();
            String output;
            // 必须先读完输出再 waitFor，否则子进程可能因管道写满而阻塞（经典死锁）
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Outcome(-1, truncate(output), true, System.currentTimeMillis() - startedAt);
            }
            return new Outcome(process.exitValue(), truncate(output), false,
                    System.currentTimeMillis() - startedAt);
        } catch (IOException e) {
            throw BizException.internal("命令启动失败（" + String.join(" ", commandLine)
                    + "）：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.internal("命令执行被中断：" + String.join(" ", commandLine));
        }
    }

    /**
     * Windows 上要经 {@code cmd.exe /c} 启动。
     *
     * <p>因为 {@code ProcessBuilder} **不会**自动补扩展名：{@code new ProcessBuilder("mvn", ...)}
     * 在 Windows 上找不到 {@code mvn.cmd}，直接报"系统找不到指定的文件"——
     * 而这类报错很容易被误读成"用户没装 maven"。</p>
     *
     * <p>安全性由上游保证：命令来自平台常量或用户配置，参数经过字符白名单校验，
     * 因此经 cmd 解析不会引入注入面。</p>
     *
     * <p><b>副作用（实测于 2026-09-24）</b>：经 cmd 包装后，"命令不存在"**不再表现为启动失败** ——
     * cmd 会自己返回非零退出码并打印 {@code 'xxx' 不是内部或外部命令}。
     * 对模型来说这反而更好（它能看到具体是哪个命令没找到）。
     * 而 Unix 上没有这层壳，{@code ProcessBuilder} 会直接抛 {@code IOException}。
     * 两种表现都被处理成"可读的失败"，但**断言必须容忍这两种**
     * （见 {@code ProjectActionTest#runnerReportsMissingCommand}）。</p>
     */
    private static List<String> wrapForPlatform(List<String> commandLine) {
        if (!WINDOWS) {
            return commandLine;
        }
        List<String> out = new ArrayList<>(commandLine.size() + 2);
        out.add("cmd.exe");
        out.add("/c");
        out.addAll(commandLine);
        return out;
    }

    /** 最小化环境：只留必要变量，避免把宿主敏感环境变量带进构建进程。 */
    private static void minimizeEnvironment(ProcessBuilder pb) {
        try {
            pb.environment().keySet().removeIf(k -> !k.equalsIgnoreCase("PATH")
                    && !k.equalsIgnoreCase("SystemRoot") && !k.equalsIgnoreCase("TEMP")
                    && !k.equalsIgnoreCase("TMP") && !k.equalsIgnoreCase("LANG")
                    && !k.equalsIgnoreCase("HOME") && !k.equalsIgnoreCase("USERPROFILE")
                    && !k.equalsIgnoreCase("PATHEXT") && !k.equalsIgnoreCase("ComSpec")
                    && !k.equalsIgnoreCase("JAVA_HOME") && !k.equalsIgnoreCase("APPDATA")
                    && !k.equalsIgnoreCase("LOCALAPPDATA"));
        } catch (UnsupportedOperationException e) {
            log.debug("[action] 当前 JDK 不支持精简子进程环境变量，沿用继承环境：{}", e.getMessage());
        }
    }

    /** 截断**保留尾部**：失败摘要通常在最后几行，掐头比掐尾有用。 */
    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        int max = Math.max(1000, maxOutputChars);
        if (text.length() <= max) {
            return text;
        }
        int cut = text.length() - max;
        return "…（前方省略 " + cut + " 字符）\n" + text.substring(cut);
    }

    /**
     * 执行结果。
     *
     * @param exitCode  退出码（{@code -1} 表示超时被杀）
     * @param output    合并后的输出（已截断）
     * @param timedOut  是否因超时被强杀
     * @param elapsedMs 耗时
     */
    public record Outcome(int exitCode, String output, boolean timedOut, long elapsedMs) {

        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }
}
