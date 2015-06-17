package com.nisovin.shopkeepers;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import com.nisovin.shopkeepers.events.*;
import com.nisovin.shopkeepers.pluginhandlers.*;
import com.nisovin.shopkeepers.shopobjects.*;
import com.nisovin.shopkeepers.shopobjects.living.LivingEntityShop;
import com.nisovin.shopkeepers.shoptypes.*;
import com.nisovin.shopkeepers.ui.UIManager;
import com.nisovin.shopkeepers.ui.defaults.DefaultUIs;
import com.nisovin.shopkeepers.ui.defaults.TradingHandler;
import com.nisovin.shopkeepers.abstractTypes.SelectableTypeRegistry;
import com.nisovin.shopkeepers.compat.NMSManager;
import com.nisovin.shopkeepers.compat.api.NMSCallProvider;

public class ShopkeepersPlugin extends JavaPlugin implements ShopkeepersAPI {

	private static ShopkeepersPlugin plugin;

	public static ShopkeepersPlugin getInstance() {
		return plugin;
	}

	// shop types manager:
	private final SelectableTypeRegistry<ShopType<?>> shopTypesManager = new SelectableTypeRegistry<ShopType<?>>() {

		@Override
		protected String getTypeName() {
			return "shop type";
		}

		@Override
		public boolean canBeSelected(Player player, ShopType<?> type) {
			// TODO This currently skips the admin shop type. Maybe included the admin shop types here for players
			// which are admins, because there /could/ be different types of admin shops in the future (?)
			return super.canBeSelected(player, type) && type.isPlayerShopType();
		}
	};

	// shop object types manager:
	private final SelectableTypeRegistry<ShopObjectType> shopObjectTypesManager = new SelectableTypeRegistry<ShopObjectType>() {

		@Override
		protected String getTypeName() {
			return "shop object type";
		}
	};

	// ui manager:
	private final UIManager uiManager = new UIManager();

	// all shopkeepers:
	private final Map<UUID, Shopkeeper> shopkeepersById = new LinkedHashMap<UUID, Shopkeeper>();
	private final Collection<Shopkeeper> allShopkeepersView = Collections.unmodifiableCollection(shopkeepersById.values());
	private final Map<ChunkData, List<Shopkeeper>> shopkeepersByChunk = new HashMap<ChunkData, List<Shopkeeper>>();
	private final Map<String, Shopkeeper> activeShopkeepers = new HashMap<String, Shopkeeper>(); // TODO remove this (?)
	private final Collection<Shopkeeper> activeShopkeepersView = Collections.unmodifiableCollection(activeShopkeepers.values());

	private final Map<String, ConfirmEntry> confirming = new HashMap<String, ConfirmEntry>();
	private final Map<String, Shopkeeper> naming = Collections.synchronizedMap(new HashMap<String, Shopkeeper>());
	private final Map<String, List<String>> recentlyPlacedChests = new HashMap<String, List<String>>();
	private final Map<String, Block> selectedChest = new HashMap<String, Block>();

	// saving:
	private boolean dirty = false;
	private int chunkLoadSaveTask = -1;
	private final SaveInfo saveInfo = new SaveInfo(); // keeps track about certain stats and information during a save, gets reused
	private int saveIOTask = -1; // the task which performs file io during a save
	private boolean saveRealAgain = false; // determines if there was another saveReal()-request while another saveIOTask was still in progress

	// listeners:
	private CreatureForceSpawnListener creatureForceSpawnListener = null;
	private SignShopListener signShopListener = null;

