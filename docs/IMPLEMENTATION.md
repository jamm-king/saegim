# 첫 일반 대화 흐름

2026-10-01 / Asia/Seoul

## 구현 범위

이 문서는 첫 일반 대화 흐름 구현 시점의 기록이다. 웹·API·MySQL의 Docker Compose 실행, 메시지 저장·조회, OpenAI Responses API 연결, 단일 대화 화면, 실패 재시도와 요청 ID 기반 중복 방지를 구현했다. 이후 추가한 전날 복습은 `docs/REVIEW.md`를 참고한다.

Spring Initializr에서 Java 21 / Kotlin / WebFlux / R2DBC 공식 골격을 생성했다. Spring Boot가 관리하는 MySQL R2DBC 드라이버를 사용한다. 런타임 DB와 HTTP 요청은 Reactor와 코루틴 await를 사용하며 이벤트 루프에서 blocking 호출을 하지 않는다.

## 검증 진행 상태

- Docker 엔진 29.8.0 및 Compose v5.5.1 실행 확인
- 웹 TypeScript 검사와 프로덕션 빌드 통과
- 백엔드 OpenAI HTTP 연동 테스트 4개 통과 (테스트 서버 사용)
- MySQL 실제 연결, API health `UP`, 프록시를 통한 설정 조회 확인
- 실제 키의 모델 조회 HTTP 200 확인
- 실제 OpenAI 응답 생성 성공 (`gpt-4.1-mini`), 사용자 메시지 ID 1 / AI 응답 ID 2
- 실패한 질문이 `FAILED`로 저장되고, 같은 요청 ID로 재시도한 뒤 `COMPLETE`로 복구됨을 확인
- 같은 요청 재전송 및 완료된 질문 재시도에서 같은 메시지 ID가 반환됨을 확인 (추가 AI 호출과 중복 저장 없음)
- 같은 요청 ID에 다른 내용은 409, 공백 질문과 6,001자 질문은 400으로 거부됨을 확인
- API 재시작 후 AI 응답 ID 2가 그대로 조회됨을 확인
- 현재 2개 메시지로 커서 이전 조회·가장 오래된 페이지·잘못된 커서 400 확인. 20개를 넘는 기록의 추가 로딩은 아직 실행 검증하지 않음
- 다른 Origin의 웹 POST가 403으로 거부되고 대화 페이지 HTTP 200을 확인
- 공개 대상 소스·문서·예제 설정에서 API 키 패턴이 검출되지 않음을 확인

실제 OpenAI 응답의 추가 필드는 DTO에서 무시하며 텍스트만 추출한다. 완료되지 않은 응답이나 빈 응답은 성공으로 처리하지 않는다. 인증·모델·사용 한도 오류는 원문 오류 본문을 노출하지 않고 구분해서 표시한다.

첫 일반 대화 구현 당시 날짜 경계와 복습 흐름은 미구현이었고 Windows 브라우저 도구는 샌드박스 초기화 오류로 실행하지 못했다. 이후 복습 작업에서는 Docker의 Playwright로 화면을 검증했다. 구체적 범위와 결과는 `docs/REVIEW.md`에 기록한다.

## 참고한 공식 문서

- [OpenAI 텍스트 생성](https://developers.openai.com/api/docs/guides/text)
- [GPT-4.1 mini](https://developers.openai.com/api/docs/models/gpt-4.1-mini)
- [Spring Boot 관리 의존성](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)
- [MySQL R2DBC 드라이버](https://github.com/asyncer-io/r2dbc-mysql)
- [Next.js 설치](https://nextjs.org/docs/app/getting-started/installation)
