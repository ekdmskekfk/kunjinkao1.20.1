package dev.modmind.kunjinkao.client.gui;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.client.KunJinKaoKeyBindings;
import dev.modmind.kunjinkao.client.gui.QolControlScreen;
import dev.modmind.kunjinkao.network.NetworkHandler;
// 9 个剑设置包已合并为 SwordSettingPayload，导入与发包点一起收敛。
import dev.modmind.kunjinkao.network.PlacementSyncPayload;
import dev.modmind.kunjinkao.network.RemovePlacementPayload;
import dev.modmind.kunjinkao.network.SwordSettingPayload;
import dev.modmind.kunjinkao.world.SwordAreaFields;
import dev.modmind.kunjinkao.world.SwordPlacementRegistry;
import dev.modmind.kunjinkao.world.SwordToolHandler;
import dev.modmind.kunjinkao.block.entity.AcceleratorBlockEntity;
import dev.modmind.kunjinkao.world.SwordTimeAcceleration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 管理员剑设置界面（. 键）。
 * <p>
 * 布局为「分区标题 + 带边框方框 + 框内双列按钮」：每个分区一个标题和一只方框，
 * 框内按两列摆该分区的选项，按钮文字形如「名称：值」。配色沿用本模组战术 HUD 的
 * 青色主题（深蓝面板 0xE0122030 / 青色描边 0xFF57CFFF / 浅青文字），控件仍用原版
 * 按钮与滑条，和改动前保持一致。
 * <p>
 * 蓝屏打击只是物品上的一个开关标记，绝不会在本机启动任何程序。
 */
public final class SwordOptionsScreen extends Screen {

    // ===== 布局常量（单位是 GUI 像素，与缩放无关）=====
    private static final int SIDE_PAD = 6;
    private static final int TITLE_H = 20;
    private static final int HEADER_H = 14;
    private static final int BOX_PAD = 5;
    private static final int BTN_H = 18;
    private static final int BTN_GAP = 3;
    private static final int ROW_H = BTN_H + BTN_GAP;
    private static final int SECTION_MAX_W = 190;
    private static final int SECTION_GAP = 8;

    // ===== 配色（与原面板一致）=====
    private static final int COLOR_PANEL = 0xE0122030;
    private static final int COLOR_BORDER = 0xFF57CFFF;
    private static final int COLOR_TITLE = 0xFFE9FBFF;
    private static final int COLOR_SECTION = 0xFF8FEAFF;

    private final InteractionHand hand;

    // ===== 9 个控件 =====
    private Button blueScreenToggle;
    private Button areaClearModeButton;
    private AttackDamageSlider attackDamageSlider;
    private Button unbreakableBlockToggle;
    private MiningSpeedSlider miningSpeedSlider;
    private OreDropMultiplierSlider oreDropMultiplierSlider;
    private LootingLevelSlider lootingLevelSlider;
    private Button ultimateDeathToggle;
    private Button quitStrikeToggle;
    private Button constructionWandToggle;
    private Button angelCoreToggle;
    private Button destructionCoreToggle;
    private Button placementUndoToggle;
    private Button brushToggle;
    private Button toolModeButton;
    private Button lightningRodToggle;
    private Button wrenchToggle;
    private Button accelModeButton;
    private Button accelMultiplierButton;
    private Button beheadingToggle;
    private Button spawnEggDropToggle;
    private Button silkTouchToggle;
    private Button autoSmeltToggle;

    // ===== 分页 =====
    /** 0 = 设置页，1 = 放置记录页。 */
    private int page;
    /** 放置记录页当前页。 */
    private int recordPage;

    private Button areaModeButton;
    private Button pauseSizeButton;
    private Button areaAccelSizeButton;
    private Button recordsPrevButton;
    private Button recordsBackButton;
    private Button openRecordsButton;
    private Button areaAccelMultiplierButton;
    private Button recordsNextButton;
    /** 放置记录页每页显示几条。 */
    private static final int RECORDS_PER_PAGE = 7;
    /** 记录页每行行距，比按钮略高，免得文字贴着上一行。 */
    private static final int RECORD_ROW_STEP = BTN_H + 2;
    /** 当前这一页要画的记录文字，renderBackground 用。 */
    private final List<Component> recordLines = new ArrayList<>();
    private int recordTextX;
    private int recordTextTop;

    // ===== init() 算出的面板与分区几何，renderBackground 复用 =====
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private final List<int[]> sectionBoxes = new ArrayList<>();
    private final List<Component> sectionTitles = new ArrayList<>();
    private final List<int[]> sectionTitlePos = new ArrayList<>();

    // ===== 滑条发包节流 =====
    // 三个滑条原来每变化一格就发一个包，拖一次最坏约 1000 个包（掉落倍数 1..1000）。
    // 规则：同一 settingId 距上次真正发送 < 100ms，且数值相对上次发送没有跨过一"档"时跳过；
    // 被跳过的数值记在 pending 里，render 每帧检查（窗口一过就补发）并在关屏时强制补发一次，
    // 保证玩家最终选定的值一定到达服务端，不会因为节流丢设置。
    // 开关/循环按钮不走节流：它们是离散操作，连点两下必须发两次。
    private static final int SETTING_COUNT = 25;
    private static final long SEND_INTERVAL_MS = 100L;
    private static final int TIER_COUNT = 20;
    private final long[] lastSendMillis = new long[SETTING_COUNT];
    private final int[] lastSentValue = new int[SETTING_COUNT];
    private final boolean[] hasPending = new boolean[SETTING_COUNT];
    private final int[] pendingValue = new int[SETTING_COUNT];

    public SwordOptionsScreen(InteractionHand hand) {
        super(Component.translatable("screen.kunjinkao.settings_options"));
        this.hand = hand;
    }

    /** 「名称：值」形式的按钮文字。 */
    private static Component label(String nameKey, Component value) {
        return Component.translatable(nameKey).append(Component.literal("：")).append(value);
    }

    /**
     * 收到最新的放置记录表时调（{@code PlacementSyncPayload}）。
     * <p>
     * 菜单正开着就重建控件，让列表立刻反映最新状态；没开就什么都不做 ——
     * 重建会走 init()，没开着的屏幕重建没有意义。
     */
    public void onPlacementSync() {
        if (this.minecraft != null) {
            this.rebuildWidgets();
        }
    }

