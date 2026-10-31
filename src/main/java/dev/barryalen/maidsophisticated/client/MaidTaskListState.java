package dev.barryalen.maidsophisticated.client;

import java.lang.reflect.Field;

/**
 * 工作模式列表的开关状态：{@code 开着没有} + {@code 翻到第几页}。
 *
 * <p>车万女仆把这两样东西存成它界面类上的两个 <b>静态</b> 字段
 * （{@code TASK_LIST_OPEN} / {@code TASK_PAGE}），所以女仆自己的各个标签页
 * 本来就共用同一个开关：在女仆首页点开工作列表，切到别的页，列表还是开着的。</p>
 *
 * <p>我们这套精妙背包页一开始自己另存了一份（默认"关着"），于是从女仆首页切过来时
 * 列表就自己关掉了 —— 这就是用户看到的"工作选项开关没有同步"。
 * 这里直接读写女仆那两个字段，两边从此共用同一个开关：
 * 精妙背包页开着，回到女仆页也开着；在这边关掉，女仆页也关掉。</p>
 *
 * <p>用反射而不是直接引用这些字段（它们是女仆模组内部的私有静态状态）：
 * 万一将来版本把字段改名，拿不到就退回本模组自己的本地状态，
 * 界面照常能开关，只是不再跨页同步，绝不会因此崩溃。</p>
 */
public final class MaidTaskListState {
    /** 车万女仆的界面类，所有女仆标签页都继承它，两个静态字段就挂在它身上。 */
    private static final String GUI_CLASS =
            "com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui";
    private static final String OPEN_FIELD = "TASK_LIST_OPEN";
    private static final String PAGE_FIELD = "TASK_PAGE";
    /** 一页放 12 个模式，和女仆首页一致。 */
    public static final int TASKS_PER_PAGE = 12;

    private static Field openField;
    private static Field pageField;
    private static boolean resolved;
    /** 反射拿不到字段时的退路：开关只活在本模组自己的界面里。 */
    private static boolean localOpen;
    private static int localPage;

    private MaidTaskListState() {
    }

    /** 第一次用到时把两个字段找出来，之后一直复用。 */
    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> gui = Class.forName(GUI_CLASS);
            Field open = gui.getDeclaredField(OPEN_FIELD);
            open.setAccessible(true);
            Field page = gui.getDeclaredField(PAGE_FIELD);
            page.setAccessible(true);
            openField = open;
            pageField = page;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // 没装女仆、或者字段改了名字：退回本地状态，界面照常能用。
            // 只接住"反射本身的问题"（找不到类/字段、模块访问被拒），
            // OutOfMemoryError 这类 JVM 致命错误照旧往外抛，不在这儿吞掉。
            //
            // 必须带上 LinkageError：Class.forName 会**初始化**目标类，如果女仆的界面类
            // 因为缺依赖导致静态初始化失败，抛的是 ExceptionInInitializerError /
            // NoClassDefFoundError（都是 Error，不是 Exception），漏掉就会从 isOpen()
            // 一路冒到 tab 界面的 init()/renderBg()，变成客户端崩溃。
            // 这里只是"拿不到字段"的退路，绝不是该拦的地方。
            openField = null;
            pageField = null;
        }
    }

    /** 工作列表现在是不是展开的（和女仆自己的界面共用）。 */
    public static boolean isOpen() {
        resolve();
        if (openField == null) {
            return localOpen;
        }
        try {
            return openField.getBoolean(null);
        } catch (IllegalAccessException | RuntimeException ignored) {
            return localOpen;
        }
    }

    /** 切换 / 设置展开状态，女仆自己的界面下次重建时会读同一个值。 */
    public static void setOpen(boolean open) {
        resolve();
        localOpen = open;
        if (openField == null) {
            return;
        }
        try {
            openField.setBoolean(null, open);
        } catch (IllegalAccessException | RuntimeException ignored) {
            // 写不进去就只留本地状态
        }
    }

    /** 当前页（从 0 开始）。 */
    public static int page() {
        resolve();
        if (pageField == null) {
            return localPage;
        }
        try {
            return pageField.getInt(null);
        } catch (IllegalAccessException | RuntimeException ignored) {
            return localPage;
        }
    }

    /** 翻页后写回去，两边页码也跟着同步。 */
    public static void setPage(int page) {
        resolve();
        localPage = page;
        if (pageField == null) {
            return;
        }
        try {
            pageField.setInt(null, page);
        } catch (IllegalAccessException | RuntimeException ignored) {
            // 写不进去就只留本地状态
        }
    }
}
