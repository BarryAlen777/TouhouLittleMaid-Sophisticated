package dev.barryalen.maidsophisticated;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模组自身的网络包（NeoForge 1.21.1 的 CustomPacketPayload 体系）：
 * - S2C：女仆背包槽内容同步（背部渲染与标签页要用）；
 * - C2S：打开标签页、切换展示背包、滚动内容窗口、从标签页跳原生界面。
 *
 * <p>旧的 SimpleChannel 已删除：每种消息现在是一个独立 payload 类型，
 * 编解码用 StreamCodec；方向由注册（playToClient/playToServer）决定，
 * 恶意客户端把 S2C 包发给服务端会在协议层直接被拒，不再需要手写方向校验。</p>
 */
public final class ModNetwork {
    /**
     * 线上协议版本。6：背包同步包里的 ItemStack 改用 OPTIONAL_STREAM_CODEC
     * （1.21.1 的严格 STREAM_CODEC 拒绝空堆，空槽会在 Netty 编码阶段把玩家踢下线）。
     * 格式变了，新旧客户端混连必须由 NeoForge 在握手阶段拒绝，不能按旧格式解析。
     */
    private static final String PROTOCOL = "6";
    /** 女仆身上的精妙背包槽数量上限（和 MaidBackpackData.SLOTS 保持一致）。 */
    private static final int MAX_MAID_BACKPACKS = MaidBackpackData.SLOTS;
    /** 标签页一屏最多同步多少格；正常是 8×5=40，这里留些余量。 */
    private static final int MAX_CONTENT_STACKS = 64;
    /** 背包总格数 / 滚动行的合理上限：只用来挡住伪造包里的天文数字，不参与正常逻辑。 */
    private static final int MAX_CONTENT_SIZE = 8192;
    public static final int MAX_SCROLL_ROW = 4096;
    /** 背包状态同步包编码失败的日志只打第一次：兜底广播每两秒一次，刷屏会淹了日志。 */
    private static final AtomicBoolean SYNC_FAILED_LOGGED = new AtomicBoolean(false);
    /** 标签页内容同步包发送失败的日志同样只打第一次。 */
    private static final AtomicBoolean CONTENT_SYNC_FAILED_LOGGED = new AtomicBoolean(false);

    private ModNetwork() {}