    @Override
    protected void init() {
        sectionBoxes.clear();
        sectionTitles.clear();
        sectionTitlePos.clear();
        recordLines.clear();
        if (page == 1) {
            initAreaPage();
            return;
        }
        if (page == 2) {
            initRecordsPage();
            return;
        }

        int avail = Math.max(300, width - 12);
        int sectionW = Math.min(SECTION_MAX_W, (avail - SIDE_PAD * 3 - SECTION_GAP) / 2);
        int btnW = Math.max(56, (sectionW - BOX_PAD * 2 - BTN_GAP) / 2);

        // 左列：挖掘与战利品(4 行) / 处决(1 行) / 加速(1 行) = 205
        // 右列：战斗(2 行) / 工具(2 行) / 放置(2 行)      = 205
        // 两列刻意配平：挖掘与战利品加了精准采集之后变成 4 行，
        // 若仍按旧分组，左列会比右列高出整整一行，面板就要超出 240 的高度。
        int leftH1 = boxHeight(4);
        int leftH2 = boxHeight(1);
        int leftH3 = boxHeight(1);
        int rightH1 = boxHeight(2);
        int rightH2 = boxHeight(2);
        int rightH3 = boxHeight(2);

        panelW = SIDE_PAD * 3 + sectionW * 2;
        int leftColH = HEADER_H + leftH1 + SECTION_GAP + HEADER_H + leftH2
                + SECTION_GAP + HEADER_H + leftH3;
        int rightColH = HEADER_H + rightH1 + SECTION_GAP + HEADER_H + rightH2
                + SECTION_GAP + HEADER_H + rightH3;
        panelH = TITLE_H + Math.max(leftColH, rightColH) + SIDE_PAD;

        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        int leftX = panelX + SIDE_PAD;
        int rightX = leftX + sectionW + SECTION_GAP;
        Player player = Minecraft.getInstance().player;

        // ---------- 左列 · 挖掘与战利品 ----------
        int contentTop = addSection(leftX, panelY + TITLE_H, sectionW, "screen.kunjinkao.section_mining_loot", 4);
        unbreakableBlockToggle = addButton(leftX + BOX_PAD, contentTop, btnW,
                this::toggleUnbreakableBlockBreaking);
        int lootingValue = player == null ? KunJinKaoSwordItem.DEFAULT_LOOTING_LEVEL
                : KunJinKaoSwordItem.getLootingLevelSetting(player.getItemInHand(hand));
        lootingLevelSlider = addRenderableWidget(new LootingLevelSlider(
                leftX + BOX_PAD + btnW + BTN_GAP, contentTop, btnW, BTN_H, lootingValue, this::setLootingLevel));
        int miningSpeed = player == null ? KunJinKaoSwordItem.DEFAULT_MINING_SPEED
                : KunJinKaoSwordItem.getMiningSpeed(player.getItemInHand(hand));
        miningSpeedSlider = addRenderableWidget(new MiningSpeedSlider(
                leftX + BOX_PAD, contentTop + ROW_H, btnW, BTN_H, miningSpeed, this::setMiningSpeed));
        int oreDropMultiplier = player == null ? KunJinKaoSwordItem.DEFAULT_ORE_DROP_MULTIPLIER
                : KunJinKaoSwordItem.getOreDropMultiplier(player.getItemInHand(hand));
        oreDropMultiplierSlider = addRenderableWidget(new OreDropMultiplierSlider(
                leftX + BOX_PAD + btnW + BTN_GAP, contentTop + ROW_H, btnW, BTN_H,
                oreDropMultiplier, this::setOreDropMultiplier));
        beheadingToggle = addButton(leftX + BOX_PAD, contentTop + ROW_H * 2, btnW, this::toggleBeheading);
        spawnEggDropToggle = addButton(leftX + BOX_PAD + btnW + BTN_GAP, contentTop + ROW_H * 2, btnW,
                this::toggleSpawnEggDrop);
        silkTouchToggle = addButton(leftX + BOX_PAD, contentTop + ROW_H * 3, btnW, this::toggleSilkTouch);
        // 第 4 行右半边本来就空着（当初加精准采集只占了左半），挖掘熔炼正好补上，面板高度不变。
        autoSmeltToggle = addButton(leftX + BOX_PAD + btnW + BTN_GAP, contentTop + ROW_H * 3, btnW,
                this::toggleAutoSmelt);

        // ---------- 左列 · 处决 ----------
        int execTitleY = contentTop + ROW_H * 3 + BTN_H + BOX_PAD + SECTION_GAP;
        contentTop = addSection(leftX, execTitleY, sectionW, "screen.kunjinkao.section_execution", 1);
        ultimateDeathToggle = addButton(leftX + BOX_PAD, contentTop, btnW, this::toggleUltimateDeath);
        quitStrikeToggle = addButton(leftX + BOX_PAD + btnW + BTN_GAP, contentTop, btnW,
                this::toggleQuitStrike);

        // ---------- 左列 · 加速 ----------
        int accelTitleY = contentTop + BTN_H + BOX_PAD + SECTION_GAP;
        contentTop = addSection(leftX, accelTitleY, sectionW, "screen.kunjinkao.section_acceleration", 1);
        accelModeButton = addButton(leftX + BOX_PAD, contentTop, btnW, this::cycleTimeAccelMode);
        accelMultiplierButton = addButton(leftX + BOX_PAD + btnW + BTN_GAP, contentTop, btnW,
                this::cycleTimeAccelMultiplier);

        // ---------- 右列 · 战斗 ----------
        contentTop = addSection(rightX, panelY + TITLE_H, sectionW, "screen.kunjinkao.section_combat", 2);
        blueScreenToggle = addButton(rightX + BOX_PAD, contentTop, btnW, this::toggleBlueScreenAttack);
        areaClearModeButton = addButton(rightX + BOX_PAD + btnW + BTN_GAP, contentTop, btnW,
                this::cycleAreaClearTargetMode);
        int attackDamage = player == null ? KunJinKaoSwordItem.DEFAULT_ATTACK_DAMAGE_LIMIT
                : KunJinKaoSwordItem.getAttackDamageLimit(player.getItemInHand(hand));
        attackDamageSlider = addRenderableWidget(new AttackDamageSlider(
                rightX + BOX_PAD, contentTop + ROW_H, sectionW - BOX_PAD * 2, BTN_H,
                attackDamage, this::setAttackDamageLimit));

        // ---------- 右列 · 工具 ----------
        int toolsTitleY = contentTop + ROW_H + BTN_H + BOX_PAD + SECTION_GAP;
        contentTop = addSection(rightX, toolsTitleY, sectionW, "screen.kunjinkao.section_tools", 2);
        brushToggle = addButton(rightX + BOX_PAD, contentTop, btnW, this::toggleBrush);
        toolModeButton = addButton(rightX + BOX_PAD + btnW + BTN_GAP, contentTop, btnW, this::cycleToolMode);
        lightningRodToggle = addButton(rightX + BOX_PAD, contentTop + ROW_H, btnW, this::toggleLightningRod);
        wrenchToggle = addButton(rightX + BOX_PAD + btnW + BTN_GAP, contentTop + ROW_H, btnW,
                this::toggleWrench);

        // ---------- 右列 · 放置 ----------
        int placeTitleY = contentTop + ROW_H + BTN_H + BOX_PAD + SECTION_GAP;
        contentTop = addSection(rightX, placeTitleY, sectionW, "screen.kunjinkao.section_placement", 2);
        constructionWandToggle = addButton(rightX + BOX_PAD, contentTop, btnW, this::toggleConstructionWand);
        angelCoreToggle = addButton(rightX + BOX_PAD + btnW + BTN_GAP, contentTop, btnW, this::toggleAngelCore);
        destructionCoreToggle = addButton(rightX + BOX_PAD, contentTop + ROW_H, btnW,
                this::toggleDestructionCore);
        placementUndoToggle = addButton(rightX + BOX_PAD + btnW + BTN_GAP, contentTop + ROW_H, btnW,
                this::togglePlacementUndo);

        addPageNav();
        refreshLabels();
    }

    /** 页面总数。第一页是设置，第二页第一项就是放置记录。 */
    private static final int PAGE_COUNT = 2;

    /**
     * 标题栏右上角的翻页按钮：上一页 / 下一页。
     * <p>
     * 到头的那一侧置灰（{@code active = false}），而不是藏起来 ——
     * 按钮位置固定，连点的时候不会因为按钮忽隐忽现而点错。
     */
    private void addPageNav() {
        int navW = 64;
        int navX = panelX + panelW - SIDE_PAD - navW * 2 - 4;
        int navY = panelY + 1;

        Button prev = addButton(navX, navY, navW, () -> {
            if (page > 0) {
                page--;
                recordPage = 0;
                rebuildWidgets();
            }
        });
        prev.setMessage(Component.translatable("screen.kunjinkao.page_prev"));
        prev.active = page > 0;

        Button next = addButton(navX + navW + 4, navY, navW, () -> {
            if (page < PAGE_COUNT - 1) {
                page++;
                recordPage = 0;
                rebuildWidgets();
            }
        });
        next.setMessage(Component.translatable("screen.kunjinkao.page_next"));
        next.active = page < PAGE_COUNT - 1;

        // 功能控制台入口：摆在翻页按钮左边。
        // 它是全局入口（面板管的是玩家自己的功能，跟手上有哪把剑无关），所以每一页都显示，
        // 而不是只在设置页出现 —— 在放置记录页想顺手开一下面板是常事。
        int entryW = 72;
        Button openQol = addButton(navX - 4 - entryW, navY, entryW, () -> {
            if (this.minecraft == null) {
                return;
            }
            // 与 G 键同一条闸：授权可能被 /kunjinkao-admin revoke 中途撤销，
            // 不能因为"现在在剑设置里"就默认放行。
            if (!dev.modmind.kunjinkao.client.AdminAuthClientState.isAuthorized()) {
                dev.modmind.kunjinkao.client.KunJinKaoClientEvents.deniedAdminPassword(this.minecraft);
                return;
            }
            this.minecraft.setScreen(new QolControlScreen());
        });
        openQol.setMessage(Component.translatable("screen.kunjinkao.qol.open_from_sword"));
    }

