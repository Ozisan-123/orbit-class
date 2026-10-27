/**
 * 列表接口的统一响应结构（后端 §9.2）。
 *
 * 迁移期的**契约变更**：Python 侧所有列表接口都返回**裸数组**，
 * Java 侧改成 `{items, total}` —— 因为原来的接口完全忽略分页参数
 * （`?page=1&size=1` 也返回全部），前端只能靠 antd 的**前端分页**一次拉全量，
 * 到 160 班规模时会拉几千行。
 *
 * ⚠ 所以「迁移期前端零改动」这个前提已经作废，改成「只做分页适配」
 * （见方案 §7.1 / §9）。`api/index.ts` 里的 `listAll()` 就是这层适配。
 */
export interface Page<T> {
  items: T[];
  /** 满足条件的**总条数**，不是当前页条数。 */
  total: number;
}

export interface Grade {
  id: number;
  name: string;
  periods_per_day: Record<string, number>;
}

export interface Class {
  id: number;
  name: string;
  grade_id: number;
  grade_name?: string;
}

export interface Subject {
  id: number;
  name: string;
  category: string;
}

export interface GradeSubject {
  id: number;
  grade_id: number;
  subject_id: number;
  weekly_hours: number;
  subject_name?: string;
}

export interface Teacher {
  id: number;
  name: string;
  subject_id: number;
  max_weekly_hours: number;
  subject_name?: string;
}

export interface Assignment {
  id: number;
  teacher_id: number;
  class_id: number;
  subject_id: number;
  teacher_name?: string;
  class_name?: string;
  subject_name?: string;
}

export interface ScheduleEntry {
  id: number;
  class_id: number;
  subject_id: number;
  teacher_id: number;
  day_of_week: number;
  period_index: number;
  subject_name?: string;
  teacher_name?: string;
  class_name?: string;
}

export interface ScheduleStatus {
  status: string;
  message?: string;
  schedules?: ScheduleEntry[];
  owner_name?: string;
  started_at?: string;
  finished_at?: string;
}

export interface GradeFixedSlot {
  id: number;
  grade_id: number;
  subject_id: number;
  day_of_week: number;
  period_index: number;
}

export interface ScheduleVersion {
  id: number;
  version_no: number;
  name: string;
  description: string;
  status: 'draft' | 'published' | 'archived';
  revision: number;
  entry_count: number;
  created_by?: number;
  published_by?: number;
  created_at: string;
  updated_at: string;
  published_at?: string;
}

export interface ScheduleAudit {
  id: number;
  user_name: string;
  action: 'generated' | 'updated' | 'copied' | 'moved' | 'swapped' | 'cross_swapped' | 'published';
  details: Record<string, unknown>;
  created_at: string;
}

export interface User {
  id: number;
  organization_id: number;
  organization_name?: string;
  username: string;
  display_name: string;
  role: 'platform_admin' | 'admin' | 'operator';
  is_active: boolean;
  must_change_password: boolean;
  created_at: string;
}

export interface Organization {
  id: number;
  name: string;
  created_at: string;
}

export interface StorageStatus {
  database_type: string;
  database_path: string;
  database_size_bytes: number | null;
  counts: Record<string, number>;
  redis: {
    available: boolean;
    used_memory?: number;
    used_memory_human?: string;
  };
}

/* ------------------------------------------------------------------ 规则系统（P2）
 *
 * 这几个类型是**后端 schema 的镜像**，不是自己设计的：
 *   RuleParamSpec  对应 solver-core 的 RuleParamSpec（一份 schema 三处用：前端表单 / 后端校验 / 助手工具）
 *   RuleType       GET /api/rules/types 的元素
 *   Rule           constraint_rules 表的一行（对外形状）
 * 所以改后端字段就要同步改这里 —— 它们本来就是一回事。
 */

