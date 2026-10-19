package dev.barryalen.maidsophisticated;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端内存里的"这个女仆背上该显示哪个精妙背包"。
 *
 * 为什么不能只靠实体 capability：那套数据要靠我们的同步包写进去，
 * 而同步包在"女仆刚刷出来 / 玩家刚靠近"时可能比实体创建还早到，到了也找不到实体就丢了，
 * 于是就出现"设置明明选了精妙背包，背上却还是她自己的背包"。
 *
 * 所以服务端每次变化都会推一份，另外每两秒再兜底推一次；收到就写进这里。
 * 背部渲染直接读这里，服务端数据、网络时序都只影响"什么时候更新"，不会让外观永远为空。
 */
public final class MaidBackpackView {
    /** 一个女仆的展示状态：三个槽里的背包 + "主背包显示"开关 + 背上显示第几格。 */
    public static final class Entry {
        private final List<ItemStack> stacks;
        private final boolean showSophisticated;
        /** 服务端记着的主背包下标（-1 = 没选过）。 */
        private final int mainSlot;

        Entry(List<ItemStack> stacks, boolean showSophisticated, int mainSlot) {
            this.stacks = stacks;
            this.showSophisticated = showSophisticated;
            this.mainSlot = mainSlot;
        }

        public boolean showSophisticated() {
            return showSophisticated;
        }

        /** 玩家选中的那个主背包下标；选的那格空了就退回第一个有包的格子。 */
        public int mainSlot() {
            if (mainSlot >= 0 && mainSlot < stacks.size() && !stacks.get(mainSlot).isEmpty()) {
                return mainSlot;
            }
            for (int i = 0; i < stacks.size(); i++) {
                if (!stacks.get(i).isEmpty()) {
                    return i;
                }
            }
            return -1;
        }

        /** 背上要画的背包：就是玩家选中的主背包（和服务端同一个判断，不会各画各的）。 */
        public ItemStack top() {
            int slot = mainSlot();
            return slot >= 0 ? stacks.get(slot) : ItemStack.EMPTY;
        }

        public boolean hasAny() {
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Map<Integer, Entry> VIEWS = new ConcurrentHashMap<>();
    /** 只给每只女仆打一次"收到状态"的日志，避免刷屏。 */
    private static final java.util.Set<Integer> LOGGED = ConcurrentHashMap.newKeySet();

    private MaidBackpackView() {}

    /** 收到服务端状态时打一行日志：出问题时从 latest.log 一眼看出数据到底有没有到客户端。 */
    public static void logFirstReception(int entityId, List<ItemStack> stacks, boolean showSophisticated,
                                         int mainSlot) {
        if (!LOGGED.add(entityId)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(stack.getItem()).append(" x").append(stack.getCount());
            }
        }
        MaidSophisticated.LOGGER.info(
                "Maid backpack state received: id={}, showSophisticated={}, mainSlot={}, backpacks=[{}]",
                entityId, showSophisticated, mainSlot, sb);
    }

    public static void update(int entityId, List<ItemStack> stacks, boolean showSophisticated, int mainSlot) {
        List<ItemStack> copy = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            copy.add(stack.copy());
        }
        VIEWS.put(entityId, new Entry(copy, showSophisticated, mainSlot));
    }

    public static Entry get(int entityId) {
        return VIEWS.get(entityId);
    }

    public static void clear(int entityId) {
        VIEWS.remove(entityId);
        LOGGED.remove(entityId);
    }

    /** 退出世界 / 切维度：全部清掉。实体 id 会在新世界重复，留着就会让新女仆串用旧背包外观。 */
    public static void clearAll() {
        VIEWS.clear();
        LOGGED.clear();
    }
}
