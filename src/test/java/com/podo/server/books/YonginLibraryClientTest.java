package com.podo.server.books;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YonginLibraryClientTest {
    private static final String ENDPOINT = "https://apis.data.go.kr/4050000/libnewbk/getLibnewbk";
    private static final UUID PLACE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final CuratedBook BOOK = new CuratedBook("무지개 물고기", "합성 저자", "합성 출판사", "2007");
    private static final String ROW = """
            {"no":1,"bk_nm":"무지개 물고기","aut_nm":"합성 저자","pblshr":"합성 출판사",
             "pblcn_yr":"2007","lib":"합성도서관","refrm":"합성자료실",
             "loan_yn":"N","rtn_ed":"2022-01-29"}
            """;
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private YonginLibraryClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = client("synthetic-key");
    }

    @Test
    void searchesOnlyTheFixedFirstPageAndReturnsBibliographicAndLibraryFields() {
        server.expect(requestTo(uri("synthetic-key", BOOK))).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(response("0", "NORMAL_CODE", 1, 100, 1, ROW), MediaType.APPLICATION_JSON));

        LibrarySearchResult result = client.search(BOOK);

        assertThat(result.totalCount()).isEqualTo(1);
        assertThat(result.truncated()).isFalse();
        assertThat(result.rows()).containsExactly(new LibrarySearchResult.Row(
                BOOK.title(), BOOK.author(), BOOK.publisher(), "2007", "합성도서관", "합성자료실"));
        assertThatThrownBy(() -> result.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"synthetic+/=", "synthetic%2B%2F%3D", "synthetic%252B", "synthetic%", "synthetic+%2F"})
    void encodesRawOrPortalEncodedKeysExactlyOnceWithoutTurningPlusIntoSpace(String key) {
        String raw = switch (key) {
            case "synthetic%2B%2F%3D" -> "synthetic+/=";
            case "synthetic%252B" -> "synthetic%2B";
            case "synthetic+%2F" -> "synthetic+/";
            default -> key;
        };
        client = client(key);
        server.expect(requestTo(uri(raw, BOOK)))
                .andRespond(withSuccess("{\"resultcode\":\"3\",\"resultMsg\":\"NODATA_ERROR\"}", MediaType.APPLICATION_JSON));

        assertThat(client.search(BOOK).rows()).isEmpty();
        server.verify();
    }

    @Test
    void encodesSearchFieldsAsValuesRatherThanQueryParameters() {
        CuratedBook book = new CuratedBook("합성 + & # 제목", "저자/이름", "출판사=이름", "2024");
        server.expect(requestTo(uri("synthetic-key", book)))
                .andRespond(withSuccess("{\"resultcode\":3,\"resultMsg\":\"NODATA_ERROR\"}", MediaType.APPLICATION_JSON));
        assertThat(client.search(book).totalCount()).isZero();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"resultcode\":\"3\",\"resultMsg\":\"NODATA_ERROR\"}",
            "{\"resultcode\":3,\"resultMsg\":\"NODATA_ERROR\"}",
            "{\"resultCode\":\"3\",\"resultMsg\":\"NODATA_ERROR\"}",
            "{\"resultCode\":3,\"resultMsg\":\"NODATA_ERROR\"}",
            "{\"resultCode\":\"0\",\"resultMsg\":\"NORMAL_CODE\",\"numOfRows\":100,\"pageNo\":1,\"totalCount\":0,\"items\":[]}"})
    void observedEmptyResponsesAreSuccessfulEmptyResults(String body) {
        expect(body);
        assertThat(client.search(BOOK)).isEqualTo(new LibrarySearchResult(List.of(), 0, false));
        server.verify();
    }

    @Test
    void reportsTruncationWithoutRequestingAnotherPage() {
        expect(response("0", "NORMAL_CODE", 1, 100, 101, ROW));
        LibrarySearchResult result = client.search(BOOK);
        assertThat(result.totalCount()).isEqualTo(101);
        assertThat(result.truncated()).isTrue();
        assertThat(result.rows()).hasSize(1);
        server.verify();
    }

    @Test
    void optionalMissingBibliographicFieldsAndRoomRemainMissingForServiceFiltering() {
        expect(response("0", "NORMAL_CODE", 1, 100, 1, "{\"lib\":\"합성도서관\"}"));
        assertThat(client.search(BOOK).rows()).containsExactly(new LibrarySearchResult.Row(null, null, null, null, "합성도서관", null));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "not-json", "[]", "{}",
            "{\"resultCode\":99,\"resultMsg\":\"private-provider-error\"}",
            "{\"resultCode\":0,\"resultMsg\":\"NODATA_ERROR\"}",
            "{\"resultCode\":3,\"resultMsg\":\"NORMAL_CODE\"}",
            "{\"resultcode\":3,\"resultMsg\":\"NODATA_ERROR\",\"totalCount\":1}",
            "{\"resultcode\":3,\"resultMsg\":\"NODATA_ERROR\",\"items\":[{}]}",
            "{\"resultcode\":3,\"resultMsg\":\"NODATA_ERROR\",\"pageNo\":2}",
            "{\"resultCode\":0.0,\"resultMsg\":\"NORMAL_CODE\"}",
            "{\"resultCode\":true,\"resultMsg\":\"NORMAL_CODE\"}",
            "{\"resultCode\":0,\"resultcode\":3,\"resultMsg\":\"NORMAL_CODE\"}",
            "{\"resultCode\":0,\"resultMsg\":\"NORMAL_CODE\"}",
            "{\"resultCode\":0,\"resultMsg\":\"NORMAL_CODE\",\"pageNo\":2,\"numOfRows\":100,\"totalCount\":0,\"items\":[]}",
            "{\"resultCode\":0,\"resultMsg\":\"NORMAL_CODE\",\"pageNo\":1,\"numOfRows\":101,\"totalCount\":0,\"items\":[]}",
            "{\"resultCode\":0,\"resultMsg\":\"NORMAL_CODE\",\"pageNo\":1,\"numOfRows\":100,\"totalCount\":-1,\"items\":[]}",
            "{\"resultCode\":0,\"resultMsg\":\"NORMAL_CODE\",\"pageNo\":1,\"numOfRows\":100,\"totalCount\":0,\"items\":{}}",
            "{\"resultCode\":0,\"resultMsg\":\"NORMAL_CODE\",\"pageNo\":1,\"numOfRows\":100,\"totalCount\":1,\"items\":[null]}",
            "{\"resultCode\":0,\"resultMsg\":\"NORMAL_CODE\",\"pageNo\":1,\"numOfRows\":100,\"totalCount\":1,\"items\":[{\"bk_nm\":123}]}"})
    void malformedProviderResponsesAreUnavailableRatherThanEmpty(String body) {
        expect(body);
        assertUnavailable(() -> client.search(BOOK));
        server.verify();
    }

    @Test
    void rejectsMoreThanOneHundredRowsAndInconsistentTotal() {
        expect(response("0", "NORMAL_CODE", 1, 100, 101, String.join(",", java.util.Collections.nCopies(101, ROW))));
        assertUnavailable(() -> client.search(BOOK));
        server.verify();
    }

    @Test
    void rejectsRowsExceedingTotalCount() {
        expect(response("0", "NORMAL_CODE", 1, 100, 0, ROW));
        assertUnavailable(() -> client.search(BOOK));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 401, 403, 429, 500, 503})
    void sanitizesHttpFailuresWithoutFollowingRedirectOrRetrying(int status) {
        server.expect(requestTo(uri("synthetic-key", BOOK)))
                .andRespond(withStatus(HttpStatus.valueOf(status)).header("Location", "https://attacker.example")
                        .body("synthetic-key private-provider-error"));
        assertUnavailable(() -> client.search(BOOK));
        server.verify();
    }

    @Test
    void sanitizesTimeoutWithoutRetrying() {
        server.expect(requestTo(uri("synthetic-key", BOOK)))
                .andRespond(withException(new SocketTimeoutException("synthetic-key private-network-error")));
        assertUnavailable(() -> client.search(BOOK));
        server.verify();
    }

    @Test
    void missingKeyDisablesOnlyProviderRequestsAndPropertiesNeverPrintTheKey() {
        BookProperties properties = new BookProperties(null, null);
        assertThat(properties.configured()).isFalse();
        assertThat(properties.catalog(PLACE_ID)).isEmpty();
        assertUnavailable(() -> new YonginLibraryClient(properties, builder).search(BOOK));
        assertThat(new BookProperties("private-synthetic-secret", List.of()).toString())
                .doesNotContain("private-synthetic-secret");
        server.verify();
    }

    @Test
    void catalogPreservesOrderAndIsImmutable() {
        var books = new ArrayList<>(List.of(BOOK, new CuratedBook("다른 책", "저자", "출판사", "2024")));
        BookProperties properties = properties(PLACE_ID.toString(), books);
        books.clear();
        assertThat(properties.catalog(PLACE_ID)).containsExactly(BOOK, new CuratedBook("다른 책", "저자", "출판사", "2024"));
        assertThatThrownBy(() -> properties.catalog(PLACE_ID).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(properties.catalog(UUID.randomUUID())).isEmpty();
    }

    @Test
    void invalidCatalogDoesNotFailConstructionButFailsLookup() {
        List<BookProperties> invalid = List.of(
                properties("not-a-uuid", List.of(BOOK)),
                properties(PLACE_ID.toString(), List.of(new CuratedBook("title", "author", "pub", "not-a-year"))),
                properties(PLACE_ID.toString(), List.of(new CuratedBook("title", " ", "pub", "2024"))),
                properties(PLACE_ID.toString(), List.of(new CuratedBook("title", "author", "pub", "0000"))),
                properties(PLACE_ID.toString(), List.of(BOOK, BOOK)),
                properties(PLACE_ID.toString(), java.util.Collections.nCopies(4, BOOK)),
                new BookProperties("key", List.of(new BookProperties.Recommendation(PLACE_ID.toString(), List.of(BOOK)),
                        new BookProperties.Recommendation(PLACE_ID.toString(), List.of(BOOK)))));
        for (BookProperties properties : invalid) {
            assertThatThrownBy(() -> properties.catalog(PLACE_ID)).isInstanceOf(BookProperties.InvalidCatalogException.class)
                    .hasMessage("Book catalog is not configured").hasNoCause();
        }
    }

    @Test
    void malformedUuidAndYearBindWithoutBreakingApplicationStartup() {
        new ApplicationContextRunner().withUserConfiguration(BookConfiguration.class)
                .withPropertyValues("podo.books.recommendations[0].place-id=invalid",
                        "podo.books.recommendations[0].books[0].title=title",
                        "podo.books.recommendations[0].books[0].author=author",
                        "podo.books.recommendations[0].books[0].publisher=publisher",
                        "podo.books.recommendations[0].books[0].publication-year=invalid")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThatThrownBy(() -> context.getBean(BookProperties.class).catalog(PLACE_ID))
                            .isInstanceOf(BookProperties.InvalidCatalogException.class);
                });
    }

    private YonginLibraryClient client(String key) {
        return new YonginLibraryClient(new BookProperties(key, List.of()), builder);
    }

    private static BookProperties properties(String placeId, List<CuratedBook> books) {
        return new BookProperties("key", List.of(new BookProperties.Recommendation(placeId, books)));
    }

    private void expect(String body) {
        server.expect(requestTo(uri("synthetic-key", BOOK))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String uri(String key, CuratedBook book) {
        return UriComponentsBuilder.fromUriString(ENDPOINT).queryParam("serviceKey", "{key}")
                .queryParam("pageNo", 1).queryParam("numOfRows", 100).queryParam("bk_nm", "{title}")
                .queryParam("aut_nm", "{author}").queryParam("pblshr", "{publisher}").encode()
                .buildAndExpand(key, book.title(), book.author(), book.publisher()).toUriString();
    }

    private static String response(String code, String message, int page, int size, int total, String rows) {
        return "{\"resultCode\":" + code + ",\"resultMsg\":\"" + message + "\",\"pageNo\":" + page
                + ",\"numOfRows\":" + size + ",\"totalCount\":" + total + ",\"items\":[" + rows + "]}";
    }

    private static void assertUnavailable(Runnable operation) {
        var exception = assertThrows(YonginLibraryClient.ProviderUnavailableException.class, operation::run);
        assertThat(exception).hasMessage("Library provider is unavailable").hasNoCause();
        assertThat(exception.getSuppressed()).isEmpty();
    }
}
