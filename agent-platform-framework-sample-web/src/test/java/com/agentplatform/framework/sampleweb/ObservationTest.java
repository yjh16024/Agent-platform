package com.agentplatform.framework.sampleweb;

import com.agentplatform.framework.api.RunObserver;
import com.agentplatform.framework.starter.MicrometerRunObserver;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 观测证据：一次 HTTP 对话真的产生了一个 {@code agent.run} 观测。
 *
 * <p>这里不是"断言 bean 存在"，而是**挂在 registry 上数事件**——
 * 因为"装配了"和"真的发出了"是两件事，前者靠 {@code @ConditionalOnMissingBean} 就可能骗过自己。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObservationRegistry registry;

    @Autowired
    private RunObserver observer;

    @Test
    void starter应装配Micrometer实现() {
        assertTrue(observer instanceof MicrometerRunObserver,
                "有 ObservationRegistry 时应装配 Micrometer 实现，实际=" + observer.getClass().getName());
    }

    @Test
    void 一次对话应产生agent_run观测() throws Exception {
        AtomicInteger started = new AtomicInteger();
        AtomicInteger stopped = new AtomicInteger();

        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return MicrometerRunObserver.OBSERVATION_NAME.equals(context.getName());
            }

            @Override
            public void onStart(Observation.Context context) {
                started.incrementAndGet();
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.incrementAndGet();
            }
        });

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"现在几点了\"}"))
                .andExpect(status().isOk());

        assertEquals(1, started.get(), "应发出一次 agent.run 观测");
        assertEquals(1, stopped.get(), "观测必须闭合（start/stop 成对）");
    }
}
