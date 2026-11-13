package com.podo.server.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.podo.server.classification.domain.Tag;
import com.podo.server.classification.repository.TagRepository;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.place.domain.Place;
import com.podo.server.place.repository.PlaceRepository;
import com.podo.server.place.service.PlaceService;
import com.podo.server.place.web.PlaceResponses.PlaceSummary;
import com.podo.server.profile.domain.ChildProfile;
import com.podo.server.profile.repository.ChildProfileRepository;
import com.podo.server.recommendation.web.RecommendationResponses.Item;
import com.podo.server.recommendation.web.RecommendationResponses.MatchedCondition;
import com.podo.server.recommendation.web.RecommendationResponses.Result;
import com.podo.server.weather.Weather;
import com.podo.server.weather.WeatherService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 규칙 기반 추천: 비·흐림 처리와 아이 프로필(childProfileId) 분기.
 * 점수 = 목적 3 + 관심사 × 2 + 책 분야 × 1 + 실내외 선호 1 − 흐림일 때 야외 2. 동점은 이름순.
 */
@DisplayName("RecommendationService 규칙 기반 추천")
class RecommendationServiceTest {

    private static final LocalDate VISIT_DATE = LocalDate.of(2026, 11, 10);
    private static final String REQUEST_ID = "test-request";

    private final PlaceRepository places = mock(PlaceRepository.class);
    private final PlaceService placeService = mock(PlaceService.class);
    private final TagRepository tags = mock(TagRepository.class);
    private final ChildProfileRepository profiles = mock(ChildProfileRepository.class);
    private final WeatherService weather = mock(WeatherService.class);
    private final RecommendationService service =
            new RecommendationService(places, placeService, tags, profiles, weather);

    private final Tag learn = tag(Tag.Kind.PURPOSE, "LEARN", "체험·배움", true);
    private final Tag retired = tag(Tag.Kind.PURPOSE, "RETIRED", "사용 중지된 목적", false);
    private final Tag nature = tag(Tag.Kind.INTEREST, "NATURE", "자연", true);
    private final Tag animal = tag(Tag.Kind.BOOK_GENRE, "ANIMAL", "동물", true);

    @BeforeEach
    void setUp() {
        when(tags.findAll()).thenReturn(List.of(learn, retired, nature, animal));
        when(placeService.summaries(any())).thenAnswer(inv -> {
            List<Place> list = inv.getArgument(0);
            return list.stream().map(RecommendationServiceTest::summary).toList();
        });
    }

    @Nested
    @DisplayName("날씨")
    class WeatherRules {
        private final Place indoor = place("가실내", "INDOOR", null);
        private final Place outdoor = place("나야외", "OUTDOOR", null);
        private final Place mixedIndoorOk = place("다혼합_실내가능", "MIXED", true);
        private final Place mixed = place("라혼합_실내미확인", "MIXED", null);
        private final Place unknownEnv = place("마환경미확인", "UNKNOWN", null);

        @BeforeEach
        void publishedPlaces() {
            when(places.findByStatus(Place.PUBLISHED))
                    .thenReturn(List.of(unknownEnv, mixed, mixedIndoorOk, outdoor, indoor));
        }

        @Test
        @DisplayName("비가 오면 실내와 실내 이용이 확인된 혼합 장소만 추천한다")
        void rainKeepsOnlyIndoorCapablePlaces() {
            weatherIs("RAIN");

            Result result = recommend(null, null, null, null);

            assertThat(names(result)).containsExactly("가실내", "다혼합_실내가능");
            assertThat(result.items()).allSatisfy(item ->
                    assertThat(item.matchedConditions()).contains(condition("WEATHER", "RAIN")));
            assertThat(result.returnedCount()).isEqualTo(2);
            assertThat(result.shortageReason()).isEqualTo("INSUFFICIENT_ELIGIBLE_PLACES");
            assertThat(result.warnings()).isEmpty();
        }

