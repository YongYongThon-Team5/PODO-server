package com.podo.server.weather;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기상청 단기예보(getVilageFcst) 호출. 같은 격자·같은 발표 시각은 메모리에 한 번만 받아 둔다.
 * 인증키는 환경변수 KMA_SERVICE_KEY(공공데이터포털 인코딩 키)로 받는다.
 */
@Component
public class KmaWeatherClient {
    private static final String URL = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst";
    private static final int[] BASE_HOURS = {2, 5, 8, 11, 14, 17, 20, 23};
    private static final int PAGE_SIZE = 1000;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String serviceKey;
    private final RestClient rest;
    private final Map<String, Forecast> cache = new ConcurrentHashMap<>();

    public KmaWeatherClient(@Value("${podo.weather.kma-service-key:${KMA_SERVICE_KEY:}}") String serviceKey) {
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.rest = RestClient.builder().requestFactory(factory).build();
    }

    /** 발표 시각(한국 시간)과 시각별 예보값. hourly: 예보 시각 → (SKY, PTY …) → 값 */
    public record Forecast(LocalDateTime baseAt, Map<LocalDateTime, Map<String, String>> hourly) {
    }

    public boolean configured() {
        return !serviceKey.isEmpty();
    }

    public Forecast fetch(int nx, int ny, LocalDateTime nowKst) {
        LocalDateTime base = latestBase(nowKst);
        String suffix = ":" + base;
        String key = nx + ":" + ny + suffix;
        Forecast cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Forecast fresh = request(nx, ny, base);
        cache.keySet().removeIf(k -> !k.endsWith(suffix));
        cache.put(key, fresh);
        return fresh;
    }

    /** 02·05·08·11·14·17·20·23시 발표, 발표 10분 뒤부터 조회된다. */
    static LocalDateTime latestBase(LocalDateTime nowKst) {
        LocalDateTime t = nowKst.minusMinutes(10);
        for (int i = BASE_HOURS.length - 1; i >= 0; i--) {
            if (t.getHour() >= BASE_HOURS[i]) {
                return t.toLocalDate().atTime(BASE_HOURS[i], 0);
            }
        }
        return t.toLocalDate().minusDays(1).atTime(23, 0);
    }

    private Forecast request(int nx, int ny, LocalDateTime base) {
        Map<LocalDateTime, Map<String, String>> hourly = new HashMap<>();
        int page = 1;
        int total;
        do {
            URI uri = URI.create(URL + "?serviceKey=" + serviceKey + "&pageNo=" + page + "&numOfRows=" + PAGE_SIZE
                    + "&dataType=JSON&base_date=" + base.format(DATE) + "&base_time=" + base.format(TIME)
                    + "&nx=" + nx + "&ny=" + ny);
            String body = rest.get().uri(uri).retrieve().body(String.class);
            JsonNode response = JSON.readTree(body).path("response");
            String resultCode = response.path("header").path("resultCode").asString();
            if (!"00".equals(resultCode)) {
                throw new IllegalStateException("KMA resultCode " + resultCode);
            }
            JsonNode bodyNode = response.path("body");
            total = bodyNode.path("totalCount").asInt();
            JsonNode items = bodyNode.path("items").path("item");
            for (int i = 0; i < items.size(); i++) {
                JsonNode item = items.get(i);
                LocalDateTime at = LocalDateTime.parse(
                        item.path("fcstDate").asString() + item.path("fcstTime").asString(), STAMP);
                hourly.computeIfAbsent(at, k -> new HashMap<>())
                        .put(item.path("category").asString(), item.path("fcstValue").asString());
            }
            page++;
        } while ((long) (page - 1) * PAGE_SIZE < total && page <= 5);
        return new Forecast(base, hourly);
    }
}
