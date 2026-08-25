package com.campusguard.evaluation;

import static com.campusguard.moderation.ModerationDecision.ALLOW;
import static com.campusguard.moderation.ModerationDecision.ESCALATE;
import static com.campusguard.moderation.ModerationDecision.REMOVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Plain arithmetic, so plain unit tests.
 *
 * <p>These numbers are the entire argument for or against an engine, and nothing
 * else in the project would notice if a denominator here were wrong: every
 * integration test would still pass while the report quietly lied.
 */
class ConfusionMatrixTest {

    @Test
    void scoresPerfectPredictionsAsOne() {
        ConfusionMatrix matrix = new ConfusionMatrix();
        matrix.record(ALLOW, ALLOW);
        matrix.record(REMOVE, REMOVE);
        matrix.record(ESCALATE, ESCALATE);

        assertThat(matrix.macroF1()).isEqualTo(1.0);
        assertThat(matrix.accuracy()).isEqualTo(1.0);
    }

    /**
     * Worked by hand so the assertion is independent of the implementation.
     *
     * <p>Three ALLOW samples, two predicted right and one called REMOVE; four
     * REMOVE samples, three right and one called ALLOW.
     *
     * <p>ALLOW: 2 true positives, 1 false positive, 1 false negative, so
     * precision and recall are both 2/3 and F1 is 2/3. REMOVE: 3 true positives,
     * 1 false positive, 1 false negative, so both are 3/4 and F1 is 3/4. The
     * macro average is 17/24, and accuracy is 5/7.
     */
    @Test
    void computesPrecisionRecallAndMacroF1FromCounts() {
        ConfusionMatrix matrix = new ConfusionMatrix();
        matrix.record(ALLOW, ALLOW);
        matrix.record(ALLOW, ALLOW);
        matrix.record(ALLOW, REMOVE);
        matrix.record(REMOVE, REMOVE);
        matrix.record(REMOVE, REMOVE);
        matrix.record(REMOVE, REMOVE);
        matrix.record(REMOVE, ALLOW);

        ConfusionMatrix.ClassMetrics allow = matrix.metricsFor(ALLOW);
        assertThat(allow.truePositives()).isEqualTo(2);
        assertThat(allow.falsePositives()).isEqualTo(1);
        assertThat(allow.falseNegatives()).isEqualTo(1);
        assertThat(allow.precision()).isCloseTo(2.0 / 3, within(1e-9));
        assertThat(allow.recall()).isCloseTo(2.0 / 3, within(1e-9));

        ConfusionMatrix.ClassMetrics remove = matrix.metricsFor(REMOVE);
        assertThat(remove.precision()).isCloseTo(0.75, within(1e-9));
        assertThat(remove.recall()).isCloseTo(0.75, within(1e-9));

        assertThat(matrix.macroF1()).isCloseTo(17.0 / 24, within(1e-9));
        assertThat(matrix.accuracy()).isCloseTo(5.0 / 7, within(1e-9));
    }

    /**
     * An engine that never removes anything scores zero on REMOVE, and the macro
     * average has to carry that through rather than averaging it away. This is the
     * exact failure a micro average would flatter, which is why the report uses
     * macro.
     */
    @Test
    void punishesAnEngineThatNeverPredictsAClass() {
        ConfusionMatrix matrix = new ConfusionMatrix();
        for (int i = 0; i < 9; i++) {
            matrix.record(ALLOW, ALLOW);
        }
        matrix.record(REMOVE, ALLOW);

        assertThat(matrix.accuracy()).isCloseTo(0.9, within(1e-9));
        assertThat(matrix.metricsFor(REMOVE).f1()).isZero();
        // 0.947... for ALLOW and 0 for REMOVE, halved.
        assertThat(matrix.macroF1()).isLessThan(0.5);
    }

    @Test
    void countsALabelThatOnlyEverAppearsAsAPrediction() {
        ConfusionMatrix matrix = new ConfusionMatrix();
        matrix.record(ALLOW, ALLOW);
        matrix.record(ALLOW, ESCALATE);

        assertThat(matrix.labels()).containsExactly(ALLOW, ESCALATE);
        assertThat(matrix.metricsFor(ESCALATE).precision()).isZero();
        assertThat(matrix.metricsFor(ESCALATE).falsePositives()).isEqualTo(1);
    }

    @Test
    void returnsZeroForAnEmptyMatrix() {
        ConfusionMatrix matrix = new ConfusionMatrix();

        assertThat(matrix.total()).isZero();
        assertThat(matrix.macroF1()).isZero();
        assertThat(matrix.accuracy()).isZero();
    }
}
