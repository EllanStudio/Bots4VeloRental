package dev.ellan.botrental.velocity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loads a copy of the real production addon config (secret replaced) after the 1.4.0 deploy script edited it, so a
 * change to the config parser can never silently stop understanding what is actually deployed.
 */
class ProductionConfigTest {
    @TempDir
    Path temp;

    @Test
    void loadsTheDeployedShapeIncludingPackages() throws Exception {
        try (var in = getClass().getResourceAsStream("/prod-like-config.yml")) {
            Files.copy(in, temp.resolve("config.yml"));
        }

        RentalConfig config = RentalConfig.load(temp);

        assertThat(config.bridges()).containsOnlyKeys("redstone", "survival");
        assertThat(config.pool()).hasSize(10).first().isEqualTo("BOT_1");
        assertThat(config.onlineRate()).isEqualTo(5);
        assertThat(config.offlineRate()).isEqualTo(20);

        PackageConfig packages = config.packages();
        assertThat(packages.enabled()).isTrue();
        assertThat(packages.pool()).hasSize(12).first().isEqualTo("BOT_11");
        assertThat(packages.pool()).last().isEqualTo("BOT_22");
        assertThat(packages.maxPerPlayer()).isEqualTo(5);
        assertThat(config.maxPerPlayer()).isEqualTo(5);
        assertThat(packages.maxCombat()).isEqualTo(4);
        assertThat(packages.allows("survival")).isTrue();
        assertThat(packages.allows("spawn")).isFalse();
        assertThat(packages.refundPercent()).isEqualTo(80);
        assertThat(packages.tiers().keySet()).containsExactly("small", "medium", "large");
        assertThat(packages.chargedDays()).containsEntry(7, 6).containsEntry(30, 20).containsEntry(1, 1);
        assertThat(Set.copyOf(config.pool())).doesNotContainAnyElementsOf(packages.pool());

        // the price table players will see
        var small = packages.tier("small").orElseThrow();
        assertThat(packages.price(small, 1, false)).isEqualTo(450);
        assertThat(packages.price(small, 7, false)).isEqualTo(2700);
        assertThat(packages.price(small, 30, false)).isEqualTo(9000);
        assertThat(packages.price(packages.tier("large").orElseThrow(), 30, true)).isEqualTo(54000);
        assertThat(packages.offers()).hasSize(9);
    }
}