    /**
     * 放置记录页。
     * <p>
     * 高度刻意与设置页一致（都是 231），切页时面板不跳。
     * 第一行三个按钮管区域模式与两档尺寸，下面是一页记录，每条右边一个「收回」。
     */
    /**
     * 第二页「区域」。刻意做成和第一页一样的形状：分区标题 + 带边框的方框 + 框内双列按钮，
     * 只是内容少。
     * <p>
     * 「放置记录」在这里只是一个按钮 —— 点它才进第三层那个记录界面，
     * 记录界面不参与上一页/下一页的翻页。
     */
    private void initAreaPage() {
        int avail = Math.max(300, width - 12);
        int sectionW = Math.min(SECTION_MAX_W, (avail - SIDE_PAD * 3 - SECTION_GAP) / 2);
        panelW = SIDE_PAD * 3 + sectionW * 2;
        int boxW = panelW - SIDE_PAD * 2;
        int btnW = Math.max(56, (boxW - BOX_PAD * 2 - BTN_GAP) / 2);
        panelH = TITLE_H + HEADER_H + boxHeight(3) + SIDE_PAD;
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        int contentTop = addSection(panelX + SIDE_PAD, panelY + TITLE_H, boxW,
                "screen.kunjinkao.section_area", 3);
        int leftX = panelX + SIDE_PAD + BOX_PAD;
        int rightX = leftX + btnW + BTN_GAP;

        areaModeButton = addButton(leftX, contentTop, btnW, this::cycleAreaMode);
        openRecordsButton = addButton(rightX, contentTop, btnW, this::openRecords);
        pauseSizeButton = addButton(leftX, contentTop + ROW_H, btnW, this::cyclePauseSize);
        areaAccelSizeButton = addButton(rightX, contentTop + ROW_H, btnW, this::cycleAreaAccelSize);
        areaAccelMultiplierButton = addButton(leftX, contentTop + ROW_H * 2, btnW,
                this::cycleAreaAccelMultiplier);

        addPageNav();
        refreshLabels();
    }

    /** 进第三层：放置记录界面。 */
    private void openRecords() {
        page = 2;
        recordPage = 0;
        rebuildWidgets();
    }

    /** 从放置记录界面退回第二页。 */
    private void backToArea() {
        page = 1;
        rebuildWidgets();
    }

    private void initRecordsPage() {
        int avail = Math.max(300, width - 12);
        int sectionW = Math.min(SECTION_MAX_W, (avail - SIDE_PAD * 3 - SECTION_GAP) / 2);
        int btnW = Math.max(56, (sectionW - BOX_PAD * 2 - BTN_GAP) / 2);

        panelW = SIDE_PAD * 3 + sectionW * 2;
        int boxW = panelW - SIDE_PAD * 2;
        // 只有一块列表分区；▲▼ 排在分区框【外面】的下面。
        panelH = TITLE_H + HEADER_H + boxHeight(RECORDS_PER_PAGE) + 4 + BTN_H + SIDE_PAD;
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        int contentTop = addSection(panelX + SIDE_PAD, panelY + TITLE_H, boxW,
                "screen.kunjinkao.page_records", RECORDS_PER_PAGE);
        recordTextX = panelX + SIDE_PAD + BOX_PAD;
        recordTextTop = contentTop;
        buildRecordRows();

        // ▲▼ 放在分区框下沿之外：画在框里会压住最后一行记录，也会看起来"偏位"。
        // contentTop = 框顶 + BOX_PAD，所以框底 = contentTop - BOX_PAD + boxHeight(...)。
        int boxBottom = contentTop - BOX_PAD + boxHeight(RECORDS_PER_PAGE);
        int navY = boxBottom + 4;
        recordsPrevButton = addButton(panelX + SIDE_PAD + BOX_PAD, navY, btnW, () -> {
            if (recordPage > 0) {
                recordPage--;
                rebuildWidgets();
            }
        });
        recordsNextButton = addButton(panelX + panelW - SIDE_PAD - BOX_PAD - btnW, navY, btnW, () -> {
            recordPage++;
            rebuildWidgets();
        });

        // 标题栏右侧改成「返回」：记录界面不参与上一页/下一页翻页。
        recordsBackButton = addButton(panelX + panelW - SIDE_PAD - 64, panelY + 1, 64, this::backToArea);
        recordsBackButton.setMessage(Component.translatable("screen.kunjinkao.records_back"));
        refreshLabels();
    }

    /** 按当前页号把记录铺成一行行文字 + 每条一个「收回」按钮。 */
    private void buildRecordRows() {
        List<PlacementSyncPayload.Entry> all = PlacementSyncPayload.clientEntries();
        int pages = Math.max(1, (all.size() + RECORDS_PER_PAGE - 1) / RECORDS_PER_PAGE);
        // 收回一条之后当前页可能越界（比如最后一页只剩一条），往回收一页而不是显示空白。
        if (recordPage >= pages) {
            recordPage = pages - 1;
        }
        if (recordPage < 0) {
            recordPage = 0;
        }
        int from = recordPage * RECORDS_PER_PAGE;
        int to = Math.min(all.size(), from + RECORDS_PER_PAGE);
        int revokeW = 52;
        for (int i = from; i < to; i++) {
            PlacementSyncPayload.Entry entry = all.get(i);
            int y = recordTextTop + (i - from) * RECORD_ROW_STEP;
            recordLines.add(recordLabel(entry));
            PlacementSyncPayload.Entry captured = entry;
            // 这个按钮是在循环里现建的，不在 refreshLabels 的覆盖范围内 ——
            // 必须当场把文字设上，否则就是个空白按钮（addButton 默认给的是 empty）。
            Button revoke = addButton(panelX + panelW - SIDE_PAD - BOX_PAD - revokeW, y, revokeW,
                    () -> revokeRecord(captured));
            revoke.setMessage(Component.translatable("screen.kunjinkao.record_revoke"));
        }
        if (all.isEmpty()) {
            recordLines.add(Component.translatable("screen.kunjinkao.records_empty"));
            recordLines.add(Component.translatable("screen.kunjinkao.records_hint"));
        }
    }

    /**
     * 一条记录的显示文字：类型、维度、三轴坐标、尺寸。
     * <p>
     * 坐标按用户要求把 x / y / z 三轴都写全；维度用注册名（overworld / the_nether / the_end 或模组维度）。
     */
    private static Component recordLabel(PlacementSyncPayload.Entry entry) {
        String kind = Component.translatable(entry.kind() == SwordPlacementRegistry.KIND_PAUSE
                ? "screen.kunjinkao.record_kind_pause"
                : "screen.kunjinkao.record_kind_accel").getString();
        int side = SwordAreaFields.sideLength(entry.radius());
        return Component.literal(String.format("%s  [%s]  x=%d  y=%d  z=%d  %dx%dx%d",
                kind, entry.dimension(), entry.pos().getX(), entry.pos().getY(), entry.pos().getZ(),
                side, side, side));
    }

    /** 收回一条记录：本地先不改（服务端才是权威），等服务端把新表推回来。 */
    private void revokeRecord(PlacementSyncPayload.Entry entry) {
        NetworkHandler.sendToServer(new RemovePlacementPayload(entry.kind(), entry.dimension(), entry.pos()));
    }

