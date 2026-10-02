# 백엔드 헥사고날 구조

단일 Gradle 프로젝트 안에서 도메인·애플리케이션·포트·어댑터를 나눈다. API URL, JSON 응답 형식, MySQL 테이블과 저장된 원문, OpenAI 설정은 유지한다. 멀티모듈이나 별도 서비스를 도입하지 않는다.

## 코드 위치와 책임

기준 경로는 `apps/api/src/main/kotlin/com/saegim`이다.

| 위치 | 책임 |
| --- | --- |
| `domain/` | 순수 Kotlin 메시지·복습 모델, 한국 날짜와 3일 탐색 규칙, 입력 한도·출제 근거 검증 |
| `application/` | `ChatService`, `ReviewService`의 대화·복습 유스케이스와 입출력 모델 |
| `application/port/UseCases.kt` | HTTP 어댑터가 호출하는 `ChatUseCase`, `ReviewUseCase` |
| `application/port/Stores.kt` | 저장소 포트와 `TransactionBoundary` |
| `application/port/Ai.kt` | 일반 대화 `ChatAi`, 복습 `ReviewAi` 포트 |
| `adapter/in/web/` | HTTP 컨트롤러, 요청 검증, 애플리케이션 오류의 HTTP 변환 |
| `adapter/out/persistence/` | R2DBC 엔티티·리포지토리, 도메인 매핑, 근거 ID JSON 변환, 비동기 트랜잭션 |
| `adapter/out/ai/` | OpenAI Responses HTTP 호출과 프롬프트·Structured Outputs 변환 |
| `configuration/` | Spring Bean 조립, 시계, 기존 스키마 보완 |

컨트롤러는 유스케이스 포트만 참조한다. 유스케이스는 도메인과 저장·AI·트랜잭션 포트만 참조한다. 어댑터가 포트를 구현하고 `UseCaseConfig`가 연결한다. 도메인은 애플리케이션도 참조하지 않으며, 도메인과 애플리케이션 모두 Spring·Reactor·Jackson·Jakarta 의존성을 갖지 않는다.

## 저장과 비동기 처리

`Message`, `ReviewDay`, `ReviewQuestion`은 DB 어노테이션 없는 도메인 모델이다. `MessageEntity`, `ReviewDayEntity`, `ReviewQuestionEntity`는 기존 테이블에 대응하는 R2DBC 레코드다. `MessageRepository`, `ReviewDayRepository`, `ReviewQuestionRepository`는 Spring Data 리포지토리이며 유스케이스가 직접 사용하지 않는다. `R2dbcMessageStore`, `R2dbcReviewDayStore`, `R2dbcReviewQuestionStore`가 저장소 포트를 구현한다.

도메인의 `sourceMessageIds`는 `List<Long>`이다. DB의 기존 JSON 문자열은 저장 어댑터에서만 변환한다. 테이블 변경이나 데이터 재작성은 필요하지 않다.

포트는 `suspend` 함수와 일반 Kotlin 값으로 통신한다. R2DBC 어댑터의 `awaitSingle`·`awaitSingleOrNull`과 OpenAI 어댑터의 WebClient가 비동기 호출을 유지한다. `R2dbcTransactionBoundary`는 `TransactionalOperator.executeAndAwait`로 기존 트랜잭션 범위와 Reactor 트랜잭션 컨텍스트를 유지한다. AI 네트워크 호출은 트랜잭션 밖에서 진행한다. 스키마 보완의 `runBlocking`은 기존처럼 기동 스레드에서만 실행한다.

## 규칙과 실패 처리

3일 탐색과 한국 날짜 경계는 `ReviewDates`, 입력 한도와 출제 근거 검증은 `ReviewRules`가 담당한다. `ReviewService`는 AI 호출 전에 입력을 검증하고 반환된 질문을 도메인 규칙으로 다시 검증한다. 공급자를 교체하거나 테스트용 포트를 사용해도 잘못된 근거가 저장되지 않는다.

유스케이스의 `ApplicationFailure`는 HTTP 예외를 상속하지 않는다. 웹 어댑터의 `ApiErrors`가 기존 400·404·409·502 상태와 Problem Detail로 변환한다. 대화·복습의 실패 상태 저장, 재시도, 같은 요청 ID 재사용, 한 API 인스턴스의 동시 변경 방지 규칙은 유지한다.

## 확인 방법

`scripts/verify-review.ps1`는 분리된 MySQL 테스트 DB와 테스트 AI 서버로 전체 백엔드 테스트를 실행한다. 실제 OpenAI 호출은 하지 않는다.

- `ReviewTest`: 순수 도메인 날짜·입력 한도·근거 검증.
- `UseCaseTest`: Spring·DB·HTTP 서버 없이 테스트용 포트로 대화 실패·재시도·중복 방지·페이지 조회·복습 누적·잘못된 근거 거부 검증.
- `DependencyRulesTest`: 코어 소스에 외부 프레임워크나 어댑터 의존성이 들어오는 것을 검사한다. 단일 모듈이므로 모듈 컴파일 경계 대신 이 회귀 검사로 경계를 유지한다.
- `OpenAiClientTest`: 테스트 HTTP 서버로 기존 공급자 요청·응답과 실패 계약 검증.
- `ReviewIntegrationTest`: 실제 MySQL 저장 매핑·복습 진행·날짜 탐색·HTTP 대화와 복습 응답·요청 검증·오류 상태·트랜잭션 롤백 검증.

실제 AI 답변 품질이나 학습 효과 검증은 구조 변경의 자동 테스트와 별도로 직접 사용하며 확인한다.

## 리팩토링 검증 결과 (2026-10-02)

- 백엔드 테스트 26개 통과, 실패·건너뛴 테스트 없음: 의존성 경계 1개, OpenAI HTTP 계약 5개, 실제 MySQL·HTTP 통합 9개, 도메인 7개, 외부 연결 없는 유스케이스 4개.
- API 프로덕션 이미지 빌드와 로컬 실행 확인. 일반 DB의 메시지 50개, 복습 묶음 2개, 질문 2개와 오늘 완료한 복습 상태를 유지했다.
- Playwright Chromium에서 기존 웹과의 연동·완료 상태·날짜 표시를 확인했다. 날짜 범위의 준비 화면은 테스트 응답으로 데스크톱·모바일에서 확인했다. 실제 OpenAI 호출은 하지 않았다.
