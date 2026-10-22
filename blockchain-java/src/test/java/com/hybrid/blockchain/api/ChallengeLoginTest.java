package com.hybrid.blockchain.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.security.QuantumResistantCrypto;
import com.hybrid.blockchain.security.JwtAuthFilter;
import com.hybrid.blockchain.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [HC-085] Found while making the live harness submit equivocation evidence: outside DEBUG no
 * endpoint issued a token for a key the client already holds (only account/create, DEBUG-only,
 * which generates the key server-side). submit-signed and every per-account endpoint were
 * unreachable for real clients. Login is now challenge-response: the client signs a one-time
 * challenge with its key.
 */
@SpringBootTest(classes = {IoTRestAPI.class, SecurityConfig.class, JwtAuthFilter.class, MDCCorrelationInterceptor.class})
@AutoConfigureMockMvc
class ChallengeLoginTest {

    static {
        com.hybrid.blockchain.Blockchain b = org.mockito.Mockito.mock(com.hybrid.blockchain.Blockchain.class);
        org.mockito.Mockito.when(b.getMempool()).thenReturn(org.mockito.Mockito.mock(com.hybrid.blockchain.Mempool.class));
        org.mockito.Mockito.when(b.getStorage()).thenReturn(org.mockito.Mockito.mock(com.hybrid.blockchain.Storage.class));
        IoTRestAPI.setNode(b, null, null);
    }

    @Autowired
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    private byte[] challenge(String address) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/challenge").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("address", address))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return HexUtils.decode((String) json.readValue(body, Map.class).get("challenge"));
    }

    private org.springframework.test.web.servlet.ResultActions login(Map<String, Object> req) throws Exception {
        return mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)));
    }

    private static Map<String, Object> signed(String address, byte[] pub, java.math.BigInteger priv, byte[] ch) {
        Map<String, Object> m = new HashMap<>();
        m.put("address", address);
        m.put("publicKey", HexUtils.encode(pub));
        m.put("challenge", HexUtils.encode(ch));
        m.put("signature", HexUtils.encode(Crypto.sign(AuthChallenges.digest(address, ch), priv)));
        return m;
    }

    @Test
    @DisplayName("a key holder gets a token for its own address by signing the challenge; once only")
    void loginWithOwnKey() throws Exception {
        TestKeyPair k = new TestKeyPair(8501);
        byte[] ch = challenge(k.getAddress());
        Map<String, Object> req = signed(k.getAddress(), k.getPublicKey(), k.getPrivateKey(), ch);

        String body = login(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = (String) json.readValue(body, Map.class).get("token");
        assertThat(new JwtManager().validateToken(token, k.getAddress())).as("token is for that address").isTrue();

        login(req).andExpect(status().isUnauthorized());   // replay of the same challenge
    }

    @Test
    @DisplayName("wrong key, wrong signature, or a key that does not derive the address: 401")
    void rejected() throws Exception {
        TestKeyPair k = new TestKeyPair(8502), other = new TestKeyPair(8503);

        byte[] ch = challenge(k.getAddress());
        login(signed(k.getAddress(), other.getPublicKey(), other.getPrivateKey(), ch)).andExpect(status().isUnauthorized());

        ch = challenge(k.getAddress());
        Map<String, Object> bad = signed(k.getAddress(), k.getPublicKey(), k.getPrivateKey(), ch);
        bad.put("signature", HexUtils.encode(Crypto.sign(AuthChallenges.digest(k.getAddress(), new byte[32]), k.getPrivateKey())));
        login(bad).andExpect(status().isUnauthorized());

        login(signed(k.getAddress(), k.getPublicKey(), k.getPrivateKey(), new byte[32])).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a hybrid (hq) address needs both public keys to match the address")
    void hybridAddress() throws Exception {
        TestKeyPair ec = new TestKeyPair(8504);
        KeyPair pq = QuantumResistantCrypto.generateDilithiumKeyPair(3);
        String hq = Crypto.deriveHybridAddress(ec.getPublicKey(), pq.getPublic().getEncoded());

        Map<String, Object> noPq = signed(hq, ec.getPublicKey(), ec.getPrivateKey(), challenge(hq));
        login(noPq).andExpect(status().isUnauthorized());

        Map<String, Object> ok = signed(hq, ec.getPublicKey(), ec.getPrivateKey(), challenge(hq));
        ok.put("mldsaPublicKey", HexUtils.encode(pq.getPublic().getEncoded()));
        login(ok).andExpect(status().isOk());
    }

    @Test
    @DisplayName("malformed requests are 400, not 500")
    void malformed() throws Exception {
        mvc.perform(post("/api/v1/auth/challenge").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        login(Map.of("address", "hbx", "publicKey", "zz", "challenge", "00", "signature", "00"))
                .andExpect(status().isBadRequest());
    }
}
