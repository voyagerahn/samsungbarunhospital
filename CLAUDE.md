# 삼성바른병원 환자 호출 시스템 — 개발 컨텍스트

이 문서는 향후 이 저장소에서 작업할 때(특히 Claude Code 세션) 다시 처음부터 코드를 읽지 않아도 되도록, 현재까지의 구현 상태와 설계 결정을 정리한 것이다. 사용자 대상 사용법은 `README.md`, `docs/manual.md` 참고.

## 프로젝트 개요

- 순수 네이티브 Android 앱(Java + 일부 Kotlin), Gradle 빌드.
- `applicationId` / `namespace`: `io.voyager.samsungbarunhospital` (구 `io.falab.samsungbarunhospital`에서 전체 변경됨 — 기존 설치본은 업데이트 불가, 재설치 필요).
- `minSdk 24`(Android 7.0), `compileSdk`/`targetSdk 33`.
- 환자 태블릿이 호출 버튼을 누르면 간호사 태블릿에 알림이 뜨고, 간호사가 확인(CONFIRMED)하면 환자 화면에 반영되는 구조.

## 기존 통신 경로 (TCP, 같은 Wi-Fi 전제)

- `network/TcpSender.java`: 포트 9999 raw TCP. payload는 `"|"` 구분 평문.
  - 호출: `"TabletName|Message|CallId"` (구버전 호환: `"TabletName|Message"`)
  - 취소: `"CANCEL|<callId>"`
  - 확인: `"CONFIRMED|<TabletName>|<Message>"`
- `network/AlertReceiverService.java`: `ServerSocket(9999)` 리스닝하는 Foreground Service. 수신 payload는 `IncomingCallHandler`로 위임.
- `network/IncomingCallHandler.java`: TCP/BLE 공통 payload 파서. `CallQueue.add()`가 callId 기준 중복 추가를 막아준다(TCP+BLE로 같은 호출이 동시 도착해도 큐에는 1건만 남음).
- `data/CallQueue.java`: 스레드안전 싱글톤 큐(`CopyOnWriteArrayList`), 30분 자동 만료, `Handler(Looper.getMainLooper())` 기반 리스너 통지.
- `data/AppSettings.java`: 간호사 IP 목록(`KEY_NURSE_IPS`), 태블릿 표시 이름(`KEY_TABLET_NAME`), BLE peerId(`KEY_BLE_PEER_ID`, 8바이트 랜덤 hex, 기기별 영구값) 등 SharedPreferences 기반 설정.
- IP는 관리자가 수동 등록하거나, 같은 Wi-Fi면 "근처 Wi-Fi 기기 검색"(mDNS/NSD, `network/NsdDiscovery.java`, 서비스 타입 `_barunhospital._tcp.`, 서비스 이름=태블릿 이름)으로 찾아 등록. 광고는 `AlertReceiverService`가 포트 바인드 직후 등록(이름 변경은 앱 재시작 후 반영). v2.2.0 미만 기기는 광고를 안 하므로 검색에 안 뜸.

## 간호사 설정 불러오기 (신규, `feature/ble-mesh` 브랜치, TCP만 사용)

환자 태블릿이 등록된 간호사 태블릿 중 하나를 골라 그 태블릿의 **호출 메시지 목록·런처 버튼 설정**을 직접 요청해서 읽어오는 기능. 서버 없이, 기존 TCP 호출 포트(9999)를 그대로 재사용.

- `network/ConfigSync.java`: `"CONFIG_REQUEST"` 요청 / `"CONFIG|<json>"` 응답 프로토콜. JSON에 `version`(현재 1) 필드 포함 — 나중에 형식이 바뀌면 이 앱이 이해 못하는 미래 버전을 거부할 수 있게. **버튼의 `imagePath`(관리자가 직접 업로드한 이미지)는 절대 동기화하지 않음** — 로컬 파일 경로라 다른 기기에서 의미 없음, 받는 쪽은 `drawableRes` 또는 기본 아이콘으로 대체.
- `network/TcpSender.java`: `requestConfig(ip, RawResponseCallback)` 추가 — 기존 `send()`(ACK만 확인)와 별개로 실제 응답 데이터를 그대로 콜백에 전달.
- `network/AlertReceiverService.java`: `handleClient()`에서 payload가 `ConfigSync.REQUEST`면 "ACK" 대신 `ConfigSync.buildResponsePayload()` 결과를 응답, 그 외에는 기존 흐름 그대로(`IncomingCallHandler`는 안 건드림).
- `ui/AdminSettingsActivity.java`: "간호사 설정 불러오기" 버튼 → 등록된 간호사 IP 선택 다이얼로그 → 요청 → 확인 다이얼로그(메시지/버튼 개수 표시) → 적용. **적용 시점이 비대칭**: 버튼은 즉시 저장(기존 버튼 추가/수정/삭제가 원래 즉시 저장이라 일관성 유지), 메시지는 입력 필드만 채우고 하단 "저장" 버튼을 눌러야 반영(기존 메시지 편집 동작과 동일). 의도적 설계이며 버그 아님.
- **범위 제한(의도적)**: BLE mesh로는 확장하지 않음 — BLE는 flood/relay 구조라 특정 상대에게 요청-응답을 주고받는 것과 궁합이 안 좋음. 필요해지면 별도 논의.
- **범위 제한(의도적)**: 필드 목록을 선언적으로 관리하는 범용 동기화 프레임워크가 아님 — 지금은 메시지·버튼 두 항목만 하드코딩. 항목이 늘어나면 그때 리팩터링.
- 테스트: `app/src/test/java/.../network/ConfigSyncTest.java`(Robolectric, 6개) — 라운드트립, 버전 필드 검증, 비정상 입력 처리.

