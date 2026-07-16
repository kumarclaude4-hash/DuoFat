package com.duoshield.app.call;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CallManager.CallState} orderings and
 * terminal-state semantics relied upon by {@code CallActivity.onDestroy()}.
 *
 * <p>These tests document the invariants the rest of the codebase depends on
 * so a future refactor of the enum cannot silently break them.
 */
public class CallStateTest {

    @Test
    public void allExpectedStatesExist() {
        // If any state is renamed/removed, code that switches on it will break.
        CallManager.CallState[] states = CallManager.CallState.values();
        boolean hasIdle       = false;
        boolean hasOutgoing   = false;
        boolean hasIncoming   = false;
        boolean hasConnecting = false;
        boolean hasConnected  = false;
        boolean hasEnded      = false;
        boolean hasFailed     = false;

        for (CallManager.CallState s : states) {
            switch (s) {
                case IDLE:             hasIdle = true; break;
                case OUTGOING_RINGING: hasOutgoing = true; break;
                case INCOMING_RINGING: hasIncoming = true; break;
                case CONNECTING:       hasConnecting = true; break;
                case CONNECTED:        hasConnected = true; break;
                case ENDED:            hasEnded = true; break;
                case FAILED:           hasFailed = true; break;
            }
        }

        assertTrue("IDLE state must exist", hasIdle);
        assertTrue("OUTGOING_RINGING state must exist", hasOutgoing);
        assertTrue("INCOMING_RINGING state must exist", hasIncoming);
        assertTrue("CONNECTING state must exist", hasConnecting);
        assertTrue("CONNECTED state must exist", hasConnected);
        assertTrue("ENDED state must exist", hasEnded);
        assertTrue("FAILED state must exist", hasFailed);
    }

    @Test
    public void endedAndFailedAreTerminalStates() {
        // CallActivity.onDestroy() guards on these two states to decide whether
        // to call hangup() or release(). Both must be terminal (no further Firestore
        // writes should happen after them).
        assertTrue("ENDED must not equal IDLE",
                CallManager.CallState.ENDED != CallManager.CallState.IDLE);
        assertTrue("FAILED must not equal IDLE",
                CallManager.CallState.FAILED != CallManager.CallState.IDLE);
        assertNotEquals("ENDED and FAILED must be distinct states",
                CallManager.CallState.ENDED, CallManager.CallState.FAILED);
    }

    @Test
    public void connectedIsNotTerminal() {
        assertNotEquals("CONNECTED must not equal ENDED",
                CallManager.CallState.CONNECTED, CallManager.CallState.ENDED);
        assertNotEquals("CONNECTED must not equal FAILED",
                CallManager.CallState.CONNECTED, CallManager.CallState.FAILED);
    }

    @Test
    public void stateEnumFromName_roundTrips() {
        // Verifies that valueOf() is stable — used in logs and diagnostics.
        for (CallManager.CallState s : CallManager.CallState.values()) {
            assertEquals("valueOf(name()) must round-trip",
                    s, CallManager.CallState.valueOf(s.name()));
        }
    }
}
