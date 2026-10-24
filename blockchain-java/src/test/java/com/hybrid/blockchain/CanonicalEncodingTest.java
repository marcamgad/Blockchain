package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [HC-090] The consensus encoding of the state's object trees, independent of any JSON library. */
class CanonicalEncodingTest {

    @Test
    @DisplayName("map key order, map implementation and integer width do not change the bytes")
    void normalised() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 5); a.put("a", List.of("x", 2L));
        Map<String, Object> b = new HashMap<>();
        b.put("a", List.of("x", 2)); b.put("b", 5L);
        Map<String, Object> c = new TreeMap<>(b);
        assertThat(CanonicalEncoding.encode(a)).isEqualTo(CanonicalEncoding.encode(b)).isEqualTo(CanonicalEncoding.encode(c));
    }

    @Test
    @DisplayName("different values, types and nesting give different bytes")
    void injective() {
        List<Object> values = Arrays.asList(null, true, false, 0, 1, -1, 1.0, 0.5, "", "1", "true",
                List.of(), List.of(1), List.of(List.of()), Map.of(), Map.of("", 0), Map.of("a", 1), Map.of("a", "1"),
                List.of("a", "b"), List.of("ab"), Map.of("a", Map.of()));
        for (int i = 0; i < values.size(); i++)
            for (int j = i + 1; j < values.size(); j++)
                assertThat(CanonicalEncoding.encode(values.get(i))).as(values.get(i) + " vs " + values.get(j))
                        .isNotEqualTo(CanonicalEncoding.encode(values.get(j)));
    }

    @Test
    @DisplayName("a float encodes like the double Jackson reads back for it")
    void floats() {
        assertThat(CanonicalEncoding.encode(0.1f)).isEqualTo(CanonicalEncoding.encode(0.1d));
    }

    @Test
    @DisplayName("an unknown type is refused instead of being encoded ambiguously")
    void unknownTypeRefused() {
        assertThatThrownBy(() -> CanonicalEncoding.encode(new Object())).isInstanceOf(IllegalArgumentException.class);
    }
}
