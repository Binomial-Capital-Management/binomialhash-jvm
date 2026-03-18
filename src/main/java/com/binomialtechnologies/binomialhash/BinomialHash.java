package com.binomialtechnologies.binomialhash;

import com.binomialtechnologies.binomialhash.extract.NestingAnalyzer;
import com.binomialtechnologies.binomialhash.extract.RowExtractor;
import com.binomialtechnologies.binomialhash.predicates.PredicateBuilder;
import com.binomialtechnologies.binomialhash.predicates.RowSorter;
import com.binomialtechnologies.binomialhash.schema.SchemaInference;
import com.binomialtechnologies.binomialhash.stats.StatsHelpers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Content-addressed, schema-aware in-memory data structure.
 *
 * <p>Intercepts large MCP/tool outputs, infers schema + stats, deduplicates by
 * content fingerprint, and returns a compact summary for the LLM.</p>
 */
public final class BinomialHash {

    private static final Logger LOG = Logger.getLogger(BinomialHash.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final int INGEST_THRESHOLD_CHARS = 3000;
    public static final int MAX_PREVIEW_ROWS = 3;
    public static final int MAX_RETRIEVE_ROWS = 50;
    public static final int MAX_SLOTS = 50;
    public static final long BUDGET_BYTES = 50L * 1024 * 1024;

    private static final Set<String> ALL_AGG_FUNCS;
    private static final Set<String> NUMERIC_FUNCS = StatsHelpers.NUMERIC_FUNCS;
    static {
        var s = new HashSet<>(NUMERIC_FUNCS);
        s.add("count"); s.add("count_distinct");
        ALL_AGG_FUNCS = Collections.unmodifiableSet(s);
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, BinomialHashSlot> slots = new LinkedHashMap<>();
    private final Map<String, String> fingerprints = new HashMap<>();
    private long usedBytes;
    private long ctxCharsIn;
    private long ctxCharsOut;
    private int ctxToolCalls;
    private final BinomialHashPolicy policy;

    public BinomialHash() {
        this(BinomialHashPolicy.DEFAULT);
    }

    public BinomialHash(BinomialHashPolicy policy) {
        this.policy = policy;
    }

    // ── Key / fingerprint helpers ──────────────────────────────────────

    static String makeKey(String label, String fp, int prefixLength) {
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < Math.min(label.length(), prefixLength); i++) {
            char c = label.charAt(i);
            clean.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : '_');
        }
        String trimmed = clean.toString().replaceAll("^_+|_+$", "");
        return trimmed + "_" + fp.substring(0, Math.min(6, fp.length()));
    }

    static String fingerprint(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static long estimateBytes(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return 0;
        long sample = 0;
        int cap = Math.min(rows.size(), 100);
        for (int i = 0; i < cap; i++) sample += rows.get(i).size() * 80L;
        return sample * Math.max(1, rows.size() / cap);
    }

    // ── Eviction ───────────────────────────────────────────────────────

    private void evictIfNeeded(long needed) {
        while (usedBytes + needed > BUDGET_BYTES && !slots.isEmpty()) {
            String victim = null; long minScore = Long.MAX_VALUE;
            for (var e : slots.entrySet()) {
                long score = (long) e.getValue().accessCount() * 1_000_000_000L + e.getValue().createdAt();
                if (score < minScore) { minScore = score; victim = e.getKey(); }
            }
            if (victim == null) break;
            BinomialHashSlot v = slots.remove(victim);
            fingerprints.remove(v.fingerprint());
            usedBytes -= v.byteSize();
            LOG.info("[BH] evicted '" + victim + "' (" + v.byteSize() + " bytes)");
        }
    }

    private BinomialHashSlot getSlot(String key) {
        BinomialHashSlot slot = slots.get(key);
        if (slot != null) slot.incrementAccess();
        return slot;
    }

    // ── Public API ─────────────────────────────────────────────────────

    public List<Map<String, Object>> keys() {
        lock.lock();
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            for (var s : slots.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", s.key()); m.put("label", s.label());
                m.put("row_count", s.rowCount());
                m.put("columns", s.columns().subList(0, Math.min(s.columns().size(), policy.keysPreviewColumnCount)));
                result.add(m);
            }
            return result;
        } finally { lock.unlock(); }
    }

