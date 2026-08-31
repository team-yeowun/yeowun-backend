package modi.backend.ingestionv2.collect.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.ingestionv2.common.IngestionClock;

/**
 * 수집 애그리거트 루트.
 *
 * <ul>
 *   <li>행 1개 = 전시 1건이 이 회차의 처리 대상으로 확정된 사실</li>
 *   <li>status = COLLECTED 한 값 (네 격벽이 공유하는 "루트가 자기 상태를 소유한다" 모양)</li>
 *   <li>vendor_key UNIQUE = 회차를 다시 돌려도 같은 전시를 두 번 태우지 않는 물리 근거</li>
 *   <li>클래스명과 테이블명이 다름 (Collection 은 java.util 과 단순 이름 충돌)</li>
 * </ul>
 */
@Entity
@Table(
		name = "ingestion_collection",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_ingestion_collection_vendor_key",
				columnNames = "vendor_key"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectedExhibition {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 문화포털 원천 식별자. 네 격벽을 잇는 상관 키. */
	@Column(name = "vendor_key", nullable = false, length = 100)
	private String vendorKey;

	/** 이 전시를 확정한 회차. */
	@Column(name = "batch_date", nullable = false)
	private LocalDate batchDate;

	/** 수집이 소유하는 상태. 현재 값은 COLLECTED 하나이며 전이가 없다. */
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private CollectionStatus status;

	@Column(name = "collected_at", nullable = false, updatable = false)
	private LocalDateTime collectedAt;

	private CollectedExhibition(String vendorKey, LocalDate batchDate, LocalDateTime collectedAt) {
		this.vendorKey = vendorKey;
		this.batchDate = batchDate;
		this.status = CollectionStatus.COLLECTED;
		this.collectedAt = collectedAt;
	}

	/** 목록에서 발견한 전시를 이 회차의 대상으로 확정한다. */
	public static CollectedExhibition create(CatalogItem item, LocalDate batchDate) {
		return new CollectedExhibition(item.vendorKey(), batchDate, IngestionClock.now());
	}
}
