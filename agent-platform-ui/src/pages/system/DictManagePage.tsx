import { useCallback, useEffect, useState } from 'react';
import {
  App as AntApp,
  Button,
  Card,
  Col,
  Empty,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  Row,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import { PlusOutlined, ReloadOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons';
import {
  createDictItem,
  createDictType,
  deleteDictItem,
  deleteDictType,
  listDictItems,
  listDictTypes,
  updateDictItem,
  updateDictType,
  type DictItemView,
  type DictTypeView,
} from '../../api/dict';

const { Text, Title } = Typography;

/**
 * 数据字典管理。
 *
 * <p>布局是**左类型 / 右字典项**的两栏：字典的天然结构就是两层，
 * 挤在一张平表里（每次都要带一列 typeCode 重复显示）反而更难读。</p>
 *
 * <h3>几处刻意的约束（都在界面上如实体现，而不是等用户提交后才报错）</h3>
 * <ul>
 *   <li>编码（{@code typeCode} / {@code itemValue}）<b>只能填一次</b>：编辑时输入框置灰并注明原因 ——
 *       它们是稳定标识，改了会让字典项失联 / 让已写进业务数据的值解析不出标签；</li>
 *   <li>内置类型与其选项<b>没有删除按钮</b>（只显示"内置"标签）：删了对应页面的下拉会变空；</li>
 *   <li>停用（status=disabled）**不影响已存数据**，只是不再出现在下拉里。</li>
 * </ul>
 */
export default function DictManagePage() {
  const { message, modal } = AntApp.useApp();

  const [types, setTypes] = useState<DictTypeView[]>([]);
  const [loadingTypes, setLoadingTypes] = useState(false);
  const [activeCode, setActiveCode] = useState<string>();

  const [items, setItems] = useState<DictItemView[]>([]);
  const [loadingItems, setLoadingItems] = useState(false);

  const [typeModal, setTypeModal] = useState<{ open: boolean; editing?: DictTypeView }>({ open: false });
  const [itemModal, setItemModal] = useState<{ open: boolean; editing?: DictItemView }>({ open: false });
  const [typeForm] = Form.useForm();
  const [itemForm] = Form.useForm();
  const [busy, setBusy] = useState(false);

  const activeType = types.find((t) => t.typeCode === activeCode);

  const loadTypes = useCallback(async () => {
    setLoadingTypes(true);
    try {
      const list = await listDictTypes();
      setTypes(list);
      // 首次加载或当前选中项已被删除时，自动落到第一个类型
      setActiveCode((prev) => {
        if (prev && list.some((t) => t.typeCode === prev)) {
          return prev;
        }
        return list[0]?.typeCode;
      });
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载字典类型失败');
    } finally {
      setLoadingTypes(false);
    }
  }, [message]);

  const loadItems = useCallback(async () => {
    if (!activeCode) {
      setItems([]);
      return;
    }
    setLoadingItems(true);
    try {
      setItems(await listDictItems(activeCode));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载字典项失败');
    } finally {
      setLoadingItems(false);
    }
  }, [activeCode, message]);

  useEffect(() => {
    void loadTypes();
  }, [loadTypes]);

  useEffect(() => {
    void loadItems();
  }, [loadItems]);

  // ------------------------------------------------------------------ 类型

  const openTypeModal = (editing?: DictTypeView) => {
    setTypeModal({ open: true, editing });
    typeForm.setFieldsValue(
      editing
        ? { typeCode: editing.typeCode, typeName: editing.typeName, remark: editing.remark }
        : { typeCode: '', typeName: '', remark: '' },
    );
  };

  const submitType = async () => {
    const v = await typeForm.validateFields();
    setBusy(true);
    try {
      if (typeModal.editing) {
        await updateDictType(typeModal.editing.dictTypeId, { typeName: v.typeName, remark: v.remark });
        message.success('已更新字典类型');
      } else {
        await createDictType({ typeCode: v.typeCode, typeName: v.typeName, remark: v.remark });
        message.success('已创建字典类型');
        setActiveCode(v.typeCode);
      }
      setTypeModal({ open: false });
      await loadTypes();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  };

  const removeType = (t: DictTypeView) => {
    modal.confirm({
      title: `删除字典类型「${t.typeCode}」？`,
      content: `将连带删除它的 ${t.itemCount} 个选项，且不可恢复。`,
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await deleteDictType(t.dictTypeId);
          message.success('已删除');
          await loadTypes();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '删除失败');
        }
      },
    });
  };

  // ------------------------------------------------------------------ 字典项

  const openItemModal = (editing?: DictItemView) => {
    setItemModal({ open: true, editing });
    itemForm.setFieldsValue(
      editing
        ? { ...editing, enabled: editing.status === 'active' }
        : { itemValue: '', itemLabel: '', sortOrder: (items.length + 1) * 10, remark: '', enabled: true },
    );
  };

  const submitItem = async () => {
    const v = await itemForm.validateFields();
    if (!activeCode) {
      return;
    }
    const body = {
      itemLabel: v.itemLabel,
      sortOrder: v.sortOrder,
      remark: v.remark,
      status: v.enabled ? 'active' : 'disabled',
    };
    setBusy(true);
    try {
      if (itemModal.editing) {
        await updateDictItem(itemModal.editing.dictItemId, body);
        message.success('已更新选项');
      } else {
        await createDictItem(activeCode, { ...body, itemValue: v.itemValue });
        message.success('已创建选项');
      }
      setItemModal({ open: false });
      await Promise.all([loadItems(), loadTypes()]);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  };

  const removeItem = (i: DictItemView) => {
    modal.confirm({
      title: `删除选项「${i.itemValue}」？`,
      content: '如果历史数据里已经用了这个值，它将只能显示原始值、解析不出中文标签。',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await deleteDictItem(i.dictItemId);
          message.success('已删除');
          await Promise.all([loadItems(), loadTypes()]);
        } catch (e) {
          message.error(e instanceof Error ? e.message : '删除失败');
        }
      },
    });
  };

  // ------------------------------------------------------------------ 渲染

  return (
    <div>
      <Title level={4} style={{ marginTop: 0 }}>
        数据字典
      </Title>
      <Text type="secondary">
        集中管理各类下拉选项（日志级别、切分策略、内置工具等）。改标签不影响已存数据，改编码不允许。
      </Text>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col xs={24} lg={9}>
          <Card
            size="small"
            title="字典类型"
            extra={
              <Space>
                <Button
                  size="small"
                  icon={<ReloadOutlined />}
                  onClick={() => {
                    void loadTypes();
                  }}
                />
                <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => openTypeModal()}>
                  新建
                </Button>
              </Space>
            }
          >
            <List
              size="small"
              loading={loadingTypes}
              dataSource={types}
              locale={{ emptyText: <Empty description="还没有字典类型" /> }}
              renderItem={(t) => (
                <List.Item
                  style={{
                    cursor: 'pointer',
                    background: t.typeCode === activeCode ? 'rgba(37,99,235,.08)' : undefined,
                    borderRadius: 6,
                    paddingLeft: 8,
                  }}
                  onClick={() => setActiveCode(t.typeCode)}
                  actions={[
                    <EditOutlined
                      key="e"
                      onClick={(ev) => {
                        ev.stopPropagation();
                        openTypeModal(t);
                      }}
                    />,
                    t.builtin ? (
                      <Tag key="b" color="blue">
                        内置
                      </Tag>
                    ) : (
                      <DeleteOutlined
                        key="d"
                        style={{ color: '#ff4d4f' }}
                        onClick={(ev) => {
                          ev.stopPropagation();
                          removeType(t);
                        }}
                      />
                    ),
                  ]}
                >
                  <List.Item.Meta
                    title={
                      <Space size={6}>
                        <span>{t.typeName}</span>
                        <Text code style={{ fontSize: 12 }}>
                          {t.typeCode}
                        </Text>
                      </Space>
                    }
                    description={
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {t.itemCount} 个选项{t.remark ? ` · ${t.remark}` : ''}
                      </Text>
                    }
                  />
                </List.Item>
              )}
            />
          </Card>
        </Col>

        <Col xs={24} lg={15}>
          <Card
            size="small"
            title={activeType ? `选项 · ${activeType.typeName}` : '选项'}
            extra={
              <Space>
                <Button
                  size="small"
                  icon={<ReloadOutlined />}
                  onClick={() => {
                    void loadItems();
                  }}
                />
                <Button
                  size="small"
                  type="primary"
                  icon={<PlusOutlined />}
                  disabled={!activeCode}
                  onClick={() => openItemModal()}
                >
                  新建选项
                </Button>
              </Space>
            }
          >
            <Table<DictItemView>
              size="small"
              rowKey="dictItemId"
              loading={loadingItems}
              dataSource={items}
              pagination={false}
              locale={{ emptyText: <Empty description={activeCode ? '该字典还没有选项' : '请先选择左侧的字典类型'} /> }}
              columns={[
                { title: '值（写进业务数据）', dataIndex: 'itemValue', width: 160 },
                { title: '标签（给人看）', dataIndex: 'itemLabel', width: 160 },
                { title: '排序', dataIndex: 'sortOrder', width: 70 },
                {
                  title: '状态',
                  dataIndex: 'status',
                  width: 80,
                  render: (s: string) =>
                    s === 'active' ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>,
                },
                {
                  title: '操作',
                  width: 90,
                  render: (_: unknown, i: DictItemView) => (
                    <Space>
                      <EditOutlined onClick={() => openItemModal(i)} />
                      {activeType?.builtin ? (
                        // 内置字典的选项不可删：删了对应页面的下拉会变空 —— 用停用代替
                        <DeleteOutlined style={{ color: '#d9d9d9' }} title="内置字典的选项不可删除，可停用" />
                      ) : (
                        <DeleteOutlined style={{ color: '#ff4d4f' }} onClick={() => removeItem(i)} />
                      )}
                    </Space>
                  ),
                },
              ]}
            />
          </Card>
        </Col>
      </Row>

      {/* 新建/编辑字典类型 */}
      <Modal
        open={typeModal.open}
        title={typeModal.editing ? '编辑字典类型' : '新建字典类型'}
        onCancel={() => setTypeModal({ open: false })}
        onOk={submitType}
        confirmLoading={busy}
        destroyOnClose
      >
        <Form form={typeForm} layout="vertical">
          <Form.Item
            name="typeCode"
            label="类型编码"
            rules={[{ required: true, message: '请输入类型编码' }]}
            extra={typeModal.editing ? '编码是稳定标识，创建后不可修改' : '如 log_level，前端按它取字典；建议用下划线命名'}
          >
            <Input disabled={!!typeModal.editing} placeholder="log_level" />
          </Form.Item>
          <Form.Item name="typeName" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="日志级别" />
          </Form.Item>
          <Form.Item name="remark" label="说明">
            <Input placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 新建/编辑字典项 */}
      <Modal
        open={itemModal.open}
        title={itemModal.editing ? '编辑选项' : '新建选项'}
        onCancel={() => setItemModal({ open: false })}
        onOk={submitItem}
        confirmLoading={busy}
        destroyOnClose
      >
        <Form form={itemForm} layout="vertical">
          <Form.Item
            name="itemValue"
            label="值"
            rules={[{ required: true, message: '请输入值' }]}
            extra={
              itemModal.editing
                ? '值会被写进业务数据，创建后不可修改'
                : '存进业务字段的值，如 INFO；建议用英文'
            }
          >
            <Input disabled={!!itemModal.editing} placeholder="INFO" />
          </Form.Item>
          <Form.Item name="itemLabel" label="标签" rules={[{ required: true, message: '请输入标签' }]}>
            <Input placeholder="信息" />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序" extra="越小越靠前">
            <InputNumber min={0} max={9999} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="remark" label="说明">
            <Input placeholder="可选" />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked" extra="停用后不再出现在下拉里，但历史数据仍能解析出标签">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
