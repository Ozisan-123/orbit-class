import axios from "axios";
import type {
  Assignment,
  AssistantAction,
  AssistantConversation,
  AssistantMessage,
  AssistantSseEvent,
  AssistantUsage,
  AvailabilityResult,
  Class,
  CurrentRuleSet,
  Diagnosis,
  Grade,
  GradeFixedSlot,
  GradeSubject,
  FreeSlotsResult,
  JobEvent,
  Organization,
  Page,
  Rule,
  RuleTrial,
  RuleType,
  RuleVersion,
  ScheduleAudit,
  ScheduleEntry,
  ScheduleJob,
  ScheduleScore,
  ScheduleScoreDiff,
  ScheduleStatus,
  ScheduleVersion,
  StorageStatus,
  SwapPreview,
  SwapRequest,
  Subject,
  Teacher,
  User,
} from "../types";

const api = axios.create({baseURL:'/api'});

api.interceptors.request.use(config => {
  const token = localStorage.getItem('orbit_token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

api.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401 && !error.config?.url?.includes('/auth/login')) {
      localStorage.removeItem('orbit_token');
      window.dispatchEvent(new Event('orbit:unauthorized'));
    }
    return Promise.reject(error);
  },
);

/* ------------------------------------------------------------------ 分页适配层
 *
 * 迁移期的契约变更：列表接口从「裸数组」改成 `{items, total}`（见 types 里的 Page）。
 * 这一层负责把它**变回数组**，这样所有页面的代码一行都不用改。
 *
 * ⚠ 这是一个**临时**方案，代价要说清楚：
 *   - 它用 `size=200`（后端上限）一次拉全量，所以页面里的 antd 表格仍然是
 *     **前端分页** —— 只是数据来源的写法适配了新契约，并没有真的用上服务端分页。
 *   - 超过 200 条时**会被截断**。所以这里不静默处理：一旦发现 total > items.length
 *     就打一条明确的告警，避免"以为看到了全部"。
 *   - P2 前端改造期要把表格改成真正的服务端分页（把 page/size 交给 antd Table 的
 *     onChange，把 total 传给 pagination.total），那时这个辅助函数就会被逐个替换掉。
 */
const MAX_PAGE_SIZE = 200;

async function listAll<T>(url: string, params: Record<string, unknown> = {}): Promise<T[]> {
  const { data } = await api.get<Page<T>>(url, {
    params: { ...params, size: MAX_PAGE_SIZE },
  });
  if (data.total > data.items.length) {
    // eslint-disable-next-line no-console
    console.warn(
      `[orbit] ${url} 共 ${data.total} 条，只取回前 ${data.items.length} 条` +
      `（迁移期上限 ${MAX_PAGE_SIZE}，P2 改成服务端分页）`,
    );
  }
  return data.items;
}

/* ------------------------------------------------------------------ 认证 */

export const login = (username: string, password: string) =>
  api.post('/auth/login', { username, password }).then(r => r.data);
export const getMe = (): Promise<User> => api.get('/auth/me').then(r => r.data);
export const changePassword = (current_password: string, new_password: string) =>
  api.post('/auth/change-password', { current_password, new_password }).then(r => r.data);

/* ------------------------------------------------------------------ 账号与组织 */

export const getUsers = () => listAll<User>('/users');
export const createUser = (data: any) => api.post('/users', data).then(r => r.data);
export const updateUser = (id: number, data: any) => api.put(`/users/${id}`, data).then(r => r.data);

export const getOrganizations = () => listAll<Organization>('/organizations');
export const createOrganization = (data: any) => api.post('/organizations', data).then(r => r.data);

/* ------------------------------------------------------------------ 系统运维 */

// `/system/storage` 返回的是**对象**不是列表，不涉及分页，保持原样
export const getStorageStatus = (): Promise<StorageStatus> =>
  api.get('/system/storage').then(r => r.data);
export const clearGeneratedSchedules = () =>
  api.delete('/system/storage/schedules', { params: { confirm: 'CLEAR_SCHEDULES' } }).then(r => r.data);
export const compactDatabase = () => api.post('/system/storage/compact').then(r => r.data);

/* ------------------------------------------------------------------ 教务主数据 */

export const getGrades = () => listAll<Grade>('/grades');
export const createGrade = (data: any) => api.post('/grades',data).then(r => r.data);
export const updateGrade = (id: number, data: any) => api.put(`/grades/${id}`, data).then(r => r.data);
export const deleteGrade = (id: number) => api.delete(`/grades/${id}`);

