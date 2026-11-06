import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Table, Space, Button, Upload, Tag, message, Popconfirm, Input, Select, Segmented,
  Image, Tooltip, Empty, Statistic, Row, Col, Card,
} from 'antd';
import {
  UploadOutlined, DownloadOutlined, DeleteOutlined, SearchOutlined,
  FileImageOutlined, FileTextOutlined, AudioOutlined, VideoCameraOutlined,
  FileOutlined, MessageOutlined, UserOutlined, EyeOutlined,
} from '@ant-design/icons';
import { listFiles, uploadFile, downloadFile, deleteFile } from '../../api/files';
import { FileAsset } from '../../api/types';

/** 字节数转可读体积。 */
const fmtSize = (n?: number) => {
  if (!n) return '—';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
};

/** 按 mimeType / fileType 归到四类，前端筛选用。 */
function kindOf(f: FileAsset): 'image' | 'document' | 'audio' | 'video' | 'other' {
  const t = `${f.fileType ?? ''} ${f.mimeType ?? ''}`.toLowerCase();
  if (t.includes('image')) return 'image';
  if (t.includes('audio')) return 'audio';
  if (t.includes('video')) return 'video';
  if (t.includes('pdf') || t.includes('word') || t.includes('text') || t.includes('document')
    || t.includes('sheet') || t.includes('excel') || t.includes('markdown')) return 'document';
  return 'other';
}

/** 来源判断（前端推断，表里没有 source 字段）。 */
function sourceOf(f: FileAsset): 'chat' | 'manual' {
  // 插件上传的聊天图统一以 qq-<时间戳> 开头
  return (f.fileName ?? '').startsWith('qq-') ? 'chat' : 'manual';
}

const KIND_META: Record<string, { label: string; color: string; icon: React.ReactNode }> = {
  image: { label: '图片', color: 'blue', icon: <FileImageOutlined /> },
  document: { label: '文档', color: 'geekblue', icon: <FileTextOutlined /> },
  audio: { label: '音频', color: 'purple', icon: <AudioOutlined /> },
  video: { label: '视频', color: 'magenta', icon: <VideoCameraOutlined /> },
  other: { label: '其他', color: 'default', icon: <FileOutlined /> },
};

