package com.orbit.scheduling.job;

import com.orbit.scheduling.diagnosis.DiagnosisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 任务失败时顺手把诊断算清楚并落库。
 *
 * <p>它刻意是一个**很薄的适配层**，而不是把诊断逻辑写进 {@code InternalJobController}：
 * 控制器只该管"协议"，"失败之后要做什么"属于业务编排。
 * 而且这一点将来会变（比如 P4 要在失败后通知助手），变的时候不该动协议。
 *
 * <p>⚠ 只做**浅诊断**（静态检查，毫秒级）。深诊断要解好几次、几十秒，
 * 而此刻任务刚失败、用户可能正盯着列表，不该让它再等 ——
 * 贵的那部分留给用户按需触发（{@code GET /api/schedule/diagnosis?deep=true}）。
 */
@Component
public class DiagnosisHook {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisHook.class);

    private final DiagnosisService diagnosisService;
    private final ScheduleJobMapper jobMapper;

    public DiagnosisHook(DiagnosisService diagnosisService, ScheduleJobMapper jobMapper) {
        this.diagnosisService = diagnosisService;
        this.jobMapper = jobMapper;
    }

    /** 任务以失败收场（或退回队列）后调用。诊断出错不影响任务本身的状态。 */
    public void onJobFailed(long jobId) {
        try {
            ScheduleJobEntity job = jobMapper.selectById(jobId);
            if (job == null) {
                return;
            }
            // ⚠ 带上 solver_status：不然"因为规则太紧而失败、静态检查又没发现问题"的任务
            //   会被诊断成 kind=OK —— 一个失败的任务，诊断却说一切正常。
            diagnosisService.diagnoseWithStatus(job.getOrganizationId(), job.getJobToken(), null,
                    false, job.getSolverStatus());
        } catch (Exception e) {
            log.warn("任务失败后的诊断出错（不影响任务状态）: job={} {}", jobId, e.getMessage());
        }
    }
}