## 관리자 설정 화면 UI (카드형)

`activity_admin_settings.xml`은 기능별로 `res/drawable/bg_card.xml`(둥근 모서리 배경)로 감싼 카드 8개로 구성(기기 정보 / 간호사 태블릿(IP 목록+설정 불러오기) / BLE 상태 / 알람 음량 / 글씨 크기 / 태블릿 기본정보(이름+비밀번호) / 호출 메시지 / 앱 버튼 관리). 저장/취소 버튼은 카드 밖 맨 아래 고정. **기존 `android:id`는 전부 그대로 유지**되어 있으니 Java 쪽 `findViewById` 호출부는 영향 없음 — 새 카드를 추가할 때도 이 패턴(배경 `@drawable/bg_card`, padding 20dp, marginBottom 20dp)을 따르면 됨.

## BLE mesh 보조 통신 (`feature/ble-mesh` 브랜치)

### 왜 추가했나
같은 Wi-Fi가 아니지만 물리적으로 가까운 태블릿끼리 서버 없이 통신하기 위한 보조 경로. bitchat-android(`/Users/ahnjaesung/Projects/bitchat-android`)의 BLE mesh 전송 계층을 참고/이식(Noise 암호화·서명·gossip sync 등 채팅 전용 기능은 제거).

### 상태 (2026-07-13 기준)
- 구현 완료(Stage 0~4), 단위테스트 43개 전부 통과, `./gradlew assembleDebug` 정상.
- **실기기 검증(Stage 5) 미완료** — 이 개발 환경에는 adb/에뮬레이터가 없어 BLE 광고·스캔·GATT 연결을 직접 확인할 수 없었음. 사용자가 API26+ 실기기 2~3대로 직접 검증해야 함. **주의: 이 검증 없이 2026-08-05에 main 병합 진행됨(아래 참고)** — BLE mesh 관련 버그가 실사용 중 발견될 수 있으니 관련 이슈 제보 시 이 부분부터 의심할 것.
- 2026-08-05, 사용자 승인으로 `main`에 병합 및 원격 push 완료(fast-forward, 커밋 `3d8060d`까지). 실기기 검증은 여전히 미완료 상태로 병합된 것이니 유의.
- 상세 설계 계획 원본: `~/.claude/plans/crispy-waddling-lynx.md` (사용자 로컬 경로, 이 저장소 밖에 있음).

### 패키지 구조 (`network/ble/`, main 아래 Kotlin)
```
BleMeshConstants.kt   상수(TTL=5, dedup 5분/500개, fragment 임계치 400B, store-forward 30분, MAX_CONNECTIONS=8, GATT UUID 등)
BlePacket.kt          바이너리 패킷 포맷 (버전1+타입1+ttl1+senderId8+recipientId8+messageId16+payloadLen2+payload)
BleDedupCache.kt       messageId 기반 재수신 필터 (LRU, 시간창)
BlePacketRelay.kt      TTL 감소/드롭 판단 (단순 flood, 확률적 relay 없음)
BleFragmentation.kt    400B 초과 payload 분할/재조립
BleStoreForwardCache.kt  오프라인 peer용 CONFIRMED 캐시(유니캐스트만 대상)
BlePeerManager.kt      ANNOUNCE 기반 peer online/offline 추적
BleConnectionManager.kt 다중 GATT 연결(client+server 공용) 관리, broadcast/sendTo
BleGattServerManager.kt peripheral 역할(advertise+GATT서버) — FULL 모드에서만 기동
BleGattClientManager.kt central 역할(scan+connect) — 모든 지원 모드에서 기동
BlePermissions.kt      API 레벨별 필요 권한
BleMode.kt              BleModeResolver.resolveMode(sdkInt, hasPermissions) — 순수 함수
BleMeshManager.kt       Java에서 호출하는 단일 퍼사드(object, @JvmStatic)
BleMeshService.kt       Foreground Service (BleMeshManager 생명주기 래핑)
```
Java 쪽 신규/변경 파일: `model/CallItem.java`(senderPeerId 필드 추가), `data/CallQueue.java`(add() → boolean, callId dedup), `network/IncomingCallHandler.java`(신규, TCP/BLE 공통 파서), `network/AlertReceiverService.java`(handleIncomingFromBle 정적 진입점), `ui/MainActivity.java`(BLE 권한 요청+발신), `ui/CallQueueActivity.java`(BLE 확인 응답 발신), `ui/AdminSettingsActivity.java`(BLE 진단 패널, 아래 참고).

