package io.voyager.samsungbarunhospital.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.voyager.samsungbarunhospital.model.CallItem;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CallQueue.notifyListeners() posts to Handler(Looper.getMainLooper()), so this needs a real
 * (or Robolectric-shadowed) Android main looper — plain JUnit can't instantiate a Looper.
 *
 * CallQueue is a process-wide singleton, so each test uses fresh random ids and cleans up after
 * itself rather than relying on isolated state between test methods.
 */
@RunWith(RobolectricTestRunner.class)
public class CallQueueTest {

    private final List<String> createdIds = new ArrayList<>();

    @After
    public void cleanup() {
        for (String id : createdIds) {
            CallQueue.getInstance().cancelById(id);
        }
        createdIds.clear();
    }

    private CallItem newCallItem(String id, String senderIp, String senderPeerId) {
        createdIds.add(id);
        return new CallItem(id, "101호", "너무 아파요", senderIp, senderPeerId);
    }

    @Test
    public void addingSameCallIdTwiceIsIgnoredOnSecondAdd() {
        String id = UUID.randomUUID().toString();
        // Simulates the same call arriving over both TCP and BLE.
        CallItem viaTcp = newCallItem(id, "192.168.0.10", null);
        CallItem viaBle = newCallItem(id, null, "aabbccddeeff0011");

        boolean firstAdded = CallQueue.getInstance().add(viaTcp);
        boolean secondAdded = CallQueue.getInstance().add(viaBle);

        assertTrue(firstAdded);
        assertFalse(secondAdded);
        assertEquals(1, countWithId(id));
    }

    @Test
    public void differentCallIdsAreBothAdded() {
        String idA = UUID.randomUUID().toString();
        String idB = UUID.randomUUID().toString();

        assertTrue(CallQueue.getInstance().add(newCallItem(idA, "192.168.0.11", null)));
        assertTrue(CallQueue.getInstance().add(newCallItem(idB, "192.168.0.12", null)));

        assertEquals(1, countWithId(idA));
        assertEquals(1, countWithId(idB));
    }

    @Test
    public void cancelByIdRemovesOnlyTheMatchingWaitingItem() {
        String idA = UUID.randomUUID().toString();
        String idB = UUID.randomUUID().toString();
        CallQueue.getInstance().add(newCallItem(idA, "192.168.0.13", null));
        CallQueue.getInstance().add(newCallItem(idB, "192.168.0.14", null));

        CallQueue.getInstance().cancelById(idA);

        assertEquals(0, countWithId(idA));
        assertEquals(1, countWithId(idB));
    }

    @Test
    public void markDoneRemovesItemFromQueue() {
        String id = UUID.randomUUID().toString();
        CallQueue.getInstance().add(newCallItem(id, "192.168.0.15", null));
        assertEquals(1, countWithId(id));

        CallQueue.getInstance().markDone(id);

        assertEquals(0, countWithId(id));
    }

    private int countWithId(String id) {
        int count = 0;
        for (CallItem item : CallQueue.getInstance().getAll()) {
            if (item.id.equals(id)) count++;
        }
        return count;
    }
}
