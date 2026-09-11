/**
 * 工作流画布（拖拽式编排）。
 *
 * 结构：FixedLayoutEditorProvider（FlowGram 固定布局：顺序流 + 分支复合节点）
 *   ├─ 顶部工具条：返回 / 保存 / 试运行
 *   ├─ 画布：EditorRenderer
 *   ├─ 右侧：当前选中节点的配置表单（由节点的 formMeta 渲染）
 *   └─ 底部：调试面板（每节点状态 / 耗时 / 输出）
 *
 * 保存路径：ctx.document.toJSON() → adapter.toBackend() → PUT /workflows/{id}
 * 试运行：先保存（保证跑的是当前画布）→ POST /workflows/{id}/debug（带节点级轨迹）
 */
import { useCallback, useMemo, useState } from 'react';
import { Alert, Button, Empty, Input, Modal, Popconfirm, Space, Tag, Typography, message } from 'antd';
import {
  ArrowLeftOutlined,
  CloudUploadOutlined,
  PlayCircleOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import {
  EditorRenderer,
  FixedLayoutEditorProvider,
  useClientContext,
  type FixedLayoutPluginContext,
} from '@flowgram.ai/fixed-layout-editor';
import '@flowgram.ai/fixed-layout-editor/index.css';

import {
  debugWorkflow,
  publishWorkflow,
  rollbackWorkflow,
  updateWorkflow,
  type WorkflowStep,
} from '../../../api/workflows';
import { toBackend } from './adapter';
import { CanvasSelectionContext, type CanvasSelection } from './selection';
import { useEditorProps } from './use-editor-props';
import type { FlowDocumentJSON } from './types';
import './styles.css';

export interface WorkflowCanvasProps {
  workflowId: string;
  workflowName: string;
  initialData: FlowDocumentJSON;
  /** 已发布版本号（来自列表项），用于展示「线上版本」 */
  publishedVersion?: string;
  onClose: () => void;
}

export default function WorkflowCanvas(props: WorkflowCanvasProps) {
  const [dirty, setDirty] = useState(false);
  const onDirty = useCallback(() => setDirty(true), []);
  const editorProps = useEditorProps(props.initialData, onDirty);

  return (
    <FixedLayoutEditorProvider {...editorProps}>
      <CanvasShell {...props} dirty={dirty} onSaved={() => setDirty(false)} />
    </FixedLayoutEditorProvider>
  );
}

interface ShellProps extends WorkflowCanvasProps {
  dirty: boolean;
  onSaved: () => void;
}

function CanvasShell({ workflowId, workflowName, publishedVersion, onClose, dirty, onSaved }: ShellProps) {
  const ctx = useClientContext();
  const [selectedId, setSelectedId] = useState<string | undefined>();
  const [saving, setSaving] = useState(false);
  const [running, setRunning] = useState(false);
  const [published, setPublished] = useState<string | undefined>(publishedVersion);
  const [publishing, setPublishing] = useState(false);
  const [runSteps, setRunSteps] = useState<WorkflowStep[] | undefined>();
  const [runVars, setRunVars] = useState<Record<string, unknown> | undefined>();
  const [runOpen, setRunOpen] = useState(false);
  const [inputJson, setInputJson] = useState('{\n  "input": "你好"\n}');

  /** 保存：画布 → 后端定义 */
  const save = useCallback(
    async (silent = false) => {
      setSaving(true);
      try {
        const doc = ctx.document.toJSON() as unknown as FlowDocumentJSON;
        const definition = toBackend(doc, workflowName) as unknown as Record<string, unknown>;
        await updateWorkflow(workflowId, definition, workflowName);
        onSaved();
        if (!silent) {
          message.success('已保存');
        }
        return true;
      } catch (e) {
        message.error(`保存失败：${(e as Error).message}`);
        return false;
      } finally {
        setSaving(false);
      }
    },
    [ctx, workflowId, workflowName, onSaved]
  );

  /** 试运行：先保存，再调试执行（拿节点级轨迹） */
  const run = useCallback(async () => {
    let input: Record<string, unknown> = {};
    try {
      input = inputJson.trim() ? JSON.parse(inputJson) : {};
    } catch {
      message.error('输入不是合法 JSON');
      return;
    }
    setRunning(true);
    try {
      const ok = await save(true);
      if (!ok) return;
      const result = await debugWorkflow(workflowId, input);
      setRunSteps(result.steps ?? []);
      setRunVars(result.variables ?? {});
      setRunOpen(false);
      message.success('运行完成');
    } catch (e) {
      message.error(`运行失败：${(e as Error).message}`);
    } finally {
      setRunning(false);
    }
  }, [inputJson, save, workflowId]);

  /** 发布：先保存草稿，再生成已发布快照（线上跑已发布版本，草稿可继续改） */
  const doPublish = useCallback(async () => {
    setPublishing(true);
    try {
      const ok = await save(true);
      if (!ok) return;
      const def = await publishWorkflow(workflowId);
      setPublished(def?.publishedVersion);
      message.success(`已发布 ${def?.publishedVersion ?? ''}`);
    } catch (e) {
      message.error(`发布失败：${(e as Error).message}`);
    } finally {
      setPublishing(false);
    }
  }, [save, workflowId]);

  /** 回滚：definition 恢复为已发布快照 */
  const doRollback = useCallback(async () => {
    try {
      await rollbackWorkflow(workflowId);
      message.success('已回滚到已发布版本，请返回列表重新打开画布查看');
    } catch (e) {
      message.error(`回滚失败：${(e as Error).message}`);
    }
  }, [workflowId]);

  const selection = useMemo<CanvasSelection>(
    () => ({ selectedId, select: setSelectedId }),
    [selectedId]
  );

  return (
    <CanvasSelectionContext.Provider value={selection}>
      <div className="wf-canvas-wrap">
        <div className="wf-toolbar">
          <div className="wf-toolbar__left">
            <Button type="text" icon={<ArrowLeftOutlined />} onClick={onClose}>
              返回列表
            </Button>
            <Typography.Text strong>{workflowName}</Typography.Text>
            {dirty ? <Tag color="orange">未保存</Tag> : <Tag color="green">已保存</Tag>}
            {published ? <Tag color="blue">线上 {published}</Tag> : <Tag>未发布</Tag>}
          </div>
          <div className="wf-toolbar__right">
            <Button icon={<SaveOutlined />} loading={saving} onClick={() => save()}>
              保存
            </Button>
            <Button
              icon={<CloudUploadOutlined />}
              loading={publishing}
              onClick={doPublish}
            >
              发布
            </Button>
            {published ? (
              <Popconfirm
                title="回滚到已发布版本？当前草稿的修改会被覆盖。"
                onConfirm={doRollback}
              >
                <Button>回滚</Button>
              </Popconfirm>
            ) : null}
            <Button
              type="primary"
              icon={<PlayCircleOutlined />}
              loading={running}
              onClick={() => setRunOpen(true)}
            >
              试运行
            </Button>
          </div>
        </div>

        <div className="wf-canvas-body">
          <div className="wf-canvas-main">
            <EditorRenderer />
          </div>
          <NodeConfigPanel ctx={ctx} nodeId={selectedId} />
        </div>

        {runSteps ? (
          <DebugPanel
            steps={runSteps}
            variables={runVars}
            onClose={() => {
              setRunSteps(undefined);
              setRunVars(undefined);
            }}
          />
        ) : null}

        <Modal
          title="试运行输入"
          open={runOpen}
          onOk={run}
          confirmLoading={running}
          onCancel={() => setRunOpen(false)}
          okText="运行"
          destroyOnClose
        >
          <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
            输入 JSON 作为工作流初始变量（对应「开始」节点声明的变量名，默认 <code>input</code>）。
          </Typography.Paragraph>
          <Input.TextArea
            rows={6}
            value={inputJson}
            onChange={(e) => setInputJson(e.target.value)}
            placeholder={'{\n  "input": "你好"\n}'}
          />
        </Modal>
      </div>
    </CanvasSelectionContext.Provider>
  );
}

/** 右侧：选中节点的配置表单。 */
function NodeConfigPanel({ ctx, nodeId }: { ctx: FixedLayoutPluginContext; nodeId?: string }) {
  const node = nodeId ? (ctx.document.getNode(nodeId) as unknown as {
    id: string;
    flowNodeType?: string;
    data?: Record<string, unknown>;
    form?: { render: () => React.ReactNode };
  }) : undefined;

  if (!node) {
    return (
      <div className="wf-panel">
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={<span style={{ fontSize: 12 }}>点击画布上的节点以配置</span>}
        />
      </div>
    );
  }

  return (
    <div className="wf-panel">
      <div className="wf-panel__title">{(node.data?.title as string) ?? node.flowNodeType}</div>
      <div className="wf-panel__hint">
        类型 {node.flowNodeType} · {node.id}
      </div>
      {node.form ? (
        node.form.render()
      ) : (
        <Alert type="warning" showIcon message="该节点暂无配置表单" />
      )}
    </div>
  );
}

/** 底部：调试面板（逐节点执行轨迹 + 变量快照）。 */
function DebugPanel({
  steps,
  variables,
  onClose,
}: {
  steps: WorkflowStep[];
  variables?: Record<string, unknown>;
  onClose: () => void;
}) {
  const [expandKey, setExpandKey] = useState<string | undefined>();
  const [showVars, setShowVars] = useState(false);
  const failed = steps.filter((s) => s.status !== 'success').length;
  const total = steps.reduce((acc, s) => acc + (s.durationMs ?? 0), 0);

  return (
    <div className="wf-run-result">
      <div className="wf-run-result__head">
        <Space size={8}>
          <Tag color={failed ? 'red' : 'blue'}>{failed ? `失败 ${failed} 个节点` : '运行成功'}</Tag>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {steps.length} 个节点 · 合计 {total}ms
          </Typography.Text>
        </Space>
        <Space size={4}>
          <Button size="small" type="link" onClick={() => setShowVars((v) => !v)}>
            {showVars ? '隐藏变量' : '变量快照'}
          </Button>
          <Button size="small" type="link" onClick={onClose}>
            收起
          </Button>
        </Space>
      </div>

      <div className="wf-steps">
        {steps.map((s, i) => {
          const key = s.nodeId ?? String(i);
          const open = expandKey === key;
          const detail = s.status === 'success' ? s.output : s.error;
          return (
            <div className="wf-step" key={key}>
              <span className="wf-step__idx">{i + 1}</span>
              <span className="wf-step__name">{s.name ?? s.nodeId}</span>
              <Tag>{s.type}</Tag>
              <Tag color={s.status === 'success' ? 'green' : 'red'}>{s.status}</Tag>
              <span className="wf-step__ms">{s.durationMs ?? 0}ms</span>
              <Button
                size="small"
                type="link"
                onClick={() => setExpandKey(open ? undefined : key)}
              >
                {open ? '收起' : '详情'}
              </Button>
              {open ? (
                <pre className="wf-step__detail">
                  {typeof detail === 'string' ? detail : JSON.stringify(detail ?? null, null, 2)}
                </pre>
              ) : null}
            </div>
          );
        })}
      </div>

      {showVars ? <pre>{JSON.stringify(variables ?? {}, null, 2)}</pre> : null}
    </div>
  );
}
