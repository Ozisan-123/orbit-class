import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert, Card, Collapse, Empty, Progress, Select, Space, Spin, Statistic, Tag, Typography,
} from 'antd';
import { ArrowDownOutlined, ArrowUpOutlined, MinusOutlined } from '@ant-design/icons';
import * as api from '../api';
import type { ScheduleScore, ScheduleScoreDiff, ScheduleVersion } from '../types';

const errorText = (error: any) => error?.response?.data?.detail ?? '评分加载失败';

interface Props {
  versionId?: number;
  revision?: number;
  versions: ScheduleVersion[];
}

const deltaTag = (delta: number) => {
  if (delta > 0) return <Tag color="success" icon={<ArrowUpOutlined />}>+{delta}</Tag>;
  if (delta < 0) return <Tag color="error" icon={<ArrowDownOutlined />}>{delta}</Tag>;
  return <Tag icon={<MinusOutlined />}>0</Tag>;
};

const QualityScoreCard: React.FC<Props> = ({ versionId, revision, versions }) => {
  const [score, setScore] = useState<ScheduleScore>();
  const [diff, setDiff] = useState<ScheduleScoreDiff>();
  const [against, setAgainst] = useState<number>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();

  const candidates = useMemo(
    () => versions.filter(item => item.id !== versionId),
    [versionId, versions],
  );

  useEffect(() => {
    setAgainst(undefined);
    setDiff(undefined);
    if (!versionId) return;
    setLoading(true);
    setError(undefined);
    api.getScheduleScore(versionId)
      .then(setScore)
      .catch(err => { setScore(undefined); setError(errorText(err)); })
      .finally(() => setLoading(false));
  }, [revision, versionId]);

  const compare = async (target?: number) => {
    setAgainst(target);
    setDiff(undefined);
    if (!versionId || !target) return;
    setLoading(true);
    setError(undefined);
    try {
      setDiff(await api.getScheduleScoreDiff(versionId, target));
    } catch (err) {
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card
      size="small"
      title="课表质量评分"
      style={{ marginBottom: 16 }}
      extra={<Select
        allowClear
        placeholder="与历史版本对比"
        value={against}
        onChange={compare}
        style={{ width: 240 }}
        options={candidates.map(item => ({
          value: item.id,
          label: `V${item.version_no} · ${item.name}`,
        }))}
      />}
    >
      <Spin spinning={loading}>
        {error && <Alert showIcon type="warning" message={error} style={{ marginBottom: 12 }} />}
        {!score ? (!loading && !error ? <Empty description="暂无评分" /> : null) : <>
          {!score.valid && <Alert
            showIcon type="error" message="硬约束未通过，本版总分归零"
            description={score.hard_violations.join('；')}
            style={{ marginBottom: 14 }}
          />}
          {diff && diff.current.rule_set_hash !== diff.against.rule_set_hash && <Alert
            showIcon type="info" message="两个版本使用了不同规则集，分差仅供参考"
            style={{ marginBottom: 14 }}
          />}
          <div style={{ display: 'grid', gridTemplateColumns: '150px repeat(4, minmax(150px, 1fr))', gap: 16 }}>
            <div style={{ textAlign: 'center' }}>
              <Progress
                type="dashboard"
                percent={score.total_score}
                status={score.valid ? 'normal' : 'exception'}
                format={value => `${value ?? 0} 分`}
              />
              {diff && <div>较对比版本 {deltaTag(diff.total_delta)}</div>}
            </div>
            {score.dimensions.map(dimension => {
              const change = diff?.dimensions.find(item => item.key === dimension.key)?.delta;
              return <Card key={dimension.key} size="small">
                <Statistic
                  title={dimension.label}
                  value={dimension.earned_points}
                  suffix={`/ ${dimension.max_points}`}
                />
                <Progress
                  percent={Math.round(dimension.earned_points * 100 / dimension.max_points)}
                  showInfo={false}
                  size="small"
                />
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {dimension.explanation}
                </Typography.Text>
                {change !== undefined && <div style={{ marginTop: 6 }}>分项变化 {deltaTag(change)}</div>}
              </Card>;
            })}
          </div>
          <Collapse
            ghost
            style={{ marginTop: 8 }}
            items={[{
              key: 'rules',
              label: `规则逐项明细（${score.rule_breakdown.length} 条）`,
              children: score.rule_breakdown.length ? <Space direction="vertical" style={{ width: '100%' }}>
                {score.rule_breakdown.map((rule, index) => <div key={`${rule.rule_type}-${index}`}>
                  <Space wrap>
                    <Typography.Text code>{rule.rule_type}</Typography.Text>
                    <Tag color={rule.hardness === 'hard' ? 'red' : 'blue'}>{rule.hardness === 'hard' ? '硬约束' : '软规则'}</Tag>
                    <Tag>权重 {rule.weight}</Tag>
                    <Typography.Text>满足度 {rule.satisfaction_percent}%</Typography.Text>
                    <Typography.Text type="secondary">违规 {rule.violations} / 机会 {rule.opportunities}</Typography.Text>
                  </Space>
                  <div><Typography.Text type="secondary">{rule.explanation}</Typography.Text></div>
                </div>)}
              </Space> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="规则集没有可配置规则" />,
            }]}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            规则集指纹：{score.rule_set_hash.slice(0, 12)}… · 评分对应修订 #{score.revision}
          </Typography.Text>
        </>}
      </Spin>
    </Card>
  );
};

export default QualityScoreCard;
