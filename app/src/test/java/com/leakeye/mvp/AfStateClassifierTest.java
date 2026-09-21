package com.leakeye.mvp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AfStateClassifierTest {
    @Test
    public void focusedLockedAndPassiveFocusedAreLocked() {
        assertTrue(AfStateClassifier.isLocked(4));  // CONTROL_AF_STATE_FOCUSED_LOCKED
        assertTrue(AfStateClassifier.isLocked(2));  // CONTROL_AF_STATE_PASSIVE_FOCUSED
    }

    @Test
    public void scanningAndUnfocusedAreNotLocked() {
        assertFalse(AfStateClassifier.isLocked(0)); // INACTIVE
        assertFalse(AfStateClassifier.isLocked(1)); // PASSIVE_SCAN
        assertFalse(AfStateClassifier.isLocked(3)); // ACTIVE_SCAN
        assertFalse(AfStateClassifier.isLocked(5)); // NOT_FOCUSED_LOCKED
        assertFalse(AfStateClassifier.isLocked(6)); // PASSIVE_UNFOCUSED
    }

    @Test
    public void nullStateIsNotLocked() {
        assertFalse(AfStateClassifier.isLocked(null));
    }
}
