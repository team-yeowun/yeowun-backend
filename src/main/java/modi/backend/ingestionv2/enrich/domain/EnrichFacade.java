package modi.backend.ingestionv2.enrich.domain;

import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.enrich.domain.detail.DetailData;
import modi.backend.ingestionv2.enrich.domain.detail.DetailService;
import modi.backend.ingestionv2.enrich.domain.genre.GenreClassifyFailedException;
import modi.backend.ingestionv2.enrich.domain.genre.GenreInput;
import modi.backend.ingestionv2.enrich.domain.genre.GenreResult;
import modi.backend.ingestionv2.enrich.domain.genre.GenreService;
import modi.backend.ingestionv2.enrich.domain.hours.HoursService;
import modi.backend.ingestionv2.enrich.domain.hours.PlaceData;
import modi.backend.ingestionv2.enrich.domain.hours.PlaceInput;

/**
 * 보강 격벽의 유일한 진입점.
 *
 * <ul>
 *   <li>세 박자(판정·외부 호출·반영)를 순서대로 호출하는 일만 함</li>
 *   <li>트랜잭션 애너테이션 없음. 외부 호출이 트랜잭션 밖에 있어야 하기 때문</li>
 *   <li>상태 판단 없음. 무엇을 다음에 할지는 전부 루트 엔티티가 정함</li>
 *   <li>장르 소진만 예외로 바꿔 던짐. 결과값으로는 미처리 신호를 배달 계층에 전할 수 없기 때문</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class EnrichFacade {

	private final EnrichmentService enrichmentService;
	private final EnrichQueryService enrichQueryService;
	private final DetailService detailService;
	private final GenreService genreService;
	private final HoursService hoursService;

	/** 수집 완료를 받아 보강을 연다. */
	public void startEnrichment(String vendorKey) {
		enrichmentService.start(vendorKey);
	}

	/** 상세 스텝 1회 실행. */
	public void enrichDetail(String vendorKey) {
		if (detailService.alreadyFetched(vendorKey)) {
			detailService.applyAlreadyFetched(vendorKey);
			return;
		}
		DetailData data = detailService.fetch(vendorKey);
		detailService.apply(vendorKey, data);
	}

	/** 장르 스텝 1회 실행. 전 공급자가 소진되면 결과값을 예외로 바꿔 던진다. */
	public void enrichGenre(String vendorKey) {
		if (genreService.alreadyClassified(vendorKey)) {
			genreService.applyAlreadyClassified(vendorKey);
			return;
		}
		Optional<GenreInput> input = genreService.readInput(vendorKey);
		if (input.isEmpty()) {
			genreService.failWithoutInput(vendorKey);
			return;
		}
		GenreResult result = genreService.classify(input.get());
		if (!result.isClassified()) {
			// 포트는 실패한 시도 목록을 도메인에 전해야 하고, 핸들러는 배달 계층에 미처리를 알려야 한다.
			// 값으로 받아 예외로 바꾸는 이 한 줄이 둘을 모두 지킨다.
			throw new GenreClassifyFailedException(result);
		}
		genreService.apply(vendorKey, result);
	}

	/** 개장 시간 스텝 1회 실행. */
	public void enrichHours(String vendorKey) {
		if (hoursService.alreadyFetched(vendorKey)) {
			hoursService.applyAlreadyFetched(vendorKey);
			return;
		}
		Optional<PlaceInput> input = hoursService.readInput(vendorKey);
		if (input.isEmpty()) {
			hoursService.failWithoutInput(vendorKey);
			return;
		}
		PlaceData data = hoursService.fetch(input.get());
		hoursService.apply(vendorKey, data);
	}

	/** 실패 기록. 재시도 대상인지 격리 대상인지를 돌려준다. */
	public FailureOutcome recordFailure(EnrichStep step, String vendorKey, String vendor, String error) {
		return enrichmentService.recordFailure(step, vendorKey, vendor, error);
	}

	/** 장르 실패 기록. 폴백 사실을 함께 남긴다. */
	public FailureOutcome recordGenreFailure(String vendorKey, GenreResult result) {
		return enrichmentService.recordGenreFailure(vendorKey, result.lastVendorName(), result.failureSummary(),
				result.fallbackUsed());
	}

	/** 관리자 실패 목록 조회. 트랜잭션 경계는 조회 서비스가 갖는다. */
	public EnrichResult.FailedPage findFailed(EnrichCriteria.FailedSearch criteria) {
		return enrichQueryService.findFailed(criteria);
	}

	/** 관리자 수동 재시도. 되돌리기와 적재는 루트 서비스의 한 트랜잭션 안에서 일어난다. */
	public EnrichResult.Reopened reopen(String vendorKey) {
		return enrichmentService.reopen(vendorKey);
	}
}
