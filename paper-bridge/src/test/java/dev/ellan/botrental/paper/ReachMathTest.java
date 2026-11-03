package dev.ellan.botrental.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class ReachMathTest {
    @Test
    void aPointInsideTheBoxIsAtDistanceZero() {
        assertThat(ReachMath.distanceToBox(0.5, 0.5, 0.5, 0, 0, 0, 1, 2, 1)).isZero();
    }

    @Test
    void distanceIsMeasuredToTheNearestFaceNotTheCentre() {
        // eye 3 blocks from the face of a 1-wide mob whose centre is 3.5 away
        assertThat(ReachMath.distanceToBox(0, 1.6, 0, 3, 0, -0.5, 4, 2, 0.5)).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void cornersUseTheFullThreeDimensionalDistance() {
        assertThat(ReachMath.distanceToBox(0, 0, 0, 3, 4, 0, 5, 6, 1)).isCloseTo(5.0, within(1e-9));
    }

    @Test
    void reachIsInclusive() {
        assertThat(ReachMath.inReach(3.0, 3.0)).isTrue();
        assertThat(ReachMath.inReach(3.0001, 3.0)).isFalse();
    }
}
