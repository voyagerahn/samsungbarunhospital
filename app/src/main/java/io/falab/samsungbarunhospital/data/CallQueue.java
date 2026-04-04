package io.falab.samsungbarunhospital.data;

import android.os.Handler;
import android.os.Looper;

import io.falab.samsungbarunhospital.model.CallItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 스레드 안전 호출 큐 (싱글톤)
 * - AlertReceiverService(백그라운드)에서 add()
 * - CallQueueActivity(메인 스레드)에서 read / markDone()
 */
public class CallQueue {

    public interface Listener {
        void onQueueChanged();
    }

    private static final CallQueue INSTANCE = new CallQueue();

    private final CopyOnWriteArrayList<CallItem> items     = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private CallQueue() {}

    public static CallQueue getInstance() {
        return INSTANCE;
    }

    /**
     * 새 호출 추가
     * 같은 태블릿에 이미 WAITING 항목이 있으면 재전송 패킷이므로 무시
     */
    public void add(CallItem newItem) {
        for (CallItem existing : items) {
            if (existing.tabletName.equals(newItem.tabletName)
                    && existing.message.equals(newItem.message)
                    && CallItem.STATUS_WAITING.equals(existing.status)) {
                return; // 동일 태블릿+메시지 이미 대기 중 → 중복 무시
            }
        }
        items.add(0, newItem); // 최신 순
        notifyListeners();
    }

    /**
     * 간호사 확인 → 큐에서 제거
     */
    public void markDone(String id) {
        for (CallItem item : items) {
            if (item.id.equals(id)) {
                item.status = CallItem.STATUS_DONE;
                break;
            }
        }
        items.removeIf(i -> CallItem.STATUS_DONE.equals(i.status));
        notifyListeners();
    }

    public List<CallItem> getAll() {
        return new ArrayList<>(items);
    }

    public int getWaitingCount() {
        int count = 0;
        for (CallItem item : items) {
            if (CallItem.STATUS_WAITING.equals(item.status)) count++;
        }
        return count;
    }

    public void addListener(Listener l)    { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    // 항상 메인 스레드에서 콜백 (UI 업데이트 안전)
    private void notifyListeners() {
        mainHandler.post(() -> {
            for (Listener l : listeners) l.onQueueChanged();
        });
    }
}
