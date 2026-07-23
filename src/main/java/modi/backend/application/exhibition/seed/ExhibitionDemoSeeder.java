package modi.backend.application.exhibition.seed;


import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionDetail;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionQuery;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionQueryRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.hours.PlaceHours;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.support.time.AppTime;

/**
 * 로컬 데모 시드(내장 웹페이지 시연용). {@code app.exhibition.demo-seed.enabled=true}일 때만 부팅 시 1회 실행한다.
 * 실제 공공데이터 동기화는 부팅이 아니라 자정 스케줄러({@code ExhibitionSyncScheduler})가 돌린다
 * (초기 적재는 시드 SQL 몫). 이 시더는 카탈로그가 비어 있으면(키 미설정 등) 응답 스키마의
 * <b>모든 필드를 채운</b> 표본 CATALOG + MOCK 전시를 적재한다(포스터·설명·운영시간·관람료·좌표까지
 * — 실제 응답처럼 보이게). 운영/테스트에서는 꺼 둔다(기본 false) — 시드 데이터가 실제 데이터와 섞이지 않게 한다.
 */
@Component
@ConditionalOnProperty(name = "app.exhibition.demo-seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ExhibitionDemoSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionDemoSeeder.class);
	private static final String SERVICE_NAME = "한국문화정보원";
	private static final String REALM_NAME = "전시";

	private final ExhibitionRepository exhibitionRepository;
	private final ExhibitionQueryRepository exhibitionQueryRepository;
	private final ExhibitionPlaceRepository exhibitionPlaceRepository;

	@Override
	public void run(ApplicationArguments args) {
		seedSamplesIfEmpty();
	}

	/** 카탈로그가 비어 있으면(키 미설정 등) 응답 전 필드를 채운 표본 + MOCK을 적재한다. */
	private void seedSamplesIfEmpty() {
		if (exhibitionQueryRepository.count(ExhibitionQuery.unfiltered()) > 0) {
			log.info("데모 시드: 카탈로그가 이미 존재 — 표본 적재 스킵");
			return;
		}
		LocalDate today = LocalDate.now(AppTime.KST);
		// 포스터는 항상 로드되는 표본 이미지(picsum.photos seed)로 둔다 — 실제 인증키 동기화 시엔 원천 thumbnail로 채워진다.
		List<Row> rows = List.of(
				new Row("SEED-CCA-204512", "국립현대미술관 소장품 상설전 ‘빛과 그림자’", "국립현대미술관 서울관",
						today.minusDays(20), today.plusDays(40), ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING,
						"https://picsum.photos/seed/modi-mmca/480/640",
						"한국 근현대 미술 소장품을 빛과 그림자라는 주제로 재구성한 상설 기획전. 회화·드로잉 120여 점을 선보인다.",
						"10:00~18:00 (수·토 21:00까지, 월요일 휴관)", "무료 (특별전 별도)",
						"https://www.mmca.go.kr/", 126.980781, 37.578608, "종로구"),
				new Row("SEED-CCA-204513", "모네에서 세잔까지 — 인상주의 특별전", "예술의전당 한가람미술관",
						today.minusDays(5), today.plusDays(60), ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING,
						"https://picsum.photos/seed/modi-monet/480/640",
						"오르세 미술관 소장 인상주의 대표작을 한자리에 모은 특별전. 모네·르누아르·세잔의 원화를 만난다.",
						"11:00~20:00 (입장 마감 19:00, 연중무휴)", "성인 20,000원 / 청소년 15,000원 / 어린이 12,000원",
						"https://www.sac.or.kr/", 127.011605, 37.479026, "서초구"),
				new Row("SEED-CCA-204514", "서울 도시 사진 아카이브전", "서울시립미술관 서소문본관",
						today.minusDays(10), today.plusDays(20), ExhibitionRegion.SEOUL, ExhibitionCategory.PHOTO,
						"https://picsum.photos/seed/modi-seoulphoto/480/640",
						"1970년대부터 오늘까지 서울의 변화를 담은 다큐멘터리 사진 300여 점을 아카이브 형식으로 전시한다.",
						"10:00~20:00 (평일), 10:00~18:00 (주말, 월요일 휴관)", "무료",
						"https://sema.seoul.go.kr/", 126.973739, 37.564178, "중구"),
				new Row("SEED-CCA-305001", "부산 현대조각 비엔날레", "부산현대미술관",
						today.minusDays(2), today.plusDays(50), ExhibitionRegion.BUSAN, ExhibitionCategory.SCULPTURE,
						"https://picsum.photos/seed/modi-busan/480/640",
						"국내외 조각가 40인의 대형 설치·조각 작품을 실내외 전시장에 걸쳐 소개하는 격년제 비엔날레.",
						"10:00~18:00 (월요일 휴관)", "성인 12,000원 / 청소년 8,000원",
						"https://www.busan.go.kr/moca/", 128.887000, 35.106000, "사하구"),
				new Row("SEED-CCA-305002", "경기 미디어아트 페스타", "백남준아트센터",
						today.plusDays(7), today.plusDays(45), ExhibitionRegion.GYEONGGI, ExhibitionCategory.MEDIA,
						"https://picsum.photos/seed/modi-media/480/640",
						"백남준의 유산을 잇는 미디어아트 특별전. 인터랙티브 영상·사운드 설치 작품을 체험형으로 구성했다.",
						"10:00~18:00 (월요일 휴관)", "성인 9,000원 / 청소년 5,000원",
						"https://njp.ggcf.kr/", 127.108000, 37.256000, "기흥구"),
				new Row("SEED-MOCK-0001", "[MOCK] 골목길 드로잉전", "성수동 언노운 갤러리",
						today.minusDays(3), today.plusDays(12), ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING,
						"https://picsum.photos/seed/modi-alley/480/640",
						"동네 골목의 풍경을 펜과 수채로 담은 신진 작가 5인의 소규모 드로잉전.",
						"13:00~20:00 (월·화 휴관)", "무료",
						"https://example.com/mock/alley", 127.056000, 37.544000, "성동구"),
				new Row("SEED-MOCK-0002", "[MOCK] 필름 카메라로 담은 대구", "대구예술발전소",
						today.minusDays(1), today.plusDays(29), ExhibitionRegion.DAEGU, ExhibitionCategory.PHOTO,
						"https://picsum.photos/seed/modi-daegu/480/640",
						"필름 카메라로 기록한 대구의 골목과 사람들. 흑백·컬러 필름 사진 80여 점.",
						"10:00~19:00 (월요일 휴관)", "성인 5,000원",
						"https://example.com/mock/daegu", 128.594000, 35.869000, "중구"),
				new Row("SEED-MOCK-0003", "[MOCK] 종료된 회고전(과거 전시 표본)", "문화역서울284",
						today.minusDays(90), today.minusDays(30), ExhibitionRegion.SEOUL, ExhibitionCategory.MEDIA,
						"https://picsum.photos/seed/modi-past/480/640",
						"이미 종료된 전시 표본. '진행 중' 필터에서 제외되는 케이스를 확인하기 위한 데이터.",
						"10:00~19:00 (월요일 휴관)", "무료",
						"https://example.com/mock/past", 126.972000, 37.556000, "중구"));

		LocalDateTime now = LocalDateTime.now();
		rows.forEach(r -> {
			// 전시장 resolve-or-create(정규화 이름) — 데모라 신설 위주지만 같은 이름은 하나로 수렴한다.
			ExhibitionPlace place = exhibitionPlaceRepository.findByPlaceKey(
					modi.backend.domain.exhibition.hours.PlaceKey.of(r.place()))
					.orElseGet(() -> exhibitionPlaceRepository.save(ExhibitionPlace.createFromList(
							r.place(), r.region(), r.sigungu(), r.gpsX(), r.gpsY())));
			place.enrichDetail(null, null, r.detailUrl());
			exhibitionPlaceRepository.save(place);
			Exhibition saved = exhibitionRepository.save(Exhibition.createCatalog(
					r.externalId(), r.title(), place.getId(), r.startDate(), r.endDate(), r.category(),
					r.posterUrl(), r.detailUrl(), SERVICE_NAME));
			// 상세 satellite(price·description·img) + 영업시간 정준행(operatingHours) — 응답 전 필드가 채워지게.
			exhibitionRepository.saveDetail(ExhibitionDetail.create(saved.getId(), r.price(), r.description(),
					r.posterUrl(), now));
			if (exhibitionPlaceRepository.findHours(place.getId()).isEmpty()) {
				exhibitionPlaceRepository.saveHours(PlaceHours.first(place.getId(), r.operatingHours(),
						PlaceHoursStatus.SUCCEEDED, PlaceHoursVendor.MOCK, now));
			}
		});
		log.info("데모 시드: 표본 전시 {}건 적재 완료(공공데이터 기반 {}건 + MOCK 3건, 전 필드 채움)",
				rows.size(), rows.size() - 3);
	}

	/** region enum → 원천 area 원문 텍스트 근사(데모 전용 — 실제 원천은 자유 텍스트를 그대로 내려준다). */
	private static String areaTextFor(ExhibitionRegion region) {
		return switch (region) {
			case SEOUL -> "서울";
			case BUSAN -> "부산";
			case GYEONGGI -> "경기";
			case DAEGU -> "대구";
			default -> region.name();
		};
	}

	/** 응답 전 필드를 채운 표본 한 행(데모 전용). */
	private record Row(String externalId, String title, String place, LocalDate startDate, LocalDate endDate,
			ExhibitionRegion region, ExhibitionCategory category, String posterUrl, String description,
			String operatingHours, String price, String detailUrl, Double gpsX, Double gpsY, String sigungu) {
	}
}
