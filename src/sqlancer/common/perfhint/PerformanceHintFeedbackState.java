package sqlancer.common.perfhint;

public final class PerformanceHintFeedbackState {

    private PerformanceHintFeedbackState() {
    }

    public enum RootCauseNoveltyClass {
        NEW, RARE, SATURATED, UNKNOWN
    }

    public static RootCauseNoveltyClass classifyRootCauseNovelty(long observationCount, long hardCap) {
        if (observationCount <= 0 || hardCap <= 0) {
            return RootCauseNoveltyClass.UNKNOWN;
        }
        if (observationCount == 1) {
            return RootCauseNoveltyClass.NEW;
        }
        if (observationCount >= hardCap) {
            return RootCauseNoveltyClass.SATURATED;
        }
        return RootCauseNoveltyClass.RARE;
    }
}
