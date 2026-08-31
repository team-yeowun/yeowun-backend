package modi.backend.ingestionv2.enrich.domain.genre;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.ingestionv2.common.IngestionClock;

/**
 * 장르 분류 원장.
 *
 * <ul>
 *   <li>원장 셋 중 유일하게 벤더 원문이 아니라 정제 결과를 담음(키워드·공급자·모델)</li>
 *   <li>vendor는 성공한 공급자. 1차가 실패하고 2차가 답했으면 2차가 남음</li>
 *   <li>genre_keyword가 NOT NULL - 분류 실패는 행 자체를 만들지 않아 "원장이 있으면 스텝이 끝났다"가 성립</li>
 * </ul>
 */
@Entity(name = "IngestionV2GenreSnapshot")
@Table(name = "ingestion_genre_snapshot",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_ingestion_genre_snapshot_vendor_key",
				columnNames = "vendor_key"),
		indexes = @Index(name = "idx_ingestion_genre_snapshot_vendor_created", columnList = "vendor, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GenreSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "vendor_key", nullable = false, unique = true, length = 100)
	private String vendorKey;

	@Column(name = "genre_keyword", nullable = false, length = 50)
	private String genreKeyword;

	@Enumerated(EnumType.STRING)
	@Column(name = "vendor", nullable = false, length = 20)
	private GenreProvider vendor;

	@Column(name = "model", length = 100)
	private String model;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private GenreSnapshot(String vendorKey, String genreKeyword, GenreProvider vendor, String model) {
		this.vendorKey = vendorKey;
		this.genreKeyword = genreKeyword;
		this.vendor = vendor;
		this.model = model;
		this.createdAt = IngestionClock.now();
	}

	public static GenreSnapshot create(String vendorKey, GenreResult result) {
		return new GenreSnapshot(vendorKey, result.keyword(), result.vendor(), result.model());
	}
}
