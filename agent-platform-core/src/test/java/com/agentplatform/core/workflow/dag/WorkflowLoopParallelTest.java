package com.agentplatform.core.workflow.dag;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowDefinition;
import com.agentplatform.core.workflow.node.WorkflowNode;
import com.agentplatform.core.workflow.node.executor.ConditionNodeExecutor;
import com.agentplatform.core.workflow.node.executor.LoopNodeExecutor;
import com.agentplatform.core.workflow.node.executor.ParallelNodeExecutor;
import com.agentplatform.core.workflow.node.executor.StartEndNodeExecutor;
import com.agentplatform.core.workflow.node.executor.TransformNodeExecutor;
import com.agentplatform.core.workflow.schema.WorkflowSchemaValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loop / Parallel 节点的引擎行为测试（2026-09-26）。
 *
 * <h3>Loop 的图长什么样（理解本测试的前提）</h3>
 * <pre>
 *   loop_0 (Loop, config.loop_body = body_1)  ──next──▶  after
 *   body_1 ──next──▶ after
 * </pre>
 * 循环体入口写在 {@code config.loop_body} 而**不是** {@code next} —— 若用 {@code next} 指回自己，
 * 图就成了环，会被 {@code WorkflowSchemaValidator.hasCycle()} 在保存期拒掉。
 * 因此"循环体"是运行期概念：引擎从 {@code body_1} 沿 {@code next} 走，遇到 {@code after} 为止。
 */
class WorkflowLoopParallelTest {

