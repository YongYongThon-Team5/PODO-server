package com.podo.server.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 날씨 판정 규칙(방문일 한국 시간 09~18시).
 * 비(PTY≠0) 한 시간이라도 있으면 RAIN, 아니면 흐림(SKY=4)이 절반 이상이면 CLOUDY, 그 외 CLEAR.
 */
@DisplayName("WeatherService 날씨 판정")
class WeatherServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 10);
    private static final LocalDateTime BASE_AT = LocalDateTime.of(2026, 11, 9, 5, 0);

    /** 09~18시 10시간을 SKY=1(맑음), PTY=0(강수 없음)으로 채운 예보. */
    private static Map<LocalDateTime, Map<String, String>> clearDay(LocalDate date) {
        Map<LocalDateTime, Map<String, String>> hourly = new HashMap<>();
        for (int hour = 0; hour <= 23; hour++) {
            hourly.put(date.atTime(hour, 0), new HashMap<>(Map.of("SKY", "1", "PTY", "0")));
        }
        return hourly;
    }

    private static void set(Map<LocalDateTime, Map<String, String>> hourly, LocalDate date, int hour,
                            String category, String value) {
        hourly.get(date.atTime(hour, 0)).put(category, value);
    }

    private static KmaWeatherClient.Forecast forecast(Map<LocalDateTime, Map<String, String>> hourly) {
        return new KmaWeatherClient.Forecast(BASE_AT, hourly);
    }

    @Nested
    @DisplayName("예보로 날씨 판정")
    class Judge {

        @Test
        @DisplayName("모든 시간이 맑으면 CLEAR")
        void clearAllDay() {
            Weather w = WeatherService.judge(DATE, forecast(clearDay(DATE)));

            assertThat(w.condition()).isEqualTo("CLEAR");
            assertThat(w.unavailableReason()).isNull();
            assertThat(w.targetDate()).isEqualTo(DATE);
        }

        @Test
        @DisplayName("방문 시간대에 한 시간이라도 비가 오면 RAIN")
        void rainInAnyVisitHour() {
            Map<LocalDateTime, Map<String, String>> hourly = clearDay(DATE);
            set(hourly, DATE, 13, "PTY", "1");

            assertThat(WeatherService.judge(DATE, forecast(hourly)).condition()).isEqualTo("RAIN");
        }

        @Test
        @DisplayName("눈이나 소나기처럼 PTY가 0이 아니면 모두 RAIN")
        void anyNonZeroPtyIsRain() {
            for (String pty : new String[]{"2", "3", "4"}) {
                Map<LocalDateTime, Map<String, String>> hourly = clearDay(DATE);
                set(hourly, DATE, 18, "PTY", pty);

                assertThat(WeatherService.judge(DATE, forecast(hourly)).condition())
                        .as("PTY=%s", pty).isEqualTo("RAIN");
            }
        }

        @Test
        @DisplayName("방문 시간대 밖의 비는 무시한다")
        void ignoresRainOutsideVisitHours() {
            Map<LocalDateTime, Map<String, String>> hourly = clearDay(DATE);
            set(hourly, DATE, 8, "PTY", "1");
            set(hourly, DATE, 19, "PTY", "1");

            assertThat(WeatherService.judge(DATE, forecast(hourly)).condition()).isEqualTo("CLEAR");
        }

        @Test
        @DisplayName("흐림이 정확히 절반이면 CLOUDY")
        void cloudyWhenExactlyHalfOvercast() {
            Map<LocalDateTime, Map<String, String>> hourly = clearDay(DATE);
            for (int hour = 9; hour <= 13; hour++) {
                set(hourly, DATE, hour, "SKY", "4");
            }

            assertThat(WeatherService.judge(DATE, forecast(hourly)).condition()).isEqualTo("CLOUDY");
        }

        @Test
        @DisplayName("흐림이 절반보다 적으면 CLEAR")
        void clearWhenLessThanHalfOvercast() {
            Map<LocalDateTime, Map<String, String>> hourly = clearDay(DATE);
            for (int hour = 9; hour <= 12; hour++) {
                set(hourly, DATE, hour, "SKY", "4");
            }

            assertThat(WeatherService.judge(DATE, forecast(hourly)).condition()).isEqualTo("CLEAR");
        }

        @Test
        @DisplayName("구름많음 SKY3은 흐림으로 세지 않는다")
        void mostlyCloudySky3IsNotOvercast() {
            Map<LocalDateTime, Map<String, String>> hourly = clearDay(DATE);
            for (int hour = 9; hour <= 18; hour++) {
                set(hourly, DATE, hour, "SKY", "3");
            }

            assertThat(WeatherService.judge(DATE, forecast(hourly)).condition()).isEqualTo("CLEAR");
        }

        @Test
        @DisplayName("흐리면서 비가 오면 RAIN이 우선")
        void rainWinsOverCloudy() {
            Map<LocalDateTime, Map<String, String>> hourly = clearDay(DATE);
            for (int hour = 9; hour <= 18; hour++) {
                set(hourly, DATE, hour, "SKY", "4");
            }
            set(hourly, DATE, 15, "PTY", "1");

            assertThat(WeatherService.judge(DATE, forecast(hourly)).condition()).isEqualTo("RAIN");
        }

        @Test
        @DisplayName("방문일 예보가 없으면 UNKNOWN OUT_OF_FORECAST_RANGE")
        void noForecastForDateIsOutOfRange() {
            Weather w = WeatherService.judge(DATE.plusDays(5), forecast(clearDay(DATE)));

            assertThat(w.condition()).isEqualTo("UNKNOWN");
            assertThat(w.unavailableReason()).isEqualTo("OUT_OF_FORECAST_RANGE");
            assertThat(w.forecastAt()).isNull();
        }

        @Test
        @DisplayName("예보 시각은 발표 시각을 한국 시간으로 표시한다")
        void forecastAtIsBaseTimeInKst() {
            Weather w = WeatherService.judge(DATE, forecast(clearDay(DATE)));

            assertThat(w.forecastAt()).isEqualTo(OffsetDateTime.of(BASE_AT, ZoneOffset.ofHours(9)));
        }
    }

    @Nested
    @DisplayName("기상청 조회와 실패 처리")
    class Forecast {

        private final KmaWeatherClient kma = mock(KmaWeatherClient.class);
        private final WeatherService service = new WeatherService(kma);
        private final LocalDate tomorrow = LocalDate.now(Weather.SEOUL).plusDays(1);

        @Test
        @DisplayName("키가 없으면 기상청을 부르지 않고 PROVIDER_FAILURE")
        void noKeyIsProviderFailureWithoutCall() {
            when(kma.configured()).thenReturn(false);

            Weather w = service.forecast("CHEOIN", null, null, tomorrow);

            assertThat(w.condition()).isEqualTo("UNKNOWN");
            assertThat(w.unavailableReason()).isEqualTo("PROVIDER_FAILURE");
            verify(kma, never()).fetch(anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("지난 날짜는 기상청을 부르지 않고 OUT_OF_FORECAST_RANGE")
        void pastDateIsOutOfRangeWithoutCall() {
            when(kma.configured()).thenReturn(true);

            Weather w = service.forecast("CHEOIN", null, null, LocalDate.now(Weather.SEOUL).minusDays(1));

            assertThat(w.condition()).isEqualTo("UNKNOWN");
            assertThat(w.unavailableReason()).isEqualTo("OUT_OF_FORECAST_RANGE");
            verify(kma, never()).fetch(anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("기상청 호출이 실패하면 PROVIDER_FAILURE")
        void kmaErrorIsProviderFailure() {
            when(kma.configured()).thenReturn(true);
            when(kma.fetch(anyInt(), anyInt(), any())).thenThrow(new IllegalStateException("KMA resultCode 03"));

            Weather w = service.forecast("GIHEUNG", null, null, tomorrow);

            assertThat(w.condition()).isEqualTo("UNKNOWN");
            assertThat(w.unavailableReason()).isEqualTo("PROVIDER_FAILURE");
        }

        @Test
        @DisplayName("지역도 좌표도 없으면 LOCATION_UNAVAILABLE")
        void noDistrictNoCoordinatesIsLocationUnavailable() {
            Weather w = service.forecast(null, null, null, tomorrow);

            assertThat(w.condition()).isEqualTo("UNKNOWN");
            assertThat(w.unavailableReason()).isEqualTo("LOCATION_UNAVAILABLE");
            verify(kma, never()).fetch(anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("지역이 있으면 구청 좌표의 격자로 조회한다")
        void districtUsesDistrictOfficeGrid() {
            when(kma.configured()).thenReturn(true);
            when(kma.fetch(anyInt(), anyInt(), any())).thenReturn(forecast(clearDay(tomorrow)));

            service.forecast("CHEOIN", new BigDecimal("37.1"), new BigDecimal("127.3"), tomorrow);
            service.forecast("GIHEUNG", null, null, tomorrow);
            service.forecast("SUJI", null, null, tomorrow);

            // 장소 좌표(37.1, 127.3)가 아니라 처인구청 격자로 조회한다. 기흥·수지 구청은 같은 격자다.
            verify(kma).fetch(eq(64), eq(120), any());
            verify(kma, times(2)).fetch(eq(62), eq(121), any());
        }

        @Test
        @DisplayName("지역이 없으면 장소 좌표로 조회한다")
        void noDistrictUsesPlaceCoordinates() {
            when(kma.configured()).thenReturn(true);
            when(kma.fetch(anyInt(), anyInt(), any())).thenReturn(forecast(clearDay(tomorrow)));

            Weather w = service.forecast(null, new BigDecimal("37.2343"), new BigDecimal("127.2016"), tomorrow);

            assertThat(w.condition()).isEqualTo("CLEAR");
            verify(kma).fetch(eq(64), eq(120), any());
        }

        @Test
        @DisplayName("조회에 성공하면 예보로 판정한다")
        void judgesFetchedForecast() {
            Map<LocalDateTime, Map<String, String>> hourly = clearDay(tomorrow);
            set(hourly, tomorrow, 10, "PTY", "1");
            when(kma.configured()).thenReturn(true);
            when(kma.fetch(anyInt(), anyInt(), any())).thenReturn(forecast(hourly));

            Weather w = service.forecast("SUJI", null, null, tomorrow);

            assertThat(w.condition()).isEqualTo("RAIN");
            assertThat(w.unavailableReason()).isNull();
        }
    }
}
