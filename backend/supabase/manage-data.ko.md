# 서버 데이터 직접 관리

Supabase 프로젝트 `kqldlmcpjhsuiaayhasi`의 Table Editor 또는 SQL Editor를 사용합니다.

- `users`: 연동 계정. 여러 기기가 같은 `user_id`를 사용해야 같은 일정을 공유합니다.
- `user_settings`: 앱 이름, 아이콘, 초기화 시간, 휴대폰 설치 여부(`app_installed`: 1 설치, 0 미설치, -1 미확인).
- `user_tasks`: 일정과 완료 상태. 동일 일정의 식별자는 `user_id`, `package_name`, `category`, `task_id` 조합입니다.
- `pairing_codes`: 일시적인 연동 코드. 일정 편집에는 사용하지 않습니다.

수정 전 Table Editor에서 해당 테이블을 CSV로 내보내 보관합니다. 아래 예시의 `계정_ID`, `패키지명`, `일정_ID`를 실제 값으로 바꿉니다. 먼저 SELECT로 대상을 확인하세요.

```sql
SELECT id, email FROM users;

SELECT package_name, app_name, app_installed, updated_at
FROM user_settings WHERE user_id = '계정_ID'
ORDER BY app_name;

SELECT package_name, category, task_id, title, done,
       CASE WHEN deadline > 0 THEN to_timestamp(deadline / 1000.0) END AS deadline_at,
       is_deleted
FROM user_tasks WHERE user_id = '계정_ID'
ORDER BY package_name, category, title;
```

## 제목 수정

동기화가 변경을 인식하도록 `updated_at`도 갱신합니다. 시간은 밀리초 단위입니다.

```sql
UPDATE user_tasks
SET title = '수정한 제목',
    updated_at = GREATEST(updated_at + 1, (extract(epoch FROM clock_timestamp()) * 1000)::bigint)
WHERE user_id = '계정_ID' AND package_name = '패키지명'
  AND category = 'Event' AND task_id = '일정_ID'
RETURNING package_name, task_id, title;
```

## 일정 삭제

행을 DELETE하면 다른 기기에 남은 복사본이 다시 올라올 수 있습니다. 대신 삭제 표시를 남겨 각 기기가 삭제를 받게 합니다.

```sql
UPDATE user_tasks
SET is_deleted = 1,
    updated_at = GREATEST(updated_at + 1, (extract(epoch FROM clock_timestamp()) * 1000)::bigint)
WHERE user_id = '계정_ID' AND package_name = '패키지명'
  AND category = 'Event' AND task_id = '일정_ID'
RETURNING package_name, task_id, is_deleted;
```

## 중복 후보 확인

제목이 같아도 다른 일정일 수 있으므로 후보를 조회한 후 삭제할 ID를 선택합니다.

```sql
SELECT package_name, category, title, count(*) AS count,
       array_agg(task_id) AS task_ids
FROM user_tasks
WHERE user_id = '계정_ID' AND is_deleted = 0
GROUP BY package_name, category, title
HAVING count(*) > 1;
```

앱 이름·아이콘·설치 여부는 휴대폰이 제공하는 정보입니다. 서버에서만 고치면 다음 모바일 동기화에서 다시 변경될 수 있습니다. 패키지명이나 일정 ID를 수정하면 다른 일정으로 취급되므로 표시 이름을 바꾸기 위해 식별자를 수정하지 마세요.
