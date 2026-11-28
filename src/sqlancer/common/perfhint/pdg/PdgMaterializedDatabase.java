package sqlancer.common.perfhint.pdg;

import java.util.List;

public final class PdgMaterializedDatabase {

    private final List<List<List<Object>>> rows;
    private final List<PdgColumnProfile> profiles;
    private final PdgInMemoryIndex inMemoryIndex;

    public PdgMaterializedDatabase(List<List<List<Object>>> rows, List<PdgColumnProfile> profiles) {
        this(rows, profiles, null);
    }

    public PdgMaterializedDatabase(List<List<List<Object>>> rows, List<PdgColumnProfile> profiles,
            PdgInMemoryIndex inMemoryIndex) {
        this.rows = rows;
        this.profiles = List.copyOf(profiles);
        this.inMemoryIndex = inMemoryIndex;
    }

    public List<List<List<Object>>> getRows() {
        return rows;
    }

    public List<PdgColumnProfile> getProfiles() {
        return profiles;
    }

    public PdgInMemoryIndex getInMemoryIndex() {
        return inMemoryIndex;
    }
}
