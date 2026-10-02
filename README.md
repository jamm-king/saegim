# 새김 · Saegim

AI와 나눈 대화를 다음 날 다시 떠올리는 개인 학습 웹 프로토타입.

## 검증할 질문

어제 AI와 나눈 대화를 오늘 질문으로 다시 떠올리는 경험이 사용자에게 도움이 되고, 계속 쓰고 싶은가?

첫 사용자는 프로젝트 소유자 한 명이다. 로컬 단일 사용자용 일반 대화와 전날 대화 기반 회상 복습 흐름을 구현했다.

## 확정된 기술 방향

- 프론트엔드: Next.js, Tailwind CSS
- 백엔드: Kotlin, Spring Boot WebFlux
- 데이터베이스: MySQL
- 로컬 실행: Docker Compose
- DB 접근: Spring Data R2DBC + `io.asyncer:r2dbc-mysql` (Spring Boot 관리 버전)
- AI 공급자: OpenAI API. 테스트용 API 키는 준비되어 있으며 서버 환경변수로만 받는다.
- 기본 모델: `gpt-6-luna`. `OPENAI_MODEL`로 바꿀 수 있다. Luna는 대화·복습 모두 `reasoning.effort: none`으로 호출하여 기존의 짧은 출력 한도를 유지한다. 웹 검색 등 도구는 활성화하지 않는다.
- 실행은 로컬 전용이며 웹/API 포트를 `127.0.0.1`에 바인딩한다. 외부 배포와 인증 방식은 미정이다.

## 로컬 실행

Docker Desktop의 Linux 컨테이너 엔진이 실행 중이어야 한다. 호스트에 Gradle이나 npm을 별도 설치할 필요는 없다.

```powershell
Copy-Item .env.example .env
# .env에서 OPENAI_API_KEY, DB_PASSWORD, MYSQL_ROOT_PASSWORD를 설정한다.
docker compose up --build -d
docker compose ps
```

이미 `.env`가 있다면 덮어쓰지 않는다. 이 작업에서 기존 `.env.test`를 바탕으로 비공개 `.env`를 준비했다. 키와 비밀번호는 출력하지 않았고, 두 파일 모두 Git 제외 대상이다. `.env.example`에는 자리표시자만 있다.

웹: http://127.0.0.1:3000 / API 상태: http://127.0.0.1:8080/actuator/health

첫 기동은 이미지와 의존성 다운로드로 시간이 걸린다. `docker compose logs api --tail 50`에서 기동 완료를 확인한다. API 상태가 `UP`이면 웹에서 질문을 보낸다.

중지: `docker compose down`. 대화는 MySQL 볼륨에 유지된다. `docker compose down -v`는 저장된 대화를 삭제하므로 초기화할 때만 사용한다.

## 현재 동작

- 단일 대화 화면과 입력창, 원문 저장·조회, 20개씩 과거 기록 추가 로딩
- 일반 대화와 복습 메시지에 마크다운 표시를 지원한다: 굵게·기울임·제목·목록·인용·링크·코드·표. 긴 코드와 표는 박스 안에서 가로 스크롤한다. 원문 저장은 유지하며 HTML 실행과 외부 이미지 자동 로딩은 허용하지 않는다.
- 서버에서 OpenAI Responses API 호출. 목 답변은 사용하지 않는다.
- 질문 6,000자 제한, 최근 완료 메시지 최대 20개를 AI 맥락으로 전달한다. 화면에 표시하는 기록과 AI 맥락은 별개다.
- 맥락이 60,000자를 넘으면 조용히 잘라내지 않고 오류로 처리한다.
- 일반 대화 AI 출력은 최대 1,200토큰으로 제한하며, 제한에 걸려 미완료 상태로 반환된 응답은 실패로 처리한다.
- 질문을 먼저 저장한다. AI 실패 시 기록에서 다시 시도할 수 있으며, 같은 요청 ID의 재전송은 메시지 중복을 만들지 않는다.
- 응답과 질문 완료 상태는 같은 DB 트랜잭션에서 저장한다.
- UTC로 저장하고 화면 날짜·시각은 Asia/Seoul로 표시한다.
- 화면은 키 설정 여부와 실제 응답 기록 여부를 구분한다. 실제 응답 기록이 있어도 현재 연결 성공을 보장하지는 않는다.

