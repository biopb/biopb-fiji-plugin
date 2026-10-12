package io.github.jiyuuchc.biopb.fiji;

import biopb.tensor.TensorFlightClient;
import biopb.tensor.TensorFlightClient.CatalogChildren;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.util.Text;

/** A {@link CatalogClient} over a {@link TensorFlightClient}. */
public final class FlightCatalogClient implements CatalogClient {
    private final TensorFlightClient client;

    /** Wraps {@code client}; the caller keeps ownership of it. */
    public FlightCatalogClient(TensorFlightClient client) {
        this.client = client;
    }

    @Override
    public Level catalogChildren(String key, int limit) throws IOException {
        CatalogChildren c = client.catalogChildren(key, limit);
        List<Folder> folders = new ArrayList<>();
        for (CatalogChildren.Folder f : c.getFolders()) {
            folders.add(new Folder(f.getName(), f.getKey()));
        }
        List<Source> sources = new ArrayList<>();
        for (CatalogChildren.Source s : c.getSources()) {
            sources.add(new Source(s.getName(), s.getSourceId()));
        }
        return new Level(c.getKey(), folders, sources, c.isTruncated());
    }

    @Override
    public List<Map<String, Object>> query(String sql) throws IOException {
        try (VectorSchemaRoot root = client.query(sql)) {
            List<Map<String, Object>> rows = new ArrayList<>(root.getRowCount());
            for (int i = 0; i < root.getRowCount(); i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (FieldVector v : root.getFieldVectors()) {
                    Object value = v.getObject(i);
                    row.put(v.getName(), value instanceof Text ? value.toString() : value);
                }
                rows.add(row);
            }
            return rows;
        }
    }

    @Override
    public Map<String, Object> healthCheck() throws IOException {
        return client.healthCheck();
    }

    @Override
    public String sourceRowColumns() {
        return client.sourceRowColumns();
    }
}
