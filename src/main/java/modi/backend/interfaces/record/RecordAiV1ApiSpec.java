package modi.backend.interfaces.record;

import java.util.concurrent.CompletableFuture;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import modi.backend.interfaces.auth.LoginUser;
import modi.backend.interfaces.record.dto.RecordAiDto;
import modi.backend.support.response.ApiResponse;

/**
 * AI 감상문 API Swagger 스펙. (MVC 어노테이션은 {@link RecordAiV1Controller})
 * 로그인 전용. api-key 미설정 환경에서는 503 AI_DISABLED. AI 호출은 전용 스레드풀에서 비동기 처리.
 */
@Tag(name = "Record AI", description = "질문으로 작성 — 전시 맥락 질문 생성 · Q&A 감상문 다듬기")
@SecurityRequirement(name = "bearerAuth")
public interface RecordAiV1ApiSpec {

	@Operation(summary = "감상 질문 생성", description = """
			전시 맥락(제목·장소·설명 등)을 반영해 답하기 쉬운 질문 3개를 생성한다(구조화 출력으로 개수 강제).
			- '다른 질문 보기'는 이 API를 다시 호출하면 된다.
			- 로그인 전용. AI 미설정 시 503 AI_DISABLED. 연속 호출은 429 AI_RATE_LIMITED.""")
	CompletableFuture<ApiResponse<RecordAiDto.QuestionsResponse>> questions(
			@Parameter(hidden = true) LoginUser loginUser,
			RecordAiDto.QuestionsRequest request);

	@Operation(summary = "감상문 다듬기", description = """
			Q&A 답변을 바탕으로 감상문 본문(≤300자)을 생성한다(동기 응답).
			- '다시 다듬기'는 이 API를 다시 호출하면 된다.
			- 반환된 본문을 사용자가 수정·확정한 뒤 기록 생성 API(POST /api/v1/records, writeMode=AI)로 저장한다.
			- 로그인 전용. AI 미설정 시 503 AI_DISABLED. 연속 호출은 429 AI_RATE_LIMITED.""")
	CompletableFuture<ApiResponse<RecordAiDto.ComposeResponse>> compose(
			@Parameter(hidden = true) LoginUser loginUser,
			RecordAiDto.ComposeRequest request);

	@Operation(summary = "감상문 다듬기(스트리밍)", description = """
			감상문 다듬기와 동일하되, 본문을 토큰 단위로 스트리밍한다(text/event-stream). 체감 지연 감소용.
			- 이벤트: `delta`(생성 조각) 여러 번 → `done`(완료) 또는 `error`(사용자 메시지). 클라이언트는 delta를 이어 붙인다.
			- 완료 시 전체 본문을 draft로 저장하므로 재진입/새로고침 복원에 그대로 쓰인다.
			- 로그인 전용. 스트림은 이미 200으로 시작되므로 rate-limit/AI 실패는 `error` 이벤트로 전달된다(HTTP 상태 아님).""")
	SseEmitter composeStream(
			@Parameter(hidden = true) LoginUser loginUser,
			RecordAiDto.ComposeRequest request);

	@Operation(summary = "임시저장(draft) 저장", description = """
			'질문으로 작성' 진행 상태(질문+답변+초안)를 임시저장한다. 뒤로가기 전 자동저장용 — 재진입 시 GET으로 복원한다.
			- 로그인 전용. AI 호출 아님(캐시 저장)이라 rate-limit 없음. 저장소 장애 시에도 실패로 처리하지 않는다(부가 기능).""")
	ApiResponse<RecordAiDto.DraftResponse> saveDraft(
			@Parameter(hidden = true) LoginUser loginUser,
			RecordAiDto.DraftSaveRequest request);

	@Operation(summary = "임시저장(draft) 복원", description = """
			전시에 대한 진행 중 draft(질문+답변+초안)를 복원한다. 뒤로가기 후 재진입 시 호출해 상태를 채운다.
			- 없거나 만료면 exists=false. 로그인 전용(본인 draft만).""")
	ApiResponse<RecordAiDto.DraftResponse> getDraft(
			@Parameter(hidden = true) LoginUser loginUser,
			@Parameter(description = "대상 전시 ID") Long exhibitionId);

	@Operation(summary = "임시저장(draft) 삭제", description = """
			진행 중 draft를 삭제한다(감상문 저장 완료·작성 포기 시). 없어도 성공하며, TTL로도 자동 정리된다.""")
	ApiResponse<Object> deleteDraft(
			@Parameter(hidden = true) LoginUser loginUser,
			@Parameter(description = "대상 전시 ID") Long exhibitionId);
}