export const getClasses = (gradeId?: number) =>
  listAll<Class>('/classes', gradeId ? { grade_id: gradeId } : {});
export const createClass = (data: any) => api.post('/classes', data).then(r => r.data);
export const updateClass = (id: number, data: any) => api.put(`/classes/${id}`, data).then(r => r.data);
export const deleteClass = (id: number) => api.delete(`/classes/${id}`);

export const getSubjects = () => listAll<Subject>('/subjects');
export const createSubject = (data: any) => api.post('/subjects', data).then(r => r.data);
export const deleteSubject = (id: number) => api.delete(`/subjects/${id}`);

export const getGradeSubjects = (gradeId?: number) =>
  listAll<GradeSubject>('/grade-subjects', gradeId ? { grade_id: gradeId } : {});
export const createGradeSubject = (data: any) => api.post('/grade-subjects', data).then(r => r.data);
// ⚠ /bulk 是**写操作**返回「更新后的状态」，后端返回的是**裸数组**（不分页）
export const updateGradeSubjectsBulk = (data: any) => api.post('/grade-subjects/bulk', data).then(r => r.data);
export const deleteGradeSubject = (id: number) => api.delete(`/grade-subjects/${id}`);

export const getTeachers = () => listAll<Teacher>('/teachers');
export const createTeacher = (data: any) => api.post('/teachers', data).then(r => r.data);
export const updateTeacher = (id: number, data: any) => api.put(`/teachers/${id}`, data).then(r => r.data);
export const deleteTeacher = (id: number) => api.delete(`/teachers/${id}`);

/* ------------------------------------------------------------------ 任教分配 */

export const getAssignments = (teacherId?: number, classId?: number) =>
  listAll<Assignment>('/assignments', {
    teacher_id: teacherId,
    class_id: classId,
  });
export const createAssignment = (data: any) => api.post('/assignments', data).then(r => r.data);
export const autoAssignTeachers = (replaceExisting = false, subjectIds: number[] = []) =>
  api.post('/assignments/auto', { replace_existing: replaceExisting, subject_ids: subjectIds }).then(r => r.data);
// ⚠ PUT 返回的是该教师操作后的分配列表（**裸数组**，写操作不分页）
export const setTeacherClasses = (teacherId: number, classIds: number[]) =>
  api.put(`/assignments/teacher/${teacherId}`, { class_ids: classIds }).then(r => r.data);
export const deleteAssignment = (id: number) => api.delete(`/assignments/${id}`);

/* ------------------------------------------------------------------ 年级固定课 */

export const getGradeFixedSlots = (gradeId?: number) =>
  listAll<GradeFixedSlot>('/fixed-slots/by-grade', gradeId ? { grade_id: gradeId } : {});
export const createGradeFixedSlot = (data: any) => api.post('/fixed-slots/by-grade', data).then(r => r.data);
export const deleteGradeFixedSlot = (id: number) => api.delete(`/fixed-slots/by-grade/${id}`);

/* ------------------------------------------------------------------ 排课 */

export const generateSchedule = () => api.post('/schedule/generate', {}).then(r => r.data);
export const getScheduleStatus = (): Promise<ScheduleStatus> =>
  api.get('/schedule/status').then(r => r.data);

export const getScheduleVersions = () => listAll<ScheduleVersion>('/schedule/versions');
export const updateScheduleVersion = (versionId: number, data: any) =>
  api.patch(`/schedule/versions/${versionId}`, data).then(r => r.data);
export const copyScheduleVersion = (versionId: number) =>
  api.post(`/schedule/versions/${versionId}/copy`).then(r => r.data);
export const publishScheduleVersion = (versionId: number) =>
  api.post(`/schedule/versions/${versionId}/publish`).then(r => r.data);
// ⚠ 审计列表也是**分页列表**（后端改成了 {items,total}），同样走 listAll
export const getScheduleAudit = (versionId: number) =>
  listAll<ScheduleAudit>(`/schedule/versions/${versionId}/audit`);
export const swapScheduleEntry = (versionId: number, data: SwapRequest) =>
  api.post(`/schedule/versions/${versionId}/swap`, data).then(r => r.data);
export const previewScheduleSwap = (versionId: number, data: SwapRequest): Promise<SwapPreview> =>
  api.post(`/schedule/versions/${versionId}/swap/preview`, data).then(r => r.data);

export const getScheduleScore = (versionId: number): Promise<ScheduleScore> =>
  api.get(`/schedule/versions/${versionId}/score`).then(r => r.data);

export const getScheduleScoreDiff = (versionId: number, against: number): Promise<ScheduleScoreDiff> =>
  api.get(`/schedule/versions/${versionId}/score/diff`, { params: { against } }).then(r => r.data);

