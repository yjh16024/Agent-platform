package com.agentplatform.framework.sampleweb;

import com.agentplatform.framework.api.AgentRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「加依赖即用」门禁：整个测试只提供了一个 ModelAdapter 与一个 Tool Bean，
 * 没有写任何装配代码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void 只加一个依赖就能跑通带工具的对话() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"现在几点了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("现在是 12:00"));
    }

    @Test
    void starter应自动装配E1与E3和运行时() {
        assertEquals(1, context.getBeansOfType(AgentRuntime.class).size());
        assertEquals(1, context.getBeansOfType(ChatMemoryRepository.class).size());
        assertTrue(context.getBeansOfType(CallAdvisor.class).size() >= 2,
                "应至少装配长度守卫与上下文注入两条 Advisor");
    }
}