### 관리자 화면 BLE 진단 패널
`AdminSettingsActivity`에 "근거리 보조 통신(BLE) 상태" 섹션 추가(`tvBleStatus` + `btnRefreshBleStatus`, `onResume()`에서 자동 갱신). 표시 항목: 모드(FULL/CENTRAL_ONLY/사용불가 + 설명), 서비스 실행 여부, 현재 연결된 기기 수, 마지막 통신 후 경과 시간, 연결 0대일 때 경고 문구. 이를 위해 `BleMeshManager`에 조회 전용 API 3개 추가: `isRunning()`, `getConnectionCount()`(`BleConnectionManager.count()` 위임), `getLastActivityAtMs()`(자기 자신이 보낸 게 아닌 유효 패킷을 처리할 때마다 `handleRawBytes()`에서 갱신). 실시간 mesh 시각화 같은 무거운 기능은 의도적으로 제외(가벼운 진단 수준으로 범위 한정).

### BleMode — API 레벨별 3단계 차등 지원
| 모드 | 조건 | 가능한 것 |
|---|---|---|
| `FULL` | API 26+ | advertise(GATT서버)+scan(central) 동시, 다른 기기가 먼저 찾아올 수 있음 |
| `CENTRAL_ONLY` | API 24~25 | scan+connect만 가능. advertise 불가 → **자기 자신은 발견될 수 없음**. 단, 스스로 건 연결은 양방향이라 발신·수신(응답) 모두 가능 |
| `UNSUPPORTED` | 권한 미부여 (API<24는 minSdk상 발생 안 함) | BLE 전체 비활성, TCP만 동작 |

`BleMeshManager.getMode(context)`가 판정, 모든 public 함수는 UNSUPPORTED면 조용히 no-op.

### 알려진 한계 / 엣지케이스 (2026-07-13, Android 7·8 혼합 배치 확정 후 정리)
1. **`CENTRAL_ONLY`끼리는 서로 영원히 못 찾음** — 둘 다 advertise 불가. 사이에 `FULL` 기기가 최소 1대 있어야 릴레이됨. 병동에 Android 8(광고 가능) 기기가 최소 1대는 있어야 이 기능이 의미가 있음.
2. **`MAX_CONNECTIONS = 8`** — 기기 1대가 동시에 유지 가능한 BLE 연결 수 캡. RSSI 기반 우선순위 교체 로직 없음(단순 선착순, bitchat 원본에는 있었으나 v1에서 제거). 7 기기 비중이 높으면 8 기기(허브 역할)에 연결 시도가 몰려 9번째부터 거부될 수 있음.
3. **API26+ 기기도 하드웨어가 advertise(peripheral)를 지원 안 할 수 있음** — `BluetoothAdapter.isMultipleAdvertisementSupported`로 체크만 하고 조용히 advertise를 안 켬. 이 경우 OS는 `FULL` 판정이어도 실질적으로 `CENTRAL_ONLY`처럼 동작. 사용자에게 별도 안내 없음.
4. **런타임 위치 권한(`ACCESS_FINE_LOCATION`, API<31) 거부 시** BLE 전체 비활성, TCP 전용 폴백. 재요청 로직 없음(1회 요청 후 Toast만).
5. **TTL 기본값 5홉.**
6. `CallItem`은 dedup 시 먼저 도착한 경로(TCP 또는 BLE)의 발신자 정보만 남음 — 반대 경로로만 존재하는 상대는 확인 응답을 못 받는 극단적 케이스가 이론상 존재(실무 영향은 낮음).
7. GATT Service/Characteristic UUID는 bitchat과 다른 신규 값 사용(`BleMeshConstants.SERVICE_UUID` 등) — 실제 bitchat 사용자와 혼선 방지 목적, 의도적 설계.
8. 실기기 미검증 항목(위 "상태" 참고) 전부.

### 테스트
- `app/src/test/kotlin/.../network/ble/*Test.kt` — 순수 로직(패킷 인코딩/디코딩, TTL relay, dedup, fragmentation, store-forward, BleMode 판정) 31개, Robolectric 불필요.
- `app/src/test/java/.../data/CallQueueTest.java`, `network/IncomingCallHandlerTest.java` — Robolectric 필요(CallQueue가 `Handler(Looper.getMainLooper())` 사용), TCP+BLE 동시 도착 dedup 시나리오 포함. 12개.
- 실행: `./gradlew :app:testDebugUnitTest`

### CALL/CANCEL도 유니캐스트로 전환 — 처음 설계는 틀렸음 (2026-07-16)
초기 구현은 `broadcastCall`/`broadcastCancel`로 `recipientPeerId = BROADCAST_RECIPIENT`를 써서 **모든 근처 기기**(다른 환자 태블릿 포함)가 `addressedToMe=true`로 판정해 자기 화면에 호출 알림을 띄우는 문제가 있었음. flood는 "메시지가 물리적으로 더 멀리 갈 수 있게" 중계하는 역할만 해야 하고, "누구 화면에 띄울지"는 별개 문제인데 이 둘을 구분 안 했던 게 원인. bitchat의 `PacketRelayManager.isPacketAddressedToMe`(recipientID가 특정 peerID와 일치할 때만 true, 그 외엔 중계만 하고 표시 안 함)를 참고해 수정:

