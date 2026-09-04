import { Tag } from 'antd';

const MAP: Record<string, { color: string; text: string }> = {
  draft: { color: 'default', text: '草稿' },
  published: { color: 'green', text: '已发布' },
  deprecated: { color: 'orange', text: '已弃用' },
  archived: { color: 'volcano', text: '已归档' },
};

export default function StatusTag({ status }: { status?: string }) {
  const s = MAP[status ?? ''] ?? { color: 'default', text: status ?? '未知' };
  return <Tag color={s.color}>{s.text}</Tag>;
}