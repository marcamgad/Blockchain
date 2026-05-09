package com.hybrid.blockchain.governance;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("severe")
public class GovernanceTest {

    private GovernanceManager governance;
    private List<String> council;

    @BeforeEach
    void setUp() {
        council = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            council.add("addr" + i);
        }
        governance = new GovernanceManager(council);
    }

    @Test
    @DisplayName("GOV.1: Should reach quorum with 5/7 votes")
    void testQuorumReaching() {
        String propId = "update_difficulty_interval";
        
        assertThat(governance.vote("addr0", propId)).isFalse();
        assertThat(governance.vote("addr1", propId)).isFalse();
        assertThat(governance.vote("addr2", propId)).isFalse();
        assertThat(governance.vote("addr3", propId)).isFalse();
        
        // 5th vote reaches quorum
        assertThat(governance.vote("addr4", propId)).isTrue();
    }

    @Test
    @DisplayName("GOV.2: Should reject non-council votes")
    void testNonCouncilVote() {
        assertThat(governance.vote("hacker_addr", "evil_prop")).isFalse();
    }

    @Test
    @DisplayName("GOV.3: Should ignore duplicate votes from same member")
    void testDuplicateVote() {
        String propId = "prop1";
        governance.vote("addr0", propId);
        governance.vote("addr0", propId);
        governance.vote("addr1", propId);
        governance.vote("addr2", propId);
        governance.vote("addr3", propId);
        
        // Still needs one more unique vote
        assertThat(governance.vote("addr3", propId)).isFalse();
        assertThat(governance.vote("addr4", propId)).isTrue();
    }
}