- `network/ConfigSync.java`: 응답 JSON에 `blePeerId` 필드 추가(`AppSettings.getOrCreateBlePeerId()`) — 간호사 설정을 TCP로 한 번이라도 받아온 적 있으면 그 기기의 BLE peerId를 같이 학습.
- `data/AppSettings.java`: `KEY_NURSE_BLE_PEER_IDS`(IP→peerId 매핑 JSON) 신설. `getNursePeerId()`/`saveNursePeerId()`.
- `ui/AdminSettingsActivity.java`: IP 추가 시(`learnNurseBlePeerId`) + "설정 불러오기" 시 모두 peerId 학습 시도(백그라운드, 실패해도 조용히 무시).
- `network/ble/BleMeshManager.kt`: `broadcastCall`/`broadcastCancel` 삭제, `sendCallToPeer`/`sendCancelToPeer`(둘 다 `sendConfirmed`와 같은 `sendUnicast` 헬퍼 공유) 신설 — 셋 다 특정 peerId로만 유니캐스트.
- `ui/MainActivity.java`: `sendBleToKnownNursePeers()` — 등록된 간호사 IP 중 peerId를 학습해둔 것에게만 개별 유니캐스트. **peerId를 모르는 간호사는 이 경로에서는 그냥 빠짐**(모르는 상대에게 broadcast로 흘리는 것보다 안전한 선택) — 대신 아래 "BLE 전용 간호사" 경로로 커버됨.

**처음엔 놓쳤던 한계, 지금은 해결됨**: TCP 기반 peerId 학습은 한 번이라도 같은 네트워크에서 통신에 성공한 적이 있어야만 동작한다. **환자 태블릿이 병원 최초 설정 때부터 간호사와 아예 다른 네트워크에 있는 경우**(실제로 존재하는 시나리오로 확인됨)는 TCP가 원천적으로 안 되니 이 방법으로는 peerId를 영영 못 배움. 아래 "BLE 전용 간호사" 기능으로 별도 해결.

### BLE 전용 간호사 — 근처 기기 검색으로 직접 등록 (2026-07-16, IP/TCP 전혀 불필요)

한 번도 같은 네트워크였던 적 없는 간호사-환자 쌍을 위한 경로. bluetooth 기기 페어링과 같은 방식 — 관리자가 등록하려는 간호사 태블릿 물리적으로 가까운 곳에서 "근처 기기 검색"을 열면, ANNOUNCE로 감지된 기기 목록(이름 표시)이 뜨고 하나를 골라 IP 없이 peerId만으로 등록.

- `network/ble/BlePacket.kt`/`BleMeshManager.kt`: ANNOUNCE payload가 빈 배열이었던 것을 `AppSettings.getTabletName()`(매 전송 시점에 새로 읽음)으로 변경 — 사람이 알아볼 수 있는 이름 없이는 검색 목록이 무의미하기 때문.
- `network/ble/BlePeerManager.kt`: `updateName(peerIdHex, name)` — ANNOUNCE에서 받은 이름을 peerId별로 기억. `listOnline(): List<OnlinePeer>` — 현재 online(최근 `STALE_PEER_TIMEOUT_MS` 이내 감지)인 peer 전체를 이름과 함께 반환.
- `network/ble/BleMeshManager.kt`: `getDiscoveredPeers()`(Java에서 쓰는 `DiscoveredPeer(peerId, name)` 리스트), `triggerAnnounceNow()`(관리자가 검색 화면을 열 때 30초 주기를 안 기다리고 즉시 한 번 더 알림 — 검색 체감 속도 개선용).
- `model/BleOnlyNurse.java`(신규): `peerId` + `label`(등록 시점 이름 스냅샷). `data/AppSettings.java`: `KEY_BLE_ONLY_NURSES` 저장소, `getBleOnlyNurses/addBleOnlyNurse/removeBleOnlyNurse`.
- `ui/AdminSettingsActivity.java`: "통신 설정" 페이지에 "BLE 전용 간호사" 카드 신설(`bleOnlyNurseListContainer`, `btnScanNearbyPeers`). `showScanNearbyPeersDialog()`가 다이얼로그 열려있는 동안 1.5초마다 `getDiscoveredPeers()`를 다시 그려서 새로 감지되는 기기를 반영, 이미 등록된 peerId는 목록에서 제외.
- `ui/MainActivity.java`: `sendBleToBleOnlyNurses()` — TCP 경로가 원천적으로 없으므로(다른 네트워크) TCP 성공/실패와 무관하게 **항상** 시도. `sendCallMessage()`/`cancelCall()`이 IP 기반 폴백(`sendBleToKnownNursePeers`)과 별개로 병행 호출.
- **의도적으로 범위 제한**: 등록만 지원하고, 관리자 화면에 실시간 "몇 홉 거리에 있는지" 같은 mesh 시각화는 추가 안 함(BLE 진단 패널과 동일하게 가벼운 수준 유지).

