import { Select, type SelectProps } from 'antd';
import { useDict, useDictReady } from '../dict/store';

/**
 * 数据字典下拉。
 *
 * <p>用法：{@code <DictSelect code={DICT.LOG_LEVEL} />}，其余 props 透传给 antd 的 Select。</p>
 *
 * <p><b>未加载完时显示 loading 而不是空列表</b>：否则用户会看到"下拉是空的"，
 * 误以为没有可选项，而不是"还在加载"。</p>
 */
export default function DictSelect({ code, ...rest }: { code: string } & SelectProps) {
  const options = useDict(code);
  const ready = useDictReady();
  return (
    <Select
      allowClear
      options={options}
      loading={!ready}
      placeholder={rest.placeholder ?? '请选择'}
      {...rest}
    />
  );
}
