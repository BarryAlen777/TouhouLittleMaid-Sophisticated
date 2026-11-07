package dev.barryalen.maidsophisticated;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 挂在女仆实体上的"专属精妙背包槽"：3 格，独立于女仆自身的背包 / 饰品格 / 隐藏栏。
 * 这里刻意不引用精妙背包模组的任何类——判断"是不是精妙背包"只用注册表命名空间，
 * 这样即使没装精妙背包，本类也不会因为类加载而崩溃。
 *
 * 另外记着一个"主背包显示"开关：女仆同时有自己的背包和精妙背包时，
 * 背上到底显示哪一个，由这个开关决定（默认显示女仆自己的背包）。
 */
public class MaidBackpackData extends ItemStackHandler {
    public static final int SLOTS = 3;
    /** 读档解析失败的日志只打第一次：能力查询在 AI / 渲染里高频发生，刷屏会淹了日志。 */
    private static final AtomicBoolean READ_FAILED_LOGGED = new AtomicBoolean(false);
    /** 写档失败的日志同样只打第一次：写档点分散在点击、世界保存、关服、定时 tick 多处。 */
    private static final AtomicBoolean SAVE_FAILED_LOGGED = new AtomicBoolean(false);
    /** "同步包写到了服务端数据上"这条防线日志只打一次（见 {@link #loadRemote}）。 */
    private static final AtomicBoolean REMOTE_ON_SERVER_LOGGED = new AtomicBoolean(false);
    /** 持久化到实体 ForgeData 的标签名。 */
    public static final String SAVE_TAG = "maid_sb_backpacks";
    /** "主背包显示精妙背包"开关在存档里的键名。 */
    private static final String TAG_SHOW_SOPHISTICATED = "show_sophisticated";
    /** "背上显示第几个背包"在存档里的键名。 */
    private static final String TAG_MAIN_SLOT = "main_slot";

    private final LivingEntity owner;
    private boolean applyingRemote = false;
    /** true = 背上显示精妙背包；false = 显示女仆自己的背包。 */
    private boolean showSophisticated = false;
    /** 背上显示的是第几个槽的背包（-1 = 没手动选过，自动取第一个有背包的格子）。 */
    private int mainSlot = -1;
    /**
     * "一个背包都没有"这件事是否已经同步给客户端过（只在运行期用，不进存档）。
     * 兜底的周期性外观广播靠它跳过一直空着的女仆，不再每两秒全服重发空包。
     */
    private transient boolean emptyStateSynced = false;

    public MaidBackpackData(LivingEntity owner) {
        super(SLOTS);
        this.owner = owner;
    }

