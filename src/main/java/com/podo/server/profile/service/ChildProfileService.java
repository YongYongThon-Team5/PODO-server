package com.podo.server.profile.service;

import com.podo.server.auth.repository.AccountRepository;
import com.podo.server.classification.domain.Tag;
import com.podo.server.classification.repository.TagRepository;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.CursorResponse;
import com.podo.server.profile.domain.ChildProfile;
import com.podo.server.profile.image.domain.ProfileImage;
import com.podo.server.profile.image.repository.ProfileImageRepository;
import com.podo.server.profile.input.ChildProfileInput;
import com.podo.server.profile.input.ChildProfileInput.Field;
import com.podo.server.profile.options.ProfileOptionsService;
import com.podo.server.profile.pagination.ChildProfileCursor;
import com.podo.server.profile.repository.ChildProfileRepository;
import com.podo.server.profile.web.ChildProfileResponse;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChildProfileService {
    private final ChildProfileRepository profiles;
    private final AccountRepository accounts;
    private final TagRepository tags;
    private final ProfileImageRepository images;
    private final ProfileOptionsService options;
    private final Clock clock;

    @Transactional
    public ChildProfileResponse create(UUID accountId, ChildProfileInput input) {
        lockAccount(accountId);
        validateColor(input.colorCode(), null);
        validateAge(input.ageBandCode(), null);
        validateTags(input, null);
        requireUnusedColor(accountId, input.colorCode(), null);
        var imageRows = lockImages(accountId, null, input.profileImageId());
        ChildProfile profile = new ChildProfile(UUID.randomUUID(), accountId, input.nickname(), input.colorCode(),
                input.ageBandCode(), input.profileImageId(), input.interestCategoryIds().toArray(UUID[]::new),
                input.bookGenreIds().toArray(UUID[]::new), clock.instant());
        save(profile);
        if (input.profileImageId() != null) {
            imageRows.get(input.profileImageId()).markLinked();
        }
        return ChildProfileResponse.from(profile);
    }

    @Transactional
    public ChildProfileResponse patch(UUID accountId, UUID profileId, ChildProfileInput input) {
        lockAccount(accountId);
        ChildProfile profile = profiles.findByIdAndAccountId(profileId, accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        String color = input.has(Field.COLOR_CODE) ? input.colorCode() : profile.getColorCode();
        String age = input.has(Field.AGE_BAND_CODE) ? input.ageBandCode() : profile.getAgeBandCode();
        if (input.has(Field.COLOR_CODE)) {
            validateColor(color, profile.getColorCode());
            requireUnusedColor(accountId, color, profileId);
        }
        if (input.has(Field.AGE_BAND_CODE)) {
            validateAge(age, profile.getAgeBandCode());
        }
        validateTags(input, profile);
        UUID oldImage = profile.getProfileImageId();
        UUID newImage = input.has(Field.PROFILE_IMAGE_ID) ? input.profileImageId() : oldImage;
        Map<UUID, ProfileImage> imageRows = input.has(Field.PROFILE_IMAGE_ID)
                ? lockImages(accountId, oldImage, newImage) : Map.of();
        profile.change(input.has(Field.NICKNAME) ? input.nickname() : profile.getNickname(), color, age, newImage,
                input.has(Field.INTEREST_CATEGORY_IDS) ? input.interestCategoryIds().toArray(UUID[]::new)
                        : profile.getInterestCategoryIds(),
                input.has(Field.BOOK_GENRE_IDS) ? input.bookGenreIds().toArray(UUID[]::new) : profile.getBookGenreIds(),
                clock.instant());
        save(profile);
        if (input.has(Field.PROFILE_IMAGE_ID)) {
            if (newImage != null) {
                imageRows.get(newImage).markLinked();
            }
            if (oldImage != null && !oldImage.equals(newImage) && !profiles.existsByProfileImageId(oldImage)) {
                imageRows.get(oldImage).markOrphaned(clock.instant());
            }
        }
        return ChildProfileResponse.from(profile);
    }

    @Transactional(readOnly = true)
    public CursorResponse<ChildProfileResponse> list(UUID accountId, String cursor, int limit) {
        if (limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        var page = PageRequest.of(0, limit + 1);
        List<ChildProfile> rows;
        if (cursor == null) {
            rows = profiles.findByAccountIdOrderByCreatedAtAscIdAsc(accountId, page);
        } else {
            var position = ChildProfileCursor.decode(cursor, accountId);
            rows = profiles.findPageAfter(accountId, position.createdAt(), position.id(), page);
        }
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        String next = more ? ChildProfileCursor.encode(accountId, items.getLast().getCreatedAt(), items.getLast().getId()) : null;
        return new CursorResponse<>(items.stream().map(ChildProfileResponse::from).toList(), next);
    }

    private void lockAccount(UUID accountId) {
        if (accounts.findLockedById(accountId).filter(account -> account.isActive()).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
    }

    private void validateColor(String color, String current) {
        if (Objects.equals(color, current)) {
            return;
        }
        if (options.colorOptions().stream().noneMatch(option -> option.active() && option.code().equals(color))) {
            throw new BusinessException(ErrorCode.INVALID_OPTION);
        }
    }

    private void validateAge(String age, String current) {
        if (age == null || Objects.equals(age, current)) {
            return;
        }
        if (options.ageOptions().stream().noneMatch(option -> option.active() && option.code().equals(age))) {
            throw new BusinessException(ErrorCode.INVALID_OPTION);
        }
    }

    private void requireUnusedColor(UUID accountId, String color, UUID currentId) {
        boolean used = currentId == null ? profiles.existsByAccountIdAndColorCode(accountId, color)
                : profiles.existsByAccountIdAndColorCodeAndIdNot(accountId, color, currentId);
        if (used) {
            throw new BusinessException(ErrorCode.COLOR_ALREADY_USED);
        }
    }

    private void validateTags(ChildProfileInput input, ChildProfile current) {
        Set<UUID> requested = new HashSet<>();
        if (input.has(Field.INTEREST_CATEGORY_IDS)) requested.addAll(input.interestCategoryIds());
        if (input.has(Field.BOOK_GENRE_IDS)) requested.addAll(input.bookGenreIds());
        if (requested.isEmpty()) return;
        Map<UUID, Tag> rows = tags.findAllForValidation(requested).stream()
                .collect(Collectors.toMap(Tag::getId, Function.identity()));
        if (input.has(Field.INTEREST_CATEGORY_IDS)) {
            checkTags(input.interestCategoryIds(), Tag.Kind.INTEREST,
                    current == null ? Set.of() : new HashSet<>(Arrays.asList(current.getInterestCategoryIds())), rows);
        }
        if (input.has(Field.BOOK_GENRE_IDS)) {
            checkTags(input.bookGenreIds(), Tag.Kind.BOOK_GENRE,
                    current == null ? Set.of() : new HashSet<>(Arrays.asList(current.getBookGenreIds())), rows);
        }
    }

    private void checkTags(List<UUID> selected, Tag.Kind kind, Set<UUID> retained, Map<UUID, Tag> rows) {
        for (UUID id : selected) {
            Tag tag = rows.get(id);
            if (tag == null) throw new BusinessException(ErrorCode.INVALID_OPTION);
            if (tag.getKind() != kind) throw new BusinessException(ErrorCode.INVALID_TAG_KIND);
            if (!tag.isActive() && !retained.contains(id)) throw new BusinessException(ErrorCode.INVALID_OPTION);
        }
    }

    private Map<UUID, ProfileImage> lockImages(UUID accountId, UUID oldImage, UUID newImage) {
        Set<UUID> ids = new HashSet<>();
        if (oldImage != null) ids.add(oldImage);
        if (newImage != null) ids.add(newImage);
        if (ids.isEmpty()) return Map.of();
        var rows = images.findOwnedLocked(accountId, ids);
        if (rows.size() != ids.size()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return rows.stream().collect(Collectors.toMap(ProfileImage::getId, Function.identity()));
    }

    private void save(ChildProfile profile) {
        try {
            profiles.saveAndFlush(profile);
        } catch (DataIntegrityViolationException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uq_child_profiles_account_color".equals(violation.getConstraintName())) {
                    throw new BusinessException(ErrorCode.COLOR_ALREADY_USED);
                }
            }
            throw ex;
        }
    }
}
