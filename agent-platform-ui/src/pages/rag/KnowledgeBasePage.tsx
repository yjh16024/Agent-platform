import { useCallback, useEffect, useState } from 'react';
import {
  Table, Button, Space, Input, InputNumber, Modal, Form, Select, Tag, message, Upload, Card, List,
  Popconfirm, Drawer, Typography, Collapse, Tooltip, Spin,
} from 'antd';
import { PlusOutlined, UploadOutlined, SearchOutlined, EyeOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  listKbs, createKb, uploadDoc, searchKb, deleteKb, listDocuments, listChunks, deleteDocument,
} from '../../api/knowledgeBases';
import { KnowledgeBase, RetrievalResult, DocMeta, ChunkMeta } from '../../api/types';
import { useDict } from '../../dict/store';
import { DICT } from '../../api/dict';

const fmtSize = (n?: number) => {
  if (!n) return '—';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
};

export default function KnowledgeBasePage() {
  /**
   * 切分策略来自数据字典。
   * 原来这里写死 ['recursive','semantic','structural']，与后端真实注册的 Chunker 两份定义；
   * 字典初值由 DictSeeder 从 Spring 容器里的 Chunker Bean 动态同步，加新切分器只需写一个类。
   */
  const chunkStrategyOptions = useDict(DICT.CHUNK_STRATEGY);
  const [items, setItems] = useState<KnowledgeBase[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [searchKbId, setSearchKbId] = useState<string>();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<RetrievalResult[]>([]);
  const [form] = Form.useForm();

  // 内容浏览态
  const [browseKb, setBrowseKb] = useState<KnowledgeBase | null>(null);
  const [documents, setDocuments] = useState<DocMeta[]>([]);
  const [browseLoading, setBrowseLoading] = useState(false);
  const [chunkKbId, setChunkKbId] = useState<string | null>(null);
  const [chunkDocTitle, setChunkDocTitle] = useState('');
  const [chunks, setChunks] = useState<ChunkMeta[]>([]);
  const [chunkLoading, setChunkLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setItems(await listKbs());
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const submitCreate = async () => {
    const v = await form.validateFields();
    try {
      await createKb(v);
      message.success('知识库已创建');
      setCreateOpen(false);
      form.resetFields();
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const doSearch = async () => {
    if (!searchKbId || !query.trim()) {
      message.warning('请选择知识库并输入检索词');
      return;
    }
    try {
      setResults(await searchKb([searchKbId], query.trim()));
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const openBrowse = async (r: KnowledgeBase) => {
    setBrowseKb(r);
    setBrowseLoading(true);
    try {
      setDocuments(await listDocuments(r.kbId!));
    } catch (e) {
      message.error((e as Error).message);
      setDocuments([]);
    } finally {
      setBrowseLoading(false);
    }
  };

  const viewChunks = async (kbId: string, docId: string | undefined, docTitle: string) => {
    setChunkKbId(kbId);
    setChunkDocTitle(docTitle);
    setChunkLoading(true);
    setChunks([]);
    try {
      setChunks(await listChunks(kbId, docId));
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setChunkLoading(false);
    }
  };

  const delDoc = async (doc: DocMeta) => {
    try {
      await deleteDocument(doc.docId!);
      message.success('文档已删除');
      setDocuments(await listDocuments(browseKb?.kbId!));
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const delKb = async (r: KnowledgeBase) => {
    try {
      await deleteKb(r.kbId!);
      message.success('知识库已删除（含文档与向量）');
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const upload = (r: KnowledgeBase, f: File) => {
    uploadDoc(r.kbId!, f)
      .then(() => {
        message.success(`文档 ${f.name} 已上传并摄取`);
        load();
        // 若正在浏览该知识库，同步刷新文档列表
        if (browseKb?.kbId === r.kbId) openBrowse(r);
      })
      .catch((e) => message.error((e as Error).message));
  };

  const columns = [
    { title: '名称', dataIndex: 'name', render: (v: string, r: KnowledgeBase) => <a onClick={() => openBrowse(r)}>{v}</a> },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    {
      title: '内容', width: 130,
      render: (_: unknown, r: KnowledgeBase) => (
        <span style={{ fontSize: 12, color: '#888' }}>{r.documentCount ?? 0} 文档 · {r.chunkCount ?? 0} chunk</span>
      ),
    },
    { title: '切分策略', dataIndex: 'chunkStrategy', width: 110 },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: (v: string) => <Tag color={v === 'active' ? 'green' : 'default'}>{v}</Tag>,
    },
    {
      title: '操作', width: 300,
      render: (_: unknown, r: KnowledgeBase) => (
        <Space>
          <Button size="small" icon={<EyeOutlined />} onClick={() => openBrowse(r)}>浏览</Button>
          <Upload
            showUploadList={false}
            beforeUpload={(f) => { upload(r, f as unknown as File); return false; }}
          >
            <Button size="small" icon={<UploadOutlined />}>上传文档</Button>
          </Upload>
          <Popconfirm title="删除该知识库（文档与向量一并删除）？" onConfirm={() => delKb(r)}>
            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建知识库</Button>
        <Select
          placeholder="选择知识库检索"
          style={{ width: 220 }}
          value={searchKbId}
          onChange={setSearchKbId}
          options={items.map((k) => ({ value: k.kbId, label: k.name }))}
        />
        <Input
          placeholder="检索词"
          style={{ width: 240 }}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onPressEnter={doSearch}
        />
        <Button icon={<SearchOutlined />} onClick={doSearch}>RAG 检索</Button>
      </Space>

      <Table rowKey="kbId" loading={loading} columns={columns} dataSource={items} pagination={false} />

      {results.length > 0 && (
        <Card title="检索结果" style={{ marginTop: 16 }}>
          <List
            dataSource={results}
            renderItem={(r) => (
              <List.Item>
                <List.Item.Meta
                  title={<Space><span>分数 {r.score?.toFixed(3)}</span><Tag>chunk {r.chunkId?.slice(0, 12)}</Tag>{r.page ? <Tag>p.{r.page}</Tag> : null}</Space>}
                  description={<Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{r.content}</Typography.Paragraph>}
                />
              </List.Item>
            )}
          />
        </Card>
      )}

      {/* 浏览知识库内容（文档列表） */}
      <Drawer
        title={`浏览知识库：${browseKb?.name ?? ''}`}
        open={browseKb !== null}
        onClose={() => setBrowseKb(null)}
        width={760}
        extra={<Button size="small" icon={<ReloadOutlined />} onClick={() => browseKb && openBrowse(browseKb)}>刷新</Button>}
      >
        <Space style={{ marginBottom: 12 }} wrap>
          <Upload
            showUploadList={false}
            beforeUpload={(f) => { if (browseKb) upload(browseKb, f as unknown as File); return false; }}
          >
            <Button type="primary" size="small" icon={<UploadOutlined />}>上传文档到该知识库</Button>
          </Upload>
          <Button size="small" onClick={() => viewChunks(browseKb?.kbId!, undefined, '(全部文档)')}>查看全部 chunk</Button>
        </Space>
        <Table
          rowKey="docId"
          size="small"
          loading={browseLoading}
          dataSource={documents}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          locale={{ emptyText: '暂无文档，点击上方「上传文档」摄取内容' }}
          columns={[
            { title: '文件名', dataIndex: 'fileName', ellipsis: true, render: (v: string, d: DocMeta) => <Tooltip title={d.docId}>{v}</Tooltip> },
            { title: '类型', dataIndex: 'fileType', width: 80 },
            { title: '大小', dataIndex: 'fileSize', width: 90, render: fmtSize },
            {
              title: '状态', dataIndex: 'status', width: 90,
              render: (v: string, d: DocMeta) => (
                <Tooltip title={d.errorMsg || ''}>
                  <Tag color={v === 'indexed' ? 'green' : v === 'failed' ? 'red' : 'orange'}>{v}</Tag>
                </Tooltip>
              ),
            },
            { title: 'chunk', dataIndex: 'chunkCount', width: 70 },
            {
              title: '操作', width: 200,
              render: (_: unknown, d: DocMeta) => (
                <Space>
                  <Button size="small" onClick={() => viewChunks(d.kbId ?? browseKb?.kbId!, d.docId, d.fileName || d.title || d.docId!)}>查看切分</Button>
                  <Popconfirm title="删除该文档（含其 chunk 与向量）？" onConfirm={() => delDoc(d)}>
                    <Button size="small" danger>删除</Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </Drawer>

      {/* 查看切分块内容 */}
      <Drawer
        title={`切分内容：${chunkDocTitle}`}
        open={chunkKbId !== null}
        onClose={() => setChunkKbId(null)}
        width={640}
      >
        {chunkLoading && <div style={{ textAlign: 'center', padding: 24 }}><Spin /> 加载切分中…</div>}
        <Collapse
          items={(chunks ?? []).map((c, i) => ({
            key: String(i),
            label: `#${(c.seqNo ?? 0)} chunk ${c.chunkId?.slice(0, 12)}${c.meta?.page ? ` · p.${c.meta.page}` : ''}`,
            children: (
              <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0, fontSize: 13 }}>
                {c.content}
              </Typography.Paragraph>
            ),
          }))}
        />
        {!chunkLoading && (chunks ?? []).length === 0 && <Card size="small" style={{ marginTop: 12 }}>该范围暂无切分内容（请先上传文档摄取）</Card>}
      </Drawer>

      <Modal title="新建知识库" open={createOpen} onOk={submitCreate} onCancel={() => setCreateOpen(false)} destroyOnClose>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="chunkStrategy" label="切分策略" initialValue="recursive">
            <Select options={chunkStrategyOptions} />
          </Form.Item>
          <Form.Item name="chunkSize" label="chunk 大小" initialValue={512}>
            <InputNumber min={64} max={4096} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="chunkOverlap" label="chunk 重叠" initialValue={50}>
            <InputNumber min={0} max={512} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
