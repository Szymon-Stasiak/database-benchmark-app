package com.dbagnets.backend.engine.driver.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CascadeBfsStateTest {

    @Test
    void seededWithRootEntityAndId() {
        CascadeBfsState state = new CascadeBfsState("Users", "u-1", 16);

        assertThat(state.hasNext()).isTrue();
        assertThat(state.poll()).isEqualTo("Users");
        assertThat(state.idsFor("Users")).containsExactly("u-1");
    }

    @Test
    void isNotVisitedFalseAfterFirstMarkTrueOnRepeat() {
        CascadeBfsState state = new CascadeBfsState("Users", "u-1", 16);
        assertThat(state.isNotVisited("Users")).isFalse();
        assertThat(state.isNotVisited("Users")).isTrue();
    }

    @Test
    void addChildrenPopulatesEntityAndEnqueues() {
        CascadeBfsState state = new CascadeBfsState("Users", "u-1", 16);
        state.poll();
        state.isNotVisited("Users");

        state.addChildren("Orders", List.of("o-1", "o-2"));

        assertThat(state.idsFor("Orders")).containsExactly("o-1", "o-2");
        assertThat(state.hasNext()).isTrue();
        assertThat(state.poll()).isEqualTo("Orders");
    }

    @Test
    void addChildrenMultipleCallsAppend() {
        CascadeBfsState state = new CascadeBfsState("Users", "u-1", 16);
        state.addChildren("Orders", List.of("o-1"));
        state.addChildren("Orders", List.of("o-2", "o-3"));

        assertThat(state.idsFor("Orders")).containsExactly("o-1", "o-2", "o-3");
    }

    @Test
    void reversedEntityOrderReturnsInsertionInReverse() {
        CascadeBfsState state = new CascadeBfsState("Users", "u-1", 16);
        state.addChildren("Orders", List.of("o-1"));
        state.addChildren("Items", List.of("i-1"));

        assertThat(state.reversedEntityOrder()).containsExactly("Items", "Orders", "Users");
    }

    @Test
    void snapshotReturnsCopyOfState() {
        CascadeBfsState state = new CascadeBfsState("Users", "u-1", 16);
        state.addChildren("Orders", List.of("o-1"));

        var snap = state.snapshot();
        assertThat(snap).containsOnlyKeys("Users", "Orders");
        assertThat(snap.get("Orders")).containsExactly("o-1");
    }

    @Test
    void hasNextRespectsMaxDepth() {
        CascadeBfsState state = new CascadeBfsState("Users", "u-1", 2);

        assertThat(state.hasNext()).isTrue();
        state.poll();
        state.addChildren("Orders", List.of("o-1"));
        assertThat(state.hasNext()).isTrue();
        state.poll();
        assertThat(state.hasNext()).isFalse();
    }

    @Test
    void hasNextFalseOnEmptyQueue() {
        CascadeBfsState state = new CascadeBfsState("Users", "u-1", 16);
        state.poll();
        assertThat(state.hasNext()).isFalse();
    }
}
