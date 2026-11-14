package com.podo.server.books;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class YonginLibraryClient {
    private static final String ENDPOINT = "https://apis.data.go.kr/4050000/libnewbk/getLibnewbk";
    private static final int PAGE_SIZE = 100;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final BookProperties properties;
    private final RestClient restClient;

    @Autowired
    public YonginLibraryClient(BookProperties properties) {
        this(properties, restClientBuilder());
    }

    YonginLibraryClient(BookProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    public LibrarySearchResult search(CuratedBook book) {
        if (!properties.configured() || book == null) {
            throw new ProviderUnavailableException();
        }
        try {
            // Portal keys can already contain percent escapes; decode once while preserving literal '+'.
            String key = UriUtils.decode(properties.apiKey().trim().replaceAll("%(?![0-9a-fA-F]{2})", "%25"),
                    StandardCharsets.UTF_8);
            URI uri = UriComponentsBuilder.fromUriString(ENDPOINT).queryParam("serviceKey", "{key}")
                    .queryParam("pageNo", 1).queryParam("numOfRows", PAGE_SIZE)
                    .queryParam("bk_nm", "{title}").queryParam("aut_nm", "{author}")
                    .queryParam("pblshr", "{publisher}").encode()
                    .buildAndExpand(key, book.title(), book.author(), book.publisher()).toUri();
            String body = restClient.get().uri(uri).accept(MediaType.APPLICATION_JSON).retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        throw new ProviderUnavailableException();
                    }).body(String.class);
            return parse(body);
        } catch (RuntimeException exception) {
            // Never propagate provider bodies, authenticated URLs, transport messages or causes.
            throw new ProviderUnavailableException();
        }
    }

    private static LibrarySearchResult parse(String body) {
        if (body == null) {
            throw new ProviderUnavailableException();
        }
        JsonNode response = JSON.readTree(body);
        if (response == null || !response.isObject()
                || (response.has("resultCode") && response.has("resultcode"))) {
            throw new ProviderUnavailableException();
        }
        JsonNode code = response.has("resultCode") ? response.path("resultCode") : response.path("resultcode");
        String value = code.isString() ? code.stringValue()
                : code.isIntegralNumber() && code.canConvertToLong() ? Long.toString(code.longValue()) : "";
        String message = text(response, "resultMsg");
        if ("3".equals(value) && "NODATA_ERROR".equals(message)) {
            if ((response.has("totalCount") && number(response, "totalCount") != 0)
                    || (response.has("pageNo") && number(response, "pageNo") != 1)
                    || (response.has("numOfRows") && number(response, "numOfRows") != PAGE_SIZE)
                    || (response.has("items") && (!response.path("items").isArray() || !response.path("items").isEmpty()))) {
                throw new ProviderUnavailableException();
            }
            return new LibrarySearchResult(List.of(), 0, false);
        }
        if (!"0".equals(value) || !"NORMAL_CODE".equals(message)
                || number(response, "pageNo") != 1 || number(response, "numOfRows") != PAGE_SIZE) {
            throw new ProviderUnavailableException();
        }
        long total = number(response, "totalCount");
        JsonNode items = response.path("items");
        if (!items.isArray() || items.size() > PAGE_SIZE || items.size() > total) {
            throw new ProviderUnavailableException();
        }
        List<LibrarySearchResult.Row> rows = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.isObject()) {
                throw new ProviderUnavailableException();
            }
            rows.add(new LibrarySearchResult.Row(text(item, "bk_nm"), text(item, "aut_nm"), text(item, "pblshr"),
                    text(item, "pblcn_yr"), text(item, "lib"), text(item, "refrm")));
        }
        return new LibrarySearchResult(rows, total, total > PAGE_SIZE);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new ProviderUnavailableException();
        }
        return value.stringValue();
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new ProviderUnavailableException();
        }
        return value.longValue();
    }

    private static RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(requestFactory);
    }

    public static final class ProviderUnavailableException extends RuntimeException {
        public ProviderUnavailableException() {
            super("Library provider is unavailable", null, false, true);
        }
    }
}
