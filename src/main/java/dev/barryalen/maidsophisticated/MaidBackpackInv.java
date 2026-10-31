package dev.barryalen.maidsophisticated;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.handler.MaidInvWrapper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把女仆身上 3 个精妙背包的"内部物品栏"拼成一个虚拟物品栏。
 *
 * <p>车万女仆判断"我手头有什么东西"永远只走
 * {@code EntityMaid#getAvailableInv} / {@code getAvailableBackpackInv} 这两个入口
 *（她自己的吃饭、喂饭、喝药、种地、放火把、挤奶、采蜜，以及各种附属模组找工具/卷轴，
 * 全都从这里读）。所以只要把精妙背包的内部格子并进这两个方法的返回值，
 * 女仆和所有附属模组就会像看待自己的格子一样看待背包里的东西——一次接通，全都生效。</p>
 *
 * <p>这里刻意返回 {@link MaidInvWrapper}（女仆自己那层包装）：车万女仆内部会用
 * {@code instanceof MaidInvWrapper} 认出"这是女仆的物品栏"，再据此去问 Curios 等
 * 外部容器要东西；如果换成普通的 {@link CombinedInvWrapper}，那条路就断了。</p>
 *
 * <p><b>用的是"{@link SbCompat#inventoryHandler} 那层带防吞护栏的仓库视图"</b>：
 * 精妙背包自己没走升级链的那份物品栏，外面再套一层"虚空升级会整堆吞掉就不收"的护栏。
 * 这是有意的，见 {@link SbCompat} 顶部说明（虚空升级会当场销毁走升级链的物品；
 * 而"女仆自动把东西塞进背包"没人盯着，吞了就是纯损失，所以这条路上必须拦）。</p>
 *
 * <p><b>防崩约定</b>：这两个方法每 tick 都会被女仆 AI 调用多次，任何一步抛异常都会
 * 顺着"可用物品栏"入口炸穿她的 AI 甚至整个服务端。所以 {@link #of} / {@link #append}
 * 全程 try/catch：拼不出来就退回"不带背包的原样返回值"，绝不把异常抛给调用方，
 * 错误只进日志一次。捕获范围是 {@code Exception | LinkageError}（后者覆盖女仆/精妙背包
 * 版本漂移导致的 {@code NoSuchMethodError}），{@code OutOfMemoryError} 这类 JVM 自身的
 * 致命错误照旧往外抛——那种情况硬撑下去只会把损坏的状态写进存档。</p>
 *
 * <p>注意：本类里不要放带初始值的静态字段。补丁类的静态代码块不会被合并进目标类，
 * 字段会一直是 null；需要跨次状态就放到本类这种普通类里。</p>
 */
public final class MaidBackpackInv {
    /**
     * 补丁入口有没有跑过。{@link #append} 只被 {@code EntityMaidInventoryMixin} 调用，
     * 进来第一件事就置位；如果补丁因为女仆版本变化没接上（注入点找不到会被静默跳过），
     * 这个标记就一直是 false——由 {@code Events} 的看门狗在日志里给出警告。
     */
    private static final AtomicBoolean HOOK_RAN = new AtomicBoolean(false);
    /**
     * 补丁有没有<b>真的把背包拼上去</b>。只有"拼出来的包装比女仆原返回值多出格子"时才置位。
     *
     * <p>光有 {@link #HOOK_RAN} 不够：注入点接上了、但每次取背包物品栏都抛异常被 catch 掉时，
     * 看门狗照样会以为一切正常。两个标记分开记，才能区分"补丁没接上"和"接上了但拼不上"。</p>
     */
    private static final AtomicBoolean HOOK_MERGED = new AtomicBoolean(false);
    /** 异常日志只打第一次：这两个方法每 tick 都跑，刷屏会把日志淹了。 */
    private static final AtomicBoolean ERROR_LOGGED = new AtomicBoolean(false);
    /** 最近一次成功拼上的背包格子数（只给看门狗那条日志用，方便确认拼上了多少格）。 */
    private static volatile int lastMergedSlots = 0;

    /**
     * 拼接结果缓存：一只女仆一份，弱引用（她卸载/死亡后自动掉）。
     *
     * <p>为什么要缓存：{@code getAvailableInv} 一个 tick 里会被女仆 AI 的传感器、
     * 任务判定、拾取判定反复调用几十次，每次都要 3 次能力查询 + 建 3 层包装对象。</p>
     *
     * <p>为什么这样缓存是安全的（缓存的不是内容，只是"包装壳"）：
     * <ul>
     *   <li>三个槽里的 {@code ItemStack} <b>对象身份</b> 逐个比对：换包、卸包、读存档、
     *       收网络包都会换对象，一比就发现；内容变化（往背包里放东西）不换对象，
     *       也不需要重建——包装壳是活的视图，读到的永远是当前内容；</li>
     *   <li>每个背包的<b>格子数</b>逐个比对：堆叠升级之类改的是"每格上限"，
     *       但列数/行数变化会让 {@link CombinedInvWrapper} 构造时算好的槽位偏移失效，
     *       所以必须重建；</li>
     *   <li>再加一层 {@link #CACHE_TICKS} 的时间上限兜底：哪怕将来出现"同一个物品堆对象
     *       内部被换了实现"这种没预料到的情况，最多也只脏这么几 tick。</li>
     * </ul>
     * 另外本模组自己改这三格时（{@code MaidBackpackData#onContentsChanged}）会直接
     * {@link #invalidate} 掉，不等上面这三条。</p>
     */
    private static final Map<LivingEntity, Cache> CACHE = new WeakHashMap<>();
    /**
     * 缓存最多活几 tick（见 {@link #CACHE} 的说明）。
     *
     * <p>取 10 tick（约 0.5 秒），和 {@code SbCompat} 那条直接调 {@code inventoryHandler}
     * 的路径的护栏有效期（500ms）对齐：加/卸虚空升级不换背包物品堆对象，也就不会触发
     * {@link #invalidate}，只能靠这个时间窗口兜住——窗口越长，"刚卸了虚空升级却还在拒收"
     * 或"刚装上虚空升级却还在吞"的时间就越长。10 tick 的代价是每只女仆每半秒重建一次
     * 包装壳（3 次能力查询），完全可以忽略。</p>
     */
    private static final long CACHE_TICKS = 10L;

    private MaidBackpackInv() {}

    /** 补丁入口是否运行过（看门狗用：false = 注入点大概率已经不存在了）。 */
    public static boolean hookRan() {
        return HOOK_RAN.get();
    }

    /** 补丁是否真的把精妙背包拼进过女仆的可用物品栏（看门狗用）。 */
    public static boolean hookMerged() {
        return HOOK_MERGED.get();
    }

    /** 最近一次拼上的格子数，没拼上过是 0。 */
    public static int lastMergedSlots() {
        return lastMergedSlots;
    }

    /**
     * 丢弃某只女仆的缓存。女仆背包槽内容被本模组改写时调用
     *（{@code MaidBackpackData#onContentsChanged}），保证下一次读一定重新拼。
     */
    public static void invalidate(LivingEntity maid) {
        if (maid == null) {
            return;
        }
        try {
            synchronized (CACHE) {
                CACHE.remove(maid);
            }
        } catch (Exception | LinkageError t) {
            // 缓存只是优化，清不掉也不能影响调用方
            logHookErrorOnce("清理女仆背包物品栏缓存", t);
        }
    }

    /**
     * 这只女仆身上所有精妙背包的内部物品栏合在一起；一个背包都没有就返回 null。
     *
     * <p>带缓存，缓存条件与安全性见 {@link #CACHE}。单个背包槽查询失败只丢那一格
     *（比如物品 NBT 被别的模组写坏、取能力时对方模组抛错），其余背包照常接上；
     * 整体异常则退回 null。</p>
     */
    public static IItemHandlerModifiable of(EntityMaid maid) {
        if (maid == null || !MaidSophisticated.sophisticatedBackpacksLoaded()) {
            return null;
        }
        try {
            MaidBackpackData data = MaidBackpackData.of(maid).orElse(null);
            if (data == null || MaidBackpackData.isEmptyHandler(data)) {
                return null;
            }
            LivingEntity owner = maid;
            ItemStack[] current = new ItemStack[MaidBackpackData.SLOTS];
            for (int i = 0; i < MaidBackpackData.SLOTS; i++) {
                current[i] = data.getStackInSlot(i);
            }
            // 没有 level（实体刚构造 / 已摘出世界）时没有可信的 tick 基准，这次干脆不缓存
            long now = maid.level() != null ? maid.level().getGameTime() : -1L;
            boolean cacheable = now >= 0L;

            Cache cached = null;
            if (cacheable) {
                synchronized (CACHE) {
                    cached = CACHE.get(owner);
                }
            }
            if (cached != null && cached.usable(now, current)) {
                return cached.composed;
            }

            Cache rebuilt = build(current, now);
            if (cacheable) {
                synchronized (CACHE) {
                    CACHE.put(owner, rebuilt);
                }
            }
            return rebuilt.composed;
        } catch (Exception | LinkageError t) {
            logHookErrorOnce("拼接女仆的精妙背包物品栏", t);
            return null;
        }
    }

    /** 现场拼一份：只在缓存未命中时走这里（一只女仆每 {@value #CACHE_TICKS} tick 最多一次）。 */
    private static Cache build(ItemStack[] stacks, long now) {
        List<IItemHandlerModifiable> handlers = new ArrayList<>(MaidBackpackData.SLOTS);
        IItemHandlerModifiable[] perSlot = new IItemHandlerModifiable[MaidBackpackData.SLOTS];
        for (int i = 0; i < MaidBackpackData.SLOTS; i++) {
            // 空槽 / 不是精妙背包的槽，SbCompat 会直接给 empty
            if (stacks[i] == null || stacks[i].isEmpty()) {
                continue;
            }
            final int slot = i;
            try {
                SbCompat.inventoryHandler(stacks[slot]).ifPresent(handler -> {
                    perSlot[slot] = handler;
                    handlers.add(handler);
                });
            } catch (Exception | LinkageError t) {
                logHookErrorOnce("读取第 " + slot + " 格背包的物品栏", t);
            }
        }
        CombinedInvWrapper composed = handlers.isEmpty()
                ? null
                : new CombinedInvWrapper(handlers.toArray(new IItemHandlerModifiable[0]));
        return new Cache(now, stacks, perSlot, composed);
    }

    /**
     * 把精妙背包接到女仆原本的"可用物品栏"后面（顺序：女仆自己的格子 → 精妙背包）。
     * 包不住（没有背包 / 没装精妙背包 / 原值异常）就原样返回，绝不把原本的返回值弄丢，
     * 也绝不向外抛异常。
     */
    public static CombinedInvWrapper append(EntityMaid maid, CombinedInvWrapper base) {
        // 进门第一件事记"补丁在跑"：哪怕后面任何一步失败，这个标记也已经置上了。
        if (!HOOK_RAN.get()) {
            HOOK_RAN.set(true);
        }
        if (base == null) {
            return null;
        }
        try {
            IItemHandlerModifiable extra = of(maid);
            if (extra == null) {
                return base;
            }
            CombinedInvWrapper merged = new MaidInvWrapper(maid, base, extra);
            // 到这一步才算"真拼上了"：不是看有没有异常，而是看格子数确实变多了。
            int mergedSlots = extra.getSlots();
            if (merged.getSlots() > base.getSlots()) {
                if (!HOOK_MERGED.get()) {
                    HOOK_MERGED.set(true);
                    MaidSophisticated.LOGGER.info(
                            "Maid available-inventory hook installed and verified: her AI now sees {} slots"
                                    + " inside Sophisticated Backpacks", mergedSlots);
                }
                // 格子数平时不会变，只在变了的时候写一次 volatile（这方法每 tick 被调很多次）
                if (lastMergedSlots != mergedSlots) {
                    lastMergedSlots = mergedSlots;
                }
            }
            return merged;
        } catch (Exception | LinkageError t) {
            logHookErrorOnce("拼接女仆可用物品栏", t);
            return base;
        }
    }

    /** 错误只报一次（带首次的异常），之后的同类异常静默——这两个方法调用频率极高。 */
    private static void logHookErrorOnce(String what, Throwable t) {
        if (ERROR_LOGGED.compareAndSet(false, true)) {
            MaidSophisticated.LOGGER.warn(
                    "maid_sophisticated: {} 时出现异常，本模组已退回女仆原始物品栏（女仆行为不受影响，"
                            + "但这一次没能看到她背包里的东西）。同类异常不再重复记录。", what, t);
        }
    }

    /** 一只女仆一份的拼接结果快照，见 {@link #CACHE}。 */
    private static final class Cache {
        private final long builtAtTick;
        private final ItemStack[] stacks;
        private final IItemHandlerModifiable[] handlers;
        private final int[] slotCounts;
        /** 拼出来的背包合并视图；一个背包都没有时是 null（负结果也缓存，免得空包女仆白查能力）。 */
        private final CombinedInvWrapper composed;

        Cache(long builtAtTick, ItemStack[] stacks, IItemHandlerModifiable[] handlers,
              CombinedInvWrapper composed) {
            this.builtAtTick = builtAtTick;
            this.stacks = stacks;
            this.handlers = handlers;
            this.composed = composed;
            this.slotCounts = new int[handlers.length];
            for (int i = 0; i < handlers.length; i++) {
                this.slotCounts[i] = handlers[i] == null ? -1 : handlers[i].getSlots();
            }
        }

        /** 这份缓存还能不能用：没过期 + 三个槽还是原来那几个对象 + 每个背包格子数没变。 */
        boolean usable(long now, ItemStack[] current) {
            // tick 往回走（换维度之类）一律当过期重算，宁可多算一次也不拿脏结果
            if (now < builtAtTick || now - builtAtTick > CACHE_TICKS) {
                return false;
            }
            for (int i = 0; i < stacks.length; i++) {
                if (stacks[i] != current[i]) {
                    return false;
                }
            }
            for (int i = 0; i < handlers.length; i++) {
                IItemHandlerModifiable handler = handlers[i];
                if (handler != null && handler.getSlots() != slotCounts[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
