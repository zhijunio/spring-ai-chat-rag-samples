package cc.chensoul.ai;

import cc.chensoul.ai.model.Input;
import cc.chensoul.ai.model.Output;
import cc.chensoul.ai.util.MarkdownHelper;
import cc.chensoul.ai.util.Utils;
import jakarta.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
class ChatController {
    private final ChatClient chatClient;
    private final ChatClient ragChatClient;
    private final ChatClient advancedRagChatClient;
    private final ChatClient qaTemplateChatClient;
    private final ChatClient modularRagChatClient;

    /**
     * 在同一个 Bean 中创建多个 ChatClient 时，必须注入 ObjectProvider<ChatClient.Builder>
     * 并通过 getObject() 获取独立的 Builder 实例，否则会导致 Advisor 污染和循环引用（StackOverflowError）
     *
     * @param builderProvider
     * @param chatMemory
     * @param vectorStore
     */
    ChatController(ObjectProvider<ChatClient.Builder> builderProvider,
                   ChatMemory chatMemory,
                   VectorStore vectorStore) {
        this.chatClient = builderProvider.getObject()
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        QuestionAnswerAdvisor.builder(vectorStore).build(),
                        new SimpleLoggerAdvisor()
                )
                .build();

        PromptTemplate customPromptTemplate = PromptTemplate.builder()
                .renderer(StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build())
                .template("""
                        <query>
                        
                        Context information is below.
                        
                        ---------------------
                        <question_answer_context>
                        ---------------------
                        
                        Given the context information and no prior knowledge, answer the query.
                        
                        Follow these rules:
                        
                        1. If the answer is not in the context, just say that you don't know.
                        2. Avoid statements like "Based on the context..." or "The provided information...".
                        """)
                .build();
        this.qaTemplateChatClient = builderProvider.getObject()
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .promptTemplate(customPromptTemplate)
                                .build(),
                        new SimpleLoggerAdvisor()
                )
                .build();
        this.ragChatClient = builderProvider.getObject()
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        RetrievalAugmentationAdvisor.builder()
                                .documentRetriever(VectorStoreDocumentRetriever.builder().vectorStore(vectorStore).build())
                                .build(),
                        new SimpleLoggerAdvisor()
                )
                .build();

        DocumentPostProcessor keywordReranker = keywordReranker();
        this.advancedRagChatClient = builderProvider.getObject()
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        RetrievalAugmentationAdvisor.builder()
                                .documentRetriever(VectorStoreDocumentRetriever.builder()
                                        .vectorStore(vectorStore)
                                        .topK(20)
                                        .similarityThreshold(0.5)
                                        .build())
                                .documentPostProcessors(List.of(keywordReranker))
                                .build(),
                        new SimpleLoggerAdvisor()
                )
                .build();

        this.modularRagChatClient = builderProvider.getObject()
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        RetrievalAugmentationAdvisor.builder()
                                .queryTransformers(List.of(
                                        RewriteQueryTransformer.builder().chatClientBuilder(builderProvider.getObject()).build(),
                                        CompressionQueryTransformer.builder().chatClientBuilder(builderProvider.getObject()).build(),
                                        TranslationQueryTransformer.builder().chatClientBuilder(builderProvider.getObject()).targetLanguage("english").build()
                                ))
                                .queryExpander(MultiQueryExpander.builder().chatClientBuilder(builderProvider.getObject()).numberOfQueries(3).build())
                                .documentRetriever(VectorStoreDocumentRetriever.builder()
                                        .vectorStore(vectorStore)
                                        .similarityThreshold(0.4)
                                        .topK(10)
                                        .build())
                                .documentPostProcessors(List.of(
                                        bm25Reranker(8),
                                        truncateDoc(600)
                                ))
                                .documentJoiner(new ConcatenationDocumentJoiner())
                                .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(true).build())
                                .build(),
                        new SimpleLoggerAdvisor()
                )
                .build();
    }

    @PostMapping
    ResponseEntity<Output> chat(@RequestBody @Valid Input input,
                                @CookieValue(name = "X-CONV-ID", required = false) String convId) {
        return chatWithClient(this.chatClient, input, convId);
    }

    @PostMapping("/advanced")
    ResponseEntity<Output> chatAdvanced(@RequestBody @Valid Input input,
                                        @CookieValue(name = "X-CONV-ID", required = false) String convId) {
        return chatWithClient(this.advancedRagChatClient, input, convId);
    }

    @PostMapping("/qa-template")
    ResponseEntity<Output> chatWithQATemplate(@RequestBody @Valid Input input,
                                              @CookieValue(name = "X-CONV-ID", required = false) String convId) {
        return chatWithClient(this.qaTemplateChatClient, input, convId);
    }

    @PostMapping("/rag")
    ResponseEntity<Output> chatRag(@RequestBody @Valid Input input,
                                   @CookieValue(name = "X-CONV-ID", required = false) String convId) {
        return chatWithClient(this.ragChatClient, input, convId);
    }

    @PostMapping("/filter")
    ResponseEntity<Output> chatFilter(@RequestBody @Valid Input input,
                                      @RequestParam(defaultValue = "source == 'about'") String filter,
                                      @CookieValue(name = "X-CONV-ID", required = false) String convId) {
        return chatWithClient(this.chatClient, input, convId, filter);
    }

    @PostMapping("/rag-modular")
    ResponseEntity<Output> chatRagModular(@RequestBody @Valid Input input,
                                          @CookieValue(name = "X-CONV-ID", required = false) String convId) {
        return chatWithClient(this.modularRagChatClient, input, convId);
    }


    private ResponseEntity<Output> chatWithClient(ChatClient client,
                                                  Input input,
                                                  String convId) {
        return chatWithClient(client, input, convId, null);
    }

    private ResponseEntity<Output> chatWithClient(ChatClient client,
                                                  Input input,
                                                  String convId,
                                                  String filter) {
        String conversationId = convId == null ? UUID.randomUUID().toString() : convId;
        String response = client.prompt()
                .user(input.prompt())
                .advisors(a -> {
                    a.param(ChatMemory.CONVERSATION_ID, conversationId);
                    if (filter != null) {
                        a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, filter);
                    }
                })
                .call().content();

        ResponseCookie cookie = ResponseCookie.from("X-CONV-ID", conversationId)
                .path("/")
                .maxAge(3600)
                .build();
        var htmlResponse = MarkdownHelper.toHTML(response);
        Output output = new Output(htmlResponse);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(output);
    }

    private DocumentPostProcessor keywordReranker() {
        return (query, docs) -> {
            Set<String> terms = Utils.toTerms(query.text());
            return docs.stream()
                    .sorted(Comparator.<Document>comparingDouble(d -> {
                        double base = Utils.relevance(d.getText(), terms);
                        double bonus = "about".equals(String.valueOf(d.getMetadata().get("source"))) ? 0.001 : 0.0;
                        return base + bonus;
                    }).reversed())
                    .limit(6)
                    .toList();
        };
    }

    private DocumentPostProcessor bm25Reranker(int limit) {
        return (query, docs) -> {
            Set<String> terms = Utils.toTerms(query.text());
            Utils.Bm25Context ctx = Utils.buildBm25Context(terms, docs);
            return docs.stream()
                    .sorted(Comparator.<Document>comparingDouble(d -> {
                        Double raw = Utils.extractScore(d.getMetadata());
                        return 0.6 * Utils.bm25Score(d, ctx) + 0.4 * (raw != null ? raw : 0.0);
                    }).reversed())
                    .limit(limit)
                    .toList();
        };
    }

    private DocumentPostProcessor truncateDoc(int maxLen) {
        return (query, docs) -> docs.stream().map(d -> {
            String t = d.getText();
            String nt = (t != null && t.length() > maxLen) ? t.substring(0, maxLen) : t;
            return new Document(nt, d.getMetadata());
        }).toList();
    }

}
