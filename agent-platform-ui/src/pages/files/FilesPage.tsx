import { useCallback, useEffect, useState } from 'react';
import { Table, Space, Button, Upload, Tag, message, Popconfirm } from 'antd';
import { UploadOutlined, DownloadOutlined } from '@ant-design/icons';
import { listFiles, uploadFile, downloadFile, deleteFile } from '../../api/files';
import { FileAsset } from '../../api/types';

const fmtSize = (n?: number) => {
  if (!n) return '—';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
};

export default function FilesPage() {
  const [items, setItems] = useState<FileAsset[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setItems(await listFiles());
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const columns = [
    { title: '文件名', dataIndex: 'fileName', ellipsis: true },
    { title: '文件 ID', dataIndex: 'fileId', width: 170, ellipsis: true },
    { title: '类型', dataIndex: 'fileType', width: 90, render: (v: string) => <Tag>{v}</Tag> },
    { title: '大小', dataIndex: 'fileSize', width: 100, render: fmtSize },
    {
      title: '上传时间', dataIndex: 'createdAt', width: 180,
      render: (v: string) => (v ? new Date(v).toLocaleString() : '—'),
    },
    {
      title: '操作', width: 180,
      render: (_: unknown, r: FileAsset) => (
        <Space>
          <Button size="small" icon={<DownloadOutlined />} onClick={() => downloadFile(r.fileId!)}>下载</Button>
          <Popconfirm title="删除该文件？" onConfirm={async () => { await deleteFile(r.fileId!); load(); }}>
            <Button size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Upload
          showUploadList={false}
          beforeUpload={(f) => {
            uploadFile(f as unknown as File).then(() => { message.success('已上传'); load(); }).catch((e) => message.error((e as Error).message));
            return false;
          }}
        >
          <Button type="primary" icon={<UploadOutlined />}>上传文件</Button>
        </Upload>
        <span style={{ color: '#999', fontSize: 12 }}>支持图片 / 音频 / 视频 / 文档，单文件 ≤ 50MB</span>
      </Space>
      <Table rowKey="fileId" loading={loading} columns={columns} dataSource={items} pagination={false} />
    </div>
  );
}