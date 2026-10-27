import React, { useState } from 'react';
import { Alert, Button, Card, Form, Input, Typography } from 'antd';
import * as api from '../api';
import type { User } from '../types';

interface Props { onLogin: (user: User) => void }

const Login: React.FC<Props> = ({ onLogin }) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const submit = async (values: { username: string; password: string }) => {
    setLoading(true);
    setError('');
    try {
      const result = await api.login(values.username, values.password);
      localStorage.setItem('orbit_token', result.access_token);
      onLogin(result.user);
    } catch (err: any) {
      setError(err.response?.data?.detail ?? '登录失败，请检查网络连接');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: '#f0f2f5' }}>
      <Card style={{ width: 380 }}>
        <Typography.Title level={3} style={{ textAlign: 'center' }}>排课系统登录</Typography.Title>
        {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} />}
        <Form layout="vertical" onFinish={submit} initialValues={{ username: 'admin' }}>
          <Form.Item name="username" label="用户名" rules={[{ required: true }]}><Input autoFocus /></Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true }]}><Input.Password /></Form.Item>
          <Button type="primary" htmlType="submit" loading={loading} block>登录</Button>
        </Form>
      </Card>
    </div>
  );
};

export default Login;
