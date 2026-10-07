package io.voyager.samsungbarunhospital.data;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 현장(병원) 디버깅용 블루투스 통신 기록. 개발자가 실기기를 못 만지는 배포 구조라, 관리자 화면
 * "진단" 탭에서 이 기록을 파일로 공유받아 어느 구간에서 통신이 끊겼는지 판단한다.
 *
 * 보관 정책 (확실하게 지켜지는 것이 우선):
 * - 하루에 파일 하나(diag_yyyyMMdd.log). 보관 기간은 파일 이름의 날짜로만 판단한다 — 파일 수정
 *   시각처럼 기기 상황에 따라 흔들리는 값을 쓰지 않는다.
 * - {@link #RETENTION_DAYS}일보다 오래된 날짜 파일은 삭제. 정리는 init 때와 **날짜가 바뀌어 새
 *   파일을 만들 때마다** 실행된다 — 이 앱은 홈 런처라 몇 주씩 재시작 없이 돌 수 있어서, 앱 시작
 *   시에만 정리하면 기간이 지켜지지 않는다.
 * - 안전장치로 전체 용량이 {@link #MAX_TOTAL_BYTES}를 넘으면 가장 오래된 날짜부터 삭제.
 * - 수동 삭제({@link #clear})는 삭제 후 남은 파일 수를 다시 세어 결과를 돌려준다.
 *
 * 여러 스레드(GATT 콜백, 코루틴, TCP 풀)에서 동시에 불리므로 디스크 작업은 전용 단일 스레드에서만
 * 순서대로 처리한다 — 호출부(메인 스레드 포함)는 절대 디스크 I/O로 막히지 않는다.
 * 호출 메시지 내용(환자 요청 문구)은 기록하지 않는다 — 공유 시 외부 메신저로 나가기 때문.
 */
public final class DiagLog {

    public static final int RETENTION_DAYS = 30;
    public static final long MAX_TOTAL_BYTES = 10L * 1024 * 1024;

    private static final String DIR_NAME = "diag_logs";
    private static final String PREFIX   = "diag_";
    private static final String SUFFIX   = ".log";

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "diag-log");
        t.setDaemon(true);
        return t;
    });

    private static volatile File dir;
    private static String lastWrittenDay; // 기록 스레드에서만 접근

    public interface TextCallback {
        /** 기록 스레드에서 호출된다 — UI 갱신은 호출부에서 runOnUiThread로 넘길 것. */
        void onText(String text);
    }

    public interface ClearCallback {
        /** @param remainingFiles 삭제 후에도 남아있는 파일 수(0이면 완전히 삭제됨). 기록 스레드에서 호출. */
        void onCleared(int remainingFiles);
    }

    private DiagLog() {}

    /** 여러 진입점(MainActivity, 각 서비스, 관리자 화면)에서 불러도 안전하다. */
    public static void init(Context ctx) {
        if (dir != null) return;
        synchronized (DiagLog.class) {
            if (dir != null) return;
            dir = new File(ctx.getApplicationContext().getFilesDir(), DIR_NAME);
        }
        WRITER.execute(DiagLog::prune);
    }

    /** @param tag 짧은 구간 이름 (예: "BLE송신", "BLE수신", "BLE연결") */
    public static void log(String tag, String message) {
        if (dir == null) return;
        Date now = new Date();
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(now)
                + " [" + tag + "] " + message + "\n";
        String day = dayKey(now);
        WRITER.execute(() -> append(day, line));
    }

    /** 오래된 것부터 시간순으로 이어붙인 전체 기록. */
    public static void readAll(TextCallback callback) {
        WRITER.execute(() -> {
            StringBuilder sb = new StringBuilder();
            for (File f : listFilesOldestFirst()) sb.append(readFile(f));
            callback.onText(sb.toString());
        });
    }

    /** 현재 보관 중인 기록의 파일 수/전체 크기 — 진단 탭에 표시용. */
    public static void stats(TextCallback callback) {
        WRITER.execute(() -> {
            List<File> files = listFilesOldestFirst();
            long total = 0;
            for (File f : files) total += f.length();
            if (files.isEmpty()) {
                callback.onText("보관된 기록 없음 (" + RETENTION_DAYS + "일 지난 기록은 자동 삭제)");
                return;
            }
            String range = dayLabel(files.get(0)) + " ~ " + dayLabel(files.get(files.size() - 1));
            callback.onText("보관 중: " + files.size() + "일치, " + formatBytes(total)
                    + " (기간 " + range + ", " + RETENTION_DAYS + "일 지나면 자동 삭제)");
        });
    }

    public static void clear(ClearCallback callback) {
        WRITER.execute(() -> {
            for (File f : listFilesOldestFirst()) f.delete();
            lastWrittenDay = null;
            if (callback != null) callback.onCleared(listFilesOldestFirst().size());
        });
    }

    // ── 기록 스레드 전용 ───────────────────────────────────────────────────

    private static void append(String day, String line) {
        try {
            if (!dir.exists()) dir.mkdirs();
            if (!day.equals(lastWrittenDay)) {
                // 날짜가 바뀌는 순간마다 보관 기간을 다시 적용한다(재시작 없이 오래 도는 경우 대비).
                lastWrittenDay = day;
                prune();
            }
            try (OutputStream out = new FileOutputStream(new File(dir, PREFIX + day + SUFFIX), true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // 진단 기록 실패가 호출 기능에 영향을 주면 안 된다.
        }
    }

    /** 보관 기간 초과 파일 삭제 + 전체 용량 상한 초과 시 오래된 날짜부터 삭제. */
    static void prune() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -RETENTION_DAYS);
        String cutoffDay = dayKey(cal.getTime()); // 이 날짜 "이전" 파일은 삭제

        List<File> files = listFilesOldestFirst();
        List<File> kept = new ArrayList<>();
        for (File f : files) {
            if (dayOf(f).compareTo(cutoffDay) < 0) f.delete();
            else kept.add(f);
        }

        long total = 0;
        for (File f : kept) total += f.length();
        // 오늘 파일(가장 최신)은 지금 쓰는 중이라 남긴다.
        for (int i = 0; i < kept.size() - 1 && total > MAX_TOTAL_BYTES; i++) {
            total -= kept.get(i).length();
            kept.get(i).delete();
        }
    }

    /** 테스트 전용 — 보관 정책을 실제 파일로 검증하기 위해. */
    static File directoryForTest() {
        return dir;
    }

    private static List<File> listFilesOldestFirst() {
        File[] arr = dir != null ? dir.listFiles((d, name) -> name.startsWith(PREFIX) && name.endsWith(SUFFIX)) : null;
        if (arr == null) return new ArrayList<>();
        List<File> files = new ArrayList<>(Arrays.asList(arr));
        // diag_yyyyMMdd.log — 이름 사전순 = 날짜순
        Collections.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        return files;
    }

    private static String dayKey(Date date) {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(date);
    }

    private static String dayOf(File f) {
        String name = f.getName();
        return name.substring(PREFIX.length(), name.length() - SUFFIX.length());
    }

    private static String dayLabel(File f) {
        String d = dayOf(f);
        return d.length() == 8 ? d.substring(4, 6) + "/" + d.substring(6, 8) : d;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private static String readFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
