package modi.backend.interfaces.record;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import modi.backend.application.record.RecordAiCriteria;
import modi.backend.application.record.RecordAiFacade;
import modi.backend.application.record.RecordAiResult;
import modi.backend.interfaces.auth.Authentication;
import modi.backend.interfaces.auth.LoginUser;
import modi.backend.interfaces.record.dto.RecordAiDto;
import modi.backend.support.response.ApiResponse;

/**
 * AI 감상문 API('질문으로 작성' 플로우). 로그인 전용 — access 토큰의 사용자로 동작한다.
 * AI 호출은 전용 스레드풀({@code aiExecutor})에서 비동기 실행해 서블릿 워커 스레드를 빨리 반환한다.
 * (@Authentication은 서블릿 스레드에서 먼저 해석됨.) 저장은 이 컨트롤러가 하지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/records/ai")
public class RecordAiV1Controller implements RecordAiV1ApiSpec {

	private final RecordAiFacade recordAiFacade;
	private final Executor aiExecutor;

	@Override
	@PostMapping("/questions")
	public CompletableFuture<ApiResponse<RecordAiDto.QuestionsResponse>> questions(
			@Parameter(hidden = true) @Authentication LoginUser loginUser,
			@Valid @RequestBody RecordAiDto.QuestionsRequest request) {
		return CompletableFuture.supplyAsync(() -> {
			RecordAiResult.Questions result = recordAiFacade.questions(
					new RecordAiCriteria.Questions(loginUser.userId(), request.exhibitionId()));
			return ApiResponse.success(new RecordAiDto.QuestionsResponse(result.questions()));
		}, aiExecutor);
	}

	@Override
	@PostMapping("/compose")
	public CompletableFuture<ApiResponse<RecordAiDto.ComposeResponse>> compose(
			@Parameter(hidden = true) @Authentication LoginUser loginUser,
			@Valid @RequestBody RecordAiDto.ComposeRequest request) {
		return CompletableFuture.supplyAsync(() -> {
			RecordAiResult.Compose result = recordAiFacade.compose(new RecordAiCriteria.Compose(
					loginUser.userId(), request.exhibitionId(),
					request.answers().stream()
							.map(a -> new RecordAiCriteria.QnaPair(a.question(), a.answer()))
							.toList()));
			return ApiResponse.success(new RecordAiDto.ComposeResponse(result.content()));
		}, aiExecutor);
	}

	@Override
	@PutMapping("/draft")
	public ApiResponse<RecordAiDto.DraftResponse> saveDraft(
			@Parameter(hidden = true) @Authentication LoginUser loginUser,
			@Valid @RequestBody RecordAiDto.DraftSaveRequest request) {
		recordAiFacade.saveDraft(new RecordAiCriteria.DraftSave(
				loginUser.userId(), request.exhibitionId(), request.questions(),
				request.answers() == null ? List.of()
						: request.answers().stream()
								.map(a -> new RecordAiCriteria.QnaPair(a.question(), a.answer())).toList(),
				request.content()));
		return ApiResponse.success(toDraftResponse(recordAiFacade.getDraft(loginUser.userId(), request.exhibitionId())));
	}

	@Override
	@GetMapping("/draft")
	public ApiResponse<RecordAiDto.DraftResponse> getDraft(
			@Parameter(hidden = true) @Authentication LoginUser loginUser,
			@RequestParam Long exhibitionId) {
		return ApiResponse.success(toDraftResponse(recordAiFacade.getDraft(loginUser.userId(), exhibitionId)));
	}

	@Override
	@DeleteMapping("/draft")
	public ApiResponse<Object> deleteDraft(
			@Parameter(hidden = true) @Authentication LoginUser loginUser,
			@RequestParam Long exhibitionId) {
		recordAiFacade.deleteDraft(loginUser.userId(), exhibitionId);
		return ApiResponse.success();
	}

	private RecordAiDto.DraftResponse toDraftResponse(RecordAiResult.Draft result) {
		return new RecordAiDto.DraftResponse(result.exists(), result.questions(),
				result.answers().stream()
						.map(qna -> new RecordAiDto.DraftQna(qna.question(), qna.answer())).toList(),
				result.content());
	}
}
