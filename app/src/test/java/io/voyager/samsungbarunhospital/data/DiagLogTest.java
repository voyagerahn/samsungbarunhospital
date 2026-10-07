package io.voyager.samsungbarunhospital.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 통신 실패 이벤트 기록의 보관 정책(30일 경과 이벤트 삭제·재시작 없이 날짜가 바뀔 때 정리·전체 삭제)이
 * 확실히 지켜지는지 실제 파일로 검증한다. DiagLog는 프로세스 전역 상태라 테스트마다 전체 삭제로 시작한다.
 */
@RunWith(RobolectricTestRunner.class)
public class DiagLogTest {

    private File file;

    @Before
    public void setUp() throws Exception {
        DiagLog.init(ApplicationProvider.getApplicationContext());
        clearAndAwait();
        file = DiagLog.fileForTest();
    }

    @Test
    public void noEvents_noFileIsCreated() throws Exception {
        DiagLog.prune();
        assertFalse(file.exists());
        assertEquals("", readAllAndAwait());
    }

    @Test
    public void prune_removesOnlyEventsOlderThanRetention() throws Exception {
        writeLines(
                eventDaysAgo(DiagLog.RETENTION_DAYS + 5, "오래된 이벤트"),
                eventDaysAgo(DiagLog.RETENTION_DAYS - 1, "보관 기간 안 이벤트"),
                eventDaysAgo(0, "오늘 이벤트"));

        DiagLog.prune();
        String all = readAllAndAwait();

        assertFalse(all.contains("오래된 이벤트"));
        assertTrue(all.contains("보관 기간 안 이벤트"));
        assertTrue(all.contains("오늘 이벤트"));
    }

    @Test
    public void prune_allExpired_deletesFile() throws Exception {
        writeLines(eventDaysAgo(DiagLog.RETENTION_DAYS + 1, "만료"));
        DiagLog.prune();
        assertFalse(file.exists());
    }

    @Test
    public void firstEventOfANewDay_appliesRetention_withoutAppRestart() throws Exception {
        // 홈 런처라 재시작 없이 몇 주씩 돌 수 있음 — 날짜가 바뀐 뒤 첫 이벤트를 기록할 때도 정리돼야 한다.
        writeLines(eventDaysAgo(DiagLog.RETENTION_DAYS + 1, "만료된 이벤트"));
        DiagLog.simulateDayChangeForTest();

        DiagLog.log("테스트", "새 이벤트");
        String raw = rawFileAfterWrites();

        assertFalse(raw.contains("만료된 이벤트"));
        assertTrue(raw.contains("[테스트] 새 이벤트"));
    }

    @Test
    public void readAll_neverReturnsExpiredEvents_evenWithoutNewEvents() throws Exception {
        // 이벤트가 한동안 없어 기록 시점 정리가 안 일어났어도, 화면 표시·전송 때는 기간 지난 이벤트가 빠져야 한다.
        writeLines(eventDaysAgo(DiagLog.RETENTION_DAYS + 3, "만료된 이벤트"), eventDaysAgo(1, "어제 이벤트"));

        String all = readAllAndAwait();

        assertFalse(all.contains("만료된 이벤트"));
        assertTrue(all.contains("어제 이벤트"));
    }

    @Test
    public void clear_deletesFile_andReportsSuccess() throws Exception {
        DiagLog.log("테스트", "삭제될 이벤트");
        readAllAndAwait();
        assertTrue(file.exists());

        assertTrue(clearAndAwait());
        assertFalse(file.exists());
        assertEquals("", readAllAndAwait());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static String eventDaysAgo(int daysAgo, String message) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo);
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(cal.getTime()) + " [테스트] " + message;
    }

    private void writeLines(String... lines) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 기록 스레드의 대기 작업이 끝난 뒤 파일 원본을 그대로 읽는다(readAll의 정리를 거치지 않음). */
    private String rawFileAfterWrites() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        DiagLog.runOnWriterForTest(latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (!file.exists()) return "";
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static boolean clearAndAwait() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean deleted = new AtomicBoolean(false);
        DiagLog.clear(d -> { deleted.set(d); latch.countDown(); });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        return deleted.get();
    }

    private static String readAllAndAwait() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> text = new AtomicReference<>();
        DiagLog.readAll(t -> { text.set(t); latch.countDown(); });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        return text.get();
    }
}
