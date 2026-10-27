package com.orbit.scheduling.job;

import com.orbit.common.util.UtcTimes;
import com.orbit.scheduling.audit.ScheduleAuditLog;
import com.orbit.scheduling.audit.ScheduleAuditLogMapper;
import com.orbit.scheduling.version.ScheduleVersion;
import com.orbit.scheduling.version.ScheduleVersionEntry;
import com.orbit.scheduling.version.ScheduleVersionEntryMapper;
import com.orbit.scheduling.version.ScheduleVersionMapper;
import com.orbit.solver.core.model.TimetableEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * worker 交回结果时落库 —— P3 版的结果闸门。
 *
 * <p>与 P1 的 {@code ScheduleResultWriter} 做的是同一件事（"确认还是我的任务，再写版本"），
 * 但判据从组织级状态行换成了**任务行 + job_token**：
 * <pre>
 * ① SELECT * FROM schedule_jobs WHERE id=? AND job_token=? AND status='running' FOR UPDATE
 *        → null ⇒ 已被接管 ⇒ **丢弃结果**（静默，不报错）
 * ② 写 schedule_versions（带三个溯源指纹）+ 明细 + 审计
 * ③ 置 job = done（同事务）
 * </pre>
 *
 * <p>⚠ 三步必须在**同一个事务**里。否则"版本写完但任务没标记完成"会在崩溃时留下
 * 一个已完成落库、却仍然显示 running 的任务，租约到期后又被别人重做一遍 →
 * **同一个任务落两份课表版本**。这正是决策门要验的幂等性。
 */
@Component
public class JobResultWriter {

    private static final Logger log = LoggerFactory.getLogger(JobResultWriter.class);

    private final ScheduleJobMapper jobMapper;
    private final ScheduleVersionMapper versionMapper;
    private final ScheduleVersionEntryMapper entryMapper;
    private final ScheduleAuditLogMapper auditMapper;
    private final SchedulingSnapshotMapper snapshotMapper;
    private final com.orbit.scheduling.capacity.ScheduleWorkerSlotMapper slotMapper;
    private final com.orbit.scheduling.schedule.ScheduleStateService stateService;

    public JobResultWriter(ScheduleJobMapper jobMapper,
                           ScheduleVersionMapper versionMapper,
                           ScheduleVersionEntryMapper entryMapper,
                           ScheduleAuditLogMapper auditMapper,
                           SchedulingSnapshotMapper snapshotMapper,
                           com.orbit.scheduling.capacity.ScheduleWorkerSlotMapper slotMapper,
                           com.orbit.scheduling.schedule.ScheduleStateService stateService) {
        this.jobMapper = jobMapper;
        this.versionMapper = versionMapper;
        this.entryMapper = entryMapper;
        this.auditMapper = auditMapper;
        this.snapshotMapper = snapshotMapper;
        this.slotMapper = slotMapper;
        this.stateService = stateService;
    }

    /** 落库结果。{@code null} 表示**结果被丢弃**（任务已被接管）。 */
    public record Persisted(Long versionId, int versionNo, int entryCount) {
    }

