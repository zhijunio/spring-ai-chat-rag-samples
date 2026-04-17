package io.zhijun.ai.util;

import org.springframework.ai.document.Document;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Utils {
    public static Set<String> toTerms(String text) {
        String[] split = text.toLowerCase().split("[\\p{Punct}\\p{Space}]+");
        return new HashSet<>(Arrays.asList(split));
    }

    public static double relevance(String content, Set<String> terms) {
        if (content == null || content.isEmpty()) return 0.0;
        String[] tokens = content.toLowerCase().split("[\\p{Punct}\\p{Space}]+");
        int hits = 0;
        for (String t : tokens) {
            if (terms.contains(t)) hits++;
        }
        int len = Math.max(tokens.length, 1);
        return (double) hits / Math.sqrt(len);
    }

    public static float[] l2normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) {
            sum += (double) x * (double) x;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0.0) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null) return 0.0;
        int n = Math.min(a.length, b.length);
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < n; i++) {
            dot += (double) a[i] * (double) b[i];
            na += (double) a[i] * (double) a[i];
            nb += (double) b[i] * (double) b[i];
        }
        if (na == 0.0 || nb == 0.0) return 0.0;
        return dot / Math.sqrt(na * nb);
    }

    public static Double extractScore(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) return null;
        Object[] keys = new Object[]{"distance", "score", "vector_score", "similarity"};
        for (Object k : keys) {
            Object v = meta.get(String.valueOf(k));
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v instanceof String s) {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    public static String highlightText(String text, Set<String> terms) {
        if (text == null || text.isEmpty() || terms.isEmpty()) return text;
        String out = text;
        for (String t : terms) {
            if (t.isBlank()) continue;
            out = out.replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(t) + "\\b", "<mark>$0</mark>");
        }
        return out;
    }

    public static Map<String, Object> baseDoc(Document d) {
        String id = d.getId() != null ? d.getId()
                : (d.getMetadata() != null ? String.valueOf(d.getMetadata().getOrDefault("id", null)) : null);
        Map<String, Object> base = new HashMap<>();
        base.put("id", id);
        base.put("text", d.getText());
        base.put("metadata", d.getMetadata());
        return base;
    }

    public static Bm25Context buildBm25Context(Set<String> terms, List<Document> docs) {
        Map<Document, Integer> lenMap = new HashMap<>();
        Map<Document, Map<String, Integer>> tfMap = new HashMap<>();
        for (Document d : docs) {
            String text = d.getText();
            String[] toks = text == null ? new String[0] : text.toLowerCase().split("[\\p{Punct}\\p{Space}]+");
            lenMap.put(d, toks.length);
            Map<String, Integer> tf = new HashMap<>();
            for (String t : toks) if (terms.contains(t)) tf.put(t, tf.getOrDefault(t, 0) + 1);
            tfMap.put(d, tf);
        }
        double avgdl = lenMap.values().stream().mapToInt(i -> i).average().orElse(1.0);
        Map<String, Integer> df = new HashMap<>();
        for (String term : terms) {
            int c = 0;
            for (Document d : docs) {
                Map<String, Integer> tf = tfMap.get(d);
                if (tf.containsKey(term)) c++;
            }
            df.put(term, c);
        }
        Map<String, Double> idf = new HashMap<>();
        for (String term : terms) {
            int dfi = df.getOrDefault(term, 0);
            double val = Math.log(1.0 + (double) (docs.size() - dfi + 0.5) / (double) (dfi + 0.5));
            idf.put(term, val);
        }
        return new Bm25Context(terms, lenMap, tfMap, idf, avgdl, 1.2, 0.75);
    }

    public static double bm25Score(Document d, Bm25Context ctx) {
        int dl = ctx.lenMap().get(d);
        Map<String, Integer> tf = ctx.tfMap().get(d);
        double bm25 = 0.0;
        for (String term : ctx.terms()) {
            int f = tf.getOrDefault(term, 0);
            if (f == 0) continue;
            double denom = f + ctx.k1() * (1 - ctx.b() + ctx.b() * ((double) dl / ctx.avgdl()));
            bm25 += ctx.idf().get(term) * (((double) f * (ctx.k1() + 1)) / denom);
        }
        return bm25;
    }

    public record Bm25Context(Set<String> terms,
                              Map<Document, Integer> lenMap,
                              Map<Document, Map<String, Integer>> tfMap,
                              Map<String, Double> idf,
                              double avgdl,
                              double k1,
                              double b) {
    }
}
