package com.hybrid.blockchain.api;

import com.hybrid.blockchain.api.JwtManager;
import com.hybrid.blockchain.security.SecurityConfig;
import com.hybrid.blockchain.security.JwtAuthFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
    @DisplayName("Admin endpoints reject requests with no token")
    public void testAdminEndpointsRejectNoToken() throws Exception {
        mockMvc.perform(get("/api/v1/admin/status").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/node/pause").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/config/update").contentType(MediaType.APPLICATION_JSON).content("{\"maxTransactionsPerBlock\":500}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Admin endpoints reject expired or non-admin tokens")
    public void testAdminEndpointsRejectExpiredOrNonAdminTokens() throws Exception {
        JwtManager jwtManager = new JwtManager();
        String expired = jwtManager.issueToken("device-1", "DEVICE", new java.util.Date(System.currentTimeMillis() - 1000));
        String nonAdmin = jwtManager.issueToken("device-1", "DEVICE");

        mockMvc.perform(get("/api/v1/admin/status").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/status").header("Authorization", "Bearer " + nonAdmin))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin: Config update endpoint")
    public void testAdminConfigUpdate() throws Exception {
        JwtManager jwtManager = new JwtManager();
        String adminToken = jwtManager.issueToken("admin-device", "ADMIN");
        mockMvc.perform(post("/api/v1/admin/config/update")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxTransactionsPerBlock\":500}"))
               .andExpect(status().isOk());
    }
}
