# Supabase 연결 순서

1. Supabase Free 프로젝트를 생성합니다. DB 비밀번호는 비밀번호 관리자에 저장합니다.
2. 기존 자동 동기화를 잠시 중단하고 Wrangler로 D1을 SQL 파일로 내보냅니다.
3. Supabase Connect에서 Transaction pooler 연결 문자열을 확인합니다. 비밀번호를 채팅이나 Git에 저장하지 않습니다.
4. 로컬 환경변수 SUPABASE_DATABASE_URL에 연결 문자열을 설정합니다.
5. backend에서 `node supabase/import-d1.mjs <D1 SQL 파일>`을 실행합니다. 비어 있는 대상 DB에만 계정과 일정 데이터를 가져옵니다.
6. Worker의 SUPABASE_DATABASE_URL secret을 설정합니다. 운영 Wrangler 설정에 nodejs_compat를 추가한 뒤 배포합니다.
7. 기존 JWT_SECRET은 유지합니다. 계정 ID를 그대로 이전하므로 기존 연동도 유지합니다.
8. 두 기기 간 추가·완료·해제·삭제를 확인한 후 자동 동기화를 재개합니다.

이벤트 수집 데이터는 기존 D1에 남습니다. Supabase 연결을 설정하지 않으면 기존 D1 동작을 유지합니다.

운영 동기화는 HYPERDRIVE 바인딩을 우선 사용합니다. Hyperdrive는 Supabase CA와 verify-full로 원본 DB를 검증하며 SQL 캐시는 끕니다. Worker와 Hyperdrive 사이의 내부 연결에는 별도 TLS를 설정하지 않고, Hyperdrive에서 원본 DB까지 TLS 검증을 수행합니다. 무료 Hyperdrive의 SQL 요청 한도는 일 100,000회입니다. 운영 프로젝트와 바인딩은 wrangler.production.jsonc에 저장됩니다.
