import React, { useEffect, useState } from 'react';
import { BrowserRouter, Routes, Route, Link, Navigate, useLocation } from 'react-router-dom';
import { Alert, Button, Dropdown, Form, Input, Layout, Menu, Modal, Space, Spin, Typography, message } from 'antd';
import { DatabaseOutlined, LogoutOutlined, PlayCircleOutlined, SafetyCertificateOutlined, SettingOutlined, SlidersOutlined, TableOutlined, UserOutlined } from '@ant-design/icons';
import * as api from './api';
import type { User } from './types';
import DataManagement from './pages/DataManagement';
import Login from './pages/Login';
import RuleManagement from './pages/RuleManagement';
import ScheduleConfig from './pages/ScheduleConfig';
import ScheduleGenerate from './pages/ScheduleGenerate';
import ScheduleView from './pages/ScheduleView';
import SystemManagement from './pages/SystemManagement';
import AssistantDrawer from './components/assistant/AssistantDrawer';

const { Content, Header, Sider } = Layout;

const Shell: React.FC<{ user: User; onLogout: () => void; onUserChange: (user: User) => void }> = ({ user, onLogout, onUserChange }) => {
  const location = useLocation();
  const [passwordOpen, setPasswordOpen] = useState(user.must_change_password);
  const [form] = Form.useForm();
  const menuItems = [
    { key: '/data', icon: <DatabaseOutlined />, label: <Link to="/data">数据管理</Link> },
    { key: '/schedule/config', icon: <SettingOutlined />, label: <Link to="/schedule/config">固定时段</Link> },
    { key: '/schedule/rules', icon: <SlidersOutlined />, label: <Link to="/schedule/rules">排课规则</Link> },
    { key: '/schedule/generate', icon: <PlayCircleOutlined />, label: <Link to="/schedule/generate">排课操作</Link> },
    { key: '/schedule/view', icon: <TableOutlined />, label: <Link to="/schedule/view">课表查看</Link> },
    ...(user.role !== 'operator' ? [{ key: '/system', icon: <SafetyCertificateOutlined />, label: <Link to="/system">系统管理</Link> }] : []),
  ];

  const changePassword = async () => {
    const values = await form.validateFields();
    await api.changePassword(values.current_password, values.new_password);
    message.success('密码修改成功');
    setPasswordOpen(false);
    form.resetFields();
    onUserChange({ ...user, must_change_password: false });
  };

  return <Layout style={{ minHeight: '100vh' }}>
    <Sider breakpoint="lg" collapsedWidth="0">
      <div style={{ height: 32, margin: 16, color: '#fff', fontSize: 18, fontWeight: 'bold', textAlign: 'center' }}>排课系统</div>
      <Menu theme="dark" mode="inline" selectedKeys={[location.pathname]} items={menuItems} />
    </Sider>
    <Layout>
      <Header style={{ background: '#fff', padding: '0 24px', display: 'flex', justifyContent: 'flex-end', alignItems: 'center' }}>
        <Dropdown menu={{ items: [
          { key: 'password', label: '修改密码', onClick: () => setPasswordOpen(true) },
          { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: onLogout },
        ] }}>
          <Button type="text" icon={<UserOutlined />}>{user.display_name}</Button>
        </Dropdown>
      </Header>
      <Content style={{ margin: 24 }}>
        <Routes>
          <Route path="/" element={<Navigate to="/data" />} />
          <Route path="/data" element={<DataManagement />} />
          <Route path="/schedule/config" element={<ScheduleConfig />} />
          {/* 规则页要求管理员 —— 规则会改变全校的排课结果，operator 不该动 */}
          <Route path="/schedule/rules" element={user.role !== 'operator' ? <RuleManagement /> : <Navigate to="/data" />} />
          <Route path="/schedule/generate" element={<ScheduleGenerate />} />
          <Route path="/schedule/view" element={<ScheduleView user={user} />} />
          <Route path="/system" element={user.role !== 'operator' ? <SystemManagement currentUser={user} /> : <Navigate to="/data" />} />
          <Route path="*" element={<Navigate to="/data" />} />
        </Routes>
      </Content>
    </Layout>
    <Modal title="修改密码" open={passwordOpen} onOk={changePassword} closable={!user.must_change_password}
      maskClosable={!user.must_change_password} onCancel={() => !user.must_change_password && setPasswordOpen(false)}>
      {user.must_change_password && <Alert type="warning" message="首次登录必须修改初始密码" style={{ marginBottom: 16 }} />}
      <Form form={form} layout="vertical">
        <Form.Item name="current_password" label="当前密码" rules={[{ required: true }]}><Input.Password /></Form.Item>
        <Form.Item name="new_password" label="新密码" rules={[{ required: true, min: 8, message: '密码至少 8 位' }]}><Input.Password /></Form.Item>
        <Form.Item name="confirm" label="确认新密码" dependencies={['new_password']} rules={[{ required: true }, ({ getFieldValue }) => ({ validator(_, value) { return !value || getFieldValue('new_password') === value ? Promise.resolve() : Promise.reject(new Error('两次密码不一致')); } })]}><Input.Password /></Form.Item>
      </Form>
    </Modal>
    <AssistantDrawer />
  </Layout>;
};

const App: React.FC = () => {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const logout = () => { localStorage.removeItem('orbit_token'); setUser(null); };

  useEffect(() => {
    const unauthorized = () => setUser(null);
    window.addEventListener('orbit:unauthorized', unauthorized);
    if (localStorage.getItem('orbit_token')) api.getMe().then(setUser).catch(() => logout()).finally(() => setLoading(false));
    else setLoading(false);
    return () => window.removeEventListener('orbit:unauthorized', unauthorized);
  }, []);

  if (loading) return <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center' }}><Space direction="vertical"><Spin size="large" /><Typography.Text>正在验证登录...</Typography.Text></Space></div>;
  if (!user) return <Login onLogin={setUser} />;
  return <BrowserRouter><Shell user={user} onLogout={logout} onUserChange={setUser} /></BrowserRouter>;
};

export default App;
