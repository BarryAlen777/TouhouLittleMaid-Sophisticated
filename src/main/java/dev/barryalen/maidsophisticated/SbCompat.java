package dev.barryalen.maidsophisticated;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import net.p3pp3rf1y.sophisticatedcore.init.ModCoreDataComponents;
import net.p3pp3rf1y.sophisticatedcore.inventory.ITrackedContentsItemHandler;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IInsertResponseUpgrade;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 与精妙背包模组的全部耦合都集中在这个类。
 * 只有在确认 sophisticatedbackpacks 已安装时才会被类加载，避免 NoClassDefFoundError。
 *
 * <p>打开界面的方式与精妙背包自己（右键手持背包 / curios 槽点击）完全一致：
 * 构造 BackpackContext.Item(处理器名, 标识符, 槽位) 后走 openMenu，
 * 并用 {@code context.toBuffer(buffer, player)} 写菜单附加数据（格式必须和读取端配对，见
 * {@link #openBackpackGui}），
 * 因此物品页、升级页双页签、筛选、嵌套背包等所有原生功能直接可用。</p>
 *
 * <p><b>插入背包按调用者分两路：自动路径走 {@link #inventoryHandler(ItemStack)} 那层"防吞外壳"，
 * 玩家亲手操作的界面路径走 {@link #guiInventoryHandler(ItemStack)}（不带护栏）。</b>
 * 下面先说为什么自动路径必须套这一层。精妙背包对外有三个物品栏视图，我们都核过字节码：</p>
 * <ul>
 *   <li>{@code getInventoryHandler()} —— 背包本身的格子（外部容器插东西不走这条）；</li>
 *   <li>{@code getInventoryForUpgradeProcessing()} —— 在第一条外面再套一层升级链；</li>
 *   <li>{@code getInventoryForInputOutput()} —— 再叠加背包自己的筛选设置。</li>
 * </ul>
 *
 * <p>看起来用第一条就能躲开升级逻辑，实际躲不开：{@code InventoryHandler#insertItemInternal}
 * 自己就会调 {@code runOnBeforeInsert}，把 {@code IInsertResponseUpgrade} 逐个问一遍——
 * 而<b>虚空升级</b>正是这个接口上的：过滤类型为"总是"且过滤器命中（或者"溢出"且那格已有同种
 * 东西）时，它直接返回空堆，调用方当成"整堆都塞进去了"，东西当场消失。三个视图都会吞，
 * 所以光换视图没用，必须在外面拦一层：女仆往背包里塞东西时先问一次虚空升级"你会不会吞"，
 * 会吞就当作这一格塞不进去（原堆原样退回去），让她放回自己格子或换一格、换一只背包。</p>
 *
 * <p>为什么可以放心拦：精妙核心里实现 {@code onBeforeInsert} 的只有虚空升级一个——
 * 压缩升级只实现 {@code onAfterInsert}（塞进去之后再压），照常生效不受影响。
 * 也就是说"插入前返回空堆"这个信号只可能是"东西要被销毁"。</p>
 *
 * <p><b>护栏只管"模组替她自动塞"的那条路，不管玩家亲手操作。</b>
 * 本类对外是两个视图：</p>
 * <ul>
 *   <li>{@link #inventoryHandler} —— 带防吞护栏。给女仆 AI、自动拾取、取用工具、
 *       别的模组顺着"可用物品栏"搬运用：这些动作没人盯着，吞了就是纯损失；</li>
 *   <li>{@link #guiInventoryHandler} —— 不带护栏，就是精妙背包本来的物品栏。
 *       给本模组页签里的 shift 移动、整理这类<b>玩家亲手点</b>的操作用：
 *       装了虚空升级就等于玩家自己想要销毁物品，在原生界面里 shift 进去会销毁，
 *       在这里也照旧销毁，两个界面行为一致，才不会出现"同样一个操作，
 *       从原生界面进去被销毁、从这个页签进去却被拒收"的困惑。</li>
 * </ul>
 *
 * <p><b>两路都不套背包自己的输入/输出筛选</b>（{@code getInventoryForInputOutput} 才带筛选）：
 * 背包对女仆就是她自己的扩展格子，里面放什么都该能用、能拿。由此带来一处与原生界面的差异——
 * 背包设了输入过滤的物品，原生界面会拒收，本模组页签里能塞进去，这是<b>有意为之</b>：
 * 页签把背包当"女仆的格子"，玩家往里放手边的东西不该被背包设置拦；而虚空升级那种
 * "插进去就销毁"的行为属于危险操作，必须和原生保持一致，所以 GUI 那一路连防吞护栏也不加
 * （见 {@link #guiInventoryHandler}：原生会销毁，这里也照旧销毁）。</p>
 */
public final class SbCompat {
    /** 注册到精妙背包 PlayerInventoryProvider 的处理器名。 */
    public static final String HANDLER_NAME = "maid_backpack";

    /**
     * 防吞护栏的结果缓存，按背包物品堆的身份分。
     *
     * <p>为什么需要：自动拾取一批掉落物时，"每个物品 × 每个背包槽"都要问一次物品栏，
     * 每次都要遍历一遍升级表并分配一个 List；有虚空升级时还要多 new 一个包装对象。
     * 不缓存的话，一次捡十几件东西就是几十次全表遍历，全压在主线程上。</p>
     *
     * <p>为什么可以缓存：这张表缓存的是"这只背包的护栏长什么样"，不是背包内容。
     * 缓存键是槽里那个 {@code ItemStack} 的对象身份（{@code ItemStack} 没有重写
     * {@code equals/hashCode}，天然就是身份比较）——换包、卸包、读档都会换对象。
     * 升级列表变化（加/卸虚空升级）不会换对象，所以只靠缓存会有一段陈旧窗口，
     * 用 {@value #SHIELD_TTL_MILLIS} 毫秒的短有效期兜住：加升级后最多这么久内还拦不住，
     * 卸升级后最多这么久内还会拒收。相对"每 tick 全表遍历"这个代价，半秒是可以接受的。</p>
     *
     * <p>用带容量上限的 LRU 而不是 {@code WeakHashMap}：值里的背包包装很可能反过来
     * 强引用着这个物品堆，弱键就永远回收不掉，表反而只增不减。固定上限 + 淘汰最老的，
     * 无论调用方传的是什么对象都封得住。第三个参数 {@code true} 才是按<b>访问顺序</b>
     *（真 LRU：最久没被用过的最先淘汰）；{@code false} 是插入顺序，淘汰的就变成
     * "最早放进来"的那只背包，常驻的背包反而会被踢掉。过期项也不等被淘汰：
     * {@link #shieldVoidInserts} 命中时顺手删掉已经陈旧的项，关服时由
     * {@link #clearCaches()} 整表清空。切维度 / 退世界时刻意不清：清掉会让服务端
     * 正在开的原生界面下一秒自查到"包装换了"而被踢掉（见 {@code Events#onLevelUnload}）。
     */
    private static final java.util.Map<ItemStack, Shield> SHIELDS =
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<ItemStack, Shield> eldest) {
                    return size() > SHIELD_CACHE_LIMIT;
                }
            };
    /** 护栏缓存的有效期（毫秒）：约 10 tick。 */
    private static final long SHIELD_TTL_MILLIS = 500L;
    /** 护栏缓存最多存多少项。 */
    private static final int SHIELD_CACHE_LIMIT = 256;
    /**
     * "这只背包的护栏没搭起来"是否已经记过一次日志。
     *
     * <p>没有它，单看日志就分不清"这只背包里本来就没东西"和"取物品栏时抛异常、这一轮被当空了"——
     * 看门狗只在<b>所有</b>背包都拼不上时才叫，单只背包失败会静默消失。只记首次，别刷屏。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean SHIELD_ERROR_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * "背包堆 → 背包包装"的身份缓存：同一只背包全模组只发一个 {@link IBackpackWrapper}。
     *
     * <p>以前每次 {@link #wrapperOf} 都现场 {@code BackpackWrapper.fromStack} 新建一个包装。
     * 一个包装一套内部物品栏：原生界面打开时精妙自己持有它的一个包装，女仆自动拾取 /
     * 取工具 / 页签渲染又各走各新建的包装，两份 {@code InventoryHandler} 对着同一份背包
     * 存档各读各写——原生界面每 tick 都要自查"存储物品有没有被动过手脚"，包装来回变
     * 就是它把界面一脚踢掉的最直接嫌疑。共享之后自动路径和界面看到的是同一套内部物品栏：
     * 拾进去的东西立刻出现在打开的界面上，两边也不会再互相踩。</p>
     *
     * <p>键仍是 ItemStack 的对象身份（同 {@link #SHIELD} 那套理由，两侧的堆对象天然不同，
     * 不会互相覆盖），容量上限 + LRU 淘汰，关服时由 {@link #clearCaches()} 整表清空。
     * 堆被换掉时旧项永远命中不上，会被新项自然挤出去——行为是对的，只是晚一点。</p>
     */
    private static final java.util.Map<ItemStack, IBackpackWrapper> WRAPPERS =
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<ItemStack, IBackpackWrapper> eldest) {
                    return size() > SHIELD_CACHE_LIMIT;
                }
            };

    /** 现在有哪些玩家开着女仆背包的精妙原生界面：玩家 UUID → 他在看谁的哪一格。 */
    private static final java.util.Map<UUID, NativeOpen> NATIVE_OPEN = new java.util.concurrent.ConcurrentHashMap<>();

    /** 一次原生界面的打开记录：玩家、目标女仆、目标槽位、菜单窗口号（用来识别这条记录是否还作数）。 */
    private record NativeOpen(UUID playerId, UUID maidId, int slot, int windowId) {}

    private SbCompat() {}

    /** 注册"女仆背包槽"处理器：identifier 就是女仆实体的 UUID 字符串。 */
    public static void registerProvider() {
        PlayerInventoryProvider.get().addPlayerInventoryHandler(
                HANDLER_NAME,
                player -> Set.of(),                       // 不参与玩家自身界面/渲染的遍历
                (player, identifier) -> MaidBackpackData.SLOTS,
                (player, identifier, slot) -> stackOfMaidSlot(player, identifier, slot),
                false,                                    // visibleInGui：不在玩家界面显示
                false,                                    // rendered：不由精妙背包渲染
                false,                                    // ownRenderer
                false                                     // accessibleByAnotherPlayer
        );
    }

    private static ItemStack stackOfMaidSlot(net.minecraft.world.entity.player.Player viewer, String identifier, int slot) {
        if (slot < 0 || slot >= MaidBackpackData.SLOTS) {
            return ItemStack.EMPTY;
        }
        try {
            UUID maidId = UUID.fromString(identifier);
            LivingEntity maid = ClientMaidIndex.resolve(viewer.level(), maidId);
            if (maid == null) {
                return ItemStack.EMPTY;
            }
            // 服务端再兜一层：主人 / 同维度 / 8 格以内，别让走远或别人家的女仆背包还能被读
            if (viewer instanceof ServerPlayer serverPlayer
                    && (maid.level() != serverPlayer.level()
                    || !Events.isMaidOwner(maid, serverPlayer)
                    || maid.distanceToSqr(serverPlayer) > 64.0)) {
                return ItemStack.EMPTY;
            }
            return MaidBackpackData.of(maid).map(data -> data.getStackInSlot(slot)).orElse(ItemStack.EMPTY);
        } catch (IllegalArgumentException ex) {
            return ItemStack.EMPTY;
        }
    }

    /** 用精妙背包的原生界面打开女仆身上第 slot 格背包。 */
    public static void openBackpackGui(ServerPlayer player, LivingEntity maid, int slot) {
        if (slot < 0 || slot >= MaidBackpackData.SLOTS) {
            return;
        }
        // 兜底再校验一次（调用方已经查过）：原生界面一旦打开就能直接改背包内容，
        // 权限/距离/维度任何一项不满足都不该走到这里。
        if (maid.isRemoved() || maid.level() != player.level() || !Events.isMaidOwner(maid, player)
                || maid.distanceToSqr(player) > 64.0) {
            return;
        }
        ItemStack stack = MaidBackpackData.of(maid)
                .map(data -> data.getStackInSlot(slot)).orElse(ItemStack.EMPTY);
        if (stack.isEmpty()) {
            return;
        }
        // 把共享包装预填进精妙自己的缓存字段（backpackWrapper），让原生界面直接用我们这份——
        // 它每 tick 自查"存储物品是否被动过"的方式是拿 上下文.getBackpackWrapper() 和建窗时那份
        // 比对象引用；不预填的话建窗时精妙现场又新建一个，跟我们这边自动路径用的就是两套内部
        // 物品栏，同写一份背包存档。"联动存储"背包例外：那种包装必须由精妙的解析器创建，不预填。
        IBackpackWrapper shared = isLinkedStorage(stack) ? null : wrapperOf(stack);
        MaidBackpackContext context = new MaidBackpackContext(
                maid.getUUID().toString(), slot, maid.getUUID(), shared);
        // 写入必须走 context.toBuffer(buffer, player)，和精妙背包自己的 BackpackItem#use 完全同款：
        // 它在最前面写 2 字节的 ContextType、最后写联动存储快照的标记位，客户端
        // BackpackContext#fromBuffer 严格按这个格式读。之前只写 addToBuffer 的裸三个字段，
        // 客户端把开头的长度字节 13 和 'm' 当成类型读掉后错位，再拿下一个字节 'a'(0x61=97)
        // 当字符串长度，剩下只有 53 字节，直接 DecoderException("expected 97, but got 53") 断线。
        player.openMenu(
                new SimpleMenuProvider((windowId, playerInventory, menuPlayer) ->
                        new BackpackContainer(windowId, menuPlayer, context), context.getDisplayName(player)),
                buffer -> context.toBuffer(buffer, player));
        // 记下"这个玩家正在看这只背包"：窗口号拿刚开出来的菜单的；槽里堆对象之后在服务端
        // 被换掉时（MaidBackpackData 的身份追踪会报过来），我们据此体面关窗并在日志里留痕。
        NATIVE_OPEN.put(player.getUUID(),
                new NativeOpen(player.getUUID(), context.maidId, context.maidSlot,
                        player.containerMenu.containerId));
        // 原生界面构造时会懒生成 contentsUuid 等物品 NBT；立刻把快照刷新一次，
        // 免得实体保存时用的还是没有 uuid 的旧物品数据（重载后背包内容会"消失"）。
        MaidBackpackData.of(maid).ifPresent(MaidBackpackData::saveToEntity);
    }

    /** 往背包物品堆里插入物品（走 {@link #inventoryHandler} 那层防吞视图，见类注释）。 */
    public static ItemStack insertIntoBackpack(ItemStack backpackStack, ItemStack toInsert) {
        if (toInsert.isEmpty() || backpackStack.isEmpty()) {
            return toInsert;
        }
        return inventoryHandler(backpackStack).map(handler -> insertIntoAnySlot(handler, toInsert, false))
                .orElse(toInsert);
    }

    /**
     * "取用自动搜包"：从背包里搜出第一件合适的工具/武器（供女仆拿在手上干活）。
     * 走背包自己的物品栏，不受外部筛选限制；只拿不塞，所以不需要防虚空那一层。
     */
    public static ItemStack extractToolForMaid(ItemStack backpackStack) {
        if (backpackStack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        IBackpackWrapper wrapper = wrapperOf(backpackStack);
        if (wrapper == null) {
            return ItemStack.EMPTY;
        }
        try {
            ITrackedContentsItemHandler inventory = wrapper.getInventoryHandler();
            for (int i = 0; i < inventory.getSlots(); i++) {
                ItemStack found = inventory.getStackInSlot(i);
                if (Events.isToolOrWeapon(found)) {
                    return inventory.extractItem(i, 1, false);
                }
            }
        } catch (Exception | LinkageError t) {
            return ItemStack.EMPTY;
        }
        return ItemStack.EMPTY;
    }

    /**
     * 拿当前背包内部物品栏（<b>带防吞护栏</b>），空包返回 empty。
     *
     * <p>给"模组替女仆自动操作"的那条路用：自动拾取、取用工具、别的模组顺着她的
     * 可用物品栏搬运。返回的视图默认就是精妙背包自己的 {@code InventoryHandler}；
     * 只有这只背包真的装了会在插入前动手的升级（虚空）时才套一层
     * {@link NoVoidInsertHandler}——没装升级就一点开销都不加，装了才拦。</p>
     *
     * <p>玩家亲手操作的界面路径请用 {@link #guiInventoryHandler}（不拦，和原生界面一致）。</p>
     */
    public static Optional<IItemHandlerModifiable> inventoryHandler(ItemStack backpackStack) {
        IBackpackWrapper wrapper = wrapperOf(backpackStack);
        if (wrapper == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(shieldVoidInserts(wrapper, backpackStack));
    }
    /**
     * 拿当前背包内部物品栏（<b>不带任何护栏</b>），空包返回 empty。
     *
     * <p>这就是精妙背包自己那个 {@code InventoryHandler} 原样，行为与在精妙背包原生界面里
     * 完全相同——装了虚空升级的背包，在这里 shift 进去的东西照常被销毁。
     * 只给玩家亲手操作的界面路径用（见类注释里两套视图的分工）。</p>
     */
    public static Optional<IItemHandlerModifiable> guiInventoryHandler(ItemStack backpackStack) {
        IBackpackWrapper wrapper = wrapperOf(backpackStack);
        if (wrapper == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(wrapper.getInventoryHandler());
        } catch (Exception | LinkageError t) {
            // 取不到物品栏就当这只背包没有内容：宁可这一帧是空的，也不能让界面炸
            return Optional.empty();
        }
    }

    /**
     * 物品堆 → 背包包装；不是背包、拿不到包装都返回 null（1.21.1 起走 BackpackWrapper.fromStack，能力入口已删）。
     *
     * <p>结果按堆的对象身份缓存，见 {@link #WRAPPERS}：自动路径和精妙原生界面共用一套
     * 内部物品栏，谁也不跟谁抢着写背包存档。</p>
     */
    private static IBackpackWrapper wrapperOf(ItemStack backpackStack) {
        if (backpackStack == null || backpackStack.isEmpty()) {
            return null;
        }
        synchronized (WRAPPERS) {
            IBackpackWrapper cached = WRAPPERS.get(backpackStack);
            if (cached != null) {
                return cached;
            }
        }
        try {
            IBackpackWrapper wrapper = BackpackWrapper.fromStack(backpackStack);
            // 不是真背包时 fromStack 给的是全局 Noop 单例，直接当"没有背包"处理（负结果不进缓存）
            if (!wrapper.supportsBackpackContents()) {
                return null;
            }
            synchronized (WRAPPERS) {
                WRAPPERS.put(backpackStack, wrapper);
            }
            return wrapper;
        } catch (Exception | LinkageError t) {
            return null;
        }
    }

    /** 这只堆是不是"联动存储"模式（跨背包联动那一套）；查不动时一律按"是"处理，让精妙自己的解析器接管。 */
    private static boolean isLinkedStorage(ItemStack stack) {
        try {
            return stack.get(ModCoreDataComponents.LINKED_STORAGE_ENDPOINT.get()) != null;
        } catch (Exception | LinkageError t) {
            return true;
        }
    }

    /**
     * 背包内部物品栏 + "别把东西变没"的护栏。
     *
     * <p>{@code InventoryHandler#insertItemInternal} 每次插入前都会问一遍所有
     * {@link IInsertResponseUpgrade}；虚空升级回答"返回空堆"就等于"这堆我已经处理过了"，
     * 东西当场消失。女仆捡东西、往里塞工具、别的模组顺着她的可用物品栏搬运，走的都是这个插入，
     * 所以在这里挡一手：会整堆消失的插入直接改成"这一格不收"（原堆原样退回去）。</p>
     *
     * <p>没有相关升级时原样返回，不套任何包装。</p>
     *
     * <p><b>结果按背包缓存</b>（见 {@link #SHIELDS}）：自动拾取一批掉落物时，
     * 每个物品 × 每个背包槽都要问一次物品栏，而"问一遍升级表"要遍历全表再分配一个 List，
     * 有虚空升级时还要 new 一个包装对象。缓存之后同一只背包每
     * {@value #SHIELD_TTL_MILLIS} 毫秒最多算一次。</p>
     */
    private static IItemHandlerModifiable shieldVoidInserts(IBackpackWrapper wrapper, ItemStack key) {
        InventoryHandler raw;
        try {
            raw = wrapper.getInventoryHandler();
        } catch (Exception | LinkageError t) {
            logShieldErrorOnce("读取背包物品栏", t);
            return null;
        }
        if (raw == null) {
            return null;
        }
        long now = System.nanoTime();
        synchronized (SHIELDS) {
            Shield cached = SHIELDS.get(key);
            if (cached != null) {
                if (cached.matches(wrapper, now)) {
                    return cached.handler;
                }
                // 顺手清掉过期项：光"能存 256 项"挡得住无限增长，挡不住旧背包包装多留一阵
                SHIELDS.remove(key);
            }
        }
        List<IInsertResponseUpgrade> responders;
        try {
            responders = wrapper.getUpgradeHandler()
                    .getWrappersThatImplementFromMainStorage(IInsertResponseUpgrade.class);
        } catch (Exception | LinkageError t) {
            // 问不到升级就当没有：宁可少拦一次，也不能因为这里抛异常让女仆整个物品栏取不到。
            // 但这一轮这只背包确实没有防吞护栏了，记一次日志，别让它无声无息
            logShieldErrorOnce("读取背包的升级表", t);
            responders = null;
        }
        IItemHandlerModifiable result = (responders == null || responders.isEmpty())
                ? raw
                : new NoVoidInsertHandler(raw, responders);
        synchronized (SHIELDS) {
            SHIELDS.put(key, new Shield(wrapper, now, result));
        }
        return result;
    }

    /** 护栏搭不起来只报一次（带首次异常），之后同类异常静默——这方法调用频率很高。 */
    private static void logShieldErrorOnce(String what, Throwable t) {
        if (SHIELD_ERROR_LOGGED.compareAndSet(false, true)) {
            MaidSophisticated.LOGGER.warn(
                    "maid_sophisticated: {}时出现异常，这只背包这一轮按「没有内容」处理。"
                            + "女仆不会因此丢东西，但这一轮看不到这只背包里的物品（防吞护栏也暂不生效）。"
                            + "同类异常不再重复记录。", what, t);
        }
    }

    /**
     * 清空本类全部按堆身份缓存的表（护栏、背包包装、原生界面开窗登记）。
     * 只在关服时由 {@code Events} 调用（调用方负责先确认精妙背包已加载）；
     * 切维度 / 退世界不清，理由见 {@link #WRAPPERS} 的说明。
     *
     * <p>缓存键是背包物品堆、值是背包包装，两边都会强引用上一局的实体数据；
     * 不清的话这些对象要等到被 LRU 淘汰才松手，单机反复进出存档就会攒一堆死引用。
     * 原生界面开窗登记里的玩家 / 女仆更是直接挂着上一局服务端对象的引用。</p>
     */
    public static void clearCaches() {
        synchronized (SHIELDS) {
            SHIELDS.clear();
        }
        synchronized (WRAPPERS) {
            WRAPPERS.clear();
        }
        NATIVE_OPEN.clear();
    }

    /**
     * 服务端 {@link MaidBackpackData} 在换掉槽里的背包堆对象时打的招呼（仅服务端调用）。
     *
     * <p>背景：精妙背包的服务端菜单每一 tick 自查一次 {@code hasSomethingMessedWithStorage}——
     * 它拿"当前槽里的堆"再解析一遍包装、和开窗时那份比<b>对象引用</b>，对不上就直接
     * {@code closeContainer()}，日志里一声不吭。用户看到的"原生界面开两秒自己关了"，
     * 根因就在这条自查：只要有人在我们没留神的地方把槽里的堆换掉，界面对象引用就断了。
     * 与其让它去查，不如我们在换堆的那一现场合主动收尾：先关窗、并留下"谁换的"的日志栈，
     * 这个类的所有缓存也顺带在这一处对齐。</p>
     *
     * <p>没开原生界面的槽位换堆是完全正常的操作（放包、取包），所以这里必须静默跳过，
     * 只在"确实在看这一格"时才动作。</p>
     */
    public static void onServerBackpackStackReplaced(LivingEntity maid, int slot, String via) {
        if (maid == null || maid.level() == null || maid.level().isClientSide) {
            return;
        }
        MinecraftServer server = maid.level().getServer();
        if (server == null) {
            return;
        }
        for (NativeOpen open : List.copyOf(NATIVE_OPEN.values())) {
            if (!open.maidId().equals(maid.getUUID()) || open.slot() != slot) {
                continue;
            }
            ServerPlayer viewer = server.getPlayerList().getPlayer(open.playerId());
            if (viewer == null || viewer.isRemoved()) {
                NATIVE_OPEN.remove(open.playerId());
                continue;
            }
            // 登记的窗口早就不作数了（玩家已经换了界面）：顺手清掉，不用等 Close 事件
            if (!(viewer.containerMenu instanceof BackpackContainer container)
                    || container.containerId != open.windowId()) {
                NATIVE_OPEN.remove(open.playerId());
                continue;
            }
            MaidSophisticated.LOGGER.warn(
                    "女仆 {} 第 {} 格的精妙背包堆对象被换掉（途径：{}），玩家 {} 的原生界面按原样会被精妙的自查踢掉，这里主动体面关闭。",
                    maid.getUUID(), slot, via, viewer.getName().getString(),
                    // 带栈打印：下一次再出现"开两秒就关"，日志直接指出是哪条路在换堆对象
                    new Throwable("stack-replaced-trace"));
            viewer.closeContainer();
            NATIVE_OPEN.remove(open.playerId());
        }
    }

    /** {@link #SHIELDS} 的一项：哪只背包、什么时候算的、算出来的护栏视图。 */
    private static final class Shield {
        private final IBackpackWrapper wrapper;
        private final long builtAtNanos;
        private final IItemHandlerModifiable handler;

        Shield(IBackpackWrapper wrapper, long builtAtNanos, IItemHandlerModifiable handler) {
            this.wrapper = wrapper;
            this.builtAtNanos = builtAtNanos;
            this.handler = handler;
        }

        /** 还能不能用：同一个背包包装（没被替换）+ 没超出有效期。 */
        boolean matches(IBackpackWrapper current, long now) {
            if (current != wrapper) {
                return false;
            }
            // nanoTime 的绝对值可能是负数，只比较差值（有符号差在溢出后依然正确）
            long age = now - builtAtNanos;
            return age >= 0L && age < SHIELD_TTL_MILLIS * 1_000_000L;
        }
    }

    /**
     * 拦截"插入即销毁"的背包物品栏视图：读、拿、改全部原样转发，只有 {@code insertItem}
     * 会先问一次插入前升级——只要答案是一整堆都不见了，就退回原堆当作塞不进去。
     */
    private static final class NoVoidInsertHandler implements IItemHandlerModifiable {
        private final InventoryHandler raw;
        private final List<IInsertResponseUpgrade> responders;

        NoVoidInsertHandler(InventoryHandler raw, List<IInsertResponseUpgrade> responders) {
            this.raw = raw;
            this.responders = responders;
        }

        @Override
        public int getSlots() {
            return raw.getSlots();
        }

        @Nonnull
        @Override
        public ItemStack getStackInSlot(int slot) {
            return raw.getStackInSlot(slot);
        }

        @Nonnull
        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (!stack.isEmpty() && wouldVoidEntireStack(slot, stack)) {
                // 当成"这一格不收"：原堆一个不少地退回去，女仆自己会去找别的格子
                return stack;
            }
            try {
                return raw.insertItem(slot, stack, simulate);
            } catch (Exception | LinkageError t) {
                // 护栏挡不住的那一半：插入本身抛了，也退回原堆。
                // 不这么做，异常会顺着女仆的 AI 往上冒，整个 tick 都完蛋；
                // 退回原堆最多是"这次没塞进去"，比崩掉或者丢物品都可控。
                return stack;
            }
        }

        @Nonnull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return raw.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return raw.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return raw.isItemValid(slot, stack);
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            raw.setStackInSlot(slot, stack);
        }

        /** 问一遍插入前升级：有没有哪个把这一整堆"处理"成空了（= 会当场销毁）。 */
        private boolean wouldVoidEntireStack(int slot, ItemStack stack) {
            for (IInsertResponseUpgrade responder : responders) {
                try {
                    // simulate=true：只问结果，不产生任何副作用
                    if (responder.onBeforeInsert(raw, slot, stack, true).isEmpty()) {
                        return true;
                    }
                } catch (Exception | LinkageError t) {
                    // 问不动就照常放行这一次插入，别把异常抛回女仆的 AI
                    return false;
                }
            }
            return false;
        }
    }

    /**
     * 把 toInsert 尽量塞进背包内部任意格子（尊重背包自己的合法性判断 + 虚空拦截），
     * 返回没塞进去的剩余部分。
     *
     * 注意：这里必须一直操作 {@code toInsert.copy()}，绝不能一边 insert 一边改传入的那个
     * ItemStack。Forge 的 ItemStackHandler 在"空槽且整堆都能放下"时会把传入的那个对象
     * 直接存进槽位，之后再 setCount(0) 就等于把槽里的东西也清空了——上一版
     * shift+左键物品直接消失就是这么来的。
     */
    private static ItemStack insertIntoAnySlot(IItemHandlerModifiable handler,
                                               ItemStack toInsert, boolean simulate) {
        ItemStack remaining = toInsert.copy();
        for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
            remaining = handler.insertItem(i, remaining, simulate);
        }
        return remaining;
    }

    /**
     * 把 toInsert 尽量塞进背包内部任意格子（不拦虚空，见 {@link #guiInventoryHandler}），
     * 改动 in-place。返回是否有任何物品被放入。
     *
     * <p>只给玩家亲手操作的界面路径用：在页签里 shift 一件物品进带虚空升级的背包，
     * 结果要和精妙背包原生界面一致（照常销毁），而不是"这里塞不进去、原生那边却能进去"。</p>
     *
     * <p>自动路径请用 {@link #insertIntoBackpack}（带防吞护栏），别在这里改：这两条路的分工
     * 是"没人盯着的不许吞、玩家亲手点的照原生"。</p>
     */
    public static boolean insertAnywhereGui(ItemStack backpackStack, ItemStack toInsert) {
        if (toInsert.isEmpty() || backpackStack.isEmpty()) {
            return false;
        }
        return guiInventoryHandler(backpackStack)
                .map(handler -> insertAnywhereImpl(handler, toInsert))
                .orElse(false);
    }

    private static boolean insertAnywhereImpl(IItemHandlerModifiable handler, ItemStack toInsert) {
        ItemStack remaining = insertIntoAnySlot(handler, toInsert, false);
        int inserted = toInsert.getCount() - remaining.getCount();
        if (inserted <= 0) {
            return false;
        }
        toInsert.setCount(remaining.getCount());
        return true;
    }

    /** 背包里还有没有位置放下这一整堆（只做模拟，不改任何东西）。 */
    public static boolean canAccept(ItemStack backpackStack, ItemStack stack) {
        if (stack.isEmpty() || backpackStack.isEmpty()) {
            return false;
        }
        return inventoryHandler(backpackStack)
                .map(handler -> insertIntoAnySlot(handler, stack, true).getCount() < stack.getCount())
                .orElse(false);
    }

    /**
     * 精妙背包"槽位里的背包"上下文 + 两件事：
     * <ol>
     *   <li>构造时把我们的共享包装预填进父类的 {@code backpackWrapper} 缓存字段。
     *       父类 {@code getBackpackWrapper} 的复用条件是"缓存非空 且 缓存持有的堆对象
     *       与当前槽里的同一个 且 不是联动存储包装"——预填后这三条都成立，
     *       精妙的菜单每 tick 的自查拿到的就是我们那份包装，对象引用恒等，不会踢窗；
     *       自动拾取和界面读写的是同一套内部物品栏，也不会互相踩存档。</li>
     *   <li>带上女仆 UUID / 槽位号，开窗登记 {@link #NATIVE_OPEN} 时直接用（不用再去拆 identifier 字符串）。</li>
     * </ol>
     *
     * <p>序列化 {@code toBuffer/addToBuffer} 原样继承父类：读端按 ContextType.ITEM 建的是
     * 普通 {@code BackpackContext.Item}，客户端没有我们的预填包装（也不需要——客户端那份
     * 菜单不跑 hasSomethingMessedWithStorage 自查，它有 {@code isClientSide} 短路）。</p>
     */
    private static final class MaidBackpackContext extends BackpackContext.Item {
        private final UUID maidId;
        private final int maidSlot;

        MaidBackpackContext(String identifier, int slot, UUID maidId,
                            @javax.annotation.Nullable IBackpackWrapper sharedWrapper) {
            super(HANDLER_NAME, identifier, slot, false);
            this.maidId = maidId;
            this.maidSlot = slot;
            if (sharedWrapper != null) {
                this.backpackWrapper = sharedWrapper;
            }
        }
    }
}
