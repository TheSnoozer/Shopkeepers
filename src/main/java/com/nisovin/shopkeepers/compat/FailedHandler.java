package com.nisovin.shopkeepers.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Villager.Profession;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.nisovin.shopkeepers.Shopkeeper;
import com.nisovin.shopkeepers.ShopkeepersPlugin;
import com.nisovin.shopkeepers.compat.api.NMSCallProvider;

@SuppressWarnings("rawtypes")
public final class FailedHandler implements NMSCallProvider {

	Class classWorld;

	Class classEntityVillager;
	Constructor classEntityVillagerConstructor;
	Field recipeListField;
	Method openTradeMethod;

	Class classEntityInsentient;
	Method setCustomNameMethod;

	Class classMerchantRecipeList;
	// Method clearMethod;
	// Method addMethod;

	Class craftInventory;
	Method craftInventoryGetInventory;
	Class classNMSItemStack;
	Field tagField;

	Class classCraftItemStack;
	Method asNMSCopyMethod;
	Method asBukkitCopyMethod;

	Class classMerchantRecipe;
	Constructor merchantRecipeConstructor;
	Field maxUsesField;
	Method getBuyItem1Method;
	Method getBuyItem2Method;
	Method getBuyItem3Method;

	Class classInventoryMerchant;
	Method getRecipeMethod;

	Class classCraftPlayer;
	Method craftPlayerGetHandle;

	Class classEntity;
	Field worldField;

	/*
	 * Class classNbtBase;
	 * Class classNbtReadLimiter;
	 * Object unlimitedNbtReadLimiter;
	 * Class classNbtTagCompound;
	 * Method compoundWriteMethod;
	 * Method compoundLoadMethod;
	 * Method compoundHasKeyMethod;
	 * Method compoundGetCompoundMethod;
	 * Method compoundSetMethod;
	 */

	@SuppressWarnings("unchecked")
	public FailedHandler() throws Exception {
		String versionString = Bukkit.getServer().getClass().getName().replace("org.bukkit.craftbukkit.", "").replace("CraftServer", "");
		String nmsPackageString = "net.minecraft.server." + versionString;
		String obcPackageString = "org.bukkit.craftbukkit." + versionString;

		classWorld = Class.forName(nmsPackageString + "World");

		classEntityVillager = Class.forName(nmsPackageString + "EntityVillager");
		classEntityVillagerConstructor = classEntityVillager.getConstructor(classWorld);
		Field[] fields = classEntityVillager.getDeclaredFields();
		for (Field field : fields) {
			if (field.getType().getName().endsWith("MerchantRecipeList")) {
				recipeListField = field;
				break;
			}
		}
		recipeListField.setAccessible(true);
		Method[] methods = classEntityVillager.getDeclaredMethods();
		for (Method method : methods) {
			if (method.getReturnType() == boolean.class && method.getParameterTypes().length == 1 && method.getParameterTypes()[0].getName().endsWith("EntityHuman")) {
				openTradeMethod = method;
				break;
			}
		}
		openTradeMethod.setAccessible(true);

		classEntityInsentient = Class.forName(nmsPackageString + "EntityInsentient");
		setCustomNameMethod = classEntityInsentient.getDeclaredMethod("setCustomName", String.class);

		classNMSItemStack = Class.forName(nmsPackageString + "ItemStack");
		tagField = classNMSItemStack.getDeclaredField("tag");

		classCraftItemStack = Class.forName(obcPackageString + "inventory.CraftItemStack");
		asNMSCopyMethod = classCraftItemStack.getDeclaredMethod("asNMSCopy", ItemStack.class);
		asBukkitCopyMethod = classCraftItemStack.getDeclaredMethod("asBukkitCopy", classNMSItemStack);

		classMerchantRecipe = Class.forName(nmsPackageString + "MerchantRecipe");
		merchantRecipeConstructor = classMerchantRecipe.getConstructor(classNMSItemStack, classNMSItemStack, classNMSItemStack);
		maxUsesField = classMerchantRecipe.getDeclaredField("maxUses");
		maxUsesField.setAccessible(true);
		getBuyItem1Method = classMerchantRecipe.getDeclaredMethod("getBuyItem1");
		getBuyItem2Method = classMerchantRecipe.getDeclaredMethod("getBuyItem2");
		getBuyItem3Method = classMerchantRecipe.getDeclaredMethod("getBuyItem3");

		craftInventory = Class.forName(obcPackageString + "inventory.CraftInventory");
		craftInventoryGetInventory = craftInventory.getDeclaredMethod("getInventory");
		classInventoryMerchant = Class.forName(nmsPackageString + "InventoryMerchant");
		getRecipeMethod = classInventoryMerchant.getDeclaredMethod("getRecipe");

		classMerchantRecipeList = Class.forName(nmsPackageString + "MerchantRecipeList");
		// clearMethod = classMerchantRecipeList.getMethod("clear");
		// addMethod = classMerchantRecipeList.getMethod("add", Object.class);

		classCraftPlayer = Class.forName(obcPackageString + "entity.CraftPlayer");
		craftPlayerGetHandle = classCraftPlayer.getDeclaredMethod("getHandle");

		classEntity = Class.forName(nmsPackageString + "Entity");
		worldField = classEntity.getDeclaredField("world");

		/*
		 * classNbtBase = Class.forName(nmsPackageString + "NBTBase");
		 * classNbtReadLimiter = Class.forName(nmsPackageString + "NBTReadLimiter");
		 * // find the unlimited read limiter:
		 * for (Field field : classNbtReadLimiter.getDeclaredFields()) {
		 * if (classNbtReadLimiter.isAssignableFrom(field.getType())) {
		 * unlimitedNbtReadLimiter = field.get(null);
		 * }
		 * }
		 * classNbtTagCompound = Class.forName(nmsPackageString + "NBTTagCompound");
		 * compoundWriteMethod = classNbtTagCompound.getDeclaredMethod("write", DataOutput.class);
		 * compoundLoadMethod = classNbtTagCompound.getDeclaredMethod("load", DataInput.class, int.class, classNbtReadLimiter);
		 * compoundHasKeyMethod = classNbtTagCompound.getDeclaredMethod("hasKey", String.class);
		 * compoundGetCompoundMethod = classNbtTagCompound.getDeclaredMethod("getCompound", String.class);
		 * compoundSetMethod = classNbtTagCompound.getDeclaredMethod("set", String.class, classNbtBase);
		 */
	}

