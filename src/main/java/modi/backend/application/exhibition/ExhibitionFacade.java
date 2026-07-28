package modi.backend.application.exhibition;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 전시 사용자 유스케이스의 <b>단일 진입점</b>(03_전시.md). interfaces는 이 파사드만 호출하고, 실제 조율은 책임별
 * 서비스가 맡는다 — 목록/탐색({@link ExhibitionListService}) · 배너({@link ExhibitionBannerService}) · 상세({@link ExhibitionDetailService}) ·
 * 개인 전시 등록·삭제({@link ExhibitionCustomService}).
 *
 * <p><b>왜 갈랐나</b>: 한 클래스가 리포지토리 8개를 들고 있었고, 그중 작가·전시관·장르 분류기(AI)는 <b>등록에서만</b>
 * 쓰였다. 목록을 그리는 경로가 Gemini 클라이언트를 함께 들고 다닐 이유가 없다. 협력자가 갈리는 선을 그대로 잘랐다.
 *
 * <p>수집·보강 파이프라인은 ingestion 슬라이스({@code ExhibitionIngestionOrchestrator})가 따로 담당한다.
 * 장소는 N:1, 상세는 1:1, 작가는 N:M 조인이라 응답 조립 시 애그리거트 루트 포트에서 읽어 모은다(API 계약 불변).
 */
@Service
@RequiredArgsConstructor
public class ExhibitionFacade {

	private final ExhibitionListService exhibitionListService;
	private final ExhibitionBannerService exhibitionBannerService;
	private final ExhibitionDetailService exhibitionDetailService;
	private final ExhibitionCustomService exhibitionCustomService;

	/** 지역 필터 그룹 목록(디자인 병합 칩). */
	public List<ExhibitionResult.RegionGroup> getRegionGroups() {
		return exhibitionListService.getRegionGroups();
	}

	/** 목록/탐색(5.2). 필터 미지정 시 오늘 진행 중인 전시를 기본 노출한다. */
	public ExhibitionResult.ListPage search(ExhibitionCriteria.Search criteria) {
		return exhibitionListService.search(criteria);
	}

	/** 홈 배너(E-10). 오늘 진행 중인 전시 중 조회수 상위 최대 3개. */
	public List<ExhibitionResult.Banner> banners() {
		return exhibitionBannerService.banners();
	}

	/** 상세(5.3). 없으면 404, 타인의 CUSTOM이면 403. 조회수를 올린다. */
	public ExhibitionResult.Detail getDetail(ExhibitionCriteria.Detail criteria) {
		return exhibitionDetailService.getDetail(criteria);
	}

	/** 스냅샷/조회용 — 조회수 증가·개인화 없이 DB에서만 읽어 반환한다(기록 생성 등 내부 사용). */
	public ExhibitionResult.Detail getForSnapshot(Long exhibitionId, Long requesterId) {
		return exhibitionDetailService.getForSnapshot(exhibitionId, requesterId);
	}

	/** 개인 전시 등록(5.4). */
	public ExhibitionResult.Created registerCustom(ExhibitionCriteria.CustomCreate criteria) {
		return exhibitionCustomService.registerCustom(criteria);
	}

	/** 개인 전시(CUSTOM) 동반 삭제 — 본인이 등록한 CUSTOM만 soft-delete. 멱등. */
	public void deleteCustomOwnedBy(Long exhibitionId, Long ownerId) {
		exhibitionCustomService.deleteCustomOwnedBy(exhibitionId, ownerId);
	}
}
