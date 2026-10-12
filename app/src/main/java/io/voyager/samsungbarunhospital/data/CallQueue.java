package io.voyager.samsungbarunhospital.data;

import android.os.Handler;
import android.os.Looper;

import io.voyager.samsungbarunhospital.model.CallItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 스레드 안전 호출 큐 (싱글톤)
 * - AlertReceiverService(백그라운드)에서 add()
 * - CallQueueActivity(메인 스레드)에서 read / markDone()
 *
 * 관리 정책:
 * - 큐 최대 크기 MAX_SIZE(50)개 초과 시 가장 오래된 항목부터 제거
 * - EXPIRE_MS(30분) 이상 미확인된 호출은 자동 만료
 */
public class CallQueue {

    public interface Listener {
        void onQueueChanged();
    }

    /** 큐 최대 항목 수 */
    public static final int MAX_SIZE = 50;

    /** 자동 만료 시간 (ms) — 30분 */
    private static final long EXPIRE_MS = 30 * 60 * 1000L;

    private static final CallQueue INSTANCE = new CallQueue();

    /**
     * 확인·취소로 이미 끝난 호출 id를 기억하는 시간. 블루투스 경로는 수신 확인을 못 받으면 같은 호출을
     * 다시 보내는데, 그 재전송이 간호사가 확인해서 지운 뒤에 도착하면 같은 호출이 새 호출처럼 다시
     * 울리게 된다 — 끝난 id를 기억해 두고 무시한다. 보내는 쪽의 재전송 기한(30분)보다 조금 길게.
     */
    private static final long CLOSED_MEMORY_MS = 40 * 60 * 1000L;
    private static final int  CLOSED_MEMORY_MAX = 200;

    private final CopyOnWriteArrayList<CallItem> items     = new CopyOnWriteArrayList<>();
    // 끝난 호출 id → 끝난 시각. 삽입 순서 유지(오래된 것부터 버림). synchronized(this)로 보호.
    private final java.util.LinkedHashMap<String, Long> closedIds = new java.util.LinkedHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private CallQueue() {}

    public static CallQueue getInstance() {
        return INSTANCE;
    }

    /**
     * 새 호출 추가
     * - 큐가 MAX_SIZE를 초과하면 가장 오래된 항목 제거 후 추가
     * - 같은 id가 이미 큐에 있으면 추가하지 않음 (TCP와 BLE로 같은 호출이 동시에 도착하는 경우 대비)
     *
     * @return true면 신규 추가됨(알림 필요), false면 이미 같은 id가 있어 무시됨
     *
     * synchronized인 이유: "중복 id 검사 → add"가 한 묶음으로 원자적이어야 함.
     * CopyOnWriteArrayList는 개별 연산만 스레드안전할 뿐 이 복합 연산은 보장하지 않아서,
     * TCP 스레드와 BLE 콜백 스레드가 같은 callId로 거의 동시에 호출하면 중복 검사를
     * 둘 다 통과해버릴 수 있다(락 없이는).
     */
    public synchronized boolean add(CallItem newItem) {
        long now = System.currentTimeMillis();
        // 만료 항목 먼저 정리
        purgeExpired(now);

        for (CallItem existing : items) {
            if (existing.id.equals(newItem.id)) {
                return false;
            }
        }
        if (isClosed(newItem.id, now)) {
            return false; // 이미 확인·취소된 호출의 재전송
        }

        // 최대 크기 초과 시 가장 오래된 항목(리스트 끝) 제거
        while (items.size() >= MAX_SIZE) {
            items.remove(items.size() - 1);
        }

        items.add(0, newItem); // 최신 순
        notifyListeners();
        return true;
    }

    /**
     * 환자 취소 → UUID로 정확히 1건만 제거
     */
    public synchronized void cancelById(String id) {
        items.removeIf(i ->
                i.id.equals(id) &&
                CallItem.STATUS_WAITING.equals(i.status));
        // 취소가 호출보다 먼저 도착한 경우(순서 뒤바뀜)에도, 뒤늦게 온 그 호출이 뜨지 않게 된다.
        rememberClosed(id);
        notifyListeners();
    }

    /**
     * 간호사 확인 → 큐에서 제거
     */
    public synchronized void markDone(String id) {
        rememberClosed(id);
        for (CallItem item : items) {
            if (item.id.equals(id)) {
                item.status = CallItem.STATUS_DONE;
                break;
            }
        }
        items.removeIf(i -> CallItem.STATUS_DONE.equals(i.status));
        notifyListeners();
    }

    /**
     * 30분 이상 미확인된 호출 자동 만료 — CallQueueActivity 알람 루프에서 주기적으로 호출
     */
    public boolean purgeExpiredAndNotify() {
        long now = System.currentTimeMillis();
        int before = items.size();
        purgeExpired(now);
        if (items.size() != before) {
            notifyListeners();
            return true;
        }
        return false;
    }

    private void rememberClosed(String id) {
        closedIds.remove(id); // 다시 넣어 순서를 최신으로
        closedIds.put(id, System.currentTimeMillis());
        java.util.Iterator<String> it = closedIds.keySet().iterator();
        while (closedIds.size() > CLOSED_MEMORY_MAX && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private boolean isClosed(String id, long now) {
        closedIds.values().removeIf(closedAt -> now - closedAt > CLOSED_MEMORY_MS);
        return closedIds.containsKey(id);
    }

    private void purgeExpired(long now) {
        items.removeIf(i ->
                CallItem.STATUS_WAITING.equals(i.status) &&
                (now - i.timestamp) >= EXPIRE_MS);
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
