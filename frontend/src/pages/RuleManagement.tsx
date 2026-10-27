import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert, Button, Card, Descriptions, Divider, Empty, Form, Input, InputNumber, List,
  Modal, Popconfirm, Select, Space, Switch, Table, Tag, Tooltip, Typography, message,
} from 'antd';
import { PlusOutlined, ReloadOutlined, MedicineBoxOutlined, HistoryOutlined } from '@ant-design/icons';
import * as api from '../api';
import type { CurrentRuleSet, Diagnosis, Rule, RuleParamSpec, RuleTrial, RuleType, RuleVersion } from '../types';

const { Text, Paragraph } = Typography;

/**
 * 规则管理页（P2 新增）。
 *
 * <h2>这一页的核心是"表单由后端 schema 动态渲染"</h2>
 * `GET /api/rules/types` 返回的 `params` 就是 solver-core 里 `RuleParamSpec` 的投影，
 * 于是 {@link renderParamField} 能按 `type/min/max/enum_values` 直接渲染控件。
 *
 * **后果是：后端加一条规则类型，这一页一行都不用改** —— 新类型自动出现在下拉里，
 * 它的参数自动出现在表单里。这正是不把表单写死的意义。
 * （也是"规则注册表 = 助手的工具箱"那条设计的同一个来源。）
 */
