package io.github.manishpateluk.llmagentloop;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopeTest {

    @Test
    void atLevelClearsEveryIdBelowThatLevel() {
        Scope scope = Scope.of("acme", "alice", "s1");

        assertThat(scope.atLevel(ScopeLevel.SESSION)).isEqualTo(scope);
        assertThat(scope.atLevel(ScopeLevel.USER)).isEqualTo(new Scope("acme", "alice", null));
        assertThat(scope.atLevel(ScopeLevel.TENANT)).isEqualTo(new Scope("acme", null, null));
    }

    @Test
    void aUsersSessionsShareAUserLevelKeyButDifferentUsersDoNot() {
        Scope aliceMorning = Scope.of("acme", "alice", "morning");
        Scope aliceEvening = Scope.of("acme", "alice", "evening");
        Scope bob = Scope.of("acme", "bob", "morning");

        assertThat(aliceMorning.atLevel(ScopeLevel.USER)).isEqualTo(aliceEvening.atLevel(ScopeLevel.USER));
        assertThat(aliceMorning.atLevel(ScopeLevel.USER)).isNotEqualTo(bob.atLevel(ScopeLevel.USER));
    }

    @Test
    void keyMarksClearedLevelsAndEncodesIdsSoTheyCannotCollideOrEscapeAPrefix() {
        assertThat(Scope.of("acme", "alice", "s1").atLevel(ScopeLevel.USER).key()).isEqualTo("acme/alice/*");
        assertThat(Scope.of("acme", "a/b", "s1").key()).isEqualTo("acme/a%2Fb/s1");
        assertThat(Scope.of("acme", "a/b", "s1").key()).isNotEqualTo(Scope.of("acme/a", "b", "s1").key());
        assertThat(Scope.of("acme", "*", "s1").atLevel(ScopeLevel.USER).key())
                .isNotEqualTo(new Scope("acme", null, null).key());
    }

    @Test
    void ofRequiresEveryId() {
        assertThatThrownBy(() -> Scope.of("acme", null, "s1")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Scope.of("acme", "alice", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Scope.of(" ", "alice", "s1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSessionWithoutAUserIsRejected() {
        assertThatThrownBy(() -> new Scope("acme", null, "s1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forUserUsesTheDefaultTenant() {
        assertThat(Scope.forUser("alice", "s1").tenantId()).isEqualTo(Scope.DEFAULT_TENANT);
    }

    @Test
    void ephemeralScopesNeverCollideEvenAtTenantLevelUsers() {
        Scope first = Scope.ephemeral(UUID.randomUUID());
        Scope second = Scope.ephemeral(UUID.randomUUID());

        assertThat(first.atLevel(ScopeLevel.USER)).isNotEqualTo(second.atLevel(ScopeLevel.USER));
    }
}
