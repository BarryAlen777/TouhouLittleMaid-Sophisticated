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
 * "这就很妙"触发器：女仆第一次背上精妙背包时触发。
 * 成就本身按原版语义每个玩家只发放一次，之后换包、重装都不会再弹。
 */
public class FirstMaidBackpackTrigger extends SimpleCriterionTrigger {
    public static final ResourceLocation ID = new ResourceLocation(MaidSophisticated.MOD_ID, "first_maid_backpack");
    public static final FirstMaidBackpackTrigger INSTANCE = new FirstMaidBackpackTrigger();

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
