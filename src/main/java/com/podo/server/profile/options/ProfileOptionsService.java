package com.podo.server.profile.options;

import com.podo.server.classification.domain.Tag;
import com.podo.server.classification.repository.TagRepository;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileOptionsService {
    private final TagRepository tags;
    private final ProfileOptionsProperties properties;

    public ProfileOptionsService(TagRepository tags, ProfileOptionsProperties properties) {
        this.tags = tags;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public ProfileOptionsResponse getOptions() {
        var colors = properties.colors();
        var ageBands = properties.ageBands();
        if (colors == null || colors.isEmpty() || ageBands == null) {
            throw unavailable();
        }
        Set<String> colorCodes = new HashSet<>();
        for (var color : colors) {
            if (color == null || !validCodeAndLabel(color.code(), color.label())
                    || color.hex() == null || !color.hex().matches("#[0-9A-Fa-f]{6}")
                    || !colorCodes.add(color.code())) {
                throw unavailable();
            }
        }
        Set<String> ageCodes = new HashSet<>();
        for (var age : ageBands) {
            if (age == null || !validCodeAndLabel(age.code(), age.label()) || !ageCodes.add(age.code())) {
                throw unavailable();
            }
        }
        var rows = tags.findAllByKindIn(List.of(Tag.Kind.INTEREST, Tag.Kind.BOOK_GENRE));
        for (Tag tag : rows) {
            if (!validCodeAndLabel(tag.getCode(), tag.getLabel())) {
                throw unavailable();
            }
        }
        return new ProfileOptionsResponse(options(rows, Tag.Kind.INTEREST), options(rows, Tag.Kind.BOOK_GENRE),
                ageBands, colors, new ProfileOptionsResponse.ColorPolicy(false),
                new ProfileOptionsResponse.PhotoUploadPolicy(
                        List.of("image/jpeg", "image/png", "image/webp"), 5_242_880, 16_777_216));
    }

    private List<ProfileOptionsResponse.TagOption> options(List<Tag> rows, Tag.Kind kind) {
        return rows.stream().filter(tag -> tag.getKind() == kind)
                .sorted(Comparator.comparing(Tag::getCode))
                .map(tag -> new ProfileOptionsResponse.TagOption(tag.getId(), tag.getCode(), tag.getLabel(), tag.isActive()))
                .toList();
    }

    private boolean validCodeAndLabel(String code, String label) {
        return code != null && !code.isBlank() && code.length() <= 64 && label != null && !label.isBlank();
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.OPTIONS_NOT_CONFIGURED);
    }
}
