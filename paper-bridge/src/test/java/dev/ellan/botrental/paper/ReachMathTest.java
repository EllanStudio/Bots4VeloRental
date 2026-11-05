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

    @Test
    void closestPointClampsToTheBox() {
        assertThat(ReachMath.closestPoint(0, 1.6, 0, 1, 0, -0.3, 1.6, 1.95, 0.3)).containsExactly(1.0, 1.6, 0.0);
        assertThat(ReachMath.closestPoint(1.2, 1.0, 0.1, 1, 0, -0.3, 1.6, 1.95, 0.3)).containsExactly(1.2, 1.0, 0.1);
    }

    @Test
    void aMobOneBlockAwayHasAnAimPointAtEyeHeightEvenWhenItsOwnEyesAreLow() {
        // spider-sized box (0.9 high) one block away: the eye-to-eye ray runs downwards, the nearest-point ray is level
        double[][] points = ReachMath.aimPoints(0, 1.62, 0, 0.5, 0, -0.35, 1.2, 0.9, 0.35);
        assertThat(points.length).isEqualTo(3);
        assertThat(points[0][1]).isCloseTo(0.9, within(1e-9));
        assertThat(points[0][0]).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void directionFollowsMinecraftConventions() {
        assertThat(ReachMath.direction(0, 0)).containsExactly(-0.0, -0.0, 1.0);
        double[] east = ReachMath.direction(-90, 0);
        assertThat(east[0]).isCloseTo(1.0, within(1e-9));
        assertThat(east[2]).isCloseTo(0.0, within(1e-9));
        double[] down = ReachMath.direction(0, 90);
        assertThat(down[1]).isCloseTo(-1.0, within(1e-9));
    }

    @Test
    void sameAngleWrapsAround() {
        assertThat(ReachMath.sameAngle(359.9F, -0.1F, 0.5F)).isTrue();
        assertThat(ReachMath.sameAngle(10F, 20F, 0.5F)).isFalse();
    }
}
