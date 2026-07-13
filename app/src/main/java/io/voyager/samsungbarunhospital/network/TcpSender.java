package io.voyager.samsungbarunhospital.network;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TcpSender {

    public static final int PORT = 9999;

    // 캐시 스레드 풀: 요청 시 생성, 60초 유휴 후 자동 소멸, 완료 스레드 재사용
    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private static final int CONNECT_TIMEOUT_MS = 3000; // 연결 타임아웃 3초
    private static final int READ_TIMEOUT_MS    = 3000; // ACK 수신 타임아웃 3초
    // 최악의 경우 총 6초 후 실패 처리 (기존 최대 10초에서 단축)

    public interface Callback {
        void onResult(boolean success);
    }

    /** 호출 메시지 전송, ACK 수신 후 callback 호출 */
    public static void send(String targetIp, String message, Callback callback) {
        POOL.execute(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(targetIp, PORT), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(READ_TIMEOUT_MS);
                PrintWriter    out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                out.println(message);
                String response = in.readLine();
                if (callback != null) callback.onResult("ACK".equals(response));
            } catch (Exception e) {
                if (callback != null) callback.onResult(false);
            }
        });
    }
}