/* ⚠ 下面三个是**课表内容**，后端刻意**不分页**（一张课表天然有上限，
 *   且页面的 Timetable 组件直接吃数组）—— 保持裸数组，不要套 listAll。 */
export const getClassSchedule = (classId: number, versionId?: number): Promise<ScheduleEntry[]> =>
  api.get(`/schedule/class/${classId}`, { params: versionId ? { version_id: versionId } : {} }).then(r => r.data);
export const getTeacherSchedule = (teacherId: number, versionId?: number): Promise<ScheduleEntry[]> =>
  api.get(`/schedule/teacher/${teacherId}`, { params: versionId ? { version_id: versionId } : {} }).then(r => r.data);
export const getGradeSchedule = (gradeId: number, versionId?: number): Promise<ScheduleEntry[]> =>
  api.get(`/schedule/grade/${gradeId}`, { params: versionId ? { version_id: versionId } : {} }).then(r => r.data);

export const exportSchedule = (versionId?: number) =>
  api.get('/schedule/export', { responseType: 'blob', params: versionId ? { version_id: versionId } : {} }).then(r => {
    const url = window.URL.createObjectURL(new Blob([r.data]));
    const a = document.createElement('a');
    a.href = url;
    a.download = '课表.xlsx';
    a.click();
    window.URL.revokeObjectURL(url);
  });

/* ------------------------------------------------------------------ 规则系统（P2）
 *
 * ⚠ 这一组接口 **Python 侧没有对应物** —— P2 之前约束硬编码在 engine.py 里，用户改不了。
 * 所以它们无法用影子对比验证，判据换成了两条可执行的事实：
 *   ① 用这套规则排课的结果与用 legacy 等价规则集一致（目标值相同）
 *   ② 规则的行为由 solver-core 的反证测试守（每条规则配灵敏度反证）
 */

/** 规则类型清单 —— 驱动"新建规则"表单**动态渲染**。 */
export const getRuleTypes = (configurableOnly = false): Promise<RuleType[]> =>
  api.get('/rules/types', {
    params: configurableOnly ? { configurable_only: true } : {},
  }).then(r => r.data);

/**
 * 规则列表。
 *
 * ⚠ 它**不走 `listAll`（不分页）**：规则数量天然有限（一屏能看完），
 * 不像班级/任教关系那样会长到几千行 —— 后端也是这么设计的（裸数组，不套 PageResponse）。
 * 真到了需要分页的规模再加。
 */
export const getRules = (): Promise<Rule[]> => api.get('/rules').then(r => r.data);

/** 当前生效的规则集（版本号 + 哈希 + 条数）。 */
export const getCurrentRuleSet = (): Promise<CurrentRuleSet> =>
  api.get('/rules/current').then(r => r.data);

export const createRule = (data: any): Promise<Rule> =>
  api.post('/rules', data).then(r => r.data);

export const updateRule = (id: number, data: any): Promise<Rule> =>
  api.put(`/rules/${id}`, data).then(r => r.data);

/** 启停开关 —— 也要带 `expected_revision`（别人可能刚改过它的参数）。 */
export const setRuleEnabled = (id: number, enabled: boolean, expectedRevision: number): Promise<Rule> =>
  api.patch(`/rules/${id}`, { enabled, expected_revision: expectedRevision }).then(r => r.data);

/** ⚠ `expected_revision` 走查询参数（DELETE 带 body 在各客户端上行为不一致）。 */
export const deleteRule = (id: number, expectedRevision: number) =>
  api.delete(`/rules/${id}`, { params: { expected_revision: expectedRevision } }).then(r => r.data);

/** 试算影响 —— 同步版：只评估编译影响（参数语义 / 能否编译 / 模型增量），不重排。 */
export const trialRule = (data: any): Promise<RuleTrial> =>
  api.post('/rules/trial', data).then(r => r.data);

export const getRuleVersions = (): Promise<RuleVersion[]> =>
  api.get('/rules/versions').then(r => r.data);

export const getRuleVersion = (id: number): Promise<RuleVersion> =>
  api.get(`/rules/versions/${id}`).then(r => r.data);

/**
 * 排课可行性诊断。
 *
 * 默认只做静态检查（毫秒级）；`deep=true` 做最小冲突集分析（要解几次）。
 * 不带 `deep` 时后端返回**最近一次落库的诊断**，没有就现算一次静态检查。
 */
export const getDiagnosis = (deep = false): Promise<Diagnosis> =>
  api.get('/schedule/diagnosis', { params: deep ? { deep: true } : {} }).then(r => r.data);

