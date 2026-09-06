package com.agentplatform.core.skill;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Skills 示例种子（首次启动自动铺设）。
 * <p>
 * 因为运行时数据目录（{@code data/}）不随源码分发，新克隆的机器 skills 目录为空，
 * 用户看不到标准格式长什么样。本组件在启动时把内置的
 * {@code classpath:skills-sample/} 示例复制到 skills 根目录——
 * <b>仅在目录为空时复制</b>，绝不覆盖用户已有内容（包括用户删除示例后的状态，
 * 删除后若目录再次为空会重新复制，属可接受行为；如需彻底移除请关闭本组件或自行放置任意 Skill）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillSampleSeeder {

    /** 内置示例文件（classpath 相对路径 → skills 目录下的相对路径）。 */
    private static final List<String[]> SAMPLES = List.of(
            new String[]{"skills-sample/README.md", "README.md"},
            new String[]{"skills-sample/example-pdf-processing/SKILL.md", "example-pdf-processing/SKILL.md"}
    );

    private final SkillFileStore fileStore;

    @PostConstruct
    public void seed() {
        Path root = fileStore.root();
        try {
            Files.createDirectories(root);
            if (hasAnySkill()) {
                return;
            }
            for (String[] pair : SAMPLES) {
                copy(pair[0], root.resolve(pair[1]));
            }
            log.info("[skills] 已铺设示例 Skill 到 {}", root);
        } catch (Exception e) {
            // 示例铺设失败不影响主流程
            log.warn("[skills] 铺设示例 Skill 失败（不影响运行）: {}", e.getMessage());
        }
    }

    private boolean hasAnySkill() throws IOException {
        try (var dirs = Files.list(fileStore.root())) {
            return dirs.anyMatch(Files::isDirectory);
        }
    }

    private void copy(String classpath, Path target) throws IOException {
        ClassPathResource resource = new ClassPathResource(classpath);
        if (!resource.exists()) {
            return;
        }
        Files.createDirectories(target.getParent());
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
