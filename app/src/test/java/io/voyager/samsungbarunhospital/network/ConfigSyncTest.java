package io.voyager.samsungbarunhospital.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import io.voyager.samsungbarunhospital.data.AppSettings;
import io.voyager.samsungbarunhospital.model.ButtonConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** buildResponsePayload()가 AppSettings(SharedPreferences)를 읽으므로 Robolectric 필요. */
@RunWith(RobolectricTestRunner.class)
public class ConfigSyncTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void roundTripsMessagesAndButtons() {
        List<String> messages = Arrays.asList("너무 아파요", "주사약이 다 들어갔어요");
        AppSettings.save(context, "101호", messages, "1234");

        List<ButtonConfig> buttons = new ArrayList<>();
        buttons.add(new ButtonConfig("/data/local/uploaded.png", "youtube", ButtonConfig.TYPE_APP, "com.google.android.youtube"));
        buttons.add(new ButtonConfig(null, null, ButtonConfig.TYPE_WEB, "https://example.com"));
        AppSettings.saveButtonConfigs(context, buttons);

        String payload = ConfigSync.buildResponsePayload(context);
        ConfigSync.RemoteConfig remote = ConfigSync.parseResponsePayload(payload);

        assertEquals(messages, remote.messages);
        assertEquals(2, remote.buttons.size());

        ButtonConfig b0 = remote.buttons.get(0);
        assertEquals("youtube", b0.drawableRes);
        assertEquals(ButtonConfig.TYPE_APP, b0.type);
        assertEquals("com.google.android.youtube", b0.target);
        assertNull("imagePath는 절대 동기화되지 않아야 함", b0.imagePath);

        ButtonConfig b1 = remote.buttons.get(1);
        assertEquals(ButtonConfig.TYPE_WEB, b1.type);
        assertEquals("https://example.com", b1.target);
        assertNull(b1.imagePath);
    }

    @Test
    public void parseResponsePayloadReturnsNullForNonConfigInputs() {
        assertNull(ConfigSync.parseResponsePayload(null));
        assertNull(ConfigSync.parseResponsePayload("ACK"));
        assertNull(ConfigSync.parseResponsePayload("CONFIRMED|101호|메시지"));
    }

    @Test
    public void parseResponsePayloadReturnsNullForMalformedJson() {
        assertNull(ConfigSync.parseResponsePayload("CONFIG|{not valid json"));
    }

    @Test
    public void parseResponsePayloadAcceptsMissingVersionField() {
        // 과거 형식 흉내 — version 필드 자체가 없는 payload도 읽을 수 있어야 함
        ConfigSync.RemoteConfig remote = ConfigSync.parseResponsePayload("CONFIG|{\"messages\":[\"호출\"],\"buttons\":[]}");
        assertTrue(remote != null && remote.messages.contains("호출"));
    }

    @Test
    public void parseResponsePayloadRejectsFutureVersion() {
        ConfigSync.RemoteConfig remote = ConfigSync.parseResponsePayload("CONFIG|{\"version\":999,\"messages\":[],\"buttons\":[]}");
        assertNull(remote);
    }

    @Test
    public void emptyMessagesAndButtonsParseToEmptyLists() {
        AppSettings.saveButtonConfigs(context, new ArrayList<>());
        // getCallMessages()는 비어있으면 기본 메시지를 돌려주므로, 빈 리스트 케이스는 직접 payload로 검증
        ConfigSync.RemoteConfig remote = ConfigSync.parseResponsePayload("CONFIG|{\"version\":1,\"messages\":[],\"buttons\":[]}");
        assertTrue(remote.messages.isEmpty());
        assertTrue(remote.buttons.isEmpty());
    }
}
