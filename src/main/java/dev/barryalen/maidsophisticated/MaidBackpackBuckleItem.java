package dev.barryalen.maidsophisticated;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** 女仆背包扣：手持右键女仆即可打开她的精妙背包标签页。 */
public class MaidBackpackBuckleItem extends Item {
    public MaidBackpackBuckleItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item." + MaidSophisticated.MOD_ID + ".maid_backpack_buckle.tooltip")
                .withStyle(ChatFormatting.GRAY));
    }
}
