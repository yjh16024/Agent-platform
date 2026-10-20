import { Fragment } from 'react';
import { usePluginSlotEntries } from '../plugins/host/pluginSlots';

/**
 * 插件注册区的宿主容器（2026-10-19，P1）。
 *
 * <p>与 {@code SlotOutlet} 的区别：那个渲染**后端下发的声明**，这个渲染**插件运行期注册的元素**
 * （`ctx.slots.register`）。两者最终都是"往宿主某个位置放东西"，但数据来源与生命周期不同 ——
 * 所以是两套实现，不硬合并。</p>
 *
 * <p>空区域返回 null（不占位）：装饰性区域留下空 div 会在布局里形成看不见的间隙。</p>
 */
export default function PluginSlotHost({ slot }: { slot: string }) {
  const entries = usePluginSlotEntries(slot);
  if (entries.length === 0) {
    return null;
  }
  return (
    <>
      {entries.map((entry, index) => (
        <Fragment key={`${entry.pluginId}:${index}`}>{entry.node}</Fragment>
      ))}
    </>
  );
}
