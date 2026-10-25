package com.axnavigator.recovery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.axnavigator.auth.JwtConfiguration;
import com.axnavigator.common.SecurityConfiguration;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=CommandController.class, excludeFilters=@ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE,classes=CommandFenceFilter.class))
@Import({SecurityConfiguration.class, JwtConfiguration.class})
@TestPropertySource(properties="ax.recovery.enabled=true")
class RecoverySecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean CommandQueue queue;
    @Test void claimAndInputRequireInternalToken() throws Exception {
        mvc.perform(post("/internal/agent-commands/claim").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/internal/agent-commands/id/input").header("X-Internal-Token","wrong").header("X-Command-Lease","token"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(queue);
    }
    @Test void authorizedWorkerCanPoll() throws Exception {
        mvc.perform(post("/internal/agent-commands/claim").header("X-Internal-Token","test-internal-token").contentType("application/json").content("{}"))
            .andExpect(status().isNoContent());
        verify(queue).claim();
    }
}
