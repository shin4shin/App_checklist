# 게임 이벤트 API

Cloudflare Workers + D1. 젠레스 존 제로(`zzz`), 붕괴: 스타레일(`hsr`)을
Ennead 한국어 공개 JSON에서 10분마다 수집합니다. 앱의 개인 일정·완료 상태는 수집하지 않습니다.

## 로컬 실행

Node.js 22.13 이상이 필요합니다.

```sh
cd backend
npm ci
npm test
npm run db:local
npm run dev
```

첫 수집은 개발 서버에서 다음 URL을 호출합니다.

```sh
curl "http://localhost:8787/__scheduled?cron=*/10+*+*+*+*"
curl "http://localhost:8787/v1/events?game=zzz"
```

## API

- `GET /v1/events`: 두 게임의 저장된 이벤트
- `GET /v1/events?game=zzz` 또는 `game=hsr`
- `status=all|active|upcoming|ended|unverified`: 선택 필터
- `GET /health`: 게임별 수집 성공·오류·갱신 시각

원본을 못 받아도 마지막 정상 데이터를 유지합니다. 초기 데이터가 없으면 503을 반환합니다.
한 게임만 실패하면 다른 게임 수집은 계속됩니다. 최근 오류 또는 마지막 성공 후 1시간 경과 시
`sources[].stale=true`입니다. 종료 이벤트도 이력 확인을 위해 반환하며 상태 필터로 제외합니다.
`fetchedAt`은 수집 시각일 뿐 원본의 공지 갱신 시각이 아닙니다.

### 날짜·언어

Ennead의 비공식 HoYoverse API에서 `lang=ko-kr`로 한국어 이벤트를 수집합니다.
원본 Unix 초 단위를 UTC ISO 날짜로 변환하며 `language=ko-KR`, `timeZoneStatus=resolved`입니다.
한국 시간 표시는 클라이언트에서 `Asia/Seoul`로 변환합니다. 서버 지역별 차이는 공식 게임 공지와 대조해야 합니다.
이벤트 목록만 수집하며 배너·도전 목록은 포함하지 않습니다.
ID는 게임과 수집원 이벤트 ID로 구성합니다. 제목·기간 변경에도 유지됩니다.
이전 영어 수집원의 ID와는 다릅니다. 원본이 제공하지 않는 버전은 null입니다.
빈 목록·중복 ID·잘못된 시각은 수집 실패로 처리하고 마지막 정상 데이터를 유지합니다.

## Cloudflare 배포 — events.yulda.kr

`wrangler.jsonc`의 DB ID는 로컬 검증용 자리표시자입니다. 다음 과정으로 실제 DB에 연결합니다.
계정 인증 토큰은 저장소에 넣지 않습니다.

```sh
npx wrangler login
npx wrangler d1 create checklist-events
```

`wrangler.jsonc`를 `wrangler.production.jsonc`로 복사하고 반환된 실제 `database_id`를 입력합니다.
루트 사이트를 변경하지 않도록 production 설정의 최상위에 다음을 추가합니다.

```json
"routes": [{ "pattern": "events.yulda.kr", "custom_domain": true }]
```

```sh
npx wrangler d1 migrations apply DB --remote --config wrangler.production.jsonc
npx wrangler deploy --dry-run --config wrangler.production.jsonc
npx wrangler deploy --config wrangler.production.jsonc
```

예약 실행은 배포 후 Cloudflare에서 동작합니다. 로컬 코드 작성만으로 원격 수집이 시작되지는 않습니다.
첫 예약 실행 후 `/health`와 `/v1/events`를 확인합니다. Android 앱의 불러오기 화면·API 연결은 별도 작업입니다.

출처: https://github.com/torikushiii/hoyoverse-api
Cloudflare: https://developers.cloudflare.com/workers/configuration/cron-triggers/

현재 배포 주소: https://events.yulda.kr/v1/events
상태 확인: https://events.yulda.kr/health
운영 DB: checklist-events (01fb8158-2a2c-40d8-b800-76915155bc71).
초기 데이터는 실제 수집 결과로 등록했습니다. 예약 실행 여부는 health의 lastAttemptAt으로 확인합니다.
