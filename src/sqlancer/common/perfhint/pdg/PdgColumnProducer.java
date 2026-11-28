package sqlancer.common.perfhint.pdg;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The single, final owner of one PDG column's values. */
public final class PdgColumnProducer {

    public enum Kind {
        INDEPENDENT, INTRA_TABLE_DERIVED, INTER_TABLE_ENDPOINT
    }

    public enum ScalePolicy {
        SCALE_FREQ, SCALE_DOMAIN, SCALE_DEP, SCALE_OVERLAP, FIXED_DISTRIBUTION_EXTEND_SAMPLES, EXTEND_SEQUENCE,
        FIXED_MAPPING, FIXED_SHARED_DOMAIN_GROW_FREQUENCY, PRESERVE_FANOUT, EXTEND_PRIVATE_KEYS
    }

    private final Kind kind;
    private final String model;
    private final ScalePolicy scalePolicy;
    private final Map<String, Object> parameters;

    public PdgColumnProducer(Kind kind, String model, ScalePolicy scalePolicy, Map<String, Object> parameters) {
        this.kind = kind;
        this.model = model;
        this.scalePolicy = scalePolicy;
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    public Kind getKind() {
        return kind;
    }

    public String getModel() {
        return model;
    }

    public ScalePolicy getScalePolicy() {
        return scalePolicy;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }
}