    public static Optional<MaidBackpackData> of(net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return Optional.empty();
        }
        return Optional.ofNullable(living.getCapability(MaidCaps.MAID_BACKPACK));
    }

    /** 只按注册表判断，避免类加载依赖精妙背包本体。 */
    public static boolean isSophisticatedBackpack(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && key.getNamespace().equals(MaidSophisticated.SB_MOD_ID)
                && key.getPath().endsWith("backpack");
    }

    @Override
    public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
        return isSophisticatedBackpack(stack);
    }

    public static boolean isEmptyHandler(ItemStackHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) {
            if (!handler.getStackInSlot(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public List<ItemStack> copyStacks() {
        List<ItemStack> list = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            list.add(getStackInSlot(i).copy());
        }
        return list;
    }

    /** 查找第一个空槽；没有空槽返回 -1。 */
    public int firstEmptySlot() {
        for (int i = 0; i < SLOTS; i++) {
            if (getStackInSlot(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 背上（主背包）显示第几个槽的背包。
     * 玩家在标签页里选中哪个背包，哪个就是主背包；没选过或选中格已经空了，
     * 就自动取第一个有背包的格子（和标签页打开时默认选中的那个一致）。
     */
    public int getMainSlot() {
        if (mainSlot >= 0 && mainSlot < SLOTS && !getStackInSlot(mainSlot).isEmpty()) {
            return mainSlot;
        }
        for (int i = 0; i < SLOTS; i++) {
            if (!getStackInSlot(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 存档里原样记着的主背包下标（可能是 -1 或已经空掉的格子），网络同步用。 */
    public int rawMainSlot() {
        return mainSlot;
    }

    /** 主背包换成第 slot 个槽：写存档并立刻同步给看得见的客户端（背上外观跟着变）。 */
    public void setMainSlot(int slot) {
        if (slot < -1 || slot >= SLOTS || this.mainSlot == slot) {
            return;
        }
        this.mainSlot = slot;
        saveToEntity();
        if (owner != null && !owner.level().isClientSide) {
            ModNetwork.syncMaidBackpack(owner, this);
        }
    }

    /** 最靠近背部的展示包：就是玩家选中的主背包，没选过则退回第一个有包的槽。 */
    public ItemStack topBackpack() {
        int slot = getMainSlot();
        return slot >= 0 ? getStackInSlot(slot) : ItemStack.EMPTY;
    }

    /** 把背上那个背包从槽里取出（剪刀卸包时还给玩家），并触发同步。 */
    public ItemStack popTopBackpack() {
        int slot = getMainSlot();
        if (slot >= 0) {
            return extractItem(slot, 1, false);
        }
        for (int i = SLOTS - 1; i >= 0; i--) {
            if (!getStackInSlot(i).isEmpty()) {
                return extractItem(i, 1, false);
            }
        }
        return ItemStack.EMPTY;
    }

    // ---------- 主背包显示开关 ----------

    public boolean isShowSophisticated() {
        return showSophisticated;
    }

    /** 服务器侧切换：写存档并同步给附近所有客户端（背上的外观要立刻变）。 */
    public void setShowSophisticated(boolean value) {
        if (this.showSophisticated == value) {
            return;
        }
        this.showSophisticated = value;
        saveToEntity();
        if (owner != null && !owner.level().isClientSide) {
            ModNetwork.syncMaidBackpack(owner, this);
        }
    }

    public void toggleShowSophisticated() {
        setShowSophisticated(!showSophisticated);
    }

    // NeoForge 1.21.1：序列化接口改为需要 HolderLookup.Provider（物品组件解析用）。
    // 存档键与 NBT 结构在 1.21.1 各版本之间不变，互相兼容；
    // 但 1.20.1 的存档不保证能迁过来：那边物品 NBT 是 id/Count/tag，1.21.1 按
    // id/count/components 解析，旧 tag 里精妙背包的 contentsUuid、升级数据会读不到，
    // 表现是升级后女仆背包"东西没了"（内容还留在背包自己的存档里，成了孤儿数据）。
    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = super.serializeNBT(provider);
        tag.putBoolean(TAG_SHOW_SOPHISTICATED, showSophisticated);
        tag.putInt(TAG_MAIN_SLOT, mainSlot);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        // 读档前把三格的堆对象引用记下来。精妙的原生界面菜单每 tick 拿"槽里这个堆的对象身份"
        // 做自查，而反序列化会把格子换成刚解析出的新对象——明明读的还是我们自己不久前写的
        // 同一份数据，内容一模一样，对象身份却"变了"，界面就这样被踢掉。
        // 所以解析完把内容等价的格子换回旧对象（保身份）；内容真的不同说明包确实被换过，
        // 让下面的统一检查去上报、让开着的界面体面关闭。
        ItemStack[] before = new ItemStack[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            before[i] = getStackInSlot(i);
        }
        // try/finally 保证标志必定复位：1.21 的组件解析比 1.20.1 更容易抛
        //（物品改名、组件类型不认识等），一旦异常把 applyingRemote 卡成 true，
        // 之后 onContentsChanged 就再也不写存档、不再同步了
        applyingRemote = true;
        try {
            super.deserializeNBT(provider, tag);
            showSophisticated = tag.getBoolean(TAG_SHOW_SOPHISTICATED);
            mainSlot = tag.contains(TAG_MAIN_SLOT) ? tag.getInt(TAG_MAIN_SLOT) : -1;
            for (int i = 0; i < SLOTS; i++) {
                ItemStack after = getStackInSlot(i);
                if (after != before[i] && !after.isEmpty() && !before[i].isEmpty()
                        && after.getCount() == before[i].getCount()
                        && ItemStack.isSameItemSameComponents(after, before[i])) {
                    // 走 super 的写回：不碰本类加在 setStackInSlot 之后的那些钩子。
                    // 旧对象继续用，省掉一次"身份变了"的假报警，也保住缓存里那套背包包装。
                    super.setStackInSlot(i, before[i]);
                }
            }
        } finally {
            applyingRemote = false;
        }
        // 三格统一复查：换了对象的（包括被清空的）在这里上报；等价换回的已经指回旧对象，不会报
        for (int i = 0; i < SLOTS; i++) {
            reportIdentityChangeIfNeeded(i, "读档反序列化");
        }
    }

    /**
     * "空包状态是否已经同步过"。只在服务端有意义：
     * 每次对外广播背包状态（{@link ModNetwork#syncMaidBackpack} 等）后，
     * 由发包处按"这次发出去的是不是全空"调用本方法记账。
     */
    public void markEmptyStateSynced(boolean nowEmpty) {
        this.emptyStateSynced = nowEmpty;
    }

    public boolean isEmptyStateSynced() {
        return emptyStateSynced;
    }

    @Override
    protected void onContentsChanged(int slot) {
        // 这三格里的物品堆可能换了对象（换包 / 卸包 / 读档 / 收同步包），
        // 女仆"可用物品栏"那份拼接结果缓存必须立刻作废，否则下一次读还是旧背包对象。
        // 注意要写在 applyingRemote 提前返回之前：收网络包、反序列化同样改了这几格。
        if (MaidSophisticated.touhouLittleMaidLoaded()) {
            MaidBackpackInv.invalidate(owner);
        }
        // 同一位置再检查一遍"堆对象身份有没有被换掉"，服务端有人开着原生界面时提前收尾。
        // 同样必须在 applyingRemote 短路之前跑：读档路径（applyingRemote=true）也会换对象。
        reportIdentityChangeIfNeeded(slot, "物品栏改动");
        if (applyingRemote) {
            return;
        }
        saveToEntity();
        if (owner != null && !owner.level().isClientSide) {
            ModNetwork.syncMaidBackpack(owner, this);
        }
    }

    /**
     * 每个槽最近一次"见过的"背包堆对象（比的是引用身份，不是内容）。
     * null 表示这个槽还没被检查过——实例刚建、刚读档的那次不算"被换过"，静默记账。
     */
    private final ItemStack[] lastSeenStacks = new ItemStack[SLOTS];

    /**
     * 检查并上报"槽里的堆对象被换掉"（只认对象身份，不比内容；内容等价与否由调用方处理）。
     *
     * <p>只在服务端上报：客户端收同步包（{@link #loadRemote}）本来就整批换对象，属设计如此；
     * 服务端换对象才意味着精妙原生界面的每-tick 身份自查会被踢（详见
     * {@link SbCompat#onServerBackpackStackReplaced}）。上报点刻意放在这里而不是逐个重写
     * setStackInSlot/insertItem/extractItem——NeoForge 的 ItemStackHandler 所有改动这几格的
     * 路径最后都会调 onContentsChanged，一处挂钩全覆盖。</p>
     */
    private void reportIdentityChangeIfNeeded(int slot, String via) {
        if (slot < 0 || slot >= SLOTS) {
            return;
        }
        ItemStack current = getStackInSlot(slot);
        ItemStack previous = lastSeenStacks[slot];
        lastSeenStacks[slot] = current;
        if (previous == null || previous == current) {
            return;
        }
        if (owner == null || owner.level() == null || owner.level().isClientSide) {
            return;
        }
        if (!MaidSophisticated.sophisticatedBackpacksLoaded()) {
            return;
        }
        SbCompat.onServerBackpackStackReplaced(owner, slot, via);
    }

    /** 客户端收到同步包时调用：写入槽位、开关与主背包下标，但不回发同步。 */
    public void loadRemote(List<ItemStack> stacks, boolean showSophisticated, int mainSlot) {
        // 防线（正常不会命中）：这是"客户端收到同步包"的写入口，owner 必须是客户端实体。
        // 单机里客户端和服务端同 id，一旦能力缓存按 id 串了表，这里就会在渲染线程上
        // 改服务端数据、并顺手把正开着的原生界面关掉（r13 那份日志就是这么来的）。
        // 现在 MaidCaps 已经按 side + 身份分表，这里再兜一层：写错侧就整包丢弃并记一次日志。
        if (owner != null && owner.level() != null && !owner.level().isClientSide) {
            if (REMOTE_ON_SERVER_LOGGED.compareAndSet(false, true)) {
                MaidSophisticated.LOGGER.error(
                        "客户端背包同步包落到了服务端女仆的数据上（这次已丢弃，不会写坏存档）。"
                                + "请把这条日志连同 latest.log 反馈给作者：说明能力缓存又把两侧串在一起了。");
            }
            return;
        }
        applyingRemote = true;
        try {
            for (int i = 0; i < SLOTS; i++) {
                // 服务端少发了格子（旧版本包 / 异常情况）就清空，别留着上一只女仆的残影
                setStackInSlot(i, i < stacks.size() ? stacks.get(i) : ItemStack.EMPTY);
            }
            this.showSophisticated = showSophisticated;
            // 网络来的下标同样夹一下，别让 -99 或 99 这种值影响后面的判断
            this.mainSlot = Math.max(-1, Math.min(mainSlot, SLOTS - 1));
        } finally {
            applyingRemote = false;
        }
    }

    public void loadFromEntity(CompoundTag tag) {
        if (owner != null) {
            deserializeNBT(owner.registryAccess(), tag);
        }
    }

    /**
     * 把当前数据（三个背包物品连同它们的最新 NBT）写进实体持久数据。
     *
     * <p>只在服务端写：客户端改的是本地副本，写进去也不会被保存，反而可能覆盖真实数据。</p>
     *
     * <p>防崩约定（与 {@link #readFromEntity()} 对称）：1.21 的组件编码同样可能抛
     *（别的模组塞进来的异常组件、版本漂移抛 {@code NoSuchMethodError}）。调用方横跨
     * 容器点击、{@code LevelEvent.Save}、关服事件和定时 tick——异常冒出去轻则这次操作失败，
     * 重则打断整级存档的保存流程。所以在这里兜住：序列化在 put 之前完成，
     * 抛了就等于"本次不写"，存档里保留上一次的旧数据，绝不会写坏，也不抛给调用方。</p>
     */
    public void saveToEntity() {
        if (owner == null || owner.level() == null || owner.level().isClientSide) {
            return;
        }
        try {
            owner.getPersistentData().put(SAVE_TAG, serializeNBT(owner.registryAccess()));
        } catch (Exception | LinkageError e) {
            if (SAVE_FAILED_LOGGED.compareAndSet(false, true)) {
                MaidSophisticated.LOGGER.warn(
                        "写女仆精妙背包存档失败，本次保留存档里的旧数据（后续同类错误不再重复打日志）：", e);
            }
        }
    }

    /**
     * 从实体持久数据读回背包。
     *
     * <p>防崩约定（与 {@link MaidBackpackInv} 一致）：1.21 的组件解析可能因存档损坏、
     * 组件格式漂移直接抛异常，甚至因版本漂移抛 {@code NoSuchMethodError}。这条链一旦冒出去，
     * 会顺着 {@link MaidCaps#getOrCreate} → {@link #of} 炸穿 {@code Events.onEntityJoin}，
     * 表现是女仆进世界即崩。所以在这里兜住：解析失败就退回空背包并记一次日志，
     * 绝不把异常抛给调用方（{@code OutOfMemoryError} 这类 JVM 致命错误不在捕获范围）。</p>
     */
    public void readFromEntity() {
        CompoundTag data = owner.getPersistentData();
        if (!data.contains(SAVE_TAG)) {
            return;
        }
        try {
            loadFromEntity(data.getCompound(SAVE_TAG));
        } catch (Exception | LinkageError e) {
            // 反序列化可能只写进去一半就抛了：把残留的半截内容清干净再当空包用，
            // 走 applyingRemote 静默清，免得中途状态被当成"本地改动"写回存档
            clearSlotsQuietly();
            if (READ_FAILED_LOGGED.compareAndSet(false, true)) {
                MaidSophisticated.LOGGER.warn(
                        "解析女仆精妙背包存档失败，本次按空背包处理（后续同类错误不再重复打日志）："
                                + "若是从 1.20.1 直升的存档，属已知的物品组件格式差异。", e);
            }
        }
    }

    /** 静默清空 3 个槽（不写档、不发同步），供读档失败回退用。 */
    private void clearSlotsQuietly() {
        applyingRemote = true;
        try {
            for (int i = 0; i < SLOTS; i++) {
                if (!getStackInSlot(i).isEmpty()) {
                    setStackInSlot(i, ItemStack.EMPTY);
                }
            }
        } catch (Exception | LinkageError ignored) {
            // 清不干净也不能再抛：调用方正是从读档异常里recover，宁可留着空槽状态
        } finally {
            applyingRemote = false;
        }
    }

    /** 清空槽位并从实体上摘掉数据（女仆死亡掉包时用）；开关和主背包下标一起复位，免得下次复用旧值。 */
    public void clearAndDetach() {
        applyingRemote = true;
        try {
            for (int i = 0; i < SLOTS; i++) {
                setStackInSlot(i, ItemStack.EMPTY);
            }
        } finally {
            applyingRemote = false;
        }
        showSophisticated = false;
        mainSlot = -1;
        // 包没了，"空状态已同步"要重新记：换女仆 / 复用实例时别拿着旧账跳过首次广播
        emptyStateSynced = false;
        if (owner != null) {
            owner.getPersistentData().remove(SAVE_TAG);
        }
    }
}
