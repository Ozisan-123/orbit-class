import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert, Button, Card, Checkbox, Form, Input, InputNumber, Modal, Popconfirm,
  Select, Space, Table, Tabs, Tag, Typography, message,
} from 'antd';
import { ClockCircleOutlined, DeleteOutlined, PlusOutlined, RobotOutlined } from '@ant-design/icons';
import * as api from '../api';
import type { Assignment, Class, Grade, GradeSubject, Subject, Teacher } from '../types';

const DAYS = [
  { key: 'Mon', label: '周一' }, { key: 'Tue', label: '周二' },
  { key: 'Wed', label: '周三' }, { key: 'Thu', label: '周四' },
  { key: 'Fri', label: '周五' },
];
const CATEGORIES = [
  { label: '主科', value: 'major', color: 'blue' },
  { label: '副科', value: 'minor', color: 'default' },
  { label: '体育', value: 'sport', color: 'green' },
  { label: '艺术', value: 'art', color: 'magenta' },
];
const errorText = (error: any, fallback: string) => error?.response?.data?.detail ?? fallback;

const DataManagement: React.FC = () => {
  const [grades, setGrades] = useState<Grade[]>([]);
  const [classes, setClasses] = useState<Class[]>([]);
  const [subjects, setSubjects] = useState<Subject[]>([]);
  const [teachers, setTeachers] = useState<Teacher[]>([]);
  const [assignments, setAssignments] = useState<Assignment[]>([]);
  const [gradeSubjects, setGradeSubjects] = useState<GradeSubject[]>([]);
  const [loading, setLoading] = useState(true);

  const [gradeModal, setGradeModal] = useState(false);
  const [classModal, setClassModal] = useState(false);
  const [subjectModal, setSubjectModal] = useState(false);
  const [teacherModal, setTeacherModal] = useState(false);
  const [assignmentModal, setAssignmentModal] = useState(false);
  const [hoursModal, setHoursModal] = useState(false);
  const [editingSubject, setEditingSubject] = useState<Subject>();
  const [assignmentTeacher, setAssignmentTeacher] = useState<Teacher>();

  const [gradeForm] = Form.useForm();
  const [classForm] = Form.useForm();
  const [subjectForm] = Form.useForm();
  const [teacherForm] = Form.useForm();
  const [assignmentForm] = Form.useForm();
  const [hoursForm] = Form.useForm();

  const loadAll = useCallback(async () => {
    setLoading(true);
    try {
      const [gradeData, classData, subjectData, teacherData, assignmentData, gradeSubjectData] = await Promise.all([
        api.getGrades(), api.getClasses(), api.getSubjects(), api.getTeachers(),
        api.getAssignments(), api.getGradeSubjects(),
      ]);
      setGrades(gradeData);
      setClasses(classData);
      setSubjects(subjectData);
      setTeachers(teacherData);
      setAssignments(assignmentData);
      setGradeSubjects(gradeSubjectData);
    } catch (error) {
      message.error(errorText(error, '基础数据加载失败'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadAll(); }, [loadAll]);

  const weeklyHours = useMemo(() => {
    const values = new Map<string, number>();
    gradeSubjects.forEach(item => values.set(`${item.subject_id}:${item.grade_id}`, item.weekly_hours));
    return values;
  }, [gradeSubjects]);

  const onGradeOk = async () => {
    const values = await gradeForm.validateFields();
    const periodsPerDay: Record<string, number> = {};
    DAYS.forEach(day => { periodsPerDay[day.key] = values[`p_${day.key}`] ?? 6; });
    await api.createGrade({ name: values.name, periods_per_day: periodsPerDay });
    setGradeModal(false);
    gradeForm.resetFields();
    message.success('年级已添加');
    await loadAll();
  };

  const onClassOk = async () => {
    const values = await classForm.validateFields();
    await api.createClass(values);
    setClassModal(false);
    classForm.resetFields();
    message.success('班级已添加');
    await loadAll();
  };

  const onSubjectOk = async () => {
    const values = await subjectForm.validateFields();
    const weeklyHoursByGrade: Record<number, number> = {};
    grades.forEach(grade => {
      weeklyHoursByGrade[grade.id] = Number(values.weekly_hours_by_grade?.[grade.id] ?? 0);
    });
    try {
      await api.createSubject({
        name: values.name,
        category: values.category,
        weekly_hours_by_grade: weeklyHoursByGrade,
      });
      setSubjectModal(false);
      subjectForm.resetFields();
      message.success('科目及各年级周课时已保存');
      await loadAll();
    } catch (error) {
      message.error(errorText(error, '新增科目失败'));
    }
  };

  const openHoursEditor = (subject: Subject) => {
    const values: Record<number, number> = {};
    grades.forEach(grade => { values[grade.id] = weeklyHours.get(`${subject.id}:${grade.id}`) ?? 0; });
    setEditingSubject(subject);
    hoursForm.setFieldsValue({ weekly_hours_by_grade: values });
    setHoursModal(true);
  };

  const saveHours = async () => {
    if (!editingSubject) return;
    const values = await hoursForm.validateFields();
    try {
      await api.updateGradeSubjectsBulk({
        subject_id: editingSubject.id,
        items: grades.map(grade => ({
          grade_id: grade.id,
          weekly_hours: Number(values.weekly_hours_by_grade?.[grade.id] ?? 0),
        })),
      });
      setHoursModal(false);
      message.success('年级周课时已更新');
      await loadAll();
    } catch (error) {
      message.error(errorText(error, '周课时保存失败'));
    }
  };

  const onTeacherOk = async () => {
    const values = await teacherForm.validateFields();
    const names = values.names.split('\n').map((name: string) => name.trim()).filter(Boolean);
    await Promise.all(names.map((name: string) => api.createTeacher({
      name, subject_id: values.subject_id, max_weekly_hours: values.max_weekly_hours,
    })));
    let autoResult: any;
    if (values.auto_assign !== false) {
      autoResult = await api.autoAssignTeachers(true, [values.subject_id]);
    }
    setTeacherModal(false);
    teacherForm.resetFields();
    if (autoResult) {
      const suffix = autoResult.unassigned.length
        ? `，另有 ${autoResult.unassigned.length} 项因教师不足未分配`
        : '';
      message.success(`已添加 ${names.length} 名教师，并自动完成 ${autoResult.assigned_count} 项任教分配${suffix}`);
    } else {
      message.success(`已添加 ${names.length} 名教师`);
    }
    await loadAll();
  };

  const openAssignmentEditor = (teacher: Teacher) => {
    setAssignmentTeacher(teacher);
    assignmentForm.setFieldsValue({
      class_ids: assignments.filter(item => item.teacher_id === teacher.id).map(item => item.class_id),
    });
    setAssignmentModal(true);
  };

  const onAssignmentOk = async () => {
    if (!assignmentTeacher) return;
    const values = await assignmentForm.validateFields();
    try {
      await api.setTeacherClasses(assignmentTeacher.id, values.class_ids ?? []);
      setAssignmentModal(false);
      assignmentForm.resetFields();
      message.success('教师任教班级已保存');
      await loadAll();
    } catch (error) {
      message.error(errorText(error, '任教班级保存失败'));
    }
  };

  const autoAssignAll = () => {
    Modal.confirm({
      title: '全自动分配任教关系？',
      content: '系统会保留已有人工分配，只补齐尚未安排的班级和科目，并按照教师周课时上限均衡工作量。',
      okText: '开始分配', cancelText: '取消',
      onOk: async () => {
        try {
          const result = await api.autoAssignTeachers(false);
          await loadAll();
          if (result.unassigned.length) {
            Modal.warning({
              title: `完成 ${result.assigned_count} 项，仍有 ${result.unassigned.length} 项未分配`,
              content: <div style={{ maxHeight: 300, overflow: 'auto' }}>{result.unassigned.map((item: string) => <div key={item}>• {item}</div>)}</div>,
            });
          } else {
            message.success(`全自动分配完成，新增 ${result.assigned_count} 项任教关系`);
          }
        } catch (error) {
          message.error(errorText(error, '自动分配失败'));
          throw error;
        }
      },
    });
  };

  const assignmentsForTeacher = (teacherId: number) => assignments.filter(item => item.teacher_id === teacherId);

  const assignedHoursForTeacher = (teacher: Teacher) => assignmentsForTeacher(teacher.id).reduce((total, assignment) => {
    const classItem = classes.find(item => item.id === assignment.class_id);
    if (!classItem) return total;
    return total + (weeklyHours.get(`${teacher.subject_id}:${classItem.grade_id}`) ?? 0);
  }, 0);

  const eligibleClassesForTeacher = (teacher: Teacher | undefined) => {
    if (!teacher) return [];
    const offeredGradeIds = new Set(
      gradeSubjects.filter(item => item.subject_id === teacher.subject_id && item.weekly_hours > 0).map(item => item.grade_id),
    );
    return classes.filter(item => offeredGradeIds.has(item.grade_id));
  };

  const subjectColumns: any[] = [
    { title: '科目', dataIndex: 'name', key: 'name', fixed: 'left', width: 130 },
    {
      title: '类型', dataIndex: 'category', key: 'category', width: 90,
      render: (value: string) => {
        const category = CATEGORIES.find(item => item.value === value);
        return <Tag color={category?.color}>{category?.label ?? value}</Tag>;
      },
    },
    ...grades.map(grade => ({
      title: grade.name,
      key: `grade_${grade.id}`,
      width: 115,
      align: 'center',
      render: (_: unknown, subject: Subject) => {
        const hours = weeklyHours.get(`${subject.id}:${grade.id}`);
        return hours ? <strong>{hours} 节/周</strong> : <Typography.Text type="secondary">不开设</Typography.Text>;
      },
    })),
    {
      title: '操作', key: 'actions', fixed: 'right', width: 170,
      render: (_: unknown, subject: Subject) => <Space>
        <Button size="small" icon={<ClockCircleOutlined />} onClick={() => openHoursEditor(subject)}>设置课时</Button>
        <Popconfirm title="确定删除该科目？" onConfirm={() => api.deleteSubject(subject.id).then(loadAll)}>
          <Button size="small" danger icon={<DeleteOutlined />} />
        </Popconfirm>
      </Space>,
    },
  ];

  const tabItems = [
    {
      key: 'grades', label: '年级管理',
      children: <>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setGradeModal(true)} style={{ marginBottom: 16 }}>新增年级</Button>
        <Table loading={loading} rowKey="id" size="small" dataSource={grades} columns={[
          { title: '年级', dataIndex: 'name' },
          { title: '每日节次', dataIndex: 'periods_per_day', render: (value: Record<string, number>) => DAYS.map(day => `${day.label} ${value?.[day.key] ?? 6}节`).join('，') },
          { title: '操作', width: 90, render: (_: unknown, grade: Grade) => <Popconfirm title="确定删除该年级？" onConfirm={() => api.deleteGrade(grade.id).then(loadAll)}><Button size="small" danger icon={<DeleteOutlined />} /></Popconfirm> },
        ]} />
      </>,
    },
    {
      key: 'classes', label: '班级管理',
      children: <>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setClassModal(true)} style={{ marginBottom: 16 }}>新增班级</Button>
        <Table loading={loading} rowKey="id" size="small" dataSource={classes} columns={[
          { title: '班级', dataIndex: 'name' }, { title: '所属年级', dataIndex: 'grade_name' },
          { title: '操作', width: 90, render: (_: unknown, item: Class) => <Popconfirm title="确定删除该班级？" onConfirm={() => api.deleteClass(item.id).then(loadAll)}><Button size="small" danger icon={<DeleteOutlined />} /></Popconfirm> },
        ]} />
      </>,
    },
    {
      key: 'subjects', label: '科目与周课时',
      children: <>
        <Alert type="info" showIcon message="每个科目可以为不同年级设置不同周课时；0 表示该年级不开设此科目。" style={{ marginBottom: 16 }} />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setSubjectModal(true)} style={{ marginBottom: 16 }}>新增科目并设置课时</Button>
        <Table loading={loading} rowKey="id" size="small" dataSource={subjects} columns={subjectColumns} scroll={{ x: Math.max(700, 390 + grades.length * 115) }} />
      </>,
    },
    {
      key: 'teachers', label: '教师管理',
      children: <>
        <Alert type="info" showIcon message="教师与任教分配已合并：可以直接查看工作量、维护任教班级，或让系统自动均衡分配。" style={{ marginBottom: 16 }} />
        <Space style={{ marginBottom: 16 }}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setTeacherModal(true)}>批量新增教师</Button>
          <Button icon={<RobotOutlined />} onClick={autoAssignAll}>全自动分配任教</Button>
        </Space>
        <Table loading={loading} rowKey="id" size="small" dataSource={teachers} columns={[
          { title: '姓名', dataIndex: 'name', width: 120, fixed: 'left' },
          { title: '任教科目', dataIndex: 'subject_name', width: 120 },
          {
            title: '课时负载', width: 150,
            render: (_: unknown, teacher: Teacher) => {
              const assigned = assignedHoursForTeacher(teacher);
              const overloaded = assigned > teacher.max_weekly_hours;
              return <Typography.Text type={overloaded ? 'danger' : undefined} strong>{assigned} / {teacher.max_weekly_hours} 节</Typography.Text>;
            },
          },
          {
            title: '任教班级',
            render: (_: unknown, teacher: Teacher) => {
              const teacherAssignments = assignmentsForTeacher(teacher.id);
              return teacherAssignments.length
                ? <Space size={[4, 4]} wrap>{teacherAssignments.map(item => <Tag key={item.id} color="blue">{item.class_name}</Tag>)}</Space>
                : <Typography.Text type="secondary">暂未分配</Typography.Text>;
            },
          },
          {
            title: '操作', width: 190, fixed: 'right',
            render: (_: unknown, teacher: Teacher) => <Space>
              <Button size="small" onClick={() => openAssignmentEditor(teacher)}>分配班级</Button>
              <Popconfirm title="确定删除该教师？" onConfirm={() => api.deleteTeacher(teacher.id).then(loadAll)}><Button size="small" danger icon={<DeleteOutlined />} /></Popconfirm>
            </Space>,
          },
        ]} scroll={{ x: 850 }} />
      </>,
    },
  ];

  const gradeHourFields = (formPrefix: string) => grades.map(grade => (
    <Form.Item
      key={grade.id}
      name={[formPrefix, grade.id]}
      label={`${grade.name}周课时`}
      initialValue={0}
      rules={[{ required: true, message: `请填写${grade.name}周课时` }]}
    >
      <InputNumber min={0} max={40} precision={0} addonAfter="节/周" style={{ width: '100%' }} />
    </Form.Item>
  ));

  return <Card title="基础数据管理">
    <Tabs items={tabItems} />

    <Modal title="新增年级" open={gradeModal} onOk={onGradeOk} onCancel={() => setGradeModal(false)} okText="保存">
      <Form form={gradeForm} layout="vertical">
        <Form.Item name="name" label="年级名称" rules={[{ required: true }]}><Input placeholder="例如：一年级" /></Form.Item>
        <Space wrap>{DAYS.map(day => <Form.Item key={day.key} name={`p_${day.key}`} label={`${day.label}节次`} initialValue={6}><InputNumber min={1} max={12} /></Form.Item>)}</Space>
      </Form>
    </Modal>

    <Modal title="新增班级" open={classModal} onOk={onClassOk} onCancel={() => setClassModal(false)} okText="保存">
      <Form form={classForm} layout="vertical">
        <Form.Item name="name" label="班级名称" rules={[{ required: true }]}><Input placeholder="例如：一（1）班" /></Form.Item>
        <Form.Item name="grade_id" label="所属年级" rules={[{ required: true }]}><Select options={grades.map(item => ({ label: item.name, value: item.id }))} /></Form.Item>
      </Form>
    </Modal>

    <Modal title="新增科目并设置周课时" open={subjectModal} onOk={onSubjectOk} onCancel={() => setSubjectModal(false)} okText="保存科目" width={560} okButtonProps={{ disabled: !grades.length }}>
      <Form form={subjectForm} layout="vertical">
        <Form.Item name="name" label="科目名称" rules={[{ required: true }]}><Input placeholder="例如：语文" /></Form.Item>
        <Form.Item name="category" label="科目类型" initialValue="minor"><Select options={CATEGORIES.map(({ label, value }) => ({ label, value }))} /></Form.Item>
        <Typography.Title level={5}>各年级周课时</Typography.Title>
        <Typography.Paragraph type="secondary">请一次配置完整，填写 0 表示该年级不开设这门课。</Typography.Paragraph>
        {grades.length ? <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>{gradeHourFields('weekly_hours_by_grade')}</div> : <Alert type="warning" message="请先创建年级，再添加科目" />}
      </Form>
    </Modal>

    <Modal forceRender title={`设置周课时${editingSubject ? ` · ${editingSubject.name}` : ''}`} open={hoursModal} onOk={saveHours} onCancel={() => setHoursModal(false)} okText="保存课时" width={560}>
      <Form form={hoursForm} layout="vertical">
        <Alert type="info" showIcon message="修改后会影响下一次智能排课，已经生成的历史版本不会改变。" style={{ marginBottom: 16 }} />
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>{gradeHourFields('weekly_hours_by_grade')}</div>
      </Form>
    </Modal>

    <Modal title="批量新增教师" open={teacherModal} onOk={onTeacherOk} onCancel={() => setTeacherModal(false)} okText="保存">
      <Form form={teacherForm} layout="vertical">
        <Form.Item name="names" label="姓名（每行一名）" rules={[{ required: true }]}><Input.TextArea rows={5} placeholder={'王老师\n李老师\n张老师'} /></Form.Item>
        <Form.Item name="subject_id" label="任教科目" rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={subjects.map(item => ({ label: item.name, value: item.id }))} /></Form.Item>
        <Form.Item name="max_weekly_hours" label="教师周课时上限" initialValue={18}><InputNumber min={1} max={40} /></Form.Item>
        <Form.Item name="auto_assign" valuePropName="checked" initialValue={true}>
          <Checkbox>添加完成后，按工作量重新均衡该科目的全部班级</Checkbox>
        </Form.Item>
      </Form>
    </Modal>

    <Modal forceRender title={`分配任教班级${assignmentTeacher ? ` · ${assignmentTeacher.name}（${assignmentTeacher.subject_name}）` : ''}`} open={assignmentModal} onOk={onAssignmentOk} onCancel={() => setAssignmentModal(false)} okText="保存分配">
      <Form form={assignmentForm} layout="vertical">
        <Alert type="info" showIcon message="这里只显示已开设该科目的班级；取消选择可解除分配，选择已由其他教师负责的班级会自动转交。" style={{ marginBottom: 16 }} />
        <Form.Item name="class_ids" label="负责班级（可多选）">
          <Select
            mode="multiple"
            allowClear
            placeholder="选择该教师负责的班级"
            options={eligibleClassesForTeacher(assignmentTeacher).map(item => ({ label: `${item.name}（${item.grade_name}）`, value: item.id }))}
          />
        </Form.Item>
        {assignmentTeacher && <Typography.Text type="secondary">教师周课时上限：{assignmentTeacher.max_weekly_hours} 节，保存时后端会重新计算并阻止超额分配。</Typography.Text>}
      </Form>
    </Modal>
  </Card>;
};

export default DataManagement;
