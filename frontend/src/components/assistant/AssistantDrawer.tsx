import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert, Button, Drawer, Empty, Input, List, Popconfirm, Progress, Space, Tag, Typography, message,
} from 'antd';
import { DeleteOutlined, PlusOutlined, RobotOutlined, SendOutlined } from '@ant-design/icons';
import * as api from '../../api';
import { useAssistantStore } from '../../stores/assistantStore';
import MessageList from './MessageList';

const EXAMPLES = [
  '检查当前排课配置',
  '评分版本 1',
  '深度诊断版本 1',
  '查看任务 1',
  '教师 1 在版本 1 周三第 2 节是否空闲',
];

const AssistantDrawer: React.FC = () => {
  const state = useAssistantStore();
  const [input, setInput] = useState('');
  const bottomRef = useRef<HTMLDivElement>(null);

  const loadSidebar = useCallback(async () => {
    const [conversations, usage] = await Promise.all([
      api.getAssistantConversations(), api.getAssistantUsage(),
    ]);
    useAssistantStore.getState().set({ conversations, usage });
  }, []);

  const loadConversation = async (id: number) => {
    const [messages, actions] = await Promise.all([
      api.getAssistantMessages(id), api.getAssistantActions(id),
    ]);
    state.set({ conversationId: id, messages, actions, streamText: '', error: undefined });
  };

  useEffect(() => {
    if (state.open) loadSidebar().catch(() => useAssistantStore.getState().set({ error: '助手数据加载失败' }));
  }, [loadSidebar, state.open]);

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [state.messages, state.streamText]);

  const send = async (preset?: string) => {
    const text = (preset ?? input).trim();
    if (!text || state.streaming) return;
    setInput('');
    state.set({ streaming: true, streamText: '', error: undefined, liveTool: undefined });
    try {
      await api.streamAssistantChat({ conversation_id: state.conversationId, message: text }, event => {
        if (event.event === 'meta') state.set({ conversationId: event.data.conversation_id });
        if (event.event === 'token') state.appendStream(event.data.text ?? '');
        if (event.event === 'tool_call') state.set({ liveTool: { tool: event.data.tool, arguments: event.data.arguments } });
        if (event.event === 'error') state.set({ error: event.data.detail ?? '助手请求失败' });
      });
      const id = useAssistantStore.getState().conversationId;
      if (id) await loadConversation(id);
      await loadSidebar();
    } catch (error: any) {
      state.set({ error: error.message ?? '助手请求失败' });
    } finally {
      state.set({ streaming: false, streamText: '', liveTool: undefined });
    }
  };

  const remove = async (id: number) => {
    await api.deleteAssistantConversation(id);
    if (state.conversationId === id) state.resetConversation();
    await loadSidebar();
    message.success('会话已删除');
  };

  const usagePercent = state.usage
    ? Math.min(100, Math.round(state.usage.used_tokens * 100 / Math.max(1, state.usage.daily_budget))) : 0;

  return <>
    <Button
      type="primary" shape="circle" size="large" icon={<RobotOutlined />}
      aria-label="打开排课助手"
      onClick={() => state.set({ open: true })}
      style={{ position: 'fixed', right: 28, bottom: 28, zIndex: 1000, boxShadow: '0 6px 20px rgba(22,119,255,.35)' }}
    />
    <Drawer
      title={<Space><RobotOutlined />排课助手<Tag color={state.usage?.mode === 'llm' ? 'purple' : 'blue'}>{state.usage?.mode === 'llm' ? '模型模式' : '本地模式'}</Tag></Space>}
      open={state.open}
      onClose={() => state.set({ open: false })}
      width={820}
      styles={{ body: { padding: 0 } }}
    >
      <div style={{ display: 'grid', gridTemplateColumns: '230px 1fr', height: '100%' }}>
        <div style={{ borderRight: '1px solid #f0f0f0', padding: 12, overflow: 'auto' }}>
          <Button block type="primary" icon={<PlusOutlined />} onClick={state.resetConversation}>新会话</Button>
          <List
            size="small"
            style={{ marginTop: 10 }}
            dataSource={state.conversations}
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无会话" /> }}
            renderItem={item => <List.Item
              onClick={() => loadConversation(item.id)}
              style={{ cursor: 'pointer', background: item.id === state.conversationId ? '#e6f4ff' : undefined, padding: 8 }}
              actions={[<Popconfirm key="delete" title="删除这段会话？" onConfirm={event => { event?.stopPropagation(); remove(item.id); }}><DeleteOutlined onClick={event => event.stopPropagation()} /></Popconfirm>]}
            >
              <Typography.Text ellipsis style={{ maxWidth: 150 }}>{item.title}</Typography.Text>
            </List.Item>}
          />
          {state.usage && <div style={{ marginTop: 16 }}>
            <Typography.Text type="secondary">今日额度</Typography.Text>
            <Progress percent={usagePercent} size="small" />
            <Typography.Text type="secondary">剩余约 {state.usage.remaining_tokens.toLocaleString()} tokens</Typography.Text>
          </div>}
        </div>
        <div style={{ display: 'grid', gridTemplateRows: '1fr auto', minWidth: 0 }}>
          <div style={{ padding: 16, overflow: 'auto' }}>
            {state.error && <Alert type="warning" showIcon closable message={state.error} onClose={() => state.set({ error: undefined })} style={{ marginBottom: 12 }} />}
            {!state.messages.length && !state.streaming && <Space wrap style={{ marginBottom: 16 }}>
              {EXAMPLES.map(example => <Button key={example} size="small" onClick={() => send(example)}>{example}</Button>)}
            </Space>}
            <MessageList
              messages={state.messages}
              actions={state.actions}
              streamText={state.streamText}
              streaming={state.streaming}
              onActionChanged={() => state.conversationId && loadConversation(state.conversationId)}
            />
            <div ref={bottomRef} />
          </div>
          <div style={{ padding: 12, borderTop: '1px solid #f0f0f0' }}>
            {state.liveTool && <Typography.Text type="secondary">正在调用 {state.liveTool.tool}…</Typography.Text>}
            <Space.Compact style={{ width: '100%', marginTop: 6 }}>
              <Input.TextArea
                value={input}
                onChange={event => setInput(event.target.value)}
                onPressEnter={event => { if (!event.shiftKey) { event.preventDefault(); send(); } }}
                autoSize={{ minRows: 2, maxRows: 5 }}
                placeholder="询问课表、空闲、任务或诊断；写操作只会生成草案"
                disabled={state.streaming}
              />
              <Button type="primary" icon={<SendOutlined />} onClick={() => send()} loading={state.streaming}>发送</Button>
            </Space.Compact>
          </div>
        </div>
      </div>
    </Drawer>
  </>;
};

export default AssistantDrawer;