	@Override
	public String getVersionId() {
		return "FailedHandler";
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean openTradeWindow(String name, List<ItemStack[]> recipes, Player player) {
		ShopkeepersPlugin.getInstance().getLogger().warning(ChatColor.AQUA + "Shopkeepers needs an update.");
		try {

			Object villager = classEntityVillagerConstructor.newInstance(worldField.get(craftPlayerGetHandle.invoke(player)));
			if (name != null && !name.isEmpty()) {
				setCustomNameMethod.invoke(villager, name);
			}

			Object recipeList = recipeListField.get(villager);
			if (recipeList == null) {
				recipeList = classMerchantRecipeList.newInstance();
				recipeListField.set(villager, recipeList);
			}
			((ArrayList) recipeList).clear();
			for (ItemStack[] recipe : recipes) {
				Object r = createMerchantRecipe(recipe[0], recipe[1], recipe[2]);
				if (r != null) {
					((ArrayList) recipeList).add(r);
				}
			}

			openTradeMethod.invoke(villager, craftPlayerGetHandle.invoke(player));

			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	@Override
	public boolean openTradeWindow(Shopkeeper shopkeeper, Player player) {
		ShopkeepersPlugin.getInstance().getLogger().warning(ChatColor.AQUA + "Shopkeepers needs an update.");
		return openTradeWindow(shopkeeper.getName(), shopkeeper.getRecipes(), player);
	}

	@Override
	public ItemStack[] getUsedTradingRecipe(Inventory merchantInventory) {
		try {
			Object inventoryMerchant = craftInventoryGetInventory.invoke(merchantInventory);
			Object merchantRecipe = getRecipeMethod.invoke(inventoryMerchant);
			ItemStack[] recipe = new ItemStack[3];
			recipe[0] = asBukkitCopy(getBuyItem1Method.invoke(merchantRecipe));
			recipe[1] = asBukkitCopy(getBuyItem2Method.invoke(merchantRecipe));
			recipe[2] = asBukkitCopy(getBuyItem3Method.invoke(merchantRecipe));
			return recipe;
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public void overwriteLivingEntityAI(LivingEntity entity) {
		/*
		 * try {
		 * EntityLiving ev = ((CraftLivingEntity)entity).getHandle();
		 * Field goalsField = EntityInsentient.class.getDeclaredField("goalSelector");
		 * goalsField.setAccessible(true);
		 * PathfinderGoalSelector goals = (PathfinderGoalSelector) goalsField.get(ev);
		 * Field listField = PathfinderGoalSelector.class.getDeclaredField("a");
		 * listField.setAccessible(true);
		 * List list = (List)listField.get(goals);
		 * list.clear();
		 * listField = PathfinderGoalSelector.class.getDeclaredField("b");
		 * listField.setAccessible(true);
		 * list = (List)listField.get(goals);
		 * list.clear();
		 * goals.a(0, new PathfinderGoalFloat((EntityInsentient) ev));
		 * goals.a(1, new PathfinderGoalLookAtPlayer((EntityInsentient) ev, EntityHuman.class, 12.0F, 1.0F));
		 * } catch (Exception e) {
		 * e.printStackTrace();
		 * }
		 */
		entity.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Short.MAX_VALUE - 1, 15, true));
	}

	@Override
	public void overwriteVillagerAI(LivingEntity villager) {
		/*
		 * try {
		 * EntityVillager ev = ((CraftVillager)villager).getHandle();
		 * Field goalsField = EntityInsentient.class.getDeclaredField("goalSelector");
		 * goalsField.setAccessible(true);
		 * PathfinderGoalSelector goals = (PathfinderGoalSelector) goalsField.get(ev);
		 * Field listField = PathfinderGoalSelector.class.getDeclaredField("a");
		 * listField.setAccessible(true);
		 * List list = (List)listField.get(goals);
		 * list.clear();
		 * listField = PathfinderGoalSelector.class.getDeclaredField("b");
		 * listField.setAccessible(true);
		 * list = (List)listField.get(goals);
		 * list.clear();
		 * goals.a(0, new PathfinderGoalFloat(ev));
		 * goals.a(1, new PathfinderGoalTradeWithPlayer(ev));
		 * goals.a(1, new PathfinderGoalLookAtTradingPlayer(ev));
		 * goals.a(2, new PathfinderGoalLookAtPlayer(ev, EntityHuman.class, 12.0F, 1.0F));
		 * } catch (Exception e) {
		 * e.printStackTrace();
		 * }
		 */
		overwriteLivingEntityAI(villager);
	}

	@Override
	public int getMaxVillagerProfession() {
		return 5;
	}

	@Override
	public void setVillagerProfession(Villager villager, int profession) {
		try {
			@SuppressWarnings("deprecation")
			Profession prof = Profession.getProfession(profession);
			if (prof != null) {
				villager.setProfession(prof);
			} else {
				villager.setProfession(Profession.FARMER);
			}
		} catch (Exception e) {
		}
	}

	@Override
	public void setEntitySilent(Entity entity, boolean silent) {
	}

	@Override
	public void setNoAI(LivingEntity bukkitEntity) {
	}

	private Object createMerchantRecipe(ItemStack item1, ItemStack item2, ItemStack item3) {
		try {
			Object recipe = merchantRecipeConstructor.newInstance(convertItemStack(item1), convertItemStack(item2), convertItemStack(item3));
			maxUsesField.set(recipe, 10000);
			return recipe;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	private Object convertItemStack(org.bukkit.inventory.ItemStack item) {
		if (item == null) return null;
		try {
			return asNMSCopyMethod.invoke(null, item);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	private ItemStack asBukkitCopy(Object nmsItem) {
		if (nmsItem == null) return null;
		try {
			return (ItemStack) asBukkitCopyMethod.invoke(null, nmsItem);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public ItemStack loadItemAttributesFromString(ItemStack item, String data) {
		/*
		 * try {
		 * Object attributesCompound = classNbtTagCompound.newInstance();
		 * ByteArrayInputStream bytes = new ByteArrayInputStream(data.getBytes(Settings.fileEncoding));
		 * DataInput in = new DataInputStream(bytes);
		 * compoundLoadMethod.invoke(attributesCompound, in, 0);
		 * Object nmsItem = asNMSCopyMethod.invoke(null, item);
		 * Object tag = tagField.get(nmsItem);
		 * compoundSetMethod.invoke(tag, "AttributeModifiers", attributesCompound);
		 * return (ItemStack)asBukkitCopyMethod.invoke(null, nmsItem);
		 * } catch (Exception e) {
		 * e.printStackTrace();
		 * return item;
		 * }
		 */
		return null;
	}

	@Override
	public String saveItemAttributesToString(ItemStack item) {
		/*
		 * try {
		 * Object nmsItem = asNMSCopyMethod.invoke(null, item);
		 * Object tag = tagField.get(nmsItem);
		 * if ((Boolean)compoundHasKeyMethod.invoke(tag, "AttributeModifiers")) {
		 * Object attributesCompound = compoundGetCompoundMethod.invoke(tag, "AttributeModifiers");
		 * ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		 * DataOutput out = new DataOutputStream(bytes);
		 * compoundWriteMethod.invoke(attributesCompound, out);
		 * return bytes.toString(Settings.fileEncoding);
		 * } else {
		 * return null;
		 * }
		 * } catch (Exception e) {
		 * e.printStackTrace();
		 * return null;
		 * }
		 */
		return null;
	}

	@Override
	public boolean supportsPlayerUUIDs() {
		return false;
	}

	@Override
	public UUID getUUID(OfflinePlayer player) {
		return null;
	}

	@Override
	public OfflinePlayer getOfflinePlayer(UUID uuid) {
		return null;
	}
}