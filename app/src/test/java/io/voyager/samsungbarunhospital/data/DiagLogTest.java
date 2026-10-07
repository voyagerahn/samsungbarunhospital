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
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 진단 기록의 보관 정책(기간·용량 상한·전체 삭제)이 확실히 지켜지는지 실제 파일로 검증한다.
 * DiagLog는 프로세스 전역 상태라 테스트마다 전체 삭제로 시작한다.
 */
@RunWith(RobolectricTestRunner.class)
public class DiagLogTest {

    private File dir;

    @Before
    public void setUp() throws Exception {
        DiagLog.init(ApplicationProvider.getApplicationContext());
        clearAndAwait();
        dir = DiagLog.directoryForTest();
        dir.mkdirs();
    }

    @Test
    public void prune_deletesFilesOlderThanRetention_keepsRecentOnes() throws Exception {
        File expired = createDayFile(DiagLog.RETENTION_DAYS + 5, 100);
        File recent  = createDayFile(DiagLog.RETENTION_DAYS - 1, 100);
        File today   = createDayFile(0, 100);

        DiagLog.prune();

        assertFalse(expired.exists());
        assertTrue(recent.exists());
        assertTrue(today.exists());
    }

    @Test
    public void firstWriteOfTheDay_appliesRetention_withoutAppRestart() throws Exception {
        // 홈 런처라 재시작 없이 몇 주씩 돌 수 있음 — 날짜가 바뀌어 처음 기록할 때도 정리돼야 한다.
        File expired = createDayFile(DiagLog.RETENTION_DAYS + 1, 100);

        DiagLog.log("테스트", "오늘 첫 기록");
        String all = readAllAndAwait();

        assertFalse(expired.exists());
        assertTrue(all.contains("[테스트] 오늘 첫 기록"));
    }

    @Test
    public void prune_overTotalSizeCap_deletesOldestFirst_butKeepsToday() throws Exception {
        long chunk = DiagLog.MAX_TOTAL_BYTES / 2;
        File oldest = createDayFile(3, chunk);
        File middle = createDayFile(2, chunk);
        File today  = createDayFile(0, chunk);

        DiagLog.prune();

        assertFalse(oldest.exists());
        assertTrue(middle.exists());
        assertTrue(today.exists());
    }

    @Test
    public void clear_removesEverything_andReportsZeroRemaining() throws Exception {
        createDayFile(10, 100);
        createDayFile(0, 100);
        DiagLog.log("테스트", "삭제될 기록");

        int remaining = clearAndAwait();

        assertEquals(0, remaining);
        File[] left = dir.listFiles((d, name) -> name.endsWith(".log"));
        assertEquals(0, left == null ? 0 : left.length);
        assertEquals("", readAllAndAwait());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private File createDayFile(int daysAgo, long sizeBytes) throws Exception {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo);
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.getTime());
        File f = new File(dir, "diag_" + day + ".log");
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(sizeBytes);
        }
        return f;
    }

    private static int clearAndAwait() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger remaining = new AtomicInteger(-1);
        DiagLog.clear(r -> { remaining.set(r); latch.countDown(); });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        return remaining.get();
    }

    private static String readAllAndAwait() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> text = new AtomicReference<>();
        DiagLog.readAll(t -> { text.set(t); latch.countDown(); });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        return text.get();
    }
}
