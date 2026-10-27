import React from 'react';
import { Alert, Descriptions, Modal, Space, Table, Tag, Typography } from 'antd';
import type { SwapPosition, SwapPreview } from '../types';

const DAYS = ['周一', '周二', '周三', '周四', '周五'];

interface Props {
  preview: SwapPreview | null;
  classNameOf?: (classId: number) => string;
  confirmLoading?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

const positionText = (position: SwapPosition, classNameOf?: (classId: number) => string) =>
  `${classNameOf?.(position.class_id) ?? `班级 #${position.class_id}`} · ` +
  `${DAYS[position.day_of_week] ?? `星期 ${position.day_of_week + 1}`}第 ${position.period_index + 1} 节`;

const SwapPreviewModal: React.FC<Props> = ({
  preview, classNameOf, confirmLoading = false, onConfirm, onCancel,
}) => (
  <Modal
    title="确认课程调整"
    open={preview !== null}
    onCancel={onCancel}
    onOk={onConfirm}
    okText="确认执行"
    cancelText="取消"
    okButtonProps={{ disabled: !preview?.can_swap }}
    confirmLoading={confirmLoading}
    width={760}
  >
    {preview && (
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Alert
          showIcon
          type={preview.can_swap ? 'success' : 'error'}
          message={preview.can_swap ? '校验通过，可以执行这次调整' : '这次调整存在冲突，不能执行'}
          description={preview.can_swap
            ? `确认时将使用草稿修订号 ${preview.expected_revision}；若期间有人修改，后端仍会拒绝覆盖。`
            : preview.conflicts.map(conflict => conflict.message).join('；')}
        />
        {preview.conflicts.length > 0 && (
          <Descriptions bordered size="small" column={1} items={preview.conflicts.map((conflict, index) => ({
            key: `${conflict.type}-${index}`,
            label: <Tag color="red">{conflict.type}</Tag>,
            children: conflict.message,
          }))} />
        )}
        {preview.affected_entries.length > 0 && (
          <Table
            rowKey="entry_id"
            size="small"
            pagination={false}
            dataSource={preview.affected_entries}
            columns={[
              { title: '课程条目', dataIndex: 'entry_id', width: 100, render: value => `#${value}` },
              {
                title: '调整前', dataIndex: 'before',
                render: (value: SwapPosition) => <Typography.Text>{positionText(value, classNameOf)}</Typography.Text>,
              },
              {
                title: '调整后', dataIndex: 'after',
                render: (value: SwapPosition) => <Typography.Text strong>{positionText(value, classNameOf)}</Typography.Text>,
              },
            ]}
          />
        )}
        {preview.can_swap && preview.affected_entries.length === 0 && (
          <Typography.Text type="secondary">目标位置与当前位置相同，不会产生实际变化。</Typography.Text>
        )}
      </Space>
    )}
  </Modal>
);

export default SwapPreviewModal;
