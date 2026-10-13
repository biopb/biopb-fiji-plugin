package io.github.jiyuuchc.biopb.fiji;

import biopb.tensor.TensorFlightClient;
import biopb.tensor.TensorFlightClient.CatalogChildren;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import biopb.tensor.TensorOperationCancelledException;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStatusCode;
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
        CatalogChildren c;
        try {
            c = client.catalogChildren(key, limit);
        } catch (RuntimeException e) {
            throw oldServer(e);
        }
        List<CatalogChildren.Folder> fs;
        List<CatalogChildren.Source> ss;
        try { // the SDK's getters throw NPE for a list the server's JSON left out
            fs = c.getFolders();
            ss = c.getSources();
        } catch (NullPointerException missing) {
            return null;
        }
        List<Folder> folders = new ArrayList<>();
        for (CatalogChildren.Folder f : fs) {
            folders.add(new Folder(f.getName(), f.getKey()));
        }
        List<Source> sources = new ArrayList<>();
        for (CatalogChildren.Source s : ss) {
            sources.add(new Source(s.getName(), s.getSourceId()));
        }
        return new Level(c.getKey(), folders, sources, c.isTruncated());
    }

    /**
     * The exception {@link SourceList} reads as "no catalog_children here". The SDK
     * already turns a server's "Unknown action" into {@code UnsupportedOperationException};
     * a raw Flight UNIMPLEMENTED or "Unknown action catalog_children" error that
     * reaches us is translated too. Anything else passes through unchanged.
     */
    static RuntimeException oldServer(RuntimeException e) {
        if (e instanceof UnsupportedOperationException) {
            return e;
        }
        if (e instanceof FlightRuntimeException) {
            FlightRuntimeException f = (FlightRuntimeException) e;
            String message = String.valueOf(f.getMessage());
            if (f.status().code() == FlightStatusCode.UNIMPLEMENTED
                    || message.contains("Unknown action") && message.contains("catalog_children")) {
                return new UnsupportedOperationException("catalog_children is unavailable on this server", e);
            }
        }
        return e;
    }

    @Override
    public List<Map<String, Object>> query(String sql) throws IOException {
        try (VectorSchemaRoot root = client.query(sql)) {
            return rows(root);
        }
    }

    private static List<Map<String, Object>> rows(VectorSchemaRoot root) {
        List<Map<String, Object>> rows = new ArrayList<>(root.getRowCount());
        for (int i = 0; i < root.getRowCount(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (FieldVector v : root.getFieldVectors()) {
                row.put(v.getName(), plain(v.getObject(i)));
            }
            rows.add(row);
        }
        return rows;
    }

    @Override
    public Map<String, Object> resolveSource(String sourceId, Consumer<ResolveProgress> onProgress,
            BooleanSupplier shouldCancel) throws IOException {
        // The SDK hands back a root the caller must close; copy the row out and close it.
        try (VectorSchemaRoot root = client.resolveSource(sourceId,
                onProgress == null ? null
                        : p -> onProgress.accept(new ResolveProgress(p.getElapsedSeconds(), p.getTargetName(),
                                p.getTargetBytes())),
                shouldCancel)) {
            List<Map<String, Object>> rows = rows(root);
            if (rows.isEmpty()) {
                throw new IOException("resolve of '" + sourceId + "' returned no catalog row");
            }
            return rows.get(0);
        } catch (TensorOperationCancelledException e) {
            throw new ResolveCancelledException(sourceId, e);
        }
    }

    /** An immutable plain-Java copy of an Arrow value: {@code Text} as {@code String}, lists and structs copied. */
    static Object plain(Object value) {
        if (value instanceof Text) {
            return value.toString();
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object o : (List<?>) value) {
                copy.add(plain(o));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                copy.put(String.valueOf(e.getKey()), plain(e.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof byte[]) {
            return ((byte[]) value).clone();
        }
        return value;
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
