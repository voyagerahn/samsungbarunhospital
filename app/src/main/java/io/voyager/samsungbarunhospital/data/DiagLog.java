package io.voyager.samsungbarunhospital.data;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 블루투스 통신 실패 이벤트 기록. 개발자가 실기기를 못 만지는 배포 구조라, 관리자 화면 "진단" 탭에서
 * 이 기록을 파일로 공유받아 통신이 왜 안 됐는지 판단한다.
 *
 * <b>정상 동작은 기록하지 않는다</b> — 통신이 실패했거나 실패할 수밖에 없는 상황(블루투스 꺼짐, 연결
 * 0대로 전송, 상대 미감지, 광고/검색/연결 실패, 받았지만 처리 못함 등)만 원인과 함께 한 줄씩 남긴다.
 * 그래서 문제가 없으면 파일 자체가 생기지 않는다.
 *
 * 보관 정책 (확실하게 지켜지는 것이 우선):
 * - 파일 하나({@code files/diag_events.log}), 한 줄 = 이벤트 하나, 줄 맨 앞에 날짜("yyyy-MM-dd HH:mm:ss").
 * - {@link #RETENTION_DAYS}일보다 오래된 이벤트는 줄 단위로 삭제. 정리는 init 때, <b>날짜가 바뀐 뒤
 *   첫 이벤트를 기록할 때</b>, 그리고 <b>기록을 읽을 때마다</b>(진단 탭 표시·개발자에게 보내기) 실행된다
 *   — 이 앱은 홈 런처라 몇 주씩 재시작 없이 돌 수 있어서 앱 시작 시에만 정리하면 기간이 지켜지지
 *   않고, 이벤트가 한동안 없으면 기록 시점 정리도 안 일어나기 때문.
 * - 안전장치로 파일이 {@link #MAX_FILE_BYTES}를 넘으면 오래된 이벤트부터 절반을 버린다.
 * - 수동 삭제({@link #clear})는 삭제 후 파일이 정말 없는지 다시 확인해 결과를 돌려준다.
 *
 * 여러 스레드(GATT 콜백, 코루틴, TCP 풀)에서 동시에 불리므로 디스크 작업은 전용 단일 스레드에서만
 * 순서대로 처리한다 — 호출부(메인 스레드 포함)는 절대 디스크 I/O로 막히지 않는다.
 * 호출 메시지 내용(환자 요청 문구)은 기록하지 않는다 — 공유 시 외부 메신저로 나가기 때문.
 */
public final class DiagLog {

    public static final int  RETENTION_DAYS = 30;
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private static final String FILE_NAME = "diag_events.log";
    private static final String TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final int    DATE_PREFIX_LEN = 10; // "yyyy-MM-dd"

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "diag-log");
        t.setDaemon(true);
        return t;
    });

    private static volatile File file;
    private static String lastPrunedDay; // 기록 스레드에서만 접근

    public interface TextCallback {
        /** 기록 스레드에서 호출된다 — UI 갱신은 호출부에서 runOnUiThread로 넘길 것. */
        void onText(String text);
    }

    public interface ClearCallback {
        /** @param deleted 기록 파일이 완전히 지워졌으면 true. 기록 스레드에서 호출. */
        void onCleared(boolean deleted);
    }

    private DiagLog() {}

    /** 여러 진입점(MainActivity, 각 서비스, 관리자 화면)에서 불러도 안전하다. */
    public static void init(Context ctx) {
        if (file != null) return;
        synchronized (DiagLog.class) {
            if (file != null) return;
            file = new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME);
        }
        WRITER.execute(DiagLog::prune);
    }

    /** 실패 이벤트 한 줄. @param tag 짧은 구간 이름 (예: "BLE송신", "BLE수신", "BLE연결") */
    public static void log(String tag, String message) {
        if (file == null) return;
        String line = new SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(new Date())
                + " [" + tag + "] " + message.replace('\n', ' ') + "\n";
        WRITER.execute(() -> append(line));
    }

    /** 오래된 것부터 시간순 전체 기록(없으면 빈 문자열). */
    public static void readAll(TextCallback callback) {
        WRITER.execute(() -> {
            prune(); // 이벤트가 한동안 없었어도 화면 표시·전송 시점에는 기간 지난 이벤트가 절대 안 나가게
            callback.onText(readFile());
        });
    }

    /** 진단 탭 표시용 요약: 이벤트 수·기간·크기. */
    public static void stats(TextCallback callback) {
        WRITER.execute(() -> {
            prune();
            String[] lines = nonEmptyLines(readFile());
            if (lines.length == 0) {
                callback.onText("기록된 실패 이벤트 없음 (" + RETENTION_DAYS + "일 지난 이벤트는 자동 삭제)");
                return;
            }
            callback.onText("실패 이벤트 " + lines.length + "건, " + formatBytes(file.length())
                    + " (" + dayLabel(lines[0]) + " ~ " + dayLabel(lines[lines.length - 1])
                    + ", " + RETENTION_DAYS + "일 지난 이벤트는 자동 삭제)");
        });
    }

    public static void clear(ClearCallback callback) {
        WRITER.execute(() -> {
            if (file != null) file.delete();
            if (callback != null) callback.onCleared(file == null || !file.exists());
        });
    }

    // ── 기록 스레드 전용 ───────────────────────────────────────────────────

    private static void append(String line) {
        try {
            String today = line.substring(0, DATE_PREFIX_LEN);
            if (!today.equals(lastPrunedDay)) prune(); // 날짜가 바뀐 뒤 첫 이벤트 — 보관 기간 재적용
            try (OutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
            if (file.length() > MAX_FILE_BYTES) dropOldestHalf();
        } catch (Exception ignored) {
            // 진단 기록 실패가 호출 기능에 영향을 주면 안 된다.
        }
    }

    /** 보관 기간이 지난 이벤트(줄)를 삭제한다. 줄 앞의 날짜를 문자열로 비교 — yyyy-MM-dd라 사전순 = 날짜순. */
    static void prune() {
        lastPrunedDay = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (file == null || !file.exists()) return;

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -RETENTION_DAYS);
        String cutoffDay = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());

        StringBuilder kept = new StringBuilder();
        boolean removedAny = false;
        for (String line : nonEmptyLines(readFile())) {
            // 날짜 형식이 아닌 줄(손상 등)은 기간을 판단할 수 없으니 버린다.
            boolean dated = line.length() >= DATE_PREFIX_LEN && line.charAt(4) == '-' && line.charAt(7) == '-';
            if (dated && line.substring(0, DATE_PREFIX_LEN).compareTo(cutoffDay) >= 0) {
                kept.append(line).append('\n');
            } else {
                removedAny = true;
            }
        }
        if (removedAny) rewrite(kept.toString());
    }

    private static void dropOldestHalf() {
        String[] lines = nonEmptyLines(readFile());
        StringBuilder kept = new StringBuilder();
        for (int i = lines.length / 2; i < lines.length; i++) kept.append(lines[i]).append('\n');
        rewrite(kept.toString());
    }

    private static void rewrite(String content) {
        if (content.isEmpty()) {
            file.delete();
            return;
        }
        File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp, false)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            tmp.renameTo(file);
        }
    }

    private static String readFile() {
        if (file == null || !file.exists()) return "";
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String[] nonEmptyLines(String text) {
        if (text.isEmpty()) return new String[0];
        return java.util.Arrays.stream(text.split("\n")).filter(l -> !l.trim().isEmpty()).toArray(String[]::new);
    }

    private static String dayLabel(String line) {
        return line.length() >= DATE_PREFIX_LEN ? line.substring(5, DATE_PREFIX_LEN).replace('-', '/') : "?";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    /** 테스트 전용 — 보관 정책을 실제 파일로 검증하기 위해. */
    static File fileForTest() {
        return file;
    }

    /** 테스트 전용 — 재시작 없이 날짜가 바뀐 상황을 흉내낸다. */
    static void simulateDayChangeForTest() {
        WRITER.execute(() -> lastPrunedDay = null);
    }

    /** 테스트 전용 — 앞서 넣은 기록 작업이 모두 끝난 뒤 실행. */
    static void runOnWriterForTest(Runnable r) {
        WRITER.execute(r);
    }
}