### 근처 기기 검색 — 오등록 방지 안전장치 (2026-07-16, v2.1)

ANNOUNCE는 간호사/환자를 구분하지 않으므로 "근처 기기 검색" 목록에는 **간호사 태블릿뿐 아니라 근처의 다른 환자 태블릿도 그대로 뜬다** — 프로토콜 레벨에 role 필드가 없고, 표시되는 건 오직 `AppSettings.getTabletName()` 문자열뿐이라 최종 판단은 전적으로 사람이 이름을 읽고 하는 것. 잘못 등록하면 이후 호출이 엉뚱한 환자 태블릿으로 유니캐스트되어 간호사가 못 받는 사고로 이어짐. `AdminSettingsActivity.showScanNearbyPeersDialog()`에 경고 문구(⚠ 다른 환자 태블릿도 나타날 수 있음)를 추가하고, `renderNearbyPeerList()`의 "등록" 버튼에 두 번째 확인 다이얼로그(선택한 이름을 다시 보여주고 "등록"/"취소")를 넣어 즉시 1탭으로 등록되지 않게 함. QR코드 기반의 결정적 페어링(간호사가 자기 peerId를 QR로 표시 → 환자가 스캔)이 오등록을 구조적으로 막는 더 확실한 대안이지만, 새 라이브러리(ZXing/ML Kit)·카메라 권한·QR 생성 UI가 추가로 필요해 지금은 채택하지 않음 — 경고+확인 다이얼로그로 실사용에서 부족하다고 판단되면 그때 도입.

### 앱 버튼(유튜브/웨이브 등) 실행 안 되고 플레이스토어로만 가는 문제 (2026-08-05)

안드로이드 12 태블릿에서 `TYPE_APP` 버튼(유튜브, 웨이브)이 실행 안 되고 항상 플레이스토어로 이동하는 버그. 원인은 Android 11+(API30+)의 패키지 가시성(package visibility) 정책 — `<queries>` 선언 없이는 `PackageManager.getLaunchIntentForPackage(packageName)`(`ui/MainActivity.java` `startNewActivity()`)이 앱이 설치돼 있어도 무조건 `null`을 반환하고, 그 결과 코드가 "미설치"로 오판해 `market://` 인텐트로 폴백함. `AndroidManifest.xml`에 아래 `<queries>` 추가로 해결:

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

특정 패키지명(`com.google.android.youtube` 등)을 나열하는 대신 LAUNCHER 카테고리 전체를 질의하도록 선언 — `ButtonEditActivity`에서 관리자가 나중에 임의의 앱 버튼을 추가해도 매니페스트 재수정 없이 계속 동작하게 하기 위함. 이 시점에 `minSdk 24`~`targetSdk 33`(API 27/28 ~ Android 12/13) 범위 전반의 다른 호환성도 함께 점검함 — 알림 채널 O(26)+ 게이팅, `PendingIntent.FLAG_IMMUTABLE`, 잠금화면 표시 플래그, BLE 런타임 권한 분기, `exported` 명시 등은 이미 올바르게 처리되어 있어 별도 수정 불필요했음.

### 배포용 APK 서명 — 아직 디버그 키 (2026-08-05)

`release/` 폴더의 배포용 APK는 현재 **디버그 키스토어로 서명됨**(`apksigner verify --print-certs` 결과 `CN=Android Debug`). `app/build.gradle`에 release용 `signingConfig`가 없어서 릴리스 빌드가 디버그 키로 서명되는 상태 — 사용자가 실기기 검증 전까지는 이 상태를 유지하기로 확인함(2026-08-05 대화). **실제 병동 배포 직전에는 반드시 전용 release 키스토어를 만들어 `signingConfig`를 추가해야 함** — 그렇지 않으면 이 개발 환경이 아닌 다른 곳에서 빌드하거나 디버그 키스토어가 초기화될 때 서명이 달라져서, 이미 설치된 모든 태블릿에서 이후 업데이트가 막히고(전체 재설치 필요) 디버그 빌드라 `debuggable=true` 상태이기도 함. `release/` 폴더는 빌드 산출물이라 `.gitignore`에 추가해 git에는 커밋하지 않음.

**버전 관리 원칙(2026-08-05 확정)**: 병원 태블릿은 이미 `io.voyager.samsungbarunhospital`(신규 패키지명)로 재설치된 상태 확인됨 — 앞으로는 **서명 키만 안 바뀌면(=이 개발 환경 유지) `versionCode`를 올릴 때마다 재설치 없이 APK 덮어 설치로 업데이트 가능**. Play 스토어 등록 없이 관리자가 새 APK를 태블릿에 수동 전달(USB/파일 공유)해서 기존 앱 위에 설치하는 "수동 전달 + 버전 관리" 방식으로 가기로 함(자동 업데이트 확인 기능은 별도 서버 호스팅이 필요해 채택 안 함). **그래서 코드 수정 시마다 `app/build.gradle`의 `versionCode`를 반드시 올릴 것** — 값이 그대로면 기존 설치본과 같은 버전으로 인식돼 업데이트가 안 먹힐 수 있음. 이번 앱 실행 버그 수정으로 `versionCode 3→4`, `versionName "2.1"→"2.1.1"`로 올림 (`release/삼성바른병원_환자호출_v2.1.1.apk`).