    private void cycleAreaMode() {
        withSword(stack -> {
            int mode = KunJinKaoSwordItem.nextAreaMode(KunJinKaoSwordItem.getAreaMode(stack));
            KunJinKaoSwordItem.setAreaMode(stack, mode);
            NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.AREA_MODE, mode));
        });
    }

    private void cyclePauseSize() {
        withSword(stack -> {
            int radius = SwordAreaFields.nextRadius(KunJinKaoSwordItem.getPauseSize(stack));
            KunJinKaoSwordItem.setPauseSize(stack, radius);
            NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.PAUSE_SIZE, radius));
        });
    }

    private void cycleAreaAccelMultiplier() {
        withSword(stack -> {
            int multiplier = SwordTimeAcceleration.nextMultiplier(
                    KunJinKaoSwordItem.getAreaAccelMultiplier(stack));
            KunJinKaoSwordItem.setAreaAccelMultiplier(stack, multiplier);
            NetworkHandler.sendToServer(new SwordSettingPayload(hand,
                    SwordSettingPayload.AREA_ACCEL_MULTIPLIER, multiplier));
        });
    }

    private void cycleAreaAccelSize() {
        withSword(stack -> {
            int radius = SwordAreaFields.nextRadius(KunJinKaoSwordItem.getAreaAccelSize(stack));
            KunJinKaoSwordItem.setAreaAccelSize(stack, radius);
            NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.AREA_ACCEL_SIZE, radius));
        });
    }

    /**
     * 记录页的按钮文字。
     * <p>
     * 单独一支，是因为设置页的 {@link #refreshLabels()} 一进来就靠
     * {@code blueScreenToggle == null} 早退，那个守卫在记录页永远成立 ——
     * 不单独处理的话记录页三个按钮会一直是空白。
     */
    /** 第二页「区域」的按钮文字。 */
    private void refreshAreaPageLabels() {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (KunJinKaoSwordItem.isSwordLike(stack)) {
            if (areaModeButton != null) {
                areaModeButton.setMessage(label("screen.kunjinkao.area_mode",
                        Component.translatable(switch (KunJinKaoSwordItem.getAreaMode(stack)) {
                            case 1 -> "screen.kunjinkao.area_mode_pause";
                            case 2 -> "screen.kunjinkao.area_mode_accel";
                            default -> "screen.kunjinkao.switch_off";
                        })));
            }
            if (pauseSizeButton != null) {
                pauseSizeButton.setMessage(label("screen.kunjinkao.pause_size",
                        Component.literal(sideText(SwordAreaFields.sideLength(KunJinKaoSwordItem.getPauseSize(stack))))));
            }
            if (areaAccelSizeButton != null) {
                areaAccelSizeButton.setMessage(label("screen.kunjinkao.area_accel_size",
                        Component.literal(sideText(SwordAreaFields.sideLength(
                                KunJinKaoSwordItem.getAreaAccelSize(stack))))));
            }
            if (areaAccelMultiplierButton != null) {
                areaAccelMultiplierButton.setMessage(label("screen.kunjinkao.area_accel_multiplier",
                        Component.literal(KunJinKaoSwordItem.getAreaAccelMultiplier(stack) + "x")));
            }
        }
        if (openRecordsButton != null) {
            openRecordsButton.setMessage(Component.translatable("screen.kunjinkao.open_records"));
        }
    }

    private void refreshRecordsPageLabels() {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (KunJinKaoSwordItem.isSwordLike(stack)) {
            if (areaModeButton != null) {
                areaModeButton.setMessage(label("screen.kunjinkao.area_mode",
                        Component.translatable(switch (KunJinKaoSwordItem.getAreaMode(stack)) {
                            case 1 -> "screen.kunjinkao.area_mode_pause";
                            case 2 -> "screen.kunjinkao.area_mode_accel";
                            default -> "screen.kunjinkao.switch_off";
                        })));
            }
            if (pauseSizeButton != null) {
                pauseSizeButton.setMessage(label("screen.kunjinkao.pause_size",
                        Component.literal(sideText(SwordAreaFields.sideLength(KunJinKaoSwordItem.getPauseSize(stack))))));
            }
            if (areaAccelSizeButton != null) {
                areaAccelSizeButton.setMessage(label("screen.kunjinkao.area_accel_size",
                        Component.literal(sideText(SwordAreaFields.sideLength(
                                KunJinKaoSwordItem.getAreaAccelSize(stack))))));
            }
        }
        if (recordsPrevButton != null) {
            recordsPrevButton.setMessage(Component.translatable("screen.kunjinkao.records_prev"));
        }
        if (recordsNextButton != null) {
            recordsNextButton.setMessage(Component.translatable("screen.kunjinkao.records_next"));
        }
    }

    private static String sideText(int side) {
        return side + "x" + side + "x" + side;
    }

    /** 拿着剑才改设置；不是剑就直接关屏，与其它开关的行为一致。 */
    private void withSword(java.util.function.Consumer<ItemStack> action) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            onClose();
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        action.accept(stack);
        refreshLabels();
    }

    private static int boxHeight(int rows) {
        return BOX_PAD * 2 + rows * ROW_H - BTN_GAP;
    }

    /**
     * 记录一个「分区标题 + 方框」，返回框内容区第一行按钮的 y。
     * 实际绘制在 {@link #renderBackground} 里做，保证顺序是「背景 → 面板 → 控件」。
     */
    private int addSection(int x, int titleY, int sectionW, String titleKey, int rows) {
        int boxTop = titleY + HEADER_H;
        sectionTitles.add(Component.translatable(titleKey));
        sectionTitlePos.add(new int[]{x, titleY});
        sectionBoxes.add(new int[]{x, boxTop, sectionW, boxHeight(rows)});
        return boxTop + BOX_PAD;
    }

    // ===== 双击滑条精确输入 =====

    /**
     * 滑条"可精确设值"的最小契约。
     *
     * <p>四个滑条各自维护自己的整数值（{@code currentSpeed} 等）并各自夹紧范围，
     * 所以这里不抽公共基类，只要求它们暴露三件事：当前值、范围、以及"按整数设值"。
     * 这样双击输入框不需要知道每个滑条内部是怎么换算的。</p>
     */
    private interface IntSlider {
        int sliderValue();

        int sliderMin();

        int sliderMax();

        /** 精确设值：实现方负责夹紧到合法范围并触发原有回调。 */
        void sliderSet(int value);
    }

    /** 双击后弹出的精确输入框；null 表示当前没在输入。 */
    private EditBox sliderInput;
    private IntSlider sliderInputTarget;
    /** 上一次点中的滑条与时间，用于判定双击。 */
    private AbstractSliderButton lastClickedSlider;
    private long lastSliderClickMillis;

    /** 双击间隔上限（毫秒）。 */
    private static final long DOUBLE_CLICK_MS = 400L;

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 输入框开着时点别处就收起，避免它一直挂在界面上
        if (sliderInput != null && !sliderInput.isMouseOver(mouseX, mouseY)) {
            closeSliderInput();
        }
        if (button == 0 && sliderInput == null) {
            AbstractSliderButton[] sliders = {
                    miningSpeedSlider, oreDropMultiplierSlider, lootingLevelSlider, attackDamageSlider};
            for (AbstractSliderButton slider : sliders) {
                if (slider == null || !slider.isMouseOver(mouseX, mouseY)) {
                    continue;
                }
                long now = net.minecraft.Util.getMillis();
                if (slider == lastClickedSlider && now - lastSliderClickMillis <= DOUBLE_CLICK_MS) {
                    lastClickedSlider = null;
                    openSliderInput(slider);
                    return true;
                }
                lastClickedSlider = slider;
                lastSliderClickMillis = now;
                break;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 在滑条原位置弹一个输入框，预填当前值。 */
    private void openSliderInput(AbstractSliderButton slider) {
        if (!(slider instanceof IntSlider target)) {
            return;
        }
        closeSliderInput();
        sliderInputTarget = target;
        sliderInput = new EditBox(font, slider.getX(), slider.getY(), slider.getWidth(), slider.getHeight(),
                Component.translatable("screen.kunjinkao.slider_input"));
        sliderInput.setMaxLength(7);
        sliderInput.setValue(Integer.toString(target.sliderValue()));
        sliderInput.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("screen.kunjinkao.slider_input_hint",
                        Integer.toString(target.sliderMin()), Integer.toString(target.sliderMax()))));
        addRenderableWidget(sliderInput);
        setFocused(sliderInput);
        sliderInput.setFocused(true);
        sliderInput.setCursorPosition(sliderInput.getValue().length());
        sliderInput.setHighlightPos(sliderInput.getValue().length());
    }

    private void closeSliderInput() {
        if (sliderInput != null) {
            removeWidget(sliderInput);
            sliderInput = null;
        }
        sliderInputTarget = null;
    }

    /** 回车：解析并应用；解析失败就静默收起（不弹错，避免打断操作）。 */
    private void applySliderInput() {
        if (sliderInput == null || sliderInputTarget == null) {
            closeSliderInput();
            return;
        }
        String text = sliderInput.getValue().trim();
        if (!text.isEmpty()) {
            try {
                sliderInputTarget.sliderSet(Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                // 非数字：当作放弃输入
            }
        }
        closeSliderInput();
    }
    private Button addButton(int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.empty(), b -> action.run())
                .bounds(x, y, w, BTN_H)
                .build());
    }

    /**
     * 1.21.1 的 Screen.render 第一步就会调用 renderBackground，随后才画控件。
     * 面板、分区标题与分区方框必须画在这里（即 super.renderBackground 之后），
     * 才能得到「背景只画一遍 → 面板与分区 → 控件」的正确顺序。
     */
    @Override
    public void renderBackground(GuiGraphics graphics) {
        drawChrome(graphics);
    }

    /** 面板/标题/分区：1.20.1 的 Screen#render 不会调用 renderBackground，必须由 render 自己调。 */
    private void drawChrome(GuiGraphics graphics) {
        super.renderBackground(graphics);

        KunJinKaoUi.panel(graphics, panelX, panelY, panelW, panelH);
        
        KunJinKaoUi.titleText(graphics, font, title, panelX + 8, panelY + 5,
                KunJinKaoAnim.easeOutCubic(KunJinKaoAnim.progress(KunJinKaoUi.openTimeOf(this), 560)));

        for (int i = 0; i < sectionBoxes.size(); i++) {
            int[] box = sectionBoxes.get(i);
            int[] pos = sectionTitlePos.get(i);
            graphics.drawString(font, sectionTitles.get(i), pos[0], pos[1], COLOR_SECTION, false);
            drawBorder(graphics, box[0], box[1], box[2], box[3], COLOR_BORDER);
        }

        // 记录页的文字画在分区框里：每条一行，右边给「收回」按钮留出位置。
        for (int i = 0; i < recordLines.size(); i++) {
            graphics.drawString(font, recordLines.get(i), recordTextX,
                    recordTextTop + i * RECORD_ROW_STEP + 5, COLOR_SECTION, false);
        }
    }

    private static void drawBorder(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 背景与控件都由 super.render 负责；面板与分区见上面的 renderBackground。
        // 每帧检查一次节流窗口：窗口一过就补发滑条最后的值（相当于拖动结束后的收尾发送）。
        flushPendingSettings(false);
        drawChrome(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * 关屏时强制补发一次被节流跳过的滑条值，避免玩家拖完立刻 ESC 导致最后一次设置丢失。
     */
    @Override
    public void onClose() {
        flushPendingSettings(true);
        super.onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 精确输入框开着时，回车/Esc 由它优先处理（否则会被下面的关界面逻辑或控件吃掉）
        if (sliderInput != null) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                applySliderInput();
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                closeSliderInput();
                return true;
            }
        }
        if (KunJinKaoKeyBindings.OPEN_SWORD_OPTIONS.getKey().equals(
                com.mojang.blaze3d.platform.InputConstants.getKey(keyCode, scanCode))) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ===== 以下是各选项的操作，逻辑与改动前完全一致 =====

    private void toggleBlueScreenAttack() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            onClose();
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        boolean enabled = !KunJinKaoSwordItem.isBlueScreenAttackEnabled(stack);
        // Client-side update is only for immediate button feedback. The server
        // independently validates the held item and remains authoritative.
        KunJinKaoSwordItem.setBlueScreenAttackEnabled(stack, enabled);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.BLUE_SCREEN_ATTACK,
                enabled ? 1 : 0));
        refreshLabels();
    }

    /** 把所有按钮文字刷新成「名称：值」。 */
    private void refreshLabels() {
        if (page == 1) {
            refreshAreaPageLabels();
            return;
        }
        if (page == 2) {
            refreshRecordsPageLabels();
            return;
        }
        if (blueScreenToggle == null) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        boolean enabled = player != null
                && KunJinKaoSwordItem.isBlueScreenAttackEnabled(player.getItemInHand(hand));
        blueScreenToggle.setMessage(label("screen.kunjinkao.blue_screen_attack",
                Component.translatable(enabled ? "screen.kunjinkao.switch_on" : "screen.kunjinkao.switch_off")));
        boolean canBreak = player != null
                && KunJinKaoSwordItem.canBreakUnbreakableBlocks(player.getItemInHand(hand));
        if (unbreakableBlockToggle != null) {
            unbreakableBlockToggle.setMessage(label("screen.kunjinkao.break_unbreakable_blocks",
                    Component.translatable(canBreak
                            ? "screen.kunjinkao.switch_on" : "screen.kunjinkao.switch_off")));
        }
        if (ultimateDeathToggle != null) {
            boolean ultimateDeath = player != null
                    && KunJinKaoSwordItem.isUltimateDeathEnabled(player.getItemInHand(hand));
            ultimateDeathToggle.setMessage(label("screen.kunjinkao.ultimate_death",
                    Component.translatable(ultimateDeath
                            ? "screen.kunjinkao.switch_on" : "screen.kunjinkao.switch_off")));
        }
        if (quitStrikeToggle != null) {
            boolean quitStrike = player != null
                    && KunJinKaoSwordItem.isQuitStrikeEnabled(player.getItemInHand(hand));
            quitStrikeToggle.setMessage(label("screen.kunjinkao.quit_strike",
                    Component.translatable(quitStrike
                            ? "screen.kunjinkao.switch_on" : "screen.kunjinkao.switch_off")));
        }
        if (constructionWandToggle != null) {
            boolean wand = player != null
                    && KunJinKaoSwordItem.isConstructionWandEnabled(player.getItemInHand(hand));
            constructionWandToggle.setMessage(label("screen.kunjinkao.construction_wand",
                    Component.translatable(wand ? "screen.kunjinkao.switch_on" : "screen.kunjinkao.switch_off")));
        }
        if (angelCoreToggle != null) {
            boolean angel = player != null
                    && KunJinKaoSwordItem.isAngelCoreEnabled(player.getItemInHand(hand));
            angelCoreToggle.setMessage(label("screen.kunjinkao.angel_core",
                    Component.translatable(angel ? "screen.kunjinkao.switch_on" : "screen.kunjinkao.switch_off")));
        }
        if (destructionCoreToggle != null) {
            boolean destruction = player != null
                    && KunJinKaoSwordItem.isDestructionCoreEnabled(player.getItemInHand(hand));
            destructionCoreToggle.setMessage(label("screen.kunjinkao.destruction_core",
                    Component.translatable(destruction
                            ? "screen.kunjinkao.switch_on" : "screen.kunjinkao.switch_off")));
        }
        if (placementUndoToggle != null) {
            boolean undo = player != null
                    && KunJinKaoSwordItem.isPlacementUndoEnabled(player.getItemInHand(hand));
            placementUndoToggle.setMessage(label("screen.kunjinkao.placement_undo",
                    Component.translatable(undo ? "screen.kunjinkao.switch_on" : "screen.kunjinkao.switch_off")));
        }
        if (brushToggle != null) {
            boolean brush = player != null && KunJinKaoSwordItem.isBrushEnabled(player.getItemInHand(hand));
            brushToggle.setMessage(label("screen.kunjinkao.brush",
                    Component.translatable(brush
                            ? "screen.kunjinkao.switch_on" : "screen.kunjinkao.switch_off")));
        }
        if (toolModeButton != null) {
            int mode = player == null ? SwordToolHandler.TOOL_MODE_OFF
                    : KunJinKaoSwordItem.getToolMode(player.getItemInHand(hand));
            String modeKey = switch (mode) {
                case SwordToolHandler.TOOL_MODE_HOE -> "screen.kunjinkao.tool_mode_hoe";
                case SwordToolHandler.TOOL_MODE_SHOVEL -> "screen.kunjinkao.tool_mode_shovel";
                default -> "screen.kunjinkao.tool_mode_off";
            };
            toolModeButton.setMessage(label("screen.kunjinkao.tool_mode", Component.translatable(modeKey)));
        }
        if (lightningRodToggle != null) {
            // 显示成四态循环里的当前那一档。
            Component state = player == null || !KunJinKaoSwordItem.isLightningRodEnabled(player.getItemInHand(hand))
                    ? Component.translatable("screen.kunjinkao.switch_off")
                    : Component.translatable(switch (KunJinKaoSwordItem.getLightningMode(player.getItemInHand(hand))) {
                        case KunJinKaoSwordItem.LIGHTNING_PLAIN -> "screen.kunjinkao.lightning_plain";
                        case KunJinKaoSwordItem.LIGHTNING_VISUAL_ONLY -> "screen.kunjinkao.lightning_visual";
                        default -> "screen.kunjinkao.lightning_natural";
                    });
            lightningRodToggle.setMessage(label("screen.kunjinkao.lightning_rod", state));
        }
        if (wrenchToggle != null) {
            boolean wrench = player != null
                    && KunJinKaoSwordItem.isWrenchEnabled(player.getItemInHand(hand));
            wrenchToggle.setMessage(label("screen.kunjinkao.wrench",
                    Component.translatable(wrench
                            ? "screen.kunjinkao.switch_on" : "screen.kunjinkao.switch_off")));
        }
        if (silkTouchToggle != null) {
            boolean silk = player != null
                    && KunJinKaoSwordItem.isSilkTouchEnabled(player.getItemInHand(hand));
            silkTouchToggle.setMessage(label("screen.kunjinkao.silk_touch",
                    Component.translatable(silk
                            ? "screen.kunjinkao.switch_on" : "screen.kunjinkao.switch_off")));
        }
        if (autoSmeltToggle != null) {
            boolean autoSmelt = player != null
                    && KunJinKaoSwordItem.isAutoSmeltEnabled(player.getItemInHand(hand));
            autoSmeltToggle.setMessage(label("screen.kunjinkao.auto_smelt",
                    Component.translatable(autoSmelt
                            ? "screen.kunjinkao.switch_on" : "screen.kunjinkao.switch_off")));
        }
        if (accelModeButton != null) {
            int accelMode = player == null ? SwordTimeAcceleration.MODE_OFF
                    : KunJinKaoSwordItem.getTimeAccelMode(player.getItemInHand(hand));
            String modeKey = switch (accelMode) {
                case SwordTimeAcceleration.MODE_TIMED -> "screen.kunjinkao.timed_acceleration";
                case SwordTimeAcceleration.MODE_INFINITE -> "screen.kunjinkao.infinite_acceleration";
                default -> "screen.kunjinkao.accel_off";
            };
            accelModeButton.setMessage(label("screen.kunjinkao.accel_mode", Component.translatable(modeKey)));
        }
        if (accelMultiplierButton != null) {
            int multiplier = player == null ? AcceleratorBlockEntity.MULTIPLIERS[0]
                    : KunJinKaoSwordItem.getTimeAccelMultiplier(player.getItemInHand(hand));
            accelMultiplierButton.setMessage(label("screen.kunjinkao.accel_multiplier",
                    Component.literal("x" + multiplier)));
        }
        if (beheadingToggle != null) {
            boolean beheading = player != null
                    && KunJinKaoSwordItem.isBeheadingEnabled(player.getItemInHand(hand));
            beheadingToggle.setMessage(label("screen.kunjinkao.beheading",
                    Component.translatable(beheading
                            ? "screen.kunjinkao.switch_on" : "screen.kunjinkao.switch_off")));
        }
        if (spawnEggDropToggle != null) {
            boolean eggDrop = player != null
                    && KunJinKaoSwordItem.isSpawnEggDropEnabled(player.getItemInHand(hand));
            spawnEggDropToggle.setMessage(label("screen.kunjinkao.spawn_egg_drop",
                    Component.translatable(eggDrop
                            ? "screen.kunjinkao.switch_on" : "screen.kunjinkao.switch_off")));
        }
        if (areaClearModeButton != null) {
            KunJinKaoSwordItem.AreaClearTargetMode mode = player == null
                    ? KunJinKaoSwordItem.AreaClearTargetMode.HOSTILE
                    : KunJinKaoSwordItem.getAreaClearTargetMode(player.getItemInHand(hand));
            areaClearModeButton.setMessage(label("screen.kunjinkao.area_clear_targets",
                    Component.translatable(mode.translationKey())));
        }
        // 抢夺滑条自己维护按钮文字（updateMessage 里带等级），这里不用管。
    }

    private void toggleUnbreakableBlockBreaking() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            onClose();
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        boolean enabled = !KunJinKaoSwordItem.canBreakUnbreakableBlocks(stack);
        KunJinKaoSwordItem.setBreakUnbreakableBlocks(stack, enabled);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.UNBREAKABLE_BLOCK_BREAKING,
                enabled ? 1 : 0));
        refreshLabels();
    }

    private void toggleUltimateDeath() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            onClose();
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        boolean enabled = !KunJinKaoSwordItem.isUltimateDeathEnabled(stack);
        KunJinKaoSwordItem.setUltimateDeathEnabled(stack, enabled);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.ULTIMATE_DEATH,
                enabled ? 1 : 0));
        refreshLabels();
    }

    private void toggleQuitStrike() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            onClose();
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        boolean enabled = !KunJinKaoSwordItem.isQuitStrikeEnabled(stack);
        KunJinKaoSwordItem.setQuitStrikeEnabled(stack, enabled);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.QUIT_STRIKE,
                enabled ? 1 : 0));
        refreshLabels();
    }

    // ===== 放置类核心（对应 Construction Wand 的三种核心）=====

    /** 建筑手杖：右键方块面时沿该面延伸放置一排方块。 */
    private void toggleConstructionWand() {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        boolean enabled = !KunJinKaoSwordItem.isConstructionWandEnabled(stack);
        KunJinKaoSwordItem.setConstructionWandEnabled(stack, enabled);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.CONSTRUCTION_WAND,
                enabled ? 1 : 0));
        refreshLabels();
    }

    /** 天使核心：把方块放到所视方块的背面，或对空在半空放置。 */
    private void toggleAngelCore() {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        boolean enabled = !KunJinKaoSwordItem.isAngelCoreEnabled(stack);
        KunJinKaoSwordItem.setAngelCoreEnabled(stack, enabled);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.ANGEL_CORE,
                enabled ? 1 : 0));
        refreshLabels();
    }

    /** 破坏核心：挖掉一格时连带清除面向那一侧的整排方块。 */
    private void toggleDestructionCore() {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        boolean enabled = !KunJinKaoSwordItem.isDestructionCoreEnabled(stack);
        KunJinKaoSwordItem.setDestructionCoreEnabled(stack, enabled);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.DESTRUCTION_CORE,
                enabled ? 1 : 0));
        refreshLabels();
    }

    /**
     * 撤销开关：开启后按 K 键可撤销最近 10 步内的放置/破坏。
     * 这里只改物品标记与同步给服务端，撤销本身由服务端的历史栈执行。
     */
    private void togglePlacementUndo() {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        boolean enabled = !KunJinKaoSwordItem.isPlacementUndoEnabled(stack);
        KunJinKaoSwordItem.setPlacementUndoEnabled(stack, enabled);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.PLACEMENT_UNDO,
                enabled ? 1 : 0));
        refreshLabels();
    }

    // ===== 工具类行为（刷子 / 锄头铲子 / 避雷针）=====

    /**
     * 精准采集。
     * <p>
     * 直接给剑挂上/摘掉原版精准采集附魔，而不是自己去替换方块掉落 ——
     * 这样所有原版与模组的战利品表都按它们原本的逻辑处理。
     */
    private void toggleSilkTouch() {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        boolean enabled = !KunJinKaoSwordItem.isSilkTouchEnabled(stack);
        // 本地先改，按钮反馈才跟得上；服务端那份由下面的包同步。
        KunJinKaoSwordItem.setSilkTouchEnabled(stack, enabled, player.level());
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.SILK_TOUCH,
                enabled ? 1 : 0));
        refreshLabels();
    }

    /**
     * 挖掘熔炼。
     * <p>
     * 掉落替换完全在服务端的 {@code KunJinKaoAutoSmeltHandler} 里做（要查配方表），
     * 这里和精准采集一样只翻开关并同步。
     * <p>
     * 与精准采集互斥：精准采集开着时熔炼不生效。这里不拦着不让点 ——
     * 两个开关各自独立、状态看得见，比"点了没反应"清楚。
     */
    private void toggleAutoSmelt() {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        boolean enabled = !KunJinKaoSwordItem.isAutoSmeltEnabled(stack);
        KunJinKaoSwordItem.setAutoSmeltEnabled(stack, enabled);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.AUTO_SMELT,
                enabled ? 1 : 0));
        refreshLabels();
    }

    /** 刷子：长按右键可疑的沙/砾石即可刷取。 */
    private void toggleBrush() {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        boolean enabled = !KunJinKaoSwordItem.isBrushEnabled(stack);
        KunJinKaoSwordItem.setBrushEnabled(stack, enabled);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.BRUSH,
                enabled ? 1 : 0));
        refreshLabels();
    }

    /** 锄头 / 铲子循环：关 → 锄头（→耕地）→ 铲子（→草径）→ 关。 */
    private void cycleToolMode() {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        int next = (KunJinKaoSwordItem.getToolMode(stack) + 1) % SwordToolHandler.TOOL_MODE_COUNT;
        KunJinKaoSwordItem.setToolMode(stack, next);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.TOOL_MODE, next));
        refreshLabels();
    }

    /** 避雷针：右键避雷针召唤闪电。 */
    private void toggleLightningRod() {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        // 四态循环：关 -> 自然 -> 普通 -> 仅视觉 -> 关。
        // 放在同一个按钮上，是因为工具分区四个槽位已经排满，
        // 再加一格会把面板顶出 240 的高度。
        // 0 = 关闭，1..3 = 开启且形态为 state - 1。总数是 1 + 形态数 = 4。
        // 上一版写成 "mode >= COUNT ? 0 : mode + 2"，最后一档时 2 >= 3 为假，
        // 算出 state = 4，形态被夹回 2 —— 于是永远停在"仅视觉"出不去。
        int state = KunJinKaoSwordItem.isLightningRodEnabled(stack)
                ? 1 + KunJinKaoSwordItem.getLightningMode(stack)
                : 0;
        state = (state + 1) % (1 + KunJinKaoSwordItem.LIGHTNING_MODE_COUNT);
        boolean enabled = state > 0;
        KunJinKaoSwordItem.setLightningRodEnabled(stack, enabled);
        if (enabled) {
            KunJinKaoSwordItem.setLightningMode(stack, state - 1);
        }
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.LIGHTNING_ROD, state));
        refreshLabels();
    }

    /**
     * 扳手标记。
     * <p>
     * 这个开关只改剑上的 NBT；真正让模组把剑当扳手的是数据包里的物品标签
     * {@code c:tools/wrench}（以及 {@code ae2:quartz_wrench}）—— 那是标签，不是 NBT，
     * 无法按物品逐个开关。所以这里管的是"这把剑自己认不认自己是扳手"，
     * 供脚本或后续逻辑读取。
     */
    private void toggleWrench() {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        boolean enabled = !KunJinKaoSwordItem.isWrenchEnabled(stack);
        KunJinKaoSwordItem.setWrenchEnabled(stack, enabled);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.WRENCH,
                enabled ? 1 : 0));
        refreshLabels();
    }

    // ===== 时间加速（shift+右键 立加速场）=====

    /**
     * 写入加速模式。
     * <p>
     * 模式是<b>单值</b>而不是两个独立开关 —— 限时与无限本来就互斥，
     * 用一个三值字段（关/限时/无限）表达，就天然不会出现两个都亮着的情况。
     */
    private void applyTimeAccelMode(int mode) {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        KunJinKaoSwordItem.setTimeAccelMode(stack, mode);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.TIME_ACCEL_MODE, mode));
        refreshLabels();
    }

    /** 加速模式循环：关闭 -> 限时加速（30 秒）-> 无限加速 -> 关闭。 */
    private void cycleTimeAccelMode() {
        Player player = Minecraft.getInstance().player;
        int current = player == null ? SwordTimeAcceleration.MODE_OFF
                : KunJinKaoSwordItem.getTimeAccelMode(player.getItemInHand(hand));
        applyTimeAccelMode((current + 1) % SwordTimeAcceleration.MODE_COUNT);
    }

    /**
     * 加速倍率循环。
     * <p>
     * 倍率是固定档位表（4/8/.../1024），到最大档之后回到最小档 —— 菜单里要的是循环，
     * 而 shift+滚轮 用的是 stepMultiplier（到顶即停），两者用途不同。
     */
    private void cycleTimeAccelMultiplier() {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        int next = SwordTimeAcceleration.nextMultiplier(KunJinKaoSwordItem.getTimeAccelMultiplier(stack));
        KunJinKaoSwordItem.setTimeAccelMultiplier(stack, next);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand,
                SwordSettingPayload.TIME_ACCEL_MULTIPLIER, next));
        refreshLabels();
    }

    /** 斩首：击杀生物时额外掉落对应头颅。 */
    private void toggleBeheading() {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        boolean enabled = !KunJinKaoSwordItem.isBeheadingEnabled(stack);
        KunJinKaoSwordItem.setBeheadingEnabled(stack, enabled);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.BEHEADING,
                enabled ? 1 : 0));
        refreshLabels();
    }

    /** 刷怪蛋掉落：击杀生物时额外掉落它的刷怪蛋。 */
    private void toggleSpawnEggDrop() {
        Player player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        boolean enabled = !KunJinKaoSwordItem.isSpawnEggDropEnabled(stack);
        KunJinKaoSwordItem.setSpawnEggDropEnabled(stack, enabled);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.SPAWN_EGG_DROP,
                enabled ? 1 : 0));
        refreshLabels();
    }

    private void setMiningSpeed(int speed) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            onClose();
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        KunJinKaoSwordItem.setMiningSpeed(stack, speed);
        // 该 settingId 走节流：拖动过程中同档碎变化合并，松手/窗口过后补发最后值。
        sendSettingValue(SwordSettingPayload.MINING_SPEED, speed);
    }

    private void setOreDropMultiplier(int multiplier) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            onClose();
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        KunJinKaoSwordItem.setOreDropMultiplier(stack, multiplier);
        sendSettingValue(SwordSettingPayload.ORE_DROP_MULTIPLIER, multiplier);
    }

    private void setAttackDamageLimit(int damageLimit) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            onClose();
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        KunJinKaoSwordItem.setAttackDamageLimit(stack, damageLimit);
        sendSettingValue(SwordSettingPayload.ATTACK_DAMAGE_LIMIT, damageLimit);
    }

    private void cycleAreaClearTargetMode() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            onClose();
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        KunJinKaoSwordItem.AreaClearTargetMode[] modes = KunJinKaoSwordItem.AreaClearTargetMode.values();
        KunJinKaoSwordItem.AreaClearTargetMode current = KunJinKaoSwordItem.getAreaClearTargetMode(stack);
        KunJinKaoSwordItem.AreaClearTargetMode next = modes[(current.ordinal() + 1) % modes.length];
        KunJinKaoSwordItem.setAreaClearTargetMode(stack, next);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.AREA_CLEAR_TARGET_MODE,
                next.id()));
        refreshLabels();
    }

    /** 抢夺等级：设置值 1..1000（1 = 无抢夺），和挖矿倍数一样走节流滑条。 */
    private void setLootingLevel(int settingValue) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            onClose();
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            onClose();
            return;
        }
        KunJinKaoSwordItem.setLootingLevel(stack, settingValue);
        sendSettingValue(SwordSettingPayload.LOOTING_MODE, settingValue);
    }

    /** 滑条专用：带节流地发送剑设置（离散开关不走这里）。 */
    private void sendSettingValue(int settingId, int value) {
        long now = System.currentTimeMillis();
        boolean withinInterval = now - lastSendMillis[settingId] < SEND_INTERVAL_MS;
        boolean crossedTier = Math.abs(value - lastSentValue[settingId]) >= tierWidthOf(settingId);
        if (withinInterval && !crossedTier) {
            hasPending[settingId] = true;
            pendingValue[settingId] = value;
            return;
        }
        hasPending[settingId] = false;
        lastSendMillis[settingId] = now;
        lastSentValue[settingId] = value;
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, settingId, value));
    }

    /**
     * 补发被节流跳过的值。{@code force} 为 true 时立刻发（关屏），
     * 否则只在 100ms 窗口过去后发（render 每帧调用一次，拖完自然会收尾）。
     */
    private void flushPendingSettings(boolean force) {
        if (Minecraft.getInstance().player == null) {
            // 已经退图/断线：服务端不会再接受设置，丢弃待发值，别往空连接上发包。
            Arrays.fill(hasPending, false);
            return;
        }
        long now = System.currentTimeMillis();
        for (int settingId = 0; settingId < SETTING_COUNT; settingId++) {
            if (!hasPending[settingId]) {
                continue;
            }
            if (!force && now - lastSendMillis[settingId] < SEND_INTERVAL_MS) {
                continue;
            }
            hasPending[settingId] = false;
            lastSendMillis[settingId] = now;
            lastSentValue[settingId] = pendingValue[settingId];
            NetworkHandler.sendToServer(new SwordSettingPayload(hand, settingId, pendingValue[settingId]));
        }
    }

    /**
     * "一档"的宽度：把设置的取值范围等分成 {@link #TIER_COUNT} 档。
     * 数值相对上次发送跨过至少一档就立刻发送（拖得快时界面数字仍能跟上），
     * 同一档内的碎变化在 100ms 窗口内合并。
     */
    private static int tierWidthOf(int settingId) {
        int min = 0;
        int max = 1;
        if (settingId == SwordSettingPayload.MINING_SPEED) {
            min = KunJinKaoSwordItem.MIN_MINING_SPEED;
            max = KunJinKaoSwordItem.MAX_MINING_SPEED;
        } else if (settingId == SwordSettingPayload.ORE_DROP_MULTIPLIER) {
            min = KunJinKaoSwordItem.MIN_ORE_DROP_MULTIPLIER;
            max = KunJinKaoSwordItem.MAX_ORE_DROP_MULTIPLIER;
        } else if (settingId == SwordSettingPayload.ATTACK_DAMAGE_LIMIT) {
            min = 0;
            max = KunJinKaoSwordItem.MAX_ATTACK_DAMAGE_LIMIT;
        } else if (settingId == SwordSettingPayload.LOOTING_MODE) {
            min = KunJinKaoSwordItem.MIN_LOOTING_LEVEL;
            max = KunJinKaoSwordItem.MAX_LOOTING_LEVEL;
        }
        return Math.max(1, (max - min) / TIER_COUNT);
    }

    // ===== 三个滑条（逻辑未变，只把文字改成「名称：值」）=====

    private static final class MiningSpeedSlider extends KunJinKaoSlider implements IntSlider {

        @Override
        public int sliderValue() {
            return currentSpeed;
        }

        @Override
        public int sliderMin() {
            return KunJinKaoSwordItem.MIN_MINING_SPEED;
        }

        @Override
        public int sliderMax() {
            return KunJinKaoSwordItem.MAX_MINING_SPEED;
        }

        @Override
        public void sliderSet(int value) {
            this.value = toSliderValue(value);
            applyValue();
        }



        private final IntConsumer onSpeedChanged;
        private int currentSpeed;

        private MiningSpeedSlider(int x, int y, int width, int height, int speed, IntConsumer onSpeedChanged) {
            super(x, y, width, height, Component.empty(), toSliderValue(speed));
            this.currentSpeed = speed;
            this.onSpeedChanged = onSpeedChanged;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(label("screen.kunjinkao.mining_speed", Component.literal(Integer.toString(roll(currentSpeed)))));
        }

        @Override
        protected void applyValue() {
            int speed = fromSliderValue(value);
            if (speed != currentSpeed) {
                currentSpeed = speed;
                onSpeedChanged.accept(speed);
            }
            updateMessage();
        }

        private static double toSliderValue(int speed) {
            int clamped = Math.max(KunJinKaoSwordItem.MIN_MINING_SPEED,
                    Math.min(KunJinKaoSwordItem.MAX_MINING_SPEED, speed));
            return (clamped - KunJinKaoSwordItem.MIN_MINING_SPEED)
                    / (double) (KunJinKaoSwordItem.MAX_MINING_SPEED - KunJinKaoSwordItem.MIN_MINING_SPEED);
        }

        private static int fromSliderValue(double value) {
            return KunJinKaoSwordItem.MIN_MINING_SPEED + (int) Math.round(value
                    * (KunJinKaoSwordItem.MAX_MINING_SPEED - KunJinKaoSwordItem.MIN_MINING_SPEED));
        }
    }

    private static final class OreDropMultiplierSlider extends KunJinKaoSlider implements IntSlider {

        @Override
        public int sliderValue() {
            return currentMultiplier;
        }

        @Override
        public int sliderMin() {
            return KunJinKaoSwordItem.MIN_ORE_DROP_MULTIPLIER;
        }

        @Override
        public int sliderMax() {
            return KunJinKaoSwordItem.MAX_ORE_DROP_MULTIPLIER;
        }

        @Override
        public void sliderSet(int value) {
            this.value = toSliderValue(value);
            applyValue();
        }



        private final IntConsumer onMultiplierChanged;
        private int currentMultiplier;

        private OreDropMultiplierSlider(int x, int y, int width, int height, int multiplier,
                                        IntConsumer onMultiplierChanged) {
            super(x, y, width, height, Component.empty(), toSliderValue(multiplier));
            this.currentMultiplier = multiplier;
            this.onMultiplierChanged = onMultiplierChanged;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(label("screen.kunjinkao.ore_drop_multiplier",
                    Component.literal(Integer.toString(roll(currentMultiplier)))));
        }

        @Override
        protected void applyValue() {
            int multiplier = fromSliderValue(value);
            if (multiplier != currentMultiplier) {
                currentMultiplier = multiplier;
                onMultiplierChanged.accept(multiplier);
            }
            updateMessage();
        }

        private static double toSliderValue(int multiplier) {
            int clamped = Math.max(KunJinKaoSwordItem.MIN_ORE_DROP_MULTIPLIER,
                    Math.min(KunJinKaoSwordItem.MAX_ORE_DROP_MULTIPLIER, multiplier));
            return (clamped - KunJinKaoSwordItem.MIN_ORE_DROP_MULTIPLIER)
                    / (double) (KunJinKaoSwordItem.MAX_ORE_DROP_MULTIPLIER
                    - KunJinKaoSwordItem.MIN_ORE_DROP_MULTIPLIER);
        }

        private static int fromSliderValue(double value) {
            return KunJinKaoSwordItem.MIN_ORE_DROP_MULTIPLIER + (int) Math.round(value
                    * (KunJinKaoSwordItem.MAX_ORE_DROP_MULTIPLIER
                    - KunJinKaoSwordItem.MIN_ORE_DROP_MULTIPLIER));
        }
    }

    /**
     * 抢夺等级滑条，取值 1..1000。
     * <p>
     * 显示的是<b>增强等级</b>（设置值 − 1），所以 1 显示成「0 级」也就是无抢夺 ——
     * 与挖矿倍数滑条"1 = 无加成"是同一套读法，两个滑条并排放在一起才不会一个 1 是关、
     * 另一个 1 是满级。
     */
    private static final class LootingLevelSlider extends KunJinKaoSlider implements IntSlider {

        /** 对外暴露的是"增强等级"（设置值 - 1），与滑条上显示的数字一致。 */
        @Override
        public int sliderValue() {
            return currentSetting - KunJinKaoSwordItem.MIN_LOOTING_LEVEL;
        }

        @Override
        public int sliderMin() {
            return 0;
        }

        @Override
        public int sliderMax() {
            return KunJinKaoSwordItem.MAX_LOOTING_LEVEL - KunJinKaoSwordItem.MIN_LOOTING_LEVEL;
        }

        @Override
        public void sliderSet(int value) {
            this.value = toSliderValue(value + KunJinKaoSwordItem.MIN_LOOTING_LEVEL);
            applyValue();
        }



        private final IntConsumer onLevelChanged;
        private int currentSetting;

        private LootingLevelSlider(int x, int y, int width, int height, int setting,
                                   IntConsumer onLevelChanged) {
            super(x, y, width, height, Component.empty(), toSliderValue(setting));
            this.currentSetting = setting;
            this.onLevelChanged = onLevelChanged;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(label("screen.kunjinkao.looting_mode",
                    Component.literal(Integer.toString(roll(currentSetting) - 1))));
        }

        @Override
        protected void applyValue() {
            int setting = fromSliderValue(value);
            if (setting != currentSetting) {
                currentSetting = setting;
                onLevelChanged.accept(setting);
            }
            updateMessage();
        }

        private static double toSliderValue(int setting) {
            int clamped = Math.max(KunJinKaoSwordItem.MIN_LOOTING_LEVEL,
                    Math.min(KunJinKaoSwordItem.MAX_LOOTING_LEVEL, setting));
            return (clamped - KunJinKaoSwordItem.MIN_LOOTING_LEVEL)
                    / (double) (KunJinKaoSwordItem.MAX_LOOTING_LEVEL
                    - KunJinKaoSwordItem.MIN_LOOTING_LEVEL);
        }

        private static int fromSliderValue(double value) {
            return KunJinKaoSwordItem.MIN_LOOTING_LEVEL + (int) Math.round(value
                    * (KunJinKaoSwordItem.MAX_LOOTING_LEVEL
                    - KunJinKaoSwordItem.MIN_LOOTING_LEVEL));
        }
    }

    private static final class AttackDamageSlider extends KunJinKaoSlider implements IntSlider {

        @Override
        public int sliderValue() {
            return currentDamage;
        }

        @Override
        public int sliderMin() {
            return 0;
        }

        @Override
        public int sliderMax() {
            return KunJinKaoSwordItem.MAX_ATTACK_DAMAGE_LIMIT;
        }

        @Override
        public void sliderSet(int value) {
            this.value = Math.max(0.0D, Math.min(1.0D,
                    value / (double) KunJinKaoSwordItem.MAX_ATTACK_DAMAGE_LIMIT));
            applyValue();
        }

        private final IntConsumer onDamageChanged;
        private int currentDamage;

        private AttackDamageSlider(int x, int y, int width, int height, int damage, IntConsumer onDamageChanged) {
            super(x, y, width, height, Component.empty(), damage / (double) KunJinKaoSwordItem.MAX_ATTACK_DAMAGE_LIMIT);
            this.currentDamage = damage;
            this.onDamageChanged = onDamageChanged;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            Component valueText = currentDamage >= KunJinKaoSwordItem.MAX_ATTACK_DAMAGE_LIMIT
                    ? Component.translatable("screen.kunjinkao.unlimited")
                    : Component.literal(Integer.toString(roll(currentDamage)));
            setMessage(label("screen.kunjinkao.attack_damage_limit", valueText));
        }

        @Override
        protected void applyValue() {
            int damage = Math.max(0, Math.min(KunJinKaoSwordItem.MAX_ATTACK_DAMAGE_LIMIT,
                    (int) Math.round(value * KunJinKaoSwordItem.MAX_ATTACK_DAMAGE_LIMIT)));
            if (damage != currentDamage) {
                currentDamage = damage;
                onDamageChanged.accept(damage);
            }
            updateMessage();
        }
    }
}