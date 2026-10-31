package dev.barryalen.maidsophisticated;

import dev.barryalen.maidsophisticated.advancement.AllMaidBackpacksTrigger;
import dev.barryalen.maidsophisticated.advancement.FirstMaidBackpackTrigger;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/** 全部游戏事件挂接：装备、面板、智能存取、持久化、掉落。 */
public final class Events {
    /** 正在跟踪的女仆（弱引用，服务器侧扫描用）。 */
    private static final Set<LivingEntity> TRACKED_MAIDS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<LivingEntity, Boolean>()));

    private static int tickCounter = 0;
    /** 快照刷新轮换的批数：15 批 × 40 tick = 600 tick（30 秒）存一圈。 */
    private static final int SAVE_BAND_COUNT = 15;

    /**
     * "补丁看门狗"：注入点用了 {@code require = 0}（宁可功能失效也不让玩家的存档崩），
     * 代价是将来车万女仆改了那两个方法的名字/签名时，补丁会被静默跳过，谁也不吭声。
     * 这里补一手：第一只女仆进世界后等一段时间，女仆 AI 迟早会读"可用物品栏"，
     * {@link MaidBackpackInv#append} 就会留下痕迹；到点还没痕迹就说明补丁没接上，
     * 往 latest.log 里记一条醒目警告，排查的人一搜就能看到。
     *
     * <p>为什么是 5 分钟而不是 1 分钟：她站着发呆的时候 AI 可以整整一分钟都不碰物品栏
     * （附近没掉落物、当前任务也不需要看东西），1 分钟会把这种正常情况误报成补丁失效。</p>
     */
    private static final int HOOK_WATCH_DELAY = 6000;
    /** 到点检查的 tick（第一只女仆进世界时排班；-1 = 没活）。 */
    private static int hookCheckTick = -1;
    /** 警告只记一次，别每 5 分钟刷屏。 */
    private static boolean hookWarned = false;
    /** "已经确认补丁真的拼上背包了"这件事只对外说一次。 */
    private static boolean hookConfirmed = false;

    private Events() {}

    /**
     * 是不是车万女仆的女仆实体。
     *
     * 先按注册表命名空间粗筛（没装女仆模组时这里就返回 false，不会去碰她的类），
     * 再用真正的女仆类确认。只按命名空间判断会把同模组的妖精、鬼火之类也当成女仆，
     * 导致给它们挂背包数据、拿背包扣右键妖精也能开面板——所以必须再精确一步。
     */
    public static boolean isMaid(Entity entity) {
        if (!(entity instanceof Mob)) {
            return false;
        }
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (key == null || !key.getNamespace().equals(MaidSophisticated.TLM_MOD_ID)) {
            return false;
        }
        return TlmCompat.loaded() && TlmCompat.isMaidEntity(entity);
    }

    /** 主人校验：驯服过的女仆只认主人；野生女仆谁都能操作。 */
    public static boolean isMaidOwner(@Nullable LivingEntity maid, Player player) {
        if (maid instanceof TamableAnimal tamable) {
            // 用 UUID 比，别用 getOwner()：主人下线时 getOwner() 是 null，
            // 那样会被当成"野生女仆"，别人就能开她背包了。
            java.util.UUID ownerId = tamable.getOwnerUUID();
            return ownerId == null || ownerId.equals(player.getUUID());
        }
        return true;
    }

    // ---------- capability 挂接与生命周期 ----------

@SubscribeEvent
        public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (!(event.getObject() instanceof LivingEntity living) || !isMaid(living)) {
            return;
        }
        MaidBackpackData data = new MaidBackpackData(living);
        LazyOptional<MaidBackpackData> optional = LazyOptional.of(() -> data);
        event.addCapability(new ResourceLocation(MaidSophisticated.MOD_ID, "maid_backpack"), new ICapabilityProvider() {
            @Nonnull
            @Override
            public <T> LazyOptional<T> getCapability(@Nonnull net.minecraftforge.common.capabilities.Capability<T> cap,
                                                     @Nullable net.minecraft.core.Direction side) {
                return MaidCaps.MAID_BACKPACK.orEmpty(cap, optional);
            }
        });
    }

