package dandeib.litesync.client;

import dandeib.litesync.core.FileScanner;
import dandeib.litesync.core.Json;
import dandeib.litesync.core.LocalFolderProvider;
import dandeib.litesync.core.Settings;
import dandeib.litesync.core.SyncEngine;
import dandeib.litesync.core.SyncState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LiteSyncClient implements ClientModInitializer {
	public static final String MOD_ID = "litesync";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final long POLL_SECONDS = 10;
	private static final long SHUTDOWN_WAIT_SECONDS = 10;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final SystemToast.SystemToastId TOAST_INFO = new SystemToast.SystemToastId();
	private static final SystemToast.SystemToastId TOAST_ERROR = new SystemToast.SystemToastId(10_000L);
	private static final Identifier ICON = Identifier.fromNamespaceAndPath(MOD_ID, "icon/sync");

	private static LiteSyncClient instance;

	// All sync work runs on this thread, never on the render thread.
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "LiteSync");
		t.setDaemon(true);
		return t;
	});
	private Minecraft client;
	private boolean started;
	private Screen pendingScreen; // client thread only

	// LiteSync thread only.
	private UUID uuid;
	private Path configDir;
	private Settings settings;
	private SyncEngine engine;
	private Path stateFile;
	private SyncState state;
	private String lastErrorToast;

	// Read by the GUI and commands.
	private volatile String status = "Not started yet";
	private volatile String lastSync = "never";
	private volatile String lastError = "";
	private volatile int fileCount;
	private volatile String folderView = "";
	private volatile Settings settingsView;

	public static LiteSyncClient get() {
		return instance;
	}

	@Override
	public void onInitializeClient() {
		instance = this;
		client = Minecraft.getInstance();
		ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
			if (!(screen instanceof TitleScreen)) return;
			if (!started) {
				started = true;
				start();
			}
			addTitleButton(screen);
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> stop());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (pendingScreen != null) {
				mc.gui.setScreen(pendingScreen);
				pendingScreen = null;
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommands.literal("litesync")
						.executes(ctx -> {
							// Open on the next tick, otherwise closing the chat closes the screen right away.
							pendingScreen = new LiteSyncScreen(null);
							return 1;
						})
						.then(ClientCommands.literal("now").executes(ctx -> {
							syncNow();
							ctx.getSource().sendFeedback(Component.literal("LiteSync: sync started"));
							return 1;
						}))
						.then(ClientCommands.literal("status").executes(ctx -> {
							for (String line : statusLines()) ctx.getSource().sendFeedback(Component.literal(line));
							return 1;
						}))));
		LOGGER.info("LiteSync loaded");
	}

	private void addTitleButton(Screen screen) {
		List<AbstractWidget> widgets = Screens.getWidgets(screen);
		Component singleplayer = Component.translatable("menu.singleplayer");
		AbstractWidget anchor = widgets.stream().filter(w -> singleplayer.equals(w.getMessage())).findFirst().orElse(null);
		SpriteIconButton button = SpriteIconButton.builder(Component.literal("LiteSync"),
						b -> client.gui.setScreen(new LiteSyncScreen(screen)), true)
				.sprite(ICON, 15, 15).width(20).withTootip().build();
		if (anchor != null) button.setPosition(anchor.getX() + anchor.getWidth() + 4, anchor.getY());
		else button.setPosition(4, 4);
		// Other mods often put buttons there; move right so theirs doesn't swallow the click.
		while (button.getX() + button.getWidth() < screen.width
				&& widgets.stream().anyMatch(w -> w.visible && w.getRectangle().intersects(button.getRectangle()))) {
			button.setX(button.getX() + button.getWidth() + 4);
		}
		widgets.add(button);
	}

	private void start() {
		uuid = client.getUser().getProfileId();
		FabricLoader loader = FabricLoader.getInstance();
		// Offline accounts get a name-based UUID (version 3), so there is no stable identity.
		if (uuid.version() == 3 && !loader.isDevelopmentEnvironment()) {
			status = "Disabled: offline account";
			LOGGER.info("Offline account detected, LiteSync is disabled");
			return;
		}
		configDir = loader.getConfigDir().resolve(MOD_ID);
		try {
			settings = Settings.load(configDir.resolve("settings.json"));
		} catch (IOException e) {
			LOGGER.error("Could not read settings.json, using defaults", e);
			settings = new Settings();
		}
		settingsView = copy(settings);
		executor.execute(() -> {
			rebuild();
			sync(true);
		});
		executor.scheduleWithFixedDelay(() -> syncIfLocalChanged(true), POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
	}

	/** Leaves engine null if the folder is invalid. */
	private void rebuild() {
		engine = null;
		Path folder;
		try {
			folder = settings.syncFolder();
		} catch (InvalidPathException e) {
			folderView = settings.localSyncFolder;
			status = "Invalid sync folder";
			return;
		}
		folderView = folder.toString();
		// One state per folder: a base from another folder would look like deletions there.
		String target = FileScanner.sha256(folder.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 8);
		Path newStateFile = configDir.resolve("state-" + uuid + "-" + target + ".json");
		if (!newStateFile.equals(stateFile)) {
			stateFile = newStateFile;
			state = null;
		}
		int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
		SyncEngine.Options options = SyncEngine.Options.of(settings, dataVersion, configDir.resolve("manifest-backup-" + uuid + "-" + target + ".json"));
		engine = new SyncEngine(new LocalFolderProvider(folder.resolve(uuid.toString())), FabricLoader.getInstance().getGameDir(),
				configDir.resolve("trash"), uuid.toString(), options, Clock.systemUTC());
		status = "Ready";
	}

	private void sync(boolean writeConfigs) {
		if (engine == null) return;
		try {
			if (state == null) state = SyncState.load(stateFile);
			status = "Syncing…";
			SyncEngine.Result r = engine.sync(state, writeConfigs);
			state.save(stateFile);
			fileCount = state.base.size();
			lastSync = LocalDateTime.now().format(TIME);
			lastError = "";
			lastErrorToast = null;
			status = "Ready";
			report(r);
		} catch (Exception e) {
			status = "Error";
			lastError = String.valueOf(e.getMessage());
			LOGGER.warn("Sync failed", e);
			toastErrorOnce("LiteSync: error", lastError);
		}
	}

	private void report(SyncEngine.Result r) {
		if (r.changedAnything()) {
			LOGGER.info("Sync: {} uploaded, {} downloaded, {} deleted locally, {} deleted remotely, configs written: {}",
					r.uploaded(), r.downloaded(), r.deletedLocal(), r.deletedRemote(), r.configsWritten());
		}
		r.warnings().forEach(w -> LOGGER.warn(w));
		if (!r.configsWritten().isEmpty()) client.execute(() -> reloadConfigs(r.configsWritten()));
		for (String copy : r.conflicts()) {
			LOGGER.warn("Conflict: your local version was saved as schematics/{}", copy);
			toast(TOAST_ERROR, "LiteSync: conflict", "Local version saved as " + copy);
		}
		if (!r.newer().isEmpty()) {
			toast(TOAST_INFO, "LiteSync: newer MC version", r.newer().size() + " schematic(s) were made with a newer version");
		}
		if (r.downloaded() + r.uploaded() + r.configsWritten().size() > 0) {
			toast(TOAST_INFO, "LiteSync", "Sync done: " + r.downloaded() + " downloaded, " + r.uploaded() + " uploaded");
		}
	}

	/** Litematica and malilib have already loaded their configs, so reload them after writing. */
	private static void reloadConfigs(List<String> written) {
		try {
			if (written.contains(SyncEngine.LITEMATICA_CONFIG)) fi.dy.masa.litematica.config.Configs.loadFromFile();
			if (written.contains(SyncEngine.MALILIB_CONFIG)) fi.dy.masa.malilib.MaLiLibConfigs.loadFromFile();
			fi.dy.masa.malilib.event.InputEventHandler.getKeybindManager().updateUsedKeys();
		} catch (Throwable t) {
			// Never crash the game here; the file is written and applies after a restart at the latest.
			LOGGER.warn("Could not reload config", t);
		}
	}

	/** The same error on every poll must not show a toast every 10 s. */
	private void toastErrorOnce(String title, String message) {
		if (message.equals(lastErrorToast)) return;
		lastErrorToast = message;
		toast(TOAST_ERROR, title, message);
	}

	private void toast(SystemToast.SystemToastId id, String title, String message) {
		client.execute(() -> SystemToast.addOrUpdate(client.gui.toastManager(), id, Component.literal(title), Component.literal(message)));
	}

	private void syncIfLocalChanged(boolean writeConfigs) {
		if (engine == null) return;
		try {
			if (state == null || engine.hasLocalChanges(state)) sync(writeConfigs);
		} catch (Exception e) {
			LOGGER.warn("Local scan failed", e);
		}
	}

	/** Uploads local changes on exit without holding up shutdown for long. */
	private void stop() {
		if (configDir == null) return;
		try {
			// Don't write configs: Litematica saves its in-memory state afterwards.
			executor.submit(() -> syncIfLocalChanged(false)).get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			LOGGER.warn("Final sync on shutdown did not finish", e);
		}
		executor.shutdownNow();
	}

	// ---- API for the GUI and commands (client thread) ----

	public boolean isActive() {
		return configDir != null;
	}

	public String status() {
		return status;
	}

	/** Empty unless the last sync failed. */
	public String lastError() {
		return lastError;
	}

	public String lastSyncLine() {
		return "Last sync: " + lastSync + " · " + fileCount + " schematics";
	}

	public List<String> statusLines() {
		return List.of("Folder: " + folderView, "Status: " + status, lastSyncLine(),
				lastError.isEmpty() ? "No errors" : "Error: " + lastError);
	}

	public Settings editableSettings() {
		return settingsView == null ? null : copy(settingsView);
	}

	public void applySettings(Settings s) {
		if (!isActive()) return;
		Settings copy = copy(s);
		settingsView = copy(s);
		executor.execute(() -> {
			settings = copy;
			try {
				settings.save(configDir.resolve("settings.json"));
			} catch (IOException e) {
				LOGGER.warn("Could not save settings.json", e);
			}
			rebuild();
		});
	}

	public void syncNow() {
		if (!isActive()) return;
		executor.execute(() -> {
			if (engine == null) rebuild();
			sync(true);
		});
	}

	private static Settings copy(Settings s) {
		return Json.GSON.fromJson(Json.GSON.toJson(s), Settings.class);
	}
}
