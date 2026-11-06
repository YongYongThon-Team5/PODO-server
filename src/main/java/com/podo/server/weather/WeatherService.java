package com.podo.server.weather;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/**
 * 장소 좌표·방문 날짜의 한국 시간 09~18시 날씨.
 * TODO: 기상청 단기예보 연동 전까지는 항상 '날씨 미확인'을 돌려준다. 맑음으로 간주하지 않는다.
 */
@Service
public class WeatherService {

    public Weather forecast(BigDecimal latitude, BigDecimal longitude, LocalDate targetDate) {
        return Weather.unknown(targetDate, "PROVIDER_FAILURE");
    }
}
