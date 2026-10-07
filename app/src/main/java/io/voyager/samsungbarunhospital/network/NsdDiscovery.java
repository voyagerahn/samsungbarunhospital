package io.voyager.samsungbarunhospital.network;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 같은 Wi-Fi 안에서 이 앱이 설치된 태블릿을 IP를 몰라도 찾을 수 있게 하는 mDNS(NSD) 래퍼.
 *
 * - 광고: AlertReceiverService가 TCP 포트를 연 직후 {@link #register}로 "태블릿 이름"을 서비스
 *   이름으로 알린다 — 검색하는 쪽이 별도 요청 없이 이름과 IP를 바로 알 수 있게.
 * - 검색: 관리자 화면이 {@link #start}/{@link #stop}으로 감싸서 쓴다. 찾은 서비스는 하나씩 순서대로
 *   resolve한다(구버전 NsdManager는 동시에 두 개를 resolve하면 FAILURE_ALREADY_ACTIVE로 실패함).
 *
 * BLE 근처 기기 검색과 마찬가지로 간호사/환자 구분은 없다 — 목록에 뜬 이름을 보고 사람이 고른다.
 */
public final class NsdDiscovery {

    public static final String SERVICE_TYPE = "_barunhospital._tcp.";
    // DNS-SD 인스턴스 이름은 최대 63바이트 — 한글은 3바이트라 20자로 자른다.
    private static final int MAX_NAME_CHARS = 20;

    public interface Listener {
        /** 메인 스레드에서 호출된다. 같은 기기가 여러 번 올 수 있으니 받는 쪽에서 IP로 중복을 거른다. */
        void onFound(String name, String ip);
    }

    // ── 광고 ───────────────────────────────────────────────────────────────

    /** @return 해제할 때 {@link #unregister}에 넘길 핸들, 실패 시 null */
    public static NsdManager.RegistrationListener register(Context ctx, String tabletName, int port) {
        NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return null;

        NsdServiceInfo info = new NsdServiceInfo();
        String name = tabletName == null || tabletName.trim().isEmpty() ? "태블릿" : tabletName.trim();
        info.setServiceName(name.length() > MAX_NAME_CHARS ? name.substring(0, MAX_NAME_CHARS) : name);
        info.setServiceType(SERVICE_TYPE);
        info.setPort(port);

        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo s) {}
            @Override public void onRegistrationFailed(NsdServiceInfo s, int errorCode) {}
            @Override public void onServiceUnregistered(NsdServiceInfo s) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo s, int errorCode) {}
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
            return listener;
        } catch (Exception e) {
            return null;
        }
    }

    public static void unregister(Context ctx, NsdManager.RegistrationListener listener) {
        if (listener == null) return;
        NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return;
        try { nsd.unregisterService(listener); } catch (Exception ignored) {}
    }

    // ── 검색 ───────────────────────────────────────────────────────────────

    private final NsdManager nsd;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Deque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving = false;
    private volatile boolean active = false;
    private NsdManager.DiscoveryListener discoveryListener;

    public NsdDiscovery(Context ctx, Listener listener) {
        this.nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    public void start() {
        if (nsd == null || active) return;
        active = true;
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int errorCode) { active = false; }
            @Override public void onStopDiscoveryFailed(String type, int errorCode) {}
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onServiceLost(NsdServiceInfo s) {}
            @Override public void onServiceFound(NsdServiceInfo s) { enqueueResolve(s); }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            active = false;
        }
    }

    public void stop() {
        if (!active) return;
        active = false;
        synchronized (resolveQueue) { resolveQueue.clear(); }
        try { nsd.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) {}
    }

    private void enqueueResolve(NsdServiceInfo s) {
        synchronized (resolveQueue) {
            resolveQueue.add(s);
            if (resolving) return;
            resolving = true;
        }
        resolveNext();
    }

    private void resolveNext() {
        NsdServiceInfo next;
        synchronized (resolveQueue) {
            next = active ? resolveQueue.poll() : null;
            if (next == null) { resolving = false; return; }
        }
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo s, int errorCode) { resolveNext(); }
                @Override public void onServiceResolved(NsdServiceInfo s) {
                    InetAddress host = s.getHost();
                    if (active && host instanceof Inet4Address) {
                        String name = s.getServiceName();
                        String ip = host.getHostAddress();
                        mainHandler.post(() -> { if (active) listener.onFound(name, ip); });
                    }
                    resolveNext();
                }
            });
        } catch (Exception e) {
            resolveNext();
        }
    }
}