    private void track(long charsIn, long charsOut) {
        ctxCharsIn += charsIn;
        ctxCharsOut += charsOut;
        ctxToolCalls++;
    }

    public Map<String, Object> contextStats() {
        lock.lock();
        try {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("tool_calls", ctxToolCalls);
            s.put("chars_in_raw", ctxCharsIn);
            s.put("chars_out_to_llm", ctxCharsOut);
            s.put("compression_ratio", Math.round(10.0 * ctxCharsIn / Math.max(ctxCharsOut, 1)) / 10.0);
            s.put("est_tokens_out", ctxCharsOut / 4);
            s.put("slots", slots.size());
            s.put("mem_bytes", usedBytes);
            return s;
        } finally { lock.unlock(); }
    }

    // ── Ingest ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public String ingest(String rawText, String label) {
        long rawLen = rawText.length();

        if (rawLen <= INGEST_THRESHOLD_CHARS) {
            lock.lock(); try { track(rawLen, rawLen); } finally { lock.unlock(); }
            return rawText;
        }

        Object data;
        try { data = MAPPER.readValue(rawText, Object.class); }
        catch (Exception e) {
            String out = rawText.substring(0, Math.min(rawText.length(), INGEST_THRESHOLD_CHARS)) + "\n... [truncated]";
            lock.lock(); try { track(rawLen, out.length()); } finally { lock.unlock(); }
            return out;
        }

        NestingProfile nesting = NestingAnalyzer.analyzeNesting(data);
        var extraction = RowExtractor.extractRows(data);
        List<Map<String, Object>> rows = extraction.rows();
        if (rows.size() < 3) {
            String out = rawText.substring(0, Math.min(rawText.length(), INGEST_THRESHOLD_CHARS)) + "\n... [truncated]";
            lock.lock(); try { track(rawLen, out.length()); } finally { lock.unlock(); }
            return out;
        }

