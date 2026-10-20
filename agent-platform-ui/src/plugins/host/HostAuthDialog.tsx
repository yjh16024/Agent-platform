import { useEffect, useState } from 'react';
import { Alert, Modal, Typography } from 'antd';
import {
  CAPABILITY_LABELS,
  currentAuthRequest,
  resolveAuthRequest,
  subscribeAuth,
} from './hostClient';

/**
 * 「插件请求本机能力」确认框（2026-10-19，P4）。
 *
 * <h3>它拦的是什么</h3>
 * 不是安全审查（本地单机没有外部攻击者），而是**知情权**：用户应该知道
 * "这个插件现在要动我的文件 / 跑命令 / 连网络"。确认一次后记住，之后同类操作不再打扰。
 *
 * <h3>为什么是一个全局单例组件</h3>
 * 授权请求来自任意插件的任意调用点，但同一时刻只应该有一个确认框 ——
 * `hostClient` 里用队列串行化，这里只渲染"当前那一个"。
 */
export default function HostAuthDialog() {
  const [, force] = useState(0);

  useEffect(() => subscribeAuth(() => force((v) => v + 1)), []);

  const req = currentAuthRequest();
  const meta = req ? CAPABILITY_LABELS[req.capability] : null;

  return (
    <Modal
      open={!!req}
      title={meta ? `允许「${meta.title}」？` : '插件请求本机能力'}
      okText="允许"
      cancelText="拒绝"
      maskClosable={false}
      keyboard={false}
      onOk={() => resolveAuthRequest(true)}
      onCancel={() => resolveAuthRequest(false)}
    >
      {meta && req && (
        <>
          <Typography.Paragraph style={{ marginBottom: 8 }}>
            插件 <Typography.Text code>{req.pluginId}</Typography.Text> 请求：<b>{meta.title}</b>
          </Typography.Paragraph>
          <Alert type="info" showIcon={false} message={meta.detail} style={{ marginBottom: 12 }} />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            允许后不再重复询问；可随时在「扩展 → 界面插件」中撤销。
          </Typography.Text>
        </>
      )}
    </Modal>
  );
}
