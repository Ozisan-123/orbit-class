package com.orbit.scheduling.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.orbit.common.error.BusinessException;
import com.orbit.common.util.UtcTimes;
import com.orbit.scheduling.snapshot.ScheduleInputLoader;
import com.orbit.solver.core.model.ScheduleInput;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 排课输入快照 —— 冻结 + 指纹 + 还原（P3，铁律 3 的真正落地）。
 *
 * <h2>为什么 P3 才真正需要它</h2>
 * P1/P2 时求解就发生在生成请求的同一条链路里，`ScheduleInputLoader` 现查一遍没问题。
 * P3 之后求解挪到独立进程、**可能排队几分钟才开始跑** —— 期间教务数据完全可能被改。
 * 不冻结的话：排出来的课表对应的是"另一份数据"，而且事后无法复现。
 *
 * <h2>指纹怎么算</h2>
 * 与 {@code rule_set_hash} 同一套做法：**把 payload 规范化成键排序的紧凑 JSON 再 sha256**。
 * 于是同一份输入 → 同一个指纹，可以写进课表版本一起进审计链
 * （`snapshot_fingerprint` + `rule_set_hash` + `model_fingerprint` 三者合起来，
 * 任何一版课表都能被精确复现）。
 */
@Service
public class SnapshotService {

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private final ScheduleInputLoader inputLoader;
    private final SchedulingSnapshotMapper snapshotMapper;

    public SnapshotService(ScheduleInputLoader inputLoader,
                           SchedulingSnapshotMapper snapshotMapper) {
        this.inputLoader = inputLoader;
        this.snapshotMapper = snapshotMapper;
    }

    /** 冻结当前输入并存库。 */
    public SchedulingSnapshotEntity freeze(long organizationId) {
        ScheduleInput input = inputLoader.load(organizationId);
        Map<String, Object> payload = toMap(input);

        SchedulingSnapshotEntity entity = new SchedulingSnapshotEntity();
        entity.setOrganizationId(organizationId);
        entity.setPayload(payload);
        entity.setFingerprint(fingerprintOf(payload));
        entity.setGradeCount(input.grades().size());
        entity.setClassCount(input.classes().size());
        entity.setSubjectCount(input.subjects().size());
        entity.setTeacherCount(input.teachers().size());
        entity.setAssignmentCount(input.assignments().size());
        entity.setGradeSubjectCount(input.gradeSubjects().size());
        entity.setFixedSlotCount(input.gradeFixedSlots().size());
        entity.setCreatedAt(UtcTimes.now());
        snapshotMapper.insert(entity);
        return entity;
    }

    /** 取快照实体（不存在则 404）。 */
    public SchedulingSnapshotEntity require(long organizationId, long snapshotId) {
        SchedulingSnapshotEntity entity = snapshotMapper.selectOne(
                Wrappers.<SchedulingSnapshotEntity>lambdaQuery()
                        .eq(SchedulingSnapshotEntity::getId, snapshotId)
                        .eq(SchedulingSnapshotEntity::getOrganizationId, organizationId));
        if (entity == null) {
            throw BusinessException.notFound("排课输入快照不存在");
        }
        return entity;
    }

    /** 还原成 {@link ScheduleInput} 交给求解器。 */
    public ScheduleInput payloadOf(long organizationId, long snapshotId) {
        return toInput(require(organizationId, snapshotId));
    }

    /** 把一个快照实体还原成 {@link ScheduleInput}。 */
    public ScheduleInput toInput(SchedulingSnapshotEntity entity) {
        try {
            return CANONICAL.treeToValue(CANONICAL.valueToTree(entity.getPayload()), ScheduleInput.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("快照无法还原成 ScheduleInput", e);
        }
    }

    /** 对一份输入直接算指纹（不落库时也用得上，例如给诊断比对）。 */
    public String fingerprintOfInput(ScheduleInput input) {
        return fingerprintOf(toMap(input));
    }

    static String fingerprintOf(Map<String, Object> payload) {
        try {
            JsonNode node = CANONICAL.valueToTree(payload);
            return sha256Hex(CANONICAL.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("快照无法规范化", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(ScheduleInput input) {
        return CANONICAL.convertValue(input, LinkedHashMap.class);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
