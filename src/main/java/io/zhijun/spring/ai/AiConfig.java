package io.zhijun.spring.ai;

import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
public class AiConfig {
    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Value("classpath:/data/about.md")
    private Resource aboutFile;

    @Value("classpath:/data/career.pdf")
    private Resource careerFile;

    @Bean
    ApplicationRunner applicationRunner(VectorStore vectorStore) {
        return args -> {
            loadDocumentIfNotExists(vectorStore, aboutFile, "about");
            loadDocumentIfNotExists(vectorStore, careerFile, "career");
        };
    }

    private void loadDocumentIfNotExists(VectorStore vectorStore, Resource resource, String sourceTag) {
        // Check if document already exists by searching for documents with this source tag in metadata
        List<Document> existing = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(sourceTag)
                        .topK(1)
                        .build()
        );

        boolean exists = !existing.isEmpty() && existing.stream()
                .anyMatch(d -> sourceTag.equals(String.valueOf(d.getMetadata().get("source"))));

        if (exists) {
            log.info("Document {} already loaded, skipping", resource.getFilename());
        } else {
            loadDocument(vectorStore, resource);
        }
    }

    private void loadDocument(VectorStore vectorStore, Resource resource) {
        log.info("Loading document {} into vector store", resource.getFilename());

        DocumentReader documentReader = createDocumentReader(resource);
        if (documentReader == null) {
            log.warn("Unsupported document type: {}", resource.getFilename());
            return;
        }

        List<Document> documents = documentReader.get();
        TextSplitter textSplitter = TokenTextSplitter.builder().build();
        List<Document> splitDocuments = textSplitter.apply(documents);
        String src = FilenameUtils.getBaseName(resource.getFilename());

        List<Document> enriched = splitDocuments.stream()
                .map(d -> {
                    Map<String, Object> meta = new HashMap<>();
                    if (d.getMetadata() != null) {
                        meta.putAll(d.getMetadata());
                    }
                    meta.put("source", src);
                    meta.put("id", src + "-" + d.getId());
                    return new Document(d.getText(), meta);
                })
                .collect(Collectors.toList());

        vectorStore.accept(enriched);
        log.info("Document {} loaded into vector store", resource.getFilename());
    }

    private DocumentReader createDocumentReader(Resource resource) {
        if (resource.getFilename().endsWith(".md")) {
            return new MarkdownDocumentReader(resource, MarkdownDocumentReaderConfig.defaultConfig());
        } else if (resource.getFilename().endsWith(".pdf")) {
            return new PagePdfDocumentReader(resource);
        }
        return null;
    }
}