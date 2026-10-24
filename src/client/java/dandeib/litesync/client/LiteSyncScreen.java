package dandeib.litesync.client;

import dandeib.litesync.core.Settings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** Settings and status in the style of the vanilla options. Changes apply on close. */
public class LiteSyncScreen extends OptionsSubScreen {

	private static final int BIG = 310;
	private static final int SMALL = 150;

	private final LiteSyncClient mod = LiteSyncClient.get();
	private final Settings settings = mod.editableSettings();
	private StringWidget statusLine;
	private StringWidget syncLine;

	public LiteSyncScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.literal("LiteSync"));
	}

	@Override
	protected void addOptions() {
		list.addHeader(Component.literal("Status"));
		statusLine = new StringWidget(BIG, 20, Component.empty(), font);
		syncLine = new StringWidget(BIG, 20, Component.empty(), font);
		list.addBig(statusLine);
		list.addBig(syncLine);
		if (settings == null) {
			updateStatus();
			return;
		}
		Button syncNow = Button.builder(Component.literal("Sync now"), b -> {
			mod.applySettings(settings);
			mod.syncNow();
		}).width(BIG).build();
		list.addBig(syncNow);

		list.addHeader(Component.literal("Sync folder"));
		EditBox folder = new EditBox(font, BIG, 20, Component.literal("Sync folder"));
		folder.setMaxLength(1024);
		folder.setValue(settings.localSyncFolder);
		folder.setHint(Component.literal(Settings.defaultSyncFolder().toString()));
		folder.setResponder(v -> settings.localSyncFolder = v.trim());
		folder.setTooltip(Tooltip.create(Component.literal(
				"Folder where LiteSync stores the synced data. All instances pointing to the same folder share their schematics and settings. "
						+ "Leave empty to use the default folder shown in gray. You can point it at a OneDrive/Nextcloud folder to sync between PCs.")));
		list.addBig(folder);

		list.addHeader(Component.literal("What to sync"));
		list.addSmall(List.of(
				toggle("Litematica config", settings.syncLitematicaConfig, v -> settings.syncLitematicaConfig = v,
						"ON: Litematica's settings (config/litematica.json) are shared between instances. Each setting is merged on its own; "
								+ "if two instances change the same setting, the newest change wins. OFF: every instance keeps its own Litematica settings."),
				toggle("malilib config", settings.syncMalilibConfig, v -> settings.syncMalilibConfig = v,
						"ON: the settings of the malilib library (config/malilib.json) are shared as well, merged the same way as the Litematica config. "
								+ "OFF: every instance keeps its own malilib settings."),
				toggle("Hotkeys", settings.syncHotkeys, v -> settings.syncHotkeys = v,
						"ON: Litematica and malilib hotkeys are synced together with the configs above. "
								+ "OFF: every instance keeps its own key bindings. Has no effect if both configs are OFF."),
				toggle(".schem / .nbt", settings.extraExtensions(), settings::setExtraExtensions,
						"ON: besides .litematic, the schematics folder also syncs .schem (Sponge/WorldEdit) and .nbt (vanilla structure) files. "
								+ "OFF: only .litematic files are synced."),
				CycleButton.booleanBuilder(Component.literal("Hide"), Component.literal("Show + notify"), settings.hideNewerSchematics)
						.withTooltip(v -> Tooltip.create(Component.literal(v
								? "What to do with schematics saved in a newer Minecraft version than this instance. Hide: they are not downloaded "
										+ "to this instance; copies that already exist here are kept."
								: "What to do with schematics saved in a newer Minecraft version than this instance. Show + notify: they are "
										+ "downloaded anyway and a notification tells you; blocks that don't exist in this version may be missing.")))
						.create(0, 0, SMALL, 20, Component.literal("Newer MC"), (b, v) -> settings.hideNewerSchematics = v)));

		list.addHeader(Component.literal("Config-Blacklist"));
		EditBox blacklist = new EditBox(font, BIG, 20, Component.literal("Config-Blacklist"));
		blacklist.setMaxLength(4096);
		blacklist.setValue(String.join(", ", settings.configBlacklist));
		blacklist.moveCursorToStart(false);
		blacklist.setHint(Component.literal("Category or Category.key, comma separated"));
		blacklist.setResponder(v -> settings.configBlacklist = new ArrayList<>(Arrays.stream(v.split(","))
				.map(String::trim).filter(s -> !s.isEmpty()).toList()));
		blacklist.setTooltip(Tooltip.create(Component.literal(
				"Config values that are never synced and stay different per instance. Enter a whole category (e.g. Generic) or a single "
				+ "setting (e.g. Generic.customSchematicBaseDirectory), separated by commas.")));
		list.addBig(blacklist);
		updateStatus();
	}

	private static AbstractWidget toggle(String label, boolean value, Consumer<Boolean> setter, String tooltip) {
		return CycleButton.onOffBuilder(value)
				.withTooltip(v -> Tooltip.create(Component.literal(tooltip)))
				.create(0, 0, SMALL, 20, Component.literal(label), (b, v) -> setter.accept(v));
	}

	private void updateStatus() {
		if (!mod.isActive()) {
			statusLine.setMessage(Component.literal(mod.status()).withStyle(ChatFormatting.GRAY));
			syncLine.setMessage(Component.literal("Starts once the title screen is reached.").withStyle(ChatFormatting.GRAY));
			return;
		}
		String error = mod.lastError();
		statusLine.setMessage(error.isEmpty()
				? Component.literal(mod.status()).withStyle(ChatFormatting.GREEN)
				: Component.literal("Error: " + error).withStyle(ChatFormatting.RED));
		syncLine.setMessage(Component.literal(mod.lastSyncLine()).withStyle(ChatFormatting.GRAY));
	}

	@Override
	public void tick() {
		if (statusLine != null) updateStatus();
	}

	@Override
	public void onClose() {
		if (settings != null) mod.applySettings(settings);
		super.onClose();
	}
}
