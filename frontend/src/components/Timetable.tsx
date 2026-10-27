import React, { useRef, useState } from 'react';
import { Table, Tooltip } from 'antd';
import { HolderOutlined } from '@ant-design/icons';
import type { ScheduleEntry } from '../types';

const DAYS = ['周一', '周二', '周三', '周四', '周五'];
const COLORS = [
  ['#e6f4ff', '#1677ff'], ['#f6ffed', '#52c41a'], ['#fff7e6', '#fa8c16'],
  ['#fff0f6', '#eb2f96'], ['#f9f0ff', '#722ed1'], ['#e6fffb', '#13c2c2'],
];

interface Props {
  entries: ScheduleEntry[];
  periodsPerDay: number[];
  editable?: boolean;
  moving?: boolean;
  showTeacher?: boolean;
  colorize?: boolean;
  compact?: boolean;
  onMove?: (entryId: number, targetDay: number, targetPeriod: number) => void;
}

const Timetable: React.FC<Props> = ({
  entries,
  periodsPerDay,
  editable = false,
  moving = false,
  showTeacher = true,
  colorize = true,
  compact = false,
  onMove,
}) => {
  const [draggedId, setDraggedId] = useState<number | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [overCell, setOverCell] = useState<string | null>(null);
  const draggedRef = useRef(false);
  const maxPeriods = Math.max(...periodsPerDay, 1);

  const columns = [
    {
      title: '节次',
      dataIndex: 'period',
      key: 'period',
      width: 78,
      fixed: 'left' as const,
      align: 'center' as const,
      render: (_: unknown, __: unknown, index: number) => <strong>第 {index + 1} 节</strong>,
    },
    ...DAYS.map((day, dayIndex) => ({
      title: day,
      key: `day_${dayIndex}`,
      align: 'center' as const,
      render: (_: unknown, record: Record<string, ScheduleEntry | number | string | null>) => {
        const entry = record[`day_${dayIndex}`] as ScheduleEntry | null;
        const period = record.period as number;
        const cellKey = `${dayIndex}-${period}`;
        const palette = entry ? COLORS[entry.subject_id % COLORS.length] : undefined;
        const canDrop = editable && !moving && period < (periodsPerDay[dayIndex] ?? 0);
        return (
          <div
            className={`schedule-cell${overCell === cellKey ? ' schedule-cell-over' : ''}`}
            style={{ minHeight: compact ? 48 : 66 }}
            onClick={() => {
              if (!editable || moving || draggedRef.current) return;
              if (selectedId === null) {
                if (entry) setSelectedId(entry.id);
                return;
              }
              const source = entries.find(item => item.id === selectedId);
              setSelectedId(null);
              if (source && (source.day_of_week !== dayIndex || source.period_index !== period)) {
                onMove?.(source.id, dayIndex, period);
              }
            }}
            onDragOver={(event) => {
              if (!canDrop) return;
              event.preventDefault();
              event.dataTransfer.dropEffect = 'move';
              setOverCell(cellKey);
            }}
            onDragLeave={() => setOverCell(current => current === cellKey ? null : current)}
            onDrop={(event) => {
              event.preventDefault();
              const transferredId = Number(event.dataTransfer.getData('text/plain'));
              const sourceId = draggedId ?? (Number.isFinite(transferredId) ? transferredId : null);
              setOverCell(null);
              setDraggedId(null);
              setSelectedId(null);
              if (sourceId !== null) {
                onMove?.(sourceId, dayIndex, period);
              }
            }}
          >
            {entry ? (
              <Tooltip title={editable ? '拖到目标节次，或依次点击课程和目标格' : `${entry.subject_name} · ${entry.teacher_name}`}>
                <div
                  draggable={editable && !moving}
                  onDragStart={(event) => {
                    draggedRef.current = true;
                    setDraggedId(entry.id);
                    setSelectedId(null);
                    event.dataTransfer.effectAllowed = 'move';
                    event.dataTransfer.setData('text/plain', String(entry.id));
                  }}
                  onDragEnd={() => {
                    setDraggedId(null);
                    setOverCell(null);
                    window.setTimeout(() => { draggedRef.current = false; }, 0);
                  }}
                  className={`schedule-course${editable ? ' schedule-course-editable' : ''}${draggedId === entry.id ? ' schedule-course-dragging' : ''}${selectedId === entry.id ? ' schedule-course-selected' : ''}`}
                  style={colorize && palette ? { background: palette[0], borderColor: palette[1] } : undefined}
                >
                  {editable && <HolderOutlined className="schedule-drag-handle" />}
                  <div className="schedule-subject">{entry.subject_name}</div>
                  {showTeacher && <div className="schedule-teacher">{entry.teacher_name}</div>}
                </div>
              </Tooltip>
            ) : <span className="schedule-empty">{editable ? '拖到这里' : '—'}</span>}
          </div>
        );
      },
    })),
  ];

  const dataSource = Array.from({ length: maxPeriods }, (_, period) => {
    const row: Record<string, ScheduleEntry | number | string | null> = { key: period, period };
    DAYS.forEach((_, day) => {
      row[`day_${day}`] = entries.find(
        entry => entry.day_of_week === day && entry.period_index === period,
      ) ?? null;
    });
    return row;
  });

  return (
    <Table
      className="schedule-table"
      columns={columns}
      dataSource={dataSource}
      pagination={false}
      bordered
      size="small"
      scroll={{ x: 760 }}
      rowClassName={(_, index) => index % 2 ? 'schedule-row-alt' : ''}
    />
  );
};

export default Timetable;
