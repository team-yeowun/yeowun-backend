package modi.backend.ingestion.application.progress;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.ExhibitionRegistration;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.snapshot.CultureDetailSnapshot;
import modi.backend.ingestion.domain.snapshot.CultureListSnapshot;
import modi.backend.ingestion.domain.snapshot.GenreSnapshot;
import modi.backend.ingestion.infra.culture.CultureFieldCodec;
import modi.backend.ingestion.infra.snapshot.CultureDetailSnapshotJpaRepository;
import modi.backend.ingestion.infra.snapshot.CultureListSnapshotJpaRepository;
import modi.backend.ingestion.infra.snapshot.GenreSnapshotJpaRepository;

/**
 * 승격 <b>어셈블 컴포넌트</b>(설계 §6-1) — 원장 3종(list·detail·genre 스냅샷)을 모아 코어 등록 입력
 * ({@link ExhibitionRegistration})으로 조립하는 읽기 전용 면이다. {@link SnapshotLedger}(쓰기면)와 대칭.
 *
 * <p>스냅샷은 verbatim 문자열이라 <b>타입 복원(날짜·좌표·enum)과 정제(이스케이프 원복·평문 추출)를 여기서</b>
 * 한다 — 목록분은 {@link CultureListSnapshot#toCatalogData()}(도메인), 상세분은 {@link CultureFieldCodec}
 * (목록/상세 수집과 같은 규칙)로. 진행 상태가 슬림해지며(설계 §5-2) 타이핑 부담이 어셈블 지점으로 온 것이다.
 *
 * <p>원장 결손은 프로그래밍 오류다(마커⇒원장 불변식 위반) — 조용히 null 조립하지 않고 예외를 던져
 * DRAFT_READY가 RETRYABLE로 남게 한다(가시화). 예외: 상세 원장은 <b>무상세 확인</b>(markDetailAbsent)이면
 * 정상적으로 없다 — 상세분 null 조립이 맞다.
 */
@Component
@RequiredArgsConstructor
public class ExhibitionAssembler {

	private final CultureListSnapshotJpaRepository listSnapshotRepository;
	private final CultureDetailSnapshotJpaRepository detailSnapshotRepository;
	private final GenreSnapshotJpaRepository genreSnapshotRepository;

	/** 원장 3종 → 등록 입력. 게이트 충족 상태에서만 불린다(진행 상태 서비스의 승격 tx). */
	@Transactional(readOnly = true)
	public ExhibitionRegistration assemble(String externalId) {
		CultureListSnapshot list = listSnapshotRepository.findByExternalId(externalId)
				.orElseThrow(() -> new IllegalStateException("목록 원장 결손(마커⇒원장 불변식 위반): " + externalId));
		GenreSnapshot genre = genreSnapshotRepository.findByExternalId(externalId)
				.orElseThrow(() -> new IllegalStateException("장르 원장 결손(마커⇒원장 불변식 위반): " + externalId));
		Optional<CultureDetailSnapshot> detail = detailSnapshotRepository.findByExternalId(externalId);

		CatalogExhibitionData listData = list.toCatalogData();
		return new ExhibitionRegistration(
				externalId,
				listData.title(),
				listData.place(),
				listData.region(),
				listData.sigungu(),
				listData.gpsX(),
				listData.gpsY(),
				listData.startDate(),
				listData.endDate(),
				listData.category(),
				listData.posterUrl(),
				detailUrlOf(listData, detail),
				listData.serviceName(),
				detail.map(d -> CultureFieldCodec.decode(CultureFieldCodec.blankToNull(d.getPrice()))).orElse(null),
				detail.map(d -> CultureFieldCodec.decodeDescription(CultureFieldCodec.blankToNull(d.getContents())))
						.orElse(null),
				detail.map(d -> CultureFieldCodec.blankToNull(d.getImgUrl())).orElse(null),
				detail.map(d -> CultureFieldCodec.decode(CultureFieldCodec.blankToNull(d.getPlaceAddr()))).orElse(null),
				detail.map(d -> CultureFieldCodec.decode(CultureFieldCodec.blankToNull(d.getPhone()))).orElse(null),
				detail.map(d -> CultureFieldCodec.blankToNull(d.getPlaceUrl())).orElse(null),
				genre.getGenreKeyword(),
				genre.getGenreProvider(),
				genre.getGenreModel());
	}

	/** 상세 링크 — 목록분 우선(원천 목록의 url), 결측이면 상세 응답의 url로 보완. */
	private static String detailUrlOf(CatalogExhibitionData listData, Optional<CultureDetailSnapshot> detail) {
		if (listData.detailUrl() != null) {
			return listData.detailUrl();
		}
		return detail.map(d -> CultureFieldCodec.blankToNull(d.getUrl())).orElse(null);
	}
}
