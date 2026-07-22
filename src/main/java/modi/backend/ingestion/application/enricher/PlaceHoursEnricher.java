package modi.backend.ingestion.application.enricher;

import modi.backend.application.exhibition.contract.PlaceHoursBackfill;
import modi.backend.application.exhibition.contract.PlaceHoursTarget;
import modi.backend.ingestion.application.ExhibitionSyncFacade;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.properties.GooglePlaceProperties;
import modi.backend.domain.exhibition.hours.OpeningHoursFormatter;
import modi.backend.ingestion.domain.data.PlaceHoursResult;

/**
 * 전시 영업시간(운영시간) 보강 오케스트레이션 — 장르 보강({@link GenreEnricher})과 동형.
 * <p>
 * 흐름: 조회 대상(주소 있고 미조회·만료)을 장소 단위로 묶어 조회({@link PlaceHoursReader}, 장소당 1콜) →
 * 벤더 원본 적재 + 우리 표시 규칙({@link OpeningHoursFormatter})으로 정준층 파생 저장. 루프는 트랜잭션 밖에서 돌고, 장소 단위 저장만
 * {@link ExhibitionFacade}의 트랜잭션 메서드(프록시 경유)로 위임해 외부 호출을 한 트랜잭션에 오래 물지 않는다(커넥션 장기 점유 방지).
 * <p>
 * <b>"스테이징 초기화" 단계가 사라졌다</b>(이관 4단계). 그건 V19의 {@code google_place_hours}에 UK가 없어 재수집이
 * 행을 누적시키기 때문에 필요했던 대응인데, 벤더층 {@code google_place_response}는 UK({@code place_key})라 upsert가
 * 멱등이다. 덕분에 <b>초기화가 조회·조기 종료보다 먼저 돌아 "할 일 0건인 날에도 원본이 매일 전멸하던" 버그가
 * 원인부터 없어졌다</b> — 고친 게 아니라 그 개념이 필요 없어진 것이다.
 * <p>
 * 영업시간은 <b>부가 기능</b>이라 어떤 실패(조회 전송오류 등)도 동기화·등록 흐름을 깨지 않는다 — 해당 장소만 스킵하고 다음 주기에 재시도한다.
 * mock provider가 기본이라 로컬·CI·develop에서는 유료 호출 없이 동일 경로가 돈다.
 */
@Component
@RequiredArgsConstructor
public class PlaceHoursEnricher {

	private static final Logger log = LoggerFactory.getLogger(PlaceHoursEnricher.class);

	private final ExhibitionSyncFacade exhibitionSyncFacade;
	/** 영업시간 정준층 계약(코어 소유) — 대상 선별·실패 기록. */
	private final PlaceHoursBackfill placeHoursBackfill;
	/** 조회 1건 + 그 호출의 감사. */
	private final PlaceHoursReader placeHoursReader;
	private final OpeningHoursFormatter openingHoursFormatter;
	private final GooglePlaceProperties properties;

	/**
	 * 조회 대상 장소들의 영업시간을 채운다(장소당 1콜). 스테디 상태(전부 최신)에선 대상이 비어 외부 호출 없이 끝난다.
	 *
	 * @return 이번 실행으로 영업시간을 반영한(또는 조회 시도한) 전시 수
	 */
	public int enrichPlaceHours() {
		LocalDateTime staleBefore = LocalDateTime.now().minusDays(properties.refreshAfterDays());
		List<PlaceHoursTarget> targets = placeHoursBackfill.findPlacesNeedingHours(staleBefore, properties.maxVenuesPerRun());
		if (targets.isEmpty()) {
			return 0;
		}
		int touched = 0;
		for (PlaceHoursTarget target : targets) {
			try {
				Optional<PlaceHoursResult> fetched = placeHoursReader.read(target);
				String formatted = fetched.map(f -> openingHoursFormatter.format(f.data().weeklyHours())).orElse(null);
				exhibitionSyncFacade.applyVenueHours(target, fetched.orElse(null), formatted,
						placeHoursReader.vendor(), LocalDateTime.now());
				touched += 1;
			} catch (RuntimeException e) {
				// 전송 실패 등 — 이 장소만 건너뛰고 synced_at을 남기지 않아 다음 주기에 재시도한다(기존 동작 불변).
				// 정준층엔 "시도했고 실패했다"를 남긴다 — 현행 스키마가 표현하지 못하던 사실이고, 표시값은 지우지 않는다.
				placeHoursBackfill.markHoursFailure(target.exhibitionPlaceId(), placeHoursReader.vendor());
				log.warn("전시 영업시간 조회 실패(장소={}, 다음 주기 재시도): {}", target.placeAddr(), e.getMessage());
			}
		}
		if (touched > 0) {
			log.info("전시 영업시간 보강 {}건({} 장소)", touched, targets.size());
		}
		return touched;
	}
}
