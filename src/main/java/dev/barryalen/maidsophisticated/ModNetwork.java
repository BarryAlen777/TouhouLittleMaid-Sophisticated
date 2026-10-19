package dev.barryalen.maidsophisticated;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 模组自身的网络包：
 * - S2C：女仆背包槽内容同步（背部渲染与标签页要用）；
 * - C2S：打开标签页、切换展示背包、滚动内容窗口、从标签页跳原生界面。
 */
public final class ModNetwork {
    private static final String PROTOCOL = "5";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MaidSophisticated.MOD_ID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private ModNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, SyncMaidBackpack.class, SyncMaidBackpack::encode, SyncMaidBackpack::decode,
                (msg, ctx) -> handle(ctx, msg::handle));
        CHANNEL.registerMessage(1, OpenSophTab.class, OpenSophTab::encode, OpenSophTab::decode,
                (msg, ctx) -> handle(ctx, () -> msg.handle(ctx)));
        CHANNEL.registerMessage(2, SophSelect.class, SophSelect::encode, SophSelect::decode,
                (msg, ctx) -> handle(ctx, () -> msg.handle(ctx)));
        CHANNEL.registerMessage(3, SophScroll.class, SophScroll::encode, SophScroll::decode,
                (msg, ctx) -> handle(ctx, () -> msg.handle(ctx)));
        CHANNEL.registerMessage(4, OpenNativeFromTab.class, OpenNativeFromTab::encode, OpenNativeFromTab::decode,
                (msg, ctx) -> handle(ctx, () -> msg.handle(ctx)));
        CHANNEL.registerMessage(5, SyncBackpackContent.class, SyncBackpackContent::encode,
                SyncBackpackContent::decode, (msg, ctx) -> handle(ctx, msg::handle));
        CHANNEL.registerMessage(6, ToggleMainBackpack.class, ToggleMainBackpack::encode,
                ToggleMainBackpack::decode, (msg, ctx) -> handle(ctx, () -> msg.handle(ctx)));
        CHANNEL.registerMessage(7, SophSearch.class, SophSearch::encode,
                SophSearch::decode, (msg, ctx) -> handle(ctx, () -> msg.handle(ctx)));
        CHANNEL.registerMessage(8, SophSort.class, SophSort::encode,
                SophSort::decode, (msg, ctx) -> handle(ctx, () -> msg.handle(ctx)));
        CHANNEL.registerMessage(9, RequestMaidState.class, RequestMaidState::encode,
                RequestMaidState::decode, (msg, ctx) -> handle(ctx, () -> msg.handle(ctx)));
    }

    /**
     * 统一的收包入口。
     *
     * {@code enqueueWork} 本身就会把工作丢到"该干活的那条线程"（客户端是主渲染线程，
     * 服务端是主服务器线程），所以这里不需要再自己比对线程组——上个版本在多出来的
     * 线程组判断里把服务端发来的状态包全丢了，女仆背上于是一直没有精妙背包数据。
     *
     * 关键还有最后那行 {@code setPacketHandled(true)}：不调用它，Forge 会把这个包当成
     * "未知自定义包" 再交给原版处理一遍，日志里就会刷
     * {@code Unknown custom packet identifier: maid_sophisticated:main}。
     */
    private static void handle(Supplier<NetworkEvent.Context> supplier, Runnable work) {
        NetworkEvent.Context ctx = supplier.get();
        ctx.enqueueWork(work);
        ctx.setPacketHandled(true);
    }

    /** 客户端标签页按钮 → 请求服务端打开容器。 */
    public static void sendOpenTab(int maidEntityId) {
        CHANNEL.sendToServer(new OpenSophTab(maidEntityId));
    }

    /** 客户端切换展示背包。 */
    public static void sendSelect(int index) {
        CHANNEL.sendToServer(new SophSelect(index));
    }

    /** 客户端滚动内容窗口。 */
    public static void sendScroll(int row) {
        CHANNEL.sendToServer(new SophScroll(row));
    }

    /** 客户端点"原生界面"按钮。 */
    public static void sendOpenNative() {
        CHANNEL.sendToServer(new OpenNativeFromTab());
    }

    /** 客户端点"主背包显示"开关。 */
    public static void sendToggleMainBackpack() {
        CHANNEL.sendToServer(new ToggleMainBackpack());
    }

    /** 客户端在工具栏里输入搜索词。 */
    public static void sendSearch(String text) {
        CHANNEL.sendToServer(new SophSearch(text == null ? "" : text));
    }

    /** 客户端点"整理 / 排序"。 */
    public static void sendSort(int mode) {
        CHANNEL.sendToServer(new SophSort(mode));
    }

    /**
     * 客户端发现某只女仆"背上是空的、也不知道该显示什么"时，主动要一次状态。
     * 兜底用：就算周期性推送因为时序问题错过了，客户端也能自己把外观补上。
     */
    public static void sendRequestMaidState(int maidEntityId) {
        CHANNEL.sendToServer(new RequestMaidState(maidEntityId));
    }

    /** 把女仆背包槽内容 + 主背包显示开关同步给所有看得见这个女仆的客户端。 */
    public static void syncMaidBackpack(LivingEntity maid, MaidBackpackData data) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> maid),
                new SyncMaidBackpack(maid.getId(), data.copyStacks(), data.isShowSophisticated(),
                        data.rawMainSlot()));
    }

    /** 玩家刚开始追踪某个女仆时补发一次数据（否则后靠近的玩家看不到背上背包）。 */
    public static void syncMaidBackpackTo(ServerPlayer player, LivingEntity maid, MaidBackpackData data) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncMaidBackpack(maid.getId(), data.copyStacks(), data.isShowSophisticated(),
                        data.rawMainSlot()));
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
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncBackpackContent(windowId, selected, scroll, stacks, total));
    }

    // ---------- S2C ----------

    public static class SyncMaidBackpack {
        private final int entityId;
        private final List<ItemStack> stacks;
        private final boolean showSophisticated;
        /** 背上显示第几格（-1 = 没选过，客户端自己按"第一个有包的格子"算）。 */
        private final int mainSlot;

        public SyncMaidBackpack(int entityId, List<ItemStack> stacks, boolean showSophisticated, int mainSlot) {
            this.entityId = entityId;
            this.stacks = stacks;
            this.showSophisticated = showSophisticated;
            this.mainSlot = mainSlot;
        }

        static void encode(SyncMaidBackpack msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.entityId);
            buf.writeBoolean(msg.showSophisticated);
            buf.writeInt(msg.mainSlot);
            buf.writeVarInt(msg.stacks.size());
            for (ItemStack stack : msg.stacks) {
                buf.writeItemStack(stack, false);
            }
        }

        static SyncMaidBackpack decode(FriendlyByteBuf buf) {
            int id = buf.readVarInt();
            boolean showSophisticated = buf.readBoolean();
            int mainSlot = buf.readInt();
            int count = buf.readVarInt();
            List<ItemStack> stacks = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                stacks.add(buf.readItem());
            }
            return new SyncMaidBackpack(id, stacks, showSophisticated, mainSlot);
        }

        void handle() {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            // 缓存给背部渲染用（不依赖实体 capability 是否已经建好）
            MaidBackpackView.update(entityId, stacks, showSophisticated, mainSlot);
            net.minecraft.world.entity.Entity entity = mc.level.getEntity(entityId);
            MaidBackpackData.of(entity).ifPresent(data -> data.loadRemote(stacks, showSophisticated, mainSlot));
            MaidBackpackView.logFirstReception(entityId, stacks, showSophisticated, mainSlot);
        }
    }

    /** 标签页内容全量同步：窗口 id、当前背包下标、滚动行、可见的整屏物品、筛选后的总格数。 */
    public static class SyncBackpackContent {
        private final int windowId;
        private final int selected;
        private final int scroll;
        private final List<ItemStack> stacks;
        private final int total;

        public SyncBackpackContent(int windowId, int selected, int scroll, List<ItemStack> stacks, int total) {
            this.windowId = windowId;
            this.selected = selected;
            this.scroll = scroll;
            this.stacks = stacks;
            this.total = total;
        }

        static void encode(SyncBackpackContent msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.windowId);
            buf.writeVarInt(msg.selected);
            buf.writeVarInt(msg.scroll);
            buf.writeVarInt(msg.total);
            buf.writeVarInt(msg.stacks.size());
            for (ItemStack stack : msg.stacks) {
                buf.writeItemStack(stack, false);
            }
        }

        static SyncBackpackContent decode(FriendlyByteBuf buf) {
            int windowId = buf.readVarInt();
            int selected = buf.readVarInt();
            int scroll = buf.readVarInt();
            int total = buf.readVarInt();
            int count = buf.readVarInt();
            List<ItemStack> stacks = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                stacks.add(buf.readItem());
            }
            return new SyncBackpackContent(windowId, selected, scroll, stacks, total);
        }

        void handle() {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) {
                return;
            }
            if (mc.player.containerMenu instanceof dev.barryalen.maidsophisticated.gui.MaidSophTabContainer menu
                    && menu.containerId == windowId) {
                menu.applyContentSync(selected, scroll, stacks, total);
            }
        }
    }

    // ---------- C2S ----------

    /** 打开女仆 GUI 里的"精妙背包"标签页（服务端校验后开容器）。 */
    public static class OpenSophTab {
        private final int maidEntityId;

        OpenSophTab(int maidEntityId) {
            this.maidEntityId = maidEntityId;
        }

        static void encode(OpenSophTab msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.maidEntityId);
        }

        static OpenSophTab decode(FriendlyByteBuf buf) {
            return new OpenSophTab(buf.readVarInt());
        }

        void handle(Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context ctx = contextSupplier.get();
            ServerPlayer player = ctx.getSender();
            if (player == null || !MaidSophisticated.sophisticatedBackpacksLoaded()) {
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
    public static class SophSelect {
        private final int index;

        SophSelect(int index) {
            this.index = index;
        }

        static void encode(SophSelect msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.index);
        }

        static SophSelect decode(FriendlyByteBuf buf) {
            return new SophSelect(buf.readVarInt());
        }

        void handle(Supplier<NetworkEvent.Context> contextSupplier) {
            ServerPlayer player = contextSupplier.get().getSender();
            if (player != null && MaidSophisticated.touhouLittleMaidLoaded()) {
                TlmCompat.handleSelect(player, index);
            }
        }
    }

    /** 标签页内：滚动内容窗口。 */
    public static class SophScroll {
        private final int row;

        SophScroll(int row) {
            this.row = row;
        }

        static void encode(SophScroll msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.row);
        }

        static SophScroll decode(FriendlyByteBuf buf) {
            return new SophScroll(buf.readVarInt());
        }

        void handle(Supplier<NetworkEvent.Context> contextSupplier) {
            ServerPlayer player = contextSupplier.get().getSender();
            if (player != null && MaidSophisticated.touhouLittleMaidLoaded()) {
                TlmCompat.handleScroll(player, row);
            }
        }
    }

    /** 标签页内：用精妙背包原生界面打开当前展示的背包。 */
    public static class OpenNativeFromTab {
        OpenNativeFromTab() {}

        static void encode(OpenNativeFromTab msg, FriendlyByteBuf buf) {
        }

        static OpenNativeFromTab decode(FriendlyByteBuf buf) {
            return new OpenNativeFromTab();
        }

        void handle(Supplier<NetworkEvent.Context> contextSupplier) {
            ServerPlayer player = contextSupplier.get().getSender();
            if (player == null || !MaidSophisticated.sophisticatedBackpacksLoaded()) {
                return;
            }
            if (MaidSophisticated.touhouLittleMaidLoaded()) {
                TlmCompat.handleOpenNative(player);
            }
        }
    }

    /** 标签页内：切换"主背包显示"（女仆自己的背包 ↔ 精妙背包）。 */
    public static class ToggleMainBackpack {
        ToggleMainBackpack() {}

        static void encode(ToggleMainBackpack msg, FriendlyByteBuf buf) {
        }

        static ToggleMainBackpack decode(FriendlyByteBuf buf) {
            return new ToggleMainBackpack();
        }

        void handle(Supplier<NetworkEvent.Context> contextSupplier) {
            ServerPlayer player = contextSupplier.get().getSender();
            if (player != null && MaidSophisticated.touhouLittleMaidLoaded()) {
                TlmCompat.handleToggleMainBackpack(player);
            }
        }
    }

    /** 标签页工具栏：搜索词。 */
    public static class SophSearch {
        private final String text;

        SophSearch(String text) {
            this.text = text == null ? "" : text;
        }

        static void encode(SophSearch msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.text, 64);
        }

        static SophSearch decode(FriendlyByteBuf buf) {
            return new SophSearch(buf.readUtf(64));
        }

        void handle(Supplier<NetworkEvent.Context> contextSupplier) {
            ServerPlayer player = contextSupplier.get().getSender();
            if (player != null && MaidSophisticated.touhouLittleMaidLoaded()) {
                TlmCompat.handleSearch(player, text);
            }
        }
    }

    /** 标签页工具栏：整理 / 排序。 */
    public static class SophSort {
        private final int mode;

        SophSort(int mode) {
            this.mode = mode;
        }

        static void encode(SophSort msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.mode);
        }

        static SophSort decode(FriendlyByteBuf buf) {
            return new SophSort(buf.readVarInt());
        }

        void handle(Supplier<NetworkEvent.Context> contextSupplier) {
            ServerPlayer player = contextSupplier.get().getSender();
            if (player != null && MaidSophisticated.touhouLittleMaidLoaded()) {
                TlmCompat.handleSort(player, mode);
            }
        }
    }

    /**
     * 客户端主动请求一次女仆背包状态（背上的外观用）。
     * 只回给"正在看着这只女仆的主人"，不会泄漏给别人。
     */
    public static class RequestMaidState {
        private final int maidEntityId;

        RequestMaidState(int maidEntityId) {
            this.maidEntityId = maidEntityId;
        }

        static void encode(RequestMaidState msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.maidEntityId);
        }

        static RequestMaidState decode(FriendlyByteBuf buf) {
            return new RequestMaidState(buf.readVarInt());
        }

        void handle(Supplier<NetworkEvent.Context> contextSupplier) {
            ServerPlayer player = contextSupplier.get().getSender();
            if (player == null) {
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
