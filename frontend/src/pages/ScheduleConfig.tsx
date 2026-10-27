import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Empty, Form, Modal, Select, Space, Table, Tag, Typography, message } from 'antd';
import { DeleteOutlined, PushpinFilled } from '@ant-design/icons';
import * as api from '../api';
import type { Assignment, Class, Grade, GradeFixedSlot } from '../types';

const DAYS = [
  { key: 'Mon', label: '周一' }, { key: 'Tue', label: '周二' },
  { key: 'Wed', label: '周三' }, { key: 'Thu', label: '周四' },
  { key: 'Fri', label: '周五' },
];
const errorText = (error: any, fallback: string) => error?.response?.data?.detail ?? fallback;

interface SelectedCell {
  day: number;
  period: number;
}

const ScheduleConfig: React.FC = () => {
  const [grades, setGrades] = useState<Grade[]>([]);
  const [classes, setClasses] = useState<Class[]>([]);
  const [assignments, setAssignments] = useState<Assignment[]>([]);
  const [fixedSlots, setFixedSlots] = useState<GradeFixedSlot[]>([]);
  const [selectedGradeId, setSelectedGradeId] = useState<number>();
  const [selectedCell, setSelectedCell] = useState<SelectedCell>();
  const [modalOpen, setModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    try {
      const [gradeData, classData, assignmentData, slotData] = await Promise.all([
        api.getGrades(), api.getClasses(), api.getAssignments(), api.getGradeFixedSlots(),
      ]);
      setGrades(gradeData);
      setClasses(classData);
      setAssignments(assignmentData);
      setFixedSlots(slotData);
      setSelectedGradeId(current => current ?? gradeData[0]?.id);
    } catch (error) {
      message.error(errorText(error, '年级固定课程加载失败'));
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const selectedGrade = grades.find(item => item.id === selectedGradeId);
  const gradeClasses = useMemo(
    () => classes.filter(item => item.grade_id === selectedGradeId),
    [classes, selectedGradeId],
  );
  const gradeSlots = fixedSlots.filter(item => item.grade_id === selectedGradeId);
  const slotAt = (day: number, period: number) => gradeSlots.find(
    item => item.day_of_week === day && item.period_index === period,
  );

  const availableSubjects = useMemo(() => {
    if (!gradeClasses.length) return [];
    const classIds = new Set(gradeClasses.map(item => item.id));
    const relevant = assignments.filter(item => classIds.has(item.class_id));
    const subjectIds = [...new Set(relevant.map(item => item.subject_id))];
    return subjectIds.flatMap(subjectId => {
      const subjectAssignments = relevant.filter(item => item.subject_id === subjectId);
      const assignmentsByClass = gradeClasses.map(classItem =>
        subjectAssignments.filter(item => item.class_id === classItem.id),
      );
      if (assignmentsByClass.some(items => items.length !== 1)) return [];
      const teacherIds = assignmentsByClass.map(items => items[0].teacher_id);
      if (new Set(teacherIds).size !== teacherIds.length) return [];
      return [{
        subject_id: subjectId,
        subject_name: subjectAssignments[0]?.subject_name ?? `科目 ${subjectId}`,
      }];
    });
  }, [assignments, gradeClasses]);

  const subjectName = (subjectId: number) =>
    assignments.find(item => item.subject_id === subjectId)?.subject_name ?? `科目 ${subjectId}`;

  const openCell = (day: number, period: number) => {
    const slot = slotAt(day, period);
    setSelectedCell({ day, period });
    form.setFieldsValue({ subject_id: slot?.subject_id });
    setModalOpen(true);
  };

  const saveCell = async () => {
    if (!selectedCell || !selectedGradeId) return;
    const values = await form.validateFields();
    setSaving(true);
    try {
      await api.createGradeFixedSlot({
        grade_id: selectedGradeId,
        subject_id: values.subject_id,
        day_of_week: selectedCell.day,
        period_index: selectedCell.period,
      });
      setModalOpen(false);
      message.success(`已固定${selectedGrade?.name}全部班级的课程`);
      await load();
    } catch (error) {
      message.error(errorText(error, '年级固定课程保存失败'));
    } finally {
      setSaving(false);
    }
  };

  const deleteCurrent = async () => {
    if (!selectedCell) return;
    const slot = slotAt(selectedCell.day, selectedCell.period);
    if (!slot) return;
    setSaving(true);
    try {
      await api.deleteGradeFixedSlot(slot.id);
      setModalOpen(false);
      message.success('已取消年级固定课程');
      await load();
    } catch (error) {
      message.error(errorText(error, '取消固定失败'));
    } finally {
      setSaving(false);
    }
  };

  const periods = selectedGrade
    ? DAYS.map(day => selectedGrade.periods_per_day[day.key] ?? 6)
    : [6, 6, 6, 6, 6];
  const maxPeriods = Math.max(...periods, 1);
  const columns: any[] = [
    {
      title: '节次', dataIndex: 'period', width: 82, fixed: 'left', align: 'center',
      render: (period: number) => <strong>第 {period + 1} 节</strong>,
    },
    ...DAYS.map((day, dayIndex) => ({
      title: day.label, key: day.key, align: 'center',
      render: (_: unknown, row: { period: number }) => {
        if (row.period >= periods[dayIndex]) {
          return <div style={{ minHeight: 76, background: '#f5f5f5', borderRadius: 8 }} />;
        }
        const slot = slotAt(dayIndex, row.period);
        return <button
          type="button"
          className={`fixed-slot-cell${slot ? ' fixed-slot-cell-active' : ''}`}
          style={{ minHeight: 76 }}
          onClick={() => openCell(dayIndex, row.period)}
        >
          {slot ? <>
            <PushpinFilled />
            <strong>{subjectName(slot.subject_id)}</strong>
            <small>全年级 · {gradeClasses.length} 个班</small>
          </> : <span>＋ 点击设置年级固定课</span>}
        </button>;
      },
    })),
  ];

  const selectedSlot = selectedCell ? slotAt(selectedCell.day, selectedCell.period) : undefined;

  return <Card title="年级固定课程">
    <Alert
      type="info"
      showIcon
      message="一张课表代表整个年级。选择某个格子的科目后，排课引擎会为该年级每个班在同一时间安排这门课，并自动使用各班的任教教师。"
      description="如果同一位教师负责该年级多个班，系统会提示无法同时固定，需先在教师管理中调整任教分配。"
      style={{ marginBottom: 16 }}
    />
    <Space wrap style={{ marginBottom: 18 }}>
      <Typography.Text strong>选择年级：</Typography.Text>
      <Select
        showSearch optionFilterProp="label" placeholder="选择年级" style={{ width: 220 }}
        value={selectedGradeId} onChange={setSelectedGradeId}
        options={grades.map(item => ({ label: item.name, value: item.id }))}
      />
      {selectedGrade && <Tag color="processing">覆盖 {gradeClasses.length} 个班级</Tag>}
      {selectedGrade && <Tag color="purple">已固定 {gradeSlots.length} 个时段</Tag>}
    </Space>

    {selectedGrade && gradeClasses.length
      ? <Table
          className="schedule-table"
          rowKey="period"
          size="small"
          bordered
          pagination={false}
          scroll={{ x: 760 }}
          columns={columns}
          dataSource={Array.from({ length: maxPeriods }, (_, period) => ({ period }))}
        />
      : <Empty description={selectedGrade ? '这个年级还没有班级' : '请先创建并选择年级'} />}

    <Modal
      forceRender
      title={selectedCell && selectedGrade ? `${selectedGrade.name} · ${DAYS[selectedCell.day].label}第 ${selectedCell.period + 1} 节` : '设置年级固定课程'}
      open={modalOpen}
      onCancel={() => setModalOpen(false)}
      onOk={saveCell}
      confirmLoading={saving}
      okText={selectedSlot ? '更新年级固定课' : '设为年级固定课'}
      footer={(_, { OkBtn, CancelBtn }) => <Space style={{ width: '100%', justifyContent: 'space-between' }}>
        <div>{selectedSlot && <Button danger icon={<DeleteOutlined />} loading={saving} onClick={deleteCurrent}>取消固定</Button>}</div>
        <Space><CancelBtn /><OkBtn /></Space>
      </Space>}
    >
      <Form form={form} layout="vertical">
        <Form.Item name="subject_id" label="全年级固定科目" rules={[{ required: true, message: '请选择科目' }]}>
          <Select
            showSearch optionFilterProp="label" placeholder="选择已完成任教分配的科目"
            options={availableSubjects.map(item => ({ value: item.subject_id, label: item.subject_name }))}
          />
        </Form.Item>
        {!availableSubjects.length && <Alert type="warning" message="没有可固定的科目。请先确保每个班都有不同的任教教师负责该科目。" />}
      </Form>
    </Modal>
  </Card>;
};

export default ScheduleConfig;
