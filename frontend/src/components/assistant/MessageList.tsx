import React from 'react';
import { Empty, Space, Typography } from 'antd';
import type { AssistantAction, AssistantMessage } from '../../types';
import ProposalCard from './ProposalCard';
import ToolCallTrace from './ToolCallTrace';

interface Props {
  messages: AssistantMessage[];
  actions: AssistantAction[];
  streamText: string;
  streaming: boolean;
  onActionChanged: () => void;
}

const MessageList: React.FC<Props> = ({ messages, actions, streamText, streaming, onActionChanged }) => {
  if (!messages.length && !streaming) return <Empty description="可以从下方示例开始提问" />;
  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    {messages.map(message => {
      const turnActions = actions.filter(action => action.turn_index === message.turn_index);
      return <div key={message.id} style={{
        display: 'flex', justifyContent: message.role === 'user' ? 'flex-end' : 'flex-start',
      }}>
        <div style={{
          maxWidth: '88%', borderRadius: 12, padding: '10px 12px',
          background: message.role === 'user' ? '#1677ff' : '#f5f5f5',
          color: message.role === 'user' ? '#fff' : undefined,
        }}>
          <Typography.Paragraph style={{ margin: 0, color: 'inherit', whiteSpace: 'pre-wrap' }}>
            {message.content}
          </Typography.Paragraph>
          {message.role === 'assistant' && <ToolCallTrace actions={turnActions} />}
          {message.role === 'assistant' && turnActions.filter(a => a.proposal).map(action =>
            <ProposalCard key={action.id} action={action} onChanged={onActionChanged} />)}
        </div>
      </div>;
    })}
    {streaming && <div style={{ display: 'flex', justifyContent: 'flex-start' }}>
      <div style={{ maxWidth: '88%', borderRadius: 12, padding: '10px 12px', background: '#f5f5f5' }}>
        <Typography.Paragraph style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
          {streamText || '正在查询业务系统…'}
        </Typography.Paragraph>
      </div>
    </div>}
  </Space>;
};

export default MessageList;
