package dev.ccassistant.provider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Model picker search (docs/CLIENT_UI.md section 2.3): every space-separated word must appear (ignoring case) in the
 * id or name. Exact id matches first, then ids starting with the query, then the rest alphabetically.
 */
public final class ModelSearch {
    private ModelSearch() {
    }

    public static List<ModelInfo> filter(List<ModelInfo> models, String query, boolean toolsOnly) {
        var q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        var words = q.isEmpty() ? new String[0] : q.split("\\s+");
        var out = new ArrayList<ModelInfo>();
        for (var m : models) {
            if (toolsOnly && Boolean.FALSE.equals(m.tools())) continue;
            var hay = (m.id() + " " + (m.name() == null ? "" : m.name())).toLowerCase(Locale.ROOT);
            var all = true;
            for (var w : words) {
                if (!hay.contains(w)) {
                    all = false;
                    break;
                }
            }
            if (all) out.add(m);
        }
        out.sort(Comparator.comparingInt((ModelInfo m) -> rank(m, q)).thenComparing(m -> m.id().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static int rank(ModelInfo m, String q) {
        if (q.isEmpty()) return 2;
        var id = m.id().toLowerCase(Locale.ROOT);
        if (id.equals(q)) return 0;
        if (id.startsWith(q)) return 1;
        return 2;
    }
}
