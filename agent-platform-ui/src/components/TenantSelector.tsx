import { Input, Space, Typography } from 'antd';
import { useAppStore } from '../store/appStore';

// 顶栏租户选择器：写入 X-Tenant-Id（所有请求头携带）
export default function TenantSelector() {
  const { tenantId, setTenant } = useAppStore();
  return (
    <Space size={8}>
      <Typography.Text type="secondary" style={{ fontSize: 13 }}>
        Tenant
      </Typography.Text>
      <Input
        size="small"
        value={tenantId}
        style={{ width: 120 }}
        placeholder="default"
        onChange={(e) => setTenant(e.target.value.trim() || 'default')}
      />
    </Space>
  );
}