### 웨이브 버튼 패키지명 오류 수정 (2026-08-05, v2.1.2)

`AppSettings.getDefaultButtonConfigs()`에 있던 웨이브 앱 패키지명 `com.movienet.touch.wavveon`이 애초에 실존하지 않는 패키지명이었음(웹 검색으로 확인 — APKMirror/APKPure/APKCombo/APKMonk 등 다수 소스가 일치해서 가리키는 실제 패키지명은 `kr.co.captv.pooqV2`, 구 "푹(pooq)" 서비스 시절 이름을 유지). `<queries>` 수정과 무관하게 패키지명 자체가 틀렸던 것이라, 그 수정만으로는 웨이브가 절대 안 열리는 상태였음. `kr.co.captv.pooqV2`로 교체하고 `versionCode 4→5`, `versionName "2.1.1"→"2.1.2"`로 올림 (`release/삼성바른병원_환자호출_v2.1.2.apk`).

**의도적으로 마이그레이션 코드는 안 넣음**: `getDefaultButtonConfigs()`는 버튼 설정을 한 번도 저장한 적 없는 새 기기에만 적용됨 — 이미 설정을 저장해본 태블릿은 SharedPreferences에 예전 잘못된 패키지명이 그대로 남아있어 자동으로 안 고쳐짐. 이번엔 사용자가 다른 태블릿에도 어차피 APK를 수동으로 옮겨줘야 해서(위 "수동 전달" 방식), 그 김에 **관리자 설정 → 앱 버튼 관리에서 웨이브 버튼을 열어 대상 패키지를 `kr.co.captv.pooqV2`로 직접 재저장**하기로 함 — 코드에 자동 마이그레이션 로직을 추가하는 대신 수동 작업으로 처리하기로 결정.

### 다른 Wi-Fi에서 IP로 등록한 간호사에게 BLE 호출이 안 가던 문제 (2026-10-07, v2.2.0)

증상: 서로 다른 망의 태블릿에서 간호사 IP를 등록하고 호출하면, 환자 쪽은 "✓ 간호사에게 전달되었습니다"가 뜨는데 간호사 쪽 알람이 안 뜸.

- **주원인**: IP 등록 시 TCP(`CONFIG_REQUEST`)로 BLE peerId를 학습하는데, 다른 망이면 이 접속이 실패하고 조용히 넘어감 → peerId 없음 → TCP 실패 후 BLE 폴백(`sendBleToKnownNursePeers`)이 실제로는 아무에게도 안 보냄. 그런데 `MainActivity`가 "BLE 지원 기기"이기만 하면 성공 토스트를 띄웠음.
- 수정(송신): `reportCallResult()` — TCP 성공="전달되었습니다", BLE로만 보냄="블루투스로 호출했습니다. 간호사 확인 시 '호출 완료'로 바뀝니다"(BLE는 ACK 없음 — 간호사가 확인을 누르면 CONFIRMED가 돌아와 배너가 "✓ 호출 완료"로 바뀜), 아무 경로도 없으면 실패 + 이유(블루투스 꺼짐 / '블루투스 연결' 필요). BLE 전송 헬퍼는 실제 전송 대상 수를 반환.
- 수정(관리자): IP 행마다 "마지막 연결 확인 시각 · 블루투스 연결됨/안 됨" 표시 + "테스트"/"블루투스 연결"/"삭제". "블루투스 연결"은 근처 BLE 기기를 골라 그 IP에 peerId를 직접 연결(`AppSettings.saveNursePeerId`) — 다른 망이라 TCP로 학습 못할 때의 경로. IP 추가 시 연결 실패하면 바로 이 연결을 권함. BLE 전용 간호사 등록과 같은 검색 다이얼로그(`showNearbyBlePicker`, 2차 확인 포함)를 공유.
- **연결 테스트는 일부러 새 PING 프로토콜을 안 만들고 `CONFIG_REQUEST`를 재사용** — 구버전 수신 측은 모르는 payload를 전부 환자 호출로 해석해서 가짜 알람이 뜨기 때문. 덤으로 peerId 학습도 됨. 성공 시각은 `AppSettings.saveNurseLastContact`(IP→epochMs).
- 수정(수신): `BleMeshService`가 수신 리스너를 직접 등록하고 peerId를 저장소에서 읽음(START_STICKY 재시작 시 intent=null이라 예전엔 BLE가 아예 안 켜지거나 리스너 없이 패킷이 버려질 수 있었음). 블루투스 ON/OFF 브로드캐스트를 받아 재기동. `BleMeshManager.start()`는 블루투스가 꺼져 있으면 "실행 중"으로 표시하지 않음. 진단 패널에 블루투스 켜짐/꺼짐 표시.
- 수정(GATT): Android는 우리가 클라이언트로 연결한 상대에 대해서도 GATT 서버 콜백을 올리는데, 이게 기존 클라이언트 연결 항목을 `subscribed=false` 서버 항목으로 덮어써 그 기기로의 전송이 조용히 건너뛰어질 수 있었음 → `BleGattServerManager`가 이미 클라이언트로 등록된 주소는 건드리지 않음.
- **실기기 미검증**(에뮬레이터는 BLE 없음). 현장 확인 순서: 환자 태블릿 관리자 화면에서 IP 행이 "블루투스: 연결됨"인지 → 아니면 간호사 태블릿 옆에서 "블루투스 연결" → Wi-Fi 끈 상태로 호출 → 간호사 알람 확인.

