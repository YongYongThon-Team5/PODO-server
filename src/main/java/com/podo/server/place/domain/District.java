package com.podo.server.place.domain;

/** 용인시 구. 날씨는 구청 좌표의 예보 격자로 조회한다. */
public enum District {
    CHEOIN("처인구", 37.2343, 127.2016),
    GIHEUNG("기흥구", 37.2803, 127.1147),
    SUJI("수지구", 37.3222, 127.0975);

    private final String label;
    private final double latitude;
    private final double longitude;

    District(String label, double latitude, double longitude) {
        this.label = label;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String code() {
        return name();
    }

    public String label() {
        return label;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    public static District fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (District district : values()) {
            if (district.name().equals(code)) {
                return district;
            }
        }
        return null;
    }
}