/* ------------------------------------------------------------------ 排课任务（P3） */

/**
 * 任务列表（分页）。
 *
 * ⚠ 这是**真正的服务端分页**（不像规则列表那样天然有限）：
 * 任务表每次排课加一行，还带事件流，会长大。所以它走 `listAll` 之外的路径 ——
 * 直接用后端的 `{items,total}`，让 antd Table 把 page/size 交给服务端。
 */
export const getJobs = (params: { status?: string; page?: number; size?: number } = {}) =>
  api.get('/schedule/jobs', { params }).then(r => r.data);

export const getJob = (jobId: number): Promise<ScheduleJob> =>
  api.get(`/schedule/jobs/${jobId}`).then(r => r.data);

/** 任务事件时间线（启动/领单/心跳/完成/失败/接管/取消）。 */
export const getJobEvents = (jobId: number): Promise<JobEvent[]> =>
  api.get(`/schedule/jobs/${jobId}/events`).then(r => r.data);

/** 重试 —— ⚠ 只对**失败**或**已取消**的任务有效，其它状态后端会 400。 */
export const retryJob = (jobId: number): Promise<ScheduleJob> =>
  api.post(`/schedule/jobs/${jobId}/retry`).then(r => r.data);

/** 取消 —— pending 会立刻终结；running 只打标记，由 worker 在心跳时收手。 */
export const cancelJob = (jobId: number): Promise<ScheduleJob> =>
  api.post(`/schedule/jobs/${jobId}/cancel`).then(r => r.data);

/* ------------------------------------------------------------------ 空闲查询（P5） */

export const getTeacherAvailability = (
  teacherId: number, versionId: number, day: number, period: number,
): Promise<AvailabilityResult> => api.get(`/availability/teacher/${teacherId}`, {
  params: { version_id: versionId, day, period },
}).then(r => r.data);

export const getClassAvailability = (
  classId: number, versionId: number, day: number, period: number,
): Promise<AvailabilityResult> => api.get(`/availability/class/${classId}`, {
  params: { version_id: versionId, day, period },
}).then(r => r.data);

export const getTeacherFreeSlots = (
  teacherId: number, versionId: number, day: number,
): Promise<FreeSlotsResult> => api.get(`/availability/teacher/${teacherId}/free-slots`, {
  params: { version_id: versionId, day },
}).then(r => r.data);

/* ------------------------------------------------------------------ 智能助手（P4-P5） */

export const getAssistantConversations = (): Promise<AssistantConversation[]> =>
  api.get('/assistant/conversations').then(r => r.data);

export const getAssistantMessages = (conversationId: number): Promise<AssistantMessage[]> =>
  api.get(`/assistant/conversations/${conversationId}/messages`).then(r => r.data);

export const deleteAssistantConversation = (conversationId: number) =>
  api.delete(`/assistant/conversations/${conversationId}`).then(r => r.data);

export const getAssistantActions = (conversationId?: number): Promise<AssistantAction[]> =>
  api.get('/assistant/actions', { params: conversationId ? { conversation_id: conversationId } : {} }).then(r => r.data);

export const getAssistantUsage = (): Promise<AssistantUsage> =>
  api.get('/assistant/usage').then(r => r.data);

export const confirmAssistantAction = (actionId: number): Promise<AssistantAction> =>
  api.post(`/assistant/actions/${actionId}/confirm`).then(r => r.data);

export const rejectAssistantAction = (actionId: number): Promise<AssistantAction> =>
  api.post(`/assistant/actions/${actionId}/reject`).then(r => r.data);

export const streamAssistantChat = async (
  payload: { conversation_id?: number; message: string; tool?: string; arguments?: Record<string, unknown> },
  onEvent: (event: AssistantSseEvent) => void,
) => {
  const response = await fetch('/api/assistant/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${localStorage.getItem('orbit_token') ?? ''}`,
    },
    body: JSON.stringify(payload),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({ detail: '助手请求失败' }));
    throw new Error(error.detail ?? '助手请求失败');
  }
  if (!response.body) throw new Error('浏览器不支持流式响应');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n');
    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      let eventName = 'message';
      const dataLines: string[] = [];
      block.split('\n').forEach(line => {
        if (line.startsWith('event:')) eventName = line.slice(6).trim();
        if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart());
      });
      if (dataLines.length) {
        onEvent({ event: eventName, data: JSON.parse(dataLines.join('\n')) } as AssistantSseEvent);
      }
      boundary = buffer.indexOf('\n\n');
    }
    if (done) break;
  }
};
