package dev.barryalen.maidsophisticated.gui;

import dev.barryalen.maidsophisticated.Events;
import dev.barryalen.maidsophisticated.MaidBackpackData;
import dev.barryalen.maidsophisticated.MaidSophisticated;
import dev.barryalen.maidsophisticated.ModMenus;
import dev.barryalen.maidsophisticated.ModNetwork;
import dev.barryalen.maidsophisticated.SbCompat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.SlotItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 女仆 GUI"精妙背包"标签页的容器。
 *
 * 不继承车万女仆的 AbstractMaidContainer（发布版 jar 是 SRG 名，直接继承会编不过），
 * 自己按同一套坐标排槽：玩家背包在下，三个专属背包槽在上，
 * 中间最上面一行是工具栏（搜索 / 整理 / 排序，和精妙背包自己的界面同一套功能），
 * 工具栏下面是 8 列 × 5 行的内容网格，带滚动条。
 */
public class MaidSophTabContainer extends AbstractContainerMenu {
    /** 内容网格：8 列 × 5 行（最上面那一行让给工具栏）。 */
    public static final int COLS = 8;
    public static final int ROWS = 5;
    public static final int VISIBLE = COLS * ROWS;
    public static final int PLAYER_INV_SIZE = 36;
    public static final int BP_SLOT_BASE = PLAYER_INV_SIZE;
    public static final int CONTENT_BASE = BP_SLOT_BASE + MaidBackpackData.SLOTS;
    /** 网格起始 x（与玩家物品栏左缘对齐）与五行的 y（行距 18，无空行）。 */
    public static final int GRID_X = 88;
    public static final int GRID_Y = 72;
    public static final int[] ROW_Y = {72, 90, 108, 126, 144};
    /** 工具栏（搜索框 + 三个按钮）所在的那一条。 */
    public static final int TOOLBAR_X = 86;
    public static final int TOOLBAR_Y = 52;
    public static final int TOOLBAR_W = 146;
    public static final int TOOLBAR_H = 18;
    /** 顶部三个背包槽（与内容网格的列对齐）。 */
    public static final int BP_X = 143;
    public static final int BP_Y = 35;
    /** 搜索词最长长度。 */
    private static final int MAX_SEARCH = 32;

    /** 排序方式：名称 / 数量 / 分类（同模组的物品排在一起）。 */
    public enum SortMode {
        NAME, COUNT, CATEGORY;

