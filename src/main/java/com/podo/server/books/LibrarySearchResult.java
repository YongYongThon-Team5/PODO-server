package com.podo.server.books;

import java.util.List;

public record LibrarySearchResult(List<Row> rows, long totalCount, boolean truncated) {
    public LibrarySearchResult {
        rows = List.copyOf(rows);
    }

    public record Row(String title, String author, String publisher, String publicationYear, String library, String room) {
    }
}
