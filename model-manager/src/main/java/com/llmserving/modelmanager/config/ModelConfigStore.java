package com.llmserving.modelmanager.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 기술 보조: model-config(EA 설정 파일)를 파일 한 곳에 읽고 쓴다. 교체·롤백 결과는 이 파일 하나만 바뀐다 (QAS 5).
 * 파일이 없으면 application.yml의 처음 값을 쓴다.
 */
@Slf4j
@Component
public class ModelConfigStore {

    private final Path file;

    public ModelConfigStore(ModelManagerProperties properties) {
        this.file = Path.of(properties.configFile());
    }

    public Optional<Snapshot> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            log.warn("event=model.config-read-failed file={} msg=model-config 읽기 실패: {}", file, e.getMessage());
            return Optional.empty();
        }
        return Optional.of(new Snapshot(p.getProperty("activeModel"), p.getProperty("activeVersion"),
                p.getProperty("activeStage"), blankToNull(p.getProperty("targetVersion")),
                blankToNull(p.getProperty("targetStage")), Integer.parseInt(p.getProperty("versionCounter", "1")),
                p.getProperty("status", "IDLE")));
    }

    public void save(Snapshot snapshot) {
        Properties p = new Properties();
        p.setProperty("activeModel", snapshot.activeModel());
        p.setProperty("activeVersion", snapshot.activeVersion());
        p.setProperty("activeStage", snapshot.activeStage());
        p.setProperty("targetVersion", snapshot.targetVersion() == null ? "" : snapshot.targetVersion());
        p.setProperty("targetStage", snapshot.targetStage() == null ? "" : snapshot.targetStage());
        p.setProperty("versionCounter", String.valueOf(snapshot.versionCounter()));
        p.setProperty("status", snapshot.status());
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(out, "model-config (Model Manager가 관리, 교체·롤백 결과)");
            }
        } catch (IOException e) {
            log.warn("event=model.config-write-failed file={} msg=model-config 쓰기 실패: {}", file, e.getMessage());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** model-config 한 벌. */
    public record Snapshot(String activeModel, String activeVersion, String activeStage, String targetVersion,
                           String targetStage, int versionCounter, String status) {
    }
}
