package com.agentplatform.core.model.balance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 模型开放平台「账户额度」视图。
 * <p>
 * 由 {@link ModelBalanceService} 调用各厂商开放平台的余额接口后组装；
 * <b>绝不包含 apiKey</b>，只回掩码无关的公开信息（余额、货币、明细）。
 * </p>
 * <p>
 * 状态语义（前端按此渲染，不要只看 ok）：
 * <ul>
 *   <li>{@code configured=false} —— 该绑定未配置（如嵌入模型还走本地 Mock）；</li>
 *   <li>{@code supported=false} —— 该厂商<b>没有</b>开放余额查询接口（如 OpenAI / 通义千问），
 *       {@code message} 说明原因；</li>
 *   <li>{@code ok=false} —— 有接口但本次查询失败（网络 / 401 / 路径不存在），{@code message} 带原因。</li>
 * </ul>
 * </p>
 *
 * @param binding    绑定来源：{@code chat}（默认对话模型）/ {@code embedding}（RAG 向量化）
 * @param provider   服务商标识（deepseek / siliconflow / moonshot ...）
 * @param model      绑定的模型名（用于展示是哪个模型在用这份额度）
 * @param baseUrl    实际查询使用的端点（非敏感，便于排查自建网关场景）
 * @param configured 是否已配置该绑定
 * @param supported  该厂商是否提供余额查询接口
 * @param ok         本次查询是否成功
 * @param currency   货币单位（CNY / USD），未知为 null
 * @param available  可用余额（主展示字段，字符串以保留厂商精度）
 * @param items      明细键值对（总余额 / 赠金 / 充值 / 现金 等）
 * @param message    不支持或失败时的说明
 * @param checkedAt  查询时间戳（毫秒）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelBalanceView(
        String binding,
        String provider,
        String model,
        String baseUrl,
        boolean configured,
        boolean supported,
        boolean ok,
        String currency,
        String available,
        List<Item> items,
        String message,
        Long checkedAt) {

    /** 余额明细项（键值对，前端直接渲染）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String label, String value) {
    }
}