    @Transactional
    public Persisted persistIfStillOwner(long jobId, String jobToken, List<TimetableEntry> entries,
                                         String solverStatus, String modelFingerprint,
                                         Long objectiveValue) {
        ScheduleJobEntity locked = jobMapper.lockForRunning(jobId, jobToken);
        if (locked == null) {
            log.warn("结果被丢弃：任务已被接管或已终结 job={}", jobId);
            return null;
        }

        int versionNo = nextVersionNo(locked.getOrganizationId());
        ScheduleVersion version = new ScheduleVersion();
        version.setOrganizationId(locked.getOrganizationId());
        version.setVersionNo(versionNo);
        version.setName("智能排课方案 V" + versionNo);
        version.setDescription("由排课引擎自动生成，可拖拽调整后发布");
        version.setStatus("draft");
        version.setRevision(1);
        version.setCreatedBy(locked.getCreatedBy());
        version.setCreatedAt(UtcTimes.now());
        version.setUpdatedAt(UtcTimes.now());
        // —— 三个溯源指纹：这版课表是怎么来的，缺一不可 ——
        version.setRuleSetHash(locked.getRuleSetHash());       // 用的哪套规则（P2）
        version.setSnapshotFingerprint(snapshotFingerprintOf(locked));   // 用的哪份输入（P3）
        version.setModelFingerprint(modelFingerprint);                   // 编译出的模型（P3）
        versionMapper.insert(version);

        for (TimetableEntry entry : entries) {
            ScheduleVersionEntry row = new ScheduleVersionEntry();
            row.setOrganizationId(locked.getOrganizationId());
            row.setVersionId(version.getId());
            row.setClassId(entry.classId());
            row.setSubjectId(entry.subjectId());
            row.setTeacherId(entry.teacherId());
            row.setDayOfWeek(entry.dayOfWeek());
            row.setPeriodIndex(entry.periodIndex());
            entryMapper.insert(row);
        }

        ScheduleAuditLog audit = new ScheduleAuditLog();
        audit.setOrganizationId(locked.getOrganizationId());
        audit.setVersionId(version.getId());
        audit.setUserId(locked.getCreatedBy());
        audit.setUserName(ownerNameOf(locked));
        audit.setAction("generated");
        audit.setDetails(Map.of(
                "entry_count", entries.size(),
                "job_id", jobId,
                "attempt", locked.getAttempt() == null ? 0 : locked.getAttempt()));
        audit.setCreatedAt(UtcTimes.now());
        auditMapper.insert(audit);

        // 同一个事务里标记完成 —— 与版本写入原子，避免"版本有了但任务还在跑"
        int affected = jobMapper.complete(jobId, jobToken, solverStatus, modelFingerprint,
                objectiveValue, version.getId(), entries.size(), UtcTimes.now());
        if (affected != 1) {
            // 行还被我们锁着，理论上不可能；真发生了就整体回滚，宁可让 worker 重来
            throw new IllegalStateException("任务完成标记失败（行已锁定）job=" + jobId);
        }
        return new Persisted(version.getId(), versionNo, entries.size());
    }

    /**
     * 收尾：交还求解槽位 + 更新对外可见的组织级状态。
     *
     * <p>⚠ 这两件事**刻意放在事务之外**，而且**必须在 {@link #persistIfStillOwner}
     * 返回非 null 之后调用**。原因：
     * <ul>
     *   <li>槽位是"产能"，交还得越早越好；放在事务里会让它等到提交才释放</li>
     *   <li>状态更新是给前端轮询看的，它不该因为版本落库回滚而跟着回滚
     *       （P1 的 {@code _finish} 也是用独立 session 做的）</li>
     * </ul>
     *
     * <p>⚠⚠ 最初我把这两件事漏了 —— 因为 P1 的旧 runner 里它们写在别处，
     * 搬到任务路径时只搬了"落库"。症状很具体：任务显示 done，但
     * **槽位永远不释放**（排课能力被一点点吃掉）、**前端一直显示"正在排课"**。
     * 实测跑一遍才看出来。
     */
    public void settle(long jobId, Persisted persisted) {
        ScheduleJobEntity job = jobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        slotMapper.releaseByToken(job.getJobToken());
        stateService.finishByOrganization(job.getOrganizationId(), "done",
                "排课完成，已生成草稿 V" + persisted.versionNo() + "，共 "
                        + persisted.entryCount() + " 节课");
    }

    /** 快照指纹存在快照表上，任务表只存 snapshot_id —— 这里是唯一的补查点。 */
    private String snapshotFingerprintOf(ScheduleJobEntity job) {
        if (job.getSnapshotId() == null) {
            return null;
        }
        SchedulingSnapshotEntity snapshot = snapshotMapper.selectById(job.getSnapshotId());
        return snapshot == null ? null : snapshot.getFingerprint();
    }

    private static String ownerNameOf(ScheduleJobEntity job) {
        return "排课任务 #" + job.getId();
    }

    private int nextVersionNo(long organizationId) {
        ScheduleVersion latest = versionMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ScheduleVersion>lambdaQuery()
                        .select(ScheduleVersion::getVersionNo)
                        .eq(ScheduleVersion::getOrganizationId, organizationId)
                        .orderByDesc(ScheduleVersion::getVersionNo)
                        .last("LIMIT 1"));
        return (latest == null || latest.getVersionNo() == null ? 0 : latest.getVersionNo()) + 1;
    }
}
