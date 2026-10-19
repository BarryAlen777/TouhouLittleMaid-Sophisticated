package dev.barryalen.maidsophisticated;

import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 女仆精妙背包标签页的容器类型注册。 */
public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MaidSophisticated.MOD_ID);

    /**
     * 标签页容器类型。只在车万女仆在场时由 {@link TlmCompat#registerMenu()} 填上，
     * 缺依赖时保持 null，本模组其余功能照常工作。
     */
    public static RegistryObject<MenuType<?>> MAID_SOPH_TAB;

    private ModMenus() {}

    public static void init() {
        if (ModList.get().isLoaded(MaidSophisticated.TLM_MOD_ID)) {
            TlmCompat.registerMenu();
        }
    }
}