### UX/디자인 정리 (2026-10-07, v2.2.0, `feature/ux-and-design-polish`)
`colors.xml` 의미 기반 팔레트로 하드코딩 색 통합, `bg_button_*` 둥근 버튼(ripple), 테마 다크 베이스(AlertDialog가 보라/흰색이던 문제), Pretendard 글꼴(`res/font/pretendard.xml`, OFL, Regular/Bold — APK +3MB; 코드에서 굵기 바꿀 때 `setTypeface(null, …)` 쓰면 시스템 글꼴로 돌아가니 금지), 앱 버튼 편집에 "설치된 앱에서 선택", 버튼 개수 N/6 표시, 글씨 크기 실시간 미리보기, 메시지 저장 시점 안내 문구. 위급도 색상 단계는 사용자 요청으로 제외.

### 진단 탭 — 블루투스 자가진단 + 통신 실패 기록 (2026-10-07, v2.2.0)

개발자가 실기기 없이 APK만 병원에 전달하고 현장에서 확인하는 구조라, 블루투스 문제를 원격으로 판단하기 위해 추가. Wi-Fi(TCP)는 현장에서 잘 동작해서 **블루투스만 대상**.

- **실패 이벤트만 기록(사용자 결정)**: 정상 동작(시작/연결/정상 수신/중계/새 호출 표시 등)은 기록하지 않고, 통신이 실패했거나 실패할 수밖에 없는 상황만 원인과 함께 한 줄씩 남김 — 블루투스 꺼짐, "다른 태블릿이 이 태블릿을 찾을 수 있게 하기"(BLE 광고) 실패/미지원, 검색·연결 실패, 연결 상한 거부, 보낼 때 연결 0대/상대 미감지, 확인응답 보관(상대 미감지), Wi-Fi 실패 후 블루투스로도 못 보냄(블루투스 연결 안 된 간호사 등), 받았지만 처리기/수신 서비스 없음, 확인응답을 받았지만 표시 못함. 문제가 없으면 파일 자체가 안 생김.
- `data/DiagLog.java`: 파일 1개(`files/diag_events.log`), 줄 = 이벤트, 줄 앞 "yyyy-MM-dd HH:mm:ss". **보관 30일**(줄 단위 삭제). 정리 시점: init, 날짜가 바뀐 뒤 첫 이벤트 기록, **기록을 읽을 때마다**(진단 탭 표시·전송 — 이벤트가 한동안 없어도 기간 지난 이벤트가 절대 표시/전송되지 않게). 안전장치로 5MB 넘으면 오래된 절반 삭제. 수동 삭제는 삭제 후 파일이 정말 없는지 확인해 결과 표시. 쓰기는 전용 단일 스레드. 사용자 요구: "보관 기간과 수동 삭제는 확실해야 함" — `DiagLogTest`(Robolectric 6개)로 검증.
- **호출 메시지 내용은 기록 안 함**(메일로 외부 전송되므로) — 종류 + callId 앞 8자 + peerId 앞 8자만. 기기 MAC은 끝 5자.
- 관리자 화면 6번째 탭 "진단": 현장 안내 3단계, 자가진단(블루투스·권한·모드·"다른 태블릿이 이 태블릿을 찾을 수 있음"·연결 수·근처 기기·호출 대상 간호사별 감지 여부 → 경고 요약; 일반인용 표현으로 "광고"라는 말은 화면에 안 씀), **개발자에게 보내기**, 새로고침, 기록 전체 삭제(전송용 사본도 삭제).
- **개발자에게 보내기**: 인터넷(VALIDATED) 없으면 "인터넷에 연결되어 있지 않아…" 안내 후 취소. 메일 앱 없으면 한국어 안내 후 취소(`<queries>`에 mailto SENDTO 추가 — Android 11+ 패키지 가시성). 있으면 메일 앱을 받는 사람 voyagerahn@gmail.com·제목·본문(자가진단 요약)·첨부(헤더+자가진단+이벤트 전체, FileProvider `${applicationId}.diagfileprovider`)가 채워진 상태로 열고 직원이 "보내기"만 누름. **앱이 직접(SMTP) 자동 전송하지 않는 이유**: 메일 계정 비밀번호를 APK에 넣어야 해서(APK를 풀면 누구나 봄). 완전 자동 전송이 필요해지면 별도 서버/메일 API가 필요.
- 에뮬레이터(API 26)에서 확인: 자가진단 표시, 실패 이벤트만 기록됨(블루투스 꺼짐 1건), 메일 앱 없음 → 한국어 안내, 비행기 모드(오프라인) → 인터넷 없음 안내, 전체 삭제 후 파일 사라짐. **Gmail이 있는 실기기에서 메일 작성 화면이 첨부와 함께 열리는지는 미확인**(에뮬레이터에 메일 앱 없음). 에뮬레이터에서 `svc wifi disable`은 실제로 네트워크를 안 끊음 — 오프라인 테스트는 `adb root` 후 airplane mode 브로드캐스트 필요.

