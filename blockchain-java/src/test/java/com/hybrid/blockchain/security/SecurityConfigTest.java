package com.hybrid.blockchain.security;

import com.hybrid.blockchain.api.IoTRestAPI;
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
public class SecurityConfigTest {

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
    @DisplayName("Security: Public GET endpoints must be accessible without auth")
    public void testPublicGetEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Security: Account creation endpoint must be accessible without auth")
    public void testAccountCreateIsPermittedAll() throws Exception {
        mockMvc.perform(post("/api/v1/account/create")
                .contentType(MediaType.APPLICATION_JSON))
               .andExpect(status().isOk());
    }


    @Test
    @DisplayName("Security: Admin endpoints require auth")
    public void testAdminEndpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/admin/status"))
               .andExpect(status().isUnauthorized());
    }
}
