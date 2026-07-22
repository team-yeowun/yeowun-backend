package modi.backend.ingestion.infra.google;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import modi.backend.domain.exhibition.hours.PlaceHoursData;
import modi.backend.domain.exhibition.hours.WeeklyOpeningHours;
import modi.backend.ingestion.domain.data.GooglePlaceVendorItem;
import modi.backend.ingestion.domain.data.PlaceHoursFetch;

/**
 * 구글 Places(New) {@code places:searchText} 요청/응답 바인딩(외곽 1클래스 + 중첩 record — 컨벤션).
 * 응답에는 우리가 쓰지 않는 필드가 많아 응답 계열은 {@code ignoreUnknown}으로 관대하게 파싱한다.
 * 영업시간은 FieldMask로 {@code places.regularOpeningHours}만 요청하므로 그 하위만 매핑한다.
 * <p>
 * <b>응답 record가 자기를 도메인 값으로 표현한다</b>(culture의 {@code Item.toCatalog()}와 같은 모양):
 * 구글 어휘(0=일요일 day 인덱스, hour/minute)를 아는 자리는 여기 하나뿐이고, 조회기는 파싱을 모른다.
 */
public final class GoogleMapsDto {

    private static final Logger log = LoggerFactory.getLogger(GoogleMapsDto.class);

    /** 스냅샷 JSON 직렬화 전용 — 빈 주입 없이 자체 인스턴스를 둔다(무상태·스레드세이프). */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private GoogleMapsDto() {
    }

    // ----- 요청 -----

    /** Text Search 요청 본문. FieldMask·API 키는 헤더로 전달(여기엔 없음). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchTextRequest(String textQuery, String languageCode, String regionCode) {
    }

    // ----- 응답 -----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchTextResponse(List<Place> places) {

        /** Jackson이 결측 필드를 null로 매핑해도, 이후 로직은 항상 리스트를 다룬다(null 분기 제거). */
        public SearchTextResponse {
            places = places == null ? List.of() : places;
        }

        /** 전송 실패 등으로 응답 자체를 못 받았을 때 쓰는 빈 응답. firstPlace()가 곧 Optional.empty()로 수렴한다. */
        public static SearchTextResponse empty() {
            return new SearchTextResponse(List.of());
        }

        // ── 도메인 번역 ── (아래 세 메서드가 "응답 → 도메인 값" 파이프라인이다)

        /**
         * 이 응답을 영업시간 조회 결과로 옮긴다 — <b>응답 → 도메인 값의 진입점</b>이자 전체 흐름이 모이는 한 자리.
         * 1순위 후보({@link #firstPlace()})를 {@link Place#toFetch()}로 넘긴다. 후보가 없으면 {@link Optional#empty()}(미발견).
         */
        public Optional<PlaceHoursFetch> toPlaceHours() {
            return firstPlace().map(Place::toFetch);
        }

        /** 구글이 반환한 후보 중 랭킹 1순위를 채택한다(신뢰 정책 — buildQuery의 질의 정확도에 의존). */
        public Optional<Place> firstPlace() {
            return hasCandidates() ? Optional.of(places.get(0)) : Optional.empty();
        }

        /** 채택할 후보가 존재하는가. */
        public boolean hasCandidates() {
            return !places.isEmpty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Place(String id, DisplayName displayName, String formattedAddress,
                        RegularOpeningHours regularOpeningHours) {
        // record 컴포넌트 = 구글 응답 모양(바인딩). 아래 메서드 = 도메인 번역.

        public String displayText() {
            return displayName == null ? null : displayName.text();
        }

        /**
         * 조회 결과 한 벌(도메인 값 + 벤더 원문)로 표현한다. 영업시간이 없는 장소도 결과를 만든다 —
         * 장소 확인은 됐으므로 {@link WeeklyOpeningHours#empty()}를 담아 재조회 대상에서 빠지게 한다(포트 계약).
         */
        public PlaceHoursFetch toFetch() {
            // 응답 구조 필드 + 깊은 중첩(영업시간)은 구조 보존 JSON — 스냅샷(google_place_snapshot) 적재 어휘(ADR-13).
            GooglePlaceVendorItem vendor = new GooglePlaceVendorItem(id, displayText(), formattedAddress,
                    openingHoursJson());
            WeeklyOpeningHours weekly = regularOpeningHours == null
                    ? WeeklyOpeningHours.empty()
                    : regularOpeningHours.toWeekly();
            return new PlaceHoursFetch(new PlaceHoursData(weekly), vendor);
        }

        /**
         * 스냅샷의 {@code regular_opening_hours} JSON 컬럼에 남길 영업시간 중첩 구조를 직렬화한다(ADR-13).
         * place_id·displayName·formattedAddress는 별도 필드 컬럼으로 올라가므로 여기엔 영업시간만 남는다.
         */
        private String openingHoursJson() {
            if (regularOpeningHours == null) {
                return null;
            }
            try {
                return OBJECT_MAPPER.writeValueAsString(regularOpeningHours);
            } catch (Exception e) {
                log.debug("영업시간 원본 직렬화 실패(무시): {}", e.getMessage());
                return null;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisplayName(String text, String languageCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegularOpeningHours(List<Period> periods, List<String> weekdayDescriptions) {

        /**
         * periods를 요일별 영업시간(도메인 값)으로 표현한다.
         * day/시각 결측이나 close 부재(24시간 영업 등)인 구간은 건너뛴다(엣지 — P1에서 정교화).
         */
        public WeeklyOpeningHours toWeekly() {
            if (periods == null || periods.isEmpty()) {
                return WeeklyOpeningHours.empty();
            }
            WeeklyOpeningHours.Builder builder = WeeklyOpeningHours.builder();
            for (Period period : periods) {
                TimePoint open = period.open();
                TimePoint close = period.close();
                if (open == null || !open.usable() || close == null || !close.usable()) {
                    continue;
                }
                // 벤더 어휘(day 인덱스)는 TimePoint 안에 숨는다 — 이 루프는 "쓸 수 있는 구간을 요일별로 넣는다"만 읽힌다.
                builder.add(open.dayOfWeek(), open.toLocalTime(), close.toLocalTime());
            }
            return builder.build();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Period(TimePoint open, TimePoint close) {
    }

    /** day: 0=일요일 … 6=토요일(구글 규격). hour/minute는 24시간 기준. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimePoint(Integer day, Integer hour, Integer minute) {

        /** 구글 day(0=일요일 … 6=토요일) → java DayOfWeek. 이 벤더 어휘를 아는 곳은 여기뿐이다. */
        private static final DayOfWeek[] GOOGLE_DAY = {
                DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY };

        /** 요일·시각이 구글 규격 범위 안에 있는가(벗어나면 그 구간은 버린다). */
        public boolean usable() {
            return day != null && day >= 0 && day <= 6 && hour != null && hour >= 0 && hour <= 23;
        }

        /** 구글 day 인덱스를 java 요일로 옮긴다({@link #usable()} 통과가 전제 — 범위 밖은 이미 걸러진 뒤다). */
        public DayOfWeek dayOfWeek() {
            return GOOGLE_DAY[day];
        }

        /** minute 결측은 0시로, 범위 밖은 60으로 접어 방어한다({@link #usable()} 통과가 전제). */
        public LocalTime toLocalTime() {
            return LocalTime.of(hour, Math.floorMod(minute == null ? 0 : minute, 60));
        }
    }
}
