package com.agentplatform.core.tool.action;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 项目类型探测 —— 决定「跑测试 / 构建」该用哪条命令。
 *
 * <h3>为什么需要它（而不让用户配命令）</h3>
 * 动作集的价值是"**开箱即用**"：把工作区指向一个 Maven 项目就该能跑 {@code mvn test}，
 * 指向 npm 项目就该能跑 {@code npm test}，不该先让人去写配置。
 * Maven / Gradle / npm / pytest / Go / Cargo 这几类占了绝大多数，
 * 靠**标志文件**判断既准又零成本（一次 {@code exists} 调用）。
 *
 * <h3>★ 关键：模型不参与这件事</h3>
 * 探测结果决定的是**平台常量里的命令**，模型只能选择"跑测试"或"跑构建"这个意图。
 * 如果让模型来判断"这个项目该用什么命令"，就等于又把"自由构造命令"这个风险源放回来了 ——
 * 那是本设计要消除的东西。
 *
 * <p>探测不到任何已知类型时返回空：此时不提供 {@code run_tests} / {@code run_build}，
 * 而不是猜一个命令（猜错的代价是让模型对着一堆"命令不存在"的报错打转）。</p>
 */
@Component
public class ProjectProbe {

    /**
     * 已知项目类型及其对应命令。
     *
     * <p>{@code -q}（quiet）不是装饰：构建工具默认会输出大量进度行，
     * 挤掉真正有用的错误信息。少输出反而让模型读得更准。</p>
     */
    public enum ProjectKind {
        MAVEN("pom.xml",
                List.of("mvn", "-q", "test"),
                List.of("mvn", "-q", "-DskipTests", "package")),
        GRADLE("build.gradle",
                List.of("gradle", "test", "--console=plain"),
                List.of("gradle", "build", "-x", "test", "--console=plain")),
        GRADLE_KTS("build.gradle.kts",
                List.of("gradle", "test", "--console=plain"),
                List.of("gradle", "build", "-x", "test", "--console=plain")),
        NPM("package.json",
                List.of("npm", "test", "--silent"),
                List.of("npm", "run", "build", "--silent")),
        PYTEST("pyproject.toml",
                List.of("python", "-m", "pytest", "-q"),
                List.of("python", "-m", "build")),
        GO("go.mod",
                List.of("go", "test", "./..."),
                List.of("go", "build", "./...")),
        CARGO("Cargo.toml",
                List.of("cargo", "test", "--quiet"),
                List.of("cargo", "build", "--quiet"));

        private final String marker;
        private final List<String> testCommand;
        private final List<String> buildCommand;

        ProjectKind(String marker, List<String> testCommand, List<String> buildCommand) {
            this.marker = marker;
            this.testCommand = testCommand;
            this.buildCommand = buildCommand;
        }

        /** 用于展示的项目类型名。 */
        public String label() {
            return name().toLowerCase().replace("_", "-");
        }

        public List<String> testCommand() {
            return testCommand;
        }

        public List<String> buildCommand() {
            return buildCommand;
        }
    }

    /** 探测工作区根的项目类型（按枚举声明顺序取第一个命中）。 */
    public Optional<ProjectKind> detect(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return Optional.empty();
        }
        for (ProjectKind kind : ProjectKind.values()) {
            if (Files.exists(root.resolve(kind.marker))) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