/** 规则参数的声明 —— 前端**按它动态渲染表单**，不用为每条规则写页面。 */
export interface RuleParamSpec {
  name: string;
  type: 'INT' | 'LONG' | 'BOOL' | 'STRING' | 'ENUM';
  required: boolean;
  min?: number | null;
  max?: number | null;
  enum_values: string[];
  label: string;
}

/** 一种规则类型的说明。`mandatory` = 系统不变量/数据驱动，用户碰不到。 */
export interface RuleType {
  rule_type: string;
  display_name: string;
  mandatory: boolean;
  editable: boolean;
  params: RuleParamSpec[];
}

export interface Rule {
  id: number;
  rule_type: string;
  /** 来自 handler 的类型名（当**类型标签**显示）。 */
  handler_display_name: string;
  params: Record<string, unknown>;
  hardness: 'hard' | 'soft';
  weight: number;
  scope: string;
  scope_id?: number | null;
  /** "全校" / "三年级" —— 后端已经翻译好，前端不查表。 */
  scope_name: string;
  enabled: boolean;
  description: string;
  source: string;
  /** 乐观锁；提交编辑/启停/删除时要带回去当 `expected_revision`。 */
  revision: number;
  created_at: string;
  updated_at: string;
}

export interface RuleVersionSnapshot {
  rule_type: string;
  params: Record<string, unknown>;
  hardness: string;
  weight: number;
  scope: string;
  scope_id?: number | null;
  description?: string | null;
}

export interface RuleVersion {
  id: number;
  version_no: number;
  rule_set_hash: string;
  rule_count: number;
  note?: string | null;
  created_at: string;
  /** 只有详情接口返回（列表不返，避免响应过大）。 */
  rules?: RuleVersionSnapshot[] | null;
}

/** 规则集当前状态（`/api/rules/current`）。 */
export interface CurrentRuleSet {
  version_no: number | null;
  rule_set_hash: string | null;
  rule_count: number;
}

export interface RuleTrial {
  valid: boolean;
  /** handler 用当前参数生成的人话描述 —— 用来确认"我配的是我想的意思"。 */
  description: string;
  hardness: string;
  weight: number;
  compiles: boolean;
  /** 不能编译时的原因（如与固定课冲突）。 */
  problem?: string | null;
  /** false = 这条规则对当前模型没有任何影响。 */
  model_changed: boolean;
  model: {
    variables_before: number;
    variables_after: number;
    variable_delta: number;
  };
  note: string;
}

export interface DiagnosisFinding {
  type: string;
  severity: 'error' | 'warning' | 'info';
  message: string;
  payload: Record<string, unknown>;
}

export interface ConflictRule {
  rule_id?: number | null;
  rule_type?: string | null;
  description?: string | null;
}

/** 诊断结果。⚠ `kind` 区分"证明了无解"与"超时未证明" —— 两者的处置完全不同。 */
export interface Diagnosis {
  kind: 'OK' | 'INFEASIBLE' | 'UNKNOWN';
  severity: 'error' | 'warning' | 'info';
  version_id?: number | null;
  job_token?: string | null;
  rule_set_hash?: string | null;
  static_findings: DiagnosisFinding[];
  conflict_rules: ConflictRule[];
  deep: boolean;
  note: string;
  created_at: string;
}

/* ------------------------------------------------------------------ 排课任务（P3）
 *
 * 任务队列让"排课"从"一个后台线程"变成有据可查的东西：
 * 排队位置、进度、重试了几次、被接管过吗、上次为什么失败。
 */

export interface ScheduleJob {
  id: number;
  status: 'pending' | 'running' | 'done' | 'failed' | 'cancelled';
  rule_set_hash: string;
  snapshot_id?: number | null;
  attempt: number;
  max_attempts: number;
  progress: number;
  cancel_requested: boolean;
  worker_id?: string | null;
  last_error?: string | null;
  solver_status?: string | null;
  objective_value?: number | null;
  version_id?: number | null;
  entry_count?: number | null;
  /** 仅 pending 有意义：前面还有几个待办。 */
  queue_position?: number | null;
  /** `running` 但租约已过期 = 持有者大概率死了，等自动接管。 */
  lease_expired: boolean;
  lease_until?: string | null;
  created_at: string;
  started_at?: string | null;
  finished_at?: string | null;
}