        @Test
        @DisplayName("비가 오면 야외 선호를 골라도 야외 장소는 제외한다")
        void rainExcludesOutdoorEvenWithOutdoorPreference() {
            weatherIs("RAIN");

            Result result = recommend(null, null, null, "OUTDOOR");

            // 우천 제외가 선호보다 우선. 남은 혼합 장소는 야외 공간도 있어 야외 선호 +1점을 받아 앞선다.
            assertThat(names(result)).containsExactly("다혼합_실내가능", "가실내");
            assertThat(item(result, "다혼합_실내가능").matchedConditions())
                    .contains(new MatchedCondition("ENVIRONMENT", "OUTDOOR", "야외 활동"));
        }

        @Test
        @DisplayName("비가 오는데 후보가 하나도 없으면 NO_ELIGIBLE_PLACES")
        void rainWithNoIndoorPlacesIsNoEligiblePlaces() {
            when(places.findByStatus(Place.PUBLISHED)).thenReturn(List.of(outdoor, mixed));
            weatherIs("RAIN");

            Result result = recommend(null, null, null, null);

            assertThat(result.items()).isEmpty();
            assertThat(result.returnedCount()).isZero();
            assertThat(result.shortageReason()).isEqualTo("NO_ELIGIBLE_PLACES");
        }

        @Test
        @DisplayName("흐리면 야외 장소는 제외하지 않고 뒤로 보낸다")
        void cloudyPenalizesOutdoorWithoutExcluding() {
            weatherIs("CLOUDY");

            Result result = recommend(null, null, null, null);

            // 실내·실내가능 혼합·환경미확인은 3점, 야외·실내미확인 혼합은 3 − 2 = 1점
            assertThat(names(result))
                    .containsExactly("가실내", "다혼합_실내가능", "마환경미확인", "나야외", "라혼합_실내미확인");
            assertThat(result.items()).allSatisfy(item ->
                    assertThat(kinds(item)).doesNotContain("WEATHER"));
        }

        @Test
        @DisplayName("맑으면 감점 없이 이름순이고 야외 장소에 맑은 날 조건이 붙는다")
        void clearHasNoPenaltyAndMarksOutdoor() {
            weatherIs("CLEAR");

            Result result = recommend(null, null, null, null);

            assertThat(names(result))
                    .containsExactly("가실내", "나야외", "다혼합_실내가능", "라혼합_실내미확인", "마환경미확인");
            assertThat(item(result, "나야외").matchedConditions()).contains(condition("WEATHER", "CLEAR"));
            assertThat(item(result, "라혼합_실내미확인").matchedConditions()).contains(condition("WEATHER", "CLEAR"));
            assertThat(kinds(item(result, "가실내"))).doesNotContain("WEATHER");
            assertThat(result.shortageReason()).isNull();
        }

        @Test
        @DisplayName("날씨를 모르면 아무것도 제외하지 않고 WEATHER_UNAVAILABLE 경고")
        void unknownWeatherExcludesNothingAndWarns() {
            weatherIs("UNKNOWN");

            Result result = recommend(null, null, null, null);

            assertThat(result.items()).hasSize(5);
            assertThat(result.warnings()).containsExactly("WEATHER_UNAVAILABLE");
            assertThat(result.items()).allSatisfy(item ->
                    assertThat(item.weather().condition()).isEqualTo("UNKNOWN"));
        }

        @Test
        @DisplayName("실내 선호는 가산점일 뿐 야외 장소를 빼지 않는다")
        void indoorPreferenceRanksButDoesNotExclude() {
            weatherIs("CLEAR");

            Result result = recommend(null, null, null, "INDOOR");

            assertThat(names(result))
                    .containsExactly("가실내", "다혼합_실내가능", "나야외", "라혼합_실내미확인", "마환경미확인");
            assertThat(item(result, "가실내").matchedConditions()).contains(condition("ENVIRONMENT", "INDOOR"));
            assertThat(kinds(item(result, "나야외"))).doesNotContain("ENVIRONMENT");
        }
    }

