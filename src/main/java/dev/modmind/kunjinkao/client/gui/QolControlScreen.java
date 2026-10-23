package dev.modmind.kunjinkao.client.gui;

import dev.modmind.kunjinkao.KunJinKaoPalette;
import dev.modmind.kunjinkao.client.qol.QolClientState;
import dev.modmind.kunjinkao.client.qol.QolKeybinds;
import dev.modmind.kunjinkao.client.qol.QolPanelConfig;
import dev.modmind.kunjinkao.network.NetworkHandler;
import dev.modmind.kunjinkao.network.QolSettingPayload;
import dev.modmind.kunjinkao.network.QolStatePayload;
import dev.modmind.kunjinkao.network.SimpleActionPayload;
import dev.modmind.kunjinkao.qol.QolSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 功能控制台：把模组的所有 QoL 功能集中到一页里开关与调值。
 * <p>
 * 这一页<b>不持有任何权威状态</b>。行上画的东西每帧都现读 {@link QolClientState} ——
 * 服务端推来新值、别的管理员在服务器上改了配置、或者权限被撤销，
 * 面板下一帧就跟着变，不需要重开界面，也不需要谁记得去刷新。
 * 本类里唯一的本地状态是"我刚请求过什么"，见 {@link #pendingValue}。
 * <p>
 * 控件全部自绘（分类标签、行、关闭按钮），因为原版 Button 画不出"整行可点 + 右侧状态列
 * + 权限徽章"这种排布；只有搜索框用原版 {@link EditBox}，输入法、光标、选中这些
 * 交给原版处理，比自己写一个稳。
 */
public final class QolControlScreen extends Screen {

    // ===== 布局常量（GUI 像素，与缩放无关）=====
    private static final int SIDE = 8;
    /** 标题栏高度：放得下标题与右上角的关闭按钮。 */
    private static final int TITLE_H = 24;
    private static final int SEARCH_H = 18;
    private static final int TAB_H = 18;
    /** 各条之间的竖直间距。 */
    private static final int GAP = 4;
    /** 上一次 render 的鼠标坐标（renderBackground 拿不到，只能这样带过去）。 */
    private int hoverMouseX = -1;
    private int hoverMouseY = -1;

    private static final int ROW_H = 26;
    /** 一屏最多画几行；再多就靠滚动。上限压着是为了面板别在超大分辨率下被拉成一条。 */
    private static final int MAX_ROWS = 8;
    private static final int CODE_BADGE_W = 24;
    private static final int BADGE_H = 12;
    private static final int CLOSE_W = 46;
    private static final int CLOSE_H = 14;
    private static final int TAB_GAP = 4;
    /** 权限徽章的最大宽度：够放「需要管理员权限」，也不至于把名字列挤没。 */
    private static final int BADGE_MAX_W = 140;
    /** 行尾快捷键格的常态宽度：放得下 F5 这种两三个字符的键名。 */
    private static final int BIND_W = 34;
    /** 快捷键格与权限徽章之间的间距，免得两个框贴在一起看起来像一个控件。 */
    private static final int BIND_GAP = 4;
    /**
     * 快捷键格的上限宽度。
     * <p>
     * 等待按键时格子里要放提示文案，各语言长短差很多，宽度得跟着文案算；
     * 但不能真的无限长 —— 格子在行尾，它一长就是从功能名那里抢地方。
     */
    private static final int BIND_MAX_W = 96;
    /** 未绑定时的占位符。不写「无」是为了不跟「已关闭」的文案撞脸。 */
    private static final String BIND_PLACEHOLDER = "\u2014";

    // ===== 配色 =====
    /** 面板底色：深蓝。用带 alpha 的值而不是不透明色，底下的世界还透得出来一点。 */
    private static final int COLOR_PANEL = 0xE0122030;
    private static final int COLOR_TEXT = 0xFFE9FBFF;
    /** 次级文字：分类标签、提示语。 */
    private static final int COLOR_DIM = 0xFF8FEAFF;
    /** 「已关闭」用的冷灰：与「已开启」的青色拉开，一眼能分清开关状态。 */
    private static final int COLOR_OFF = 0xFF9BB8C4;
    /** 半透明的青色蒙版：徽章底、标签选中底、行悬停底共用一份。 */
    private static final int COLOR_TINT = 0x4020A0C0;
    private static final int COLOR_ROW_HOVER = 0x40165A70;
    private static final int COLOR_SEPARATOR = 0x3020A0C0;

    /**
     * 数值行「在途」缓冲的有效期。
     * <p>
     * 滚轮连滚时包还没回来，若每次都拿只读镜像当基准，滚 5 格只会加 1。
     * 这里记下"我最后请求的值"，在服务端把它推回来之前用它当基准；
     * 超时（比如请求被服务端拒绝、值根本没变）就丢弃，回到镜像值，免得永远卡在幻值上。
     */
    private static final long PENDING_TTL_MS = 1000L;

    // ===== init() 算出的几何，render 与命中测试共用 =====
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int searchY;
    private int tabY;
    private int listTop;
    private int listRows;
    private int closeX;
    private int closeY;
    /** 权限徽章列宽：取三种文案里最宽的那个，保证各行的状态列对齐。 */
    private int badgeW = 48;

    // ===== 交互状态 =====
    private EditBox searchBox;
    /** 当前搜索词。过滤一律以它为准，不看 EditBox —— 重建界面时 EditBox 是新的。 */
    private String query = "";
    private QolSetting.Category selectedCategory = restoredCategory();
    private final List<QolSetting> visible = new ArrayList<>();
    private int scroll = QolPanelConfig.get().scrollY;
    private final List<int[]> tabBoxes = new ArrayList<>();
    private final List<QolSetting.Category> tabCategories = new ArrayList<>();

    /** 数值行最近一次请求的值与请求时刻，见 {@link #PENDING_TTL_MS}。 */
    private final int[] pendingValue = new int[QolSetting.COUNT];
    private final long[] pendingAt = new long[QolSetting.COUNT];
    private final boolean[] pendingValid = new boolean[QolSetting.COUNT];

    /**
     * 正在等玩家按一个键来绑定的那一项，null 表示没在等。
     * <p>
     * 只存在这一个"正在改哪一行"，同一时刻不可能绑两行 —— 键盘没有"正在按哪个键"的归属感，
     * 同时等两个键只能靠玩家自己记住顺序，那是给自己找麻烦。
     */
    private QolSetting listening;
    /**
     * 正在等按键的是哪一格。
     * <p>
     * 与 {@link #listening} 必须同进同出：只记设定项的话，带执行键的那一行就没法区分
     * "玩家点的是开关键格还是执行键格"，改出来的键会写进另一张表 —— 而且是静悄悄地写错。
     */
    private Cell listeningCell = Cell.TOGGLE;

    /**
     * 行尾按键格的种类。
     * <p>
     * 两种格子的行为完全不同：开关键按一下等于点这一行的开关（只有开关类项能绑），
     * 执行键按一下等于对当前选区开工（只有带执行动作的项才画这一格）。
     * 它们各自有独立的绑定表，所以命中测试也必须回答"是哪一种"，不能只回答"哪一行"。
     */
    private enum Cell {
        TOGGLE,
        ACTION
    }

    /** 命中结果：哪一行的哪一种格子。 */
    private record CellHit(QolSetting setting, Cell cell) {
    }

    public QolControlScreen() {
        super(Component.translatable("screen.kunjinkao.qol.title"));
    }

    /** 第一个有内容的分类。空分类不画标签，所以不能直接取 {@code values()[0]} 当默认选中项。 */
    private static QolSetting.Category firstCategory() {
        for (QolSetting.Category category : QolSetting.Category.values()) {
            if (QolSetting.hasAnyIn(category)) {
                return category;
            }
        }
        return QolSetting.Category.values()[0];
    }

    /**
     * 恢复上次选中的分类。
     * <p>
     * 配置文件里的分类名可能是手改的、也可能来自一个类别集合已经变过的旧版本，
     * 所以必须验证两件事：名字对得上、且这个类别现在确实有内容（空类别不画标签）。
     * 任何一条不满足就退回 {@link #firstCategory()}，而不是让面板选中一个画不出来的标签。
     */
    private static QolSetting.Category restoredCategory() {
        String saved = QolPanelConfig.get().selectedCategory;
        for (QolSetting.Category category : QolSetting.Category.values()) {
            if (category.name().equals(saved) && QolSetting.hasAnyIn(category)) {
                return category;
            }
        }
        return firstCategory();
    }

    /**
     * 关闭面板时把界面状态写回磁盘。
     * <p>
     * 刻意只在关闭时存一次、不在每次滚动时存：滚动一屏能触发几十次写盘，
     * 而这份文件唯一的作用就是"下次打开时还是上次那个样子"，丢最后一次中间状态无所谓。
     */
    @Override
    public void onClose() {
        QolPanelConfig.Data data = QolPanelConfig.get();
        data.selectedCategory = selectedCategory.name();
        data.scrollY = scroll;
        QolPanelConfig.save();
        super.onClose();
    }

    @Override
    protected void init() {
        panelW = Math.min(380, Math.max(160, width - 16));
        // 面板高度跟着屏幕走：矮屏少画几行（滚动兜底），高屏多画几行，不必写死。
        int chrome = TITLE_H + SEARCH_H + GAP + TAB_H + GAP + SIDE;
        int rows = (Math.max(80, height - 8) - chrome) / ROW_H;
        listRows = Math.max(1, Math.min(MAX_ROWS, rows));
        panelH = chrome + ROW_H * listRows;
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        searchY = panelY + TITLE_H;
        tabY = searchY + SEARCH_H + GAP;
        listTop = tabY + TAB_H + GAP;
        closeX = panelX + panelW - SIDE - CLOSE_W;
        closeY = panelY + 5;

        // 徽章列宽按当前语言实际像素算：英文文案比中文长得多，写死宽度会溢出面板。
        badgeW = Math.min(BADGE_MAX_W, Math.max(
                Math.max(font.width(Component.translatable("screen.kunjinkao.qol.needs_permission")),
                        font.width(Component.translatable("screen.kunjinkao.qol.scope_personal"))),
                font.width(Component.translatable("screen.kunjinkao.qol.scope_server"))) + 8);

        buildTabs();

        searchBox = new EditBox(font, panelX + SIDE, searchY, panelW - SIDE * 2, SEARCH_H,
                Component.translatable("screen.kunjinkao.qol.search_hint"));
        searchBox.setMaxLength(24);
        searchBox.setHint(Component.translatable("screen.kunjinkao.qol.search_hint"));
        searchBox.setResponder(this::onSearchChanged);
        // 改窗口大小会重跑 init()，EditBox 是新的；把上一个搜索词放回去，别让玩家的输入白打。
        searchBox.setValue(query);
        addRenderableWidget(searchBox);

        rebuildVisible();

        // 打开面板就主动要一次全量状态：镜像里可能还是上一个服务器的旧行（断线才清），
        // 不主动要一次的话，面板会一直画旧值直到服务端碰巧推包。
        NetworkHandler.sendToServer(new SimpleActionPayload(SimpleActionPayload.REQUEST_QOL_STATE));
    }

    private void buildTabs() {
        tabBoxes.clear();
        tabCategories.clear();
        int x = panelX + SIDE;
        for (QolSetting.Category category : QolSetting.Category.values()) {
            // 没有项的类别不画标签 —— 空标签点进去是一片空白，比不显示更让人困惑。
            if (!QolSetting.hasAnyIn(category)) {
                continue;
            }
            int w = font.width(Component.translatable(category.translationKey())) + 16;
            if (x + w > panelX + panelW - SIDE) {
                break;
            }
            tabBoxes.add(new int[]{x, tabY, w, TAB_H});
            tabCategories.add(category);
            x += w + TAB_GAP;
        }
    }

    private void onSearchChanged(String text) {
        query = text == null ? "" : text;
        // 换搜索词等于换了一个列表，滚动位置必须归零，否则会停在一片空白上。
        scroll = 0;
        rebuildVisible();
    }

    /**
     * 重算当前要显示的行。
     * <p>
     * 有搜索词时<b>忽略分类标签、跨全部类别</b>：玩家搜「89」时心里想的是某一项功能，
     * 不是"便捷分类下的第 89 号"，再让他先点对标签就是多余的一步。
     */
    private void rebuildVisible() {
        visible.clear();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            for (QolSetting setting : QolSetting.values()) {
                // 用 inCategory 而不是 category() 直接比：服务器级那个标签是汇总视图，
                // 要把它自己分类下的服务器级项也捞进来（见 QolSetting#inCategory）。
                if (QolSetting.inCategory(setting, selectedCategory)) {
                    visible.add(setting);
                }
            }
        } else {
            for (QolSetting setting : QolSetting.values()) {
                // 搜索同样只搜面板上的项：服务器级项不在面板里，搜出来也点不动，
                // 反而会让人以为"面板能改服务器配置"。
                if (setting.panelVisible() && matches(setting, needle)) {
                    visible.add(setting);
                }
            }
        }
        scroll = clampScroll(scroll);
    }

    private int clampScroll(int value) {
        return Math.max(0, Math.min(Math.max(0, visible.size() - listRows), value));
    }

    /**
     * 三种匹配方式：序号（89）、枚举名（reach）、当前语言下的显示名（长臂善舞）。
     * <p>
     * 序号用 contains 而不是 equals：输入「8」时把 80/83/85/88/89 都列出来，
     * 玩家记不清后两位时还能往下挑；搜到更多不是错，搜不到才是。
     */
    private static boolean matches(QolSetting setting, String needle) {
        if (Integer.toString(setting.code()).contains(needle)) {
            return true;
        }
        if (setting.name().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        // 显示名必须按当前语言取：写死中文的话，英文客户端上按名字搜什么都搜不到。
        return Component.translatable(setting.translationKey()).getString()
                .toLowerCase(Locale.ROOT).contains(needle);
    }

    private boolean isSearching() {
        return !query.trim().isEmpty();
    }

    // ===== 绘制 =====

    /**
     * 1.21.1 的 {@code Screen.render} 先调 renderBackground，之后才画控件。
     * 面板、标签、行都必须画在这里：写在 render 里画在 super.render 之前会被背景糊掉，
     * 之后又会盖住搜索框。
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawChrome(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    /** 面板/标题/文字：1.20.1 的 Screen#render 不会调用 renderBackground，必须由 render 自己调。 */
    private void drawChrome(GuiGraphics graphics) {
        // 1.20.1 的 renderBackground 只有单参，拿不到鼠标坐标；用 render() 里记下的副本
        // （悬停高亮与提示都依赖它）。1.21 的四参重载能直接拿到。
        int mouseX = this.hoverMouseX;
        int mouseY = this.hoverMouseY;
        super.renderBackground(graphics);

        KunJinKaoUi.panel(graphics, panelX, panelY, panelW, panelH);
        
        KunJinKaoUi.titleText(graphics, font, title, panelX + 8, panelY + 5);

        drawCloseButton(graphics, mouseX, mouseY);
        drawTabStrip(graphics, mouseX, mouseY);
        drawRows(graphics, mouseX, mouseY);
        // 提示画在最后：它是压在内容上面的一层，先画就会被后面的行盖掉。
        drawBindTooltip(graphics, mouseX, mouseY);
        drawRowTooltip(graphics, mouseX, mouseY);
    }

    /**
     * 鼠标停在某一行上时给出这一项到底干什么。
     * <p>
     * 行里只有名字和当前值，「万民敬仰 0 / 5」这种写法不查资料是猜不出效果的。
     * 说明做成悬停提示而不是塞进行内，是因为行高只有 26 像素，
     * 塞进去要么把行撑成两行、要么把字缩到看不清。
     * <p>
     * 悬停在行尾快捷键格上时不弹它 —— 那一刻玩家要的是「这个键怎么绑」，不是功能说明。
     */
    private void drawRowTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (bindCellAt(mouseX, mouseY) != null) {
            return;
        }
        int index = rowIndexAt(mouseX, mouseY);
        if (index < 0 || index >= visible.size()) {
            return;
        }
        QolSetting setting = visible.get(index);
        graphics.renderTooltip(font,
                Component.translatable(setting.translationKey() + ".desc"), mouseX, mouseY);
    }

    /**
     * 右上角关闭按钮。
     * <p>
     * 自绘而不是用原版 Button：面板其余部分都是自绘的，混一个原版灰色按钮在标题栏上
     * 会显得是别处拼来的；而且悬停时能直接切到语义色红，一眼看出"这是退出"。
     */
    private void drawCloseButton(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean hovered = inside(mouseX, mouseY, closeX, closeY, CLOSE_W, CLOSE_H);
        if (hovered) {
            graphics.fill(closeX, closeY, closeX + CLOSE_W, closeY + CLOSE_H,
                    KunJinKaoPalette.alpha(KunJinKaoPalette.ERROR, 0.25F));
        }
        drawBorder(graphics, closeX, closeY, CLOSE_W, CLOSE_H,
                hovered ? KunJinKaoPalette.ERROR : KunJinKaoPalette.NORMAL);
        graphics.drawCenteredString(font, Component.translatable("screen.kunjinkao.qol.close"),
                closeX + CLOSE_W / 2, closeY + (CLOSE_H - 8) / 2,
                hovered ? KunJinKaoPalette.ERROR : COLOR_TEXT);
    }

    /**
     * 分类标签条。搜索时这一条改成显示「找到 N 项」——
     * 搜索本来就跨类别，标签这时候既不能点也没有意义，占着位置不如报个数。
     */
    private void drawTabStrip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (isSearching()) {
            graphics.drawString(font,
                    Component.translatable("screen.kunjinkao.qol.search_result", visible.size()),
                    panelX + SIDE, tabY + (TAB_H - 8) / 2, KunJinKaoPalette.ACCEL, false);
            return;
        }
        for (int i = 0; i < tabBoxes.size(); i++) {
            int[] box = tabBoxes.get(i);
            QolSetting.Category category = tabCategories.get(i);
            boolean selected = category == selectedCategory;
            boolean hovered = inside(mouseX, mouseY, box[0], box[1], box[2], box[3]);
            if (selected || hovered) {
                graphics.fill(box[0], box[1], box[0] + box[2], box[1] + box[3],
                        selected ? COLOR_TINT : COLOR_ROW_HOVER);
            }
            if (selected) {
                drawBorder(graphics, box[0], box[1], box[2], box[3], KunJinKaoPalette.NORMAL);
            }
            graphics.drawCenteredString(font, Component.translatable(category.translationKey()),
                    box[0] + box[2] / 2, box[1] + (TAB_H - 8) / 2,
                    selected ? KunJinKaoPalette.NORMAL : COLOR_DIM);
        }
    }

    private void drawRows(GuiGraphics graphics, int mouseX, int mouseY) {
        if (visible.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.kunjinkao.qol.empty"),
                    panelX + panelW / 2, listTop + ROW_H * listRows / 2 - 4, COLOR_DIM);
            return;
        }
        int hoveredIndex = rowIndexAt(mouseX, mouseY);
        for (int i = 0; i < listRows; i++) {
            int index = scroll + i;
            if (index >= visible.size()) {
                break;
            }
            drawRow(graphics, visible.get(index), listTop + i * ROW_H, index == hoveredIndex);
        }
    }

    private void drawRow(GuiGraphics graphics, QolSetting setting, int rowY, boolean hovered) {
        int rowX = panelX + SIDE;
        int rowW = panelW - SIDE * 2;
        int rowH = ROW_H - 2;
        int textY = rowY + (rowH - 8) / 2;

        // 每帧现读镜像：服务端一改，下一帧这里就是新值。任何"上次画的是什么"的缓存都会
        // 让面板显示过期的权威状态。
        QolStatePayload row = QolClientState.row(setting);
        boolean syncing = row == null;
        boolean authorized = !syncing && row.authorized();

        if (hovered) {
            graphics.fill(rowX, rowY, rowX + rowW, rowY + rowH, COLOR_ROW_HOVER);
        }
        graphics.fill(rowX, rowY + ROW_H - 1, rowX + rowW, rowY + ROW_H, COLOR_SEPARATOR);

        // 名字列从行首开始，不再留序号徽章的位置。
        // 那个 80/83/85 是开发计划内部的功能编号（对应参考模组的技能 id），对玩家没有任何意义，
        // 面板上摆一排四位数字只会占地方。它唯一的实际用处 —— 按序号搜索 ——
        // 保留在 matches() 里：输入 89 仍然能定位到「长臂善舞」。
        int nameX = rowX + 4;

        // 状态列：先算出文案与颜色，好把名字列裁到不撞上它。
        Component stateText;
        int stateColor;
        if (syncing) {
            // 还没收到这一行：不猜、不画默认值，直接说在同步。
            stateText = Component.translatable("screen.kunjinkao.qol.syncing");
            stateColor = COLOR_OFF;
        } else if (setting.kind() == QolSetting.Kind.TOGGLE) {
            boolean on = row.enabled();
            stateText = Component.translatable(on
                    ? "screen.kunjinkao.qol.state_on" : "screen.kunjinkao.qol.state_off");
            stateColor = on ? KunJinKaoPalette.NORMAL : COLOR_OFF;
        } else if (setting.kind() == QolSetting.Kind.ACTION) {
            // 动作类没有"值"可显示。这一行唯一需要让人看懂的是"它靠什么触发" ——
            // 值那一列写一句提示，比画一条永远满格的数值条有用。
            stateText = Component.translatable("screen.kunjinkao.qol.action_ready");
            stateColor = KunJinKaoPalette.CORRUPT;
        } else if (setting.kind() == QolSetting.Kind.CHOICE) {
            // 选项类画选项名而不是数字：玩家要的是"破坏还是放置"，不是"0 还是 1"。
            // 数字只留给真正的区间项 —— 那里除了数字没有别的说法。
            stateText = choiceName(setting, QolClientState.value(setting));
            stateColor = KunJinKaoPalette.ACCEL;
        } else {
            // 上限取服务端下发的那个（含服务器配置），不是本项硬上限。
            stateText = Component.literal(QolClientState.value(setting) + " / " + QolClientState.max(setting));
            stateColor = KunJinKaoPalette.ACCEL;
        }

        // 快捷键格钉在行尾，权限徽章与状态列整体左移让位 —— 键是行上最不需要读的东西，
        // 放最右边既好找又不打断"序号 → 名字 → 状态 → 权限"这条阅读顺序。
        // 宽度交给 bindCellWidth：画和点用同一个函数，不会出现"看着能点、点上去没反应"。
        int bindW = bindCellWidth(setting, listening == setting && listeningCell == Cell.TOGGLE);
        int bindX = rowX + rowW - 4 - bindW;
        // 执行键格往左排，而不是把开关键格挤开：开关键格各行的位置必须对齐在同一列上，
        // 否则一眼扫下去会以为"这一行的键和别人不一样"。多出来的格子只能往左长。
        int actionW = 0;
        int actionX = bindX;
        if (setting.hasExecuteAction()) {
            actionW = bindCellWidth(setting, listening == setting && listeningCell == Cell.ACTION);
            actionX = bindX - BIND_GAP - actionW;
        }
        int badgeX = actionX - BIND_GAP - badgeW;
        Component badgeText;
        int badgeColor;
        if (syncing) {
            badgeText = Component.translatable(setting.scope() == QolSetting.Scope.SERVER
                    ? "screen.kunjinkao.qol.scope_server" : "screen.kunjinkao.qol.scope_personal");
            badgeColor = COLOR_OFF;
        } else if (authorized) {
            badgeText = Component.translatable(setting.scope() == QolSetting.Scope.SERVER
                    ? "screen.kunjinkao.qol.scope_server" : "screen.kunjinkao.qol.scope_personal");
            badgeColor = setting.scope() == QolSetting.Scope.SERVER
                    ? KunJinKaoPalette.CORRUPT : KunJinKaoPalette.NORMAL;
        } else {
            // 没权限时徽章改说原因：这一行仍会画出服务端发来的当前值，
            // 玩家看得见"功能存在、值是多少、但我动不了"。
            badgeText = Component.translatable("screen.kunjinkao.qol.needs_permission");
            badgeColor = COLOR_OFF;
        }

        // 未授权整行降饱和度：不用图标或额外标记，颜色本身就说明"这行不能动"。
        int nameColor = authorized || syncing ? COLOR_TEXT : desaturate(COLOR_TEXT);
        int shownStateColor = authorized || syncing ? stateColor : desaturate(stateColor);

        int stateX = badgeX - 8 - font.width(stateText);
        graphics.drawString(font, stateText, stateX, textY, shownStateColor, false);

        int nameMax = Math.max(20, stateX - 8 - nameX);
        graphics.drawString(font,
                font.plainSubstrByWidth(
                        Component.translatable(setting.translationKey()).getString(), nameMax),
                nameX, textY, nameColor, false);

        int badgeY = rowY + (rowH - BADGE_H) / 2;
        graphics.fill(badgeX, badgeY, badgeX + badgeW, badgeY + BADGE_H,
                KunJinKaoPalette.alpha(badgeColor, authorized ? 0.18F : 0.10F));
        drawBorder(graphics, badgeX, badgeY, badgeW, BADGE_H, badgeColor);
        graphics.drawCenteredString(font, badgeText, badgeX + badgeW / 2, badgeY + (BADGE_H - 8) / 2,
                badgeColor);

        drawBindCell(graphics, setting, Cell.TOGGLE, bindX, badgeY, bindW, authorized || syncing);
        if (setting.hasExecuteAction()) {
            // 先画开关键格、再画执行键格：两格的位置算式是同一个来源，先后不影响结果，
            // 这里只是与 drawRow 里"从左到右"的读法保持一致。
            drawBindCell(graphics, setting, Cell.ACTION, actionX, badgeY, actionW, authorized || syncing);
        }
    }

    /**
     * 选项类项当前值对应的选项名。
     * <p>
     * 夹一次范围再拼键：值是服务端下发的，理论上越不了界，但真越界时拼出来的是一个
     * 不存在的语言键（画面上显示键名本身），而不是抛异常 —— 面板不该因为一个坏值整个画不出来。
     */
    private static Component choiceName(QolSetting setting, int value) {
        int min = setting.min();
        int max = Math.max(min, QolClientState.max(setting));
        return Component.translatable(setting.choiceKey(Math.max(min, Math.min(max, value))));
    }

    /**
     * 行尾的按键格。
     * <p>
     * 两种格子的内容来源不同（{@link QolPanelConfig#toggleKey} 与
     * {@link QolPanelConfig#actionKey}），但画法完全一样，所以共用这一个方法。
     * <p>
     * 开关类的格子对非开关项画成禁用态：干脆不画的话，玩家分不清"这个功能不支持开关快捷键"
     * 和"面板里就是不能绑"，留个灰格子至少说明前者。
     * <p>
     * 未授权时跟着整行一起降饱和度，但<b>仍然可以点</b>：绑定是本机偏好，
     * 跟服务端授不授权无关；真按下去时 {@link QolKeybinds} 会挡住不发包，
     * 不需要在这一层提前禁止，否则权限恢复后玩家还得重新绑一遍。
     */
    private void drawBindCell(GuiGraphics graphics, QolSetting setting, Cell cell,
                              int x, int y, int w, boolean bright) {
        boolean listeningHere = listening == setting && listeningCell == cell;
        Component text;
        int color;
        if (listeningHere) {
            color = KunJinKaoPalette.ACCEL;
            text = Component.translatable("screen.kunjinkao.qol.bind_listening");
        } else if (cell == Cell.TOGGLE && setting.kind() != QolSetting.Kind.TOGGLE) {
            color = COLOR_OFF;
            text = Component.literal(BIND_PLACEHOLDER);
        } else {
            int code = cell == Cell.ACTION ? QolPanelConfig.actionKey(setting) : QolPanelConfig.toggleKey(setting);
            if (code == 0) {
                color = COLOR_DIM;
                text = Component.literal(BIND_PLACEHOLDER);
            } else {
                color = KunJinKaoPalette.NORMAL;
                text = Component.literal(keyName(code));
            }
        }
        if (!bright) {
            color = desaturate(color);
        }
        graphics.fill(x, y, x + w, y + BADGE_H,
                listeningHere ? KunJinKaoPalette.alpha(KunJinKaoPalette.ACCEL, 0.30F)
                        : KunJinKaoPalette.alpha(color, 0.14F));
        drawBorder(graphics, x, y, w, BADGE_H, color);
        // 键名和提示文案都可能比格子宽（长键名、长句子），裁一下总比让它画出格子外好。
        graphics.drawCenteredString(font,
                Component.literal(font.plainSubstrByWidth(text.getString(), w - 6)),
                x + w / 2, y + (BADGE_H - 8) / 2, color);
    }

    /**
     * 从 GLFW key code 取显示用的键名。
     * <p>
     * 交给原版翻而不是自己拼：这样 F5、G、方向键在各语言下都是同一套说法，
     * 跟原版按键设置界面里显示的也一致。
     */
    private static String keyName(int code) {
        return InputConstants.getKey(code, 0).getDisplayName().getString();
    }

    /**
     * 快捷键格这一帧该有多宽。
     * <p>
     * 只有"等按键"这一种状态需要变宽：格子里那一刻放的是提示文案，写死宽度会把
     * 一句话截成半句；其余状态是键名，最长也就 F5 那种，固定宽度反而让各行对齐。
     */
    private int bindCellWidth(QolSetting setting, boolean listeningHere) {
        if (!listeningHere) {
            return BIND_W;
        }
        return Math.min(BIND_MAX_W, Math.max(BIND_W,
                font.width(Component.translatable("screen.kunjinkao.qol.bind_listening")) + 8));
    }

    /**
     * 某一行的某个按键格的矩形，与 {@link #drawRow} 里那套算式逐项对应。
     * <p>
     * 行高用 {@code ROW_H - 2}（行与行之间留了一条分隔线的位置），
     * 所以命中测试必须用同一个值，否则会点到上一行的格子上。
     *
     * @return 该格子不存在（这一行没有执行键格）时返回 null
     */
    private int[] bindCellBox(int index, Cell cell) {
        QolSetting setting = visible.get(index);
        int rowX = panelX + SIDE;
        int rowW = panelW - SIDE * 2;
        int rowY = listTop + (index - scroll) * ROW_H;
        int y = rowY + (ROW_H - 2 - BADGE_H) / 2;
        int toggleW = bindCellWidth(setting, listening == setting && listeningCell == Cell.TOGGLE);
        int toggleX = rowX + rowW - 4 - toggleW;
        if (cell == Cell.TOGGLE) {
            return new int[]{toggleX, y, toggleW, BADGE_H};
        }
        if (!setting.hasExecuteAction()) {
            return null;
        }
        int actionW = bindCellWidth(setting, listening == setting && listeningCell == Cell.ACTION);
        return new int[]{toggleX - BIND_GAP - actionW, y, actionW, BADGE_H};
    }

    /** 鼠标指在哪个按键格上；不在任何格子上返回 null。 */
    private CellHit bindCellAt(double mouseX, double mouseY) {
        int index = rowIndexAt(mouseX, mouseY);
        if (index < 0) {
            return null;
        }
        // 两格互不重叠，先判哪个都不影响结果；这里按"先看多出来的执行键格"的读法写。
        for (Cell cell : new Cell[]{Cell.ACTION, Cell.TOGGLE}) {
            int[] box = bindCellBox(index, cell);
            if (box != null && inside(mouseX, mouseY, box[0], box[1], box[2], box[3])) {
                return new CellHit(visible.get(index), cell);
            }
        }
        return null;
    }

    /**
     * 按键格上的悬停提示。
     * <p>
     * 第一行永远说明"这一格是干什么的"：带执行键的行上有两个格子，光看格子本身
     * 分不出哪个是开关键、哪个是执行键（都是一个方框加一个键名）。第二行只在
     * "空格子是什么"或"灰格子为什么不能绑"时才补 —— 已经绑好的格子不必再念一遍键名。
     */
    private void drawBindTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (listening != null) {
            return;
        }
        CellHit hit = bindCellAt(mouseX, mouseY);
        if (hit == null) {
            return;
        }
        QolSetting setting = hit.setting();
        List<Component> lines = new ArrayList<>(2);
        if (hit.cell() == Cell.ACTION) {
            lines.add(Component.translatable("screen.kunjinkao.qol.bind_action_cell"));
            if (QolPanelConfig.actionKey(setting) == 0) {
                lines.add(Component.translatable("screen.kunjinkao.qol.bind_unbound"));
            }
        } else {
            lines.add(Component.translatable("screen.kunjinkao.qol.bind_toggle_cell"));
            if (setting.kind() != QolSetting.Kind.TOGGLE) {
                lines.add(Component.translatable("screen.kunjinkao.qol.bind_toggle_only"));
            } else if (QolPanelConfig.toggleKey(setting) == 0) {
                lines.add(Component.translatable("screen.kunjinkao.qol.bind_unbound"));
            }
        }
        if (lines.size() == 1) {
            graphics.renderTooltip(font, lines.get(0), mouseX, mouseY);
        } else {
            graphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
        }
    }

    /**
     * 降饱和度：按人眼亮度权重把它拉向灰。
     * <p>
     * 不做成纯灰是因为整行还有信息要读（值是多少）；保留一点原色，既看得出"这项是青的/琥珀的"，
     * 又明显比可操作的行暗一档。
     */
    private static int desaturate(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int gray = (r * 30 + g * 59 + b * 11) / 100;
        return (alpha << 24) | (((r + gray * 2) / 3) << 16) | (((g + gray * 2) / 3) << 8) | ((b + gray * 2) / 3);
    }

    // ===== 交互 =====

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 先让控件（搜索框）处理：点进搜索框要能聚焦、能拖选，
        // 自定义区域与搜索框不重叠，所以先给控件不会漏掉任何点击。
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        // 按键格要用到右键（清除绑定），所以它排在下面那句 "只认左键" 之前。
        // 命中结果只算一次：下面"点别处"的判定与"点在格子上"的处理必须看同一个答案，
        // 各算一次的话，两次之间改了 listening 就会得出不同的结论。
        CellHit hit = button <= 1 ? bindCellAt(mouseX, mouseY) : null;
        // 正在等按键时点别处 = 放弃这次改键。顺手把这一下吃掉：
        // 玩家本来就还想按键、只是点歪了，不该顺手把某一行开关翻过来。
        // 点回自己那个格子（左键重进、右键清除）不算"点别处"，继续往下走。
        if (listening != null
                && !(hit != null && hit.setting() == listening && hit.cell() == listeningCell)) {
            listening = null;
            return true;
        }
        if (hit != null) {
            if (hit.cell() == Cell.TOGGLE && hit.setting().kind() != QolSetting.Kind.TOGGLE) {
                // 非开关项的开关键格是禁用态，点了什么都不该发生（提示由悬停给）。
                return true;
            }
            if (button == 0) {
                listening = hit.setting();
                listeningCell = hit.cell();
                // 把焦点从搜索框拿走：不然光标还在那里闪，看着像输入法要收字符。
                // 按键本来就会被下面的 keyPressed 拦下，这一步纯粹是为了画面不矛盾。
                setFocused(null);
            } else {
                listening = null;
                writeBind(hit.setting(), hit.cell(), 0);
                QolPanelConfig.save();
            }
            return true;
        }
        if (button != 0) {
            return false;
        }
        if (inside(mouseX, mouseY, closeX, closeY, CLOSE_W, CLOSE_H)) {
            onClose();
            return true;
        }
        if (!isSearching()) {
            for (int i = 0; i < tabBoxes.size(); i++) {
                int[] box = tabBoxes.get(i);
                if (inside(mouseX, mouseY, box[0], box[1], box[2], box[3])) {
                    selectedCategory = tabCategories.get(i);
                    scroll = 0;
                    rebuildVisible();
                    return true;
                }
            }
        }
        int index = rowIndexAt(mouseX, mouseY);
        if (index >= 0) {
            QolSetting setting = visible.get(index);
            // 区间项点一下不改值：那会让"点空白处不小心改了数字"，滚轮才有明确的方向感。
            // 选项项只有两三档，点一下往后换一个与滚轮同向，比要求玩家必须滚轮更顺手。
            if (setting.kind() == QolSetting.Kind.TOGGLE) {
                toggle(setting);
            } else if (setting.kind() == QolSetting.Kind.CHOICE) {
                cycleChoice(setting, 1);
            }
            return true;
        }
        return false;
    }

    /**
     * 等按键时接管整个键盘。
     * <p>
     * 必须在最前面把这一下吃掉：{@link Screen#keyPressed} 认得 Esc，会让整个面板关掉 ——
     * 玩家想取消改键，结果面板没了，之前滚到的位置也白滚了（界面状态虽然存了，但他的意图落空）。
     * Delete 与 Backspace 跟 Esc 同义（"把这个键去掉"），玩家按哪个都符合直觉，
     * 不用先记住"取消是 Esc、清除是 Delete"。
     * <p>
     * 除了这三个键，按什么都算绑定 —— 包括 Shift 这类修饰键。听起来怪，但"按住 Shift 触发"
     * 在游戏里本来就有先例（潜行、快速调值），强行禁掉反而要多解释一句"为什么这个键不行"。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening == null) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        QolSetting setting = listening;
        Cell cell = listeningCell;
        listening = null;
        // Esc / Delete / Backspace 都是"把这个键去掉"：玩家按哪个都符合直觉，
        // 不用先记住"取消是 Esc、清除是 Delete"。
        writeBind(setting, cell, keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_DELETE
                || keyCode == GLFW.GLFW_KEY_BACKSPACE ? 0 : keyCode);
        // 改一次就落盘，不等关面板：快捷键是"配好就不想再想起"的东西，
        // 攒着一起存的话，改完立刻崩一次或者被踢出服务器就白配了。
        QolPanelConfig.save();
        return true;
    }

    /** 把一次改键写进对应的那张表：开关键与执行键各存一份，格子的种类决定写哪一张。 */
    private static void writeBind(QolSetting setting, Cell cell, int keyCode) {
        if (cell == Cell.ACTION) {
            QolPanelConfig.setActionKey(setting, keyCode);
        } else {
            QolPanelConfig.setToggleKey(setting, keyCode);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (scrollY == 0.0D) {
            return super.mouseScrolled(mouseX, mouseY, scrollY);
        }
        int index = rowIndexAt(mouseX, mouseY);
        if (index >= 0) {
            QolSetting setting = visible.get(index);
            QolStatePayload row = QolClientState.row(setting);
            if (setting.kind() == QolSetting.Kind.CHOICE && row != null && row.authorized()) {
                // 选项类到头绕回另一端：只有两三档，"顶住了不动"会让人以为滚轮坏了。
                cycleChoice(setting, scrollY > 0.0D ? 1 : -1);
                return true;
            }
            if (setting.kind() == QolSetting.Kind.NUMBER && row != null && row.authorized()) {
                // 向上滚加、向下滚减；Shift 一次 10 格，方便在上百的区间里快速走。
                stepValue(setting, scrollY > 0.0D ? 1 : -1, Screen.hasShiftDown());
                return true;
            }
            // 区间行没同步/没权限时不吃滚轮：否则指针一停在这两行上，列表就滚不动了。
        }
        if (!inside(mouseX, mouseY, panelX + SIDE, listTop, panelW - SIDE * 2, ROW_H * listRows)) {
            return super.mouseScrolled(mouseX, mouseY, scrollY);
        }
        int next = clampScroll(scroll + (scrollY > 0.0D ? -1 : 1));
        if (next == scroll) {
            return super.mouseScrolled(mouseX, mouseY, scrollY);
        }
        scroll = next;
        return true;
    }

    /** 整行点击：只翻开关。是否生效由服务端说了算，这里不做任何乐观改动。 */
    private void toggle(QolSetting setting) {
        QolStatePayload row = QolClientState.row(setting);
        if (row == null || !row.authorized()) {
            // 没同步就把请求发出去，等于拿一个自己都不知道的值做翻转；
            // 没权限发出去也只会被服务端丢掉。两种都直接不发。
            return;
        }
        NetworkHandler.sendToServer(new QolSettingPayload(setting.id(), row.enabled() ? 0 : 1));
    }

    private void stepValue(QolSetting setting, int direction, boolean coarse) {
        QolStatePayload row = QolClientState.row(setting);
        if (row == null || !row.authorized()) {
            return;
        }
        int max = QolClientState.max(setting);
        int base = stepBase(setting);
        int next = Math.max(row.min(), Math.min(max, base + direction * (coarse ? 10 : 1)));
        if (next == base) {
            // 已经顶到上下限：停住，不发这种"值没变"的包，免得白占带宽。
            return;
        }
        requestValue(setting, next);
    }

    /**
     * 选项类项：换到相邻的下一个取值，到头绕回另一端。
     * <p>
     * 与 {@link #stepValue} 的"夹取"不同，这里刻意是环绕：选项只有两三个，
     * 到头停住会让玩家以为这个格子的滚轮坏了；而区间项有一百多档，环绕反而是灾难
     * （从 0 一下跳到 255）。
     */
    private void cycleChoice(QolSetting setting, int direction) {
        QolStatePayload row = QolClientState.row(setting);
        if (row == null || !row.authorized()) {
            return;
        }
        int min = row.min();
        int max = Math.max(min, QolClientState.max(setting));
        int span = max - min + 1;
        int current = Math.max(min, Math.min(max, stepBase(setting)));
        requestValue(setting, min + Math.floorMod(current - min + direction, span));
    }

    /** 把"我要把这一项改成 N"记成在途请求并发出去；在途状态见 {@link #PENDING_TTL_MS}。 */
    private void requestValue(QolSetting setting, int value) {
        int id = setting.id();
        pendingValid[id] = true;
        pendingValue[id] = value;
        pendingAt[id] = System.currentTimeMillis();
        NetworkHandler.sendToServer(new QolSettingPayload(id, value));
    }

    /**
     * 滚轮连滚时的基准值。
     * <p>
     * 请求还在路上（服务端还没把新值推回来）时用"我请求过的值"接着加，
     * 否则连滚 5 格会因为基准一直是旧值而只加 1。请求被服务端拒绝或改变时镜像值会一直对不上，
     * 所以给在途状态加了过期时间：超时就认镜像值，面板不会卡在一个服务端并不认可的数字上。
     */
    private int stepBase(QolSetting setting) {
        int id = setting.id();
        int server = QolClientState.value(setting);
        if (!pendingValid[id]) {
            return server;
        }
        if (server == pendingValue[id] || System.currentTimeMillis() - pendingAt[id] > PENDING_TTL_MS) {
            pendingValid[id] = false;
            return server;
        }
        return pendingValue[id];
    }

    /** 命中哪一行（返回 {@link #visible} 的下标），不在列表里返回 -1。 */
    private int rowIndexAt(double mouseX, double mouseY) {
        if (!inside(mouseX, mouseY, panelX + SIDE, listTop, panelW - SIDE * 2, ROW_H * listRows)) {
            return -1;
        }
        int index = scroll + (int) ((mouseY - listTop) / ROW_H);
        return index >= 0 && index < visible.size() ? index : -1;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static void drawBorder(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    /**
     * 不暂停游戏。
     * <p>
     * 与模组里其它界面一致：这是服务器上的功能面板，多人游戏里根本没有"暂停"这回事，
     * 单人游戏里暂停也会让面板表现和多人时不一致。反正所有改动都要等服务端回执。
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