    /** 模组总线上的注册入口（@Mod 构造器里 addListener 进来）。 */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL);
        registrar.playToClient(SyncMaidBackpack.TYPE, SyncMaidBackpack.CODEC,
                ModNetwork::handleSyncMaidBackpack);
        registrar.playToClient(SyncBackpackContent.TYPE, SyncBackpackContent.CODEC,
                ModNetwork::handleSyncBackpackContent);
        registrar.playToServer(OpenSophTab.TYPE, OpenSophTab.CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> msg.handle(ctx)));
        registrar.playToServer(SophSelect.TYPE, SophSelect.CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> msg.handle(ctx)));
        registrar.playToServer(SophScroll.TYPE, SophScroll.CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> msg.handle(ctx)));
        registrar.playToServer(OpenNativeFromTab.TYPE, OpenNativeFromTab.CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> msg.handle(ctx)));
        registrar.playToServer(ToggleMainBackpack.TYPE, ToggleMainBackpack.CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> msg.handle(ctx)));
        registrar.playToServer(SophSearch.TYPE, SophSearch.CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> msg.handle(ctx)));
        registrar.playToServer(SophSort.TYPE, SophSort.CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> msg.handle(ctx)));
        registrar.playToServer(RequestMaidState.TYPE, RequestMaidState.CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> msg.handle(ctx)));
    }

    /**
     * S2C 收包入口：lambda 里才引用纯客户端类 MaidSyncHandler。
     *
     * <p>注册表在专用服务器上也存这两个 handler（存 lambda 本身不会加载客户端类），
     * 但方向由协议层保证，服务器永远收不到这两个包，因此永远执行不到那行。
     * 直接写成方法引用反而会让 ModNetwork 类在服务器加载时就解析到客户端类，
     * 所以这里刻意用"方法体里调用"的形式。</p>
     */
    private static void handleSyncMaidBackpack(SyncMaidBackpack msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> dev.barryalen.maidsophisticated.client.MaidSyncHandler
                .handleMaidBackpack(msg.entityId(), msg.stacks(), msg.showSophisticated(), msg.mainSlot()));
    }

    private static void handleSyncBackpackContent(SyncBackpackContent msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> dev.barryalen.maidsophisticated.client.MaidSyncHandler
                .handleBackpackContent(msg.windowId(), msg.selected(), msg.scroll(), msg.stacks(), msg.total()));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MaidSophisticated.MOD_ID, path);
    }

    // 收包统一走注册表：{@code enqueueWork} 会把工作丢到主线程，NeoForge 自动回执 handled。

    /** 客户端标签页按钮 → 请求服务端打开容器。 */
    public static void sendOpenTab(int maidEntityId) {
        PacketDistributor.sendToServer(new OpenSophTab(maidEntityId));
    }

    /** 客户端切换展示背包。 */
    public static void sendSelect(int index) {
        PacketDistributor.sendToServer(new SophSelect(index));
    }

    /** 客户端滚动内容窗口。 */
    public static void sendScroll(int row) {
        PacketDistributor.sendToServer(new SophScroll(row));
    }

    /** 客户端点"原生界面"按钮。 */
    public static void sendOpenNative() {
        PacketDistributor.sendToServer(new OpenNativeFromTab());
    }

    /** 客户端点"主背包显示"开关。 */
    public static void sendToggleMainBackpack() {
        PacketDistributor.sendToServer(new ToggleMainBackpack());
    }

    /** 客户端在工具栏里输入搜索词。 */
    public static void sendSearch(String text) {
        PacketDistributor.sendToServer(new SophSearch(text == null ? "" : text));
    }

    /** 客户端点"整理 / 排序"。 */
    public static void sendSort(int mode) {
        PacketDistributor.sendToServer(new SophSort(mode));
    }

    /**
     * 客户端发现某只女仆"背上是空的、也不知道该显示什么"时，主动要一次状态。
     * 兜底用：就算周期性推送因为时序问题错过了，客户端也能自己把外观补上。
     */
    public static void sendRequestMaidState(int maidEntityId) {
        PacketDistributor.sendToServer(new RequestMaidState(maidEntityId));
    }

    /**
     * 把女仆背包槽内容 + 主背包显示开关同步给所有看得见这个女仆的客户端。
     *
     * <p>防崩约定（与 {@link MaidBackpackData#saveToEntity()} 对称）：物品走
     * {@code ItemStack.OPTIONAL_STREAM_CODEC}（空槽是合法编码，不会再踢人）；
     * 组件编码失败仍会同步抛在调用方（服务端线程），
     * 调用点还包括进世界事件和每两秒的兜底广播。这里兜住：失败只是这次外观没同步上，
     * 下一轮兜底广播会补发；"空状态已同步"的账也就不记，避免把没发出去的当成发过了。</p>
     */
    public static void syncMaidBackpack(LivingEntity maid, MaidBackpackData data) {
        try {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(maid,
                    new SyncMaidBackpack(maid.getId(), data.copyStacks(), data.isShowSophisticated(),
                            data.rawMainSlot()));
            data.markEmptyStateSynced(MaidBackpackData.isEmptyHandler(data));
        } catch (Exception | LinkageError e) {
            logSyncFailure(e);
        }
    }

    /** 玩家刚开始追踪某个女仆时补发一次数据（否则后靠近的玩家看不到背上背包）。 */
    public static void syncMaidBackpackTo(ServerPlayer player, LivingEntity maid, MaidBackpackData data) {
        try {
            PacketDistributor.sendToPlayer(player,
                    new SyncMaidBackpack(maid.getId(), data.copyStacks(), data.isShowSophisticated(),
                            data.rawMainSlot()));
            data.markEmptyStateSynced(MaidBackpackData.isEmptyHandler(data));
        } catch (Exception | LinkageError e) {
            logSyncFailure(e);
        }
    }

    /** 同步包编码失败只记一次日志：兜底广播每两秒撞一次同样的异常，刷屏会淹了日志。 */
    private static void logSyncFailure(Throwable e) {
        if (SYNC_FAILED_LOGGED.compareAndSet(false, true)) {
            MaidSophisticated.LOGGER.warn(
                    "女仆背包状态同步包编码失败，外观同步顺延（后续同类错误不再重复打日志）：", e);
        }
    }

    /**
     * 标签页滚动/切换背包后，把"当前可见的那一屏"整屏推给客户端。
     *
     * 原版容器同步只发送"和上次不同"的槽位，而滚动会改变槽位→背包下标的映射；
     * 客户端清空旧显示后就再也收不到那些内容没变的槽位，于是物品乱排/消失。
     * 全量推送这一屏是唯一可靠的解法。
     */
    public static void sendBackpackContent(ServerPlayer player, int windowId, int selected, int scroll,
                                           List<ItemStack> stacks, int total) {
        // 兜底只拦"同步阶段"的异常（如玩家已进入断开流程）；Netty 编码阶段的异常
        // 在这里拦不住，靠 OPTIONAL_STREAM_CODEC 从根上避免（见 SyncBackpackContent.encode）。
        try {
            PacketDistributor.sendToPlayer(player,
                    new SyncBackpackContent(windowId, selected, scroll, stacks, total));
        } catch (Exception | LinkageError e) {
            if (CONTENT_SYNC_FAILED_LOGGED.compareAndSet(false, true)) {
                MaidSophisticated.LOGGER.warn(
                        "背包内容同步包发送失败，本页显示顺延（后续同类错误不再重复打日志）：", e);
            }
        }
    }

    // ---------- S2C ----------

    /** 女仆背包槽整体状态（背部渲染、标签页头部都要用）。 */
    public record SyncMaidBackpack(int entityId, List<ItemStack> stacks, boolean showSophisticated,
                                   int mainSlot) implements CustomPacketPayload {
        public static final Type<SyncMaidBackpack> TYPE = new Type<>(id("sync_maid_backpack"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncMaidBackpack> CODEC =
                StreamCodec.of(SyncMaidBackpack::encode, SyncMaidBackpack::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buf, SyncMaidBackpack msg) {
            buf.writeVarInt(msg.entityId);
            buf.writeBoolean(msg.showSophisticated);
            buf.writeInt(msg.mainSlot);
            // 只发真正的 3 格，避免调用方传进来一个超长列表把包撑大
            int size = Math.min(msg.stacks.size(), MAX_MAID_BACKPACKS);
            buf.writeVarInt(size);
            for (int i = 0; i < size; i++) {
                // OPTIONAL：空槽写一个 false 字节。严格 STREAM_CODEC 遇空堆直接抛
                // "Empty ItemStack not allowed"，且是在 Netty 编码阶段，调用方兜不住。
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, msg.stacks.get(i));
            }
        }

        private static SyncMaidBackpack decode(RegistryFriendlyByteBuf buf) {
            int id = buf.readVarInt();
            boolean showSophisticated = buf.readBoolean();
            int mainSlot = Math.max(-1, Math.min(buf.readInt(), MAX_MAID_BACKPACKS - 1));
            int count = buf.readVarInt();
            // count 是对方给的：不校验就直接 new ArrayList<>(count)，
            // 一个伪造的 2^31-1 就能把内存吃穿。正常的包最多 3 格。
            if (count < 0 || count > MAX_MAID_BACKPACKS) {
                throw new IllegalArgumentException("maid_sophisticated: bad maid backpack sync size " + count);
            }
            List<ItemStack> stacks = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                stacks.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
            }
            return new SyncMaidBackpack(id, stacks, showSophisticated, mainSlot);
        }
    }

    /** 标签页内容全量同步：窗口 id、当前背包下标、滚动行、可见的整屏物品、筛选后的总格数。 */
    public record SyncBackpackContent(int windowId, int selected, int scroll, List<ItemStack> stacks,
                                      int total) implements CustomPacketPayload {
        public static final Type<SyncBackpackContent> TYPE = new Type<>(id("sync_backpack_content"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncBackpackContent> CODEC =
                StreamCodec.of(SyncBackpackContent::encode, SyncBackpackContent::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buf, SyncBackpackContent msg) {
            buf.writeVarInt(msg.windowId);
            buf.writeVarInt(msg.selected);
            buf.writeVarInt(msg.scroll);
            buf.writeVarInt(msg.total);
            int size = Math.min(msg.stacks.size(), MAX_CONTENT_STACKS);
            buf.writeVarInt(size);
            for (int i = 0; i < size; i++) {
                // 同 SyncMaidBackpack：标签页整屏 40 格里必然有空槽
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, msg.stacks.get(i));
            }
        }

        private static SyncBackpackContent decode(RegistryFriendlyByteBuf buf) {
            int windowId = buf.readVarInt();
            int selected = Math.max(0, Math.min(buf.readVarInt(), MAX_MAID_BACKPACKS - 1));
            int scroll = Math.max(0, Math.min(buf.readVarInt(), MAX_SCROLL_ROW));
            int total = buf.readVarInt();
            int count = buf.readVarInt();
            // 同上：列表长度和总格数都必须有上限，否则伪造包可以直接打爆内存
            if (count < 0 || count > MAX_CONTENT_STACKS) {
                throw new IllegalArgumentException("maid_sophisticated: bad backpack content sync size " + count);
            }
            if (total < 0 || total > MAX_CONTENT_SIZE) {
                throw new IllegalArgumentException("maid_sophisticated: bad backpack content total " + total);
            }
            List<ItemStack> stacks = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                stacks.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
            }
            return new SyncBackpackContent(windowId, selected, scroll, stacks, total);
        }
    }

    // ---------- C2S ----------

    /** 打开女仆 GUI 里的"精妙背包"标签页（服务端校验后开容器）。 */
    public record OpenSophTab(int maidEntityId) implements CustomPacketPayload {
        public static final Type<OpenSophTab> TYPE = new Type<>(id("open_soph_tab"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenSophTab> CODEC =
                StreamCodec.of((buf, msg) -> buf.writeVarInt(msg.maidEntityId), buf -> new OpenSophTab(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        void handle(IPayloadContext ctx) {
            if (!(ctx.player() instanceof ServerPlayer player) || !MaidSophisticated.sophisticatedBackpacksLoaded()) {
                return;
            }
            if (!(player.level().getEntity(maidEntityId) instanceof LivingEntity maid) || !Events.isMaid(maid)) {
                return;
            }
            // 和容器 stillValid 用同一个距离（8 格），太远就别给开界面的机会
            if (!maid.isRemoved() && Events.isMaidOwner(maid, player) && maid.distanceToSqr(player) < 64.0) {
                Events.openMaidPanel(player, maid);
            }
        }
    }

    /** 标签页内：切换展示哪个背包槽。 */
    public record SophSelect(int index) implements CustomPacketPayload {
        public static final Type<SophSelect> TYPE = new Type<>(id("soph_select"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SophSelect> CODEC =
                StreamCodec.of((buf, msg) -> buf.writeVarInt(msg.index), buf -> new SophSelect(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        void handle(IPayloadContext ctx) {
            if (ctx.player() instanceof ServerPlayer player && MaidSophisticated.touhouLittleMaidLoaded()) {
                TlmCompat.handleSelect(player, index);
            }
        }
    }

    /** 标签页内：滚动内容窗口。 */
    public record SophScroll(int row) implements CustomPacketPayload {
        public static final Type<SophScroll> TYPE = new Type<>(id("soph_scroll"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SophScroll> CODEC =
                StreamCodec.of((buf, msg) -> buf.writeVarInt(msg.row), buf -> new SophScroll(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        void handle(IPayloadContext ctx) {
            if (ctx.player() instanceof ServerPlayer player && MaidSophisticated.touhouLittleMaidLoaded()) {
                TlmCompat.handleScroll(player, row);
            }
        }
    }

    /** 标签页内：用精妙背包原生界面打开当前展示的背包。 */
    public record OpenNativeFromTab() implements CustomPacketPayload {
        public static final Type<OpenNativeFromTab> TYPE = new Type<>(id("open_native_from_tab"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenNativeFromTab> CODEC =
                StreamCodec.of((buf, msg) -> { }, buf -> new OpenNativeFromTab());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        void handle(IPayloadContext ctx) {
            if (!(ctx.player() instanceof ServerPlayer player) || !MaidSophisticated.sophisticatedBackpacksLoaded()) {
                return;
            }
            if (MaidSophisticated.touhouLittleMaidLoaded()) {
                TlmCompat.handleOpenNative(player);
            }
        }
    }

    /** 标签页内：切换"主背包显示"（女仆自己的背包 ↔ 精妙背包）。 */
    public record ToggleMainBackpack() implements CustomPacketPayload {
        public static final Type<ToggleMainBackpack> TYPE = new Type<>(id("toggle_main_backpack"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ToggleMainBackpack> CODEC =
                StreamCodec.of((buf, msg) -> { }, buf -> new ToggleMainBackpack());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        void handle(IPayloadContext ctx) {
            if (ctx.player() instanceof ServerPlayer player && MaidSophisticated.touhouLittleMaidLoaded()) {
                TlmCompat.handleToggleMainBackpack(player);
            }
        }
    }

    /** 标签页工具栏：搜索词。 */
    public record SophSearch(String text) implements CustomPacketPayload {
        public static final Type<SophSearch> TYPE = new Type<>(id("soph_search"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SophSearch> CODEC =
                StreamCodec.of((buf, msg) -> buf.writeUtf(msg.text, 64),
                        buf -> new SophSearch(buf.readUtf(64)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        void handle(IPayloadContext ctx) {
            if (ctx.player() instanceof ServerPlayer player && MaidSophisticated.touhouLittleMaidLoaded()) {
                TlmCompat.handleSearch(player, text);
            }
        }
    }

    /** 标签页工具栏：整理 / 排序。 */
    public record SophSort(int mode) implements CustomPacketPayload {
        public static final Type<SophSort> TYPE = new Type<>(id("soph_sort"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SophSort> CODEC =
                StreamCodec.of((buf, msg) -> buf.writeVarInt(msg.mode), buf -> new SophSort(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        void handle(IPayloadContext ctx) {
            if (ctx.player() instanceof ServerPlayer player && MaidSophisticated.touhouLittleMaidLoaded()) {
                TlmCompat.handleSort(player, mode);
            }
        }
    }

    /**
     * 客户端主动请求一次女仆背包状态（背上的外观用）。
     * 只回给"正在看着这只女仆的主人"，不会泄漏给别人。
     */
    public record RequestMaidState(int maidEntityId) implements CustomPacketPayload {
        public static final Type<RequestMaidState> TYPE = new Type<>(id("request_maid_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestMaidState> CODEC =
                StreamCodec.of((buf, msg) -> buf.writeVarInt(msg.maidEntityId),
                        buf -> new RequestMaidState(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        void handle(IPayloadContext ctx) {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.level().getEntity(maidEntityId) instanceof LivingEntity maid) || !Events.isMaid(maid)) {
                return;
            }
            if (!Events.isMaidOwner(maid, player) || maid.distanceToSqr(player) > 64.0D) {
                return;
            }
            MaidBackpackData.of(maid).ifPresent(data -> syncMaidBackpackTo(player, maid, data));
        }
    }
}
