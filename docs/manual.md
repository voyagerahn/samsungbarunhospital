---
pdf_options:
  format: A4
  margin: 30mm 25mm 30mm 25mm
  printBackground: true
stylesheet: |
  @import url('https://fonts.googleapis.com/css2?family=Noto+Sans+KR:wght@400;500;700;900&display=swap');

  * {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
  }

  body {
    font-family: 'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif;
    font-size: 13px;
    line-height: 1.8;
    color: #2c3e50;
    background: #fff;
  }

  /* 표지 헤더 */
  .cover {
    text-align: center;
    padding: 20px 0 36px 0;
    border-bottom: 4px solid #1565C0;
    margin-bottom: 36px;
  }

  .cover .badge {
    display: inline-block;
    background: #1565C0;
    color: #fff;
    font-size: 11px;
    font-weight: 700;
    padding: 4px 14px;
    border-radius: 20px;
    letter-spacing: 1px;
    margin-bottom: 14px;
  }

  .cover h1 {
    font-size: 26px;
    font-weight: 900;
    color: #1565C0;
    margin-bottom: 8px;
  }

  .cover .subtitle {
    font-size: 13px;
    color: #666;
  }

  /* 섹션 제목 */
  h2 {
    font-size: 16px;
    font-weight: 700;
    color: #fff;
    background: #1565C0;
    padding: 10px 16px;
    border-radius: 6px;
    margin: 32px 0 16px 0;
  }

  h3 {
    font-size: 13px;
    font-weight: 700;
    color: #1565C0;
    border-left: 4px solid #1565C0;
    padding-left: 10px;
    margin: 20px 0 10px 0;
  }

  p {
    margin-bottom: 10px;
    color: #444;
  }

  /* 리스트 */
  ul, ol {
    padding-left: 20px;
    margin-bottom: 12px;
  }

  li {
    margin-bottom: 5px;
    color: #444;
  }

  /* 인라인 코드 (IP, 비밀번호 등) */
  code {
    background: #E3F2FD;
    color: #1565C0;
    font-family: 'Courier New', monospace;
    font-size: 12px;
    font-weight: 700;
    padding: 2px 7px;
    border-radius: 4px;
  }

  /* 표 */
  table {
    width: 100%;
    border-collapse: collapse;
    margin: 12px 0 20px 0;
    font-size: 12.5px;
  }

  thead tr {
    background: #1565C0;
    color: #fff;
  }

  thead th {
    padding: 10px 14px;
    text-align: left;
    font-weight: 700;
  }

  tbody tr:nth-child(even) {
    background: #F5F9FF;
  }

  tbody tr:hover {
    background: #E3F2FD;
  }

  td {
    padding: 9px 14px;
    border-bottom: 1px solid #dde;
    color: #333;
  }

  /* 경고/안내 박스 */
  blockquote {
    background: #FFF8E1;
    border-left: 5px solid #FFC107;
    padding: 12px 16px;
    border-radius: 0 6px 6px 0;
    margin: 14px 0;
    color: #5D4037;
    font-size: 12.5px;
  }

  blockquote p {
    margin: 0;
    color: #5D4037;
  }

  blockquote strong {
    color: #E65100;
  }

  /* hr 구분선 */
  hr {
    border: none;
    border-top: 1px solid #e0e0e0;
    margin: 28px 0;
  }

  /* strong */
  strong {
    color: #1565C0;
    font-weight: 700;
  }
---

<div class="cover">
  <div class="badge">사용 설명서 v1.0</div>
  <h1>삼성바른병원 환자 호출 시스템</h1>
  <p class="subtitle">환자 태블릿 · 간호사 태블릿 설치 및 운영 가이드</p>
</div>

## 시스템 개요

환자 태블릿에서 호출 버튼을 누르면 간호사 태블릿에 즉시 알림이 전달되는 시스템입니다.
같은 WiFi 네트워크에 연결된 태블릿 간에 TCP 통신으로 동작하며, 인터넷 연결 없이 병원 내부 네트워크만으로 작동합니다.

---

## 설치 전 준비사항

> **필수 조건**: 환자 태블릿과 간호사 태블릿이 **같은 WiFi(무선 네트워크)**에 연결되어 있어야 합니다.

1. 모든 태블릿에 APK 파일 설치
2. 앱 실행 후 **알림 권한 허용**
3. 배터리 절약 모드에서 앱 **예외 처리** (백그라운드 실행 허용)