const RuleManagement: React.FC = () => {
  const [rules, setRules] = useState<Rule[]>([]);
  const [configurableTypes, setConfigurableTypes] = useState<RuleType[]>([]);
  const [systemTypes, setSystemTypes] = useState<RuleType[]>([]);
  const [current, setCurrent] = useState<CurrentRuleSet | null>(null);
  const [versions, setVersions] = useState<RuleVersion[]>([]);
  const [diagnosis, setDiagnosis] = useState<Diagnosis | null>(null);
  const [loading, setLoading] = useState(true);
  const [diagnosing, setDiagnosing] = useState(false);

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Rule | null>(null);
  const [selectedType, setSelectedType] = useState<string | undefined>(undefined);
  const [trial, setTrial] = useState<RuleTrial | null>(null);
  const [trialLoading, setTrialLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [versionModalOpen, setVersionModalOpen] = useState(false);
  const [form] = Form.useForm();

  const fail = (e: any, fallback: string) => message.error(e?.response?.data?.detail ?? fallback);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [ruleList, types, allTypes, cur, vers] = await Promise.all([
        api.getRules(),
        api.getRuleTypes(true),
        api.getRuleTypes(false),
        api.getCurrentRuleSet(),
        api.getRuleVersions(),
      ]);
      setRules(ruleList);
      setConfigurableTypes(types);
      setSystemTypes(allTypes.filter((t) => t.mandatory));
      setCurrent(cur);
      setVersions(vers);
    } catch (e) {
      fail(e, '加载规则失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  // ---------------------------------------------------------------- 新建 / 编辑

  const openCreate = () => {
    setEditing(null);
    setSelectedType(undefined);
    setTrial(null);
    form.resetFields();
    form.setFieldsValue({ hardness: 'soft', weight: 1, enabled: true });
    setModalOpen(true);
  };

  const openEdit = (rule: Rule) => {
    setEditing(rule);
    setSelectedType(rule.rule_type);
    setTrial(null);
    form.resetFields();
    form.setFieldsValue({
      rule_type: rule.rule_type,
      hardness: rule.hardness,
      weight: rule.weight,
      description: rule.description,
      // 参数是动态字段，直接展开
      ...(rule.params ?? {}),
    });
    setModalOpen(true);
  };

  /** 只取 schema 里声明过的参数，避免把表单上残留的无关字段提交上去。 */
  const collectParams = (type?: RuleType): Record<string, unknown> => {
    if (!type) return {};
    const values = form.getFieldsValue(true);
    const params: Record<string, unknown> = {};
    type.params.forEach((spec) => {
      const value = values[spec.name];
      if (value !== undefined && value !== null && value !== '') {
        params[spec.name] = value;
      }
    });
    return params;
  };

  const currentType = (): RuleType | undefined =>
    configurableTypes.find((t) => t.rule_type === selectedType);

  const buildPayload = () => {
    const type = currentType();
    const values = form.getFieldsValue(true);
    return {
      rule_type: selectedType,
      params: collectParams(type),
      hardness: values.hardness,
      weight: values.weight,
      description: values.description,
    };
  };

  const doTrial = async () => {
    if (!selectedType) { message.warning('请先选择规则类型'); return; }
    setTrialLoading(true);
    try {
      setTrial(await api.trialRule(buildPayload()));
    } catch (e) {
      setTrial(null);
      fail(e, '试算失败');
    } finally {
      setTrialLoading(false);
    }
  };

  const save = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      if (editing) {
        await api.updateRule(editing.id, {
          params: collectParams(currentType()),
          hardness: values.hardness,
          weight: values.weight,
          description: values.description,
          expected_revision: editing.revision,
        });
        message.success('规则已更新');
      } else {
        await api.createRule(buildPayload());
        message.success('规则已创建');
      }
      setModalOpen(false);
      await load();
    } catch (e: any) {
      if (e?.errorFields) return;            // 表单校验失败，antd 已经提示了
      fail(e, '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const toggle = async (rule: Rule, enabled: boolean) => {
    try {
      await api.setRuleEnabled(rule.id, enabled, rule.revision);
      message.success(enabled ? '已启用' : '已停用');
      await load();
    } catch (e) {
      fail(e, '切换失败');
      await load();                          // 失败时刷新，把界面拉回真实状态
    }
  };

  const remove = async (rule: Rule) => {
    try {
      await api.deleteRule(rule.id, rule.revision);
      message.success('已删除');
      await load();
    } catch (e) {
      fail(e, '删除失败');
    }
  };

  // ---------------------------------------------------------------- 诊断

  const runDiagnosis = async (deep: boolean) => {
    setDiagnosing(true);
    try {
      setDiagnosis(await api.getDiagnosis(deep));
    } catch (e) {
      fail(e, '诊断失败');
    } finally {
      setDiagnosing(false);
    }
  };

  // ---------------------------------------------------------------- 动态表单

  /** 按 `RuleParamSpec` 渲染一个参数控件 —— 这一页"不用为每条规则写页面"的原因。 */
  const renderParamField = (spec: RuleParamSpec) => {
    const rules = spec.required
      ? [{ required: true, message: `请填写「${spec.label}」` }]
      : [];
    switch (spec.type) {
      case 'INT':
      case 'LONG':
        return (
          <Form.Item key={spec.name} name={spec.name} label={spec.label} rules={rules}
            extra={spec.min != null || spec.max != null
              ? `取值范围 ${spec.min ?? '-∞'} ~ ${spec.max ?? '+∞'}；留空表示"适用于全体"`
              : '留空表示"适用于全体"'}>
            <InputNumber style={{ width: '100%' }} min={spec.min ?? undefined} max={spec.max ?? undefined} />
          </Form.Item>
        );
      case 'BOOL':
        return (
          <Form.Item key={spec.name} name={spec.name} label={spec.label} valuePropName="checked" rules={rules}>
            <Switch />
          </Form.Item>
        );
      case 'ENUM':
        return (
          <Form.Item key={spec.name} name={spec.name} label={spec.label} rules={rules}>
            <Select options={spec.enum_values.map((v) => ({ value: v, label: v }))} placeholder="请选择" />
          </Form.Item>
        );
      default:
        return (
          <Form.Item key={spec.name} name={spec.name} label={spec.label} rules={rules}>
            <Input />
          </Form.Item>
        );
    }
  };

  // ---------------------------------------------------------------- 表格列

  const columns = [
    {
      title: '规则', dataIndex: 'handler_display_name', key: 'name', width: 170,
      render: (_: unknown, rule: Rule) => (
        <Space direction="vertical" size={0}>
          <Text strong>{rule.handler_display_name}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>{rule.rule_type}</Text>
        </Space>
      ),
    },
    {
      title: '说明', dataIndex: 'description', key: 'description',
      render: (text: string) => <Text>{text}</Text>,
    },
    {
      title: '参数', dataIndex: 'params', key: 'params', width: 200,
      render: (params: Record<string, unknown>) => {
        const entries = Object.entries(params ?? {});
        if (entries.length === 0) return <Text type="secondary">（无参数 · 适用于全体）</Text>;
        return (
          <Space size={4} wrap>
            {entries.map(([k, v]) => <Tag key={k}>{`${k}=${String(v)}`}</Tag>)}
          </Space>
        );
      },
    },
    {
      title: '性质', key: 'hardness', width: 130,
      render: (_: unknown, rule: Rule) => (
        <Space size={4}>
          <Tag color={rule.hardness === 'hard' ? 'red' : 'blue'}>
            {rule.hardness === 'hard' ? '硬约束' : '软偏好'}
          </Tag>
          {rule.hardness === 'soft' && <Text type="secondary">权重 {rule.weight}</Text>}
        </Space>
      ),
    },
    {
      title: '作用域', dataIndex: 'scope_name', key: 'scope', width: 90,
    },
    {
      title: '启用', key: 'enabled', width: 70,
      render: (_: unknown, rule: Rule) => (
        <Switch size="small" checked={rule.enabled} onChange={(v) => toggle(rule, v)} />
      ),
    },
    {
      title: '操作', key: 'action', width: 120,
      render: (_: unknown, rule: Rule) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => openEdit(rule)}>编辑</Button>
          <Popconfirm title="删除这条规则？" onConfirm={() => remove(rule)} okText="删除" cancelText="取消">
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  // ---------------------------------------------------------------- 渲染

  const diagnosisAlert = () => {
    if (!diagnosis) return null;
    const type = diagnosis.kind === 'OK' ? 'success'
      : diagnosis.kind === 'UNKNOWN' ? 'warning' : 'error';
    const title = diagnosis.kind === 'OK' ? '排课可行性：正常'
      : diagnosis.kind === 'UNKNOWN' ? '求解超时，未证明无解'
      : '排课可行性：存在确定的问题';
    return (
      <Alert
        type={type}
        showIcon
        message={title}
        description={
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <Text>{diagnosis.note}</Text>
            {diagnosis.static_findings.map((f, i) => (
              <Text key={i} type={f.severity === 'error' ? 'danger' : 'warning'}>
                {f.severity === 'error' ? '• ' : '· '}{f.message}
              </Text>
            ))}
            {diagnosis.conflict_rules.length > 0 && (
              <Space direction="vertical" size={2}>
                <Text strong>冲突来源（去掉其中任一条后即可排出课表）：</Text>
                {diagnosis.conflict_rules.map((c, i) => (
                  <Text key={i}>• [{c.rule_id}] {c.description ?? c.rule_type}</Text>
                ))}
              </Space>
            )}
          </Space>
        }
      />
    );
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        title="规则系统"
        extra={
          <Space>
            <Button icon={<MedicineBoxOutlined />} loading={diagnosing} onClick={() => runDiagnosis(false)}>
              检查排课可行性
            </Button>
            <Button icon={<MedicineBoxOutlined />} loading={diagnosing} onClick={() => runDiagnosis(true)}>
              深诊断（找冲突规则）
            </Button>
            <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          </Space>
        }
      >
        <Descriptions size="small" column={3}>
          <Descriptions.Item label="当前规则集版本">
            {current?.version_no != null ? `v${current.version_no}` : '—'}
          </Descriptions.Item>
          <Descriptions.Item label="规则条数">{current?.rule_count ?? 0}</Descriptions.Item>
          <Descriptions.Item label="规则集指纹">
            <Tooltip title={current?.rule_set_hash ?? ''}>
              <Text code>{current?.rule_set_hash ? current.rule_set_hash.slice(0, 12) : '—'}</Text>
            </Tooltip>
          </Descriptions.Item>
        </Descriptions>

        <Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
          <Text strong>{systemTypes.length}</Text> 条系统规则（不变量 / 数据驱动）始终生效、不可关闭：
          {' '}{systemTypes.map((t) => t.display_name).join('、')}。
          它们不在这里编辑 —— 想改变它们的效果要改教务数据（周课时、教师上限等）。
        </Paragraph>

        {diagnosis && <div style={{ marginTop: 12 }}>{diagnosisAlert()}</div>}
      </Card>

      <Card
        title={`可配置规则（${rules.length}）`}
        extra={
          <Space>
            <Button icon={<HistoryOutlined />} onClick={() => setVersionModalOpen(true)}>
              版本历史（{versions.length}）
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建规则</Button>
          </Space>
        }
      >
        <Table
          rowKey="id"
          size="small"
          loading={loading}
          columns={columns as any}
          dataSource={rules}
          pagination={false}
          locale={{
            emptyText: <Empty description="还没有配置任何规则 —— 此时只有系统规则生效" />,
          }}
        />
      </Card>

      {/* ---------------------------------------------------------- 新建 / 编辑 */}
      <Modal
        title={editing ? `编辑规则：${editing.handler_display_name}` : '新建规则'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={save}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        width={620}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item name="rule_type" label="规则类型" rules={[{ required: true, message: '请选择规则类型' }]}>
            {editing ? (
              // 换类型等于换一条规则（参数含义完全不同），所以编辑时不允许改类型
              <Input disabled />
            ) : (
              <Select
                placeholder="选择一条规则"
                optionFilterProp="label"
                onChange={(value) => {
                  setSelectedType(value);
                  setTrial(null);
                  // 换类型必须清掉旧参数：不同规则的参数名完全不同，留着会造成
                  // "提交了上一个类型的字段"这种诡异状态
                  currentType()?.params.forEach((spec) => form.setFieldValue(spec.name, undefined));
                }}
                options={configurableTypes.map((t) => ({ value: t.rule_type, label: t.display_name }))}
              />
            )}
          </Form.Item>

          {selectedType && (
            <>
              <Divider titlePlacement="start" plain>参数</Divider>
              {currentType()?.params.length
                ? currentType()!.params.map(renderParamField)
                : <Text type="secondary">这种规则不需要参数（对所有科目 / 教师生效）</Text>}
            </>
          )}

          <Divider titlePlacement="start" plain>性质</Divider>
          <Space size={24}>
            <Form.Item name="hardness" label="约束性质" style={{ marginBottom: 0 }}>
              <Select style={{ width: 200 }} options={[
                { value: 'hard', label: '硬约束（必须满足，否则无解）' },
                { value: 'soft', label: '软偏好（尽量满足，可权衡）' },
              ]} />
            </Form.Item>
            <Form.Item name="weight" label="权重（仅软偏好）" style={{ marginBottom: 0 }}>
              <InputNumber min={1} max={1000} />
            </Form.Item>
          </Space>

          <Form.Item name="description" label="说明（列表里显示给人看）" style={{ marginTop: 16 }}>
            <Input placeholder="例如：语文一天最多 2 节" maxLength={255} />
          </Form.Item>

          <Space>
            <Button onClick={doTrial} loading={trialLoading} disabled={!selectedType}>试算影响</Button>
            <Text type="secondary">试算只评估编译影响，不会真的重排课表</Text>
          </Space>

          {trial && (
            <Alert
              style={{ marginTop: 12 }}
              type={trial.compiles ? 'info' : 'error'}
              showIcon
              message={trial.compiles ? '这条规则的读法是：' : '这条规则现在无法生效'}
              description={
                <Space direction="vertical" size={4}>
                  <Text strong>{trial.description}</Text>
                  {!trial.compiles && <Text type="danger">{trial.problem}</Text>}
                  {trial.compiles && (
                    <Text type="secondary">
                      模型变量 {trial.model.variables_before} → {trial.model.variables_after}
                      （{trial.model.variable_delta >= 0 ? '+' : ''}{trial.model.variable_delta}）；
                      {trial.model_changed
                        ? '这条规则确实改变了模型'
                        : '⚠ 这条规则对当前数据没有任何影响，可能参数写错了'}
                    </Text>
                  )}
                  <Text type="secondary" style={{ fontSize: 12 }}>{trial.note}</Text>
                </Space>
              }
            />
          )}
        </Form>
      </Modal>

      {/* ---------------------------------------------------------- 版本历史 */}
      <Modal
        title="规则集版本历史"
        open={versionModalOpen}
        onCancel={() => setVersionModalOpen(false)}
        footer={null}
        width={720}
      >
        <Paragraph type="secondary">
          每次规则变更都会重算一次规则集指纹。<Text strong>改回原样不会产生新版本</Text> ——
          所以"当前版本"在历史里可能是一个编号更小的版本，这是对的。
          排课生成的每个课表版本都记下了它当时用的指纹，审计链据此可以回答"这版课表用的哪套规则"。
        </Paragraph>
        <List
          size="small"
          dataSource={versions}
          renderItem={(v) => (
            <List.Item>
              <Space direction="vertical" size={0} style={{ width: '100%' }}>
                <Space>
                  <Tag color="blue">v{v.version_no}</Tag>
                  <Text>{v.note ?? '—'}</Text>
                  <Text type="secondary">{v.rule_count} 条规则</Text>
                </Space>
                <Space size={8}>
                  <Tooltip title={v.rule_set_hash}>
                    <Text code style={{ fontSize: 12 }}>{v.rule_set_hash.slice(0, 16)}…</Text>
                  </Tooltip>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {v.created_at?.replace('T', ' ')}
                  </Text>
                  {current?.rule_set_hash === v.rule_set_hash && <Tag color="green">当前生效</Tag>}
                </Space>
              </Space>
            </List.Item>
          )}
        />
      </Modal>
    </Space>
  );
};

export default RuleManagement;
