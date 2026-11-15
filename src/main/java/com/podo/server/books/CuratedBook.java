package com.podo.server.books;

public record CuratedBook(String title, String author, String publisher, String publicationYear) {
    static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized;
    }
}
