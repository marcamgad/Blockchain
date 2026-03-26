package com.hybrid.blockchain.api;

import com.hybrid.blockchain.security.SecurityConfig;
import com.hybrid.blockchain.security.JwtAuthFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest(classes = {IoTRestAPI.class, SecurityConfig.class, JwtAuthFilter.class, com.hybrid.blockchain.api.MDCCorrelationInterceptor.class})
@AutoConfigureMockMvc
public class ChainExplorerTest {

    static {
        com.hybrid.blockchain.Blockchain b = org.mockito.Mockito.mock(com.hybrid.blockchain.Blockchain.class);
        com.hybrid.blockchain.Mempool m = org.mockito.Mockito.mock(com.hybrid.blockchain.Mempool.class);
        com.hybrid.blockchain.Storage s = org.mockito.Mockito.mock(com.hybrid.blockchain.Storage.class);
        org.mockito.Mockito.when(b.getMempool()).thenReturn(m);
        org.mockito.Mockito.when(b.getStorage()).thenReturn(s);
        org.mockito.Mockito.when(b.getHeight()).thenReturn(100);
        org.mockito.Mockito.when(b.getChain()).thenReturn(java.util.Collections.emptyList());
        org.mockito.Mockito.when(m.toArray()).thenReturn(java.util.Collections.emptyList());
        com.hybrid.blockchain.api.IoTRestAPI.setNode(b, null, null);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Explorer: Endpoints are accessible publicly")
    public void testExplorerEndpointsPublic() throws Exception {
        // These will return 404 because "dummyHash" isn't a real block/tx
        // but 404 means it bypassed Spring Security successfully, unlike 401
        
        mockMvc.perform(get("/api/v1/explorer/block/dummyHash"))
               .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/explorer/tx/dummyHash"))
               .andExpect(status().isNotFound());
               
        mockMvc.perform(get("/api/v1/explorer/address/dummyAddr"))
               .andExpect(status().isOk()); // Returns generic empty list or response ok
    }
}
