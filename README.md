# homedrive
Self-hosted Drive App

## Docker로 실행

Docker Desktop을 실행한 뒤 다음 명령을 사용합니다.

```bash
docker network create shared-proxy # 최초 1회
docker compose up -d --build
docker compose -f proxy/compose.yml up -d
```

Spring Primary는 `http://localhost:10001`, Secondary는 `http://localhost:10002`에서 직접 확인할 수 있습니다.
호스트에서 직접 접근할 경우 Redis는 `localhost:10003`, MySQL은 `localhost:10004`를 사용합니다.

별도 `shared-proxy` 프로젝트의 `shared-nginx`는 `http://localhost:10000`에서 요청을 받고,
HomeDrive 프로젝트의 `homedrive-spring-primary`와 `homedrive-spring-secondary`로 분산합니다.
다른 프로그램도 `shared-proxy` 네트워크와 [proxy/nginx.conf](proxy/nginx.conf)에 추가할 수 있습니다.

```bash
docker compose ps                                  # HomeDrive 상태
docker compose -f proxy/compose.yml ps             # 공용 Nginx 상태
docker compose logs -f app-primary app-secondary
docker compose down                                # HomeDrive 중지
docker compose -f proxy/compose.yml down            # 공용 Nginx 중지
docker compose down -v                             # HomeDrive 데이터까지 삭제
```

기본 로컬 비밀번호를 변경하려면 프로젝트 루트에 `.env` 파일을 만들 수 있습니다.

```dotenv
MYSQL_PASSWORD=change-this-password
MYSQL_ROOT_PASSWORD=change-this-root-password
JWT_SECRET=change-this-to-a-random-secret-at-least-32-characters
```
