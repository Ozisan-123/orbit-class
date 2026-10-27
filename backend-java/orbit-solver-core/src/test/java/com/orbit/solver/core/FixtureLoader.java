package com.orbit.solver.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbit.solver.core.model.ScheduleInput;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 读 JSON fixture。Java 与 Python 两侧读同一份文件，保证输入完全一致。 */
public final class FixtureLoader {

    /** 测试用的标准 fixture：8 班 / 36 教师 / 64 任教关系。 */
    public static final String SCHOOL_8 = "/fixtures/school-8.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ScheduleInput load(Path path) throws IOException {
        return MAPPER.readValue(Files.readString(path, StandardCharsets.UTF_8),
                ScheduleInput.class);
    }

    /** 从 classpath 读，供 JUnit 测试使用（不依赖工作目录）。 */
    public static ScheduleInput loadResource(String resourcePath) throws IOException {
        try (InputStream in = FixtureLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("classpath 上找不到测试资源: " + resourcePath);
            }
            return MAPPER.readValue(in, ScheduleInput.class);
        }
    }

    private FixtureLoader() {
    }
}
