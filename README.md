# homedrive
Self-hosted Drive App

## Docker로 실행

Docker Desktop을 실행한 뒤 다음 명령을 사용합니다.

```bash
docker compose up -d --build
```

웹 페이지는 `http://localhost:10001`에서 확인할 수 있습니다.
호스트에서 직접 접근할 경우 Redis는 `localhost:10002`, MySQL은 `localhost:10003`을 사용합니다.

```bash
docker compose ps       # 실행 상태
docker compose logs -f app
docker compose down     # 컨테이너 중지
docker compose down -v  # 컨테이너와 로컬 데이터 삭제
```

기본 로컬 비밀번호를 변경하려면 프로젝트 루트에 `.env` 파일을 만들 수 있습니다.

```dotenv
MYSQL_PASSWORD=change-this-password
MYSQL_ROOT_PASSWORD=change-this-root-password
JWT_SECRET=change-this-to-a-random-secret-at-least-32-characters
```
