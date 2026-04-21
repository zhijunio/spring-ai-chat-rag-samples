package io.zhijun.ai;

import io.zhijun.ai.util.Utils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/rag")
public class RagController {
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;

    RagController(EmbeddingModel embeddingModel, VectorStore vectorStore, ChatClient.Builder chatClientBuilder) {
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.chatClientBuilder = chatClientBuilder;
    }

    // 向量嵌入示例：支持单条/多条文本与可选归一化
    @GetMapping("/embedding")
    public Map<String, Object> embed(
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "messages", required = false) List<String> messages,
            @RequestParam(value = "normalize", defaultValue = "false") boolean normalize) {
        List<String> inputs = messages != null && !messages.isEmpty()
                ? messages
                : List.of(message != null ? message : "Tell me a joke");
        List<float[]> vectors = embeddingModel.embed(inputs);
        if (normalize) {
            vectors = vectors.stream().map(Utils::l2normalize).toList();
        }
        return Map.of("inputs", inputs, "embeddings", vectors);
    }

    // 相似度示例：对两段文本计算余弦相似度
    @GetMapping("/embedding/similarity")
    public Map<String, Object> embeddingSimilarity(@RequestParam String text1,
                                                   @RequestParam String text2,
                                                   @RequestParam(defaultValue = "true") boolean normalize) {
        List<float[]> vectors = embeddingModel.embed(List.of(text1, text2));
        float[] v1 = vectors.get(0);
        float[] v2 = vectors.get(1);
        if (normalize) {
            v1 = Utils.l2normalize(v1);
            v2 = Utils.l2normalize(v2);
            double similarity = 0.0;
            int n = Math.min(v1.length, v2.length);
            for (int i = 0; i < n; i++) {
                similarity += (double) v1[i] * (double) v2[i];
            }
            return Map.of(
                    "text1", text1,
                    "text2", text2,
                    "normalized", true,
                    "similarity", similarity,
                    "vectorSize", n
            );
        }
        return Map.of(
                "text1", text1,
                "text2", text2,
                "normalized", false,
                "similarity", Utils.cosineSimilarity(v1, v2),
                "vectorSize", Math.min(v1.length, v2.length)
        );
    }

    // 向量检索示例：支持阈值、分页、来源过滤与高亮
    @GetMapping("/search")
    ResponseEntity<Map<String, Object>> searchVectorStore(@RequestParam String query,
                                                          @RequestParam(defaultValue = "10") int topK,
                                                          @RequestParam(defaultValue = "0.0") double threshold,
                                                          @RequestParam(required = false) String source,
                                                          @RequestParam(defaultValue = "0") int offset,
                                                          @RequestParam(required = false) Integer limit,
                                                          @RequestParam(defaultValue = "false") boolean highlight) {
        List<Document> docs = retrieveDocs(query, topK, threshold);
        List<Document> finalDocs = filterBySource(docs, source);
        Set<String> terms = Utils.toTerms(query);
        Utils.Bm25Context ctx = Utils.buildBm25Context(terms, finalDocs);
        int size = finalDocs.size();
        int lim = limit == null ? size : Math.min(limit, size);
        List<Map<String, Object>> items = finalDocs.stream()
                .sorted(java.util.Comparator.<Document>comparingDouble(d -> {
                    Double raw = Utils.extractScore(d.getMetadata());
                    return 0.6 * Utils.bm25Score(d, ctx) + 0.4 * (raw != null ? raw : 0.0);
                }).reversed())
                .skip(Math.max(offset, 0))
                .limit(Math.max(lim, 0))
                .map(d -> {
                    Double raw = Utils.extractScore(d.getMetadata());
                    double bm25 = Utils.bm25Score(d, ctx);
                    Map<String, Object> base = Utils.baseDoc(d);
                    base.put("bm25", bm25);
                    if (raw != null) base.put("rawScore", raw);
                    base.put("score", 0.6 * bm25 + 0.4 * (raw != null ? raw : 0.0));
                    if (highlight) base.put("highlight", Utils.highlightText(d.getText(), terms));
                    return base;
                })
                .toList();
        return ResponseEntity.ok(Map.of("query", query, "results", items));
    }

    // 元数据过滤示例：支持多来源、id 前缀与任意 metadata 键值
    @GetMapping("/search/filter")
    ResponseEntity<Map<String, Object>> searchWithMetadataFilter(@RequestParam String query,
                                                                  @RequestParam(defaultValue = "10") int topK,
                                                                  @RequestParam(defaultValue = "0.0") double threshold,
                                                                  @RequestParam(required = false) String source,
                                                                  @RequestParam(required = false) String sources,
                                                                  @RequestParam(required = false) String idPrefix,
                                                                  @RequestParam(required = false) String metadataKey,
                                                                  @RequestParam(required = false) String metadataValue,
                                                                  @RequestParam(defaultValue = "false") boolean highlight) {
        List<Document> docs = retrieveDocs(query, topK, threshold);
        Set<String> allowedSources = parseSources(source, sources);
        List<Document> filtered = docs.stream()
                .filter(d -> matchesMetadata(d, allowedSources, idPrefix, metadataKey, metadataValue))
                .toList();
        Set<String> terms = Utils.toTerms(query);
        Utils.Bm25Context ctx = Utils.buildBm25Context(terms, filtered);
        List<Map<String, Object>> items = filtered.stream()
                .sorted(java.util.Comparator.<Document>comparingDouble(d -> {
                    Double raw = Utils.extractScore(d.getMetadata());
                    return 0.6 * Utils.bm25Score(d, ctx) + 0.4 * (raw != null ? raw : 0.0);
                }).reversed())
                .map(d -> {
                    Double raw = Utils.extractScore(d.getMetadata());
                    double bm25 = Utils.bm25Score(d, ctx);
                    Map<String, Object> base = Utils.baseDoc(d);
                    base.put("bm25", bm25);
                    if (raw != null) base.put("rawScore", raw);
                    base.put("score", 0.6 * bm25 + 0.4 * (raw != null ? raw : 0.0));
                    if (highlight) base.put("highlight", Utils.highlightText(d.getText(), terms));
                    return base;
                })
                .toList();
        Map<String, Object> filters = new java.util.HashMap<>();
        if (!allowedSources.isEmpty()) filters.put("sources", allowedSources);
        if (idPrefix != null && !idPrefix.isBlank()) filters.put("idPrefix", idPrefix);
        if (metadataKey != null && !metadataKey.isBlank()) {
            filters.put("metadataKey", metadataKey);
            if (metadataValue != null && !metadataValue.isBlank()) {
                filters.put("metadataValue", metadataValue);
            }
        }
        return ResponseEntity.ok(Map.of(
                "query", query,
                "filters", filters,
                "count", filtered.size(),
                "results", items
        ));
    }

    // 查询翻译示例：将 query 转为目标语言
    @GetMapping("/query/translate")
    public Map<String, Object> translate(@RequestParam String query,
                                         @RequestParam(defaultValue = "english") String target) {
        var transformer = TranslationQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .targetLanguage(target)
                .build();
        Query transformed = transformer.apply(new Query(query));
        return Map.of(
                "originalQuery", query,
                "targetLanguage", target,
                "transformedQuery", transformed.text()
        );
    }

    // 查询压缩示例：基于对话历史压缩 query
    @GetMapping("/query/compress")
    public Map<String, Object> compress(@RequestParam String query,
                                        @RequestParam(required = false) String history) {
        java.util.List<Message> historyMessages = (history != null && !history.isBlank())
                ? java.util.List.of(new UserMessage(history))
                : java.util.List.of();
        Query q = Query.builder().text(query).history(historyMessages).build();
        var transformer = CompressionQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .build();
        Query transformed = transformer.apply(q);
        return Map.of(
                "originalQuery", query,
                "historyCount", historyMessages.size(),
                "compressedQuery", transformed.text()
        );
    }

    // 查询改写示例：改写为更检索友好的 query
    @GetMapping("/query/rewrite")
    public Map<String, Object> rewrite(@RequestParam String query) {
        var transformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .build();
        Query transformed = transformer.apply(new Query(query));
        return Map.of(
                "originalQuery", query,
                "rewrittenQuery", transformed.text()
        );
    }

    // 查询扩展示例：扩展多 query 并合并结果
    @GetMapping("/query/expand")
    ResponseEntity<Map<String, Object>> expand(@RequestParam String query,
                                               @RequestParam(defaultValue = "3") int n,
                                               @RequestParam(defaultValue = "10") int topK) {
        var expander = MultiQueryExpander.builder()
                .chatClientBuilder(chatClientBuilder)
                .numberOfQueries(n)
                .build();
        java.util.List<Query> expanded = expander.expand(new Query(query));
        var retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(topK)
                .build();
        Map<Query, java.util.List<java.util.List<Document>>> docsForQuery = new java.util.LinkedHashMap<>();
        for (Query eq : expanded) {
            java.util.List<Document> d = retriever.retrieve(eq);
            docsForQuery.put(eq, java.util.List.of(d));
        }
        java.util.List<Document> joined = new ConcatenationDocumentJoiner().join(docsForQuery);
        return ResponseEntity.ok(Map.of(
                "originalQuery", query,
                "expandedQueries", expanded.stream().map(Query::text).toList(),
                "joinedCount", joined.size()
        ));
    }

    // 多路检索示例：分来源检索并拼接合并
    @GetMapping("/search/multi")
    ResponseEntity<Map<String, Object>> multiSourceSearch(@RequestParam String query,
                                                          @RequestParam(defaultValue = "10") int topK,
                                                          @RequestParam(defaultValue = "0.0") double threshold) {
        List<Document> docs = retrieveDocs(query, topK, threshold);
        List<Document> aboutDocs = docs.stream()
                .filter(d -> "about".equals(String.valueOf(d.getMetadata().get("source"))))
                .toList();
        List<Document> careerDocs = docs.stream()
                .filter(d -> "career".equals(String.valueOf(d.getMetadata().get("source"))))
                .toList();
        Map<Query, List<List<Document>>> documentsForQuery = new java.util.LinkedHashMap<>();
        documentsForQuery.put(new Query(query + " about"), java.util.List.of(aboutDocs));
        documentsForQuery.put(new Query(query + " career"), java.util.List.of(careerDocs));
        List<Document> joined = new ConcatenationDocumentJoiner().join(documentsForQuery);
        List<Map<String, Object>> items = joined.stream().map(d -> Map.of(
                "id", d.getId() != null ? d.getId()
                        : (d.getMetadata() != null ? String.valueOf(d.getMetadata().getOrDefault("id", null)) : null),
                "text", d.getText(),
                "metadata", d.getMetadata()
        )).toList();
        return ResponseEntity.ok(Map.of(
                "query", query,
                "aboutCount", aboutDocs.size(),
                "careerCount", careerDocs.size(),
                "joinedCount", joined.size(),
                "results", items
        ));
    }

    // 两阶段检索示例：扩展查询 -> 检索 -> 合并 -> 重排序
    @GetMapping("/search/two-stage")
    ResponseEntity<Map<String, Object>> twoStageSearch(@RequestParam String query,
                                                       @RequestParam(defaultValue = "3") int n,
                                                       @RequestParam(defaultValue = "10") int topK,
                                                       @RequestParam(defaultValue = "0.0") double threshold,
                                                       @RequestParam(defaultValue = "false") boolean highlight) {
        var expander = MultiQueryExpander.builder()
                .chatClientBuilder(chatClientBuilder)
                .numberOfQueries(n)
                .build();
        List<Query> expanded = expander.expand(new Query(query));
        var retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(topK)
                .similarityThreshold(threshold)
                .build();
        Map<Query, List<List<Document>>> documentsForQuery = new java.util.LinkedHashMap<>();
        Map<String, Integer> perQueryCounts = new java.util.LinkedHashMap<>();
        for (Query eq : expanded) {
            List<Document> d = retriever.retrieve(eq);
            documentsForQuery.put(eq, List.of(d));
            perQueryCounts.put(eq.text(), d.size());
        }
        List<Document> joined = new ConcatenationDocumentJoiner().join(documentsForQuery);
        Set<String> terms = Utils.toTerms(query);
        Utils.Bm25Context ctx = Utils.buildBm25Context(terms, joined);
        List<Map<String, Object>> items = joined.stream()
                .sorted(java.util.Comparator.<Document>comparingDouble(d -> {
                    Double raw = Utils.extractScore(d.getMetadata());
                    return 0.6 * Utils.bm25Score(d, ctx) + 0.4 * (raw != null ? raw : 0.0);
                }).reversed())
                .limit(topK)
                .map(d -> {
                    Double raw = Utils.extractScore(d.getMetadata());
                    double bm25 = Utils.bm25Score(d, ctx);
                    Map<String, Object> base = Utils.baseDoc(d);
                    base.put("bm25", bm25);
                    if (raw != null) base.put("rawScore", raw);
                    base.put("score", 0.6 * bm25 + 0.4 * (raw != null ? raw : 0.0));
                    if (highlight) base.put("highlight", Utils.highlightText(d.getText(), terms));
                    return base;
                })
                .toList();
        return ResponseEntity.ok(Map.of(
                "query", query,
                "expandedQueries", expanded.stream().map(Query::text).toList(),
                "perQueryCounts", perQueryCounts,
                "joinedCount", joined.size(),
                "results", items
        ));
    }

    // 上下文增强示例：基于检索结果扩展 query
    @GetMapping("/augment/context")
    ResponseEntity<Map<String, Object>> augmentQueryWithContext(@RequestParam String query,
                                                                @RequestParam(defaultValue = "8") int topK,
                                                                @RequestParam(defaultValue = "0.0") double threshold,
                                                                @RequestParam(defaultValue = "true") boolean allowEmptyContext) {
        List<Document> docs = retrieveDocs(query, topK, threshold);
        ContextualQueryAugmenter augmenter = ContextualQueryAugmenter.builder()
                .allowEmptyContext(allowEmptyContext)
                .build();
        Query augmented = augmenter.apply(new Query(query), docs);
        List<Map<String, Object>> items = docs.stream().map(d -> Map.of(
                "id", d.getId() != null ? d.getId()
                        : (d.getMetadata() != null ? String.valueOf(d.getMetadata().getOrDefault("id", null)) : null),
                "text", d.getText(),
                "metadata", d.getMetadata()
        )).toList();
        return ResponseEntity.ok(Map.of(
                "originalQuery", query,
                "augmentedQuery", augmented.text(),
                "docCount", docs.size(),
                "results", items
        ));
    }

    // 重排序示例：BM25 与向量分数融合
    @GetMapping("/search/rerank")
    ResponseEntity<Map<String, Object>> rerank(@RequestParam String query,
                                               @RequestParam(defaultValue = "10") int topK,
                                               @RequestParam(defaultValue = "0.0") double threshold) {
        List<Document> docs = retrieveDocs(query, topK, threshold);
        Set<String> terms = Utils.toTerms(query);
        Utils.Bm25Context ctx = Utils.buildBm25Context(terms, docs);
        List<Document> reranked = docs.stream()
                .sorted(java.util.Comparator.<Document>comparingDouble(d -> {
                    Double raw = Utils.extractScore(d.getMetadata());
                    return 0.6 * Utils.bm25Score(d, ctx) + 0.4 * (raw != null ? raw : 0.0);
                }).reversed())
                .limit(topK)
                .toList();
        List<Map<String, Object>> items = reranked.stream().map(d -> {
            Double raw = Utils.extractScore(d.getMetadata());
            double bmKw = Utils.relevance(d.getText(), terms);
            Map<String, Object> m = Utils.baseDoc(d);
            if (raw != null) m.put("rawScore", raw);
            m.put("kwScore", bmKw);
            return m;
        }).toList();
        return ResponseEntity.ok(Map.of(
                "query", query,
                "count", items.size(),
                "results", items
        ));
    }

    // 重排序解释示例：返回 BM25 组成项
    @GetMapping("/search/rerank/explain")
    ResponseEntity<Map<String, Object>> rerankExplain(@RequestParam String query,
                                                      @RequestParam(defaultValue = "10") int topK,
                                                      @RequestParam(defaultValue = "0.0") double threshold) {
        List<Document> docs = retrieveDocs(query, topK, threshold);
        Set<String> terms = Utils.toTerms(query);
        Utils.Bm25Context ctx = Utils.buildBm25Context(terms, docs);
        List<Map<String, Object>> items = docs.stream().map(d -> {
            int dl = ctx.lenMap().get(d);
            Map<String, Integer> tf = ctx.tfMap().get(d);
            Map<String, Double> components = new java.util.HashMap<>();
            double bm25 = 0.0;
            for (String term : ctx.terms()) {
                int f = tf.getOrDefault(term, 0);
                if (f == 0) continue;
                double denom = f + ctx.k1() * (1 - ctx.b() + ctx.b() * ((double) dl / ctx.avgdl()));
                double contrib = ctx.idf().get(term) * (((double) f * (ctx.k1() + 1)) / denom);
                components.put(term, contrib);
                bm25 += contrib;
            }
            Double raw = Utils.extractScore(d.getMetadata());
            Map<String, Object> base = Utils.baseDoc(d);
            base.put("docLength", dl);
            base.put("tf", tf);
            base.put("idf", ctx.idf());
            base.put("components", components);
            base.put("bm25", bm25);
            if (raw != null) base.put("rawScore", raw);
            base.put("score", 0.6 * bm25 + 0.4 * (raw != null ? raw : 0.0));
            return base;
        }).toList();
        return ResponseEntity.ok(Map.of(
                "query", query,
                "avgDocLength", ctx.avgdl(),
                "results", items
        ));
    }

    private List<Document> retrieveDocs(String query, int topK, double threshold) {
        return VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(topK)
                .similarityThreshold(threshold)
                .build()
                .retrieve(new Query(query));
    }

    private List<Document> filterBySource(List<Document> docs, String source) {
        if (source == null || source.isBlank()) return docs;
        return docs.stream().filter(d -> source.equals(String.valueOf(d.getMetadata().get("source")))).toList();
    }

    private Set<String> parseSources(String source, String sources) {
        Set<String> allowed = new java.util.HashSet<>();
        if (source != null && !source.isBlank()) allowed.add(source);
        if (sources != null && !sources.isBlank()) {
            java.util.Arrays.stream(sources.split("[,;\\s]+"))
                    .filter(s -> !s.isBlank())
                    .forEach(allowed::add);
        }
        return allowed;
    }

    private boolean matchesMetadata(Document d,
                                    Set<String> allowedSources,
                                    String idPrefix,
                                    String metadataKey,
                                    String metadataValue) {
        Map<String, Object> meta = d.getMetadata();
        if (!allowedSources.isEmpty()) {
            String src = meta != null ? String.valueOf(meta.get("source")) : null;
            if (src == null || !allowedSources.contains(src)) return false;
        }
        if (idPrefix != null && !idPrefix.isBlank()) {
            String id = d.getId() != null ? d.getId()
                    : (meta != null ? String.valueOf(meta.get("id")) : null);
            if (id == null || !id.startsWith(idPrefix)) return false;
        }
        if (metadataKey != null && !metadataKey.isBlank()) {
            Object v = meta != null ? meta.get(metadataKey) : null;
            if (metadataValue == null || metadataValue.isBlank()) {
                if (v == null) return false;
            } else {
                if (v == null || !metadataValue.equals(String.valueOf(v))) return false;
            }
        }
        return true;
    }

}
