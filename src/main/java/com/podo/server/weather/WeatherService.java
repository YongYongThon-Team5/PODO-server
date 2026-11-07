package com.podo.server.weather;

import com.podo.server.place.domain.District;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 장소 지역·방문 날짜의 한국 시간 09~18시 날씨.
 * 비(PTY≠0)가 한 시간이라도 있으면 RAIN, 아니면 흐림(SKY=4)이 절반 이상이면 CLOUDY, 그 외 CLEAR.
 * 예보가 없거나 조회에 실패하면 UNKNOWN이며 맑음으로 간주하지 않는다.
 */
@Service
public class WeatherService {
    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final KmaWeatherClient kma;

    public WeatherService(KmaWeatherClient kma) {
        this.kma = kma;
    }

    public Weather forecast(String districtCode, BigDecimal latitude, BigDecimal longitude, LocalDate targetDate) {
        District district = District.fromCode(districtCode);
        double lat;
        double lon;
        if (district != null) {
            lat = district.latitude();
            lon = district.longitude();
        } else if (latitude != null && longitude != null) {
            lat = latitude.doubleValue();
            lon = longitude.doubleValue();
        } else {
            return Weather.unknown(targetDate, "LOCATION_UNAVAILABLE");
        }
        if (!kma.configured()) {
            return Weather.unknown(targetDate, "PROVIDER_FAILURE");
        }
        LocalDateTime now = LocalDateTime.now(Weather.SEOUL);
        if (targetDate.isBefore(now.toLocalDate())) {
            return Weather.unknown(targetDate, "OUT_OF_FORECAST_RANGE");
        }
        GridConverter.Grid grid = GridConverter.toGrid(lat, lon);
        KmaWeatherClient.Forecast forecast;
        try {
            forecast = kma.fetch(grid.nx(), grid.ny(), now);
        } catch (RuntimeException ex) {
            log.warn("기상청 예보 조회 실패 nx={} ny={}: {}", grid.nx(), grid.ny(), ex.getMessage());
            return Weather.unknown(targetDate, "PROVIDER_FAILURE");
        }
        return judge(targetDate, forecast);
    }

    static Weather judge(LocalDate targetDate, KmaWeatherClient.Forecast forecast) {
        List<Map<String, String>> hours = new ArrayList<>();
        for (int hour = 9; hour <= 18; hour++) {
            Map<String, String> values = forecast.hourly().get(targetDate.atTime(hour, 0));
            if (values != null) {
                hours.add(values);
            }
        }
        if (hours.isEmpty()) {
            return Weather.unknown(targetDate, "OUT_OF_FORECAST_RANGE");
        }
        boolean rain = hours.stream().anyMatch(v -> v.containsKey("PTY") && !"0".equals(v.get("PTY")));
        long cloudyHours = hours.stream().filter(v -> "4".equals(v.get("SKY"))).count();
        String condition = rain ? "RAIN" : cloudyHours * 2 >= hours.size() ? "CLOUDY" : "CLEAR";
        OffsetDateTime forecastAt = forecast.baseAt().atZone(Weather.SEOUL).toOffsetDateTime();
        return new Weather(targetDate, condition, null, forecastAt, OffsetDateTime.now(Weather.SEOUL));
    }
}