	@Override
	public void onEnable() {
		plugin = this;

		// register default stuff:
		shopTypesManager.registerAll(DefaultShopTypes.getAll());
		shopObjectTypesManager.registerAll(DefaultShopObjectTypes.getAll());
		uiManager.registerAll(DefaultUIs.getAll());

		// try to load suitable NMS code
		NMSManager.load(this);
		if (NMSManager.getProvider() == null) {
			plugin.getLogger().severe("Incompatible server version: Shopkeepers cannot be enabled.");
			this.setEnabled(false);
			return;
		}

		// get config
		File file = new File(this.getDataFolder(), "config.yml");
		if (!file.exists()) {
			this.saveDefaultConfig();
		}
		this.reloadConfig();
		Configuration config = this.getConfig();
		if (Settings.loadConfiguration(config)) {
			// if values were missing -> add those to the file and save it
			this.saveConfig();
		}
		Log.setDebug(config.getBoolean("debug", false));

		// get lang config
		String lang = config.getString("language", "en");
		File langFile = new File(this.getDataFolder(), "language-" + lang + ".yml");
		if (!langFile.exists() && this.getResource("language-" + lang + ".yml") != null) {
			this.saveResource("language-" + lang + ".yml", false);
		}
		if (langFile.exists()) {
			try {
				YamlConfiguration langConfig = new YamlConfiguration();
				langConfig.load(langFile);
				Settings.loadLanguageConfiguration(langConfig);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		// process additional permissions
		String[] perms = Settings.maxShopsPermOptions.replace(" ", "").split(",");
		for (String perm : perms) {
			if (Bukkit.getPluginManager().getPermission("shopkeeper.maxshops." + perm) == null) {
				Bukkit.getPluginManager().addPermission(new Permission("shopkeeper.maxshops." + perm, PermissionDefault.FALSE));
			}
		}

		// inform ui manager (registers ui event handlers):
		uiManager.onEnable(this);

		// register events
		PluginManager pm = Bukkit.getPluginManager();
		pm.registerEvents(new WorldListener(this), this);
		pm.registerEvents(new PlayerJoinQuitListener(this), this);
		pm.registerEvents(new ShopNamingListener(this), this);
		pm.registerEvents(new ChestListener(this), this);
		pm.registerEvents(new CreateListener(this), this);
		pm.registerEvents(new VillagerInteractionListener(this), this);
		pm.registerEvents(new LivingEntityShopListener(this), this);

		if (Settings.enableSignShops) {
			this.signShopListener = new SignShopListener(this);
			pm.registerEvents(signShopListener, this);
		}
		if (Settings.enableCitizenShops) {
			try {
				if (!CitizensHandler.isEnabled()) {
					Log.warning("Citizens Shops enabled, but Citizens plugin not found or disabled.");
					Settings.enableCitizenShops = false;
				} else {
					this.getLogger().info("Citizens found, enabling NPC shopkeepers");
					CitizensShopkeeperTrait.registerTrait();
				}
			} catch (Throwable ex) {
			}
		}

		if (Settings.blockVillagerSpawns) {
			pm.registerEvents(new BlockVillagerSpawnListener(), this);
		}

		if (Settings.protectChests) {
			pm.registerEvents(new ChestProtectListener(this), this);
		}
		if (Settings.deleteShopkeeperOnBreakChest) {
			pm.registerEvents(new RemoveShopOnChestBreakListener(this), this);
		}

		// register force-creature-spawn event handler:
		if (Settings.bypassSpawnBlocking) {
			creatureForceSpawnListener = new CreatureForceSpawnListener();
			Bukkit.getPluginManager().registerEvents(creatureForceSpawnListener, this);
		}

		// register command handler:
		CommandManager commandManager = new CommandManager(this);
		this.getCommand("shopkeeper").setExecutor(commandManager);

		// load shopkeeper saved data:
		this.load();

		// activate (spawn) shopkeepers in loaded chunks:
		for (World world : Bukkit.getWorlds()) {
			this.loadShopkeepersInWorld(world);
		}

		// start removing inactive player shops after a short delay:
		Bukkit.getScheduler().runTaskLater(this, new Runnable() {

			@Override
			public void run() {
				removeInactivePlayerShops();
			}
		}, 5L);

		// start teleporter task:
		Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
			public void run() {
				List<Shopkeeper> readd = new ArrayList<Shopkeeper>();
				Iterator<Map.Entry<String, Shopkeeper>> iter = activeShopkeepers.entrySet().iterator();
				while (iter.hasNext()) {
					Shopkeeper shopkeeper = iter.next().getValue();
					boolean update = shopkeeper.check();
					if (update) {
						// if the shopkeeper had to be respawned it's shop id changed:
						// this removes the entry which was stored with the old shop id and later adds back the shopkeeper with it's new id
						readd.add(shopkeeper);
						iter.remove();
					}
				}
				if (!readd.isEmpty()) {
					for (Shopkeeper shopkeeper : readd) {
						if (shopkeeper.isActive()) {
							activeShopkeepers.put(shopkeeper.getObjectId(), shopkeeper);
						}
					}

					// shopkeepers might have been respawned, request save:
					save();
				}
			}
		}, 200, 200); // 10 seconds

		// start verifier task:
		if (Settings.enableSpawnVerifier) {
			Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
				public void run() {
					int count = 0;
					for (Entry<ChunkData, List<Shopkeeper>> chunkEntry : shopkeepersByChunk.entrySet()) {
						ChunkData chunk = chunkEntry.getKey();
						if (chunk.isChunkLoaded()) {
							List<Shopkeeper> shopkeepers = chunkEntry.getValue();
							for (Shopkeeper shopkeeper : shopkeepers) {
								if (shopkeeper.needsSpawning() && !shopkeeper.isActive()) {
									// remove old entry in activeShopkeepers, in case there is one:
									String oldObjectId = shopkeeper.getObjectId();
									if (oldObjectId != null) {
										activeShopkeepers.remove(oldObjectId);
									}

									boolean spawned = shopkeeper.spawn();
									if (spawned) {
										activeShopkeepers.put(shopkeeper.getObjectId(), shopkeeper);
										count++;
									} else {
										Log.debug("Failed to spawn shopkeeper at " + shopkeeper.getPositionString());
									}
								}
							}
						}
					}
					if (count > 0) {
						Log.debug("Spawn verifier: " + count + " shopkeepers respawned");
						save();
					}
				}
			}, 600, 1200); // 30,60 seconds
		}

		// start save task:
		if (!Settings.saveInstantly) {
			Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
				public void run() {
					if (dirty) {
						saveReal();
					}
				}
			}, 6000, 6000); // 5 minutes
		}

		// let's update the shopkeepers for all online players:
		if (NMSManager.getProvider().supportsPlayerUUIDs()) {
			for (Player player : Bukkit.getOnlinePlayers()) {
				if (Utils.isNPC(player)) continue;
				this.updateShopkeepersForPlayer(player.getUniqueId(), player.getName());
			}
		}
	}

	@Override
	public void onDisable() {
		if (dirty) {
			this.saveReal(false); // not async here
		}

		// close all open windows:
		uiManager.closeAll();

		for (Shopkeeper shopkeeper : activeShopkeepers.values()) {
			shopkeeper.despawn();
		}
		activeShopkeepers.clear();
		shopkeepersByChunk.clear();
		shopkeepersById.clear();

		shopTypesManager.clearAllSelections();
		shopObjectTypesManager.clearAllSelections();

		confirming.clear();
		naming.clear();
		selectedChest.clear();

		// clear all types of registers:
		shopTypesManager.clearAll();
		shopObjectTypesManager.clearAll();
		uiManager.clearAll();

		HandlerList.unregisterAll(this);
		Bukkit.getScheduler().cancelTasks(this);

		plugin = null;
	}

	/**
	 * Reloads the plugin.
	 */
	public void reload() {
		this.onDisable();
		this.onEnable();
	}

	void onPlayerQuit(Player player) {
		String playerName = player.getName();
		shopTypesManager.clearSelection(player);
		shopObjectTypesManager.clearSelection(player);
		uiManager.onInventoryClose(player);

		selectedChest.remove(playerName);
		recentlyPlacedChests.remove(playerName);
		naming.remove(playerName);
		this.endConfirmation(player);
	}

	// bypassing creature blocking plugins ('region protection' plugins):
	public void forceCreatureSpawn(Location location, EntityType entityType) {
		if (creatureForceSpawnListener != null && Settings.bypassSpawnBlocking) {
			creatureForceSpawnListener.forceCreatureSpawn(location, entityType);
		}
	}

	public void cancelNextBlockPhysics(Location location) {
		if (signShopListener != null) {
			signShopListener.cancelNextBlockPhysics(location);
		}
	}

	// UI

	public UIManager getUIManager() {
		return uiManager;
	}

	// SHOP TYPES

	public SelectableTypeRegistry<ShopType<?>> getShopTypeRegistry() {
		return shopTypesManager;
	}

	// SHOP OBJECT TYPES

	public SelectableTypeRegistry<ShopObjectType> getShopObjectTypeRegistry() {
		return shopObjectTypesManager;
	}

	// RECENTLY PLACED CHESTS

	void onChestPlacement(Player player, Block chest) {
		assert player != null && chest != null && Utils.isChest(chest.getType());
		String playerName = player.getName();
		List<String> recentlyPlaced = recentlyPlacedChests.get(playerName);
		if (recentlyPlaced == null) {
			recentlyPlaced = new LinkedList<String>();
			recentlyPlacedChests.put(playerName, recentlyPlaced);
		}
		recentlyPlaced.add(chest.getWorld().getName() + "," + chest.getX() + "," + chest.getY() + "," + chest.getZ());
		if (recentlyPlaced.size() > 5) {
			recentlyPlaced.remove(0);
		}
	}

	public boolean wasRecentlyPlaced(Player player, Block chest) {
		assert player != null && chest != null && Utils.isChest(chest.getType());
		String playerName = player.getName();
		List<String> recentlyPlaced = recentlyPlacedChests.get(playerName);
		return recentlyPlaced != null && recentlyPlaced.contains(chest.getWorld().getName() + "," + chest.getX() + "," + chest.getY() + "," + chest.getZ());
	}

	// SELECTED CHEST

	void selectChest(Player player, Block chest) {
		assert player != null;
		String playerName = player.getName();
		if (chest == null) selectedChest.remove(playerName);
		else {
			assert Utils.isChest(chest.getType());
			selectedChest.put(playerName, chest);
		}
	}

	public Block getSelectedChest(Player player) {
		assert player != null;
		return selectedChest.get(player.getName());
	}

	// COMMAND CONFIRMING

	void waitForConfirm(final Player player, Runnable action, int delay) {
		assert player != null && delay > 0;
		int taskId = new BukkitRunnable() {

			@Override
			public void run() {
				endConfirmation(player);
				Utils.sendMessage(player, Settings.msgConfirmationExpired);
			}
		}.runTaskLater(this, delay).getTaskId();
		ConfirmEntry oldEntry = confirming.put(player.getName(), new ConfirmEntry(action, taskId));
		if (oldEntry != null) {
			// end old confirmation task:
			Bukkit.getScheduler().cancelTask(oldEntry.getTaskId());
		}
	}

	Runnable endConfirmation(Player player) {
		ConfirmEntry entry = confirming.remove(player.getName());
		if (entry != null) {
			// end confirmation task:
			Bukkit.getScheduler().cancelTask(entry.getTaskId());

			// return action:
			return entry.getAction();
		}
		return null;
	}

	void onConfirm(Player player) {
		assert player != null;
		Runnable action = this.endConfirmation(player);
		if (action != null) {
			// execute confirmed task:
			action.run();
		} else {
			Utils.sendMessage(player, Settings.msgNothingToConfirm);
		}
	}

	// SHOPKEEPER NAMING

	void onNaming(Player player, Shopkeeper shopkeeper) {
		assert player != null && shopkeeper != null;
		naming.put(player.getName(), shopkeeper);
	}

	Shopkeeper getCurrentlyNamedShopkeeper(Player player) {
		assert player != null;
		return naming.get(player.getName());
	}

	boolean isNaming(Player player) {
		assert player != null;
		return this.getCurrentlyNamedShopkeeper(player) != null;
	}

	Shopkeeper endNaming(Player player) {
		assert player != null;
		return naming.remove(player.getName());
	}

	// SHOPKEEPER MEMORY STORAGE

	private void addShopkeeperToChunk(Shopkeeper shopkeeper, ChunkData chunkData) {
		List<Shopkeeper> list = shopkeepersByChunk.get(chunkData);
		if (list == null) {
			list = new ArrayList<Shopkeeper>();
			shopkeepersByChunk.put(chunkData, list);
		}
		list.add(shopkeeper);
	}

	private void removeShopkeeperFromChunk(Shopkeeper shopkeeper, ChunkData chunkData) {
		List<Shopkeeper> byChunk = shopkeepersByChunk.get(chunkData);
		if (byChunk == null) return;
		if (byChunk.remove(shopkeeper) && byChunk.isEmpty()) {
			shopkeepersByChunk.remove(chunkData);
		}
	}

	// this needs to be called right after a new shopkeeper was created..
	void registerShopkeeper(Shopkeeper shopkeeper) {
		assert shopkeeper != null;
		// assert !this.isRegistered(shopkeeper);

		// add default trading handler, if none is provided:
		if (shopkeeper.getUIHandler(DefaultUIs.TRADING_WINDOW) == null) {
			shopkeeper.registerUIHandler(new TradingHandler(DefaultUIs.TRADING_WINDOW, shopkeeper));
		}

		// store by uuid:
		shopkeepersById.put(shopkeeper.getUniqueId(), shopkeeper);

		// add shopkeeper to chunk:
		ChunkData chunkData = shopkeeper.getChunkData();
		this.addShopkeeperToChunk(shopkeeper, chunkData);

		if (!shopkeeper.needsSpawning()) activeShopkeepers.put(shopkeeper.getObjectId(), shopkeeper);
		else if (!shopkeeper.isActive() && chunkData.isChunkLoaded()) {
			boolean spawned = shopkeeper.spawn();
			if (spawned) {
				activeShopkeepers.put(shopkeeper.getObjectId(), shopkeeper);
			} else {
				Log.debug("Failed to spawn shopkeeper at " + shopkeeper.getPositionString());
			}
		}
	}

	@Override
	public Shopkeeper getShopkeeperByEntity(Entity entity) {
		if (entity == null) return null;
		Shopkeeper shopkeeper = activeShopkeepers.get("entity" + entity.getEntityId());
		if (shopkeeper != null) return shopkeeper;
		// check if this is a citizens npc shopkeeper:
		Integer npcId = CitizensHandler.getNPCId(entity);
		if (npcId == null) return null;
		return activeShopkeepers.get("NPC-" + npcId);
	}

	@Override
	public Shopkeeper getShopkeeper(UUID shopkeeperUUID) {
		return shopkeepersById.get(shopkeeperUUID);
	}

	@Override
	public Shopkeeper getShopkeeperByBlock(Block block) {
		if (block == null) return null;
		return activeShopkeepers.get("block" + block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ());
	}

	public Shopkeeper getShopkeeperById(String shopkeeperId) {
		return activeShopkeepers.get(shopkeeperId);
	}

	@Override
	public boolean isShopkeeper(Entity entity) {
		return this.getShopkeeperByEntity(entity) != null;
	}

	/*
	 * Shopkeeper getActiveShopkeeper(String shopId) {
	 * return activeShopkeepers.get(shopId);
	 * }
	 */

	@Override
	public Collection<Shopkeeper> getAllShopkeepers() {
		return allShopkeepersView;
	}

	@Override
	public Collection<List<Shopkeeper>> getAllShopkeepersByChunks() {
		return Collections.unmodifiableCollection(shopkeepersByChunk.values());
	}

	@Override
	public Collection<Shopkeeper> getActiveShopkeepers() {
		return activeShopkeepersView;
	}

	@Override
	public List<Shopkeeper> getShopkeepersInChunk(String worldName, int x, int z) {
		return this.getShopkeepersInChunk(new ChunkData(worldName, x, z));
	}

	@Override
	public List<Shopkeeper> getShopkeepersInChunk(ChunkData chunkData) {
		List<Shopkeeper> byChunk = shopkeepersByChunk.get(chunkData);
		if (byChunk == null) return null;
		return Collections.unmodifiableList(byChunk);
	}

	boolean isChestProtected(Player player, Block block) {
		for (Shopkeeper shopkeeper : activeShopkeepers.values()) {
			if (shopkeeper instanceof PlayerShopkeeper) {
				PlayerShopkeeper pshop = (PlayerShopkeeper) shopkeeper;
				if ((player == null || !pshop.isOwner(player)) && pshop.usesChest(block)) {
					return true;
				}
			}
		}
		return false;
	}

	List<PlayerShopkeeper> getShopkeeperOwnersOfChest(Block block) {
		List<PlayerShopkeeper> owners = new ArrayList<PlayerShopkeeper>();
		for (Shopkeeper shopkeeper : activeShopkeepers.values()) {
			if (shopkeeper instanceof PlayerShopkeeper) {
				PlayerShopkeeper pshop = (PlayerShopkeeper) shopkeeper;
				if (pshop.usesChest(block)) {
					owners.add(pshop);
				}
			}
		}
		return owners;
	}

	// LOADING/UNLOADING/REMOVAL

	private void activateShopkeeper(Shopkeeper shopkeeper) {
		assert shopkeeper != null;
		if (shopkeeper.needsSpawning() && !shopkeeper.isActive()) {
			// remove old shopkeeper indexed by old shop object id, in case there is one:
			Shopkeeper oldShopkeeper = activeShopkeepers.remove(shopkeeper.getObjectId());
			if (Log.isDebug() && oldShopkeeper != null && oldShopkeeper.getShopObject() instanceof LivingEntityShop) {
				LivingEntityShop oldLivingShop = (LivingEntityShop) oldShopkeeper.getShopObject();
				LivingEntity oldEntity = oldLivingShop.getEntity();
				Log.debug("Old, active shopkeeper was found (unloading probably has been skipped earlier): "
						+ (oldEntity == null ? "null" : (oldEntity.getUniqueId() + " | " + (oldEntity.isDead() ? "dead | " : "alive | ")
								+ (oldEntity.isValid() ? "valid" : "invalid"))));
			}
			boolean spawned = shopkeeper.spawn();
			if (spawned) {
				activeShopkeepers.put(shopkeeper.getObjectId(), shopkeeper);
			} else {
				Log.warning("Failed to spawn shopkeeper at " + shopkeeper.getPositionString());
			}
		}
	}

	private void deactivateShopkeeper(Shopkeeper shopkeeper, boolean closeWindows) {
		String shopId = shopkeeper.getObjectId();
		if (closeWindows) {
			// delayed closing of all open windows:
			shopkeeper.closeAllOpenWindows();
		}
		activeShopkeepers.remove(shopId);
		shopkeeper.despawn();
	}

	public void deleteShopkeeper(Shopkeeper shopkeeper) {
		assert shopkeeper != null;
		this.deactivateShopkeeper(shopkeeper, true);
		shopkeeper.onDeletion();

		// remove shopkeeper by id:
		shopkeepersById.remove(shopkeeper.getUniqueId());

		// remove shopkeeper from chunk:
		ChunkData chunkData = shopkeeper.getChunkData();
		this.removeShopkeeperFromChunk(shopkeeper, chunkData);
	}

	public void onShopkeeperMove(Shopkeeper shopkeeper, ChunkData oldChunk) {
		assert oldChunk != null;
		ChunkData newChunk = shopkeeper.getChunkData();
		if (!oldChunk.equals(newChunk)) {
			// remove from old chunk:
			this.removeShopkeeperFromChunk(shopkeeper, oldChunk);

			// add to new chunk:
			this.addShopkeeperToChunk(shopkeeper, newChunk);
		}
	}

	/**
	 * 
	 * @param chunk
	 * @return the number of shops in the affected chunk
	 */
	int loadShopkeepersInChunk(Chunk chunk) {
		assert chunk != null;
		int affectedShops = 0;
		List<Shopkeeper> shopkeepers = shopkeepersByChunk.get(new ChunkData(chunk));
		if (shopkeepers != null) {
			affectedShops = shopkeepers.size();
			Log.debug("Loading " + affectedShops + " shopkeepers in chunk " + chunk.getX() + "," + chunk.getZ());
			for (Shopkeeper shopkeeper : shopkeepers) {
				// inform shopkeeper about chunk load:
				shopkeeper.onChunkLoad();

				// activate:
				this.activateShopkeeper(shopkeeper);
			}

			// save:
			dirty = true;
			if (Settings.saveInstantly) {
				if (chunkLoadSaveTask == -1) {
					chunkLoadSaveTask = Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
						public void run() {
							if (dirty) {
								saveReal();
							}
							chunkLoadSaveTask = -1;
						}
					}, 600).getTaskId();
				}
			}
		}
		return affectedShops;
	}

	/**
	 * 
	 * @param chunk
	 * @return the number of shops in the affected chunk
	 */
	int unloadShopkeepersInChunk(Chunk chunk) {
		assert chunk != null;
		int affectedShops = 0;
		List<Shopkeeper> shopkeepers = this.getShopkeepersInChunk(new ChunkData(chunk));
		if (shopkeepers != null) {
			affectedShops = shopkeepers.size();
			Log.debug("Unloading " + affectedShops + " shopkeepers in chunk " + chunk.getX() + "," + chunk.getZ());
			for (Shopkeeper shopkeeper : shopkeepers) {
				// inform shopkeeper about chunk unload:
				shopkeeper.onChunkUnload();

				// skip shopkeepers which are kept active all the time (ex. sign, citizens shops):
				if (!shopkeeper.needsSpawning()) continue;

				// deactivate:
				this.deactivateShopkeeper(shopkeeper, false);
			}
		}
		return affectedShops;
	}

	void loadShopkeepersInWorld(World world) {
		assert world != null;
		int affectedShops = 0;
		for (Chunk chunk : world.getLoadedChunks()) {
			affectedShops += this.loadShopkeepersInChunk(chunk);
		}
		Log.debug("Loaded " + affectedShops + " shopkeepers in world " + world.getName());
	}

	void unloadShopkeepersInWorld(World world) {
		assert world != null;
		int affectedShops = 0;
		for (Chunk chunk : world.getLoadedChunks()) {
			affectedShops += this.unloadShopkeepersInChunk(chunk);
		}
		Log.debug("Unloaded " + affectedShops + " shopkeepers in world " + world.getName());

		/*String worldName = world.getName();
		Iterator<Shopkeeper> iter = activeShopkeepers.values().iterator();
		int count = 0;
		while (iter.hasNext()) {
			Shopkeeper shopkeeper = iter.next();
			if (shopkeeper.getWorldName().equals(worldName)) {
				shopkeeper.despawn();
				iter.remove();
				count++;
			}
		}
		Log.debug("Unloaded " + count + " shopkeepers in world " + worldName);*/
	}

	// SHOPKEEPER CREATION:

	@Override
	public boolean hasCreatePermission(Player player) {
		if (player == null) return false;
		return shopTypesManager.getSelection(player) != null && shopObjectTypesManager.getSelection(player) != null;
	}

	@Override
	public Shopkeeper createNewAdminShopkeeper(ShopCreationData creationData) {
		if (creationData == null || creationData.spawnLocation == null || creationData.objectType == null) return null;
		if (creationData.shopType == null) creationData.shopType = DefaultShopTypes.ADMIN;
		else if (creationData.shopType.isPlayerShopType()) return null; // we are expecting an admin shop type here..
		// create the shopkeeper (and spawn it)
		Shopkeeper shopkeeper = creationData.shopType.createShopkeeper(creationData);
		if (shopkeeper != null) {
			this.save();
			Utils.sendMessage(creationData.creator, creationData.shopType.getCreatedMessage());

			// run event
			Bukkit.getPluginManager().callEvent(new ShopkeeperCreatedEvent(creationData.creator, shopkeeper));
		} else {
			// TODO send informative message here?
		}
		return shopkeeper;
	}

	@Override
	public Shopkeeper createNewPlayerShopkeeper(ShopCreationData creationData) {
		if (creationData == null || creationData.shopType == null || creationData.objectType == null
				|| creationData.creator == null || creationData.chest == null || creationData.spawnLocation == null) {
			return null;
		}

		// check if this chest is already used by some other shopkeeper:
		if (this.isChestProtected(null, creationData.chest)) {
			Utils.sendMessage(creationData.creator, Settings.msgShopCreateFail);
			return null;
		}

		// check worldguard:
		if (Settings.enableWorldGuardRestrictions) {
			if (!WorldGuardHandler.canBuild(creationData.creator, creationData.spawnLocation)) {
				Utils.sendMessage(creationData.creator, Settings.msgShopCreateFail);
				return null;
			}
		}

		// check towny:
		if (Settings.enableTownyRestrictions) {
			if (!TownyHandler.isCommercialArea(creationData.spawnLocation)) {
				Utils.sendMessage(creationData.creator, Settings.msgShopCreateFail);
				return null;
			}
		}

		int maxShops = this.getMaxShops(creationData.creator);

		// call event:
		CreatePlayerShopkeeperEvent event = new CreatePlayerShopkeeperEvent(creationData, maxShops);
		Bukkit.getPluginManager().callEvent(event);
		if (event.isCancelled()) {
			return null;
		} else {
			creationData.spawnLocation = event.getSpawnLocation();
			creationData.shopType = event.getType();
			maxShops = event.getMaxShopsForPlayer();
		}

		// count owned shops:
		if (maxShops > 0) {
			int count = this.countShopsOfPlayer(creationData.creator);
			if (count >= maxShops) {
				Utils.sendMessage(creationData.creator, Settings.msgTooManyShops);
				return null;
			}
		}

		// create the shopkeeper:
		Shopkeeper shopkeeper = creationData.shopType.createShopkeeper(creationData);

		// spawn and save the shopkeeper:
		if (shopkeeper != null) {
			this.save();
			Utils.sendMessage(creationData.creator, creationData.shopType.getCreatedMessage());
			// run event
			Bukkit.getPluginManager().callEvent(new ShopkeeperCreatedEvent(creationData.creator, shopkeeper));
		} else {
			// TODO print some 'creation fail' message here?
		}

		return shopkeeper;
	}

	public int countShopsOfPlayer(Player player) {
		int count = 0;
		for (Shopkeeper shopkeeper : shopkeepersById.values()) {
			if (shopkeeper instanceof PlayerShopkeeper && ((PlayerShopkeeper) shopkeeper).isOwner(player)) {
				count++;
			}
		}
		return count;
	}

	public int getMaxShops(Player player) {
		int maxShops = Settings.maxShopsPerPlayer;
		String[] maxShopsPermOptions = Settings.maxShopsPermOptions.replace(" ", "").split(",");
		for (String perm : maxShopsPermOptions) {
			if (Utils.hasPermission(player, "shopkeeper.maxshops." + perm)) {
				maxShops = Integer.parseInt(perm);
			}
		}
		return maxShops;
	}

	// INACTIVE SHOPS

	private void removeInactivePlayerShops() {
		if (Settings.playerShopkeeperInactiveDays <= 0) return;

		final boolean supportsPlayerUUIDs = NMSManager.getProvider().supportsPlayerUUIDs();

		final Set<UUID> playerUUIDs = new HashSet<UUID>();
		final Set<String> unconvertedNames = new HashSet<String>();
		for (Shopkeeper shopkeeper : shopkeepersById.values()) {
			if (shopkeeper instanceof PlayerShopkeeper) {
				PlayerShopkeeper playerShop = (PlayerShopkeeper) shopkeeper;
				if (supportsPlayerUUIDs && playerShop.getOwnerUUID() != null) {
					playerUUIDs.add(playerShop.getOwnerUUID());
				} else {
					unconvertedNames.add(playerShop.getOwnerName());
				}
			}
		}

		if (playerUUIDs.isEmpty() && unconvertedNames.isEmpty()) return;

		final NMSCallProvider nmsProvider = NMSManager.getProvider();

		// fetch OfflinePlayers async:
		Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {

			@Override
			public void run() {
				final List<OfflinePlayer> inactivePlayers = new ArrayList<OfflinePlayer>(playerUUIDs.size() + unconvertedNames.size());
				long now = System.currentTimeMillis();
				if (supportsPlayerUUIDs) {
					for (UUID uuid : playerUUIDs) {
						OfflinePlayer offlinePlayer = nmsProvider.getOfflinePlayer(uuid);
						if (!offlinePlayer.hasPlayedBefore()) continue;

						long lastPlayed = offlinePlayer.getLastPlayed();
						if ((lastPlayed > 0) && ((now - lastPlayed) / 86400000 > Settings.playerShopkeeperInactiveDays)) {
							inactivePlayers.add(offlinePlayer);
						}
					}
				}

				for (String playerName : unconvertedNames) {
					OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
					if (!offlinePlayer.hasPlayedBefore()) continue;

					if (supportsPlayerUUIDs) {
						UUID playerUUID = nmsProvider.getUUID(offlinePlayer);
						if (playerUUIDs.contains(playerUUID)) {
							// we already have handled this player:
							// this can occur if there is a inconsistency in the shopkeeper data:
							// some player shopkeepers have the uuid set whereas other shopkeepers of the same player don't have it set
							// with this check we guarantee that inactivePlayers contains each player only once, without having to use a Set
							continue;
						}
					}

					long lastPlayed = offlinePlayer.getLastPlayed();
					if ((lastPlayed > 0) && ((now - lastPlayed) / 86400000 > Settings.playerShopkeeperInactiveDays)) {
						inactivePlayers.add(offlinePlayer);
					}
				}

				if (inactivePlayers.isEmpty()) return;

				// continue in main thread:
				Bukkit.getScheduler().runTask(ShopkeepersPlugin.this, new Runnable() {

					@Override
					public void run() {
						List<PlayerShopkeeper> forRemoval = new ArrayList<PlayerShopkeeper>();
						for (OfflinePlayer inactivePlayer : inactivePlayers) {
							// remove all shops of this inactive player:
							String playerName = inactivePlayer.getName();
							UUID playerUUID = supportsPlayerUUIDs ? nmsProvider.getUUID(inactivePlayer) : null;

							for (Shopkeeper shopkeeper : shopkeepersById.values()) {
								if (shopkeeper instanceof PlayerShopkeeper) {
									PlayerShopkeeper playerShop = (PlayerShopkeeper) shopkeeper;
									String ownerName = playerShop.getOwnerName();
									if (supportsPlayerUUIDs) {
										UUID ownerUUID = playerShop.getOwnerUUID();
										// ignore case, because owner names were initially stored in lower case in the past:
										if ((ownerUUID != null && ownerUUID.equals(playerUUID)) || (ownerUUID == null && ownerName.equalsIgnoreCase(playerName))) {
											forRemoval.add(playerShop);
										}
									} else {
										// ignore case, because owner names were initially stored in lower case in the past:
										if (ownerName.equalsIgnoreCase(playerName)) {
											forRemoval.add(playerShop);
										}
									}

								}
							}
						}

						for (PlayerShopkeeper shopkeeper : forRemoval) {
							shopkeeper.delete();
							ShopkeepersPlugin.this.getLogger().info("Shopkeeper owned by " + shopkeeper.getOwnerAsString() + " at "
									+ shopkeeper.getPositionString() + " has been removed for owner inactivity.");
						}

						ShopkeepersPlugin.this.save();
					}
				});
			}
		});
	}

	// UUID <-> PLAYERNAME HANDLING

	// TODO unused for now, as under certain circumstances the uuid's we get through this might be incorrect:
	// we currently convert them dynamically once the player joins
	private void convertAllPlayerNamesToUUIDs() {
		final boolean supportsPlayerUUIDs = NMSManager.getProvider().supportsPlayerUUIDs();
		if (!supportsPlayerUUIDs) return;

		final Set<String> unconvertedNames = new HashSet<String>();
		for (Shopkeeper shopkeeper : shopkeepersById.values()) {
			if (shopkeeper instanceof PlayerShopkeeper) {
				PlayerShopkeeper playerShop = (PlayerShopkeeper) shopkeeper;
				UUID ownerUUID = playerShop.getOwnerUUID();
				if (ownerUUID == null) {
					// player shop with yet unknown owner uuid:
					unconvertedNames.add(playerShop.getName());
				}
			}
		}

		if (unconvertedNames.isEmpty()) return;

		final NMSCallProvider nmsProvider = NMSManager.getProvider();

		Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {

			@Override
			public void run() {
				final List<Entry<String, UUID>> nameUUIDPairs = new ArrayList<Entry<String, UUID>>(unconvertedNames.size());
				for (String playerName : unconvertedNames) {
					OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
					if (!offlinePlayer.hasPlayedBefore()) continue; // we definitely got the wrong OfflinePlayer

					UUID playerUUID = nmsProvider.getUUID(offlinePlayer);
					assert playerUUID != null;

					nameUUIDPairs.add(new SimpleEntry<String, UUID>(playerName, playerUUID));
				}

				// continue sync:
				Bukkit.getScheduler().runTask(ShopkeepersPlugin.this, new Runnable() {

					@Override
					public void run() {
						for (Entry<String, UUID> entry : nameUUIDPairs) {
							updateShopkeepersForPlayer(entry.getValue(), entry.getKey());
						}
					}
				});
			}
		});
	}

	// checks for missing owner uuids and updates owner names for the shopkeepers of the specified player:
	void updateShopkeepersForPlayer(UUID playerUUID, String playerName) {
		if (!NMSManager.getProvider().supportsPlayerUUIDs()) return;

		boolean dirty = false;
		for (Shopkeeper shopkeeper : shopkeepersById.values()) {
			if (shopkeeper instanceof PlayerShopkeeper) {
				PlayerShopkeeper playerShop = (PlayerShopkeeper) shopkeeper;
				UUID ownerUUID = playerShop.getOwnerUUID();
				String ownerName = playerShop.getOwnerName();

				if (ownerUUID != null) {
					if (ownerUUID.equals(playerUUID)) {
						if (!ownerName.equals(playerName)) {
							// update the stored name, because the player must have changed it:
							playerShop.setOwner(playerUUID, playerName);
							dirty = true;
						} else {
							// The shop was already updated to uuid based identification and the player's name hasn't changed.
							// If we assume that this is consistent among all shops of this player
							// we can stop checking the other shops here:
							return;
						}
					}
				} else {
					// we have no uuid for the owner of this shop yet, let's identify the owner by name:
					// ignore case, because in early versions the owner name was initially stored in lower case..
					if (ownerName.equalsIgnoreCase(playerName)) {
						// let's store this player's uuid, and update the name to correct case:
						playerShop.setOwner(playerUUID, playerName);
						dirty = true;
					}
				}
			}
		}

		if (dirty) {
			this.save();
		}
	}

	// SHOPS LOADING AND SAVING

	private static class SaveInfo {
		long startTime;
		long packingDuration;
		long ioStartTime;
		long ioDuration;
		long fullDuration;

		public void printDebugInfo() {
			Log.debug("Saved shopkeeper data (" + fullDuration + "ms (Data packing: " + packingDuration + "ms, Async IO: " + ioDuration + "ms))");
		}
	}

	private File getSaveFile() {
		return new File(this.getDataFolder(), "save.yml");
	}

	private void load() {
		File file = this.getSaveFile();
		if (!file.exists()) return;

		YamlConfiguration config = new YamlConfiguration();
		Scanner scanner = null;
		FileInputStream stream = null;
		try {
			if (Settings.fileEncoding != null && !Settings.fileEncoding.isEmpty()) {
				stream = new FileInputStream(file);
				scanner = new Scanner(stream, Settings.fileEncoding);
				scanner.useDelimiter("\\A");
				if (!scanner.hasNext()) return; // file is completely empty -> no shopkeeper data is available
				String data = scanner.next();
				config.loadFromString(data);
			} else {
				config.load(file);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return;
		} finally {
			if (scanner != null) scanner.close();
			if (stream != null) {
				try {
					stream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		Set<String> keys = config.getKeys(false);
		for (String key : keys) {
			ConfigurationSection section = config.getConfigurationSection(key);
			ShopType<?> shopType = shopTypesManager.get(section.getString("type"));
			// unknown shop type
			if (shopType == null) {
				// got an owner entry? -> default to normal player shop type
				if (section.contains("owner")) {
					shopType = DefaultShopTypes.PLAYER_NORMAL;
				} else {
					Log.warning("Failed to load shopkeeper '" + key + "': unknown type");
					continue; // no valid shop type given..
				}
			}
			Shopkeeper shopkeeper = shopType.loadShopkeeper(section);
			if (shopkeeper == null) {
				Log.warning("Failed to load shopkeeper: " + key);
				continue;
			}
		}
	}

	@Override
	public void save() {
		if (Settings.saveInstantly) {
			this.saveReal();
		} else {
			dirty = true;
		}
	}

	@Override
	public void saveReal() {
		this.saveReal(true);
	}

	// should only get called sync on disable:
	private void saveReal(boolean async) {
		// is another async save task already running?
		if (async && saveIOTask != -1) {
			// set flag which triggers a new save once that current task is done:
			saveRealAgain = true;
			return;
		}

		// store shopkeeper data into memory configuration:
		saveInfo.startTime = System.currentTimeMillis();
		final YamlConfiguration config = new YamlConfiguration();
		int counter = 0;
		for (Shopkeeper shopkeeper : shopkeepersById.values()) {
			ConfigurationSection section = config.createSection(counter + "");
			shopkeeper.save(section);
			counter++;
		}
		saveInfo.packingDuration = System.currentTimeMillis() - saveInfo.startTime; // time to store shopkeeper data in memory configuration

		dirty = false;

		if (!async) {
			// sync file io:
			this.saveDataToFile(config, null);
			// print debug info:
			saveInfo.printDebugInfo();
		} else {
			// async file io:
			saveIOTask = Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {

				@Override
				public void run() {
					saveDataToFile(config, new Runnable() {

						@Override
						public void run() {
							// continue sync:
							Bukkit.getScheduler().runTask(ShopkeepersPlugin.this, new Runnable() {

								@Override
								public void run() {
									saveIOTask = -1;

									// print debug info:
									saveInfo.printDebugInfo();

									// did we get another request to saveReal() in the meantime?
									if (saveRealAgain) {
										// trigger another full save with latest data:
										saveRealAgain = false;
										saveReal();
									}
								}
							});
						}
					});
				}
			}).getTaskId();
		}
	}

	// total delay: 500ms
	private static final int SAVING_MAX_ATTEMPTS = 20;
	private static final long SAVING_ATTEMPTS_DELAY_MILLIS = 25;

	// can be run async and sync:
	private void saveDataToFile(FileConfiguration config, Runnable callback) {
		assert config != null;

		saveInfo.ioStartTime = System.currentTimeMillis();
		File file = this.getSaveFile();

		int savingAttempt = 0;
		String error;
		Exception exception;

		while (savingAttempt++ <= SAVING_MAX_ATTEMPTS) {
			boolean problem = false;
			error = null;
			exception = null;

			boolean saveFileExists = file.exists();

			if (saveFileExists) {
				if (!file.canWrite()) {
					error = "Cannot write to save file!";
					problem = true;
				} else {
					// remove old save file, so all old data gets removed:
					if (!file.delete()) {
						error = "Couldn't delete existing save file!";
						problem = true;
					}
				}
			}

			if (!problem) {
				// make sure that the parent directories exist:
				File parentDir = file.getParentFile();
				if (parentDir != null && !parentDir.exists()) {
					if (!parentDir.mkdirs()) {
						error = "Couldn't create parent directories for save file!";
						problem = true;
					}
				}
			}

			if (!problem) {
				// create new empty file, this usually fails if there is some problem in which case we wouldn't be able
				try {
					file.createNewFile();
				} catch (IOException e) {
					error = "Couldn't create save file!";
					exception = e;
					problem = true;
				}
			}

			if (!problem) {
				try {
					if (Settings.fileEncoding != null && !Settings.fileEncoding.isEmpty()) {
						PrintWriter writer = new PrintWriter(file, Settings.fileEncoding);
						writer.write(config.saveToString());
						writer.close();
					} else {
						config.save(file);
					}
				} catch (IOException e) {
					error = "Couldn't save data to save file!";
					exception = e;
					problem = true;
				}
			}

			if (problem) {
				// don't spam with errors and stacktraces, only print them on the first attempt:
				if (savingAttempt == 1) {
					if (exception != null) {
						exception.printStackTrace();
					}
				}
				Log.severe("Saving attempt " + savingAttempt + " failed: " + (error != null ? error : "Unknown error"));

				if (savingAttempt < SAVING_MAX_ATTEMPTS) {
					// try again after a small delay:
					try {
						Thread.sleep(SAVING_ATTEMPTS_DELAY_MILLIS);
					} catch (InterruptedException e) {
					}
				} else {
					// saving failed even after a bunch of retries:
					Log.severe("Saving failed! Save data might be lost! :(");
					break;
				}
			} else {
				// saving was successful:
				break;
			}
		}

		long now = System.currentTimeMillis();
		saveInfo.ioDuration = now - saveInfo.ioStartTime; // time for pure io
		saveInfo.fullDuration = now - saveInfo.startTime; // time from saveReal() call to finished save

		if (callback != null) {
			callback.run();
		}
	}

	private static class ConfirmEntry {

		private final Runnable action;
		private final int taskId;

		public ConfirmEntry(Runnable action, int taskId) {
			this.taskId = taskId;
			this.action = action;
		}

		public int getTaskId() {
			return taskId;
		}

		public Runnable getAction() {
			return action;
		}
	}
}