package sqlancer.common.perfhint.pdg;

import java.util.List;

/** Per-database PDG state. YAML is constructed only if a case is persisted. */
public final class PdgIrContext {

    private final PdgSpecification specification;
    private final List<PdgColumnProfile> profiles;
    private final PdgInMemoryIndex inMemoryIndex;
    private volatile String cachedYaml;

    public PdgIrContext(PdgSpecification specification, List<PdgColumnProfile> profiles) {
        this(specification, profiles, null);
    }

    public PdgIrContext(PdgSpecification specification, List<PdgColumnProfile> profiles,
            PdgInMemoryIndex inMemoryIndex) {
        this.specification = specification;
        this.profiles = List.copyOf(profiles);
        this.inMemoryIndex = inMemoryIndex;
    }

    public PdgSpecification getSpecification() {
        return specification;
    }

    public List<PdgColumnProfile> getProfiles() {
        return profiles;
    }

    public PdgInMemoryIndex getInMemoryIndex() {
        return inMemoryIndex;
    }

    public boolean isSerialized() {
        return cachedYaml != null;
    }

    public String yaml() {
        String yaml = cachedYaml;
        if (yaml == null) {
            synchronized (this) {
                yaml = cachedYaml;
                if (yaml == null) {
                    yaml = PdgIrYamlSerializer.serialize(specification, profiles);
                    cachedYaml = yaml;
                }
            }
        }
        return yaml;
    }
}
