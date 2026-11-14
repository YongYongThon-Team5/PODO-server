package com.podo.server.books;

import com.podo.server.books.PlaceBookResponse.Item;
import com.podo.server.books.PlaceBookResponse.Warning;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.place.domain.Place;
import com.podo.server.place.repository.PlaceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PlaceBookService {
    private final PlaceRepository places;
    private final BookProperties properties;
    private final YonginLibraryClient client;

    public PlaceBookService(PlaceRepository places, BookProperties properties, YonginLibraryClient client) {
        this.places = places;
        this.properties = properties;
        this.client = client;
    }

    public PlaceBookResponse get(UUID placeId) {
        places.findByIdAndStatus(placeId, Place.PUBLISHED)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<CuratedBook> catalog;
        try {
            catalog = properties.catalog(placeId);
        } catch (BookProperties.InvalidCatalogException exception) {
            return empty(Warning.PROVIDER_NOT_CONFIGURED);
        }
        if (catalog.isEmpty()) return empty(Warning.NOT_CURATED);
        if (!properties.configured()) return empty(Warning.PROVIDER_NOT_CONFIGURED);

        List<Item> items = new ArrayList<>();
        EnumSet<Warning> warnings = EnumSet.noneOf(Warning.class);
        for (CuratedBook selected : catalog) {
            LibrarySearchResult result;
            try {
                result = client.search(selected);
            } catch (YonginLibraryClient.ProviderUnavailableException exception) {
                warnings.add(Warning.PROVIDER_UNAVAILABLE);
                continue;
            }
            if (result.truncated() || result.totalCount() > 100) warnings.add(Warning.RESULTS_TRUNCATED);
            LibrarySearchResult.Row matched = null;
            LinkedHashSet<String> libraries = new LinkedHashSet<>();
            for (LibrarySearchResult.Row row : result.rows()) {
                if (normalize(row.library()) != null && matches(selected, row)) {
                    if (matched == null) matched = row;
                    libraries.add(normalize(row.library()));
                }
            }
            if (matched == null) {
                warnings.add(Warning.CURATED_BOOK_NOT_FOUND);
            } else {
                items.add(new Item(normalize(matched.title()), List.copyOf(libraries)));
            }
        }
        return new PlaceBookResponse(List.copyOf(items), List.copyOf(warnings), Instant.now());
    }

    private boolean matches(CuratedBook selected, LibrarySearchResult.Row row) {
        return normalize(selected.title()).equals(normalize(row.title()))
                && normalize(selected.author()).equals(normalize(row.author()))
                && normalize(selected.publisher()).equals(normalize(row.publisher()))
                && normalize(selected.publicationYear()).equals(normalize(row.publicationYear()));
    }

    private String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized;
    }

    private PlaceBookResponse empty(Warning warning) {
        return new PlaceBookResponse(List.of(), List.of(warning), Instant.now());
    }
}
