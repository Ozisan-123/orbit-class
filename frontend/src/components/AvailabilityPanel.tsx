import React, { useEffect, useState } from 'react';
import { Alert, Button, Card, Segmented, Select, Space, Tag, Typography, message } from 'antd';
import * as api from '../api';
import type {
  AvailabilityResult, Class, FreeSlotsResult, ScheduleVersion, Teacher,
} from '../types';

const DAYS = ['周一', '周二', '周三', '周四', '周五'];

interface Props {
  versions: ScheduleVersion[];
  selectedVersionId?: number;
  teachers: Teacher[];
  classes: Class[];
}

const AvailabilityPanel: React.FC<Props> = ({
  versions, selectedVersionId, teachers, classes,
}) => {
  const [resourceType, setResourceType] = useState<'teacher' | 'class'>('teacher');
  const [resourceId, setResourceId] = useState<number>();
  const [day, setDay] = useState(0);
  const [period, setPeriod] = useState(0);
  const [checking, setChecking] = useState(false);
  const [result, setResult] = useState<AvailabilityResult | null>(null);
  const [freeSlots, setFreeSlots] = useState<FreeSlotsResult | null>(null);

  useEffect(() => {
    const options = resourceType === 'teacher' ? teachers : classes;
    setResourceId(options[0]?.id);
    setResult(null);
    setFreeSlots(null);
  }, [classes, resourceType, teachers]);

  const check = async () => {
    if (!selectedVersionId || !resourceId) return;
    setChecking(true);
    setFreeSlots(null);
    try {
      const data = resourceType === 'teacher'
        ? await api.getTeacherAvailability(resourceId, selectedVersionId, day, period)
        : await api.getClassAvailability(resourceId, selectedVersionId, day, period);
      setResult(data);
    } catch (error: any) {
      message.error(error?.response?.data?.detail ?? '空闲状态查询失败');
    } finally {
      setChecking(false);
    }
  };

  const findFree = async () => {
    if (!selectedVersionId || !resourceId || resourceType !== 'teacher') return;
    setChecking(true);
    setResult(null);
    try {
      setFreeSlots(await api.getTeacherFreeSlots(resourceId, selectedVersionId, day));
    } catch (error: any) {
      message.error(error?.response?.data?.detail ?? '空闲节次查询失败');
    } finally {
      setChecking(false);
    }
  };

  const options = resourceType === 'teacher' ? teachers : classes;
  const version = versions.find(item => item.id === selectedVersionId);

  return (
    <Card size="small" title="资源空闲查询">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Typography.Text type="secondary">
          查询结论固定在当前课表版本 {version ? `V${version.version_no}` : '—'}，切换版本后请重新查询。
        </Typography.Text>
        <Space wrap>
          <Segmented
            value={resourceType}
            options={[{ label: '教师', value: 'teacher' }, { label: '班级', value: 'class' }]}
            onChange={value => setResourceType(value as 'teacher' | 'class')}
          />
          <Select
            showSearch optionFilterProp="label" style={{ width: 220 }} value={resourceId}
            onChange={value => { setResourceId(value); setResult(null); setFreeSlots(null); }}
            options={options.map(item => ({ value: item.id, label: item.name }))}
          />
          <Select style={{ width: 110 }} value={day} onChange={value => { setDay(value); setResult(null); setFreeSlots(null); }}
            options={DAYS.map((label, value) => ({ label, value }))} />
          <Select style={{ width: 110 }} value={period} onChange={value => { setPeriod(value); setResult(null); }}
            options={Array.from({ length: 12 }, (_, value) => ({ label: `第 ${value + 1} 节`, value }))} />
          <Button type="primary" loading={checking} onClick={check}>检查该时段</Button>
          {resourceType === 'teacher' && <Button loading={checking} onClick={findFree}>查看全天空闲</Button>}
        </Space>

        {result && (
          <Alert
            showIcon
            type={result.available ? 'success' : 'warning'}
            message={result.available ? '该时段空闲' : '该时段已有课程'}
            description={result.occupied_by
              ? `${result.occupied_by.class_name} · ${result.occupied_by.subject_name} · ${result.occupied_by.teacher_name}`
              : `${DAYS[result.day_of_week]}第 ${result.period_index + 1} 节可以安排`}
          />
        )}

        {freeSlots && (
          <Alert
            showIcon type="info"
            message={`${DAYS[freeSlots.day_of_week]}共有 ${freeSlots.free_periods.length} 个空闲节次`}
            description={<Space wrap>{freeSlots.free_periods.length
              ? freeSlots.free_periods.map(value => <Tag color="green" key={value}>第 {value + 1} 节</Tag>)
              : <Typography.Text>当天没有空闲节次</Typography.Text>}</Space>}
          />
        )}
      </Space>
    </Card>
  );
};

export default AvailabilityPanel;
