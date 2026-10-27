package com.orbit.academic.subject;

import com.orbit.academic.auth.OrbitUserPrincipal;
import com.orbit.common.dto.PageQuery;
import com.orbit.common.dto.PageResponse;
import com.orbit.common.error.BusinessException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 科目接口 —— {@code /api/subjects/*}，对应 Python 的 {@code routers/subjects.py}（3 个）。
 *
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>Python</th><th>这里</th></tr>
 *   <tr><td>GET</td><td>/api/subjects</td><td>裸数组</td><td><b>{items, total}</b>（§9.2 新增分页）</td></tr>
 *   <tr><td>POST</td><td>/api/subjects</td><td>200 SubjectOut</td><td>同</td></tr>
 *   <tr><td>DELETE</td><td>/api/subjects/{id}</td><td>200 {"ok":true}</td><td>同</td></tr>
 * </table>
 *
 * <p>注意 Python 的 {@code @router.post} **没有指定 status_code**，
 * FastAPI 的默认值是 **200**（不是 201）。Spring 的 {@code @PostMapping} 默认也是 200 ✓
 * —— 两边一致，不用额外处理。但这个「默认值恰好相同」值得记一笔，
 * 将来如果有接口用了 201，就必须显式写 {@code @ResponseStatus}。
 */
@RestController
@RequestMapping("/api/subjects")
public class SubjectController {

    private final SubjectService subjectService;

    public SubjectController(SubjectService subjectService) {
        this.subjectService = subjectService;
    }

    /**
     * 列表。
     *
     * <p>{@link PageQuery} 会被 Spring 当作 {@code @ModelAttribute} 做**构造器绑定**
     * （record 的紧凑构造器负责归一化：page/size 缺省、size 上限、order 合法性）。
     * 没传任何参数时 {@code page=0,size=0} 由原始类型默认值给出，然后被归一化成 1/20。
     */
    @GetMapping
    public PageResponse<SubjectResponse> list(PageQuery query,
                                             @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return subjectService.list(requirePrincipal(principal).organizationId(), query);
    }

    @PostMapping
    public SubjectResponse create(@Valid @RequestBody SubjectCreateRequest request,
                                  @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return subjectService.create(requirePrincipal(principal).organizationId(), request);
    }

    @DeleteMapping("/{subjectId}")
    public Map<String, Object> delete(@PathVariable Long subjectId,
                                      @AuthenticationPrincipal OrbitUserPrincipal principal) {
        subjectService.delete(requirePrincipal(principal).organizationId(), subjectId);
        return Map.of("ok", true);
    }

    /**
     * 正常情况下到不了这里（授权层已保证 authenticated）。
     *
     * <p>但 {@code @AuthenticationPrincipal} 在异常场景下确实可能为 null，
     * 那时应该给 401 而不是让 NullPointerException 变成 500。
     * 这段小检查每个 Controller 都要有 —— 与其复制，不如放在
     * {@code OrbitUserPrincipal} 的静态方法里（见 {@code requirePrincipal}）。
     */
    private static OrbitUserPrincipal requirePrincipal(OrbitUserPrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return principal;
    }
}
