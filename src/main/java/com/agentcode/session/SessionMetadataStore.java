package com.agentcode.session;

import com.agentcode.properties.AgentCodeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 会话元数据持久化。runId 与工作区的绑定必须跨进程存活，否则重连后无法恢复。
 */
@Component
public class SessionMetadataStore {

    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path directory;

    public SessionMetadataStore(AgentCodeProperties properties) {
        this.directory =
                Path.of(properties.getSession().getRuntimeDir())
                        .toAbsolutePath()
                        .resolve("sessions");
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            throw new UncheckedIOException("无法创建会话元数据目录: " + directory, error);
        }
    }

    public void save(SessionMetadata metadata) {
        requireId(metadata.runId());
        Path target = directory.resolve(metadata.runId() + ".json");
        Path temporary = directory.resolve(metadata.runId() + ".json.tmp");
        try {
            objectMapper.writeValue(temporary.toFile(), metadata);
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            throw new UncheckedIOException("无法写入会话元数据: " + target, error);
        }
    }

    public Optional<SessionMetadata> find(String runId) {
        requireId(runId);
        Path source = directory.resolve(runId + ".json");
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(source.toFile(), SessionMetadata.class));
        } catch (IOException error) {
            throw new UncheckedIOException("无法读取会话元数据: " + source, error);
        }
    }

    public void delete(String runId) {
        requireId(runId);
        try {
            Files.deleteIfExists(directory.resolve(runId + ".json"));
        } catch (IOException error) {
            throw new UncheckedIOException("无法删除会话元数据: " + runId, error);
        }
    }

    private static void requireId(String runId) {
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("非法会话标识: " + runId);
        }
    }
}
