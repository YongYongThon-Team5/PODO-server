package com.podo.server.observation.ai;

public final class ObservationTextLimits {

    public static final int INTRODUCTION = 60;
    public static final int QUESTION = 40;
    public static final int EXPLANATION = 80;
    public static final int HINT = 30;
    public static final int OBSERVATION_GUIDE = 30;
    public static final int PHOTO_EVIDENCE = 60;

    // 내부 출처 검증용 원문은 충분히 보관한다.
    public static final int SOURCE_QUOTE = 500;

    private ObservationTextLimits() {
    }
}