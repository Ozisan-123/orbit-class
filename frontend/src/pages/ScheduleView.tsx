import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert, Button, Card, Drawer, Empty, Form, Input, Modal, Segmented, Select,
  Space, Spin, Switch, Tabs, Tag, Timeline, Tooltip, Typography, message,
} from 'antd';
import {
  CopyOutlined, DownloadOutlined, EditOutlined, HistoryOutlined,
  ReloadOutlined, SendOutlined, SwapOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import * as api from '../api';
import type {
  Class, Grade, ScheduleAudit, ScheduleEntry, ScheduleVersion, SwapPreview, SwapRequest, Teacher, User,
} from '../types';
import Timetable from '../components/Timetable';
import AvailabilityPanel from '../components/AvailabilityPanel';
import SwapPreviewModal from '../components/SwapPreviewModal';
import QualityScoreCard from '../components/QualityScoreCard';

const DAY_KEYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri'];
const STATUS_LABEL = { draft: '草稿', published: '已发布', archived: '历史版本' };
const STATUS_COLOR = { draft: 'processing', published: 'success', archived: 'default' } as const;
const ACTION_LABEL: Record<string, string> = {
  generated: '生成了排课草稿', updated: '修改了方案信息', copied: '复制了一个新草稿',
  moved: '移动了课程', swapped: '交换了课程', cross_swapped: '完成了跨班协同换时', published: '发布了课表',
};

const errorText = (error: any, fallback: string) => error?.response?.data?.detail ?? fallback;

const ScheduleView: React.FC<{ user: User }> = ({ user }) => {
  const [grades, setGrades] = useState<Grade[]>([]);
  const [classes, setClasses] = useState<Class[]>([]);
  const [teachers, setTeachers] = useState<Teacher[]>([]);
  const [versions, setVersions] = useState<ScheduleVersion[]>([]);
  const [selectedVersionId, setSelectedVersionId] = useState<number>();
  const [selectedClassId, setSelectedClassId] = useState<number>();
  const [compareClassId, setCompareClassId] = useState<number>();
  const [selectedTeacherId, setSelectedTeacherId] = useState<number>();
  const [selectedGradeId, setSelectedGradeId] = useState<number>();
  const [classEntries, setClassEntries] = useState<ScheduleEntry[]>([]);
  const [compareEntries, setCompareEntries] = useState<ScheduleEntry[]>([]);
  const [teacherEntries, setTeacherEntries] = useState<ScheduleEntry[]>([]);
  const [gradeEntries, setGradeEntries] = useState<ScheduleEntry[]>([]);
  const [activeTab, setActiveTab] = useState('class');
  const [loading, setLoading] = useState(true);
  const [moving, setMoving] = useState(false);
  const [swapPreview, setSwapPreview] = useState<SwapPreview | null>(null);
  const [pendingSwap, setPendingSwap] = useState<{ versionId: number; request: SwapRequest } | null>(null);
  const [confirmingSwap, setConfirmingSwap] = useState(false);
  const [showTeacher, setShowTeacher] = useState(true);
  const [colorize, setColorize] = useState(true);
  const [compact, setCompact] = useState(false);
  const [editMode, setEditMode] = useState(true);
  const [classViewMode, setClassViewMode] = useState<'single' | 'dual'>('dual');
  const [metaOpen, setMetaOpen] = useState(false);
  const [auditOpen, setAuditOpen] = useState(false);
  const [audits, setAudits] = useState<ScheduleAudit[]>([]);
  const [metaForm] = Form.useForm();

  const selectedVersion = versions.find(item => item.id === selectedVersionId);
  const selectedClass = classes.find(item => item.id === selectedClassId);
  const classGrade = grades.find(item => item.id === selectedClass?.grade_id);
  const classPeriods = classGrade
    ? DAY_KEYS.map(day => classGrade.periods_per_day[day] ?? 6)
    : [6, 6, 6, 6, 6];
  const compareClass = classes.find(item => item.id === compareClassId);
  const compareGrade = grades.find(item => item.id === compareClass?.grade_id);
  const comparePeriods = compareGrade
    ? DAY_KEYS.map(day => compareGrade.periods_per_day[day] ?? 6)
    : classPeriods;
  const comparableClasses = classes.filter(item => item.grade_id === selectedClass?.grade_id && item.id !== selectedClassId);

  const reloadVersions = useCallback(async (preferredId?: number) => {
    const data: ScheduleVersion[] = await api.getScheduleVersions();
    setVersions(data);
    setSelectedVersionId(current => {
      if (preferredId && data.some(item => item.id === preferredId)) return preferredId;
      if (current && data.some(item => item.id === current)) return current;
      return data.find(item => item.status === 'draft')?.id
        ?? data.find(item => item.status === 'published')?.id
        ?? data[0]?.id;
    });
  }, []);

  useEffect(() => {
    Promise.all([api.getGrades(), api.getClasses(), api.getTeachers(), api.getScheduleVersions()])
      .then(([gradeData, classData, teacherData, versionData]) => {
        setGrades(gradeData);
        setClasses(classData);
        setTeachers(teacherData);
        setVersions(versionData);
        setSelectedClassId(classData[0]?.id);
        setCompareClassId(classData.find((item: Class) => item.grade_id === classData[0]?.grade_id && item.id !== classData[0]?.id)?.id);
        setSelectedTeacherId(teacherData[0]?.id);
        setSelectedGradeId(gradeData[0]?.id);
        setSelectedVersionId(
          versionData.find((item: ScheduleVersion) => item.status === 'draft')?.id
          ?? versionData.find((item: ScheduleVersion) => item.status === 'published')?.id
          ?? versionData[0]?.id,
        );
      })
      .catch(() => message.error('课表数据加载失败'))
      .finally(() => setLoading(false));
  }, []);

  const loadClass = useCallback(async () => {
    if (!selectedClassId || !selectedVersionId) return;
    setClassEntries(await api.getClassSchedule(selectedClassId, selectedVersionId));
  }, [selectedClassId, selectedVersionId]);

  const loadCompareClass = useCallback(async () => {
    if (!compareClassId || !selectedVersionId) {
      setCompareEntries([]);
      return;
    }
    setCompareEntries(await api.getClassSchedule(compareClassId, selectedVersionId));
  }, [compareClassId, selectedVersionId]);

  useEffect(() => { loadClass().catch(() => message.error('班级课表加载失败')); }, [loadClass]);
  useEffect(() => { loadCompareClass().catch(() => message.error('对照班级课表加载失败')); }, [loadCompareClass]);
  useEffect(() => {
    if (!selectedClass) return;
    if (!compareClass || compareClass.grade_id !== selectedClass.grade_id || compareClass.id === selectedClass.id) {
      setCompareClassId(classes.find(item => item.grade_id === selectedClass.grade_id && item.id !== selectedClass.id)?.id);
    }
  }, [classes, compareClass, selectedClass]);
  useEffect(() => {
    if (activeTab === 'teacher' && selectedTeacherId && selectedVersionId) {
      api.getTeacherSchedule(selectedTeacherId, selectedVersionId).then(setTeacherEntries);
    }
  }, [activeTab, selectedTeacherId, selectedVersionId]);
  useEffect(() => {
    if (activeTab === 'grade' && selectedGradeId && selectedVersionId) {
      api.getGradeSchedule(selectedGradeId, selectedVersionId).then(setGradeEntries);
    }
  }, [activeTab, selectedGradeId, selectedVersionId]);

  useEffect(() => {
    setEditMode(selectedVersion?.status === 'draft');
  }, [selectedVersion?.status]);

  const replaceVersion = (updated: ScheduleVersion) => {
    setVersions(current => current.map(item => item.id === updated.id ? updated : item));
  };

  const onMove = async (entryId: number, day: number, period: number, targetClassId?: number) => {
    if (!selectedVersion || !targetClassId) return;
    setMoving(true);
    try {
      const request: SwapRequest = {
        source_entry_id: entryId,
        target_class_id: targetClassId,
        target_day_of_week: day,
        target_period_index: period,
        expected_revision: selectedVersion.revision,
      };
      const preview = await api.previewScheduleSwap(selectedVersion.id, request);
      setPendingSwap({ versionId: selectedVersion.id, request });
      setSwapPreview(preview);
    } catch (error) {
      message.error(errorText(error, '课程调整预览失败'));
    } finally {
      setMoving(false);
    }
  };

  const confirmSwap = async () => {
    if (!pendingSwap || !swapPreview?.can_swap) return;
    setConfirmingSwap(true);
    try {
      const updated = await api.swapScheduleEntry(pendingSwap.versionId, pendingSwap.request);
      replaceVersion(updated);
      setSwapPreview(null);
      setPendingSwap(null);
      await Promise.all([loadClass(), loadCompareClass()]);
      message.success('课程位置已保存');
    } catch (error) {
      message.error(errorText(error, '课程调整失败'));
      setSwapPreview(null);
      setPendingSwap(null);
      await Promise.all([reloadVersions(pendingSwap.versionId), loadClass(), loadCompareClass()]);
    } finally {
      setConfirmingSwap(false);
    }
  };

  const openMeta = () => {
    if (!selectedVersion) return;
    metaForm.setFieldsValue({ name: selectedVersion.name, description: selectedVersion.description });
    setMetaOpen(true);
  };

  const saveMeta = async () => {
    if (!selectedVersion) return;
    const values = await metaForm.validateFields();
    try {
      const updated = await api.updateScheduleVersion(selectedVersion.id, {
        ...values, expected_revision: selectedVersion.revision,
      });
      replaceVersion(updated);
      setMetaOpen(false);
      message.success('方案信息已保存');
    } catch (error) {
      message.error(errorText(error, '保存失败'));
    }
  };

  const copyVersion = async () => {
    if (!selectedVersion) return;
    try {
      const copied = await api.copyScheduleVersion(selectedVersion.id);
      await reloadVersions(copied.id);
      setEditMode(true);
      message.success('已复制为可编辑草稿');
    } catch (error) {
      message.error(errorText(error, '复制失败'));
    }
  };

  const publishVersion = () => {
    if (!selectedVersion) return;
    Modal.confirm({
      title: `发布“${selectedVersion.name}”？`,
      content: '发布前会再次检查教师冲突；当前正式课表将自动归档，可随时复制恢复。',
      okText: '确认发布',
      cancelText: '再检查一下',
      onOk: async () => {
        try {
          await api.publishScheduleVersion(selectedVersion.id);
          await reloadVersions(selectedVersion.id);
          message.success('课表发布成功');
        } catch (error) {
          message.error(errorText(error, '发布失败'));
          throw error;
        }
      },
    });
  };

  const showAudit = async () => {
    if (!selectedVersion) return;
    setAuditOpen(true);
    try { setAudits(await api.getScheduleAudit(selectedVersion.id)); }
    catch { message.error('操作记录加载失败'); }
  };

  const gradeGroups = useMemo(() => {
    const grouped: Record<number, ScheduleEntry[]> = {};
    gradeEntries.forEach(entry => { (grouped[entry.class_id] ??= []).push(entry); });
    return grouped;
  }, [gradeEntries]);

  if (loading) return <Card><div style={{ padding: 60, textAlign: 'center' }}><Spin tip="正在加载课表工作区" /></div></Card>;
  if (!versions.length) return (
    <Card title="课表工作区">
      <Empty description="还没有课表版本，请先到“排课操作”生成一个草稿" />
    </Card>
  );

  const timetableOptions = { showTeacher, colorize, compact };
  const viewSettings = (
    <Space wrap>
      <span>科目配色</span><Switch checked={colorize} onChange={setColorize} size="small" />
      <span>显示教师</span><Switch checked={showTeacher} onChange={setShowTeacher} size="small" />
      <Segmented size="small" value={compact ? '紧凑' : '舒适'} options={['舒适', '紧凑']} onChange={value => setCompact(value === '紧凑')} />
    </Space>
  );

  return (
    <Card title="课表工作区" styles={{ body: { paddingTop: 16 } }}>
      <div className="version-toolbar">
        <Space wrap size="middle">
          <div>
            <Typography.Text type="secondary">当前方案</Typography.Text>
            <div style={{ marginTop: 5 }}>
              <Select
                showSearch
                optionFilterProp="label"
                style={{ width: 300 }}
                value={selectedVersionId}
                onChange={setSelectedVersionId}
                options={versions.map(item => ({
                  value: item.id,
                  label: `V${item.version_no} · ${item.name} · ${STATUS_LABEL[item.status]}`,
                }))}
              />
            </div>
          </div>
          {selectedVersion && <Tag color={STATUS_COLOR[selectedVersion.status]}>{STATUS_LABEL[selectedVersion.status]}</Tag>}
          <Typography.Text type="secondary">{selectedVersion?.entry_count ?? 0} 节课</Typography.Text>
        </Space>
        <Space wrap>
          {selectedVersion?.status === 'draft' && (
            <Tooltip title="开启后可以拖动课程卡片">
              <Button
                type={editMode ? 'primary' : 'default'}
                icon={<SwapOutlined />}
                onClick={() => setEditMode(value => !value)}
              >{editMode ? '结束调整' : '拖拽调整'}</Button>
            </Tooltip>
          )}
          {selectedVersion?.status === 'draft' && <Button icon={<EditOutlined />} onClick={openMeta}>方案信息</Button>}
          <Button icon={<CopyOutlined />} onClick={copyVersion}>复制为草稿</Button>
          <Button icon={<HistoryOutlined />} onClick={showAudit}>操作记录</Button>
          <Button icon={<DownloadOutlined />} onClick={() => api.exportSchedule(selectedVersionId)}>导出</Button>
          {selectedVersion?.status === 'draft' && user.role !== 'operator' && (
            <Button type="primary" icon={<SendOutlined />} onClick={publishVersion}>发布课表</Button>
          )}
        </Space>
      </div>

      {selectedVersion?.status === 'draft' && editMode && (
        <Alert
          showIcon
          type="info"
          message="草稿已进入调整模式：可以直接拖动课程，也可以先点击一门课程、再点击目标格进行移动或交换。"
          style={{ marginBottom: 16 }}
          action={<Button size="small" icon={<ReloadOutlined />} onClick={loadClass}>刷新</Button>}
        />
      )}
      {selectedVersion?.status !== 'draft' && (
        <Alert
          showIcon
          type={selectedVersion?.status === 'published' ? 'success' : 'warning'}
          message={selectedVersion?.status === 'published' ? '这是当前正式课表，仅支持查看。' : '这是历史快照；如需恢复或调整，请先复制为草稿。'}
          style={{ marginBottom: 16 }}
        />
      )}

      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 10 }}>{viewSettings}</div>
      <AvailabilityPanel
        versions={versions}
        selectedVersionId={selectedVersionId}
        teachers={teachers}
        classes={classes}
      />
      <QualityScoreCard
        versions={versions}
        versionId={selectedVersionId}
        revision={selectedVersion?.revision}
      />
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: 'class', label: '班级课表',
            children: <Space direction="vertical" style={{ width: '100%' }} size="middle">
              <Space wrap>
                <Segmented
                  value={classViewMode}
                  onChange={value => setClassViewMode(value as 'single' | 'dual')}
                  options={[{ label: '单班视图', value: 'single' }, { label: '双班对照', value: 'dual' }]}
                />
                <Select
                  showSearch optionFilterProp="label" placeholder="选择主班级" style={{ width: 220 }}
                  value={selectedClassId} onChange={setSelectedClassId}
                  options={classes.map(item => ({ label: item.name, value: item.id }))}
                />
                {classViewMode === 'dual' && <Select
                  showSearch optionFilterProp="label" placeholder="选择同年级对照班级" style={{ width: 240 }}
                  value={compareClassId} onChange={setCompareClassId}
                  options={comparableClasses.map(item => ({ label: item.name, value: item.id }))}
                />}
              </Space>
              {classViewMode === 'dual' && compareClassId && selectedVersion?.status === 'draft' && (
                <Alert type="info" showIcon message="可在两个班级之间拖动已有课程进行协同换时；系统会同步调整两个班，并校验固定课程和教师冲突。" />
              )}
              <div style={{ display: 'grid', gridTemplateColumns: classViewMode === 'dual' && compareClassId ? 'repeat(2, minmax(620px, 1fr))' : '1fr', gap: 16, overflowX: 'auto' }}>
                <Card size="small" title={selectedClass?.name ?? '主班级'}>
                  <Timetable
                    entries={classEntries} periodsPerDay={classPeriods}
                    editable={selectedVersion?.status === 'draft' && editMode}
                    moving={moving}
                    onMove={(entryId, day, period) => onMove(entryId, day, period, selectedClassId)}
                    {...timetableOptions}
                  />
                </Card>
                {classViewMode === 'dual' && compareClassId && <Card size="small" title={compareClass?.name ?? '对照班级'}>
                  <Timetable
                    entries={compareEntries} periodsPerDay={comparePeriods}
                    editable={selectedVersion?.status === 'draft' && editMode}
                    moving={moving}
                    onMove={(entryId, day, period) => onMove(entryId, day, period, compareClassId)}
                    {...timetableOptions}
                  />
                </Card>}
              </div>
            </Space>,
          },
          {
            key: 'teacher', label: '教师课表',
            children: <Space direction="vertical" style={{ width: '100%' }} size="middle">
              <Select
                showSearch optionFilterProp="label" placeholder="选择教师" style={{ width: 240 }}
                value={selectedTeacherId} onChange={setSelectedTeacherId}
                options={teachers.map(item => ({ label: item.name, value: item.id }))}
              />
              <Timetable entries={teacherEntries} periodsPerDay={[8, 8, 8, 8, 8]} {...timetableOptions} />
            </Space>,
          },
          {
            key: 'grade', label: '年级总视图',
            children: <Space direction="vertical" style={{ width: '100%' }} size="middle">
              <Select
                showSearch optionFilterProp="label" placeholder="选择年级" style={{ width: 240 }}
                value={selectedGradeId} onChange={setSelectedGradeId}
                options={grades.map(item => ({ label: item.name, value: item.id }))}
              />
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(620px, 1fr))', gap: 16, overflowX: 'auto' }}>
              {Object.entries(gradeGroups).map(([classId, entries]) => {
                const classItem = classes.find(item => item.id === Number(classId));
                const grade = grades.find(item => item.id === classItem?.grade_id);
                return <Card key={classId} size="small" title={classItem?.name ?? `班级 ${classId}`}>
                  <Timetable
                    entries={entries}
                    periodsPerDay={DAY_KEYS.map(day => grade?.periods_per_day[day] ?? 6)}
                    {...timetableOptions}
                  />
                </Card>;
              })}
              </div>
            </Space>,
          },
        ]}
      />

      <Modal title="自定义方案信息" open={metaOpen} onCancel={() => setMetaOpen(false)} onOk={saveMeta} okText="保存">
        <Form form={metaForm} layout="vertical">
          <Form.Item name="name" label="方案名称" rules={[{ required: true, message: '请输入方案名称' }, { max: 120 }]}>
            <Input placeholder="例如：2026 秋季正式方案" showCount maxLength={120} />
          </Form.Item>
          <Form.Item name="description" label="方案备注">
            <Input.TextArea rows={4} placeholder="记录排课规则、适用年级或本次调整原因" showCount maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer title="版本操作记录" open={auditOpen} onClose={() => setAuditOpen(false)} width={430}>
        {audits.length ? <Timeline items={audits.map(item => ({
          color: item.action === 'published' ? 'green' : 'blue',
          children: <div>
            <Typography.Text strong>{item.user_name}</Typography.Text> {ACTION_LABEL[item.action] ?? item.action}
            <div><Typography.Text type="secondary">{dayjs(item.created_at).format('YYYY-MM-DD HH:mm:ss')}</Typography.Text></div>
          </div>,
        }))} /> : <Empty description="暂无操作记录" />}
      </Drawer>
      <SwapPreviewModal
        preview={swapPreview}
        classNameOf={classId => classes.find(item => item.id === classId)?.name ?? `班级 #${classId}`}
        confirmLoading={confirmingSwap}
        onConfirm={confirmSwap}
        onCancel={() => { setSwapPreview(null); setPendingSwap(null); }}
      />
    </Card>
  );
};

export default ScheduleView;
