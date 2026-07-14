package com.hybrid.blockchain.ai;

import com.hybrid.blockchain.ai.FederatedLearningManager.AggregationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Byzantine-robustness of the federated aggregation strategies. Each test injects a
 * poisoned update that would drag a plain mean far off, and asserts the robust
 * estimator stays anchored to the honest majority.
 */
public class FederatedLearningRobustTest {

    private FederatedLearningManager fl;

    @BeforeEach
    void setup() {
        fl = FederatedLearningManager.getInstance();
        fl.resetForTesting();
    }

    @Test
    @DisplayName("Coordinate median ignores a wildly poisoned client")
    void coordinateMedianResistsPoison() {
        fl.setAggregationStrategy(AggregationStrategy.COORDINATE_MEDIAN);
        fl.submitUpdate("h1", new double[]{10, 10, 10});
        fl.submitUpdate("h2", new double[]{10.2, 9.8, 10.1});
        fl.submitUpdate("h3", new double[]{9.9, 10.1, 9.9});
        fl.submitUpdate("evil", new double[]{1000, -1000, 1000}); // poison

        FederatedLearningManager.AggregationResult r = fl.aggregate("leader", null, 4);
        assertThat(r).isNotNull();
        // A plain mean would be ~257; the median stays ~10.
        assertThat(r.model[0]).isBetween(9.0, 11.0);
        assertThat(r.model[1]).isBetween(9.0, 11.0);
        assertThat(r.model[2]).isBetween(9.0, 11.0);
    }

    @Test
    @DisplayName("Krum selects an honest update and discards the outlier")
    void krumSelectsHonestUpdate() {
        fl.setAggregationStrategy(AggregationStrategy.KRUM);
        // n=5, f=(5-1)/3=1, needs n >= 2f+3 = 5 → Krum active.
        fl.submitUpdate("h1", new double[]{1.0, 2.0, 3.0});
        fl.submitUpdate("h2", new double[]{1.1, 2.1, 3.1});
        fl.submitUpdate("h3", new double[]{0.9, 1.9, 2.9});
        fl.submitUpdate("h4", new double[]{1.05, 2.05, 3.05});
        fl.submitUpdate("evil", new double[]{100, 100, 100}); // far outlier

        FederatedLearningManager.AggregationResult r = fl.aggregate("leader", null, 5);
        assertThat(r).isNotNull();
        // Krum returns one honest update verbatim, never the poison.
        assertThat(r.model[0]).isBetween(0.8, 1.2);
        assertThat(r.model[1]).isBetween(1.8, 2.2);
        assertThat(r.model[2]).isBetween(2.8, 3.2);
    }

    @Test
    @DisplayName("Trimmed mean drops extreme values before averaging")
    void trimmedMeanDropsExtremes() {
        fl.setAggregationStrategy(AggregationStrategy.TRIMMED_MEAN);
        // n=7, f=2 → drop 2 lowest + 2 highest per coordinate, average the middle 3.
        fl.submitUpdate("h1", new double[]{5.0});
        fl.submitUpdate("h2", new double[]{5.1});
        fl.submitUpdate("h3", new double[]{4.9});
        fl.submitUpdate("h4", new double[]{5.05});
        fl.submitUpdate("h5", new double[]{4.95});
        fl.submitUpdate("lo", new double[]{-500.0}); // poison low
        fl.submitUpdate("hi", new double[]{500.0});   // poison high

        FederatedLearningManager.AggregationResult r = fl.aggregate("leader", null, 7);
        assertThat(r).isNotNull();
        assertThat(r.model[0]).isBetween(4.8, 5.2);
    }

    @Test
    @DisplayName("Default strategy remains the legacy mean+distance-filter (backward compatible)")
    void defaultStrategyUnchanged() {
        assertThat(fl.getAggregationStrategy())
                .isEqualTo(AggregationStrategy.MEAN_DISTANCE_FILTER);
    }

    @Test
    @DisplayName("submitUpdate rejects NaN/Infinity so it cannot poison any aggregator")
    void nonFiniteUpdateRejected() {
        assertThatThrownBy(() -> fl.submitUpdate("bad", new double[]{1.0, Double.NaN, 3.0}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fl.submitUpdate("bad", new double[]{Double.POSITIVE_INFINITY}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("submitUpdate rejects an oversized weight array (memory-exhaustion guard)")
    void oversizedUpdateRejected() {
        int tooBig = (com.hybrid.blockchain.Config.MAX_FL_MODEL_BYTES / Double.BYTES) + 1;
        // Allocate lazily via a length check only — we don't need a real huge array to
        // trip the guard, but constructing one of exactly tooBig proves the boundary.
        assertThatThrownBy(() -> fl.submitUpdate("big", new double[tooBig]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
