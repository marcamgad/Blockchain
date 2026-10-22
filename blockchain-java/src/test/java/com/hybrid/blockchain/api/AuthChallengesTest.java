package com.hybrid.blockchain.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [HC-085] One-time login challenges. */
class AuthChallengesTest {

    @Test
    @DisplayName("a challenge is valid once, for the address it was issued to, until it expires")
    void singleUseBoundAndExpiring() {
        AtomicLong now = new AtomicLong(1_000);
        AuthChallenges c = new AuthChallenges(now::get, 10);

        byte[] ch = c.issue("hbA");
        assertThat(ch).hasSize(32);
        assertThat(c.consume("hbB", ch)).as("issued to another address").isFalse();
        assertThat(c.consume("hbA", ch)).as("a failed attempt also uses it up").isFalse();

        byte[] ok = c.issue("hbA");
        assertThat(c.consume("hbA", ok)).isTrue();
        assertThat(c.consume("hbA", ok)).as("replay").isFalse();

        byte[] late = c.issue("hbA");
        now.addAndGet(AuthChallenges.TTL_MS + 1);
        assertThat(c.consume("hbA", late)).as("expired").isFalse();
    }

    @Test
    @DisplayName("requesting challenges for someone else's address does not cancel theirs")
    void othersCannotDisplace() {
        AuthChallenges c = new AuthChallenges(System::currentTimeMillis, 100);
        byte[] mine = c.issue("hbVictim");
        for (int i = 0; i < 20; i++) c.issue("hbVictim");
        assertThat(c.consume("hbVictim", mine)).isTrue();
    }

    @Test
    @DisplayName("the number of pending challenges is bounded; expired ones are purged to make room")
    void bounded() {
        AtomicLong now = new AtomicLong(0);
        AuthChallenges c = new AuthChallenges(now::get, 3);
        for (int i = 0; i < 3; i++) c.issue("hb" + i);
        assertThatThrownBy(() -> c.issue("hbX")).isInstanceOf(IllegalStateException.class);
        now.addAndGet(AuthChallenges.TTL_MS + 1);
        assertThat(c.issue("hbX")).hasSize(32);
    }
}