export interface JobEvent {
  id: number;
  event: string;
  worker_id?: string | null;
  attempt?: number | null;
  detail?: string | null;
  created_at: string;
}

/* ------------------------------------------------------------------ 空闲查询与换课预览（P5） */

export interface OccupiedBy {
  entry_id: number;
  day_of_week: number;
  period_index: number;
  class_id: number;
  class_name: string;
  subject_id: number;
  subject_name: string;
  teacher_id: number;
  teacher_name: string;
}

export interface AvailabilityResult {
  available: boolean;
  version_id: number;
  resource_type: 'teacher' | 'class';
  resource_id: number;
  day_of_week: number;
  period_index: number;
  occupied_by?: OccupiedBy | null;
}

export interface FreeSlotsResult {
  version_id: number;
  teacher_id: number;
  day_of_week: number;
  periods_in_day: number;
  free_periods: number[];
  occupied_by: OccupiedBy[];
}

export interface SwapRequest {
  source_entry_id: number;
  target_class_id: number;
  target_day_of_week: number;
  target_period_index: number;
  expected_revision: number;
}

export interface SwapPosition {
  class_id: number;
  subject_id: number;
  teacher_id: number;
  day_of_week: number;
  period_index: number;
}

export interface SwapPreview {
  can_swap: boolean;
  affected_entries: Array<{
    entry_id: number;
    before: SwapPosition;
    after: SwapPosition;
  }>;
  conflicts: Array<{ type: string; message: string }>;
  expected_revision: number;
}

/* ------------------------------------------------------------------ 课表质量评分（P5） */

export interface ScoreDimension {
  key: string;
  label: string;
  max_points: number;
  earned_points: number;
  actual: number;
  opportunities: number;
  deduction: number;
  explanation: string;
}

export interface ScoreRuleMetric {
  rule_type: string;
  hardness: 'hard' | 'soft';
  weight: number;
  violations: number;
  opportunities: number;
  satisfaction_percent: number;
  explanation: string;
}

export interface ScheduleScore {
  version_id: number;
  revision: number;
  rule_set_hash: string;
  valid: boolean;
  total_score: number;
  hard_violations: string[];
  dimensions: ScoreDimension[];
  rule_breakdown: ScoreRuleMetric[];
  computed_at: string;
}

export interface ScheduleScoreDiff {
  current: ScheduleScore;
  against: ScheduleScore;
  total_delta: number;
  dimensions: Array<{
    key: string;
    label: string;
    current_points: number;
    against_points: number;
    delta: number;
  }>;
}

/* ------------------------------------------------------------------ 智能助手（P4-P5） */

export interface AssistantConversation {
  id: number;
  title: string;
  created_at: string;
  updated_at: string;
}

export interface AssistantMessage {
  id: number;
  conversation_id: number;
  turn_index: number;
  role: 'user' | 'assistant';
  content: string;
  token_count: number;
  created_at: string;
}

export interface AssistantAction {
  id: number;
  conversation_id: number;
  turn_index: number;
  tool_name: string;
  arguments: Record<string, unknown>;
  result?: Record<string, unknown> | null;
  proposal?: Record<string, unknown> | null;
  status: 'completed' | 'proposed' | 'confirmed' | 'rejected' | 'failed';
  expected_revision?: number | null;
  final_revision?: number | null;
  error_detail?: string | null;
  created_at: string;
  decided_at?: string | null;
}

export interface AssistantUsage {
  used_tokens: number;
  daily_budget: number;
  remaining_tokens: number;
  llm_enabled: boolean;
  mode: 'local' | 'llm';
}

export interface AssistantSseEvent {
  event: 'meta' | 'tool_call' | 'tool_result' | 'proposal' | 'token' | 'done' | 'error';
  data: Record<string, any>;
}
