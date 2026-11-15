package com.podo.server.books;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.place.domain.Place;
import com.podo.server.place.repository.PlaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PlaceBookServiceTest {
    private final UUID placeId = UUID.randomUUID();
    private final PlaceRepository places = mock(PlaceRepository.class);
    private final YonginLibraryClient client = mock(YonginLibraryClient.class);
    private final CuratedBook first = new CuratedBook("합성 첫 책", "첫 저자", "출판사", "2024");
    private final CuratedBook second = new CuratedBook("합성 둘 책", "둘 저자", "출판사", "2025");

    @BeforeEach
    void published() {
        when(places.findByIdAndStatus(placeId, Place.PUBLISHED)).thenReturn(Optional.of(mock(Place.class)));
    }

    @Test
    void preservesSelectionOrderAndUsesOnlyActualProviderRows() {
        when(client.search(first)).thenReturn(result(row(first, "첫 도서관", null)));
        when(client.search(second)).thenReturn(result(row(second, "둘 도서관", "자료실")));
        var response = service("synthetic", List.of(second, first)).get(placeId);
        assertThat(response.items()).extracting(PlaceBookResponse.Item::title)
                .containsExactly(second.title(), first.title());
        assertThat(response.items().get(1).libraries()).containsExactly("첫 도서관");
        assertThat(response.warnings()).isEmpty();
        assertThat(response.checkedAt()).isBetween(Instant.now().minusSeconds(5), Instant.now());
    }

    @Test
    void filtersIncompleteRowsAndMatchesAllFourFieldsAfterWhitespaceNormalization() {
        CuratedBook spaced = new CuratedBook(" 합성   첫 책 ", "첫 저자", "출판사", "2024");
        when(client.search(spaced)).thenReturn(result(
                new LibrarySearchResult.Row("합성 첫 책", "첫 저자", "출판사", "2023", "잘못된 연도", null),
                new LibrarySearchResult.Row("합성 첫 책", "다른 저자", "출판사", "2024", "잘못된 저자", null),
                new LibrarySearchResult.Row("합성 첫 책", "첫 저자", "다른 출판사", "2024", "잘못된 출판사", null),
                new LibrarySearchResult.Row("합성 첫 책 개정판", "첫 저자", "출판사", "2024", "다른 제목", null),
                new LibrarySearchResult.Row(null, "첫 저자", "출판사", "2024", "불완전", null),
                row(first, " ", null), row(first, " 도서관  A ", " 어린이  실 "),
                row(first, "도서관 A", "어린이 실"), row(first, "도서관 A", " "),
                row(first, "도서관 A", null), row(first, "도서관 B", null)));
        var response = service("synthetic", List.of(spaced)).get(placeId);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().title()).isEqualTo(first.title());
        assertThat(response.items().getFirst().libraries()).containsExactly("도서관 A", "도서관 B");
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void partialFailureKeepsGoodBooksAndWarningsAreDistinctInContractOrder() {
        CuratedBook third = new CuratedBook("셋", "저자", "출판사", "2024");
        when(client.search(first)).thenThrow(new YonginLibraryClient.ProviderUnavailableException());
        when(client.search(second)).thenReturn(new LibrarySearchResult(List.of(row(second, "도서관", null)), 101, true));
        when(client.search(third)).thenReturn(new LibrarySearchResult(List.of(), 150, true));
        var response = service("synthetic", List.of(first, second, third)).get(placeId);
        assertThat(response.items()).extracting(PlaceBookResponse.Item::title).containsExactly(second.title());
        assertThat(response.warnings()).containsExactly(PlaceBookResponse.Warning.PROVIDER_UNAVAILABLE,
                PlaceBookResponse.Warning.RESULTS_TRUNCATED, PlaceBookResponse.Warning.CURATED_BOOK_NOT_FOUND);
        verify(client, times(3)).search(any());
    }

    @Test
    void allFailuresAreUnavailableAndNeverNotFound() {
        when(client.search(any())).thenThrow(new YonginLibraryClient.ProviderUnavailableException());
        var response = service("synthetic", List.of(first, second)).get(placeId);
        assertThat(response.items()).isEmpty();
        assertThat(response.warnings()).containsExactly(PlaceBookResponse.Warning.PROVIDER_UNAVAILABLE);
    }

    @Test
    void noMatchNeverSynthesizesCuratedMetadataAndStillReportsTotalCountTruncation() {
        when(client.search(first)).thenReturn(new LibrarySearchResult(List.of(row(second, "도서관", null)), 101, false));
        var response = service("synthetic", List.of(first)).get(placeId);
        assertThat(response.items()).isEmpty();
        assertThat(response.warnings()).containsExactly(PlaceBookResponse.Warning.RESULTS_TRUNCATED,
                PlaceBookResponse.Warning.CURATED_BOOK_NOT_FOUND);
    }

    @Test
    void emptyCatalogIsNotCuratedBeforeCheckingKey() {
        BookProperties properties = mock(BookProperties.class);
        when(properties.catalog(placeId)).thenReturn(List.of());
        var response = new PlaceBookService(places, properties, client).get(placeId);
        assertThat(response.items()).isEmpty();
        assertThat(response.warnings()).containsExactly(PlaceBookResponse.Warning.NOT_CURATED);
        verify(properties, never()).configured();
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void absentKeyDoesNotCallProvider(String key) {
        var response = service(key, List.of(first)).get(placeId);
        assertThat(response.items()).isEmpty();
        assertThat(response.warnings()).containsExactly(PlaceBookResponse.Warning.PROVIDER_NOT_CONFIGURED);
        verifyNoInteractions(client);
    }

    @Test
    void invalidCatalogIncludingTooManyBooksIsNotConfigured() {
        var response = service("synthetic", List.of(first, second, first, second)).get(placeId);
        assertThat(response.items()).isEmpty();
        assertThat(response.warnings()).containsExactly(PlaceBookResponse.Warning.PROVIDER_NOT_CONFIGURED);
        verifyNoInteractions(client);
    }

    @Test
    void unpublishedPlaceFailsBeforeAnyConfigurationOrProviderAccess() {
        when(places.findByIdAndStatus(placeId, Place.PUBLISHED)).thenReturn(Optional.empty());
        BookProperties properties = mock(BookProperties.class);
        assertThatThrownBy(() -> new PlaceBookService(places, properties, client).get(placeId))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verifyNoInteractions(properties, client);
    }

    private PlaceBookService service(String key, List<CuratedBook> books) {
        return new PlaceBookService(places,
                new BookProperties(key, List.of(new BookProperties.Recommendation(placeId.toString(), books))), client);
    }

    private LibrarySearchResult result(LibrarySearchResult.Row... rows) {
        return new LibrarySearchResult(List.of(rows), rows.length, false);
    }

    private LibrarySearchResult.Row row(CuratedBook book, String library, String room) {
        return new LibrarySearchResult.Row(book.title(), book.author(), book.publisher(), book.publicationYear(), library, room);
    }
}
