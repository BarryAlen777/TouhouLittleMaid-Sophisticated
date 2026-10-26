package dev.barryalen.maidsophisticated;

import com.github.tartaricacid.touhoulittlemaid.api.event.InteractMaidEvent;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidPickupEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 引用车万女仆事件类的挂钩（只在装了车万女仆时注册）。
 *
 * 单开一个类是为了不把车万女仆的类带进 {@link Events}：
 * 女仆模组没装时那个类也照样注册，碰到女仆模组的类型就会类加载失败。
 */
public final class PickupEvents {
    /**
     * 试探结果的短缓存：女仆的拾取传感器每 tick 都会对范围内每个掉落物问一次
     * "她捡不捡得动"，而我们为了回答要把女仆物品栏和三个精妙背包的全部槽位模拟
     * 插入一遍。同一个掉落物 20 tick（1 秒）内结论复用一次，足够把开销压下去；
     * 真正弯腰捡的那一次（simulate=false）永远现场重算，不吃缓存。
     *
     * 外层按女仆分桶（弱引用，她离开世界后自动清理），内层 key 是掉落物实体 id，
     * value 为 [过期 tick, 是否要改判能捡(0/1), 记录时的物品数量]。
     * 数量变了（被别人捡走一半之类）缓存作废重算。
     */
    private static final Map<EntityMaid, Map<Integer, long[]>> SIM_CACHE = new WeakHashMap<>();
    private static final long SIM_CACHE_TICKS = 20L;
    /** 单只女仆的缓存条目上限：超过就先清过期，还超就整个清掉（宁可多算，不可泄漏）。 */
    private static final int SIM_CACHE_MAX = 64;

    private PickupEvents() {}

    /**
     * 兜底拦截"拿占位物品右键女仆"触发的换包流程（第一道在 {@link Events} 的交互事件里，
     * 但别的模组可能直接调 {@code EntityMaid#interact}，绕过玩家交互事件）。
     *
     * 女仆模组按各背包类型的 getItem() 建了"物品→背包类型"映射，我们六档精妙背包
     * 共用同一个生存里拿不到的占位物品：命中这条映射就会把女仆自己的背包当场挤下来
     * 换成精妙档（还会吃掉 1 个占位物品）。在 HIGHEST 优先级取消后，女仆模组
     * 默认优先级、不接收已取消事件的换包处理器根本不会执行；两侧都会走到这里，
     * 所以客户端那半套换包预测同样被拦住。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteractMaidGuard(InteractMaidEvent event) {
        if (event.getStack().getItem() == ModItems.MAID_BACKPACK_MARKER.get()) {
            event.setCanceled(true);
        }
    }

    /**
     * 女仆"要不要走过去捡这个东西"的判断也走这里。
     *
     * 车万女仆的拾取 AI：传感器先用 {@code maid.canPickup(实体, true)} 过滤一遍附近的掉落物，
     * 而那个方法会往"女仆自己的可用物品栏"里模拟插入——她自己背包满了就当作捡不了，
     * 连走都不走过去。这里补一刀：她自己装不下、但精妙背包还装得下时，
     * 直接把结论改成"能捡"（{@code setCanPickup(true)} 后取消事件），她就会正常走过去；
     * 真正弯腰捡的那一刻还会再经过这里（simulate=false），由我们把东西收进精妙背包。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
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
        // 女仆模组自己的拾取规则（配置黑名单等）：她本来就不能捡的东西，我们也不替她捡
        if (!TlmCompat.canMaidPickupItem(stack)) {
            return;
        }
        MaidBackpackData data = MaidBackpackData.of(maid).orElse(null);
        if (data == null || MaidBackpackData.isEmptyHandler(data)) {
            return;
        }
        if (event.isSimulate()) {
            // 试探：结论走 1 秒缓存，命中就直接按缓存改判/放行，不再全槽模拟
            if (!sophCanRescue(maid, itemEntity, stack, data)) {
                return;
            }
            event.setCanPickup(true);
            event.setCanceled(true);
            return;
        }
        // 真正弯腰捡的那一次：现场重算，不吃缓存
        invalidateSimEntry(maid, itemEntity.getId());
        if (TlmCompat.canInsertIntoMaidInventory(maid, stack)) {
            return;
        }
        // 精妙背包也放不下：让她照旧待在原地
        if (!anySophCanAccept(data, stack)) {
            return;
        }
        event.setCanPickup(true);
        event.setCanceled(true);
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
        TlmCompat.playPickupSound(maid);
        // 补发女仆模组的"拾取完成"事件：这次拾取是我们取消原逻辑后自己完成的，
        // 不补发的话依赖它的模组/脚本就收不到通知。
        TlmCompat.postPickupResult(maid, stack, taken);
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

    /**
     * 试探结论（带 1 秒缓存）："她自己装不下、但精妙背包还装得下"吗？
     * 只有这个组合才需要将拾取改判为能捡；两种否定情况女仆模组的原判断照常生效。
     */
    private static boolean sophCanRescue(EntityMaid maid, ItemEntity itemEntity, ItemStack stack,
                                         MaidBackpackData data) {
        long now = maid.level().getGameTime();
        Map<Integer, long[]> perMaid = SIM_CACHE.computeIfAbsent(maid, k -> new HashMap<>());
        long[] entry = perMaid.get(itemEntity.getId());
        if (entry != null && entry[0] > now && entry[2] == stack.getCount()) {
            return entry[1] == 1L;
        }
        boolean rescue = !TlmCompat.canInsertIntoMaidInventory(maid, stack) && anySophCanAccept(data, stack);
        if (perMaid.size() >= SIM_CACHE_MAX) {
            perMaid.values().removeIf(cached -> cached[0] <= now);
            if (perMaid.size() >= SIM_CACHE_MAX) {
                perMaid.clear();
            }
        }
        perMaid.put(itemEntity.getId(), new long[]{now + SIM_CACHE_TICKS, rescue ? 1L : 0L, stack.getCount()});
        return rescue;
    }

    /** 真捡之后这条缓存作废：物品可能少了一半、也可能没了，别拿旧结论糊弄下一轮试探。 */
    private static void invalidateSimEntry(EntityMaid maid, int itemEntityId) {
        Map<Integer, long[]> perMaid = SIM_CACHE.get(maid);
        if (perMaid != null) {
            perMaid.remove(itemEntityId);
        }
    }

}
