package com.agentplatform.core.model.adapter;

import com.agentplatform.core.model.ModelCapability;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.Set;

/**
 * 本地 Mock 模型适配器（无外部依赖兜底）。
 * <p>
 * 用于开发、单元测试与端到端演示：在未部署 LiteLLM Proxy / 无 API Key 的场景下
 * 返回确定性文本，保证平台完整流程可运行。生产环境不启用。
 * </p>
 * <p>仅当 provider 显式为 {@code local} 时才会走本适配器（见路由优先级）。</p>
 */
@Slf4j
public class MockModelAdapter implements ModelAdapter {

    @Override
    public String provider() {
        return "local";
    }

    @Override
    public Set<ModelCapability> capabilities() {
        return Set.of(ModelCapability.TEXT, ModelCapability.TOOL, ModelCapability.EMBEDDING);
    }

    @Override
    public double costWeight() {
        return 0.0;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        log.info("[model:local] 来源=Mock（本地兜底），非真实模型");
        String reply = "[mock] 您好，我是本地 Mock 模型。您说：「" + request.userMessage() + "」";
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            reply += "（已收到系统提示词）";
        }
        return new ChatResponse(reply, 10, 20, 0.0, 1L);
    }

    @Override
    public Flux<ChatDelta> stream(ChatRequest request) {
        String reply = chat(request).content();
        // 按字符切分模拟流式输出
        return Flux.fromArray(reply.split("(?<=\\G.{4})"))
                .map(chunk -> new ChatDelta(chunk, false, null))
                .concatWith(Flux.just(new ChatDelta("", true, null)));
    }

    @Override
    public float[] embed(EmbeddingRequest request) {
        // 确定性伪嵌入（长度 8，用于本地 RAG/诊断测试；忽略 model/baseUrl/apiKey）
        String text = request.input();
        float[] vec = new float[8];
        int hash = text.hashCode();
        for (int i = 0; i < 8; i++) {
            vec[i] = ((hash >> (i * 3)) & 0x7) / 7.0f;
        }
        return vec;
    }
}