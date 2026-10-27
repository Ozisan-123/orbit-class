import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert, Button, Card, List, Modal, Popconfirm, Progress, Select, Space, Spin, Steps, Table,
  Tag, Tooltip, Typography, message,
} from 'antd';
import { ArrowRightOutlined, HistoryOutlined, PlayCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import * as api from '../api';
import type {
  Class, Grade, JobEvent, ScheduleEntry, ScheduleJob, ScheduleStatus, ScheduleVersion,
  SwapPreview, SwapRequest,
} from '../types';
import Timetable from '../components/Timetable';
import SwapPreviewModal from '../components/SwapPreviewModal';

const DAY_KEYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri'];
const PAGE_SIZE = 10;

/** 任务状态 → 展示（颜色 + 中文）。 */
const STATUS_META: Record<string, { color: string; text: string }> = {
  pending: { color: 'blue', text: '排队中' },
  running: { color: 'processing', text: '求解中' },
  done: { color: 'success', text: '已完成' },
  failed: { color: 'error', text: '失败' },
  cancelled: { color: 'warning', text: '已取消' },
};

const ScheduleGenerate: React.FC = () => {
  const navigate = useNavigate();
  const [status, setStatus] = useState<ScheduleStatus>({ status: 'idle' });
  const [classes, setClasses] = useState<Class[]>([]);
  const [grades, setGrades] = useState<Grade[]>([]);
  const [selectedClassId, setSelectedClassId] = useState<number>();
  const [previewVersion, setPreviewVersion] = useState<ScheduleVersion>();
  const [entries, setEntries] = useState<ScheduleEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [moving, setMoving] = useState(false);
  const [swapPreview, setSwapPreview] = useState<SwapPreview | null>(null);
  const [pendingSwap, setPendingSwap] = useState<SwapRequest | null>(null);
  const [confirmingSwap, setConfirmingSwap] = useState(false);
  const pollingRef = useRef<number | null>(null);

  // —— P3：任务队列 ——
  const [jobs, setJobs] = useState<ScheduleJob[]>([]);
  const [jobsTotal, setJobsTotal] = useState(0);
  const [jobsPage, setJobsPage] = useState(1);
  const [jobsLoading, setJobsLoading] = useState(false);
  const [eventsOf, setEventsOf] = useState<ScheduleJob | null>(null);
  const [events, setEvents] = useState<JobEvent[]>([]);

  const loadJobs = useCallback(async (page = 1) => {
    setJobsLoading(true);
    try {
      const data = await api.getJobs({ page, size: PAGE_SIZE });
      setJobs(data.items ?? []);
      setJobsTotal(data.total ?? 0);
      setJobsPage(page);
    } catch (error: any) {
      // 任务列表拉不到不该阻塞主流程（排课本身还在跑），只提示
      message.error(error?.response?.data?.detail ?? '加载任务列表失败');
    } finally {
      setJobsLoading(false);
    }
  }, []);

  useEffect(() => {
    Promise.all([api.getClasses(), api.getGrades()]).then(([classData, gradeData]) => {
      setClasses(classData);
      setGrades(gradeData);
      setSelectedClassId(classData[0]?.id);
    });
    loadJobs(1);
  }, [loadJobs]);

  const stopPolling = useCallback(() => {
    if (pollingRef.current) window.clearInterval(pollingRef.current);
    pollingRef.current = null;
  }, []);

  const loadLatestDraft = useCallback(async (classId?: number) => {
    const versions: ScheduleVersion[] = await api.getScheduleVersions();
    const latest = versions.find(item => item.status === 'draft') ?? versions[0];
    setPreviewVersion(latest);
    if (latest && classId) setEntries(await api.getClassSchedule(classId, latest.id));
  }, []);

  const startPolling = useCallback(() => {
    stopPolling();
    pollingRef.current = window.setInterval(async () => {
      try {
        const current = await api.getScheduleStatus();
        setStatus(current);
        // ⚠ 轮询同时刷新任务列表：状态只有一行（"在跑吗"），
        //   而"排第几 / 进度多少 / 被接管过吗"只在任务列表里能看到。
        //   两个都刷，用户才看得到完整的进行中状态。
        loadJobs(jobsPage);
        if (['done', 'failed', 'cancelled', 'idle'].includes(current.status)) {
          stopPolling();
          setLoading(false);
          if (current.status === 'done') await loadLatestDraft(selectedClassId);
        }
      } catch {
        stopPolling();
        setLoading(false);
      }
    }, 1000);
  }, [jobsPage, loadLatestDraft, loadJobs, selectedClassId, stopPolling]);

  useEffect(() => {
    api.getScheduleStatus().then(current => {
      setStatus(current);
      if (current.status === 'running') {
        setLoading(true);
        startPolling();
      } else if (current.status === 'done') {
        loadLatestDraft(selectedClassId);
      }
    });
    return stopPolling;
  }, [loadLatestDraft, selectedClassId, startPolling, stopPolling]);

  useEffect(() => {
    if (selectedClassId && previewVersion) {
      api.getClassSchedule(selectedClassId, previewVersion.id).then(setEntries);
    }
  }, [selectedClassId, previewVersion]);

  const generate = async () => {
    setLoading(true);
    setPreviewVersion(undefined);
    setEntries([]);
    setStatus({ status: 'running', message: '正在启动排课任务...' });
    try {
      const current = await api.generateSchedule();
      setStatus(current);
      loadJobs(1);
      startPolling();
    } catch (error: any) {
      const detail = error.response?.data?.detail ?? '启动排课失败';
      setStatus({ status: 'failed', message: detail });
      setLoading(false);
      message.error(detail);
    }
  };

  const movePreviewCourse = async (entryId: number, day: number, period: number) => {
    if (!previewVersion || !selectedClassId) return;
    setMoving(true);
    try {
      const request: SwapRequest = {
        source_entry_id: entryId,
        target_class_id: selectedClassId,
        target_day_of_week: day,
        target_period_index: period,
        expected_revision: previewVersion.revision,
      };
      setPendingSwap(request);
      setSwapPreview(await api.previewScheduleSwap(previewVersion.id, request));
    } catch (error: any) {
      message.error(error?.response?.data?.detail ?? '课程调整预览失败');
    } finally {
      setMoving(false);
    }
  };

  const confirmPreviewSwap = async () => {
    if (!previewVersion || !pendingSwap || !swapPreview?.can_swap || !selectedClassId) return;
    setConfirmingSwap(true);
    try {
      const updated = await api.swapScheduleEntry(previewVersion.id, pendingSwap);
      setPreviewVersion(updated);
      setEntries(await api.getClassSchedule(selectedClassId, updated.id));
      setSwapPreview(null);
      setPendingSwap(null);
      message.success('课程位置已保存到草稿');
    } catch (error: any) {
      message.error(error?.response?.data?.detail ?? '课程调整失败');
      setSwapPreview(null);
      setPendingSwap(null);
      await loadLatestDraft(selectedClassId);
    } finally {
      setConfirmingSwap(false);
    }
  };

  const cancelJob = async (job: ScheduleJob) => {
    try {
      await api.cancelJob(job.id);
      message.success(job.status === 'pending' ? '已取消' : '取消请求已发出，求解会在下一次心跳时收手');
      await loadJobs(jobsPage);
    } catch (error: any) {
      message.error(error?.response?.data?.detail ?? '取消失败');
      await loadJobs(jobsPage);
    }
  };

  const retryJob = async (job: ScheduleJob) => {
    try {
      await api.retryJob(job.id);
      message.success('已重新入队');
      await loadJobs(1);
    } catch (error: any) {
      message.error(error?.response?.data?.detail ?? '重试失败');
    }
  };

  const openEvents = async (job: ScheduleJob) => {
    setEventsOf(job);
    try {
      setEvents(await api.getJobEvents(job.id));
    } catch (error: any) {
      message.error(error?.response?.data?.detail ?? '加载事件失败');
    }
  };

  const selectedClass = classes.find(item => item.id === selectedClassId);
  const selectedGrade = grades.find(item => item.id === selectedClass?.grade_id);
  const periods = DAY_KEYS.map(day => selectedGrade?.periods_per_day[day] ?? 6);
  const step = status.status === 'done' ? 1 : status.status === 'running' ? 0 : 0;

  const jobColumns = [
    { title: '#', dataIndex: 'id', key: 'id', width: 56 },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 150,
      render: (value: string, job: ScheduleJob) => {
        const meta = STATUS_META[value] ?? { color: 'default', text: value };
        return (
          <Space size={4} wrap>
            <Tag color={meta.color}>{meta.text}</Tag>
            {/* 租约过期 = 持有者大概率已经死了，等自动接管。不说清的话用户会以为卡死了 */}
            {job.lease_expired && (
              <Tooltip title="执行求解的进程可能已经退出，租约到期后会被自动接管重做">
                <Tag color="orange">等待接管</Tag>
              </Tooltip>
            )}
          </Space>
        );
      },
    },
    {
      title: '进度', key: 'progress', width: 190,
      render: (_: unknown, job: ScheduleJob) => {
        if (job.status === 'pending') {
          return <Typography.Text type="secondary">
            排队中{job.queue_position ? `（前面还有 ${job.queue_position} 个）` : '（下一个就是它）'}
          </Typography.Text>;
        }
        if (job.status === 'running') {
          return <Progress percent={job.progress ?? 0} size="small" status="active" />;
        }
        if (job.status === 'done') {
          return <Typography.Text>{job.entry_count ?? 0} 节课 · 版本 #{job.version_id}</Typography.Text>;
        }
        return <Typography.Text type="secondary">—</Typography.Text>;
      },
    },
    {
      title: '次数', key: 'attempt', width: 80,
      render: (_: unknown, job: ScheduleJob) => (
        <Tooltip title={`已尝试 ${job.attempt} 次，最多 ${job.max_attempts} 次`}>
          <span>{job.attempt}/{job.max_attempts}</span>
        </Tooltip>
      ),
    },
    {
      title: '求解', key: 'solver', width: 150,
      render: (_: unknown, job: ScheduleJob) => job.solver_status
        ? <Space size={4}>
            <Tag>{job.solver_status}</Tag>
            {job.objective_value != null && <Typography.Text type="secondary">目标 {job.objective_value}</Typography.Text>}
          </Space>
        : <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: '说明', key: 'error', ellipsis: true,
      render: (_: unknown, job: ScheduleJob) => job.last_error
        ? <Tooltip title={job.last_error}><Typography.Text type="danger">{job.last_error}</Typography.Text></Tooltip>
        : <Typography.Text type="secondary">{(job.worker_id ? `由 ${job.worker_id} 执行` : '—')}</Typography.Text>,
    },
    {
      title: '操作', key: 'action', width: 180,
      render: (_: unknown, job: ScheduleJob) => (
        <Space size={4}>
          {(job.status === 'pending' || job.status === 'running') && (
            <Popconfirm
              title={job.status === 'pending' ? '取消这个排课任务？' : '请求取消？求解会在下一次心跳时收手'}
              onConfirm={() => cancelJob(job)} okText="取消任务" cancelText="再想想"
            >
              <Button type="link" size="small" danger>取消</Button>
            </Popconfirm>
          )}
          {(job.status === 'failed' || job.status === 'cancelled') && (
            <Button type="link" size="small" onClick={() => retryJob(job)}>重试</Button>
          )}
          <Button type="link" size="small" icon={<HistoryOutlined />} onClick={() => openEvents(job)}>事件</Button>
        </Space>
      ),
    },
  ];

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="large">
      <Card title="智能排课">
        <Steps
          current={step}
          status={status.status === 'failed' ? 'error' : status.status === 'done' ? 'finish' : 'process'}
          items={[{ title: '生成草稿', description: '自动求解约束' }, { title: '人工调整与发布', description: '拖拽检查后上线' }]}
          style={{ marginBottom: 28 }}
        />
        <Space direction="vertical" style={{ width: '100%' }} size="large">
          <Space wrap>
            <Button type="primary" size="large" icon={<PlayCircleOutlined />} onClick={generate} loading={loading}>
              {status.status === 'done' ? '生成新方案' : '开始排课'}
            </Button>
            {status.status === 'done' && <Button icon={<ReloadOutlined />} onClick={generate}>重新生成</Button>}
          </Space>
          {status.status === 'running' && (
            <Alert showIcon icon={<Spin />} type="info" message="排课正在进行"
              description={`${status.message ?? ''}${status.owner_name ? `（操作人：${status.owner_name}）` : ''}`} />
          )}
          {status.status === 'failed' && <Alert showIcon type="error" message="排课失败" description={status.message} />}
          {status.status === 'done' && (
            <Alert
              showIcon type="success" message="排课草稿已生成"
              description="正式课表没有被覆盖。你可以先预览，再进入课表工作区拖拽调整、填写备注并发布。"
              action={<Button type="primary" icon={<ArrowRightOutlined />} onClick={() => navigate('/schedule/view')}>去调整和发布</Button>}
            />
          )}
        </Space>
      </Card>

      {/* ---------------------------------------------------------- P3：任务队列 */}
      <Card
        title="排课任务"
        extra={<Button icon={<ReloadOutlined />} onClick={() => loadJobs(jobsPage)}>刷新</Button>}
      >
        <Typography.Paragraph type="secondary" style={{ marginTop: -8 }}>
          每次排课都是一条<strong>任务</strong>：可能排队、可能被重试、崩溃后会被自动接管。
          这里能看到它排第几、跑了多久、由哪个求解进程执行、上次为什么失败。
        </Typography.Paragraph>
        <Table
          rowKey="id"
          size="small"
          loading={jobsLoading}
          columns={jobColumns as any}
          dataSource={jobs}
          pagination={{
            current: jobsPage,
            pageSize: PAGE_SIZE,
            total: jobsTotal,
            showSizeChanger: false,
            onChange: (page) => loadJobs(page),
          }}
        />
      </Card>

      <Modal
        title={eventsOf ? `任务 #${eventsOf.id} 的事件时间线` : '事件时间线'}
        open={!!eventsOf}
        onCancel={() => setEventsOf(null)}
        footer={null}
        width={640}
      >
        <Typography.Paragraph type="secondary">
          领单、心跳、接管、取消都会留痕。<strong>「接管」出现时说明上一个求解进程没能收尾</strong> ——
          它被判定为死亡后，任务被另一个进程接手重做。
        </Typography.Paragraph>
        <List
          size="small"
          dataSource={events}
          renderItem={(event) => (
            <List.Item>
              <Space direction="vertical" size={0} style={{ width: '100%' }}>
                <Space>
                  <Tag color={event.event === 'takeover' ? 'orange' : event.event === 'failed' ? 'red' : 'blue'}>
                    {event.event}
                  </Tag>
                  {event.worker_id && <Typography.Text type="secondary">worker={event.worker_id}</Typography.Text>}
                  {event.attempt != null && <Typography.Text type="secondary">attempt={event.attempt}</Typography.Text>}
                </Space>
                <Typography.Text>{event.detail ?? '—'}</Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {event.created_at?.replace('T', ' ')}
                </Typography.Text>
              </Space>
            </List.Item>
          )}
        />
      </Modal>

      {previewVersion && (
        <Card title={<Space><span>草稿预览</span><Typography.Text type="secondary">V{previewVersion.version_no} · {previewVersion.name}</Typography.Text></Space>}>
          <Space direction="vertical" style={{ width: '100%' }} size="middle">
            <Alert type="info" showIcon message="草稿可以直接拖动调整，也可以依次点击课程和目标格完成交换。" />
            <Select
              showSearch optionFilterProp="label" style={{ width: 240 }} value={selectedClassId}
              onChange={setSelectedClassId} options={classes.map(item => ({ label: item.name, value: item.id }))}
            />
            <Timetable entries={entries} periodsPerDay={periods} editable moving={moving} onMove={movePreviewCourse} />
          </Space>
        </Card>
      )}
      <SwapPreviewModal
        preview={swapPreview}
        classNameOf={classId => classes.find(item => item.id === classId)?.name ?? `班级 #${classId}`}
        confirmLoading={confirmingSwap}
        onConfirm={confirmPreviewSwap}
        onCancel={() => { setSwapPreview(null); setPendingSwap(null); }}
      />
    </Space>
  );
};

export default ScheduleGenerate;
