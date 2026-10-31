package dev.barryalen.maidsophisticated.mixin;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 只在"车万女仆 + 精妙背包"都装了时才应用本模组的补丁。
 *
 * <p>本模组对这两个依赖都是可选的：没装女仆模组时 {@code EntityMaid} 根本不存在，
 * 直接应用补丁会在启动时炸掉；没装精妙背包时补丁虽然不会炸，但它唯一的用途
 * （把背包并进女仆物品栏）永远用不上，是纯死代码。Mixin 配置插件会在类还没被
 * 加载时先问一句"要不要应用"，这里就是那个开关——两个模组齐全才放行，缺任何一个
 * 补丁整个跳过，模组照常加载。</p>
 */
public final class MaidMixinPlugin implements IMixinConfigPlugin {
    private static final String TLM_MOD_ID = "touhou_little_maid";
    private static final String SB_MOD_ID = "sophisticatedbackpacks";

    @Override
    public void onLoad(String mixinPackage) {
        // 不需要额外准备
    }

    @Override
    public String getRefMapperConfig() {
        // 我们只注车万女仆自己的方法（模组方法不参与混淆重映射），不需要 refmap
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        LoadingModList mods = LoadingModList.get();
        if (mods == null) {
            return false;
        }
        // 女仆和精妙背包都得在场：这个补丁唯一的活就是把精妙背包并进女仆的物品栏，
        // 缺任何一个它都是纯死代码（还得白挨一次注入），整条跳过最干净。
        // 用 LoadingModList（类加载期就能用的那份模组清单），不能用 ModList.get()。
        return mods.getModFileById(TLM_MOD_ID) != null
                && mods.getModFileById(SB_MOD_ID) != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // 没有跨配置的目标
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // 不需要在应用前改字节码
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // 不需要在应用后改字节码
    }
}
