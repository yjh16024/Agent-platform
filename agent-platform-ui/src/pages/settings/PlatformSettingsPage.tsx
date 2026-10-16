import { Tabs, Typography } from 'antd';
import ModelSettingsTab from './ModelSettingsTab';
import FeatureTogglesTab from './FeatureTogglesTab';

const { Title, Text } = Typography;

/**
 * 「平台设置」页（2026-10-15 由「模型设置」页升级而来）。
 *
 * <p>两层：<b>模型设置</b>（平台级默认模型绑定，原页面内容原样保留）与
 * <b>功能开关</b>（记忆 / 工具 / 审计 / 模型链路等运行期开关）。
 * 开关层里"需重启"的项在保存后弹窗告知，桌面壳可直接「立即重启应用」。</p>
 */
export default function PlatformSettingsPage() {
  return (
    <div>
      <Title level={4} style={{ marginTop: 0 }}>平台设置</Title>
      <Text type="secondary">
        平台级配置。「模型设置」管理默认模型与密钥；「功能开关」控制记忆、工具、审计等能力的开合 ——
        开关保存即写入，标注「需重启」的项重启应用后生效。
      </Text>
      <Tabs
        style={{ marginTop: 12 }}
        items={[
          { key: 'model', label: '模型设置', children: <ModelSettingsTab /> },
          { key: 'toggles', label: '功能开关', children: <FeatureTogglesTab /> },
        ]}
      />
    </div>
  );
}
