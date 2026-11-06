package com.rhl.realtime;

import com.rhl.realtime.testutils.TestJwtDecoderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestJwtDecoderConfig.class)
@DisplayName("RealtimeService Context Loads Test")
class RealtimeServiceApplicationTests {

    @Test
    @DisplayName("Context loads successfully")
    void contextLoads() {
    }
}