    @Nested
    @DisplayName("아이 프로필")
    class ChildProfileBranch {
        private final UUID me = UUID.randomUUID();
        private final UUID someoneElse = UUID.randomUUID();
        private final UUID profileId = UUID.randomUUID();

        private final Place museum = place("가박물관", "INDOOR", null);
        private final Place forest = place("나숲체험장", "OUTDOOR", null);

        @BeforeEach
        void setUp() {
            ReflectionTestUtils.setField(forest, "interestCategoryIds", new UUID[]{nature.getId()});
            ReflectionTestUtils.setField(forest, "bookGenreIds", new UUID[]{animal.getId()});
            when(places.findByStatus(Place.PUBLISHED)).thenReturn(List.of(museum, forest));
            weatherIs("CLEAR");
        }

        @Test
        @DisplayName("로그인하지 않고 프로필 ID를 보내면 401")
        void profileIdWithoutLoginIs401() {
            assertThatThrownBy(() -> recommend(null, profileId, null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED);
            verify(profiles, never()).findById(any());
        }

        @Test
        @DisplayName("다른 사람의 프로필이면 404")
        void someoneElsesProfileIs404() {
            when(profiles.findById(profileId)).thenReturn(Optional.of(profile(someoneElse)));

            assertThatThrownBy(() -> recommend(me, profileId, null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("없는 프로필이면 404")
        void missingProfileIs404() {
            when(profiles.findById(profileId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> recommend(me, profileId, null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("프로필이 없으면 목적만으로 추천한다")
        void noProfileUsesPurposeOnly() {
            Result result = recommend(null, null, null, null);

            assertThat(names(result)).containsExactly("가박물관", "나숲체험장");
            assertThat(result.childProfileId()).isNull();
            verify(profiles, never()).findById(any());
        }

        @Test
        @DisplayName("내 프로필의 관심사와 책 분야가 점수에 반영된다")
        void ownProfileAddsInterestAndBookGenreScore() {
            when(profiles.findById(profileId)).thenReturn(Optional.of(profile(me)));

            Result result = recommend(me, profileId, null, null);

            // 나숲체험장 = 목적 3 + 관심사 2 + 책 분야 1 = 6점, 가박물관 = 목적 3점
            assertThat(names(result)).containsExactly("나숲체험장", "가박물관");
            assertThat(item(result, "나숲체험장").matchedConditions())
                    .contains(condition("INTEREST", "NATURE"), condition("BOOK_GENRE", "ANIMAL"));
            assertThat(kinds(item(result, "가박물관"))).doesNotContain("INTEREST", "BOOK_GENRE");
            assertThat(result.childProfileId()).isEqualTo(profileId);
        }

        private ChildProfile profile(UUID accountId) {
            return new ChildProfile(profileId, accountId, "아이", "COLOR_1", null, null,
                    new UUID[]{nature.getId()}, new UUID[]{animal.getId()}, Instant.parse("2026-11-01T00:00:00Z"));
        }
    }

    @Nested
    @DisplayName("입력 검증")
    class Validation {

        @Test
        @DisplayName("없는 목적 코드는 INVALID_OPTION")
        void unknownPurposeIsInvalidOption() {
            assertThatThrownBy(() -> service.recommend(null,
                    new RecommendationService.Request(VISIT_DATE, "XXX", null, null, null), REQUEST_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_OPTION);
        }

        @Test
        @DisplayName("사용 중지된 목적 코드는 INVALID_OPTION")
        void inactivePurposeIsInvalidOption() {
            assertThatThrownBy(() -> service.recommend(null,
                    new RecommendationService.Request(VISIT_DATE, "RETIRED", null, null, null), REQUEST_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_OPTION);
        }

        @Test
        @DisplayName("없는 지역 코드는 INVALID_OPTION")
        void unknownDistrictIsInvalidOption() {
            assertThatThrownBy(() -> recommend(null, null, "SEOUL", null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_OPTION);
        }

        @Test
        @DisplayName("지역을 고르면 그 지역 장소만 조회하고 지역 조건이 붙는다")
        void districtFiltersAndAddsRegionCondition() {
            Place cheoin = place("가처인장소", "INDOOR", null);
            when(places.findByStatusAndDistrictCode(Place.PUBLISHED, "CHEOIN")).thenReturn(List.of(cheoin));
            weatherIs("CLEAR");

            Result result = recommend(null, null, "CHEOIN", null);

            assertThat(names(result)).containsExactly("가처인장소");
            assertThat(result.items().get(0).matchedConditions()).contains(condition("REGION", "CHEOIN"));
            verify(places, never()).findByStatus(any());
        }
    }

    // ---- helpers ----

    private Result recommend(UUID accountId, UUID childProfileId, String districtCode, String preference) {
        return service.recommend(accountId,
                new RecommendationService.Request(VISIT_DATE, "LEARN", childProfileId, districtCode, preference),
                REQUEST_ID);
    }

    private void weatherIs(String condition) {
        Weather w = "UNKNOWN".equals(condition)
                ? Weather.unknown(VISIT_DATE, "PROVIDER_FAILURE")
                : new Weather(VISIT_DATE, condition, null, OffsetDateTime.now(Weather.SEOUL),
                        OffsetDateTime.now(Weather.SEOUL));
        when(weather.forecast(any(), any(), any(), any())).thenReturn(w);
    }

    /** 목적 LEARN, 처인구, 관심사·책 분야 없음. */
    private Place place(String name, String environment, Boolean indoorOnlyAvailable) {
        Place place = newInstance(Place.class);
        ReflectionTestUtils.setField(place, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(place, "name", name);
        ReflectionTestUtils.setField(place, "districtCode", "CHEOIN");
        ReflectionTestUtils.setField(place, "environment", environment);
        ReflectionTestUtils.setField(place, "indoorOnlyAvailable", indoorOnlyAvailable);
        ReflectionTestUtils.setField(place, "purposeIds", new UUID[]{learn.getId()});
        ReflectionTestUtils.setField(place, "interestCategoryIds", new UUID[0]);
        ReflectionTestUtils.setField(place, "bookGenreIds", new UUID[0]);
        ReflectionTestUtils.setField(place, "status", Place.PUBLISHED);
        return place;
    }

    private static Tag tag(Tag.Kind kind, String code, String label, boolean active) {
        Tag tag = newInstance(Tag.class);
        ReflectionTestUtils.setField(tag, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(tag, "kind", kind);
        ReflectionTestUtils.setField(tag, "code", code);
        ReflectionTestUtils.setField(tag, "label", label);
        ReflectionTestUtils.setField(tag, "active", active);
        return tag;
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PlaceSummary summary(Place p) {
        return new PlaceSummary(p.getId(), p.getName(), p.getAddress(), p.getLatitude(), p.getLongitude(),
                p.getDistrictCode(), List.of(), p.getEnvironment(), null, List.of());
    }

    private static List<String> names(Result result) {
        return result.items().stream().map(i -> i.place().name()).toList();
    }

    private static Item item(Result result, String name) {
        return result.items().stream().filter(i -> i.place().name().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> kinds(Item item) {
        return item.matchedConditions().stream().map(MatchedCondition::kind).toList();
    }

    private static MatchedCondition condition(String kind, String code) {
        String label = switch (kind + ":" + code) {
            case "WEATHER:RAIN" -> "비 와도 실내 이용 가능";
            case "WEATHER:CLEAR" -> "맑은 날 야외 활동";
            case "ENVIRONMENT:INDOOR" -> "실내 활동";
            case "INTEREST:NATURE" -> "자연";
            case "BOOK_GENRE:ANIMAL" -> "동물";
            case "REGION:CHEOIN" -> "처인구";
            default -> throw new IllegalArgumentException(kind + ":" + code);
        };
        return new MatchedCondition(kind, code, label);
    }
}
