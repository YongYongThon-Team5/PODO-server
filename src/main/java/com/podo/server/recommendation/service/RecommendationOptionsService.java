package com.podo.server.recommendation.service;

import com.podo.server.classification.domain.Tag;
import com.podo.server.classification.repository.TagRepository;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.place.domain.District;
import com.podo.server.recommendation.web.RecommendationOptionsResponse;
import com.podo.server.recommendation.web.RecommendationOptionsResponse.EnvironmentPreference;
import com.podo.server.recommendation.web.RecommendationOptionsResponse.Option;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationOptionsService {
    private static final List<EnvironmentPreference> ENVIRONMENT_PREFERENCES = List.of(
            new EnvironmentPreference("INDOOR", "실내 선호"),
            new EnvironmentPreference("OUTDOOR", "야외 선호"),
            new EnvironmentPreference("ANY", "선호 없음"));

    private final TagRepository tags;

    public RecommendationOptionsService(TagRepository tags) {
        this.tags = tags;
    }

    /** 목적은 tags(PURPOSE), 지역은 District. 목적 운영 목록이 없으면 503 OPTIONS_NOT_CONFIGURED. */
    @Transactional(readOnly = true)
    public RecommendationOptionsResponse getOptions() {
        List<Option> purposes = tags.findAllByKindIn(List.of(Tag.Kind.PURPOSE)).stream()
                .filter(tag -> tag.getCode() != null && !tag.getCode().isBlank()
                        && tag.getLabel() != null && !tag.getLabel().isBlank())
                .sorted(Comparator.comparing(Tag::getCode))
                .map(tag -> new Option(tag.getCode(), tag.getLabel(), tag.isActive()))
                .toList();
        if (purposes.isEmpty()) {
            throw new BusinessException(ErrorCode.OPTIONS_NOT_CONFIGURED);
        }
        List<Option> districts = Arrays.stream(District.values())
                .map(district -> new Option(district.code(), district.label(), true))
                .toList();
        return new RecommendationOptionsResponse(purposes, districts, ENVIRONMENT_PREFERENCES);
    }
}