        String fp = fingerprint(rawText);
        lock.lock();
        try {
            if (fingerprints.containsKey(fp)) {
                BinomialHashSlot existing = slots.get(fingerprints.get(fp));
                existing.incrementAccess();
                String summary = buildSummary(existing);
                track(rawLen, summary.length());
                return summary;
            }

            if (slots.size() >= MAX_SLOTS) {
                evictIfNeeded(0);
                if (slots.size() >= MAX_SLOTS) {
                    String out = rawText.substring(0, INGEST_THRESHOLD_CHARS) + "\n... [truncated, store full]";
                    track(rawLen, out.length());
                    return out;
                }
            }

            Set<String> seen = new LinkedHashSet<>();
            int scanRows = Math.min(rows.size(), policy.ingestKeyScanRowCount);
            for (int i = 0; i < scanRows; i++) for (String k : rows.get(i).keySet()) seen.add(k);
            List<String> columns = new ArrayList<>(seen);
            if (columns.size() > policy.ingestMaxColumnCount)
                columns = columns.subList(0, policy.ingestMaxColumnCount);

            var inferred = SchemaInference.inferSchema(rows, columns, StatsHelpers::toFloatPermissive);
            long byteSize = estimateBytes(rows);
            if (byteSize > BUDGET_BYTES) {
                String out = rawText.substring(0, INGEST_THRESHOLD_CHARS) + "\n... [payload too large to cache]";
                track(rawLen, out.length());
                return out;
            }
            evictIfNeeded(byteSize);
            String key = makeKey(label, fp, policy.keyLabelPrefixLength);

            BinomialHashSlot slot = new BinomialHashSlot(
                    key, label, fp, rows, columns,
                    inferred.colTypes(), inferred.colStats(),
                    rows.size(), byteSize, nesting, null);
            slots.put(key, slot);
            fingerprints.put(fp, key);
            usedBytes += byteSize;
            String summary = buildSummary(slot);
            track(rawLen, summary.length());
            LOG.info(String.format("[BH-perf] ingest '%s' → '%s' | %d rows %d cols | %.0fx compression",
                    label, key, rows.size(), columns.size(), (double) rawLen / Math.max(summary.length(), 1)));
            return summary;
        } finally { lock.unlock(); }
    }

    private String buildSummary(BinomialHashSlot slot) {
        List<String> parts = new ArrayList<>();
        int previewCols = Math.min(slot.columns().size(), policy.summaryPreviewColumnCount);
        for (int i = 0; i < previewCols; i++) {
            String col = slot.columns().get(i);
            String ct = slot.colTypes().getOrDefault(col, "?");
            Map<String, Object> st = slot.colStats().getOrDefault(col, Map.of());
            String detail = "";
            if ("numeric".equals(ct) && st.containsKey("min"))
                detail = ", " + fmtNum(st.get("min")) + ".." + fmtNum(st.get("max"));
            else if ("string".equals(ct) && st.containsKey("unique_count"))
                detail = ", " + st.get("unique_count") + " unique";
            else if ("date".equals(ct) && st.containsKey("min_date"))
                detail = ", " + String.valueOf(st.get("min_date")).substring(0, Math.min(10, String.valueOf(st.get("min_date")).length()))
                        + ".." + String.valueOf(st.get("max_date")).substring(0, Math.min(10, String.valueOf(st.get("max_date")).length()));
            parts.add(col + "(" + ct.substring(0, Math.min(3, ct.length())) + detail + ")");
        }
        String schemaLine = String.join(", ", parts);
        if (slot.columns().size() > policy.summaryPreviewColumnCount)
            schemaLine += " +" + (slot.columns().size() - policy.summaryPreviewColumnCount) + " more";
        String preview;
        try {
            preview = MAPPER.writeValueAsString(slot.rows().subList(0, Math.min(MAX_PREVIEW_ROWS, slot.rows().size())));
        } catch (JsonProcessingException e) { preview = "[]"; }
        if (preview.length() > policy.summaryPreviewCharLimit)
            preview = preview.substring(0, policy.summaryPreviewCharLimit) + "...]";
        return "[BH] key=\"" + slot.key() + "\" | " + slot.rowCount() + " records | " + slot.label() + "\n"
                + "Schema: " + schemaLine + "\n"
                + "Preview: " + preview + "\n"
                + "Use bh_retrieve/bh_query/bh_aggregate/bh_group_by/bh_to_excel. Do NOT re-fetch.";
    }

    // ── Retrieval ──────────────────────────────────────────────────────

    public Map<String, Object> retrieve(String key, int offset, int limit,
                                        String sortBy, boolean sortDesc, List<String> columns) {
        lock.lock();
        try {
            BinomialHashSlot slot = getSlot(key);
            if (slot == null) return Map.of("error", "Key '" + key + "' not found. Available: " + slots.keySet());
            List<Map<String, Object>> rows = slot.rows();
            if (sortBy != null && slot.colTypes().containsKey(sortBy))
                rows = RowSorter.sortRows(rows, sortBy, slot.colTypes().get(sortBy), sortDesc);
            int eff = Math.min(limit, MAX_RETRIEVE_ROWS);
            List<Map<String, Object>> sliced = rows.subList(offset, Math.min(offset + eff, rows.size()));
            if (columns != null && !columns.isEmpty()) sliced = projectColumns(sliced, columns);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", key); result.put("label", slot.label()); result.put("total_rows", slot.rowCount());
            result.put("offset", offset); result.put("returned", sliced.size()); result.put("rows", sliced);
            track(0, jsonLen(result));
            return result;
        } finally { lock.unlock(); }
    }

    public Map<String, Object> retrieve(String key) {
        return retrieve(key, 0, 25, null, true, null);
    }

    // ── Aggregate ──────────────────────────────────────────────────────

    public Map<String, Object> aggregate(String key, String column, String func) {
        lock.lock();
        try {
            BinomialHashSlot slot = getSlot(key);
            if (slot == null) return Map.of("error", "Key '" + key + "' not found.");
            if (!slot.colTypes().containsKey(column))
                return Map.of("error", "Column '" + column + "' not found.");
            if (!ALL_AGG_FUNCS.contains(func))
                return Map.of("error", "Unknown func '" + func + "'. Use: " + ALL_AGG_FUNCS);
            Object val = StatsHelpers.runAgg(slot.rows(), column, func);
            Map<String, Object> out = Map.of("key", key, "column", column, "func", func, "result", val == null ? "null" : val);
            track(0, jsonLen(out));
            return out;
        } finally { lock.unlock(); }
    }

    // ── Query ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> query(String key, String whereJson, String sortBy,
                                     boolean sortDesc, int limit, List<String> columns) {
        lock.lock();
        try {
            BinomialHashSlot slot = getSlot(key);
            if (slot == null) return Map.of("error", "Key '" + key + "' not found.");
            Map<String, Object> where;
            try { where = MAPPER.readValue(whereJson, Map.class); }
            catch (Exception e) { return Map.of("error", "Invalid where_json: " + whereJson.substring(0, Math.min(100, whereJson.length()))); }
            Predicate<Map<String, Object>> pred = PredicateBuilder.buildPredicate(where, slot.colTypes());
            if (pred == null) return Map.of("error", "Invalid where clause.");
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (var r : slot.rows()) if (pred.test(r)) filtered.add(r);
            List<Map<String, Object>> sliced = RowSorter.applySortSliceProject(
                    filtered, slot, sortBy, sortDesc, limit, columns, MAX_RETRIEVE_ROWS);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", key); result.put("label", slot.label()); result.put("total_rows", slot.rowCount());
            result.put("matched", filtered.size()); result.put("returned", sliced.size()); result.put("rows", sliced);
            track(0, jsonLen(result));
            return result;
        } finally { lock.unlock(); }
    }

    // ── Group By ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> groupBy(String key, List<String> groupCols, String aggJson,
                                       String sortBy, boolean sortDesc, int limit) {
        lock.lock();
        try {
            BinomialHashSlot slot = getSlot(key);
            if (slot == null) return Map.of("error", "Key '" + key + "' not found.");
            for (String gc : groupCols)
                if (!slot.colTypes().containsKey(gc))
                    return Map.of("error", "Group column '" + gc + "' not found.");
            List<Map<String, Object>> aggs;
            try { aggs = MAPPER.readValue(aggJson, List.class); }
            catch (Exception e) { return Map.of("error", "Invalid agg_json."); }
            if (aggs == null || aggs.isEmpty()) return Map.of("error", "agg_json must be non-empty.");

            Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (var row : slot.rows()) {
                StringJoiner gk = new StringJoiner("|");
                for (String gc : groupCols) gk.add(String.valueOf(row.getOrDefault(gc, "")));
                groups.computeIfAbsent(gk.toString(), k -> new ArrayList<>()).add(row);
            }

            List<Map<String, Object>> resultRows = new ArrayList<>();
            for (var grpRows : groups.values()) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (String gc : groupCols) out.put(gc, grpRows.get(0).get(gc));
                int aggCap = Math.min(aggs.size(), policy.groupByAggLimit);
                for (int i = 0; i < aggCap; i++) {
                    var agg = aggs.get(i);
                    String alias = (String) agg.getOrDefault("alias",
                            agg.getOrDefault("func", "count") + "_" + agg.getOrDefault("column", ""));
                    out.put(alias, StatsHelpers.runAgg(grpRows,
                            (String) agg.getOrDefault("column", ""),
                            (String) agg.getOrDefault("func", "count")));
                }
                resultRows.add(out);
            }

            if (sortBy != null) {
                boolean isNum = aggs.stream().anyMatch(a -> {
                    String alias = (String) a.getOrDefault("alias",
                            a.getOrDefault("func", "") + "_" + a.getOrDefault("column", ""));
                    return alias.equals(sortBy) && NUMERIC_FUNCS.contains(a.get("func"));
                });
                if (isNum) {
                    resultRows.sort((a, b) -> {
                        Double va = StatsHelpers.toFloatPermissive(a.get(sortBy));
                        Double vb = StatsHelpers.toFloatPermissive(b.get(sortBy));
                        if (va == null && vb == null) return 0;
                        if (va == null) return 1;
                        if (vb == null) return -1;
                        return sortDesc ? Double.compare(vb, va) : Double.compare(va, vb);
                    });
                } else {
                    final String sb = sortBy;
                    resultRows.sort((a, b) -> {
                        String sa = String.valueOf(a.getOrDefault(sb, ""));
                        String sbb = String.valueOf(b.getOrDefault(sb, ""));
                        return sortDesc ? sbb.compareTo(sa) : sa.compareTo(sbb);
                    });
                }
            }

            List<Map<String, Object>> sliced = resultRows.subList(0, Math.min(resultRows.size(), Math.min(limit, MAX_RETRIEVE_ROWS)));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", key); result.put("label", slot.label()); result.put("total_rows", slot.rowCount());
            result.put("groups", groups.size()); result.put("returned", sliced.size()); result.put("rows", sliced);
            track(0, jsonLen(result));
            return result;
        } finally { lock.unlock(); }
    }

    // ── Schema ─────────────────────────────────────────────────────────

    public Map<String, Object> schema(String key) {
        lock.lock();
        try {
            BinomialHashSlot slot = getSlot(key);
            if (slot == null) return Map.of("error", "Key '" + key + "' not found.");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", key); result.put("label", slot.label()); result.put("row_count", slot.rowCount());
            result.put("byte_size", slot.byteSize()); result.put("columns", slot.columns());
            result.put("col_types", slot.colTypes()); result.put("col_stats", slot.colStats());
            track(0, jsonLen(result));
            return result;
        } finally { lock.unlock(); }
    }

    // ── Accessors ──────────────────────────────────────────────────────

    public BinomialHashSlot slot(String key) {
        lock.lock();
        try { return getSlot(key); }
        finally { lock.unlock(); }
    }

    public BinomialHashPolicy policy() { return policy; }

    // ── Internal helpers ───────────────────────────────────────────────

    private static List<Map<String, Object>> projectColumns(List<Map<String, Object>> rows, List<String> columns) {
        Set<String> colSet = new HashSet<>(columns);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (var row : rows) {
            Map<String, Object> filtered = new LinkedHashMap<>();
            for (var entry : row.entrySet()) if (colSet.contains(entry.getKey())) filtered.put(entry.getKey(), entry.getValue());
            out.add(filtered);
        }
        return out;
    }

    private static long jsonLen(Object obj) {
        try { return MAPPER.writeValueAsString(obj).length(); }
        catch (JsonProcessingException e) { return 100; }
    }

    private static String fmtNum(Object n) {
        if (n == null) return "?";
        double f = ((Number) n).doubleValue();
        if (!Double.isFinite(f)) return String.valueOf(f);
        if (Math.abs(f) >= 1e9) return String.format("%.1fB", f / 1e9);
        if (Math.abs(f) >= 1e6) return String.format("%.1fM", f / 1e6);
        if (Math.abs(f) >= 1e3) return String.format("%.1fK", f / 1e3);
        return f == (int) f ? String.valueOf((int) f) : String.format("%.2f", f);
    }
}
