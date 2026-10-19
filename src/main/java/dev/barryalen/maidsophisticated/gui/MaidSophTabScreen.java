package dev.barryalen.maidsophisticated.gui;

import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidDownloadButton;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.TabIndex;
import com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler;
import com.github.tartaricacid.touhoulittlemaid.network.message.MaidConfigMessage;
import com.github.tartaricacid.touhoulittlemaid.network.message.ToggleTabMessage;
import dev.barryalen.maidsophisticated.MaidBackpackData;
import dev.barryalen.maidsophisticated.MaidSophisticated;
import dev.barryalen.maidsophisticated.ModItems;
import dev.barryalen.maidsophisticated.ModNetwork;
import dev.barryalen.maidsophisticated.TlmCompat;
import dev.barryalen.maidsophisticated.client.MaidSophTabButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.StateSwitchingButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * 女仆 GUI 的"精妙背包"标签页。
 * 自己画女仆主界面的外壳（底图 + 左侧预览 + 顶部标签），
 * 右侧是三个专属背包槽 + 一条工具栏（搜索 / 整理 / 排序，和精妙背包自己的界面同一套）
 * + 5 行 × 8 列内容网格 + 细滚动条；
 * 左侧头像下方复刻女仆首页的状态条、任务按钮、日程条与三个模式开关。
 */
public class MaidSophTabScreen extends AbstractContainerScreen<MaidSophTabContainer> {
    private static final ResourceLocation MAIN =
            new ResourceLocation("touhou_little_maid", "textures/gui/maid_gui_main.png");
    private static final ResourceLocation BACKPACK =
            new ResourceLocation("touhou_little_maid", "textures/gui/maid_gui_backpack.png");
    private static final ResourceLocation SIDE =
            new ResourceLocation("touhou_little_maid", "textures/gui/maid_gui_side.png");
    private static final ResourceLocation BUTTON =
            new ResourceLocation("touhou_little_maid", "textures/gui/maid_gui_button.png");
    private static final ResourceLocation BUCKLE =
            new ResourceLocation(MaidSophisticated.MOD_ID, "textures/item/maid_backpack_buckle.png");
    /** 精妙背包自己的图标表：搜索、整理、排序的图标直接借用，画风和原生界面一致。 */
    private static final ResourceLocation SB_ICONS =
            new ResourceLocation("sophisticatedcore", "textures/gui/icons.png");

    /**
     * 图标坐标全部照抄精妙背包自己的 {@code ButtonDefinitions}：
     * 整理是图标表 (0,144) 的绿色上箭头，排序方式分别是
     * 名称 (24,144)、标签 (12,144)、数量 (36,144)、模组 (0,156)，都是 12×12。
     * 这一块是 12 像素一格的紧凑布局，直接按 16 格去截会截到隔壁图标，
     * 上一版图标"不在正中、还带黑点"就是这么来的。
     */
    private static final int ICON_SORT_U = 0;
    private static final int ICON_SORT_V = 144;
    private static final int ICON_BY_NAME_U = 24;
    private static final int ICON_BY_COUNT_U = 36;
    private static final int ICON_BY_MOD_U = 0;
    private static final int ICON_SORT_ROW_V = 144;
    private static final int ICON_SORT_ROW2_V = 156;
    /** 小图标是 12×12；放进 16×16 的按钮里，四周各留 2 像素正好居中。 */
    private static final int SMALL_ICON = 12;
    private static final int SMALL_ICON_PAD = 2;
    /** 搜索用的放大镜是图标表里 16×16 的那一个。 */
    private static final int SEARCH_ICON_U = 208;
    private static final int SEARCH_ICON_V = 32;
    private static final DecimalFormat SCALE_FORMAT = new DecimalFormat("00");

    /** 细滚动条：贴在内容网格右侧、面板右缘之前。 */
    private static final int SCROLL_X = 238;
    private static final int SCROLL_Y = MaidSophTabContainer.GRID_Y;
    private static final int SCROLL_W = 6;
    private static final int SCROLL_H = 89;
    /** 内容网格区域（8 列 × 5 行）。 */
    private static final int GRID_X = MaidSophTabContainer.GRID_X;
    private static final int GRID_Y = MaidSophTabContainer.GRID_Y;
    private static final int GRID_RIGHT = GRID_X + MaidSophTabContainer.COLS * 18 - 1;
    private static final int GRID_BOTTOM = GRID_Y + MaidSophTabContainer.ROWS * 18 - 1;
    /** 三个背包槽位置（与容器一致）。 */
    private static final int BP_X = MaidSophTabContainer.BP_X;
    private static final int BP_Y = MaidSophTabContainer.BP_Y;

