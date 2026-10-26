package dev.barryalen.maidsophisticated;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        return Optional.ofNullable(living.getCapability(MaidCaps.MAID_BACKPACK).orElse(null));
    }

    /** 只按注册表判断，避免类加载依赖精妙背包本体。 */
    public static boolean isSophisticatedBackpack(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
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

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = super.serializeNBT();
        tag.putBoolean(TAG_SHOW_SOPHISTICATED, showSophisticated);
        tag.putInt(TAG_MAIN_SLOT, mainSlot);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        applyingRemote = true;
        super.deserializeNBT(tag);
        applyingRemote = false;
        showSophisticated = tag.getBoolean(TAG_SHOW_SOPHISTICATED);
        mainSlot = tag.contains(TAG_MAIN_SLOT) ? tag.getInt(TAG_MAIN_SLOT) : -1;
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
        if (applyingRemote) {
            return;
        }
        saveToEntity();
        if (owner != null && !owner.level().isClientSide) {
            ModNetwork.syncMaidBackpack(owner, this);
        }
    }

    /** 客户端收到同步包时调用：写入槽位、开关与主背包下标，但不回发同步。 */
    public void loadRemote(List<ItemStack> stacks, boolean showSophisticated, int mainSlot) {
        applyingRemote = true;
        for (int i = 0; i < SLOTS; i++) {
            // 服务端少发了格子（旧版本包 / 异常情况）就清空，别留着上一只女仆的残影
            setStackInSlot(i, i < stacks.size() ? stacks.get(i) : ItemStack.EMPTY);
        }
        applyingRemote = false;
        this.showSophisticated = showSophisticated;
        // 网络来的下标同样夹一下，别让 -99 或 99 这种值影响后面的判断
        this.mainSlot = Math.max(-1, Math.min(mainSlot, SLOTS - 1));
    }

    public void loadFromEntity(CompoundTag tag) {
        deserializeNBT(tag);
    }

    /**
     * 把当前数据（三个背包物品连同它们的最新 NBT）写进实体持久数据。
     *
     * 只在服务端写：客户端改的是本地副本，写进去也不会被保存，反而可能覆盖真实数据。
     */
    public void saveToEntity() {
        if (owner == null || owner.level() == null || owner.level().isClientSide) {
            return;
        }
        owner.getPersistentData().put(SAVE_TAG, serializeNBT());
    }

    public void readFromEntity() {
        CompoundTag data = owner.getPersistentData();
        if (data.contains(SAVE_TAG)) {
            loadFromEntity(data.getCompound(SAVE_TAG));
        }
    }

    /** 清空槽位并从实体上摘掉数据（女仆死亡掉包时用）；开关和主背包下标一起复位，免得下次复用旧值。 */
    public void clearAndDetach() {
        applyingRemote = true;
        for (int i = 0; i < SLOTS; i++) {
            setStackInSlot(i, ItemStack.EMPTY);
        }
        applyingRemote = false;
        showSophisticated = false;
        mainSlot = -1;
        // 包没了，"空状态已同步"要重新记：换女仆 / 复用实例时别拿着旧账跳过首次广播
        emptyStateSynced = false;
        if (owner != null) {
            owner.getPersistentData().remove(SAVE_TAG);
        }
    }
}
