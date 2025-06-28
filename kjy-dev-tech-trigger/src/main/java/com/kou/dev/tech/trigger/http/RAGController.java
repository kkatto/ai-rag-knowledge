package com.kou.dev.tech.trigger.http;

import com.kou.dev.tech.api.IRAGService;
import com.kou.dev.tech.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.PathResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/**
 * @author KouJY
 * @description
 * @create 2025-06-28 14:11
 */
@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/rag/")
public class RAGController implements IRAGService {

    @Resource
    private OllamaChatClient ollamaChatClient;
    @Resource
    private TokenTextSplitter tokenTextSplitter;
    @Resource
    private SimpleVectorStore simpleVectorStore;
    @Resource
    private PgVectorStore pgVectorStore;
    @Resource
    private RedissonClient redissonClient;

    @RequestMapping(value = "query_rag_tag_list", method = RequestMethod.GET)
    @Override
    public Response<List<String>> queryRagTagList() {
        RList<String> elements = redissonClient.getList("ragTag");
        return Response.<List<String>>builder()
                .code("0000")
                .info("调用成功")
                .data(elements)
                .build();
    }

    @RequestMapping(value = "file/upload", method = RequestMethod.POST, headers = "content-type=multipart/form-data")
    @Override
    public Response<String> uploadFile(@RequestParam("ragTag") String ragTag,
                                       @RequestParam("files") List<MultipartFile> files) {
        log.info("上传知识库开始 {}", ragTag);
        files.forEach(file -> {
            TikaDocumentReader documentReader = new TikaDocumentReader(file.getResource());

            List<Document> documentList = documentReader.get();
            List<Document> documentsSplitterList = tokenTextSplitter.apply(documentList);

            documentList.forEach(document -> document.getMetadata().put("knowledge", ragTag));
            documentsSplitterList.forEach(document -> document.getMetadata().put("knowledge", ragTag));

            pgVectorStore.accept(documentsSplitterList);

            RList<String> elements = redissonClient.getList("ragTag");
            if (!elements.contains(ragTag)) {
                elements.add(ragTag);
            }
        });

        log.info("上传知识库结束 {}", ragTag);
        return Response.<String>builder()
                .code("0000")
                .info("调用成功")
                .build();
    }

    @RequestMapping(value = "analyze_git_repository", method = RequestMethod.POST)
    @Override
    public Response<String> analyzeGitRepository(@RequestParam("repoUrl") String repoUrl,
                                                 @RequestParam("username") String username,
                                                 @RequestParam("token") String token) throws Exception {
        log.info("解析Git仓库开始");
        String localPath = "./git-cloned-repo";
        String repoProjectName = extractProjectName(repoUrl);

        FileUtils.deleteDirectory(new File(localPath));

        Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(new File(localPath))
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, token))
                .call();

        Files.walkFileTree(Paths.get(localPath), new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                log.info("{} 遍历解析路径，上传知识库:{}", repoProjectName, file.getFileName());

                try {
                    TikaDocumentReader reader = new TikaDocumentReader(new PathResource(file));

                    List<Document> documentList = reader.get();
                    List<Document> documentSplitterList = tokenTextSplitter.apply(documentList);

                    documentList.forEach(document -> document.getMetadata().put("knowledge", repoProjectName));
                    documentSplitterList.forEach(document -> document.getMetadata().put("knowledge", repoProjectName));

                    pgVectorStore.accept(documentSplitterList);
                } catch (Exception e) {
                    log.error("遍历解析路径，上传知识库失败:{}", file.getFileName());
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                log.info("Failed to access file: {} - {}", file.toString(), exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        FileUtils.deleteDirectory(new File(localPath));

        RList<String> elements = redissonClient.getList("ragTag");
        if (!elements.contains(repoProjectName)) {
            elements.add(repoProjectName);
        }
        git.close();

        log.info("遍历解析路径，上传完成:{}", repoUrl);

        return Response.<String>builder().code("0000").info("调用成功").build();
    }

    private String extractProjectName(String repoUrl) {
        String[] parts = repoUrl.split("/");
        String projectNameWithGit = parts[parts.length - 1];
        return projectNameWithGit.replace(".git", "");
    }
}
