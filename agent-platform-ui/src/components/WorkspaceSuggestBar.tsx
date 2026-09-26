import { useEffect, useState } from 'react';
import { Alert, App as AntApp, Button, Space, Typography } from 'antd';
import { FolderOpenOutlined } from '@ant-design/icons';
import { getWorkspace, setWorkspace } from '../api/workspace';

const { Text } = Typography;

/** 路径归一：统一分隔符、去尾随斜杠、转小写（Windows 不区分大小写）。 */
function norm(p: string): string {
  return p.replace(/[\\/]+$/, '').replace(/\\/g, '/').toLowerCase();
}

/** child 是否就是 parent、或位于 parent 之下。 */
function isUnderOrEqual(child: string, parent: string): boolean {
  const c = norm(child);
  const p = norm(parent);
  return c === p || c.startsWith(p + '/');
}

/**
 * 「检测到文件来自某个目录 —— 要把它设为工作区吗？」提示条（第二步，2026-09-26）。
 *
 * <h3>★ 为什么是"提示"而不是"自动设"</h3>
 * 工作区根 = 模型**可读写范围的全部**，而且 {@code fs_read_file} / {@code fs_glob} /
 * {@code fs_grep} **都是只读、不走审批的** —— 审批弹窗挡得住"写"，挡不住"读"。
 * 若根随着"用户拖了哪个文件"自动漂移：拖一个 {@code C:\a.txt} 进去，根就变成 {@code C:\}，
 * 模型随即可以搜遍整块盘，而**用户连一次拒绝的机会都没有**。
 * 所以这里只做"发现 + 建议"，真正的授权必须由人点那一下。
 *
 * <h3>★ 什么时候干脆不提示</h3>
 * 两种情况说明"已经够得着了"，此时提示只会变成打扰：
 * <ul>
 *   <li>当前工作区**就是**这个目录（或它的父目录）—— 文件本来就能被改；</li>
 *   <li>拖进来的文件**位于当前工作区之内** —— 同理。</li>
 * </ul>
 * 换句话说：只有"够不着"时才出声。用户拖自己项目里的文件是最常见的情形，
 * 那种情况下**不该有任何弹窗**。
 */
export default function WorkspaceSuggestBar({
  dir,
  onDismiss,
}: {
  /** 检测到的文件所在目录（绝对路径）。 */
  dir: string;
  onDismiss: () => void;
}) {
  const { message } = AntApp.useApp();
  const [current, setCurrent] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let alive = true;
    getWorkspace()
      .then((s) => {
        if (alive) {
          setCurrent(s.root);
        }
      })
      .catch(() => {
        // 拿不到状态就按"够不着"处理，让用户自己决定 —— 提示条本来是可关的
      });
    return () => {
      alive = false;
    };
  }, []);

  // 已经够得着（工作区就是它/它的父目录，或文件就在工作区内）→ 不打扰
  if (current && (isUnderOrEqual(dir, current) || isUnderOrEqual(current, dir))) {
    return null;
  }

  const apply = async () => {
    setBusy(true);
    try {
      const s = await setWorkspace(dir);
      setCurrent(s.root);
      // 工具条上那个常显的目录名也要跟着变（它是"模型能看到多大范围"的唯一提示）
      window.dispatchEvent(new Event('ap:workspace-changed'));
      message.success('工作区已切换，现在可以让智能体改这个目录里的文件了');
      onDismiss();
    } catch (e) {
      // 后端会拒掉盘根 / 系统目录 / 主目录本身 / 凭据目录，并说明原因
      message.error(`设置失败：${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Alert
      type="info"
      showIcon
      icon={<FolderOpenOutlined />}
      closable
      onClose={onDismiss}
      style={{ marginBottom: 8 }}
      message={
        <span style={{ fontSize: 13 }}>
          检测到文件来自 <Text code>{dir}</Text>
        </span>
      }
      description={
        <span style={{ fontSize: 12 }}>
          这个目录不在当前工作区内，智能体现在**够不着**它。
          设为工作区后，它就能读写这里的文件（写操作仍会弹确认框；读取不需要批准，所以请确认这个范围合适）。
        </span>
      }
      action={
        <Space direction="vertical" size={4}>
          <Button size="small" type="primary" loading={busy} onClick={() => void apply()}>
            设为工作区
          </Button>
          <Button size="small" type="text" onClick={onDismiss}>
            忽略
          </Button>
        </Space>
      }
    />
  );
}
