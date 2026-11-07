package com.podo.server.place.service;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.CursorResponse;
import com.podo.server.place.domain.Place;
import com.podo.server.place.domain.PlaceSource;
import com.podo.server.place.repository.PlaceRepository;
import com.podo.server.place.repository.PlaceSourceRepository;
import com.podo.server.place.web.PlaceResponses.MapLinks;
import com.podo.server.place.web.PlaceResponses.PlaceDetail;
import com.podo.server.place.web.PlaceResponses.PlaceImage;
import com.podo.server.place.web.PlaceResponses.PlaceSummary;
import com.podo.server.place.web.PlaceResponses.SourceReference;
import com.podo.server.weather.Weather;
import com.podo.server.weather.WeatherService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PlaceService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final char SEP = '\u0000';

    private final PlaceRepository places;
    private final PlaceSourceRepository sources;
    private final WeatherService weather;

    public PlaceService(PlaceRepository places, PlaceSourceRepository sources, WeatherService weather) {
        this.places = places;
        this.sources = sources;
        this.weather = weather;
    }

    /** 지도 가시 영역. 경도·위도 순서는 명세의 bbox와 같다. */
    public record Bbox(double minLon, double minLat, double maxLon, double maxLat, String raw) {
    }

    public CursorResponse<PlaceSummary> list(String districtCode, Bbox bbox, String cursor, int limit) {
        String filterKey = (districtCode == null ? "" : districtCode) + "|" + (bbox == null ? "" : bbox.raw());
        String afterName = null;
        String afterId = null;
        if (cursor != null) {
            String[] parts = decodeCursor(cursor);
            if (!parts[0].equals(filterKey)) {
                throw new BusinessException(ErrorCode.INVALID_CURSOR);
            }
            afterName = parts[1];
            afterId = parts[2];
        }
        List<Place> rows = places.findPublishedPage(districtCode, bbox != null,
                bbox == null ? 0 : bbox.minLon(), bbox == null ? 0 : bbox.minLat(),
                bbox == null ? 0 : bbox.maxLon(), bbox == null ? 0 : bbox.maxLat(),
                afterName, afterId, limit + 1);
        boolean hasNext = rows.size() > limit;
        List<Place> page = hasNext ? rows.subList(0, limit) : rows;
        List<PlaceSummary> items = summaries(page);
        String next = hasNext ? encodeCursor(filterKey, page.get(page.size() - 1)) : null;
        return new CursorResponse<>(items, next);
    }

    /** 장소 목록을 PlaceSummary로 바꾼다(순서 유지). 추천·인기에서도 쓴다. */
    public List<PlaceSummary> summaries(List<Place> list) {
        List<UUID> ids = list.stream().map(Place::getId).toList();
        Map<UUID, String> thumbnails = thumbnails(ids);
        Map<UUID, String> quiz = quizAvailability(ids);
        return list.stream()
                .map(p -> summary(p, thumbnails.get(p.getId()), quiz.getOrDefault(p.getId(), "NOT_READY")))
                .toList();
    }

    public PlaceDetail detail(UUID placeId, LocalDate visitDate) {
        Place place = places.findByIdAndStatus(placeId, Place.PUBLISHED)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<PlaceImage> images = images(placeId);
        String quiz = quizAvailability(List.of(placeId)).getOrDefault(placeId, "NOT_READY");
        List<SourceReference> refs = sources.findByPlaceIdAndStatusOrderByIdAsc(placeId, PlaceSource.ACTIVE).stream()
                .map(s -> new SourceReference(s.getId(), s.getSourceType(), s.getSourceUrl(), s.getCheckedAt()))
                .toList();
        Weather w = visitDate == null ? null : weatherOrUnknown(place, visitDate);
        return new PlaceDetail(place.getId(), place.getName(), place.getAddress(), place.getLatitude(),
                place.getLongitude(), place.getDistrictCode(), list(place.getMainActivities()), place.getEnvironment(),
                images.isEmpty() ? null : images.get(0).url(), quiz, place.getDescription(), images,
                place.getOpeningHours(), place.getFeeInfo(), place.getTargetAgeInfo(),
                nullableList(place.getAmenities()), nullableList(place.getCautions()), refs, w,
                new MapLinks(place.getMapPlaceUrl(), place.getMapDirectionsUrl()),
                list(place.getInterestCategoryIds()));
    }

    private Weather weatherOrUnknown(Place place, LocalDate date) {
        try {
            return weather.forecast(place.getDistrictCode(), place.getLatitude(), place.getLongitude(), date);
        } catch (RuntimeException ex) {
            return Weather.unknown(date, "PROVIDER_FAILURE");
        }
    }

    private PlaceSummary summary(Place p, String thumbnailUrl, String quiz) {
        return new PlaceSummary(p.getId(), p.getName(), p.getAddress(), p.getLatitude(), p.getLongitude(),
                p.getDistrictCode(), list(p.getMainActivities()), p.getEnvironment(), thumbnailUrl, quiz,
                list(p.getInterestCategoryIds()));
    }

    private Map<UUID, String> thumbnails(Collection<UUID> ids) {
        Map<UUID, String> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        for (Object[] row : places.findThumbnailRows(ids)) {
            if (row[1] != null) {
                result.put(UUID.fromString((String) row[0]), (String) row[1]);
            }
        }
        return result;
    }

    /** READY: 3문항 공개 세트 있음, CORRECTION_REQUIRED: 공개 세트 없이 정정 세트만 있음, 그 외 NOT_READY. */
    private Map<UUID, String> quizAvailability(Collection<UUID> ids) {
        Map<UUID, String> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        for (Object[] row : places.findQuizStateRows(ids)) {
            UUID id = UUID.fromString((String) row[0]);
            boolean ready = Boolean.TRUE.equals(row[1]);
            boolean invalidated = Boolean.TRUE.equals(row[2]);
            result.put(id, ready ? "READY" : invalidated ? "CORRECTION_REQUIRED" : "NOT_READY");
        }
        return result;
    }

    private List<PlaceImage> images(UUID placeId) {
        String json = places.findImagesJson(placeId);
        List<PlaceImage> result = new ArrayList<>();
        if (json == null) {
            return result;
        }
        int position = 1;
        JsonNode root = JSON.readTree(json);
        for (int i = 0; i < root.size(); i++) {
            JsonNode node = root.get(i);
            JsonNode url = node.get("url");
            if (url == null || url.isNull()) {
                continue;
            }
            JsonNode id = node.get("id");
            UUID imageId = id != null && !id.isNull() ? UUID.fromString(id.asString())
                    : UUID.nameUUIDFromBytes((placeId + ":image:" + position).getBytes(StandardCharsets.UTF_8));
            JsonNode sourceUrl = node.get("sourceUrl");
            result.add(new PlaceImage(imageId, url.asString(), position,
                    sourceUrl == null || sourceUrl.isNull() ? null : sourceUrl.asString()));
            position++;
        }
        return result;
    }

    private static <T> List<T> list(T[] values) {
        return values == null ? List.of() : Arrays.asList(values);
    }

    private static <T> List<T> nullableList(T[] values) {
        return values == null ? null : Arrays.asList(values);
    }

    private static String encodeCursor(String filterKey, Place last) {
        String raw = filterKey + SEP + last.getName() + SEP + last.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(String.valueOf(SEP), -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException();
            }
            UUID.fromString(parts[2]);
            return parts;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_CURSOR);
        }
    }
}
