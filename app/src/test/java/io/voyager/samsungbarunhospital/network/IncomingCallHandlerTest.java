package io.voyager.samsungbarunhospital.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.voyager.samsungbarunhospital.data.CallQueue;
import io.voyager.samsungbarunhospital.model.CallItem;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** parseAndApply() calls into CallQueue (Handler-based), hence Robolectric. */
@RunWith(RobolectricTestRunner.class)
public class IncomingCallHandlerTest {

    private final List<String> createdIds = new ArrayList<>();

    @After
    public void cleanup() {
        for (String id : createdIds) {
            CallQueue.getInstance().cancelById(id);
        }
        createdIds.clear();
    }

    @Test
    public void parsesNewCallOverTcp() {
        String callId = UUID.randomUUID().toString();
        createdIds.add(callId);

        IncomingCallHandler.Result result = IncomingCallHandler.parseAndApply(
                "101호|너무 아파요|" + callId, "192.168.0.20", null);

        assertEquals(IncomingCallHandler.Kind.NEW_CALL, result.kind);
        CallItem item = findById(callId);
        assertEquals("192.168.0.20", item.senderIp);
        assertNull(item.senderPeerId);
    }

    @Test
    public void parsesNewCallOverBle() {
        String callId = UUID.randomUUID().toString();
        createdIds.add(callId);

        IncomingCallHandler.Result result = IncomingCallHandler.parseAndApply(
                "101호|너무 아파요|" + callId, null, "aabbccddeeff0011");

        assertEquals(IncomingCallHandler.Kind.NEW_CALL, result.kind);
        CallItem item = findById(callId);
        assertNull(item.senderIp);
        assertEquals("aabbccddeeff0011", item.senderPeerId);
    }

    @Test
    public void legacyTwoFieldPayloadIsStillAccepted() {
        IncomingCallHandler.Result result = IncomingCallHandler.parseAndApply(
                "101호|화장실 도움이 필요해요", "192.168.0.21", null);

        assertEquals(IncomingCallHandler.Kind.NEW_CALL, result.kind);
        // Auto-generated id — find it by tabletName/senderIp instead and remember it for cleanup.
        CallItem found = null;
        for (CallItem item : CallQueue.getInstance().getAll()) {
            if ("192.168.0.21".equals(item.senderIp)) {
                found = item;
                break;
            }
        }
        org.junit.Assert.assertNotNull(found);
        createdIds.add(found.id);
    }

    @Test
    public void cancelRemovesTheMatchingQueuedCall() {
        String callId = UUID.randomUUID().toString();
        createdIds.add(callId);
        IncomingCallHandler.parseAndApply("101호|메시지|" + callId, "192.168.0.22", null);
        assertTrue(containsId(callId));

        IncomingCallHandler.Result result = IncomingCallHandler.parseAndApply("CANCEL|" + callId, "192.168.0.22", null);

        assertEquals(IncomingCallHandler.Kind.CANCEL, result.kind);
        assertEquals(false, containsId(callId));
    }

    @Test
    public void confirmedParsesTabletNameAndMessageWithoutTouchingQueue() {
        IncomingCallHandler.Result result = IncomingCallHandler.parseAndApply(
                "CONFIRMED|101호|너무 아파요", null, null);

        assertEquals(IncomingCallHandler.Kind.CONFIRMED, result.kind);
        assertEquals("101호", result.confirmedTabletName);
        assertEquals("너무 아파요", result.confirmedMessage);
    }

    @Test
    public void malformedConfirmedIsIgnored() {
        IncomingCallHandler.Result result = IncomingCallHandler.parseAndApply("CONFIRMED|onlyonefield", null, null);
        assertEquals(IncomingCallHandler.Kind.IGNORED, result.kind);
    }

    @Test
    public void emptyPayloadIsIgnored() {
        assertEquals(IncomingCallHandler.Kind.IGNORED, IncomingCallHandler.parseAndApply("", "192.168.0.23", null).kind);
        assertEquals(IncomingCallHandler.Kind.IGNORED, IncomingCallHandler.parseAndApply(null, "192.168.0.23", null).kind);
    }

    @Test
    public void sameCallIdArrivingOverTcpThenBleIsDedupedAndKeepsFirstSenderInfo() {
        String callId = UUID.randomUUID().toString();
        createdIds.add(callId);

        IncomingCallHandler.Result first = IncomingCallHandler.parseAndApply(
                "101호|메시지|" + callId, "192.168.0.24", null);
        IncomingCallHandler.Result second = IncomingCallHandler.parseAndApply(
                "101호|메시지|" + callId, null, "aabbccddeeff0022");

        assertEquals(IncomingCallHandler.Kind.NEW_CALL, first.kind);
        assertEquals(IncomingCallHandler.Kind.IGNORED, second.kind);

        CallItem item = findById(callId);
        assertEquals("192.168.0.24", item.senderIp); // first-arrived path wins
        assertNull(item.senderPeerId);
    }

    private CallItem findById(String id) {
        for (CallItem item : CallQueue.getInstance().getAll()) {
            if (item.id.equals(id)) return item;
        }
        throw new AssertionError("CallItem with id " + id + " not found in queue");
    }

    private boolean containsId(String id) {
        for (CallItem item : CallQueue.getInstance().getAll()) {
            if (item.id.equals(id)) return true;
        }
        return false;
    }

    @Test
    public void payloadWithoutSeparatorIsNotACall() {
        // 병원 망의 점검 도구·브라우저 등이 수신 포트에 아무 글자나 보내도 가짜 호출이 되면 안 된다.
        int before = CallQueue.getInstance().getAll().size();

        IncomingCallHandler.Result result = IncomingCallHandler.parseAndApply("GET / HTTP/1.1", "10.0.0.7", null);

        assertEquals(IncomingCallHandler.Kind.IGNORED, result.kind);
        assertEquals(before, CallQueue.getInstance().getAll().size());
    }
}
