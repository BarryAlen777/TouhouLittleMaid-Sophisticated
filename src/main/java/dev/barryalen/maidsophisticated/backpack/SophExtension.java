package dev.barryalen.maidsophisticated.backpack;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.backpack.BackpackManager;

/**
 * 车万女仆的官方扩展入口（{@code @LittleMaidExtension} 会被女仆模组自动扫描）。
 * 这里只干一件事：把六个"精妙背包"档位注册进女仆的背包类型表，
 * 之后她背上的渲染层才能按类型 id 找到我们的模型和贴图。
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
