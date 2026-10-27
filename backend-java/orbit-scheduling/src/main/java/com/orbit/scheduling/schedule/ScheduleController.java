package com.orbit.scheduling.schedule;

import com.orbit.common.error.BusinessException;
import com.orbit.scheduling.security.SchedulePrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 排课接口 —— {@code /api/schedule/generate} 与 {@code /api/schedule/status}。
 *
 * <p>这两个接口是**整套并发控制的门面**：generate 里有 Redis 锁、容量槽位、
 * {@code job_token} 栅栏、CAS 抢状态、进程内后台线程、租约与心跳
 * —— 也就是方案 §3 铁律 1「排课原子性不跨服务」要保住的那一整条链路。
 */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleStateService stateService;
    private final ScheduleRunService runService;

    public ScheduleController(ScheduleStateService stateService, ScheduleRunService runService) {
        this.stateService = stateService;
        this.runService = runService;
    }

    /**
     * 发起排课。
     *
     * <p>请求体在 Python 侧是个**空模型**（{@code class ScheduleGenerateRequest: pass}）——
     * 所有数据都从库里取。所以这里收下 body 但**完全不看**它。
     * 参数声明为可选，是为了不让"前端没带 body"直接变成 422
     * （Python 那边空的 `{}` 也是合法的）。
     */
    @PostMapping("/generate")
    public ScheduleStatusResponse generate(
            @RequestBody(required = false) Map<String, Object> ignoredBody,
            @AuthenticationPrincipal SchedulePrincipal principal) {
        return runService.trigger(requirePrincipal(principal));
    }

    /**
     * 当前排课状态。前端每 1 秒轮询这个接口来驱动进度条
     * （见 {@code frontend/src/pages/ScheduleGenerate.tsx}）。
     */
    @GetMapping("/status")
    public ScheduleStatusResponse status(@AuthenticationPrincipal SchedulePrincipal principal) {
        SchedulePrincipal user = requirePrincipal(principal);
        SchedulingState state = stateService.findByOrganization(user.organizationId());
        // ⚠ 与 Python 的有意偏离：Python 在"没有状态行"时是 500（它假设行一定存在）。
        //    P1 把状态行的初始化从 academic 挪到了 scheduling（见方案 §15），
        //    所以这里必须容忍缺失，返回等价的默认 idle —— 对外可见行为与 Python 一致
        //    （Python 那边返回的也是那行 idle 状态）。
        return state == null ? ScheduleStatusResponse.idleDefault()
                : ScheduleStatusResponse.from(state);
    }

    private static SchedulePrincipal requirePrincipal(SchedulePrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return principal;
    }
}
