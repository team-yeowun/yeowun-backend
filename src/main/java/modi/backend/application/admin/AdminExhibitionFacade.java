package modi.backend.application.admin;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import modi.backend.application.exhibition.cache.ExhibitionAdminUpdatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionDetail;
import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;
import modi.backend.domain.exhibition.catalog.ExhibitionHistory;
import modi.backend.infra.exhibition.catalog.ExhibitionHistoryJpaRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.catalog.FieldChange;
import modi.backend.domain.exhibition.hours.PlaceKey;
import modi.backend.support.error.CoreException;
import modi.backend.support.text.HtmlTextExtractor;

/**
 * 관리자 전시 유지보수(내부 운영용). 프론트 비노출 — 수집 파이프라인과 별개로 기존 데이터를 손보는 일회성/운영성 작업을 담는다.
 * 이관 후 수정 대상이 세 엔티티로 나뉜다: title=코어, place=전시장(resolve-or-create 재지정), price·description=상세 satellite.
 */
@Service
@RequiredArgsConstructor
public class AdminExhibitionFacade {

	private static final Logger log = LoggerFactory.getLogger(AdminExhibitionFacade.class);

	private final ExhibitionRepository exhibitionRepository;
	private final ExhibitionPlaceRepository exhibitionPlaceRepository;
	/** 사람 수정 이력(감사) — 실제로 바뀐 필드를 old→new로 남긴다. */
	private final ExhibitionHistoryJpaRepository exhibitionHistoryRepository;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * 저장된 전시 설명을 재파싱해 남아 있는 HTML/워드프레스 마크업을 벗긴다. 설명은 상세 satellite({@code exhibition_detail})로
	 * 이동했으므로 그 행을 대상으로 한다. 외부 재조회 없이 저장값만 정리하며, 실질 변경이 있는 행만 저장한다(멱등).
	 */
	@Transactional
	public AdminExhibitionResult.DescriptionReparse reparseDescriptions() {
		List<ExhibitionDetail> rows = exhibitionRepository.findDetailsWithDescription();
		int updated = 0;
		for (ExhibitionDetail detail : rows) {
			String cleaned = HtmlTextExtractor.toPlainText(detail.getDescription());
			if (!Objects.equals(cleaned, detail.getDescription())) {
				detail.reparseDescription(cleaned);
				exhibitionRepository.saveDetail(detail);
				updated++;
			}
		}
		log.info("전시 설명 재파싱: 검사 {}건 / 갱신 {}건", rows.size(), updated);
		return new AdminExhibitionResult.DescriptionReparse(rows.size(), updated);
	}

	/**
	 * 전시 필드를 수정하고, 실제로 바뀐 필드마다 이력을 남긴다(감사). 각 엔티티가 자기 변경을 판단하고(무엇이 바뀌었나 = 도메인
	 * 규칙), Facade는 세 엔티티에 걸친 변경을 한 {@code editedAt}으로 묶어 이력에 적재하는 조율만 한다.
	 * 값이 그대로면 변경 목록이 비어 이력이 생기지 않는다(멱등).
	 */
	@Transactional
	public AdminExhibitionResult.Edited editExhibition(Long exhibitionId, String title, String place, String price,
			String description) {
		Exhibition exhibition = exhibitionRepository.findById(exhibitionId)
				.orElseThrow(() -> new CoreException(ExhibitionErrorCode.EXHIBITION_NOT_FOUND));
		List<FieldChange> changes = new ArrayList<>();

		exhibition.applyTitleEdit(title).ifPresent(changes::add);
		applyPlaceEdit(exhibition, place).ifPresent(changes::add);
		List<FieldChange> detailChanges = applyDetailEdit(exhibitionId, price, description);
		changes.addAll(detailChanges);
		// 가격이 실제로 바뀌었으면 전시 행의 무료 판정을 다시 굳힌다(V49). 가격이 코어에 닿는 지점은
		// 승격 등록과 여기 둘뿐이라, 이 한 줄이 빠지면 필터(is_free)와 상세에 보이는 가격이 어긋난다.
		if (detailChanges.stream().anyMatch(change -> "price".equals(change.fieldName()))) {
			exhibition.applyPriceJudgement(exhibitionRepository.findDetail(exhibitionId)
					.map(ExhibitionDetail::getPrice).orElse(null));
		}

		if (changes.isEmpty()) {
			return new AdminExhibitionResult.Edited(exhibitionId, 0);
		}
		exhibitionRepository.save(exhibition);
		LocalDateTime editedAt = LocalDateTime.now();
		for (FieldChange change : changes) {
			exhibitionHistoryRepository.save(ExhibitionHistory.of(exhibitionId, change, editedAt));
		}
		log.info("전시 수정: id={} 변경필드 {}개", exhibitionId, changes.size());
		// 커밋이 확정된 뒤에 상세 캐시를 지운다(무효화는 AFTER_COMMIT 리스너가 수행).
		// 조기 반환 뒤라서, 값이 그대로면 이벤트도 나가지 않는다(멱등 유지).
		eventPublisher.publishEvent(new ExhibitionAdminUpdatedEvent(exhibitionId));
		return new AdminExhibitionResult.Edited(exhibitionId, changes.size());
	}

	/** 장소명 수정 — 새 이름으로 전시장을 resolve-or-create하고 재지정한다(자연키가 이름이라 이름 변경 = 다른 전시장). */
	private java.util.Optional<FieldChange> applyPlaceEdit(Exhibition exhibition, String place) {
		if (place == null) {
			return java.util.Optional.empty();
		}
		ExhibitionPlace current = exhibitionPlaceRepository.findById(exhibition.getExhibitionPlaceId()).orElse(null);
		String currentName = current == null ? null : current.getName();
		if (Objects.equals(currentName, PlaceKey.of(place))) {
			return java.util.Optional.empty();
		}
		ExhibitionPlace resolved = exhibitionPlaceRepository.findByPlaceKey(PlaceKey.of(place))
				.orElseGet(() -> exhibitionPlaceRepository.save(
						ExhibitionPlace.createFromList(place, current == null ? null : current.getRegion(),
								current == null ? null : current.getSigungu(),
								current == null ? null : current.getGpsX(),
								current == null ? null : current.getGpsY())));
		// 지역 스냅샷도 새 전시장 것으로 옮긴다(V49) — 전시장이 바뀌었는데 지역만 옛 값이면 필터·표시가 둘 다 틀린다.
		exhibition.reassignPlace(resolved.getId(), resolved.getRegion());
		return java.util.Optional.of(new FieldChange("place", currentName, resolved.getName()));
	}

	/** price·description 수정 — 상세 satellite에 반영. 상세행이 없으면 만든다(수정으로 상세가 생김). */
	private List<FieldChange> applyDetailEdit(Long exhibitionId, String price, String description) {
		if (price == null && description == null) {
			return List.of();
		}
		ExhibitionDetail detail = exhibitionRepository.findDetail(exhibitionId)
				.orElseGet(() -> ExhibitionDetail.markChecked(exhibitionId, LocalDateTime.now()));
		List<FieldChange> changes = detail.applyAdminEdit(price, description);
		if (!changes.isEmpty()) {
			exhibitionRepository.saveDetail(detail);
		}
		return changes;
	}
}
