package dev.barryalen.maidsophisticated.advancement;

import dev.barryalen.maidsophisticated.MaidSophisticated;
import com.google.gson.JsonObject;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * "我什么都不缺了"触发器：某一只女仆的三个专属背包槽全部装满精妙背包时触发。
 *
 * <p>和 {@link FirstMaidBackpackTrigger} 一样，成就按原版语义每个玩家只发放一次：
 * 第一次满足条件弹提示音与 toast，之后无论拆装多少次都不会再弹（成就图标只在进度界面里亮着）。</p>
 */
public class AllMaidBackpacksTrigger extends SimpleCriterionTrigger {
    public static final ResourceLocation ID = new ResourceLocation(MaidSophisticated.MOD_ID, "all_maid_backpacks");
    public static final AllMaidBackpacksTrigger INSTANCE = new AllMaidBackpacksTrigger();

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected AbstractCriterionTriggerInstance createInstance(JsonObject json, ContextAwarePredicate playerPredicate,
                                                              DeserializationContext context) {
        return new Instance(ID, playerPredicate);
    }

    public void trigger(ServerPlayer player) {
        trigger(player, instance -> true);
    }

    public static class Instance extends AbstractCriterionTriggerInstance {
        public Instance(ResourceLocation criterionIn, ContextAwarePredicate playerPredicate) {
            super(criterionIn, playerPredicate);
        }

        @Override
        public JsonObject serializeToJson(SerializationContext serializationContext) {
            return new JsonObject();
        }
    }
}
