package dev.barryalen.maidsophisticated.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.barryalen.maidsophisticated.MaidBackpackInv;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把精妙背包并进车万女仆的"可用物品栏"（1.20.1 车万女仆 1.5.3 实测）。
 *
 * <p>补的是两个方法，都是车万女仆自己对外暴露、所有 AI 与附属模组共用的入口：</p>
 * <ul>
 *   <li>{@code getAvailableInv(boolean includeHands)}：女仆物品栏 +（可选）双手，
 *       吃饭、喂主人、喝药、种地、放火把、挤奶、采蜜、捡东西全走它；</li>
 *   <li>{@code getAvailableBackpackInv()}：只有背包部分的视图，
 *       工作/回家吃饭、自我治疗、憋气换气走它。</li>
 * </ul>
 *
 * <p>只在服务端接：客户端那份背包数据是同步下来的副本，往里写会和真实数据打架。</p>
 *
 * <p>注意：这个类里不要放带初始值的静态字段。补丁类的静态代码块不会被合并进目标类，
 * 字段会一直是 null；需要跨次状态就放到 {@link MaidBackpackInv} 那种普通类里。</p>
 */
@Mixin(value = EntityMaid.class, remap = false)
public abstract class EntityMaidInventoryMixin {

    @Inject(method = "getAvailableInv(Z)Lnet/minecraftforge/items/wrapper/CombinedInvWrapper;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void maidSophisticated$appendBackpacks(boolean includeHands,
                                                   CallbackInfoReturnable<CombinedInvWrapper> cir) {
        EntityMaid self = (EntityMaid) (Object) this;
        if (self.level() == null || self.level().isClientSide) {
            return;
        }
        CombinedInvWrapper replaced = MaidBackpackInv.append(self, cir.getReturnValue());
        if (replaced != cir.getReturnValue()) {
            cir.setReturnValue(replaced);
        }
    }

    @Inject(method = "getAvailableBackpackInv()Lnet/minecraftforge/items/wrapper/CombinedInvWrapper;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void maidSophisticated$appendBackpacksOnly(CallbackInfoReturnable<CombinedInvWrapper> cir) {
        EntityMaid self = (EntityMaid) (Object) this;
        if (self.level() == null || self.level().isClientSide) {
            return;
        }
        CombinedInvWrapper replaced = MaidBackpackInv.append(self, cir.getReturnValue());
        if (replaced != cir.getReturnValue()) {
            cir.setReturnValue(replaced);
        }
    }
}
