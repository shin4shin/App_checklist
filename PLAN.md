# 스케줄러 앱 — 현황 및 계획

## 앱 개요

게임을 실행하면 할 일 오버레이가 뜨고, 탭별로 데일리/위클리/이벤트 앱 목록을 관리할 수 있는 Android 앱.

---

## 현재 완성된 기능

### UI / 네비게이션
- **바텀 네비게이션 5탭**: 홈 · 데일리 · 위클리 · 이벤트 · 더보기
- 탭 전환은 새 Activity 없이 Fragment 인라인 교체
- MD3 Blue 단일 팔레트 (Primary `#2563EB`, Secondary `#3B82F6`)
- 앱 진입 시 미설정 권한 알림 다이얼로그

### 앱 관리 (DailyFragment — 탭별 공유)
- 설치된 앱 목록에서 추가 / 삭제
- 드래그 가능한 FAB으로 앱 추가
- 앱별 할 일 목록 설정 (탭별 카테고리 분리)
  - 데일리 탭 → Daily 카테고리만
  - 위클리 탭 → Weekly 카테고리만
  - 이벤트 탭 → Event 카테고리만
- 정렬 (가나다 / 시간 순), 다중 선택 삭제, 일괄 시간 설정
- 일일 초기화 AlarmManager 스케줄링

### 오버레이 (GameOverlayService)
- AccessibilityService로 실행 중 앱 감지
- 등록된 앱 실행 시 오버레이 자동 표시
- 등록 앱 벗어나면 500ms 디바운스 후 숨김
- 오버레이 꾹 누르면 삭제

### 위젯 (3종)
- **HomeworkWidget** (대형): 9개 앱 행 + 페이지 이동
- **SmallWidget** (중형): 동일 구조, 소형
- **MiniWidget** (2×2): 아이콘 그리드 4칸
- 디자인: 딥 네이비 블루 배경, 블루 강조 헤더

### 더보기 탭
- 오버레이 · 접근성 · 알람 권한 상태 확인 및 설정 이동
- 프리미엄 카드 (미구현)

---

## 데이터 구조

```
SharedPreferences "added_apps"
  └─ "apps": Set<String>  (패키지명 목록)

SharedPreferences "app_tasks"
  └─ <packageName>: JSON
       {
         "Daily":   ["퀘스트1", "퀘스트2"],
         "Weekly":  ["주간 목표"],
         "Event":   ["이벤트 미션"]
       }
```

---

## 미완성 / 할 일

### 기능 분리 (우선순위 높음)
- [ ] 위클리 / 이벤트 탭이 데일리와 **앱 목록 자체를 공유** 중 → 탭별 독립 앱 목록 필요
  - `added_apps` → `added_apps_daily` / `added_apps_weekly` / `added_apps_event` 분리
- [ ] 위클리 초기화 주기 → 매주 특정 요일 AlarmManager
- [ ] 이벤트는 초기화 없음, 수동 완료 처리

### 오버레이 개선
- [ ] 오버레이에 할 일 목록 실제 표시 (현재는 앱 이름만)
- [ ] 카테고리별 탭 전환 (데일리 / 위클리 / 이벤트)
- [ ] 진행률 표시 (완료 N / 전체 N)

### 홈 탭
- [ ] 오늘 날짜 + 전체 완료율 요약 카드
- [ ] 미완료 앱 수 뱃지

### 위젯
- [ ] 위젯에서 앱 직접 실행 (현재 btnLaunch 있으나 연결 여부 확인 필요)
- [ ] 완료 체크 위젯에서 직접 토글

### 기타
- [ ] 프리미엄 기능 정의
- [ ] 앱 아이콘 / 스플래시 스크린 디자인
- [ ] 백업 / 복원 기능

---

## 파일 구조

```
app/src/main/java/.../
├── HomeActivity.kt          — 바텀 네비게이션 셸
├── HomeFragment.kt          — 홈 탭 (날짜 + 바로가기)
├── DailyFragment.kt         — 데일리/위클리/이벤트 공용 앱 목록 UI
├── MoreFragment.kt          — 권한 설정 탭
├── AppAdapter.kt            — 앱 목록 RecyclerView 어댑터
├── Apppickeradapter.kt      — 앱 추가 다이얼로그 어댑터
├── GameOverlayService.kt    — 접근성 서비스 + 오버레이
├── HomeworkWidget.kt        — 대형 위젯
├── SmallWidget.kt           — 중형 위젯
├── MiniWidget.kt            — 2×2 미니 위젯
├── HomeworkWidgetService.kt — 위젯 RemoteViews 서비스
├── ResetReceiver.kt         — 일일 초기화 브로드캐스트
└── BootReceiver.kt          — 부팅 후 알람 재등록
```