export default function FilesPage() {
  const [items, setItems] = useState<FileAsset[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [kind, setKind] = useState<string>('all');
  const [source, setSource] = useState<string>('all');
  const [selected, setSelected] = useState<React.Key[]>([]);
  const [preview, setPreview] = useState<string>('');
  const [previewOpen, setPreviewOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setItems(await listFiles());
      setSelected([]);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  /** 三个条件叠加过滤。 */
  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    return items.filter((f) => {
      if (kind !== 'all' && kindOf(f) !== kind) return false;
      if (source !== 'all' && sourceOf(f) !== source) return false;
      if (kw && !(f.fileName ?? '').toLowerCase().includes(kw)) return false;
      return true;
    });
  }, [items, keyword, kind, source]);

  /** 统计（按全部，不受筛选影响，方便看总量）。 */
  const stats = useMemo(() => {
    const totalSize = items.reduce((s, f) => s + (f.fileSize ?? 0), 0);
    const byKind = items.reduce<Record<string, number>>((m, f) => {
      const k = kindOf(f);
      m[k] = (m[k] ?? 0) + 1;
      return m;
    }, {});
    const chat = items.filter((f) => sourceOf(f) === 'chat').length;
    return { total: items.length, totalSize, byKind, chat };
  }, [items]);

  /** 批量删除：逐个调接口，统计成功数。 */
  const batchDelete = async () => {
    let ok = 0;
    for (const id of selected) {
      try {
        await deleteFile(String(id));
        ok++;
      } catch {
        /* 单条失败不阻断 */
      }
    }
    message.success(`已删除 ${ok} / ${selected.length} 个`);
    load();
  };

  /** 预览：先 fetch 成 blob 再给 Image（下载接口要带 token，img 标签带不了）。 */
  const openPreview = async (fileId: string) => {
    try {
      const { getTenantId, getToken } = await import('../../api/http');
      const h: Record<string, string> = { 'X-Tenant-Id': getTenantId() };
      const t = getToken();
      if (t) h['Authorization'] = `Bearer ${t}`;
      const res = await fetch(`/api/v1/files/${fileId}/download`, { headers: h });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const blob = await res.blob();
      setPreview(URL.createObjectURL(blob));
      setPreviewOpen(true);
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const columns = [
    {
      title: '名称',
      dataIndex: 'fileName',
      ellipsis: true,
      render: (v: string, r: FileAsset) => {
        const k = kindOf(r);
        const isChat = sourceOf(r) === 'chat';
        return (
          <Space size={6}>
            <span style={{ color: '#7A8A99' }}>{KIND_META[k].icon}</span>
            <span>{v || '—'}</span>
            {isChat && (
              <Tooltip title="QQ 聊天中收到或发出的图片">
                <Tag color="orange" style={{ marginInlineEnd: 0 }} icon={<MessageOutlined />}>聊天</Tag>
              </Tooltip>
            )}
            {!isChat && (
              <Tooltip title="你手动上传的文件">
                <Tag color="green" style={{ marginInlineEnd: 0 }} icon={<UserOutlined />}>上传</Tag>
              </Tooltip>
            )}
          </Space>
        );
      },
    },
    {
      title: '类型',
      dataIndex: 'fileType',
      width: 96,
      filters: Object.entries(KIND_META).map(([k, m]) => ({ text: m.label, value: k })),
      onFilter: (v: React.Key, r: FileAsset) => kindOf(r) === v,
      render: (_v: string, r: FileAsset) => {
        const m = KIND_META[kindOf(r)];
        return <Tag color={m.color}>{m.label}</Tag>;
      },
    },
    {
      title: '大小',
      dataIndex: 'fileSize',
      width: 96,
      sorter: (a: FileAsset, b: FileAsset) => (a.fileSize ?? 0) - (b.fileSize ?? 0),
      render: fmtSize,
    },
    {
      title: '时间',
      dataIndex: 'createdAt',
      width: 165,
      defaultSortOrder: 'descend' as const,
      sorter: (a: FileAsset, b: FileAsset) =>
        new Date(a.createdAt ?? 0).getTime() - new Date(b.createdAt ?? 0).getTime(),
      render: (v: string) => (v ? new Date(v).toLocaleString() : '—'),
    },
    {
      title: '文件 ID',
      dataIndex: 'fileId',
      width: 130,
      ellipsis: true,
      render: (v: string) => <span style={{ color: '#9AA8B5', fontSize: 12 }}>{v}</span>,
    },
    {
      title: '操作',
      width: 210,
      render: (_: unknown, r: FileAsset) => (
        <Space size={4}>
          {kindOf(r) === 'image' && (
            <Button size="small" type="link" icon={<EyeOutlined />} onClick={() => openPreview(r.fileId!)}>
              预览
            </Button>
          )}
          <Button size="small" icon={<DownloadOutlined />} onClick={() => downloadFile(r.fileId!)}>
            下载
          </Button>
          <Popconfirm
            title="删除该文件？"
            onConfirm={async () => {
              await deleteFile(r.fileId!);
              load();
            }}
          >
            <Button size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      {/* 统计条 */}
      <Row gutter={12} style={{ marginBottom: 14 }}>
        <Col span={6}>
          <Card size="small" styles={{ body: { padding: '10px 14px' } }}>
            <Statistic title="文件总数" value={stats.total} suffix="个" valueStyle={{ fontSize: 20 }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" styles={{ body: { padding: '10px 14px' } }}>
            <Statistic title="占用空间" value={fmtSize(stats.totalSize)} valueStyle={{ fontSize: 20 }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" styles={{ body: { padding: '10px 14px' } }}>
            <Statistic
              title="聊天图"
              value={stats.chat}
              suffix="个"
              valueStyle={{ fontSize: 20, color: stats.chat > 0 ? '#D46B08' : undefined }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" styles={{ body: { padding: '10px 14px' } }}>
            <Statistic
              title="手动上传"
              value={stats.total - stats.chat}
              suffix="个"
              valueStyle={{ fontSize: 20 }}
            />
          </Card>
        </Col>
      </Row>

      {/* 工具栏 */}
      <Space wrap style={{ marginBottom: 12 }}>
        <Upload
          showUploadList={false}
          beforeUpload={(f) => {
            uploadFile(f as unknown as File)
              .then(() => {
                message.success('已上传');
                load();
              })
              .catch((e) => message.error((e as Error).message));
            return false;
          }}
        >
          <Button type="primary" icon={<UploadOutlined />}>上传文件</Button>
        </Upload>

        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder="搜索文件名"
          style={{ width: 200 }}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
        />

        <Segmented
          value={kind}
          onChange={(v) => setKind(String(v))}
          options={[
            { label: '全部', value: 'all' },
            { label: '图片', value: 'image' },
            { label: '文档', value: 'document' },
            { label: '音频', value: 'audio' },
            { label: '视频', value: 'video' },
            { label: '其他', value: 'other' },
          ]}
        />

        <Select
          value={source}
          onChange={setSource}
          style={{ width: 130 }}
          options={[
            { label: '全部来源', value: 'all' },
            { label: '聊天图', value: 'chat' },
            { label: '手动上传', value: 'manual' },
          ]}
        />

        {selected.length > 0 && (
          <Popconfirm
            title={`删除选中的 ${selected.length} 个文件？`}
            description="删除后不可恢复"
            onConfirm={batchDelete}
          >
            <Button danger icon={<DeleteOutlined />}>
              批量删除（{selected.length}）
            </Button>
          </Popconfirm>
        )}

        <Button onClick={load} loading={loading}>刷新</Button>

        <span style={{ color: '#999', fontSize: 12 }}>
          单文件 ≤ 50MB。聊天图与手动上传已分开标记，可按来源筛选后批量清理。
        </span>
      </Space>

      <Table
        rowKey="fileId"
        size="middle"
        loading={loading}
        columns={columns as never}
        dataSource={filtered}
        rowSelection={{
          selectedRowKeys: selected,
          onChange: (keys) => setSelected(keys),
        }}
        locale={{
          emptyText: (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={items.length === 0 ? '还没有文件' : '当前筛选条件下没有文件'}
            />
          ),
        }}
        pagination={{
          defaultPageSize: 20,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          pageSizeOptions: [10, 20, 50, 100],
        }}
      />

      <Image
        style={{ display: 'none' }}
        preview={{
          visible: previewOpen,
          src: preview,
          onVisibleChange: (v) => {
            setPreviewOpen(v);
            if (!v) {
              URL.revokeObjectURL(preview);
              setPreview('');
            }
          },
        }}
      />
    </div>
  );
}
