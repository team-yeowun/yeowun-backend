package modi.backend.interfaces.remind;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import modi.backend.interfaces.auth.LoginUser;
import modi.backend.interfaces.record.dto.PageResponse;
import modi.backend.interfaces.remind.dto.RemindDto;
import modi.backend.support.response.ApiResponse;

/**
 * 리마인드(회고) API Swagger 스펙. (MVC 어노테이션은 {@link RemindV1Controller})
 * 로그인 전용. 저장 시 감정 변화 AI 요약은 best-effort로 함께 생성한다(AI 미설정/오류여도 저장은 성공).
 */
@Tag(name = "Remind", description = "리마인드(회고) — 과거 기록 소환 · 감정 변화 저장/조회")
@SecurityRequirement(name = "bearerAuth")
public interface RemindV1ApiSpec {

	@Operation(summary = "오늘의 소환 대상 조회", description = """
			작성 후 7일 이상 지났고 아직 회고하지 않은 내 기록 1건(최신순)을 반환한다.
			화면 1~3 렌더용(당시 기록 카드 + 그날의 감상 + 당시 감정). 대상이 없으면 data=null.""")
	ApiResponse<RemindDto.CandidateResponse> candidate(@Parameter(hidden = true) LoginUser loginUser);

	@Operation(summary = "리마인드 저장", description = """
			과거 기록에 대한 '지금 다시 보니'의 새 감정(선택)·소감(필수)을 저장한다.
			감정 변화 AI 서술 요약은 best-effort로 함께 생성 — AI 미설정/rate-limit(SKIPPED)·오류(FAILED)여도 저장은 성공한다.
			응답은 원본(그때) vs 회고(지금) + AI 요약(감정 변화 요약).""")
	ApiResponse<RemindDto.SummaryResponse> save(
			@Parameter(hidden = true) LoginUser loginUser,
			RemindDto.SaveRequest request);

	@Operation(summary = "리마인드 목록", description = "아카이브 '리마인드' — 내 회고를 최신순으로 조회한다.")
	ApiResponse<PageResponse<RemindDto.ListItemResponse>> list(
			@Parameter(hidden = true) LoginUser loginUser,
			@ParameterObject Pageable pageable);

	@Operation(summary = "리마인드 상세(감정 변화 요약)", description = """
			원본(그때)과 회고(지금)를 나란히, AI 서술 요약과 함께 반환한다.
			원본 기록이 삭제됐다면 before는 null.""")
	ApiResponse<RemindDto.SummaryResponse> get(
			@Parameter(hidden = true) LoginUser loginUser,
			@Parameter(description = "리마인드 ID") Long remindId);

	@Operation(summary = "리마인드(여운) 삭제", description = "내 여운 1건을 삭제한다(soft-delete). 원본 기록은 유지된다.")
	ApiResponse<Object> delete(
			@Parameter(hidden = true) LoginUser loginUser,
			@Parameter(description = "리마인드 ID") Long remindId);
}
