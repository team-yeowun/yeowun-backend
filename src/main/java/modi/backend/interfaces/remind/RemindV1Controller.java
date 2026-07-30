package modi.backend.interfaces.remind;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import modi.backend.application.remind.RemindCriteria;
import modi.backend.application.remind.RemindFacade;
import modi.backend.application.remind.RemindResult;
import modi.backend.interfaces.auth.Authentication;
import modi.backend.interfaces.auth.LoginUser;
import modi.backend.interfaces.record.dto.PageResponse;
import modi.backend.interfaces.remind.dto.RemindDto;
import modi.backend.support.response.ApiResponse;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reminds")
public class RemindV1Controller implements RemindV1ApiSpec {

	private final RemindFacade remindFacade;

	@Override
	@GetMapping("/candidate")
	public ApiResponse<RemindDto.CandidateResponse> candidate(
			@Parameter(hidden = true) @Authentication LoginUser loginUser) {
		RemindResult.Candidate candidate = remindFacade.candidate(loginUser.userId());
		return ApiResponse.success(candidate == null ? null : RemindDto.CandidateResponse.from(candidate));
	}

	@Override
	@PostMapping
	public ApiResponse<RemindDto.SummaryResponse> save(
			@Parameter(hidden = true) @Authentication LoginUser loginUser,
			@Valid @RequestBody RemindDto.SaveRequest request) {
		RemindResult.Summary summary = remindFacade.save(new RemindCriteria.Save(
				loginUser.userId(), request.recordId(), request.emotionCodes(), request.reflection()));
		return ApiResponse.success(RemindDto.SummaryResponse.from(summary));
	}

	@Override
	@GetMapping
	public ApiResponse<PageResponse<RemindDto.ListItemResponse>> list(
			@Parameter(hidden = true) @Authentication LoginUser loginUser,
			@ParameterObject @PageableDefault(size = 20) Pageable pageable) {
		return ApiResponse.success(PageResponse.from(
				remindFacade.list(loginUser.userId(), pageable).map(RemindDto.ListItemResponse::from)));
	}

	@Override
	@GetMapping("/{remindId}")
	public ApiResponse<RemindDto.SummaryResponse> get(
			@Parameter(hidden = true) @Authentication LoginUser loginUser,
			@PathVariable Long remindId) {
		return ApiResponse.success(RemindDto.SummaryResponse.from(remindFacade.get(loginUser.userId(), remindId)));
	}

	@Override
	@DeleteMapping("/{remindId}")
	public ApiResponse<Object> delete(
			@Parameter(hidden = true) @Authentication LoginUser loginUser,
			@PathVariable Long remindId) {
		remindFacade.delete(loginUser.userId(), remindId);
		return ApiResponse.success();
	}
}