---

## 간호사 태블릿 설정

### 1단계 — 관리자 설정 진입

- 화면 **왼쪽 빈 영역**을 빠르게 **4번 연속 탭**
- 관리자 비밀번호 입력 (기본값: `0819`)
- **앱 관리자 설정** 선택

### 2단계 — 태블릿 이름 설정

- 태블릿 이름란에 `간호사 스테이션` 등 고유한 이름 입력
- 이 이름이 환자 호출 알림에 발신자로 표시됩니다

### 3단계 — IP 주소 확인

- 설정 화면 상단 **"이 기기의 IP 주소"** 확인
- 예: `192.168.1.105` → 이 번호를 메모해두세요 (환자 태블릿 설정에 필요)

### 4단계 — 저장

- 하단 **저장** 버튼 클릭

> **주의**: 간호사 태블릿은 **앱이 항상 실행 중**이어야 호출을 받을 수 있습니다. 앱을 완전히 종료하지 마세요.

---

## 환자 태블릿 설정

### 1단계 — 관리자 설정 진입

- 화면 **왼쪽 빈 영역**을 빠르게 **4번 연속 탭**
- 관리자 비밀번호 입력

### 2단계 — 태블릿 이름 설정

- `1호실 1번`, `2호실 3번` 등 병실/병상을 구분하는 이름 입력
- 간호사 알림 화면에 이 이름이 표시됩니다

### 3단계 — 간호사 태블릿 IP 등록

- **간호사 태블릿 IP 목록** 섹션 → **IP 추가** 버튼 클릭
- 간호사 태블릿에서 메모한 IP 입력 (예: `192.168.1.105`)
- 간호사 태블릿이 여러 대인 경우 모두 추가

### 4단계 — 호출 메시지 설정 (선택)

- 호출 시 선택할 메시지 목록 편집 가능
- 예: `너무 아파요`, `주사약이 다 들어갔어요`, `화장실 도움이 필요해요`

### 5단계 — 저장

---

## 일상 사용 방법

### 환자 태블릿

| 동작 | 기능 |
|------|------|
| 메인 화면 앱 버튼 탭 | 연결된 앱 또는 웹페이지 실행 |
| 🔔 벨 버튼 탭 | 호출 메시지 선택 창 표시 |
| 메시지 선택 후 전송 | 간호사에게 호출 전달, 전달 확인 알림 표시 |

### 간호사 태블릿

| 상황 | 동작 |
|------|------|
| 환자 호출 수신 | 화면에 알림 표시 |
| 알림 탭 | 호출 목록 화면으로 이동 |
| 처리 완료 | 해당 호출 항목 삭제 |

---

## 유의사항

| 항목 | 내용 |
|------|------|
| 네트워크 | 환자·간호사 태블릿 모두 같은 WiFi 연결 필수 |
| 앱 실행 유지 | 간호사 태블릿은 앱이 항상 켜져 있어야 호출 수신 가능 |
| IP 변경 | WiFi 재연결 시 IP가 바뀔 수 있음 → 환자 태블릿에서 IP 재확인 후 재등록 |
| 태블릿 이름 | 각 태블릿마다 서로 다른 이름 설정 권장 |
| 비밀번호 | 관리자 비밀번호 분실 시 앱 재설치 필요 |
| 배터리 | 간호사 태블릿은 항상 충전 상태 유지 권장 |

---

## 문제 해결

### "전달 실패" 메시지가 뜰 때

1. 간호사 태블릿에서 앱이 실행 중인지 확인
2. 두 태블릿이 같은 WiFi에 연결되어 있는지 확인
3. 환자 태블릿 관리자 설정에서 간호사 IP가 올바르게 입력되어 있는지 확인
4. WiFi 재연결 후 간호사 IP가 바뀌었을 수 있음 → 간호사 태블릿 IP 재확인 후 재등록

### 간호사 태블릿에 알림이 안 뜰 때

1. 태블릿 알림 설정에서 앱 알림 허용 여부 확인
2. 앱 완전 종료 후 다시 실행

---

## 기본 관리자 정보

| 항목 | 값 |
|------|-----|
| 기본 비밀번호 | `0819` |
| 비밀번호 변경 위치 | 관리자 설정 → 비밀번호 항목 |
| 관리자 진입 방법 | 왼쪽 빈 영역 4번 연속 탭 |
