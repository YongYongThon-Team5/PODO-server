package com.podo.server.books;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties("podo.books")
public record BookProperties(String apiKey, List<Recommendation> recommendations) {
    public BookProperties {
        recommendations = immutable(recommendations);
    }

    public List<CuratedBook> catalog(UUID placeId) {
        Set<UUID> places = new HashSet<>();
        List<CuratedBook> selected = List.of();
        for (Recommendation recommendation : recommendations) {
            if (recommendation == null || recommendation.placeId() == null
                    || !recommendation.placeId().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw new InvalidCatalogException();
            }
            UUID configuredPlace = UUID.fromString(recommendation.placeId());
            if (!places.add(configuredPlace) || recommendation.books().size() > 3) {
                throw new InvalidCatalogException();
            }
            Set<List<String>> books = new HashSet<>();
            for (CuratedBook book : recommendation.books()) {
                if (book == null || !StringUtils.hasText(book.title()) || !StringUtils.hasText(book.author())
                        || !StringUtils.hasText(book.publisher()) || book.publicationYear() == null
                        || !book.publicationYear().matches("[0-9]{4}") || "0000".equals(book.publicationYear())
                        || !books.add(List.of(normalize(book.title()), normalize(book.author()),
                                normalize(book.publisher()), book.publicationYear()))) {
                    throw new InvalidCatalogException();
                }
            }
            if (configuredPlace.equals(placeId)) {
                selected = recommendation.books();
            }
        }
        return selected;
    }

    public boolean configured() {
        return StringUtils.hasText(apiKey);
    }

    @Override
    public String toString() {
        return "BookProperties[apiKey=[REDACTED], recommendations=" + recommendations + "]";
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }

    public record Recommendation(String placeId, List<CuratedBook> books) {
        public Recommendation {
            books = immutable(books);
        }
    }

    public static final class InvalidCatalogException extends RuntimeException {
        public InvalidCatalogException() {
            super("Book catalog is not configured", null, false, true);
        }
    }
}
