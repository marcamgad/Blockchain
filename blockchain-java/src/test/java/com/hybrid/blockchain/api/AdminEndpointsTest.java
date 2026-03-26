package com.hybrid.blockchain.api;

import com.hybrid.blockchain.security.SecurityConfig;
import com.hybrid.blockchain.security.JwtAuthFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest(classes = {IoTRestAPI.class, SecurityConfig.class, JwtAuthFilter.class, com.hybrid.blockchain.api.MDCCorrelationInterceptor.class})
@AutoConfigureMockMvc
public class AdminEndpointsTest {

    static {
        com.hybrid.blockchain.Blockchain b = org.mockito.Mockito.mock(com.hybrid.blockchain.Blockchain.class);
        com.hybrid.blockchain.Mempool m = org.mockito.Mockito.mock(com.hybrid.blockchain.Mempool.class);
        com.hybrid.blockchain.Storage s = org.mockito.Mockito.mock(com.hybrid.blockchain.Storage.class);
        org.mockito.Mockito.when(b.getMempool()).thenReturn(m);
        org.mockito.Mockito.when(b.getStorage()).thenReturn(s);
        org.mockito.Mockito.when(b.getHeight()).thenReturn(100);
        com.hybrid.blockchain.api.IoTRestAPI.setNode(b, null, null);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "admin")
    @DisplayName("Admin: Node pause and resume endpoints")
    public void testAdminPauseResume() throws Exception {
        mockMvc.perform(post("/api/v1/admin/node/pause")
                .contentType(MediaType.APPLICATION_JSON))
               .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/node/resume")
                .contentType(MediaType.APPLICATION_JSON))
               .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin")
    @DisplayName("Admin: Config update endpoint")
    public void testAdminConfigUpdate() throws Exception {
        mockMvc.perform(post("/api/v1/admin/config/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxTransactionsPerBlock\":500}"))
               .andExpect(status().isOk());
    }
}
