import React, { useCallback, useEffect, useState } from 'react';
import { Button, Card, Descriptions, Form, Input, Modal, Popconfirm, Select, Space, Switch, Table, Tabs, Tag, message } from 'antd';
import { DeleteOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import * as api from '../api';
import type { Organization, StorageStatus, User } from '../types';

const labels: Record<string, string> = {
  users: '账号', grades: '年级', classes: '班级', subjects: '科目', teachers: '教师',
  grade_subjects: '年级课时', teacher_assignments: '任教关系', fixed_slots: '固定时段', schedules: '课表记录',
};

const SystemManagement: React.FC<{ currentUser: User }> = ({ currentUser }) => {
  const [users, setUsers] = useState<User[]>([]);
  const [storage, setStorage] = useState<StorageStatus | null>(null);
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [open, setOpen] = useState(false);
  const [organizationOpen, setOrganizationOpen] = useState(false);
  const [form] = Form.useForm();
  const [organizationForm] = Form.useForm();

  const loadUsers = useCallback(() => { api.getUsers().then(setUsers); }, []);
  const loadStorage = useCallback(() => { api.getStorageStatus().then(setStorage); }, []);
  useEffect(() => { loadUsers(); loadStorage(); }, [loadUsers, loadStorage]);
  useEffect(() => { if (currentUser.role === 'platform_admin') api.getOrganizations().then(setOrganizations); }, [currentUser.role]);

  const addUser = async () => {
    const values = await form.validateFields();
    await api.createUser(values);
    message.success('账号已创建，首次登录需修改密码');
    setOpen(false);
    form.resetFields();
    loadUsers();
  };

  const addOrganization = async () => {
    const values = await organizationForm.validateFields();
    await api.createOrganization(values);
    message.success('工作空间及首位管理员已创建');
    setOrganizationOpen(false);
    organizationForm.resetFields();
    setOrganizations(await api.getOrganizations());
  };

  const columns = [
    { title: '用户名', dataIndex: 'username' },
    { title: '姓名', dataIndex: 'display_name' },
    { title: '角色', dataIndex: 'role', render: (v: string) => <Tag color={v === 'platform_admin' ? 'purple' : v === 'admin' ? 'red' : 'blue'}>{v === 'platform_admin' ? '平台管理员' : v === 'admin' ? '管理员' : '排课员'}</Tag> },
    { title: '首次改密', dataIndex: 'must_change_password', render: (v: boolean) => v ? '是' : '否' },
    { title: '启用', render: (_: unknown, user: User) => <Switch checked={user.is_active} onChange={async checked => { await api.updateUser(user.id, { is_active: checked }); loadUsers(); }} /> },
    { title: '操作', render: (_: unknown, user: User) => <Button size="small" onClick={() => Modal.confirm({
      title: `重置 ${user.username} 的密码`, content: <Input.Password id={`reset-${user.id}`} placeholder="至少 8 位新密码" />,
      onOk: async () => {
        const input = document.getElementById(`reset-${user.id}`) as HTMLInputElement;
        if (!input?.value || input.value.length < 8) throw new Error('密码至少需要 8 位');
        await api.updateUser(user.id, { password: input.value });
        message.success('密码已重置'); loadUsers();
      },
    })}>重置密码</Button> },
  ];

  const size = storage?.database_size_bytes == null ? '-' : `${(storage.database_size_bytes / 1024 / 1024).toFixed(2)} MB`;
  const storageTab = storage && <Space direction="vertical" size="large" style={{ width: '100%' }}>
    <Descriptions bordered column={1} items={[
      { key: 'type', label: '数据库类型', children: storage.database_type },
      { key: 'path', label: '数据库位置', children: storage.database_path },
      { key: 'size', label: '占用空间', children: size },
      { key: 'redis', label: 'Redis', children: storage.redis.available ? `已连接（${storage.redis.used_memory_human ?? '运行中'}）` : '未连接，当前使用 MySQL 锁兜底' },
    ]} />
    <Descriptions bordered title="数据记录" items={Object.entries(storage.counts).map(([key, value]) => ({ key, label: labels[key] ?? key, children: value }))} />
    <Space>
      <Button icon={<ReloadOutlined />} onClick={loadStorage}>刷新</Button>
      <Popconfirm title="整理数据库空闲空间？" onConfirm={async () => { await api.compactDatabase(); message.success('空间整理完成'); loadStorage(); }}>
        <Button>整理空间</Button>
      </Popconfirm>
      <Popconfirm title="确定删除全部已生成课表？基础配置不会删除。" onConfirm={async () => { const r = await api.clearGeneratedSchedules(); message.success(`已删除 ${r.deleted} 条课表记录`); loadStorage(); }}>
        <Button danger icon={<DeleteOutlined />}>清空生成课表</Button>
      </Popconfirm>
    </Space>
  </Space>;

  return <Card title="系统管理">
    <Tabs items={[
      { key: 'users', label: '账号管理', children: <><Descriptions size="small" style={{ marginBottom: 16 }} items={[{ key: 'org', label: '当前工作空间', children: currentUser.organization_name ?? `#${currentUser.organization_id}` }]} /><Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)} style={{ marginBottom: 16 }}>新增本校账号</Button><Table rowKey="id" columns={columns} dataSource={users} /></> },
      { key: 'storage', label: '空间管理', children: storageTab },
      ...(currentUser.role === 'platform_admin' ? [{ key: 'organizations', label: '工作空间管理', children: <><Button type="primary" icon={<PlusOutlined />} onClick={() => setOrganizationOpen(true)} style={{ marginBottom: 16 }}>新增工作空间</Button><Table rowKey="id" dataSource={organizations} columns={[{ title: 'ID', dataIndex: 'id' }, { title: '学校/工作空间', dataIndex: 'name' }, { title: '创建时间', dataIndex: 'created_at', render: (v: string) => new Date(v).toLocaleString() }]} /></> }] : []),
    ]} />
    <Modal title="新增账号" open={open} onOk={addUser} onCancel={() => setOpen(false)}>
      <Form form={form} layout="vertical" initialValues={{ role: 'operator' }}>
        <Form.Item name="username" label="用户名" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="display_name" label="显示姓名" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="password" label="初始密码" rules={[{ required: true, min: 8 }]}><Input.Password /></Form.Item>
        <Form.Item name="role" label="角色"><Select options={[{ label: '排课员', value: 'operator' }, { label: '管理员', value: 'admin' }]} /></Form.Item>
      </Form>
    </Modal>
    <Modal title="新增学校工作空间" open={organizationOpen} onOk={addOrganization} onCancel={() => setOrganizationOpen(false)}>
      <Form form={organizationForm} layout="vertical">
        <Form.Item name="name" label="学校/工作空间名称" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="admin_username" label="首位管理员用户名" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="admin_display_name" label="管理员姓名" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="admin_password" label="管理员初始密码" rules={[{ required: true, min: 8 }]}><Input.Password /></Form.Item>
      </Form>
    </Modal>
  </Card>;
};

export default SystemManagement;