        public SortMode next() {
            SortMode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    private final Inventory viewerInventory;
    @Nullable
    private final LivingEntity maid;
    @Nullable
    private final MaidBackpackData data;
    private final DataSlot selected = DataSlot.standalone();
    private final DataSlot scroll = DataSlot.standalone();
    /** 主背包显示开关：0 = 女仆自己的背包，1 = 精妙背包。 */
    private final DataSlot showSophisticated = DataSlot.standalone();
    /** 排序方式（供界面画按钮图标）。 */
    private final DataSlot sortMode = DataSlot.standalone();
    /** 当前窗口里一共有多少格（搜索时为匹配数），供滚动条换算。 */
    private final DataSlot contentSize = DataSlot.standalone();
    /** 搜索词（只存在服务端；客户端自己留着输入框里的文本）。 */
    private String search = "";
    /**
     * 没搜索时的窗口映射缓存，见 {@link #windowIndexes()}。
     * 只跟格数有关：格数一变就重建，跟包里装了什么都无关，所以永远不会过期变脏。
     */
    @Nullable
    private List<Integer> windowCache;
    private int windowCacheSlots = -1;
    /** 上一帧内容指纹：背包被升级、漏斗、拾取这类外部途径改动时，靠它发现并重推整屏。 */
    private int lastContentHash;
    /**
     * 搜索 / 整理的服务端节流：一次整理是整个背包 O(n²) 并逐格写回，
     * 连点或改过的客户端能把这条路径打成高频消耗。同一窗口两次重活之间
     * 至少隔 4 tick（约 200ms），搜索至少隔 2 tick（约 100ms）。
     *
     * 被节流挡掉的需求不丢弃：各记一条"最新的一次"（pendingSearch / pendingSort），
     * 冷却窗口一过就在 broadcastChanges 里补做。挡掉一次搜索比省下的开销贵得多——
     * 尤其"关掉搜索"发的空串被吞后，服务端会永远停在过滤状态（内容指纹没变就不会重推）。
     */
    private static final long SORT_COOLDOWN_TICKS = 4L;
    private static final long SEARCH_COOLDOWN_TICKS = 2L;
    private long lastSortTick = Long.MIN_VALUE / 4;
    private long lastSearchTick = Long.MIN_VALUE / 4;
    /** 冷却窗口内收到的搜索词：合并语义，只留最新一条，等窗口过期补发。 */
    @Nullable
    private String pendingSearch;
    /** 冷却窗口内收到过、还没落地的整理请求（方式本身已经记进 sortMode）。 */
    private boolean pendingSort;

    public MaidSophTabContainer(int windowId, Inventory inventory, FriendlyByteBuf buf) {
        this(windowId, inventory, buf.readInt());
    }

    public MaidSophTabContainer(int windowId, Inventory inventory, int entityId) {
        super(ModMenus.MAID_SOPH_TAB == null ? null : ModMenus.MAID_SOPH_TAB.get(), windowId);
        this.viewerInventory = inventory;
        LivingEntity resolved = inventory.player.level().getEntity(entityId) instanceof LivingEntity living
                ? living : null;
        this.maid = resolved != null && Events.isMaid(resolved) ? resolved : null;
        this.data = this.maid != null
                ? MaidBackpackData.of(this.maid).orElseGet(() -> {
                    // capability 极端情况下还没挂上（实体刚生成的一瞬间）：临时对象先读一份实体存档，
                    // 不能拿一个全空的对象去接操作，否则一写就把她原来的背包数据覆盖没了。
                    MaidBackpackData fallback = new MaidBackpackData(this.maid);
                    fallback.readFromEntity();
                    return fallback;
                })
                : null;
        addDataSlot(selected);
        addDataSlot(scroll);
        addDataSlot(showSophisticated);
        addDataSlot(sortMode);
        addDataSlot(contentSize);
        addPlayerInv(inventory);
        if (this.data == null) {
            return;
        }
        // 打开时直接停在玩家上次选的主背包上（背部渲染用的也是这个下标）
        selected.set(Math.max(0, this.data.getMainSlot()));
        showSophisticated.set(this.data.isShowSophisticated() ? 1 : 0);
        sortMode.set(SortMode.NAME.ordinal());
        for (int i = 0; i < MaidBackpackData.SLOTS; i++) {
            addSlot(new MaidBackpackSlot(this, data, i, BP_X + 18 * i, BP_Y));
        }
        for (int k = 0; k < VISIBLE; k++) {
            addSlot(new BackpackContentSlot(this, k, GRID_X + (k % COLS) * 18, ROW_Y[k / COLS]));
        }
        contentSize.set(windowIndexes().size());
    }

    private void addPlayerInv(Inventory playerInventory) {
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 88 + col * 18, 174 + row * 18));
            }
        }
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInventory, col, 88 + col * 18, 232));
        }
    }

    @Nullable
    public LivingEntity getMaid() {
        return maid;
    }

    public MaidBackpackData backpackData() {
        return data;
    }

    public int selectedSlot() {
        return selected.get();
    }

    public int scrollRow() {
        return scroll.get();
    }

    /** 当前"主背包显示"是不是精妙背包（0/1 由服务端权威同步）。 */
    public boolean showSophisticated() {
        return showSophisticated.get() != 0;
    }

    public SortMode sortMode() {
        int index = Math.max(0, Math.min(sortMode.get(), SortMode.values().length - 1));
        return SortMode.values()[index];
    }

    public ItemStack currentBackpack() {
        int index = selected.get();
        return data == null || index < 0 || index >= MaidBackpackData.SLOTS
                ? ItemStack.EMPTY : data.getStackInSlot(index);
    }

    public boolean hasAnyBackpack() {
        if (data == null) {
            return false;
        }
        for (int i = 0; i < MaidBackpackData.SLOTS; i++) {
            if (!data.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public int currentContentSize() {
        return contentSize.get();
    }

    public int maxScrollRow() {
        int rows = (currentContentSize() + COLS - 1) / COLS;
        return Math.max(0, rows - ROWS);
    }

    // ---------- 搜索 ----------

    public boolean isSearching() {
        return !search.isEmpty();
    }

    /**
     * 当前窗口里的每一格对应背包内部第几格。
     * 没搜索时就是按顺序铺满（保留背包自己的格子布局）；搜索时只留匹配的物品、从前往后紧凑排。
     *
     * <p><b>返回的是只读列表，调用方不许改。</b>没搜索时结果会被复用：这方法被
     * {@code BackpackContentSlot} 的每一格各调一次（{@code realIndexForSlot} → 这里），
     * 一次整屏刷新就是四五十遍全背包扫描，不缓存的话每 tick 都有几十个 List 和上千次格子读取。</p>
     *
     * <p>只缓存"没搜索"这一种情况，因为它的答案只跟格数有关（就是 0,1,2,…,slots-1），
     * 跟包里装了什么、这一 tick 变了什么都无关，永远不会脏。搜索开着时结果依赖物品内容，
     * 整理/拾取/别的模组改包都会让旧答案失效，所以一律现算——搜索是低频操作，
     * 多算几次也不心疼。</p>
     */
    public List<Integer> windowIndexes() {
        if (!MaidSophisticated.sophisticatedBackpacksLoaded()) {
            return Collections.emptyList();
        }
        IItemHandlerModifiable handler = SbCompat.guiInventoryHandler(currentBackpack()).orElse(null);
        if (handler == null) {
            return Collections.emptyList();
        }
        int slots = handler.getSlots();
        if (search.isEmpty()) {
            List<Integer> cached = windowCache;
            if (cached != null && windowCacheSlots == slots) {
                return cached;
            }
            List<Integer> out = new ArrayList<>(slots);
            for (int i = 0; i < slots; i++) {
                out.add(i);
            }
            List<Integer> result = Collections.unmodifiableList(out);
            windowCache = result;
            windowCacheSlots = slots;
            return result;
        }
        List<Integer> out = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty() && matchesSearch(stack)) {
                out.add(i);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** 界面上第 local 个格子对应背包内部下标的第几格；无效返回 -1。 */
    public int realIndexForSlot(int local) {
        List<Integer> window = windowIndexes();
        int index = scroll.get() * COLS + local;
        return index >= 0 && index < window.size() ? window.get(index) : -1;
    }

    private boolean matchesSearch(ItemStack stack) {
        if (search.isEmpty()) {
            return true;
        }
        if (stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(search)) {
            return true;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) {
            return false;
        }
        return key.toString().toLowerCase(Locale.ROOT).contains(search)
                || key.getPath().toLowerCase(Locale.ROOT).contains(search.replace(' ', '_'));
    }

    /**
     * 服务端：设置搜索词并重推这一屏。
     *
     * 带 100ms 节流（防连点 / 高频改词打满全包扫描），但节流窗口内的请求不是丢掉而是记账：
     * 只留最新一条（更早的那几条本来就被这一条覆盖了），窗口一过由 broadcastChanges 补做。
     * 最容易出事的是"关掉搜索"——客户端清输入框后发的是一条空串，这条一旦被吞，
     * 界面就停在过滤状态，而且服务端的内容刷新靠"内容指纹变了才重推"，永远不自愈。
     */
    public void serverSearch(String text) {
        if (data == null || maid == null) {
            return;
        }
        String cleaned = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (cleaned.length() > MAX_SEARCH) {
            cleaned = cleaned.substring(0, MAX_SEARCH);
        }
        // 新请求总把旧的待补发值作废（"只留最新一条"），不然会出现：
        // 窗口内攒了一条旧词 → 窗口一过新词直接落地 → 旧 pending 又晚一 tick 把新词顶回去
        pendingSearch = null;
        long now = maid.level().getGameTime();
        if (now - lastSearchTick < SEARCH_COOLDOWN_TICKS) {
            if (!cleaned.equals(search)) {
                pendingSearch = cleaned;
            }
            return;
        }
        applySearch(cleaned, now);
    }

    /** 真正落地搜索词：和旧行为一致——记冷却时间、清滚动位、整屏重推。 */
    private void applySearch(String cleaned, long now) {
        if (cleaned.equals(search)) {
            return;
        }
        lastSearchTick = now;
        search = cleaned;
        scroll.set(0);
        sendContentToViewer();
    }

    // ---------- 整理 / 排序 ----------

    /**
     * 服务端：换排序方式并立刻整理一次。
     *
     * 整理本身有 200ms 节流（防连点把 O(n²) 的合并写成刷屏消耗），
     * 但排序方式照常立刻记下：被节流掉的那次点不会重排，只记一条"待整理"，
     * 冷却窗口一过由 broadcastChanges 按"当时最新的方式"补做一次——
     * 不然连点后最后选中的方式图标和包内实际顺序会对不上，而且永远不会自愈。
     */
    public void serverSort(int mode) {
        if (data == null || maid == null) {
            return;
        }
        int count = SortMode.values().length;
        sortMode.set(Math.floorMod(mode, count));
        long now = maid.level().getGameTime();
        if (now - lastSortTick < SORT_COOLDOWN_TICKS) {
            pendingSort = true;
            return;
        }
        lastSortTick = now;
        pendingSort = false;
        sortCurrentBackpack(sortMode());
        sendContentToViewer();
    }

    /** 客户端点"整理 / 排序"：先本地记下方式（手感不延迟），再请服务端真的整理。 */
    public void clientSort(int mode) {
        if (maid == null || !maid.level().isClientSide) {
            return;
        }
        int count = SortMode.values().length;
        sortMode.set(Math.floorMod(mode, count));
        ModNetwork.sendSort(sortMode.get());
    }

    /** 把当前背包里的物品合并同类项、按排序方式重新排好、往前紧凑堆放。 */
    private void sortCurrentBackpack(SortMode mode) {
        if (!MaidSophisticated.sophisticatedBackpacksLoaded()) {
            return;
        }
        IItemHandlerModifiable handler = SbCompat.guiInventoryHandler(currentBackpack()).orElse(null);
        if (handler == null) {
            return;
        }
        List<ItemStack> merged = new ArrayList<>();
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            handler.setStackInSlot(i, ItemStack.EMPTY);
            ItemStack remaining = stack.copy();
            for (ItemStack existing : merged) {
                if (remaining.isEmpty()) {
                    break;
                }
                if (ItemStack.isSameItemSameTags(existing, remaining)) {
                    int space = Math.min(existing.getMaxStackSize(), remaining.getMaxStackSize()) - existing.getCount();
                    if (space > 0) {
                        int move = Math.min(space, remaining.getCount());
                        existing.grow(move);
                        remaining.shrink(move);
                    }
                }
            }
            if (!remaining.isEmpty()) {
                merged.add(remaining);
            }
        }
        merged.sort(comparator(mode));
        int index = 0;
        List<ItemStack> overflow = new ArrayList<>();
        for (ItemStack stack : merged) {
            int left = stack.getCount();
            while (left > 0 && index < handler.getSlots()) {
                int limit = Math.min(handler.getSlotLimit(index), stack.getMaxStackSize());
                if (limit <= 0) {
                    index++;
                    continue;
                }
                int put = Math.min(limit, left);
                ItemStack piece = stack.copy();
                piece.setCount(put);
                handler.setStackInSlot(index, piece);
                left -= put;
                index++;
            }
            if (left > 0) {
                // 槽位不够（背包被升级限制 / NBT 异常）也绝不能把物品吞掉
                ItemStack rest = stack.copy();
                rest.setCount(left);
                overflow.add(rest);
            }
        }
        for (ItemStack rest : overflow) {
            ItemStack left = rest;
            for (int i = 0; i < handler.getSlots() && !left.isEmpty(); i++) {
                left = handler.insertItem(i, left, false);
            }
            if (!left.isEmpty() && maid != null && !maid.level().isClientSide) {
                net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(
                        maid.level(), maid.getX(), maid.getY() + 0.5, maid.getZ(), left);
                drop.setDefaultPickUpDelay();
                maid.level().addFreshEntity(drop);
            }
        }
    }

    private Comparator<ItemStack> comparator(SortMode mode) {
        Comparator<ItemStack> byName = Comparator.comparing(
                stack -> stack.getHoverName().getString(), String.CASE_INSENSITIVE_ORDER);
        Comparator<ItemStack> byId = Comparator.comparing(stack -> {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            return key == null ? "" : key.toString();
        });
        return switch (mode) {
            case NAME -> byName.thenComparing(byId);
            case COUNT -> Comparator.<ItemStack>comparingInt(ItemStack::getCount).reversed().thenComparing(byName);
            case CATEGORY -> byId.thenComparing(byName);
        };
    }

    // ---------- 背包槽切换 ----------

    public int firstNonEmptySlot() {
        if (data == null) {
            return -1;
        }
        for (int i = 0; i < MaidBackpackData.SLOTS; i++) {
            if (!data.getStackInSlot(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    public int nextNonEmptySlot(int dir) {
        int cur = selected.get();
        if (data == null) {
            return cur;
        }
        for (int step = 1; step <= MaidBackpackData.SLOTS; step++) {
            int i = Math.floorMod(cur + dir * step, MaidBackpackData.SLOTS);
            if (!data.getStackInSlot(i).isEmpty()) {
                return i;
            }
        }
        return cur;
    }

    /**
     * 服务端：切换当前展示的背包。
     * 这里顺便把它记成"主背包"——玩家在标签页里选中的那个，就是女仆背上要显示的那个，
     * 选中后立刻同步给所有看得见她的客户端，背上的外观当场就换。
     */
    public void serverSelect(int index) {
        // 只校验下标：点击已经不会把物品拿走了，再要求"格子当时非空"，
        // 赶上"shift 取下包 → 立刻点回原格""别的玩家同时把包拿走"这类时序，玩家的选择就被白白丢掉。
        // 选到空格也不慌：显示层 broadcastChanges 会临时纠偏，等包装回原格再自动切回去。
        if (index < 0 || index >= MaidBackpackData.SLOTS || data == null) {
            return;
        }
        if (selected.get() == index && data.rawMainSlot() == index) {
            // 重复点同一个已生效的背包槽：整屏内容没变，不重推
            // （既省包，也堵住改过的客户端用几字节的选择包换 40 格物品包的上行放大）
            return;
        }
        selected.set(index);
        scroll.set(Math.min(scroll.get(), maxScrollRow()));
        data.setMainSlot(index);
        sendContentToViewer();
    }

    public void serverScroll(int row) {
        int clamped = Math.max(0, Math.min(row, maxScrollRow()));
        if (clamped == scroll.get()) {
            // 行号没变：同样不重推整屏（内容变化由 broadcastChanges 的指纹负责）
            return;
        }
        scroll.set(clamped);
        sendContentToViewer();
    }

    /**
     * 客户端收到服务端整屏内容后写入显示槽。
     * 滚动/切包/搜索时原版只同步"变化"的槽位，映射一变就会缺格，所以这里整屏覆盖。
     */
    public void applyContentSync(int sel, int row, List<ItemStack> stacks, int total) {
        if (maid == null || !maid.level().isClientSide) {
            return;
        }
        // 包里的数值一律夹到合法范围：即使服务端/别的模组发来异常值，也只影响显示，不能把界面搞崩
        selected.set(Math.max(0, Math.min(sel, MaidBackpackData.SLOTS - 1)));
        scroll.set(Math.max(0, Math.min(row, ModNetwork.MAX_SCROLL_ROW)));
        contentSize.set(Math.max(0, total));
        for (int k = 0; k < VISIBLE; k++) {
            int slotIndex = CONTENT_BASE + k;
            // 容器没建全（女仆数据缺失等极端情况）时别越界，界面照样能正常关掉
            if (slotIndex >= this.slots.size()) {
                break;
            }
            Slot slot = getSlot(slotIndex);
            if (slot instanceof BackpackContentSlot contentSlot) {
                contentSlot.set(k < stacks.size() ? stacks.get(k) : ItemStack.EMPTY);
            }
        }
    }

    /** 把"当前选中背包 + 当前搜索 + 当前滚动行"可见的整屏内容发给正在看这个界面的玩家。 */
    public void sendContentToViewer() {
        if (data == null || !MaidSophisticated.sophisticatedBackpacksLoaded()
                || !(viewerInventory.player instanceof ServerPlayer player)) {
            return;
        }
        List<Integer> window = windowIndexes();
        int maxScroll = Math.max(0, (window.size() + COLS - 1) / COLS - ROWS);
        if (scroll.get() > maxScroll) {
            scroll.set(maxScroll);
        }
        contentSize.set(window.size());
        IItemHandlerModifiable handler = SbCompat.guiInventoryHandler(currentBackpack()).orElse(null);
        int base = scroll.get() * COLS;
        List<ItemStack> stacks = new ArrayList<>(VISIBLE);
        for (int k = 0; k < VISIBLE; k++) {
            int index = base + k;
            stacks.add(handler != null && index >= 0 && index < window.size()
                    ? handler.getStackInSlot(window.get(index)).copy() : ItemStack.EMPTY);
        }
        ModNetwork.sendBackpackContent(player, this.containerId, selected.get(), scroll.get(), stacks,
                window.size());
    }

    public void clientSelect(int index) {
        if (maid != null && maid.level().isClientSide) {
            selected.set(index);
            ModNetwork.sendSelect(index);
        }
    }

    /** 客户端点"主背包显示"开关：先本地翻页（手感不延迟），再请服务端落存档。 */
    public void clientToggleShowSophisticated() {
        if (maid == null || !maid.level().isClientSide) {
            return;
        }
        showSophisticated.set(showSophisticated.get() == 0 ? 1 : 0);
        ModNetwork.sendToggleMainBackpack();
    }

    /** 服务端切换并同步给所有看得见这个女仆的客户端。 */
    public void serverToggleShowSophisticated() {
        if (data == null) {
            return;
        }
        data.toggleShowSophisticated();
        showSophisticated.set(data.isShowSophisticated() ? 1 : 0);
    }

    public void clientScroll(int row) {
        if (maid == null || !maid.level().isClientSide) {
            return;
        }
        int clamped = Math.max(0, Math.min(row, maxScrollRow()));
        if (clamped == scroll.get()) {
            return;
        }
        scroll.set(clamped);
        // 不清空旧显示：服务端会立刻整屏推送，清空反而会闪空白
        ModNetwork.sendScroll(clamped);
    }

    @Override
    public void broadcastChanges() {
        if (maid != null && data != null && !maid.level().isClientSide) {
            // 补做被节流挡掉的搜索 / 整理（各只留最新一条，见 serverSearch / serverSort）
            long now = maid.level().getGameTime();
            if (pendingSearch != null && now - lastSearchTick >= SEARCH_COOLDOWN_TICKS) {
                String next = pendingSearch;
                pendingSearch = null;
                applySearch(next, now);
            }
            if (pendingSort && now - lastSortTick >= SORT_COOLDOWN_TICKS) {
                pendingSort = false;
                lastSortTick = now;
                sortCurrentBackpack(sortMode());
                sendContentToViewer();
            }
            // DataSlot 理论上只会在 0~2；夹一下，别让异常值把服务端 tick 带崩
            int selectedIndex = Math.max(0, Math.min(selected.get(), MaidBackpackData.SLOTS - 1));
            int preferred = data.rawMainSlot();
            boolean preferredValid = preferred >= 0 && preferred < MaidBackpackData.SLOTS
                    && !data.getStackInSlot(preferred).isEmpty();
            if (data.getStackInSlot(selectedIndex).isEmpty()) {
                // 正在看的那一格被拿走了：界面先临时显示"玩家选的主背包"或第一个还有包的格子。
                // 注意这里不能 data.setMainSlot(next)——那会把玩家真正选中的主背包永久改写掉，
                // 等他把原来的包放回原格，背上也不会再切回去（"主背包自己变成铁制"就是这个原因）。
                int next = preferredValid ? preferred : firstNonEmptySlot();
                if (next >= 0 && next != selectedIndex) {
                    selected.set(next);
                    scroll.set(Math.min(scroll.get(), maxScrollRow()));
                    sendContentToViewer();
                }
            } else if (preferredValid && preferred != selectedIndex) {
                // 玩家选的主背包被取下后又装回来了：显示自动切回它，但存档里的 mainSlot 一直没动
                selected.set(preferred);
                scroll.set(Math.min(scroll.get(), maxScrollRow()));
                sendContentToViewer();
            }
            int max = maxScrollRow();
            if (scroll.get() > max) {
                scroll.set(max);
                sendContentToViewer();
            }
            showSophisticated.set(data.isShowSophisticated() ? 1 : 0);
            contentSize.set(windowIndexes().size());
            int hash = contentHash();
            if (hash != lastContentHash) {
                lastContentHash = hash;
                // 背包内容被外部改动（拾取升级、漏斗、别的界面）时也立刻刷新这一屏
                sendContentToViewer();
            }
        }
        super.broadcastChanges();
    }

    /** 当前选中背包内容 + 搜索词的快速指纹。 */
    private int contentHash() {
        int hash = search.hashCode() * 31 + scroll.get();
        if (!MaidSophisticated.sophisticatedBackpacksLoaded()) {
            return hash;
        }
        IItemHandlerModifiable handler = SbCompat.guiInventoryHandler(currentBackpack()).orElse(null);
        if (handler == null) {
            return hash;
        }
        hash = hash * 31 + handler.getSlots();
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty()) {
                hash = hash * 31 + i * 31 + stack.getItem().hashCode() * 31 + stack.getCount();
            }
        }
        return hash;
    }

    @Override
    public boolean stillValid(Player player) {
        if (maid == null || !maid.isAlive()) {
            return false;
        }
        // 女仆被传送去别的维度时，坐标可能还"很近"，必须比对维度，否则界面会一直开着
        return Events.isMaidOwner(maid, player) && player.level() == maid.level()
                && player.distanceToSqr(maid) < 64.0;
    }

    /**
     * 关闭界面时把背包物品的最新 NBT 写回实体持久数据。
     *
     * 精妙背包的内容物其实存在它自己的 SavedData 里，物品 NBT 只记一个 contentsUuid；
     * 而这个 uuid 是在第一次打开背包时懒生成的。我们实体上的背包数据是"快照"式存法，
     * 不主动刷新的话，世界保存用的还是背包刚装上去时那份没有 uuid 的旧 NBT，
     * 重进游戏就会生成一个新 uuid，看起来就是"背包里的东西全没了"。
     */
    @Override
    public void removed(Player player) {
        if (data != null && !player.level().isClientSide) {
            data.saveToEntity();
        }
        super.removed(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return result;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        if (index >= CONTENT_BASE) {
            // 背包内容格 → 玩家背包：绝不能学原版拿处理器返回的"活引用"直接 shrink——
            // 那会绕过精妙背包内容处理器的变更通知（容量台账不更新、不标脏不保存）。
            // 先整堆 extractItem 下来（走正规通道，通知齐全），再往玩家背包塞，
            // 塞不下的原路 setStackInSlot 塞回，全程没有活引用旁路。
            ItemStack extracted = slot.remove(result.getCount());
            if (extracted.isEmpty()) {
                return ItemStack.EMPTY;
            }
            boolean moved = moveItemStackTo(extracted, 0, PLAYER_INV_SIZE, true);
            if (!extracted.isEmpty()) {
                slot.set(extracted);
            }
            return moved ? result : ItemStack.EMPTY;
        }
        if (index >= BP_SLOT_BASE) {
            // 三个专属背包槽 → 玩家背包：同理，extract 与 set 都走 ItemStackHandler 正规通道
            ItemStack extracted = slot.remove(result.getCount());
            if (extracted.isEmpty()) {
                return ItemStack.EMPTY;
            }
            boolean moved = moveItemStackTo(extracted, 0, PLAYER_INV_SIZE, true);
            if (!extracted.isEmpty()) {
                slot.set(extracted);
            }
            return moved ? result : ItemStack.EMPTY;
        }
        // 玩家背包往上塞：源是玩家自己物品栏的活引用，改它本来就是原版 quickMove 的语义；
        // 目标格的 set() 已经会写回处理器（MaidBackpackSlot / BackpackContentSlot 都走正规通道）。
        boolean moved;
        moved = false;
        if (MaidBackpackData.isSophisticatedBackpack(stack) && stack.getCount() == 1) {
            moved = moveItemStackTo(stack, BP_SLOT_BASE, CONTENT_BASE, false);
        }
        if (!moved && MaidSophisticated.sophisticatedBackpacksLoaded()) {
            // 走不带防吞护栏的那份视图：这是玩家亲手按的 shift，行为要和精妙背包
            // 原生界面完全一致（装了虚空升级就照常销毁）。防吞护栏只管女仆自动拾取那条路。
            moved = SbCompat.insertAnywhereGui(currentBackpack(), stack);
            if (moved) {
                // 背包内部不是真的 Slot，原版差异同步要等下一 tick，
                // 这里主动整屏推一次，物品才会立刻出现在格子里。
                sendContentToViewer();
            }
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == result.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return result;
    }

    public static class MaidBackpackSlot extends SlotItemHandler {
        private final MaidSophTabContainer menu;
        /** 这只女仆的背包数据本身：结算"三个槽全满"成就时要看另外两个槽。 */
        private final MaidBackpackData handler;

        public MaidBackpackSlot(MaidSophTabContainer menu, MaidBackpackData handler, int index, int x, int y) {
            super(handler, index, x, y);
            this.menu = menu;
            this.handler = handler;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(@Nonnull ItemStack stack) {
            return 1;
        }

        @Override
        public void set(ItemStack stack) {
            boolean justEquipped = !stack.isEmpty() && MaidBackpackData.isSophisticatedBackpack(stack)
                    && getItem().isEmpty();
            super.set(stack);
            if (!menu.viewerInventory.player.level().isClientSide) {
                if (justEquipped) {
                    Events.creditFirstBackpack(menu.viewerInventory.player);
                }
                // 每格写入后都结算一次"三个槽全满"：成就只在首次达成时弹，重复结算无副作用
                Events.creditAllBackpacks(menu.viewerInventory.player, handler);
            }
        }

        @Override
        public void setByPlayer(ItemStack stack) {
            boolean justEquipped = !stack.isEmpty() && MaidBackpackData.isSophisticatedBackpack(stack)
                    && getItem().isEmpty();
            super.setByPlayer(stack);
            if (!menu.viewerInventory.player.level().isClientSide) {
                if (justEquipped) {
                    Events.creditFirstBackpack(menu.viewerInventory.player);
                }
                Events.creditAllBackpacks(menu.viewerInventory.player, handler);
            }
        }
    }

    public static class BackpackContentSlot extends Slot {
        private final MaidSophTabContainer menu;
        private final int local;
        private final boolean client;
        private ItemStack display = ItemStack.EMPTY;

        public BackpackContentSlot(MaidSophTabContainer menu, int local, int x, int y) {
            super(EmptyDummy.INSTANCE, local, x, y);
            this.menu = menu;
            this.local = local;
            this.client = menu.viewerInventory.player.level().isClientSide;
        }

        private int realIndex() {
            return menu.realIndexForSlot(local);
        }

        private IItemHandlerModifiable handler() {
            if (!MaidSophisticated.sophisticatedBackpacksLoaded()) {
                return null;
            }
            return SbCompat.guiInventoryHandler(menu.currentBackpack()).orElse(null);
        }

        @Override
        public ItemStack getItem() {
            if (client) {
                return display;
            }
            IItemHandlerModifiable h = handler();
            int ri = realIndex();
            return h != null && ri >= 0 ? h.getStackInSlot(ri) : ItemStack.EMPTY;
        }

        @Override
        public boolean hasItem() {
            return !getItem().isEmpty();
        }

        @Override
        public void set(ItemStack stack) {
            if (client) {
                display = stack;
                return;
            }
            IItemHandlerModifiable h = handler();
            int ri = realIndex();
            if (h != null && ri >= 0) {
                h.setStackInSlot(ri, stack);
            } else if (!stack.isEmpty() && menu.maid != null && !menu.maid.level().isClientSide) {
                // 极端情况（滚动竞态导致这一格突然没有真实下标）：把物品掉在她脚下，绝不静默吞掉
                net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(
                        menu.maid.level(), menu.maid.getX(), menu.maid.getY() + 0.5, menu.maid.getZ(), stack);
                drop.setDefaultPickUpDelay();
                menu.maid.level().addFreshEntity(drop);
            }
        }

        @Override
        public void setByPlayer(ItemStack stack) {
            set(stack);
        }

        @Override
        public ItemStack remove(int amount) {
            if (client) {
                return display.isEmpty() ? ItemStack.EMPTY : display.split(amount);
            }
            IItemHandlerModifiable h = handler();
            int ri = realIndex();
            return h != null && ri >= 0 ? h.extractItem(ri, amount, false) : ItemStack.EMPTY;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (client) {
                return false;
            }
            IItemHandlerModifiable h = handler();
            int ri = realIndex();
            return h != null && ri >= 0 && h.isItemValid(ri, stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            if (client) {
                return !display.isEmpty();
            }
            IItemHandlerModifiable h = handler();
            int ri = realIndex();
            return h != null && ri >= 0 && !h.extractItem(ri, 1, true).isEmpty();
        }

        @Override
        public int getMaxStackSize() {
            if (client) {
                return 64;
            }
            IItemHandlerModifiable h = handler();
            int ri = realIndex();
            return h != null && ri >= 0 ? h.getSlotLimit(ri) : 64;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return Math.min(getMaxStackSize(), stack.getMaxStackSize());
        }

        @Override
        public boolean isActive() {
            if (client) {
                // 客户端只负责画格子，真正有没有内容由服务端整屏推送决定
                return true;
            }
            return realIndex() >= 0;
        }

        @Override
        public int getContainerSlot() {
            return realIndex();
        }

        @Override
        public void setChanged() {
        }

        public void clearDisplay() {
            display = ItemStack.EMPTY;
        }
    }

    /** 占位容器：虚拟槽的 Slot 构造需要一个非 null Container，真正读写走背包处理器。 */
    static final class EmptyDummy implements net.minecraft.world.Container {
        static final EmptyDummy INSTANCE = new EmptyDummy();

        private EmptyDummy() {}

        @Override
        public int getContainerSize() {
            return 0;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
        }
    }
}