@SubscribeEvent
        public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!isMaid(event.getEntity())) {
            return;
        }
        ClientMaidIndex.track(event.getEntity());
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        // 有女仆了才谈得上检查补丁有没有生效：给看门狗排个班（只排一次）
        if (hookCheckTick < 0) {
            hookCheckTick = tickCounter + HOOK_WATCH_DELAY;
        }
        MaidBackpackData.of(event.getEntity()).ifPresent(data -> {
            data.readFromEntity();
            ModNetwork.syncMaidBackpack((LivingEntity) event.getEntity(), data);
            TRACKED_MAIDS.add((LivingEntity) event.getEntity());
        });
        // 老存档兼容：更早的版本把精妙背包写成了女仆自己的"背包类型"，
        // 那种存档里她自己的背包其实已经被顶掉了，进世界时帮她改回空背包。
        if (event.getEntity() instanceof LivingEntity living && TlmCompat.loaded()) {
            TlmCompat.repairForeignBackpackType(living);
        }
    }

    /** 玩家开始追踪女仆时补发背包数据：后靠近的玩家也能立刻看到背上的背包。 */
    @SubscribeEvent
    public static void onStartTracking(net.minecraftforge.event.entity.player.PlayerEvent.StartTracking event) {
        if (event.getEntity().level().isClientSide || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (event.getTarget() instanceof LivingEntity maid && isMaid(maid)) {
            MaidBackpackData.of(maid).ifPresent(data -> ModNetwork.syncMaidBackpackTo(player, maid, data));
        }
    }

@SubscribeEvent
        public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!isMaid(event.getEntity())) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof LivingEntity living) {
            MaidBackpackData.of(living).ifPresent(data -> {
                // 死亡兜底：正常死亡时 onLivingDrops 已经把包清空了（数据走掉落物）。
                // 走到这里还"非空 + 已移除 + 原因是死亡"，说明掉落事件根本没发生
                //（比如别的模组把 doMobLoot 关了、或女仆模组的墓碑流程跳过了掉落事件）——
                // 不补掉的话背包就跟着实体一起没了。掉落原因必须严格只认 KILLED：
                // 切维度 / 区块卸载时实体同样 isRemoved，但数据要原样留给新实例，不能掉；
                // DISCARDED 更不能当死亡——精灵球、金套索这类"收服"模组是先把女仆的存档数据
                // 抄进球里再 discard() 实体的，我们这里把背包掉地上，球里那份还带着包，
                // 放出来就是两份（复制物品）。收服流程的数据由对方球自己负责，我们别插手。
                // 时序已考证（照 1.20.1 反混淆字节码逐条核过，不是猜）：Entity#remove(reason)
                // 的第一步就写 removalReason 字段，之后才走 inLevelCallback 回调链，
                // 链尾（ServerLevel 实体表 remove 处）才发 EntityLeaveLevelEvent；
                // 而 1.20.1 的 isRemoved() 实现就是 removalReason != null。
                // 所以事件触发的一瞬两个条件必然都已成立，这个兜底是活的：
                // 女仆死亡 1 秒（deathTime 攒满 20 tick）后 remove(KILLED) → 事件 → 掉包。
                Entity.RemovalReason reason = living.getRemovalReason();
                boolean goneForGood = living.isRemoved() && reason == Entity.RemovalReason.KILLED;
                if (goneForGood && !MaidBackpackData.isEmptyHandler(data)) {
                    for (ItemStack stack : data.copyStacks()) {
                        if (stack.isEmpty()) {
                            continue;
                        }
                        ItemEntity drop = new ItemEntity(level,
                                living.getX(), living.getY() + 0.3, living.getZ(), stack);
                        drop.setDefaultPickUpDelay();
                        level.addFreshEntity(drop);
                    }
                    data.clearAndDetach();
                } else {
                    data.saveToEntity();
                }
            });
        }
        TRACKED_MAIDS.remove(event.getEntity());
        ClientMaidIndex.untrack(event.getEntity());
        MaidBackpackView.clear(event.getEntity().getId());
    }

    /** 退出世界 / 切维度：清掉客户端按实体 id 缓存的背包外观，免得新世界的女仆串用旧数据。 */
    @SubscribeEvent
    public static void onLevelUnload(net.minecraftforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            MaidBackpackView.clearAll();
        }
        // 精妙背包的护栏缓存按背包物品堆存（值是背包包装，会强引用实体数据）：
        // 换世界不清，上一局的背包包装要等到被 LRU 淘汰才松手。
        if (MaidSophisticated.sophisticatedBackpacksLoaded()) {
            SbCompat.clearShieldCache();
        }
    }

    /**
     * 取一份"当前跟踪的女仆"快照。
     *
     * TRACKED_MAIDS 底层是 WeakHashMap：GC 随时可能回收键，直接边遍历边被 GC 清项
     * 就是 CME 的经典现场；synchronizedSet 也只是让单个方法原子，遍历仍需自己上锁。
     * 所以统一在锁内拷贝一份，遍历走快照。
     */
    private static List<LivingEntity> trackedMaidsSnapshot() {
        synchronized (TRACKED_MAIDS) {
            return new ArrayList<>(TRACKED_MAIDS);
        }
    }

    /**
     * 看门狗到点检查，三种结果分开处理（每 5 分钟才走一次，成本可忽略）：
     *
     * <ol>
     *   <li><b>真拼上了</b>（{@link MaidBackpackInv#hookMerged()}）—— 记一行 INFO 收工。
     *       注意不能只看"补丁跑过"：跑过但每次都取不到背包，功能照样是坏的；</li>
     *   <li><b>还没法判断</b> —— 场上没有女仆，或者没有一只女仆身上真带着精妙背包
     *       （拼不上是应该的），改日再查，不报任何警告；</li>
     *   <li><b>确实不对</b> —— 有带背包的女仆却始终没拼上，按"补丁没跑"和"跑了但拼不上"
     *       两种情况分别给出可操作的 WARN（只报一次，不刷屏）。</li>
     * </ol>
     */
    private static void checkHookWatchdog(boolean statePass) {
        if (hookCheckTick < 0 || !statePass || tickCounter < hookCheckTick) {
            return;
        }
        if (MaidBackpackInv.hookMerged()) {
            hookCheckTick = -1;
            if (!hookConfirmed) {
                hookConfirmed = true;
                MaidSophisticated.LOGGER.info(
                        "可用物品栏补丁确认生效：女仆的 AI 已经能看到精妙背包里的 {} 格物品。",
                        MaidBackpackInv.lastMergedSlots());
            }
            return;
        }
        if (trackedMaidsSnapshot().isEmpty()) {
            hookCheckTick = tickCounter + HOOK_WATCH_DELAY; // 女仆们都没进世界，改日再查
            return;
        }
        if (!anyTrackedMaidHasBackpack()) {
            // 没有一只女仆身上有精妙背包：拼不上是正常现象，不该报故障，继续等
            hookCheckTick = tickCounter + HOOK_WATCH_DELAY;
            return;
        }
        hookCheckTick = -1;
        if (hookWarned) {
            return;
        }
        hookWarned = true;
        // 措辞一律用"尚未观察到"，不用"没运行/没拼上"：这只女仆可能只是在挂机
        // （附近没掉落物、当前任务也不碰物品栏），5 分钟没跑过是完全正常的。
        // 日志不该用确定的口气去断言一件"还没看到"的事，否则排查的人会被自己误导。
        if (MaidBackpackInv.hookRan()) {
            MaidSophisticated.LOGGER.warn(
                    "可用物品栏补丁跑起来了，但 5 分钟内尚未观察到它真的拼上任何一个精妙背包："
                            + "注入点还在，疑似每次取背包内部物品栏都失败（多半是精妙背包这个版本"
                            + "改了内部物品栏的取法）。请在本日志里搜 maid_sophisticated 的异常记录；"
                            + "若那只女仆一直在挂机、附近也没有掉落物，这条可以先忽略。");
        } else {
            MaidSophisticated.LOGGER.warn(
                    "5 分钟内尚未观察到可用物品栏补丁运行：车万女仆的 getAvailableInv(Z)/"
                            + "getAvailableBackpackInv() 可能已在这个版本改名或改签名，"
                            + "女仆将看不见精妙背包里的物品。请核对本模组与该版本女仆的兼容性；"
                            + "若那只女仆一直在挂机、AI 没碰过物品栏，这条可以先忽略。");
        }
    }

    /** 被跟踪的女仆里有没有哪一只确实带着精妙背包（看门狗用它区分"故障"和"本来就没包"）。 */
    private static boolean anyTrackedMaidHasBackpack() {
        for (LivingEntity maid : trackedMaidsSnapshot()) {
            if (maid.isRemoved()) {
                continue;
            }
            MaidBackpackData data = MaidBackpackData.of(maid).orElse(null);
            if (data != null && !MaidBackpackData.isEmptyHandler(data)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 世界保存前把女仆背包数据刷新一遍。
     *
     * 原版保存实体时不会帮我们序列化 capability，我们存的是"快照"。而精妙背包的
     * contentsUuid 等物品 NBT 是在运行中懒生成的，快照不刷新的话，这次保存写下去的还是旧 NBT。
     * {@code LevelEvent.Save} 在实体保存之后触发，所以这里刷的是"下一次保存"用的快照，
     * 再配合下面的定时刷新和 {@link #onServerStopping} 就不会丢数据。
     */
    @SubscribeEvent
    public static void onLevelSave(net.minecraftforge.event.level.LevelEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        for (LivingEntity maid : trackedMaidsSnapshot()) {
            if (!maid.isRemoved() && maid.level() == level) {
                MaidBackpackData.of(maid).ifPresent(MaidBackpackData::saveToEntity);
            }
        }
    }

    /** 服务器关闭：原版紧接着就会保存世界，这里赶在保存前把最新数据写回（关服才退出游戏也安全）。 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (LivingEntity maid : trackedMaidsSnapshot()) {
            if (!maid.isRemoved()) {
                MaidBackpackData.of(maid).ifPresent(MaidBackpackData::saveToEntity);
            }
        }
        // 单级整合包重启服务端不换类加载器时，上一局的实体引用会留在这里：
        // 快照集虽然会随 GC 变空，但关服是清空它的最佳时机。
        synchronized (TRACKED_MAIDS) {
            TRACKED_MAIDS.clear();
        }
        // 同理清掉精妙背包那层护栏缓存里的背包包装
        if (MaidSophisticated.sophisticatedBackpacksLoaded()) {
            SbCompat.clearShieldCache();
        }
    }

    /** 女仆死亡：背包从独立槽里掉出来，内容物跟着背包物品本身走，不会丢。 */
@SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide || !isMaid(entity)) {
            return;
        }
        // 别的模组取消了掉落（墓碑 / 收尸袋 / 保留物品之类）：它只会搬走 event.getDrops() 里的东西，
        // 而我们挂在实体 PersistentData 上的背包根本不在那个列表里，随实体一起消失就等于背包没了。
        // 所以这里自己把背包掉在地上，但不放进 event.getDrops()，避免和墓碑里的东西重复。
        if (event.isCanceled()) {
            MaidBackpackData.of(entity).ifPresent(data -> {
                for (ItemStack stack : data.copyStacks()) {
                    if (stack.isEmpty()) {
                        continue;
                    }
                    ItemEntity drop = new ItemEntity(entity.level(),
                            entity.getX(), entity.getY() + 0.3, entity.getZ(), stack);
                    drop.setDefaultPickUpDelay();
                    entity.level().addFreshEntity(drop);
                }
                data.clearAndDetach();
            });
            return;
        }
        MaidBackpackData.of(entity).ifPresent(data -> {
            for (ItemStack stack : data.copyStacks()) {
                if (!stack.isEmpty()) {
                    ItemEntity drop = new ItemEntity(entity.level(), entity.getX(), entity.getY() + 0.3, entity.getZ(), stack);
                    drop.setDefaultPickUpDelay();
                    event.getDrops().add(drop);
                }
            }
            data.clearAndDetach();
        });
    }

    // ---------- 玩家交互 ----------

    /**
     * 右键女仆：
     * - 手持"女仆背包扣"→ 打开女仆 GUI 的精妙背包标签页；
     * - 手持精妙背包（恰好 1 个）→ 直接装备到空槽（最多 3 个背包）。
     *
     * 背包扣这一支在客户端也要拦：女仆模组的交互是"客户端预测 + 服务端补包"两条腿，
     * 只拦服务端的话，客户端本地那半套换背包预测照样会跑一遍，把她的客户端背包类型改脏。
     */
@SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!isMaid(event.getTarget())) {
            return;
        }
        Player player = event.getEntity();
        ItemStack held = event.getItemStack();
        if (held.isEmpty()) {
            return;
        }
        LivingEntity maid = (LivingEntity) event.getTarget();

        if (held.getItem() == ModItems.MAID_BACKPACK_BUCKLE.get()) {
            // 非主人不许开别人家女仆的背包；两侧都不拦，让原版交互照常走
            if (!isMaidOwner(maid, player)) {
                return;
            }
            if (event.getLevel().isClientSide) {
                // 拦住客户端的换包预测：背包扣不是任何背包物品，女仆不该对她做任何事
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
            if (player instanceof ServerPlayer serverPlayer) {
                if (openMaidPanel(serverPlayer, maid)) {
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.SUCCESS);
                } else {
                    // 打不开面板（没装精妙背包等）也必须取消：不能让交互落到车万女仆的换包逻辑，
                    // 那条路会真把她原有的背包挤下来换成占位档，还把 1 个背包扣吃掉
                    serverPlayer.displayClientMessage(
                            Component.translatable("gui." + MaidSophisticated.MOD_ID + ".need_sb"), true);
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.SUCCESS);
                }
            }
            return;
        }

        if (held.getItem() == ModItems.MAID_BACKPACK_MARKER.get()) {
            // 占位物品生存里正常途径拿不到，但 /give、创造"满遍"标签页能拿到。
            // 女仆模组按各背包类型的 getItem() 建了"物品→背包类型"映射，六档精妙背包共用
            // 这一件占位物品：拿着它右键一个装了自己背包的女仆，会走她的换包流程——
            // 她的包当场被取下塞回玩家背包、背包类型被挤成精妙档（等 onPutOn 兜底才还原），
            // 整个过程还白吃 1 个占位物品。两侧都要拦（换包是"客户端预测 + 服务端补包"
            // 两条腿，逻辑同上面背包扣那支）。
            if (!event.getLevel().isClientSide) {
                // 给它个下文：不然拿着它右键装了自有背包的女仆，物品凭空少 1 还没有任何反馈
                player.displayClientMessage(
                        Component.translatable("gui." + MaidSophisticated.MOD_ID + ".marker_not_equippable"), true);
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (event.getLevel().isClientSide) {
            return;
        }
        if (held.is(net.minecraft.world.item.Items.AIR)) {
            return;
        }

        if (MaidBackpackData.isSophisticatedBackpack(held)) {
            if (!MaidSophisticated.sophisticatedBackpacksLoaded() || held.getCount() != 1) {
                return;
            }
            Optional<MaidBackpackData> optional = MaidBackpackData.of(maid);
            if (optional.isEmpty()) {
                return;
            }
            MaidBackpackData data = optional.get();
            int slot = data.firstEmptySlot();
            if (slot < 0) {
                // 槽位满：不再装备，女仆原有的"送礼"逻辑照常进行
                return;
            }
            ItemStack leftover = data.insertItem(slot, held.copy(), false);
            if (leftover.isEmpty()) {
                held.shrink(1);
                playEquipFeedback(maid);
                creditFirstBackpack(player);
                // 第三个槽也放进去时，顺带结算"三个槽全满"的成就
                creditAllBackpacks(player, data);
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
        }
    }

    /**
     * 打开女仆 GUI 的"精妙背包"标签页（顶部第四个标签 / 背包扣右键都走这里）。
     *
     * @return 真的打开了才返回 true；缺依赖或不是服务端时返回 false，调用方别吞掉原版交互。
     */
    public static boolean openMaidPanel(ServerPlayer player, LivingEntity maid) {
        if (!(maid.level() instanceof ServerLevel) || !MaidSophisticated.touhouLittleMaidLoaded()
                || !MaidSophisticated.sophisticatedBackpacksLoaded()) {
            return false;
        }
        return TlmCompat.openTab(player, maid);
    }

    // ---------- 智能存取 ----------

    // 注：Forge 1.20.1 没有生物拾取物品事件（EntityPickupItemEvent 已移除），
    // "拾取先入背包"由下面的服务器扫描实现：女仆附近掉落物优先入包。

    /**
     * 服务器节流的后台维护：
     * - 每 10 tick：女仆附近掉落物按"女仆自己的物品栏 → 女仆自己的背包 → 精妙背包"顺序收取；
     * - 每 40 tick：女仆手上空着时，按同样顺序搜一把趁手工具塞到手上（"取用自动搜包"）；
     * - 每 40 tick：补发背包外观同步；
     * - 每 600 tick（30 秒，按实体 id 分批错开）：把背包数据的最新快照写回实体。
     *
     * 三种活都没轮到的 tick 直接返回，不再每 tick 遍历女仆表。
     */
@SubscribeEvent
        public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCounter++;
        boolean pickupPass = tickCounter % 10 == 0;
        boolean refillPass = tickCounter % 40 == 13;
        boolean statePass = tickCounter % 40 == 0;
        if (!pickupPass && !refillPass && !statePass) {
            return;
        }
        if (!TlmCompat.loaded() || !MaidSophisticated.sophisticatedBackpacksLoaded()) {
            return;
        }
        // 看门狗到点：确认"可用物品栏补丁"到底有没有真的把背包拼上去（详见下面这个方法）
        checkHookWatchdog(statePass);
        // 快照刷新按 15 批轮换：每次 statePass 只存 1/15 的女仆，一轮 15×40 = 600 tick 转完。
        int saveBand = (tickCounter / 40) % SAVE_BAND_COUNT;
        for (LivingEntity maid : trackedMaidsSnapshot()) {
            if (maid.isRemoved() || !(maid.level() instanceof ServerLevel level)) {
                continue;
            }
            MaidBackpackData data = MaidBackpackData.of(maid).orElse(null);
            if (data == null) {
                continue;
            }
            // 刷新实体上的快照：背包物品的 NBT（contentsUuid、颜色、设置）是在运行中才写进物品里的，
            // 不刷新的话世界保存写下去的还是旧快照，重进游戏背包里的东西会丢。
            // 内容变化时 onContentsChanged 会立刻存一次，这里只是兜底，所以 30 秒一次就够。
            if (statePass && Math.floorMod(maid.getId(), SAVE_BAND_COUNT) == saveBand
                    && !MaidBackpackData.isEmptyHandler(data)) {
                data.saveToEntity();
            }
            // 兜底补发背包状态：女仆刚刷出来时首个同步包可能早于客户端建好实体而丢掉，
            // 每两秒补一次，客户端最多两秒就能把背上的外观纠正过来。
            // 一个背包都没有、而且"空"这个状态也已经同步过的女仆直接跳过：
            // 大基地里多数女仆根本没背包，空包广播发一次就够，不该每两秒全服重来一遍。
            // 从有到无 / 从无到有都会走 onContentsChanged 的即时同步，这里只是补丢包。
            if (statePass && !(MaidBackpackData.isEmptyHandler(data) && data.isEmptyStateSynced())) {
                ModNetwork.syncMaidBackpack(maid, data);
            }
            // 没有精妙背包的女仆不归我们管，交给她自己的 AI
            if (MaidBackpackData.isEmptyHandler(data)) {
                continue;
            }
            // 女仆自己的"拾取"开关关着，或者她正坐着休息，就别替她捡东西
            if (pickupPass && TlmCompat.isPickupEnabled(maid) && !TlmCompat.isSitting(maid)) {
                pickupNearbyItems(level, maid, data);
            }
            if (refillPass) {
                refillMaidTool(maid, data);
            }
        }
    }

    /**
     * 收取附近掉落物，顺序与玩家直觉一致：
     * 1. 女仆自己的物品栏（她装了车万女仆的背包时，背包扩容出来的格子也算自己的）；
     * 2. 女仆自己的背包格（就是上一步里扩容的那些，没背包就没有这一步）；
     * 3. 最后才进精妙背包，按槽位顺序一个个塞。
     */
    private static void pickupNearbyItems(ServerLevel level, LivingEntity maid, MaidBackpackData data) {
        List<net.minecraft.world.entity.Entity> items = level.getEntities(maid, maid.getBoundingBox().inflate(2.2),
                e -> e instanceof ItemEntity item && !item.isRemoved() && !item.hasPickUpDelay());
        for (net.minecraft.world.entity.Entity entity : items) {
            if (!(entity instanceof ItemEntity itemEntity) || itemEntity.isRemoved()) {
                continue;
            }
            // 女仆模组自己的拾取规则（配置黑名单等）：她本来就不能捡的东西，我们也不替她捡
            if (!TlmCompat.canMaidPickupItem(itemEntity.getItem())) {
                continue;
            }
            ItemStack before = itemEntity.getItem().copy();
            ItemStack remaining = before.copy();
            // 1 + 2：女仆自己的物品栏与她自己背包的格子
            remaining = TlmCompat.insertIntoMaidInventory(maid, remaining);
            // 3：精妙背包
            for (int i = 0; i < MaidBackpackData.SLOTS && !remaining.isEmpty(); i++) {
                ItemStack backpack = data.getStackInSlot(i);
                if (backpack.isEmpty()) {
                    continue;
                }
                remaining = SbCompat.insertIntoBackpack(backpack, remaining);
            }
            if (ItemStack.matches(before, remaining)) {
                continue;
            }
            int taken = before.getCount() - remaining.getCount();
            if (taken > 0) {
                // 和女仆自己弯腰捡一样记一次"拾取"，经验与拾取动画都按原版走
                maid.take(itemEntity, taken);
                TlmCompat.playPickupSound(maid);
                TlmCompat.postPickupResult(maid, before, taken);
            }
            if (remaining.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setItem(remaining);
            }
        }
    }

    /** 手上空着时补一把工具：先掏女仆自己的背包格，再掏精妙背包。 */
    private static void refillMaidTool(LivingEntity maid, MaidBackpackData data) {
        if (!maid.getMainHandItem().isEmpty()) {
            return;
        }
        ItemStack own = TlmCompat.takeToolFromMaidInventory(maid);
        if (!own.isEmpty()) {
            maid.setItemInHand(InteractionHand.MAIN_HAND, own);
            return;
        }
        for (int i = 0; i < MaidBackpackData.SLOTS; i++) {
            ItemStack backpack = data.getStackInSlot(i);
            if (backpack.isEmpty()) {
                continue;
            }
            ItemStack tool = SbCompat.extractToolForMaid(backpack);
            if (!tool.isEmpty()) {
                maid.setItemInHand(InteractionHand.MAIN_HAND, tool);
                return;
            }
        }
    }

    /** 是不是"趁手的东西"：斧 / 镐 / 锹 / 锄 / 剑 / 盾。拾取与取用规则共用。 */
    public static boolean isToolOrWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        var item = stack.getItem();
        return item instanceof net.minecraft.world.item.AxeItem
                || item instanceof net.minecraft.world.item.PickaxeItem
                || item instanceof net.minecraft.world.item.ShovelItem
                || item instanceof net.minecraft.world.item.HoeItem
                || item instanceof net.minecraft.world.item.SwordItem
                || item == net.minecraft.world.item.Items.SHIELD;
    }

    // ---------- 反馈与进度 ----------

    /** 装备成功：8 个光点粒子绕一圈 + 1 声皮革装备音效。 */
    public static void playEquipFeedback(LivingEntity maid) {
        Level level = maid.level();
        if (level instanceof ServerLevel serverLevel) {
            double cx = maid.getX();
            double cy = maid.getY() + 0.9;
            double cz = maid.getZ();
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI / 4 * i;
                serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                        cx + Math.cos(angle) * 0.55, cy, cz + Math.sin(angle) * 0.55,
                        1, 0.0, 0.02, 0.0, 0.01);
            }
            serverLevel.playSound(null, cx, cy, cz, SoundEvents.ARMOR_EQUIP_LEATHER,
                    SoundSource.PLAYERS, 0.7f, 1.0f);
        }
    }

    /** 首次给女仆背上精妙背包 → 弹成就"这就很妙"（原版语义：每个玩家只弹一次，重装不会再弹）。 */
    public static void creditFirstBackpack(@Nullable net.minecraft.world.entity.Entity actor) {
        if (actor instanceof ServerPlayer player && MaidSophisticated.sophisticatedBackpacksLoaded()) {
            FirstMaidBackpackTrigger.INSTANCE.trigger(player);
        }
    }

    /**
     * 女仆三个专属背包槽全部装满精妙背包 → 弹成就"我什么都不缺了"。
     *
     * <p>调用点只在"往槽里放东西之后"，所以拆包、换包都不会误触发；
     * 成就本身按原版语义每个玩家只发放一次，之后拆装多少次都不会再弹。</p>
     *
     * @param actor 这次放包的人（服务端玩家）；不是玩家（比如自动化模组）就不发
     * @param data  被放包的那只女仆的背包数据
     */
    public static void creditAllBackpacks(@Nullable net.minecraft.world.entity.Entity actor,
                                          @Nullable MaidBackpackData data) {
        if (!(actor instanceof ServerPlayer player) || data == null
                || !MaidSophisticated.sophisticatedBackpacksLoaded()) {
            return;
        }
        for (int i = 0; i < MaidBackpackData.SLOTS; i++) {
            // isSophisticatedBackpack 对空格子返回 false，正好一并把"有空槽"挡掉
            if (!MaidBackpackData.isSophisticatedBackpack(data.getStackInSlot(i))) {
                return;
            }
        }
        AllMaidBackpacksTrigger.INSTANCE.trigger(player);
    }
}
