package modi.backend.application.exhibition;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionQueryRepository;
import modi.backend.support.time.AppTime;

/**
 * 홈 배너(E-10) — 오늘 진행 중인 전시 중 조회수 상위 최대 3개. 진행 중 전시가 없으면 빈 배열.
 *
 * <p>탐색과 갈라 둔 이유는 <b>성질이 다르기 때문</b>이다: 사용자 입력이 없고(필터·정렬·커서 없음), 결과가
 * 요청자와 무관하게 같으며, 전 사용자가 홈에 들어올 때마다 친다. 캐시를 얹는다면 이 경로가 첫 후보다.
 */
@Service
@RequiredArgsConstructor
public class ExhibitionBannerService {

	private static final int BANNER_LIMIT = 3;

	private final ExhibitionQueryRepository exhibitionQueryRepository;
	private final ExhibitionListAssembler listAssembler;

	@Transactional(readOnly = true)
	public List<ExhibitionResult.Banner> banners() {
		List<Exhibition> rows = exhibitionQueryRepository.findOngoingCatalogTopByViews(LocalDate.now(AppTime.KST),
				BANNER_LIMIT);
		Map<Long, ExhibitionPlace> placesById = listAssembler.placesById(rows);
		return rows.stream().map(e -> ExhibitionResult.Banner.from(e, placesById.get(e.getExhibitionPlaceId())))
				.toList();
	}
}