    private DagEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DagEngine(
                List.of(new StartEndNodeExecutor(), new TransformNodeExecutor(),
                        new ConditionNodeExecutor(), new LoopNodeExecutor(), new ParallelNodeExecutor()),
                new WorkflowSchemaValidator());
    }

    /** Transform 节点的返回值是"解析后的 Map"，读取时要从 Map 里取字段。 */
    @SuppressWarnings("unchecked")
    private static Object field(Object varValue, String key) {
        assertNotNull(varValue, "变量 " + key + " 不存在");
        return ((Map<String, Object>) varValue).get(key);
    }

    // ---------------- Loop ----------------

    private WorkflowDefinition loopWorkflow(Map<String, Object> loopConfig) {
        return new WorkflowDefinition("loop", List.of(
                new WorkflowNode("start", NodeType.Start, null, "loop_0", Map.of("seed", 1), null, null, null),
                new WorkflowNode("loop_0", NodeType.Loop, null, "after", null, null, null, loopConfig),
                new WorkflowNode("body_1", NodeType.Transform, null, "after",
                        Map.of("round", "${i}", "seed_copy", "${seed}"), "body_out", null, null),
                new WorkflowNode("after", NodeType.End, null, null, null, null, null, null)
        ), null, null);
    }

    @Test
    @DisplayName("循环：无 while 条件时跑满 max_iterations 次")
    void loopRunsToMaxIterations() {
        WorkflowDefinition wf = loopWorkflow(new LinkedHashMap<>(Map.of(
                "loop_body", "body_1",
                "max_iterations", 3,
                "index_var", "i")));

        WorkflowContext ctx = engine.execute(wf, Map.of());

        // 每轮把当轮 i 写入 body_out；3 轮后应停在 i=2
        assertEquals(2, field(ctx.get("body_out"), "round"), "最后一轮的下标应为 max_iterations-1");
        // 循环体每轮都能读到上游变量（start 写的 seed）
        assertEquals(1, field(ctx.get("body_out"), "seed_copy"), "循环体内应能解析到循环外的变量");
    }

    @Test
    @DisplayName("循环：while 条件成立时提前退出（不必跑满上限）")
    void loopStopsWhenConditionFalse() {
        // i 从 0 起，条件 i < 2 ⇒ 应只跑 i=0、i=1 两轮
        WorkflowDefinition wf = loopWorkflow(new LinkedHashMap<>(Map.of(
                "loop_body", "body_1",
                "max_iterations", 10,
                "index_var", "i",
                "while", "${i} < 2")));

        WorkflowContext ctx = engine.execute(wf, Map.of());

        assertEquals(1, field(ctx.get("body_out"), "round"), "条件在 i=2 时不再成立，最后一轮是 i=1");
    }

    @Test
    @DisplayName("循环：max_iterations 是安全阀 —— 条件恒真也不会无限循环")
    void loopRespectsMaxIterationsEvenIfConditionAlwaysTrue() {
        WorkflowDefinition wf = loopWorkflow(new LinkedHashMap<>(Map.of(
                "loop_body", "body_1",
                "max_iterations", 4,
                "index_var", "i",
                "while", "${i} >= 0")));   // 恒真

        WorkflowContext ctx = engine.execute(wf, Map.of());

        assertEquals(3, field(ctx.get("body_out"), "round"), "上限 4 ⇒ 最后一轮 i=3，绝不能无限跑");
    }

    @Test
    @DisplayName("循环：max_iterations 超过硬上限时被夹到 1000（配置不能把服务打满）")
    void loopClampsHugeMaxIterations() {
        LoopNodeExecutor executor = new LoopNodeExecutor();
        WorkflowNode node = new WorkflowNode("loop_0", NodeType.Loop, null, "after", null, null, null,
                Map.of("loop_body", "body_1", "max_iterations", 999_999));

        LoopControl control = (LoopControl) executor.execute(node, new WorkflowContext());

        assertEquals(1000, control.maxIterations(), "应被夹到硬上限");
    }

    @Test
    @DisplayName("循环：缺 loop_body 时执行器直接报错（信息里带节点 id）")
    void loopWithoutBodyFails() {
        LoopNodeExecutor executor = new LoopNodeExecutor();
        WorkflowNode node = new WorkflowNode("loop_x", NodeType.Loop, null, "after", null, null, null, Map.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(node, new WorkflowContext()));
        assertTrue(e.getMessage().contains("loop_x"), "错误信息必须带节点 id，否则用户不知道改哪");
        assertTrue(e.getMessage().contains("loop_body"));
    }

    @Test
    @DisplayName("循环：loop_body 指向不存在的节点 → 保存期就被拦下（不必等运行）")
    void loopBodyMustExist() {
        WorkflowDefinition wf = loopWorkflow(Map.of(
                "loop_body", "no_such_node",
                "max_iterations", 2));

        BizException e = assertThrows(BizException.class, () -> engine.execute(wf, Map.of()));
        assertTrue(e.getMessage().contains("no_such_node"), "应指出是哪个目标不存在");
    }

    @Test
    @DisplayName("★ 合法循环不会被环检测误判为环（loop_body 不参与 hasCycle）")
    void validLoopPassesCycleCheck() {
        WorkflowDefinition wf = loopWorkflow(Map.of("loop_body", "body_1", "max_iterations", 2));

        // 不抛异常即通过 —— 若把 loop_body 当边，这里会报 "Workflow contains a cycle"
        assertNotNull(engine.execute(wf, Map.of()));
    }

    // ---------------- Parallel ----------------

    /**
     * 两条并行分支各写一个**同名**变量 {@code shared}，值不同。
     * 旧实现下结果取决于哪个分支后完成（不可复现）；现在应按 nextIds 顺序合并。
     */
    private WorkflowDefinition parallelWorkflow() {
        return new WorkflowDefinition("parallel", List.of(
                new WorkflowNode("start", NodeType.Start, null, "par_0",
                        Map.of("base", 100), null, null, null),
                new WorkflowNode("par_0", NodeType.Parallel, null, List.of("left", "right"),
                        null, "par_info", null, null),
                new WorkflowNode("left", NodeType.Transform, null, "end",
                        Map.of("shared", "LEFT", "read_base", "${base}"), "left_out", null, null),
                new WorkflowNode("right", NodeType.Transform, null, "end",
                        Map.of("shared", "RIGHT"), "right_out", null, null),
                new WorkflowNode("end", NodeType.End, null, null, null, null, null, null)
        ), null, null);
    }

    @Test
    @DisplayName("并行：各分支写入互不干扰，结果按分支声明顺序合并（可复现）")
    void parallelBranchesAreIsolatedAndMergedInOrder() {
        WorkflowDefinition wf = parallelWorkflow();

        // 跑多次：结果必须完全一致（旧实现下这里会因分支完成顺序不同而飘）
        for (int round = 0; round < 10; round++) {
            WorkflowContext ctx = engine.execute(wf, Map.of());

            assertEquals("LEFT", field(ctx.get("left_out"), "shared"), "左分支自己的变量不受右分支影响");
            assertEquals("RIGHT", field(ctx.get("right_out"), "shared"), "右分支自己的变量不受左分支影响");
            assertEquals(100, field(ctx.get("left_out"), "read_base"), "分支应能读到父层变量");
        }
    }

    /**
     * ★ 关键回归：分支之间**不得看见对方写入的变量**。
     *
     * <p>这是"子作用域"这一改动唯一能被行为观测到的地方 —— 上面那条测试只验证了
     * "各自的结果正确"，而**共享 ctx 时各分支的结果同样正确**（它们写的是不同的 output_var，
     * 所以不会被彼此覆盖）。真正区分两种实现的是：某分支去读**另一个分支的 output_var** 时会读到什么。</p>
     *
     * <p>隔离实现下必然读到 null；共享实现下则取决于两个分支谁先跑完 —— 不确定、不可复现。
     * 所以这里跑多次：只要出现过一次"读到了对方的值"，就说明隔离失效。</p>
     */
    @Test
    @DisplayName("★ 并行：分支读不到另一个分支的变量（隔离性的行为证据）")
    void parallelBranchCannotSeeSiblingVariables() {
        WorkflowDefinition wf = new WorkflowDefinition("parallel-isolation", List.of(
                new WorkflowNode("par_0", NodeType.Parallel, null, List.of("left", "right"),
                        null, null, null, null),
                // left 试图读 right 才会写的变量
                new WorkflowNode("left", NodeType.Transform, null, "end",
                        Map.of("peek", "${right_only}"), "left_out", null, null),
                new WorkflowNode("right", NodeType.Transform, null, "end",
                        Map.of("v", "RIGHT_VALUE"), "right_only", null, null),
                new WorkflowNode("end", NodeType.End, null, null, null, null, null, null)
        ), null, null);

        for (int round = 0; round < 20; round++) {
            WorkflowContext ctx = engine.execute(wf, Map.of());
            Object peeked = field(ctx.get("left_out"), "peek");
            assertEquals(null, peeked,
                    "第 " + round + " 次：left 分支读到了 right 分支的变量，说明两分支共用了同一个作用域");
            // right 自己的结果仍应正常落回父层
            assertEquals("RIGHT_VALUE", field(ctx.get("right_only"), "v"));
        }
    }

    @Test
    @DisplayName("并行：分支写父层同名变量时按声明顺序合并（后者覆盖前者）")
    void parallelMergeOrderIsDeterministic() {
        WorkflowDefinition wf = new WorkflowDefinition("parallel-merge", List.of(
                new WorkflowNode("par_0", NodeType.Parallel, null, List.of("a", "b"),
                        null, null, null, null),
                // 两个分支都写同一个 outputVar
                new WorkflowNode("a", NodeType.Transform, null, "end", Map.of("v", "A"), "dup", null, null),
                new WorkflowNode("b", NodeType.Transform, null, "end", Map.of("v", "B"), "dup", null, null),
                new WorkflowNode("end", NodeType.End, null, null, null, null, null, null)
        ), null, null);

        for (int round = 0; round < 10; round++) {
            WorkflowContext ctx = engine.execute(wf, Map.of());
            // 合并按 nextIds 顺序 ⇒ b 在 a 之后 ⇒ 最终是 B，与谁先跑完无关
            assertEquals("B", field(ctx.get("dup"), "v"),
                    "同名冲突必须按分支声明顺序解决，不能取决于完成先后");
        }
    }

    @Test
    @DisplayName("并行：Parallel 节点产出分支清单供下游引用")
    void parallelExposesBranchList() {
        WorkflowContext ctx = engine.execute(parallelWorkflow(), Map.of());

        Object info = ctx.get("par_info");
        assertNotNull(info, "Parallel 节点应把分支清单写入 output_var");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) info;
        assertEquals(2, map.get("count"));
        assertEquals(List.of("left", "right"), map.get("branches"));
    }

    @Test
    @DisplayName("并行：单个下游时等价于串行，不报错")
    void parallelWithSingleBranchBehavesLikeSerial() {
        WorkflowDefinition wf = new WorkflowDefinition("parallel-one", List.of(
                new WorkflowNode("par_0", NodeType.Parallel, null, List.of("only"), null, null, null, null),
                new WorkflowNode("only", NodeType.Transform, null, "end", Map.of("v", "X"), "out", null, null),
                new WorkflowNode("end", NodeType.End, null, null, null, null, null, null)
        ), null, null);

        WorkflowContext ctx = engine.execute(wf, Map.of());
        assertEquals("X", field(ctx.get("out"), "v"));
    }

    // ---------------- 子作用域本身 ----------------

    @Test
    @DisplayName("子作用域：读向上查找、写只落本层、all() 合并父层")
    void childScopeSemantics() {
        WorkflowContext parent = new WorkflowContext(Map.of("p", 1));
        WorkflowContext child = new WorkflowContext(parent);

        child.set("c", 2);

        assertEquals(2, child.get("c"), "本层变量");
        assertEquals(1, child.get("p"), "应能读到父层变量");
        assertEquals(1, parent.get("p"));
        assertFalse(parent.localContains("c"), "子层写入不得污染父层");
        assertEquals(Map.of("c", 2), child.localAll(), "localAll 只含本层");

        // all() 含父层（End 节点返回它，必须能看到上游变量）
        Map<String, Object> merged = child.all();
        assertEquals(1, merged.get("p"));
        assertEquals(2, merged.get("c"));
    }

    @Test
    @DisplayName("子作用域：嵌套路径 ${a.b} 也能向上解析到父层")
    void childScopeResolvesNestedPathFromParent() {
        WorkflowContext parent = new WorkflowContext(Map.of("user", Map.of("name", "张三")));
        WorkflowContext child = new WorkflowContext(parent);

        assertEquals("张三", child.resolve("${user.name}"));
        assertEquals("你好 张三", child.resolveString("你好 ${user.name}"));
    }

    @Test
    @DisplayName("根作用域行为与历史一致（无父层时不向上查找）")
    void rootScopeUnchanged() {
        WorkflowContext root = new WorkflowContext();
        root.set("k", "v");

        assertEquals("v", root.get("k"));
        assertEquals(null, root.get("missing"));
        assertEquals(Map.of("k", "v"), root.localAll());
        assertEquals(Map.of("k", "v"), root.all());
    }
}
