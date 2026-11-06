package com.agentplatform.core;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构门禁（docs/framework-plan.md §2.4 Rule 1 / Rule 2）。
 *
 * <p>这两条规则是「框架不是 Spring AI 的增值包」与「平台只是框架的消费者」的
 * **结构保证**——不靠纪律，靠构建失败。</p>
 */
@AnalyzeClasses(packages = "com.agentplatform", importOptions = ImportOption.DoNotIncludeTests.class)
class FrameworkArchitectureTest {

    /**
     * Rule 1：契约层与运行时不得依赖 Spring / JPA / Spring AI。
     *
     * <p>一旦有人图省事在 api 里 {@code @Autowired} 或直接 new 一个
     * {@code ChatClient}，这条会立刻红——而不是等到某天有人说"这个框架怎么拖了一堆依赖"。</p>
     */
    @ArchTest
    static final ArchRule 契约层不得依赖Spring或JPA或SpringAI = noClasses()
            .that().resideInAPackage("com.agentplatform.framework.api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "com.mysql..",
                    "org.flywaydb..");

    /** 运行时同理：它只认 api + reactor + jackson + slf4j。 */
    @ArchTest
    static final ArchRule 运行时不得依赖Spring或JPA或SpringAI = noClasses()
            .that().resideInAPackage("com.agentplatform.framework.runtime..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "com.mysql..",
                    "org.flywaydb..");

    /**
     * Rule 2：契约层不得反向依赖运行时或平台。
     *
     * <p>依赖方向只能是「平台 → 运行时 → api」；api 若 import 了 core 的类，
     * 嵌入方就被迫把整个平台一起拖进来。</p>
     */
    @ArchTest
    static final ArchRule 契约层不得依赖运行时与平台 = noClasses()
            .that().resideInAPackage("com.agentplatform.framework.api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.agentplatform.framework.runtime..",
                    "com.agentplatform.core..",
                    "com.agentplatform.model..");
}
