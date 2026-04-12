package io.falab.samsungbarunhospital.network;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

public class TcpSender {

    public static final int PORT = 9999;

    public interface Callback {
        void onResult(boolean success);
    }

    /** 호출 메시지 전송 (환자 → 간호사), ACK 수신 후 callback 호출 */
    public static void send(String targetIp, String message, Callback callback) {
        new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(targetIp, PORT), 5000);
                socket.setSoTimeout(5000);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                out.println(message);
                String response = in.readLine();
                if (callback != null) callback.onResult("ACK".equals(response));
            } catch (Exception e) {
                e.printStackTrace();
                if (callback != null) callback.onResult(false);
            }
        }, "tcp-sender").start();
    }
}
