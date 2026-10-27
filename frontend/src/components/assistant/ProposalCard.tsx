import React, { useState } from 'react';
import { Alert, Button, Card, Popconfirm, Space, Tag, Typography, message } from 'antd';
import * as api from '../../api';
import type { AssistantAction } from '../../types';

const LABEL: Record<string, string> = {
  propose_rule: '规则草案',
  propose_swap: '换课草案',
  propose_reschedule: '重新排课草案',
};

const ProposalCard: React.FC<{ action: AssistantAction; onChanged: () => void }> = ({ action, onChanged }) => {
  const [loading, setLoading] = useState(false);
  const pending = action.status === 'proposed';

  const decide = async (accept: boolean) => {
    setLoading(true);
    try {
      const updated = accept
        ? await api.confirmAssistantAction(action.id)
        : await api.rejectAssistantAction(action.id);
      if (updated.status === 'failed') message.error(updated.error_detail ?? '草案执行失败');
      else message.success(accept ? '草案已确认执行' : '草案已取消');
      onChanged();
    } catch (error: any) {
      message.error(error?.response?.data?.detail ?? error?.message ?? '操作失败');
    } finally {
      setLoading(false);
    }
  };

  return <Card
    size="small"
    title={LABEL[action.tool_name] ?? '助手草案'}
    style={{ marginTop: 8, borderColor: pending ? '#91caff' : undefined }}
    extra={<Tag color={pending ? 'processing' : action.status === 'confirmed' ? 'success' : action.status === 'failed' ? 'error' : 'default'}>
      {pending ? '等待确认' : action.status === 'confirmed' ? '已执行' : action.status === 'rejected' ? '已取消' : '执行失败'}
    </Tag>}
  >
    {action.error_detail && <Alert type="error" showIcon message={action.error_detail} style={{ marginBottom: 8 }} />}
    <pre style={{ whiteSpace: 'pre-wrap', maxHeight: 280, overflow: 'auto', background: '#fafafa', padding: 10 }}>
      {JSON.stringify(action.proposal, null, 2)}
    </pre>
    {pending && <Space>
      <Popconfirm
        title="确认执行这份草案？"
        description="系统会再次检查 revision 和业务冲突。"
        onConfirm={() => decide(true)}
      >
        <Button type="primary" loading={loading}>确认执行</Button>
      </Popconfirm>
      <Button onClick={() => decide(false)} disabled={loading}>取消草案</Button>
      <Typography.Text type="secondary">助手不会自动执行</Typography.Text>
    </Space>}
  </Card>;
};

export default ProposalCard;
