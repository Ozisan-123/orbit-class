import React from 'react';
import { Collapse, Space, Tag, Typography } from 'antd';
import type { AssistantAction } from '../../types';

const STATUS: Record<string, { label: string; color: string }> = {
  completed: { label: '查询完成', color: 'success' },
  proposed: { label: '等待确认', color: 'processing' },
  confirmed: { label: '已执行', color: 'success' },
  rejected: { label: '已取消', color: 'default' },
  failed: { label: '执行失败', color: 'error' },
};

const ToolCallTrace: React.FC<{ actions: AssistantAction[] }> = ({ actions }) => {
  if (!actions.length) return null;
  return <Collapse
    size="small"
    style={{ marginTop: 8 }}
    items={actions.map(action => ({
      key: action.id,
      label: <Space wrap>
        <Typography.Text code>{action.tool_name}</Typography.Text>
        <Tag color={STATUS[action.status]?.color}>{STATUS[action.status]?.label ?? action.status}</Tag>
      </Space>,
      children: <Space direction="vertical" style={{ width: '100%' }}>
        <div><Typography.Text strong>参数</Typography.Text></div>
        <pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{JSON.stringify(action.arguments, null, 2)}</pre>
        {action.result && <>
          <div><Typography.Text strong>结果</Typography.Text></div>
          <pre style={{ whiteSpace: 'pre-wrap', margin: 0, maxHeight: 260, overflow: 'auto' }}>{JSON.stringify(action.result, null, 2)}</pre>
        </>}
        {action.error_detail && <Typography.Text type="danger">{action.error_detail}</Typography.Text>}
      </Space>,
    }))}
  />;
};

export default ToolCallTrace;