- 첫 접속 시 한국 시간 전날의 완료된 일반 대화에서 회상 질문을 최대 3개 준비한다. 별도 배치나 알림은 없다.
- 복습 시작을 선택하면 같은 대화 공간에서 한 문제씩 답변·힌트·피드백을 진행한다. 언제든 오늘 복습을 건너뛰고 일반 대화로 돌아갈 수 있다.
- 날짜별 질문과 진행 상태를 DB에 보관하여 재접속·새로고침 때 재사용한다. 기대 답과 근거 ID는 공개 API에 반환하지 않는다.
- 전날 대화 없음, 생성 성공 후 질문 0개, 생성 실패를 구분한다. 실패한 생성과 답변은 재시도할 수 있다.
- 복습 메시지는 `REVIEW`로 구분하여 일반 대화 맥락과 다음 날 출제 대상에서 제외한다.
- 복습 생성은 원문 60,000자·출력 2,500토큰, 힌트·피드백은 출력 1,000토큰으로 제한한다. 입력을 일부만 잘라서 출제하지 않는다.

단일 API 인스턴스로 실행하며 다중 인스턴스 동시 요청은 현재 범위에 포함하지 않는다. 복습 데이터와 상태·검증 기록은 [docs/REVIEW.md](docs/REVIEW.md)를 참고한다.

오늘 처음 대화를 시작했다면 복습이 없는 것이 정상이다. 다음 날 접속하면 오늘 대화로 질문을 준비한다. 며칠 만에 접속해도 기본 대상은 전날이며 밀린 복습을 몰아서 생성하지 않는다.

## 확인 방법

`docker compose build`에서 백엔드 OpenAI HTTP 연동 테스트와 웹 TypeScript 검사 및 프로덕션 빌드를 실행한다. 백엔드 테스트는 테스트 서버의 응답을 사용하며 실제 AI 호출과 구분한다.

```powershell
./scripts/smoke.ps1
```

위 스크립트는 실제 OpenAI 응답 1회를 생성하고, 저장·중복 요청·재시도 재사용·입력 검증을 확인한다. API 사용료가 발생하며 테스트 질문과 답변이 대화 기록에 남는다. 키와 답변 내용은 출력하지 않는다.

```powershell
./scripts/verify-review.ps1
```

복습 테스트는 `saegim_review_test`라는 별도 MySQL DB와 테스트 AI 서버에서 실행한다. 해당 테스트 DB의 기록을 초기화하며 일반 대화 DB `saegim`에는 영향을 주지 않는다. 실제 OpenAI 호출 없이 날짜 경계, 재사용, 실패 재시도, 진행·완료·건너뛰기, 입력 한도와 근거 검증을 확인한다. 실행 결과는 `apps/api/build/reports/tests/test/index.html`에서 확인할 수 있다.

테스트 시계가 필요하면 서버 환경변수 `SAEGIM_TEST_NOW`에 UTC 시각을 지정할 수 있다. 예를 들어 `2026-10-02T03:00:00Z`는 한국 시간 10월 2일 정오로, 복습 대상은 10월 1일이다. 평소에는 비워 둔다. 이 설정은 복습 날짜 판단용이며 기존 메시지의 시각을 수정하지 않는다.

사용 버전: Java 21, Spring Boot 4.1.1, Kotlin 2.3.21, Gradle Wrapper 9.7.1, Next.js 16.3.8, React 19.3.0, Tailwind CSS 4.3.3, MySQL 8.4. 웹 의존성은 `package-lock.json`으로 고정한다.

## 문서 읽는 순서

1. `AGENTS.md`: Codex 작업 지침
2. `docs/PROTOTYPE.md`: 사용자 경험, 데이터와 구현 범위
3. `docs/START_HERE.md`: 새 Codex 채팅 시작용 프롬프트와 작업 순서

권장 프로젝트 경로는 `C:\workspaces\saegim`이다. 이 폴더를 Codex의 프로젝트로 열고 `docs/START_HERE.md`의 시작 프롬프트를 사용한다.

## 디렉터리 구조

```text
saegim/
  AGENTS.md
  README.md
  docs/
  apps/
    api/       Kotlin / Spring Boot WebFlux
    web/       Next.js / Tailwind CSS
  compose.yaml
  .env.example
```

현재 구현을 초기 스켈레톤 기준점으로 Git에 기록한다. 원격 저장소는 https://github.com/jamm-king/saegim 이며 외부 배포는 하지 않았다.

## 참고 프로젝트

- https://github.com/jamm-king/time-archive
- 사용자 선호와 `apps/api`, `apps/web` 구조를 참고한다. 기존 프로젝트의 기능이나 인프라를 그대로 복제하지 않는다.
- 이전 대화에서는 README와 상위 폴더 구조만 확인했다. 의존성 및 구현 코드 전체를 검토한 상태는 아니다.
