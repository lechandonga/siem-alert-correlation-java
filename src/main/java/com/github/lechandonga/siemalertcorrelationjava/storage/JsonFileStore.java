package com.github.lechandonga.siemalertcorrelationjava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地 JSON 文件存储：先写临时文件再原子移动，避免并发/崩溃产生半截文件。
 */
@Component
public class JsonFileStore {
    private static final Logger log = LoggerFactory.getLogger(JsonFileStore.class);

    private final ObjectMapper mapper;
    private final Path dataDir;

    public JsonFileStore(ObjectMapper mapper, @Value("${siem.data-dir:./data}") String dataDir) {
        this.mapper = mapper;
        this.dataDir = Path.of(dataDir);
        try {
            Files.createDirectories(this.dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建数据目录: " + this.dataDir, e);
        }
        log.info("本地数据目录: {}", this.dataDir.toAbsolutePath());
    }

    public synchronized <T> T read(String file, java.lang.reflect.Type type, T fallback) {
        Path path = dataDir.resolve(file);
        if (!Files.exists(path)) {
            return fallback;
        }
        try {
            return mapper.readValue(path.toFile(), mapper.constructType(type));
        } catch (IOException e) {
            log.error("读取 {} 失败，使用空数据启动", path, e);
            return fallback;
        }
    }

    public synchronized void write(String file, Object value) {
        Path target = dataDir.resolve(file);
        Path tmp = dataDir.resolve(file + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("写入数据文件失败: " + target, e);
        }
    }
}