    private boolean draggingScroll;
    private EditBox searchBox;
    private boolean searchOpen;
    private String searchText = "";

    public MaidSophTabScreen(MaidSophTabContainer menu, Inventory inv, Component titleIn) {
        super(menu, inv, titleIn);
        this.imageHeight = 256;
        this.imageWidth = 256;
        this.inventoryLabelY = 10000;
        this.titleLabelY = 10000;
    }

    @Override
    protected void init() {
        super.init();
        LivingEntity maid = this.menu.getMaid();
        if (maid == null) {
            return;
        }
        int id = maid.getId();
        // 顶部三个原版标签：点了切回女仆自己的对应页
        addTab(94, 107, "main", b -> NetworkHandler.CHANNEL.sendToServer(new ToggleTabMessage(id, TabIndex.MAIN)));
        addTab(119, 132, "task_config", b -> NetworkHandler.CHANNEL.sendToServer(new ToggleTabMessage(id, TabIndex.TASK_CONFIG)));
        addTab(144, 157, "maid_config", b -> NetworkHandler.CHANNEL.sendToServer(new ToggleTabMessage(id, TabIndex.MAID_CONFIG)));
        // 第四个：当前页，按下不可点
        this.addRenderableWidget(new MaidSophTabButton(leftPos + 169, topPos + 5, true, b -> {}));

        addMiniButton(197, BP_Y, "gui." + MaidSophisticated.MOD_ID + ".prev",
                b -> menu.clientSelect(menu.nextNonEmptySlot(-1)));
        addMiniButton(215, BP_Y, "gui." + MaidSophisticated.MOD_ID + ".next",
                b -> menu.clientSelect(menu.nextNonEmptySlot(1)));
        addMiniButton(233, BP_Y, "gui." + MaidSophisticated.MOD_ID + ".open_native",
                b -> {
                    if (!menu.currentBackpack().isEmpty()) {
                        ModNetwork.sendOpenNative();
                    }
                });

        // 三个背包槽左边那块空白：主背包显示开关
        this.addRenderableWidget(new MainBackpackButton(leftPos + 86, topPos + 35));

        addToolbar();

        // 左侧栏：与女仆首页同一套小部件
        if (maid instanceof EntityMaid entityMaid) {
            addLeftColumnWidgets(entityMaid);
        }
    }

    /**
     * 工具栏：搜索框 + 搜索 / 整理 / 排序三个按钮，占掉原来内容网格的第一行。
     * 外观和精妙背包自己的界面同一套（图标直接用它自己的图标表）。
     */
    private void addToolbar() {
        searchBox = new EditBox(this.font, leftPos + 88, topPos + MaidSophTabContainer.TOOLBAR_Y + 2,
                86, 14, Component.translatable("gui." + MaidSophisticated.MOD_ID + ".search"));
        searchBox.setMaxLength(32);
        searchBox.setValue(searchText);
        searchBox.setVisible(searchOpen);
        searchBox.setResponder(text -> {
            String cleaned = text == null ? "" : text;
            if (!cleaned.equals(searchText)) {
                searchText = cleaned;
                ModNetwork.sendSearch(cleaned);
            }
        });
        this.addRenderableWidget(searchBox);

        addToolButton(leftPos + 178, topPos + MaidSophTabContainer.TOOLBAR_Y + 1,
                () -> SEARCH_ICON_U, () -> SEARCH_ICON_V, 16,
                b -> toggleSearch(), "gui." + MaidSophisticated.MOD_ID + ".search.tip");
        addToolButton(leftPos + 196, topPos + MaidSophTabContainer.TOOLBAR_Y + 1,
                () -> ICON_SORT_U, () -> ICON_SORT_V, SMALL_ICON,
                b -> menu.clientSort(menu.sortMode().ordinal()),
                "gui." + MaidSophisticated.MOD_ID + ".compact");
        this.addRenderableWidget(new SortToolButton(leftPos + 214, topPos + MaidSophTabContainer.TOOLBAR_Y + 1));
    }

