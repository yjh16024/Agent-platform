import { useCallback, useEffect, useState } from 'react';
import { Alert, App as AntApp, Button, Input, Modal, Space, Tag, Tooltip, Typography } from 'antd';
import { FolderOpenOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  desktopBridge,
  getWorkspace,
  resetWorkspace,
  setWorkspace,
  type WorkspaceStatus,
} from '../api/workspace';

const { Text, Paragraph } = Typography;

/**
 * 工作区选择器 —— 挂在聊天页工具条上，显示并设置"智能体能碰到哪个目录"。
 *
 * <h3>它解决的是什么</h3>
 * 在它存在之前，工作区根只能靠环境变量 `AGENT_WORKSPACE_ROOT` 设置，而桌面版用户
 * **没有改环境变量的入口**；默认值 `./data/workspace` 又是个空目录。结果就是
 * "让智能体帮我改这个文件"在界面上**无路可走**（2026-09-26 的真实反馈）。
 *
 * <h3>★ 为什么按钮上要显示目录名</h3>
 * 工作区根就是模型**可读写范围的全部**。用户必须**随时看得见**当前授权了哪个目录，
 * 而不是"设过一次就忘了"。所以这里常态显示目录名，而不是藏进设置页。
 *
 * <h3>桌面版 vs 浏览器</h3>
 * 桌面壳通过 `preload.js` 暴露原生目录选择对话框（`window.apWorkspace`）；
 * 浏览器里拿不到绝对路径（安全限制），退化为手动输入。
 */
export default function WorkspacePicker() {
  const { message } = AntApp.useApp();
  const [status, setStatus] = useState<WorkspaceStatus | null>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [manual, setManual] = useState('');

  const load = useCallback(async () => {
    try {
      setStatus(await getWorkspace());
    } catch {
      // 静默：拿不到工作区状态不该打断对话主流程
    }
  }, []);

  useEffect(() => {
    void load();
    /*
     * 别处（如拖入文件后的 WorkspaceSuggestBar）改了工作区时，这里要跟着刷新 ——
     * 这个按钮是"模型能看到多大范围"的**唯一常显提示**，显示过期值比不显示更糟。
     * 用极轻的自定义事件而不是把状态提到共同父级：两者在组件树上相隔较远，
     * 提状态会把 ChatPage 也卷进来，而它并不关心这个值。
     */
    const onChanged = () => void load();
    window.addEventListener('ap:workspace-changed', onChanged);
    return () => window.removeEventListener('ap:workspace-changed', onChanged);
  }, [load]);

  const apply = async (path: string) => {
    if (!path) {
      message.warning('请先选择或输入一个目录');
      return;
    }
    setBusy(true);
    try {
      setStatus(await setWorkspace(path));
      message.success('工作区已切换，之后智能体读写文件都在这个目录内');
      setOpen(false);
    } catch (e) {
      // 后端会拒掉盘根/系统目录/主目录本身/凭据目录，并把原因说清楚
      message.error(`设置失败：${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const pick = async () => {
    const bridge = desktopBridge();
    if (!bridge) {
      await apply(manual.trim());
      return;
    }
    const dir = await bridge.pickDirectory();
    if (dir) {
      await apply(dir);
    }
  };

  const reset = async () => {
    setBusy(true);
    try {
      setStatus(await resetWorkspace());
      message.success('已重置为默认工作区');
    } catch (e) {
      message.error(`重置失败：${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  /** 只显示末级目录名，按钮不至于被长路径撑爆（完整路径在弹窗里）。 */
  const leaf = status ? status.root.split(/[\\/]/).filter(Boolean).pop() : null;

  return (
    <>
      <Tooltip title={status ? `工作区：${status.root}` : '设置智能体可读写的目录'}>
        <Button
          size="small"
          type="text"
          icon={<FolderOpenOutlined />}
          onClick={() => {
            setManual('');
            setOpen(true);
            void load();
          }}
        >
          <Text style={{ fontSize: 12 }}>{leaf ?? '工作区'}</Text>
        </Button>
      </Tooltip>

      <Modal
        title="工作区"
        open={open}
        onCancel={() => setOpen(false)}
        footer={null}
        width={600}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="智能体只能读写这个目录内的文件"
          description={
            <>
              写操作（改文件）仍会弹出确认框由你批准；<b>但读取不需要批准</b> ——
              所以这里选的目录，就是智能体**能看到**的全部范围，请选一个具体的项目目录，
              不要选整个盘或你的主目录。
            </>
          }
        />

        <Paragraph style={{ marginBottom: 6 }}>
          <Text type="secondary">当前：</Text>
          <Text code>{status?.root ?? '（读取中…）'}</Text>
          {status?.overridden ? (
            <Tag color="blue" style={{ marginLeft: 8 }}>
              界面设置
            </Tag>
          ) : (
            <Tag style={{ marginLeft: 8 }}>来自配置</Tag>
          )}
          {status && !status.available && (
            <Tag color="red" style={{ marginLeft: 8 }}>
              文件工具已被关闭
            </Tag>
          )}
        </Paragraph>

        {!desktopBridge() && (
          <Input
            placeholder="浏览器里拿不到文件路径，请手动输入绝对路径，例如 D:\my-project"
            value={manual}
            onChange={(e) => setManual(e.target.value)}
            style={{ marginBottom: 10 }}
          />
        )}

        <Space>
          <Button type="primary" icon={<FolderOpenOutlined />} loading={busy} onClick={() => void pick()}>
            选择目录
          </Button>
          <Button
            icon={<ReloadOutlined />}
            disabled={busy || !status?.overridden}
            onClick={() => void reset()}
          >
            重置为默认
          </Button>
        </Space>

        {status && status.protectedSummary.length > 0 && (
          <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 14, marginBottom: 0 }}>
            <b>即使在这个目录内，以下内容也不会被读取或修改</b>（凭证隔离，不可豁免）：
            {status.protectedSummary.map((s) => (
              <div key={s}>· {s}</div>
            ))}
          </Paragraph>
        )}
      </Modal>
    </>
  );
}
