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
 * "这就很妙"触发器：女仆第一次背上精妙背包时触发。
 * 成就本身按原版语义每个玩家只发放一次，之后换包、重装都不会再弹。
 *
 * <p>1.21.1 起触发器实例改用 codec 反序列化（旧的 createInstance/getId 已删），
 * 注册走 {@code MaidSophisticated.TRIGGERS}  DeferredRegister。</p>
 */
public class FirstMaidBackpackTrigger extends SimpleCriterionTrigger<FirstMaidBackpackTrigger.Instance> {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(MaidSophisticated.MOD_ID, "first_maid_backpack");
    public static final FirstMaidBackpackTrigger INSTANCE = new FirstMaidBackpackTrigger();

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