    private void toggleSearch() {
        searchOpen = !searchOpen;
        if (searchBox == null) {
            return;
        }
        searchBox.setVisible(searchOpen);
        if (searchOpen) {
            // 必须把整个界面的焦点交给它，不然键盘事件（尤其字符输入）到不了搜索框
            this.setFocused(searchBox);
            searchBox.setCursorPosition(searchBox.getValue().length());
        } else {
            this.setFocused(null);
            if (!searchBox.getValue().isEmpty()) {
                // responder 会把空搜索发给服务端，格子恢复原样
                searchBox.setValue("");
            }
        }
    }

    /** 搜索框是不是正在接收键盘（只有这时才拦键盘，其余按键照常走原版逻辑）。 */
    private boolean isSearchFocused() {
        return searchOpen && searchBox != null && searchBox.isVisible() && searchBox.isFocused();
    }

    /**
     * 工具栏上的小方按钮：深灰描边 + 浅灰面，中间盖一个精妙背包自己的图标。
     * 12×12 的小图标按原版那样四周各留 2 像素居中，绝不再让图标压在边框上。
     */
    private void addToolButton(int x, int y, IntSupplier u, IntSupplier v, int iconSize,
                               Button.OnPress press, String tooltipKey) {
        this.addRenderableWidget(new Button(Button.builder(Component.empty(), press)
                .bounds(x, y, 16, 16)
                .tooltip(Tooltip.create(Component.translatable(tooltipKey)))) {
            @Override
            protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                drawToolFace(graphics, this);
                int pad = (16 - iconSize) / 2;
                graphics.blit(SB_ICONS, this.getX() + pad, this.getY() + pad,
                        u.getAsInt(), v.getAsInt(), iconSize, iconSize, 256, 256);
            }
        });
    }

    private static void drawToolFace(GuiGraphics graphics, Button button) {
        int x = button.getX();
        int y = button.getY();
        graphics.fill(x, y, x + 16, y + 16, 0xFF373737);
        graphics.fill(x + 1, y + 1, x + 15, y + 15, button.isHovered() ? 0xFFE8E8E8 : 0xFFC6C6C6);
    }

    /** 排序方式按钮：图标跟着当前方式变（T 分类 / A 名称 / 1 数量），点一下换下一种并立刻整理。 */
    private final class SortToolButton extends Button {
        private MaidSophTabContainer.SortMode lastMode;

        SortToolButton(int x, int y) {
            super(Button.builder(Component.empty(),
                            b -> menu.clientSort(menu.sortMode().next().ordinal()))
                    .bounds(x, y, 16, 16));
        }

        private int iconU() {
            return switch (menu.sortMode()) {
                case NAME -> ICON_BY_NAME_U;
                case COUNT -> ICON_BY_COUNT_U;
                // 分类 = 同一个模组的物品排在一起，用原版"按模组排序"那个 M 图标
                case CATEGORY -> ICON_BY_MOD_U;
            };
        }

        private int iconV() {
            return menu.sortMode() == MaidSophTabContainer.SortMode.CATEGORY ? ICON_SORT_ROW2_V : ICON_SORT_ROW_V;
        }

        private String modeKey() {
            return switch (menu.sortMode()) {
                case NAME -> "gui." + MaidSophisticated.MOD_ID + ".sort.name";
                case COUNT -> "gui." + MaidSophisticated.MOD_ID + ".sort.count";
                case CATEGORY -> "gui." + MaidSophisticated.MOD_ID + ".sort.category";
            };
        }

        @Override
        public void onPress() {
            super.onPress();
            lastMode = null;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (lastMode != menu.sortMode()) {
                lastMode = menu.sortMode();
                setTooltip(Tooltip.create(Component.translatable(
                        "gui." + MaidSophisticated.MOD_ID + ".sort.tip",
                        Component.translatable(modeKey()))));
            }
            drawToolFace(graphics, this);
            graphics.blit(SB_ICONS, this.getX() + SMALL_ICON_PAD, this.getY() + SMALL_ICON_PAD,
                    iconU(), iconV(), SMALL_ICON, SMALL_ICON, 256, 256);
        }
    }

    private void addLeftColumnWidgets(EntityMaid maid) {
        int id = maid.getId();
        // 任务按钮：显示当前任务，点击回女仆主页管理任务清单
        ImageButton task = new ImageButton(leftPos + 4, topPos + 159, 71, 21, 0, 42, 22, BUTTON,
                b -> NetworkHandler.CHANNEL.sendToServer(new ToggleTabMessage(id, TabIndex.MAIN)));
        task.setTooltip(Tooltip.create(Component.translatable("gui." + MaidSophisticated.MOD_ID + ".open_task_page")));
        this.addRenderableWidget(task);
        // 日程条：点击循环 全天/白天/黑夜，和女仆首页一致
        this.addRenderableWidget(new SophScheduleButton(leftPos + 9, topPos + 187, maid));
        // 三个模式开关：待命 / 拾取 / 骑乘，直接发女仆模组的原版配置消息
        this.addRenderableWidget(modeSwitch(leftPos + 9, topPos + 206, 0, maid, Mode.HOME,
                "gui.touhou_little_maid.button.home"));
        this.addRenderableWidget(modeSwitch(leftPos + 30, topPos + 206, 42, maid, Mode.PICKUP,
                "gui.touhou_little_maid.button.pickup"));
        this.addRenderableWidget(modeSwitch(leftPos + 51, topPos + 206, 84, maid, Mode.RIDE,
                "gui.touhou_little_maid.button.maid_riding_set"));
        // 模型下载按钮：直接复用女仆模组的现成按钮
        this.addRenderableWidget(new MaidDownloadButton(leftPos + 20, topPos + 230, BUTTON, maid));
    }

    private enum Mode { HOME, PICKUP, RIDE }

    private StateSwitchingButton modeSwitch(int x, int y, int u, EntityMaid maid, Mode mode, String tooltipKey) {
        boolean initial = switch (mode) {
            case HOME -> maid.isHomeModeEnable();
            case PICKUP -> maid.isPickup();
            case RIDE -> maid.isRideable();
        };
        StateSwitchingButton button = new StateSwitchingButton(x, y, 20, 20, initial) {
            @Override
            public void onClick(double mouseX, double mouseY) {
                this.isStateTriggered = !this.isStateTriggered;
                boolean home = mode == Mode.HOME ? this.isStateTriggered : maid.isHomeModeEnable();
                boolean pick = mode == Mode.PICKUP ? this.isStateTriggered : maid.isPickup();
                boolean ride = mode == Mode.RIDE ? this.isStateTriggered : maid.isRideable();
                NetworkHandler.CHANNEL.sendToServer(new MaidConfigMessage(maid.getId(), home, pick, ride, maid.getSchedule()));
            }
        };
        button.initTextureValues(u, 0, 21, 21, BUTTON);
        button.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
        return button;
    }

    /** 日程按钮：与女仆首页 ScheduleButton 同款外观与行为。 */
    static class SophScheduleButton extends Button {
        private final EntityMaid maid;
        private MaidSchedule mode;

        SophScheduleButton(int x, int y, EntityMaid maid) {
            super(builder(Component.empty(), b -> {}).bounds(x, y, 61, 13));
            this.maid = maid;
            this.mode = maid.getSchedule();
            setTooltip(Tooltip.create(Component.translatable("gui." + MaidSophisticated.MOD_ID + ".schedule.desc")));
        }

        @Override
        public void onPress() {
            MaidSchedule[] values = MaidSchedule.values();
            this.mode = values[(this.mode.ordinal() + 1) % values.length];
            NetworkHandler.CHANNEL.sendToServer(new MaidConfigMessage(maid.getId(),
                    maid.isHomeModeEnable(), maid.isPickup(), maid.isRideable(), this.mode));
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.blit(BUTTON, this.getX(), this.getY(), 82, 43 + 14 * this.mode.ordinal(),
                    this.width, this.height, 256, 256);
        }
    }

    private void addTab(int xOff, int u, String key, Button.OnPress press) {
        this.addRenderableWidget(new VanillaTabButton(leftPos + xOff, topPos + 5, u, key, press));
    }

    private void addMiniButton(int relX, int relY, String tooltipKey, Button.OnPress press) {
        Button button = new Button(Button.builder(Component.empty(), press)
                .bounds(leftPos + relX, topPos + relY, 16, 16)
                .tooltip(Tooltip.create(Component.translatable(tooltipKey)))) {
            @Override
            public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                if (this.isHovered()) {
                    graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(),
                            this.getY() + this.getHeight(), 0x66FFFFFF);
                }
            }
        };
        this.addRenderableWidget(button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (menu.getMaid() instanceof EntityMaid maid) {
            drawCurrentTaskText(graphics, maid);
        }
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    /**
     * 悬停在顶部三个背包槽上时，在物品提示末尾多写一行：
     * 点一下就把她背上显示的那个背包换成这个（和服务端记录的"主背包"是同一件事）。
     */
    @Override
    protected void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.hoveredSlot instanceof MaidSophTabContainer.MaidBackpackSlot backpackSlot
                && backpackSlot.hasItem()) {
            @SuppressWarnings("unchecked")
            List<Component> lines = new ArrayList<>(
                    this.getTooltipFromContainerItem(backpackSlot.getItem()));
            if (menu.selectedSlot() == backpackSlot.getSlotIndex()) {
                lines.add(Component.translatable("gui." + MaidSophisticated.MOD_ID + ".main_backpack.is_main")
                        .withStyle(ChatFormatting.YELLOW));
            } else {
                lines.add(Component.translatable("gui." + MaidSophisticated.MOD_ID + ".main_backpack.set_main")
                        .withStyle(ChatFormatting.GRAY));
            }
            graphics.renderTooltip(this.font, lines, Optional.empty(), mouseX, mouseY);
            return;
        }
        super.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
        graphics.blit(MAIN, leftPos, topPos, 0, 0, imageWidth, imageHeight);

        LivingEntity maid = menu.getMaid();
        if (maid != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, leftPos + 40, topPos + 100, 40,
                    (leftPos + 40) - mouseX, (topPos + 50) - mouseY, maid);
        }
        if (maid instanceof EntityMaid entityMaid) {
            drawLeftColumnInfo(graphics, entityMaid);
        }

        drawSlotSquares(graphics);

        boolean hasBackpack = menu.hasAnyBackpack();
        if (!hasBackpack) {
            for (int r = 0; r < MaidSophTabContainer.ROWS; r++) {
                fillLocked(graphics, r);
            }
        } else {
            int visibleRows = Math.min(MaidSophTabContainer.ROWS,
                    Math.max(0, (menu.currentContentSize() + MaidSophTabContainer.COLS - 1)
                            / MaidSophTabContainer.COLS - menu.scrollRow()));
            for (int r = visibleRows; r < MaidSophTabContainer.ROWS; r++) {
                fillLocked(graphics, r);
            }
        }

        int sel = menu.selectedSlot();
        if (sel >= 0 && sel < 3 && !menu.currentBackpack().isEmpty()) {
            int hx = leftPos + BP_X + sel * 18 - 1;
            int hy = topPos + BP_Y - 1;
            graphics.fill(hx, hy, hx + 18, hy + 1, 0xFFF0E6A1);
            graphics.fill(hx, hy + 17, hx + 18, hy + 18, 0xFFF0E6A1);
            graphics.fill(hx, hy, hx + 1, hy + 18, 0xFFF0E6A1);
            graphics.fill(hx + 17, hy, hx + 18, hy + 18, 0xFFF0E6A1);
        }

        drawScrollBar(graphics);
        drawMiniButtonIcons(graphics);
        drawToolbarBar(graphics);
    }

    /**
     * 工具栏底板：和精妙背包自己的界面一样，浅灰标题条 + 深灰描边。
     * 搜索框没打开时，左边显示当前背包的名字。
     */
    private void drawToolbarBar(GuiGraphics graphics) {
        int x = leftPos + MaidSophTabContainer.TOOLBAR_X;
        int y = topPos + MaidSophTabContainer.TOOLBAR_Y;
        int right = x + MaidSophTabContainer.TOOLBAR_W;
        int bottom = y + MaidSophTabContainer.TOOLBAR_H;
        graphics.fill(x, y, right, bottom, 0xFF373737);
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1, 0xFFC6C6C6);
        if (searchOpen) {
            return;
        }
        Component title;
        int color = 0xFF3F3F3F;
        if (menu.currentBackpack().isEmpty()) {
            title = Component.translatable("gui." + MaidSophisticated.MOD_ID + ".toolbar.empty");
            color = 0xFF6B6B6B;
        } else {
            title = menu.currentBackpack().getHoverName();
        }
        graphics.drawString(font, font.plainSubstrByWidth(title.getString(), 86), x + 4, y + 5, color, false);
    }

    /**
     * 原版 1.20.1 的容器界面不画槽位底（槽底全部烤在 GUI 底图里），
     * 而女仆底图没有我们这套布局的格子，所以这里自己画。
     *
     * 像素照抄原版 inventory.png 里的槽位（37/37/37 深灰 + 8B8B8B 内芯 + 右下白色高光），
     * 这样背包格子和下方的玩家物品栏看起来是同一套画风。
     */
    private void drawSlotSquares(GuiGraphics graphics) {
        if (menu.backpackData() != null) {
            for (int i = 0; i < MaidBackpackData.SLOTS; i++) {
                drawSlotSquare(graphics, leftPos + BP_X + i * 18, topPos + BP_Y);
            }
        }
        int base = MaidSophTabContainer.CONTENT_BASE;
        for (int k = 0; k < MaidSophTabContainer.VISIBLE; k++) {
            // 女仆数据缺失时容器可能没建内容槽，别越界把整个界面搞崩
            if (base + k >= menu.slots.size()) {
                break;
            }
            Slot slot = menu.slots.get(base + k);
            if (slot.isActive()) {
                drawSlotSquare(graphics, leftPos + slot.x, topPos + slot.y);
            }
        }
    }

    private void drawSlotSquare(GuiGraphics graphics, int x, int y) {
        // 18x18 的格子：先整块铺槽体灰，再画右下白高光、左上深灰阴影。
        // 阴影/高光故意只画 17 格，把最末一格留给槽体色——和原版物品栏一致，
        // 这样四个格子交汇的地方不会出现刺眼的黑点。
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF8B8B8B);
        graphics.fill(x + 16, y, x + 17, y + 17, 0xFFFFFFFF);
        graphics.fill(x, y + 16, x + 17, y + 17, 0xFFFFFFFF);
        graphics.fill(x - 1, y - 1, x + 16, y, 0xFF373737);
        graphics.fill(x - 1, y - 1, x, y + 16, 0xFF373737);
    }

    /** 左侧栏静态信息：四条状态条 + 日程条背景（坐标与女仆首页完全一致）。 */
    private void drawLeftColumnInfo(GuiGraphics graphics, EntityMaid maid) {
        // 生命
        bar(graphics, 113, 0, 18, maid.getHealth() / Math.max(1.0F, maid.getMaxHealth()));
        number(graphics, maid.getHealth(), 63, 114);
        // 护甲
        double armor = Math.min(maid.getAttributeValue(Attributes.ARMOR) / 20.0, 1.0);
        bar(graphics, 124, 9, 23, armor);
        number(graphics, maid.getArmorValue(), 63, 125);
        // 经验
        int exp = maid.getExperience();
        bar(graphics, 135, 18, 28, (exp % 120) / 120.0);
        number(graphics, exp / 120, 63, 136);
        // 好感度
        bar(graphics, 146, 27, 33, maid.getFavorabilityManager().getLevelPercent());
        number(graphics, maid.getFavorabilityManager().getLevel(), 63, 147);
        // 日程条背景（"WORK!" 那条）
        graphics.blit(SIDE, leftPos + 6, topPos + 178, 0, 47, 67, 25);
    }

    private void bar(GuiGraphics graphics, int y, int iconU, int fillU, double ratio) {
        graphics.blit(SIDE, leftPos + 53, topPos + y, iconU, 0, 9, 9);
        graphics.blit(SIDE, leftPos + 5, topPos + y, 0, 9, 47, 9);
        int w = (int) (43.0 * Mth.clamp(ratio, 0.0, 1.0));
        if (w > 0) {
            graphics.blit(SIDE, leftPos + 7, topPos + y + 2, 2, fillU, w, 5);
        }
    }

    private void number(GuiGraphics graphics, double value, int x, int y) {
        String text = formatScale((long) value);
        graphics.pose().pushPose();
        graphics.pose().scale(0.5F, 0.5F, 1.0F);
        graphics.drawString(this.font, text, x * 2, y * 2 + 9 / 2, ChatFormatting.DARK_GRAY.getColor(), false);
        graphics.pose().popPose();
    }

    private static String formatScale(long v) {
        if (v >= 1_000_000_000L) {
            long unit = v / 1_000_000_000L;
            return unit < 10L ? unit + "." + v % 1_000_000_000L / 100_000_000L + "G" : unit + "G";
        }
        if (v >= 1_000_000L) {
            long unit = v / 1_000_000L;
            return unit < 10L ? unit + "." + v % 1_000_000L / 100_000L + "M" : unit + "M";
        }
        if (v >= 1_000L) {
            long unit = v / 1_000L;
            return unit < 10L ? unit + "." + v % 1_000L / 100L + "K" : unit + "K";
        }
        return SCALE_FORMAT.format(v);
    }

    /** 任务按钮上的当前任务图标与名字（和女仆首页一致，画在按钮贴图之上）。 */
    private void drawCurrentTaskText(GuiGraphics graphics, EntityMaid maid) {
        var task = maid.getTask();
        if (task == null) {
            return;
        }
        graphics.renderItem(task.getIcon(), leftPos + 6, topPos + 161);
        var lines = this.font.split(task.getName(), 42);
        if (!lines.isEmpty()) {
            graphics.drawString(this.font, lines.get(0), leftPos + 28, topPos + 165, 0x333333, false);
        }
    }

    private void fillLocked(GuiGraphics graphics, int row) {
        int y0 = topPos + MaidSophTabContainer.ROW_Y[row] - 1;
        int y1 = y0 + 18;
        graphics.fill(leftPos + GRID_X - 1, y0, leftPos + GRID_RIGHT, y1, 0xaa222222);
        graphics.blit(BACKPACK, leftPos + (GRID_X + GRID_RIGHT) / 2 - 5, y0 + 4, 165, 0, 11, 11);
    }

    private void drawMiniButtonIcons(GuiGraphics graphics) {
        int y = topPos + BP_Y;
        graphics.drawCenteredString(font, "<", leftPos + 197 + 8, y + 4, 0xFFE0E0E0);
        graphics.drawCenteredString(font, ">", leftPos + 215 + 8, y + 4, 0xFFE0E0E0);
        graphics.blit(BUCKLE, leftPos + 233, y, 0, 0, 16, 16, 16, 16);
    }

    private void drawScrollBar(GuiGraphics graphics) {
        int x = leftPos + SCROLL_X;
        int y = topPos + SCROLL_Y;
        graphics.fill(x, y, x + SCROLL_W, y + SCROLL_H, 0xFF17181C);
        graphics.fill(x + 1, y + 1, x + SCROLL_W - 1, y + SCROLL_H - 1, 0xFF3A3D44);
        int max = menu.maxScrollRow();
        int knobH = max <= 0 ? SCROLL_H - 2 : Math.max(10, (SCROLL_H - 2) * MaidSophTabContainer.ROWS
                / (MaidSophTabContainer.ROWS + max));
        int travel = (SCROLL_H - 2) - knobH;
        int knobY = y + 1 + (max <= 0 ? 0 : travel * menu.scrollRow() / max);
        graphics.fill(x + 1, knobY, x + SCROLL_W - 1, knobY + knobH, 0xFFC6C6C6);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverScroll(mouseX, mouseY)) {
            draggingScroll = true;
            applyScrollFromMouse(mouseY);
            return true;
        }
        // 点到别处就取消搜索框的焦点，免得继续打字改搜索词
        if (isSearchFocused() && !searchBox.isMouseOver(mouseX, mouseY)) {
            this.setFocused(null);
        }
        Slot hovered = this.getSlotUnderMouse();
        if (hovered instanceof MaidSophTabContainer.MaidBackpackSlot bpSlot && bpSlot.hasItem()) {
            menu.clientSelect(bpSlot.getSlotIndex());
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 搜索框有焦点时，键盘全部交给它：不然按 E 会把整个界面关掉
        if (isSearchFocused()) {
            if (keyCode == 256) {
                toggleSearch();
                return true;
            }
            searchBox.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            draggingScroll = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScroll && button == 0) {
            applyScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta != 0 && (isOverContent(mouseX, mouseY) || isOverScroll(mouseX, mouseY))) {
            menu.clientScroll(menu.scrollRow() + (delta < 0 ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void applyScrollFromMouse(double mouseY) {
        int max = menu.maxScrollRow();
        if (max <= 0) {
            return;
        }
        double rel = (mouseY - (topPos + SCROLL_Y)) / SCROLL_H;
        menu.clientScroll(Mth.clamp((int) Math.round(rel * max), 0, max));
    }

    private boolean isOverScroll(double mouseX, double mouseY) {
        int x = leftPos + SCROLL_X;
        int y = topPos + SCROLL_Y;
        return mouseX >= x && mouseX < x + SCROLL_W && mouseY >= y && mouseY < y + SCROLL_H;
    }

    private boolean isOverContent(double mouseX, double mouseY) {
        return mouseX >= leftPos + GRID_X && mouseX < leftPos + GRID_RIGHT
                && mouseY >= topPos + GRID_Y && mouseY < topPos + GRID_BOTTOM;
    }

    /** 复用车万女仆顶部标签外形的小按钮（可点，非当前页）。 */
    static class VanillaTabButton extends Button {
        private final int u;

        VanillaTabButton(int x, int y, int u, String key, OnPress press) {
            super(Button.builder(Component.empty(), press).pos(x, y).size(24, 26)
                    .tooltip(Tooltip.create(Component.translatable("gui.touhou_little_maid.button." + key))));
            this.u = u;
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.blit(SIDE, this.getX() + 4, this.getY() + 6, u, 47, 16, 16, 256, 256);
        }
    }

    /**
     * 主背包显示开关：放在三个背包槽左边那块空白里（女仆精妙背包页）。
     * 女仆同时装了自己的背包和精妙背包时，用这个按钮决定背上显示哪一个；
     * 女仆没有自己的背包时它变灰，只剩精妙背包可显示。
     */
    private final class MainBackpackButton extends Button {
        private static final String KEY = "gui." + MaidSophisticated.MOD_ID + ".main_backpack.";
        private Boolean lastState;

        MainBackpackButton(int x, int y) {
            super(Button.builder(Component.empty(), b -> {
            }).bounds(x, y, 56, 16));
        }

        private EntityMaid maidEntity() {
            return menu.getMaid() instanceof EntityMaid entityMaid ? entityMaid : null;
        }

        /** 女仆有没有装备她自己的背包。 */
        private boolean hasOwnBackpack() {
            EntityMaid maid = maidEntity();
            return maid != null && TlmCompat.hasOwnBackpack(maid);
        }

        /** 现在背上显示的是不是精妙背包。 */
        private boolean showsSophisticated() {
            if (maidEntity() == null) {
                return false;
            }
            return !hasOwnBackpack() || menu.showSophisticated();
        }

        private Component label() {
            return Component.translatable(KEY + (showsSophisticated() ? "soph" : "own"));
        }

        private ItemStack icon() {
            if (showsSophisticated()) {
                ItemStack stack = menu.currentBackpack();
                return stack.isEmpty() ? new ItemStack(ModItems.MAID_BACKPACK_BUCKLE.get()) : stack;
            }
            EntityMaid maid = maidEntity();
            return maid == null ? ItemStack.EMPTY : TlmCompat.ownBackpackItem(maid);
        }

        @Override
        public void onPress() {
            if (!hasOwnBackpack()) {
                return;
            }
            menu.clientToggleShowSophisticated();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (lastState == null || lastState != showsSophisticated()) {
                lastState = showsSophisticated();
                setTooltip(Tooltip.create(tooltipText()));
            }
            int x = this.getX();
            int y = this.getY();
            // 和物品槽同一套画风：深灰描边 + 浅灰面，悬停时提亮
            graphics.fill(x, y, x + this.width, y + this.height, 0xFF373737);
            graphics.fill(x + 1, y + 1, x + this.width - 1, y + this.height - 1,
                    this.isHovered() && hasOwnBackpack() ? 0xFFC6C6C6 : 0xFF8B8B8B);
            ItemStack icon = icon();
            if (!icon.isEmpty()) {
                graphics.renderItem(icon, x + 2, y);
            }
            int color = hasOwnBackpack() ? 0xFF2B2B2B : 0xFF6B6B6B;
            graphics.drawString(font, label(), x + 19, y + 4, color, false);
        }

        private Component tooltipText() {
            Component head = Component.translatable(KEY + "tip", label())
                    .withStyle(ChatFormatting.WHITE);
            Component body;
            if (!hasOwnBackpack()) {
                body = Component.translatable(KEY + "no_own").withStyle(ChatFormatting.GRAY);
            } else {
                Component next = Component.translatable(KEY + (showsSophisticated() ? "own" : "soph"));
                body = Component.translatable(KEY + "toggle", next).withStyle(ChatFormatting.GRAY);
            }
            return head.copy().append("\n").append(body);
        }
    }
}