### 배포 전 안정성 점검 (2026-10-10, v2.2.0)

- **Android 7 실행 즉시 크래시 (기존 버그, 수정)**: `BleMeshService.start()`가 API 26+ 전용인 `Context.startForegroundService()`를 직접 호출 → Android 7(API 24/25)에서 BLE 권한이 있으면 `MainActivity.onCreate`에서 `NoSuchMethodError`로 홈 런처가 반복 크래시. `feature/ble-mesh` 병합 때부터 있던 것으로, lint `NewApi`가 잡음. `ContextCompat.startForegroundService()`로 교체. API 24/25 에뮬레이터 이미지가 이 환경에 없어 실행 확인은 못 함(lint 재검사로 해소 확인). **앞으로 `./gradlew lintDebug`의 Error는 배포 전 반드시 확인할 것.**
- GATT 클라이언트 핸들 누수: 블루투스가 꺼질 때는 STATE_DISCONNECTED 콜백(평소 `close()` 지점)이 안 올 수 있어 핸들이 쌓임(프로세스당 슬롯 제한) → `BleGattClientManager`가 연 핸들을 전부 추적(`openGatts`)해 `stop()`에서 직접 close. "연결됨+오류 status" 콜백도 끊김으로 처리(전엔 핸들이 영구히 열린 채 그 기기로 재연결 안 함). 클라이언트 끊김 시 같은 주소의 서버 연결 항목은 지우지 않음(서버 쪽 가드와 대칭).
- 관리자 화면 검색 다이얼로그가 열린 채 화면이 종료되면 1.5초 갱신 루프/NSD 검색이 Activity를 붙잡던 누수 → `onDestroy`에서 정리.
- 다른 기기에서 받아 저장한 peerId는 길이 미보장 → 화면 표시에 `substring(0, 8)` 대신 `shortId()`.
- 남은 lint Error(의도적으로 유지): `MissingPermission`(BleConnectionManager — 시작 시 권한 확인 + try/catch), `CoarseFineLocation`(FINE은 maxSdk 30이라 API 31+에선 요청 안 함 — 오탐), `NotificationPermission`(Android 13에서 POST_NOTIFICATIONS 미요청 → 상단 알림 배지만 안 보임. 호출 알람 화면·소리는 Activity로 뜨므로 무관. 기존부터 그랬음).

### 다음에 이어서 할 일
- **실제 배포 전 필수**: 전용 release 키스토어 생성 + `app/build.gradle`에 release `signingConfig` 추가(위 "배포용 APK 서명" 참고).
- Stage 5 실기기 검증(API26+ 2~3대, 가능하면 API24/25 기기도 섞어서): 계획 문서 9절 체크리스트 참고.
- 위 "알려진 한계" 2번(연결 캡 8개)·3번(하드웨어 advertise 미지원) — 실기기에서 문제로 확인되면 우선 대응 필요.
- "간호사 설정 불러오기" 기능도 실기기(또는 에뮬레이터 2대)로 실제 요청→응답→적용 흐름 검증 필요 — 지금까지는 유닛테스트(`ConfigSyncTest`)와 빌드 성공만 확인됨.
- BLE CALL 유니캐스트 타겟팅 실기기 검증: 환자 태블릿 3대(간호사 역할 1대 + 관계없는 환자 역할 2대)로, 관계없는 환자 태블릿들이 중계는 하되 자기 화면에는 절대 호출을 안 띄우는지 확인 필요.
- "근처 기기 검색"(BLE 전용 간호사 등록) 실기기 검증: ANNOUNCE 이름이 실제로 목록에 뜨는지, `triggerAnnounceNow()`로 체감 대기시간이 줄어드는지, 등록 후 실제로 다른 네트워크 상황(Wi-Fi 끄고)에서 호출이 도달하는지.

### 개발 환경 참고
adb 경로가 PATH에 없음 — `$HOME/Library/Android/sdk/platform-tools/adb`로 직접 호출. 에뮬레이터 실행 중이면 `adb devices -l`로 확인 후 `adb install -r app/build/outputs/apk/debug/app-debug.apk`, `adb shell monkey -p io.voyager.samsungbarunhospital -c android.intent.category.LAUNCHER 1`(실행), `adb exec-out screencap -p > 파일.png`(스크린샷)로 실제 동작을 직접 확인할 수 있었음.
- ~~`feature/ble-mesh` → `main` 병합은 실기기 검증 이후, 사용자 승인 후 진행.~~ → 2026-08-05 사용자 승인으로 실기기 검증 전에 이미 병합·push됨. Stage 5 실기기 검증은 여전히 필요(위 "상태" 참고), 지금은 사후 검증 형태로 진행해야 함.
