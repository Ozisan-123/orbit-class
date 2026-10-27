package com.orbit.scheduling.rule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.orbit.common.error.BusinessException;
import com.orbit.common.util.UtcTimes;
import com.orbit.solver.core.compile.RuleSets;
import com.orbit.solver.core.rule.ConstraintRule;
import com.orbit.solver.core.rule.RuleParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则集服务 —— 「一组规则」作为一个整体的读写、哈希与版本（P2 的中枢）。
 *
 * <h2>三件事</h2>
 * <ol>
 *   <li><b>载入</b>：给求解用的 {@code List<ConstraintRule>}（只含 enabled）</li>
 *   <li><b>哈希</b>：把一组规则规范化成一个确定性的 {@code rule_set_hash}</li>
 *   <li><b>版本</b>：哈希变了就记一条 {@code constraint_rule_versions}，并冻结快照</li>
 * </ol>
 *
 * <h2>{@code rule_set_hash} 为什么必须确定性</h2>
 * 同一个规则集**任何时候算出来必须是同一个值**，否则：
 * <ul>
 *   <li>「这版课表用的哪套规则」对不上（当前 hash 找不到历史版本）</li>
 *   <li>规则改回去之后会被当成"新规则集"，历史版本无限膨胀</li>
 * </ul>
 * 所以规范化规则是：
 * <pre>
 *   每行 → rule_type|hardness|weight|scope|scope_id|&lt;params 的键排序 JSON&gt;
 *   全部行**按这个字符串排序**后换行拼接 → sha256 → 小写 hex
 * </pre>
 * <b>刻意不含</b> {@code id}（删掉再建一条一样的规则，应当被认作"没变"）、
 * {@code description}（纯展示）、{@code enabled}（哈希只覆盖 enabled 的行，
 * 所以启停会自然地改变哈希）。
 *
 * <h2>首次访问自动初始化</h2>
 * 新组织的规则是懒初始化的：写入两条**与迁移前 Python 硬编码约束语义等价**的软规则
 * （{@link RuleSets#legacyEquivalent()}）。这样"新组织第一次排课"的行为
 * 与 P1（用 legacy 等价规则集）**完全一致**，而不是悄悄变成"只剩不变量"。
 *
 * <p>它同时解决了"以后新建的组织怎么办"—— 不需要在 academic 建组织时跨服务写规则
 * （那就违反铁律 2 了）。
 */
@Service
public class RuleSetService {

    private static final Logger log = LoggerFactory.getLogger(RuleSetService.class);

    /** 规范化哈希专用的 ObjectMapper：**Map 键排序**，输出才可能与输入顺序无关。 */
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    /** 首次初始化时写入的默认规则说明（会显示在规则列表里，用户可改可删）。 */
    public static final String DEFAULT_NOTE = "系统默认：与迁移前的硬编码约束等价";

    private final ConstraintRuleMapper ruleMapper;
    private final ConstraintRuleVersionMapper versionMapper;
    private final RuleTypeCatalog catalog;

    public RuleSetService(ConstraintRuleMapper ruleMapper,
                          ConstraintRuleVersionMapper versionMapper,
                          RuleTypeCatalog catalog) {
        this.ruleMapper = ruleMapper;
        this.versionMapper = versionMapper;
        this.catalog = catalog;
    }

    // ------------------------------------------------------------------ 载入

    /**
     * 载入某组织**生效**的规则（给求解用）。
     *
     * <p>调它会顺带完成首次初始化 —— 因为求解是"必须有一组规则"的场景，
     * 让它自己保证这个前提，比要求每个调用方先记得调一次初始化可靠。
     */
    public List<ConstraintRule> loadForSolve(long organizationId) {
        ensureInitialized(organizationId);
        return toConstraintRules(enabledRows(organizationId));
    }

    /** 某组织全部规则（含停用的），按 id 升序。 */
    public List<ConstraintRuleEntity> listAll(long organizationId) {
        return ruleMapper.selectList(Wrappers.<ConstraintRuleEntity>lambdaQuery()
                .eq(ConstraintRuleEntity::getOrganizationId, organizationId)
                .orderByAsc(ConstraintRuleEntity::getId));
    }

    private List<ConstraintRuleEntity> enabledRows(long organizationId) {
        return ruleMapper.selectList(Wrappers.<ConstraintRuleEntity>lambdaQuery()
                .eq(ConstraintRuleEntity::getOrganizationId, organizationId)
                .eq(ConstraintRuleEntity::getEnabled, true)
                .orderByAsc(ConstraintRuleEntity::getId));
    }

    private static List<ConstraintRule> toConstraintRules(List<ConstraintRuleEntity> rows) {
        List<ConstraintRule> out = new ArrayList<>(rows.size());
        for (ConstraintRuleEntity row : rows) {
            out.add(new ConstraintRule(
                    row.getRuleType(),
                    RuleParams.of(row.getParams()),
                    "hard".equalsIgnoreCase(row.getHardness())
                            ? ConstraintRule.Hardness.HARD : ConstraintRule.Hardness.SOFT,
                    row.getWeight() == null ? 1 : row.getWeight(),
                    true,
                    row.getDescription()));
        }
        return out;
    }

    // ------------------------------------------------------------------ 哈希

    /** 规则集的规范化哈希（只覆盖 enabled 的行）。 */
    public String hashOf(long organizationId) {
        return canonicalHash(enabledRows(organizationId));
    }

    static String canonicalHash(List<ConstraintRuleEntity> enabledRows) {
        List<String> canonicals = new ArrayList<>(enabledRows.size());
        for (ConstraintRuleEntity row : enabledRows) {
            canonicals.add(canonicalOf(row));
        }
        // 排序 → 与数据库返回顺序无关；单条规则内部也做了键排序
        canonicals.sort(String::compareTo);
        return sha256Hex(String.join("\n", canonicals));
    }

    private static String canonicalOf(ConstraintRuleEntity row) {
        return String.join("|",
                row.getRuleType() == null ? "" : row.getRuleType(),
                row.getHardness() == null ? "" : row.getHardness(),
                String.valueOf(row.getWeight() == null ? 1 : row.getWeight()),
                row.getScope() == null ? "" : row.getScope(),
                row.getScopeId() == null ? "" : String.valueOf(row.getScopeId()),
                canonicalJson(row.getParams()));
    }

    /** Map 键排序后的紧凑 JSON（无空格），保证不含格式差异。 */
    private static String canonicalJson(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        try {
            JsonNode node = CANONICAL.valueToTree(params);
            return CANONICAL.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规则参数无法规范化: " + params, e);
        }
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    // ------------------------------------------------------------------ 版本

    /**
     * 记一次规则集版本：哈希已存在就**不新建**（改回去等于没改）。
     *
     * <p>调用时机是规则表任何一次成功变更之后。
     *
     * @param note 人话说明，例如"新建规则：语文一天最多 2 节"
     * @return 当前生效的版本行
     */
    @Transactional
    public ConstraintRuleVersionEntity recordVersion(long organizationId, String note) {
        List<ConstraintRuleEntity> enabled = enabledRows(organizationId);
        String hash = canonicalHash(enabled);

        ConstraintRuleVersionEntity existing = findByHash(organizationId, hash);
        if (existing != null) {
            return existing;
        }
        return insertVersion(organizationId, enabled, hash, note);
    }

    /**
     * 当前生效的规则集版本。
     *
     * <p>⚠ **按哈希找，不按 version_no 找最大的那个**。因为版本是哈希去重的：
     * 把规则改回去（例如把一条规则停用、或把参数改回原值）会让规则集**回到一个更早的版本**，
     * 此时"当前版本"就是那个**编号更小**的版本。
     *
     * <p>实测过这个坑：停用一条规则后，规则集与 v1 完全相同，
     * 但按 {@code ORDER BY version_no DESC} 会报成 v3 —— 等于告诉用户
     * "你现在用的是 v3"，而 v3 的规则内容根本不是当前生效的那一套。
     */
    public ConstraintRuleVersionEntity currentVersion(long organizationId) {
        String hash = hashOf(organizationId);
        return findByHash(organizationId, hash);
    }

    public List<ConstraintRuleVersionEntity> listVersions(long organizationId) {
        return versionMapper.selectList(Wrappers.<ConstraintRuleVersionEntity>lambdaQuery()
                .eq(ConstraintRuleVersionEntity::getOrganizationId, organizationId)
                .orderByDesc(ConstraintRuleVersionEntity::getVersionNo));
    }

    public ConstraintRuleVersionEntity requireVersion(long organizationId, long versionId) {
        ConstraintRuleVersionEntity found = versionMapper.selectOne(
                Wrappers.<ConstraintRuleVersionEntity>lambdaQuery()
                        .eq(ConstraintRuleVersionEntity::getId, versionId)
                        .eq(ConstraintRuleVersionEntity::getOrganizationId, organizationId));
        if (found == null) {
            throw BusinessException.notFound("规则集版本不存在");
        }
        return found;
    }

    private ConstraintRuleVersionEntity findByHash(long organizationId, String hash) {
        return versionMapper.selectOne(Wrappers.<ConstraintRuleVersionEntity>lambdaQuery()
                .eq(ConstraintRuleVersionEntity::getOrganizationId, organizationId)
                .eq(ConstraintRuleVersionEntity::getRuleSetHash, hash)
                .last("LIMIT 1"));
    }

    /**
     * 按指纹取回**当时冻结的那份规则**（P3 的任务队列要用）。
     *
     * <p>为什么不能直接用"当前规则集"：任务可能**排队几分钟**才被 worker 领走，
     * 期间管理员完全可能改了规则。那样排出来的课表用的就不是任务创建时那一套，
     * 而 {@code rule_set_hash} 却记的是旧指纹 —— 审计链会自相矛盾。
     *
     * <p>这件事之所以只要三行代码，是因为 P2 的时候就把**整份规则快照**冻进了
     * {@code constraint_rule_versions.rules}，而不是只存了个 hash。
     * （当时的理由是"审计链要求这版课表用的哪套规则任何时候都能点开"，
     * 到了 P3 它顺带解决了"排队期间规则被改"的问题。）
     */
    @SuppressWarnings("unchecked")
    public List<ConstraintRule> rulesByHash(long organizationId, String hash) {
        ConstraintRuleVersionEntity version = findByHash(organizationId, hash);
        if (version == null || version.getRules() == null) {
            throw BusinessException.conflict(
                    "规则集版本已不存在（指纹 " + hash + "）—— 该任务无法用当时的规则复现");
        }
        List<ConstraintRule> out = new ArrayList<>(version.getRules().size());
        for (Map<String, Object> item : version.getRules()) {
            Object params = item.get("params");
            Object weight = item.get("weight");
            out.add(new ConstraintRule(
                    String.valueOf(item.get("rule_type")),
                    RuleParams.of(params instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of()),
                    "hard".equalsIgnoreCase(String.valueOf(item.get("hardness")))
                            ? ConstraintRule.Hardness.HARD : ConstraintRule.Hardness.SOFT,
                    weight instanceof Number n ? n.intValue() : 1,
                    true,
                    item.get("description") == null ? null : String.valueOf(item.get("description"))));
        }
        return out;
    }

    private ConstraintRuleVersionEntity insertVersion(long organizationId,
                                                      List<ConstraintRuleEntity> enabled,
                                                      String hash, String note) {
        Integer maxNo = versionMapper.selectList(Wrappers.<ConstraintRuleVersionEntity>lambdaQuery()
                        .select(ConstraintRuleVersionEntity::getVersionNo)
                        .eq(ConstraintRuleVersionEntity::getOrganizationId, organizationId))
                .stream()
                .map(ConstraintRuleVersionEntity::getVersionNo)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);

        ConstraintRuleVersionEntity version = new ConstraintRuleVersionEntity();
        version.setOrganizationId(organizationId);
        version.setVersionNo(maxNo + 1);
        version.setRuleSetHash(hash);
        version.setRules(snapshotOf(enabled));
        version.setRuleCount(enabled.size());
        version.setNote(note);
        version.setCreatedAt(UtcTimes.now());
        versionMapper.insert(version);
        log.info("规则集版本 +1: org={} v{} hash={} rules={} note={}",
                organizationId, version.getVersionNo(), hash.substring(0, 12), enabled.size(), note);
        return version;
    }

    /** 冻结快照：只放语义字段，不放 id/时间戳（那些不该进审计快照的正文）。 */
    private static List<Map<String, Object>> snapshotOf(List<ConstraintRuleEntity> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ConstraintRuleEntity row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rule_type", row.getRuleType());
            item.put("params", row.getParams() == null ? Map.of() : row.getParams());
            item.put("hardness", row.getHardness());
            item.put("weight", row.getWeight());
            item.put("scope", row.getScope());
            item.put("scope_id", row.getScopeId());
            item.put("description", row.getDescription());
            out.add(item);
        }
        return out;
    }

    // ------------------------------------------------------------------ 首次初始化

    /**
     * 首次访问时写入默认规则（幂等）。
     *
     * <p>两条默认规则来自 {@link RuleSets#legacyEquivalent()} —— 也就是 P1 迁移期
     * 用来复现 Python 行为的那套。**这样 P2 上线不会改变任何一所学校的排课结果**：
     * 新组织第一次排课用的还是那两条软约束，只是它们现在**看得见、改得动**了。
     *
     * <p>并发安全见 {@link ConstraintRuleMapper#lockOrganizationRules}。
     */
    @Transactional
    public void ensureInitialized(long organizationId) {
        // 先加间隙锁：并发的首次访问会在这里排队，而不是各插一份默认规则
        ruleMapper.lockOrganizationRules(organizationId);

        if (currentVersion(organizationId) != null) {
            return;   // 已经初始化过（有版本行 = 规则集存在过）
        }

        List<ConstraintRule> defaults = RuleSets.legacyEquivalent();
        for (ConstraintRule rule : defaults) {
            ConstraintRuleEntity entity = new ConstraintRuleEntity();
            entity.setOrganizationId(organizationId);
            entity.setRuleType(rule.type());
            entity.setParams(new LinkedHashMap<>(rule.params().values()));
            entity.setHardness(rule.isSoft() ? "soft" : "hard");
            entity.setWeight(rule.effectiveWeight());
            entity.setScope("org");
            entity.setScopeId(null);
            entity.setEnabled(true);
            entity.setDescription(rule.description());
            entity.setSource("manual");
            entity.setVersion(1);
            entity.setCreatedAt(UtcTimes.now());
            entity.setUpdatedAt(UtcTimes.now());
            ruleMapper.insert(entity);
        }
        log.info("首次初始化规则集: org={} 写入 {} 条默认规则", organizationId, defaults.size());
        insertVersion(organizationId, enabledRows(organizationId),
                canonicalHash(enabledRows(organizationId)), DEFAULT_NOTE);
    }

    // ------------------------------------------------------------------ 校验

    /**
     * 校验参数是否合法（复用 solver-core 的 {@code RuleParams.validate}）。
     *
     * <p>**不在这里实现第二套校验逻辑**：{@code RuleParamSpec} 是前端表单、后端校验、
     * 助手工具定义三处共用的同一份 schema，校验规则也必须只有一份。
     */
    public void validateParams(String ruleType, Map<String, Object> params) {
        if (!catalog.isKnown(ruleType)) {
            throw BusinessException.badRequest("未知的规则类型: " + ruleType);
        }
        List<String> errors = RuleParams.of(params).validate(catalog.handlerOf(ruleType).params());
        if (!errors.isEmpty()) {
            throw BusinessException.badRequest(String.join("；", errors));
        }
    }

    /** 不变量规则不允许写进规则表（它们始终生效，写成数据行只会产生"看起来能关掉"的假象）。 */
    public void rejectMandatory(String ruleType) {
        if (catalog.isMandatory(ruleType)) {
            throw BusinessException.badRequest(
                    "该规则是系统不变量，始终生效且不可配置: " + ruleType);
        }
    }
}
