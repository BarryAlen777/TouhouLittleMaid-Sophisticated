package dev.barryalen.maidsophisticated.backpack;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.backpack.BackpackManager;

/**
 * 车万女仆的官方扩展入口（{@code @LittleMaidExtension} 会被女仆模组自动扫描）。
 *
 * <p>只干一件事：把六个"精妙背包"档位注册进女仆的背包类型表。之后渲染时把她的类型临时
 * 换成其中一档，女仆自己的背包层（普通人形 / Gecko / YSM 三条都算）就会按那档的
 * 模型和贴图把她背上的包画出来——位置、朝向、躺坐、骑乘、缩放全部由女仆模组自己负责。</p>
 *
 * <p>YSM 2.4+ 的女仆兼容会把 YSM 模型转换成 TLM 的 {@code ILocationModel}（包括
 * {@code BackpackLocator}/Elytra 定位组），所以它同样走这条层，不需要额外的自绘代码。</p>
 */
@LittleMaidExtension
public class SophExtension implements ILittleMaid {
    @Override
    public void addMaidBackpack(BackpackManager manager) {
        for (SophMaidBackpack backpack : SophMaidBackpack.ALL) {
            manager.add(backpack);
        }
    }
}
