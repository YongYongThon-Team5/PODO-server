package com.podo.server.recommendation.service;

import com.podo.server.classification.domain.Tag;
import com.podo.server.classification.repository.TagRepository;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.place.domain.District;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 규칙 기반 추천(searchMode=RULE_BASED).
 * 점수 = 목적 일치 3 + 관심사 일치 수 × 2 + 책 분야 일치 수 × 1 + 실내외 선호 일치 1 − 흐림일 때 야외 2.
 * 비 예보면 야외·환경 미확인 장소와 실내 이용이 확인되지 않은 혼합 장소를 제외한다.
 */
@Service
public class RecommendationService {
    public static final String POLICY_VERSION = "2026-11-03";
    private static final int REQUESTED_COUNT = 5;

    private final PlaceRepository places;
    private final PlaceService placeService;
    private final TagRepository tags;
    private final ChildProfileRepository profiles;
    private final WeatherService weather;

    public RecommendationService(PlaceRepository places, PlaceService placeService, TagRepository tags,
                                 ChildProfileRepository profiles, WeatherService weather) {
        this.places = places;
        this.placeService = placeService;
        this.tags = tags;
        this.profiles = profiles;
        this.weather = weather;
    }

    public record Request(LocalDate visitDate, String purposeCode, UUID childProfileId, String districtCode,
                          String environmentPreference) {
    }

    private record Scored(Place place, int score, List<MatchedCondition> matched, Weather weather) {
    }

    public Result recommend(UUID accountId, Request request, String requestId) {
        Map<UUID, Tag> tagById = tags.findAll().stream().collect(Collectors.toMap(Tag::getId, Function.identity()));
        Tag purpose = tagById.values().stream()
                .filter(t -> t.getKind() == Tag.Kind.PURPOSE && t.isActive() && t.getCode().equals(request.purposeCode()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_OPTION));
        District district = null;
        if (request.districtCode() != null) {
            district = District.fromCode(request.districtCode());
            if (district == null) {
                throw new BusinessException(ErrorCode.INVALID_OPTION);
            }
        }

        Set<UUID> interests = Set.of();
        Set<UUID> books = Set.of();
        if (request.childProfileId() != null) {
            if (accountId == null) {
                throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
            }
            ChildProfile profile = profiles.findById(request.childProfileId())
                    .filter(p -> accountId.equals(p.getAccountId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            interests = new HashSet<>(Arrays.asList(profile.getInterestCategoryIds()));
            books = new HashSet<>(Arrays.asList(profile.getBookGenreIds()));
        }

        List<Place> candidates = district == null
                ? places.findByStatus(Place.PUBLISHED)
                : places.findByStatusAndDistrictCode(Place.PUBLISHED, district.code());

        Map<String, Weather> weatherByDistrict = new HashMap<>();
        List<Scored> eligible = new ArrayList<>();
        for (Place place : candidates) {
            String key = place.getDistrictCode() == null ? "" : place.getDistrictCode();
            Weather w = weatherByDistrict.computeIfAbsent(key, k ->
                    weather.forecast(place.getDistrictCode(), place.getLatitude(), place.getLongitude(),
                            request.visitDate()));
            Scored scored = score(place, w, purpose, interests, books, district, request.environmentPreference(), tagById);
            if (scored != null) {
                eligible.add(scored);
            }
        }

        eligible.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(s -> s.place().getName())
                .thenComparing(s -> s.place().getId().toString()));
        List<Scored> top = eligible.subList(0, Math.min(REQUESTED_COUNT, eligible.size()));
        List<PlaceSummary> summaries = placeService.summaries(top.stream().map(Scored::place).toList());
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            items.add(new Item(summaries.get(i), top.get(i).matched(), top.get(i).weather()));
        }

        String shortage = items.isEmpty() ? "NO_ELIGIBLE_PLACES"
                : items.size() < REQUESTED_COUNT ? "INSUFFICIENT_ELIGIBLE_PLACES" : null;
        long unknown = weatherByDistrict.values().stream().filter(w -> "UNKNOWN".equals(w.condition())).count();
        Set<String> warnings = new LinkedHashSet<>();
        if (!weatherByDistrict.isEmpty() && unknown == weatherByDistrict.size()) {
            warnings.add("WEATHER_UNAVAILABLE");
        } else if (unknown > 0) {
            warnings.add("WEATHER_PARTIAL");
        }
        return new Result(requestId, POLICY_VERSION, request.visitDate(), purpose.getCode(), request.childProfileId(),
                "RULE_BASED", REQUESTED_COUNT, items.size(), shortage, List.copyOf(warnings), items);
    }

    /** 제외 대상이면 null. */
    private Scored score(Place place, Weather w, Tag purpose, Set<UUID> interests, Set<UUID> books, District district,
                         String preference, Map<UUID, Tag> tagById) {
        String env = place.getEnvironment();
        boolean indoorCapable = "INDOOR".equals(env)
                || ("MIXED".equals(env) && Boolean.TRUE.equals(place.getIndoorOnlyAvailable()));
        boolean outdoorRule = "OUTDOOR".equals(env) || ("MIXED".equals(env) && !indoorCapable);
        String condition = w.condition();

        if ("RAIN".equals(condition) && !indoorCapable) {
            return null;
        }

        int score = 0;
        List<MatchedCondition> matched = new ArrayList<>();
        if (contains(place.getPurposeIds(), purpose.getId())) {
            score += 3;
            matched.add(new MatchedCondition("PURPOSE", purpose.getCode(), purpose.getLabel()));
        }
        score += 2 * addMatches(place.getInterestCategoryIds(), interests, "INTEREST", tagById, matched);
        score += addMatches(place.getBookGenreIds(), books, "BOOK_GENRE", tagById, matched);

        if ("INDOOR".equals(preference) && indoorCapable) {
            score += 1;
            matched.add(new MatchedCondition("ENVIRONMENT", "INDOOR", "실내 활동"));
        } else if ("OUTDOOR".equals(preference) && ("OUTDOOR".equals(env) || "MIXED".equals(env))) {
            score += 1;
            matched.add(new MatchedCondition("ENVIRONMENT", "OUTDOOR", "야외 활동"));
        }

        if (district != null) {
            matched.add(new MatchedCondition("REGION", district.code(), district.label()));
        }

        if ("CLOUDY".equals(condition) && outdoorRule) {
            score -= 2;
        } else if ("RAIN".equals(condition)) {
            matched.add(new MatchedCondition("WEATHER", "RAIN", "비 와도 실내 이용 가능"));
        } else if ("CLEAR".equals(condition) && outdoorRule) {
            matched.add(new MatchedCondition("WEATHER", "CLEAR", "맑은 날 야외 활동"));
        }
        return new Scored(place, score, matched, w);
    }

    private static int addMatches(UUID[] placeTags, Set<UUID> wanted, String kind, Map<UUID, Tag> tagById,
                                  List<MatchedCondition> matched) {
        if (placeTags == null || wanted.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (UUID id : placeTags) {
            Tag tag = tagById.get(id);
            if (tag != null && wanted.contains(id)) {
                matched.add(new MatchedCondition(kind, tag.getCode(), tag.getLabel()));
                count++;
            }
        }
        return count;
    }

    private static boolean contains(UUID[] values, UUID target) {
        if (values == null) {
            return false;
        }
        for (UUID value : values) {
            if (target.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
