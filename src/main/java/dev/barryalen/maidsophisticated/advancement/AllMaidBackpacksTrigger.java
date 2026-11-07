package dev.barryalen.maidsophisticated.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.barryalen.maidsophisticated.MaidSophisticated;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * "我什么都不缺了"触发器：某一只女仆的三个专属背包槽全部装满精妙背包时触发。
 *
 * <p>和 {@link FirstMaidBackpackTrigger} 一样，成就按原版语义每个玩家只发放一次：
 * 第一次满足条件弹提示音与 toast，之后无论拆装多少次都不会再弹（成就图标只在进度界面里亮着）。</p>
 *
 * <p>1.21.1 起触发器实例改用 codec 反序列化，注册走 DeferredRegister。</p>
 */
public class AllMaidBackpacksTrigger extends SimpleCriterionTrigger<AllMaidBackpacksTrigger.Instance> {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(MaidSophisticated.MOD_ID, "all_maid_backpacks");
    public static final AllMaidBackpacksTrigger INSTANCE = new AllMaidBackpacksTrigger();

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player) {
        trigger(player, instance -> true);
    }

    public record Instance(Optional<ContextAwarePredicate> player) implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player)
        ).apply(i, Instance::new));
    }
}
