package dev.barryalen.maidsophisticated.client;

import com.github.tartaricacid.touhoulittlemaid.api.client.gui.ITooltipButton;
import com.google.common.collect.Lists;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.barryalen.maidsophisticated.MaidSophisticated;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 女仆 GUI 顶部第四个标签：外形复用车万女仆的空白标签贴图，
 * 图标用本模组的"女仆背包扣"。点开就是三个精妙背包槽 + 内容网格。
 */
public class MaidSophTabButton extends Button implements ITooltipButton {
    private static final ResourceLocation SIDE =
            new ResourceLocation("touhou_little_maid", "textures/gui/maid_gui_side.png");
    /** 空白标签外形在 maid_gui_side.png 上的 UV x（前三个标签分别是 107/132/157）。 */
    private static final int TAB_U = 182;
    private static final ResourceLocation ICON =
            new ResourceLocation(MaidSophisticated.MOD_ID, "textures/item/maid_backpack_buckle.png");
    private final List<Component> tooltips;
    private final boolean selected;

    public MaidSophTabButton(int x, int y, boolean selected, OnPress onPress) {
        super(Button.builder(Component.empty(), onPress).pos(x, y).size(24, 26));
        this.selected = selected;
        this.active = !selected;
        this.tooltips = Lists.newArrayList(
                Component.translatable("gui." + MaidSophisticated.MOD_ID + ".tab"),
                Component.translatable("gui." + MaidSophisticated.MOD_ID + ".tab.desc")
        );
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        RenderSystem.enableDepthTest();
        if (!this.active) {
            // 当前页：画"按下"态的空白标签外形
            graphics.blit(SIDE, this.getX(), this.getY(), TAB_U, 21, this.width, this.height, 256, 256);
        }
        // 背包扣图标
        graphics.blit(ICON, this.getX() + 4, this.getY() + 6, 0, 0, 16, 16, 16, 16);
    }

    @Override
    public boolean isTooltipHovered() {
        return this.active && this.isHovered();
    }

    @Override
    public void renderTooltip(GuiGraphics graphics, Minecraft mc, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        graphics.renderComponentTooltip(font, tooltips, mouseX, mouseY);
    }
}
