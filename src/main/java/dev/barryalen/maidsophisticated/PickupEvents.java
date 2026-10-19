package dev.barryalen.maidsophisticated;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidPickupEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 女仆拾取相关的挂钩（只在装了车万女仆时注册）。
 *
 * 单开一个类是为了不把车万女仆的类带进 {@link Events}：
 * 女仆模组没装时那个类也照样注册，碰到女仆模组的类型就会类加载失败。
 */
public final class PickupEvents {
    private PickupEvents() {}

    /**
     * 女仆"要不要走过去捡这个东西"的判断也走这里。
     *
     * 车万女仆的拾取 AI：传感器先用 {@code maid.canPickup(实体, true)} 过滤一遍附近的掉落物，
     * 而那个方法会往"女仆自己的可用物品栏"里模拟插入——她自己背包满了就当作捡不了，
     * 连走都不走过去。这里补一刀：她自己装不下、但精妙背包还装得下时，
     * 直接把结论改成"能捡"（{@code setCanPickup(true)} 后取消事件），她就会正常走过去；
     * 真正弯腰捡的那一刻还会再经过这里（simulate=false），由我们把东西收进精妙背包。
     */
    @SubscribeEvent
    public static void onMaidPickupItem(MaidPickupEvent.ItemResultPre event) {
        // 别的模组已经拦下这次拾取：尊重它，别再插手
        if (event.isCanceled()) {
            return;
        }
        EntityMaid maid = event.getMaid();
        if (maid == null || maid.level().isClientSide || !TlmCompat.loaded()
                || !MaidSophisticated.sophisticatedBackpacksLoaded()) {
            return;
        }
        ItemEntity itemEntity = event.getEntityItem();
        if (itemEntity == null || itemEntity.isRemoved()) {
            return;
        }
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) {
            return;
        }
        MaidBackpackData data = MaidBackpackData.of(maid).orElse(null);
        if (data == null || MaidBackpackData.isEmptyHandler(data)) {
            return;
        }
        // 她自己就装得下：交回女仆模组的原逻辑，我们完全不插手
        if (TlmCompat.canInsertIntoMaidInventory(maid, stack)) {
            return;
        }
        // 精妙背包也放不下：让她照旧待在原地
        if (!anySophCanAccept(data, stack)) {
            return;
        }
        event.setCanPickup(true);
        event.setCanceled(true);
        if (event.isSimulate()) {
            // 这一趟只是试探，让她把"走过去"这件事定下来
            return;
        }
        // 真正收下：先女仆自己（能塞多少塞多少），剩下的进精妙背包
        ItemStack remaining = TlmCompat.insertIntoMaidInventory(maid, stack.copy());
        for (int i = 0; i < MaidBackpackData.SLOTS && !remaining.isEmpty(); i++) {
            remaining = SbCompat.insertIntoBackpack(data.getStackInSlot(i), remaining);
        }
        int taken = stack.getCount() - remaining.getCount();
        if (taken <= 0) {
            return;
        }
        // 和女仆自己弯腰捡一样先记一次拾取（经验/动画都按原版走），再处理掉落物
        maid.take(itemEntity, taken);
        if (remaining.isEmpty()) {
            itemEntity.discard();
        } else {
            itemEntity.setItem(remaining);
        }
        maid.playSound(SoundEvents.ITEM_PICKUP, 0.2F, ((maid.getRandom().nextFloat() - maid.getRandom().nextFloat()) * 0.7F + 1.0F) * 2.0F);
    }

    /** 三个精妙背包槽里，有没有哪一个能放下这堆东西。 */
    private static boolean anySophCanAccept(MaidBackpackData data, ItemStack stack) {
        for (int i = 0; i < MaidBackpackData.SLOTS; i++) {
            if (SbCompat.canAccept(data.getStackInSlot(i), stack)) {
                return true;
            }
        }
        return false;
    }

}
