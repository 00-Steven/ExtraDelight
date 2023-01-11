package com.lance5057.extradelight.workstations;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import com.lance5057.extradelight.ExtraDelightBlockEntities;
import com.lance5057.extradelight.ExtraDelightItems;
import com.lance5057.extradelight.ExtraDelightRecipes;
import com.lance5057.extradelight.workstations.inventory.OvenItemHandler;
import com.lance5057.extradelight.workstations.recipes.OvenRecipe;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.RecipeHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import vectorwing.farmersdelight.common.block.entity.HeatableBlockEntity;
import vectorwing.farmersdelight.common.block.entity.SyncedBlockEntity;
import vectorwing.farmersdelight.common.mixin.accessor.RecipeManagerAccessor;
import vectorwing.farmersdelight.common.registry.ModParticleTypes;
import vectorwing.farmersdelight.common.utility.ItemUtils;
import vectorwing.farmersdelight.common.utility.TextUtils;

public class OvenBlockEntity extends SyncedBlockEntity
		implements MenuProvider, HeatableBlockEntity, Nameable, RecipeHolder {
	public static final int MEAL_DISPLAY_SLOT = 9;
	public static final int CONTAINER_SLOT = 10;
	public static final int OUTPUT_SLOT = 11;
	public static final int INVENTORY_SIZE = OUTPUT_SLOT + 1;

	private final ItemStackHandler inventory;
	private final LazyOptional<IItemHandler> inputHandler;
	private final LazyOptional<IItemHandler> outputHandler;

	private int cookTime;
	private int cookTimeTotal;
	private ItemStack mealContainerStack;
	private Component customName;

	protected final ContainerData OvenData;
	private final Object2IntOpenHashMap<ResourceLocation> usedRecipeTracker;

	private ResourceLocation lastRecipeID;
	private boolean checkNewRecipe;

	public OvenBlockEntity(BlockPos pos, BlockState state) {
		super(ExtraDelightBlockEntities.OVEN.get(), pos, state);
		this.inventory = createHandler();
		this.inputHandler = LazyOptional.of(() -> new OvenItemHandler(inventory, Direction.UP));
		this.outputHandler = LazyOptional.of(() -> new OvenItemHandler(inventory, Direction.DOWN));
		this.mealContainerStack = ItemStack.EMPTY;
		this.OvenData = createIntArray();
		this.usedRecipeTracker = new Object2IntOpenHashMap<>();
		this.checkNewRecipe = true;
	}

	public static ItemStack getMealFromItem(ItemStack OvenStack) {
		if (!OvenStack.is(ExtraDelightItems.OVEN.get())) {
			return ItemStack.EMPTY;
		}

		CompoundTag compound = OvenStack.getTagElement("BlockEntityTag");
		if (compound != null) {
			CompoundTag inventoryTag = compound.getCompound("Inventory");
			if (inventoryTag.contains("Items", 9)) {
				ItemStackHandler handler = new ItemStackHandler();
				handler.deserializeNBT(inventoryTag);
				return handler.getStackInSlot(6);
			}
		}

		return ItemStack.EMPTY;
	}

	public static void takeServingFromItem(ItemStack OvenStack) {
		if (!OvenStack.is(ExtraDelightItems.OVEN.get())) {
			return;
		}

		CompoundTag compound = OvenStack.getTagElement("BlockEntityTag");
		if (compound != null) {
			CompoundTag inventoryTag = compound.getCompound("Inventory");
			if (inventoryTag.contains("Items", 9)) {
				ItemStackHandler handler = new ItemStackHandler();
				handler.deserializeNBT(inventoryTag);
				ItemStack newMealStack = handler.getStackInSlot(6);
				newMealStack.shrink(1);
				compound.remove("Inventory");
				compound.put("Inventory", handler.serializeNBT());
			}
		}
	}

	public static ItemStack getContainerFromItem(ItemStack OvenStack) {
		if (!OvenStack.is(ExtraDelightItems.OVEN.get())) {
			return ItemStack.EMPTY;
		}

		CompoundTag compound = OvenStack.getTagElement("BlockEntityTag");
		if (compound != null) {
			return ItemStack.of(compound.getCompound("Container"));
		}

		return ItemStack.EMPTY;
	}

	@Override
	public void load(CompoundTag compound) {
		super.load(compound);
		inventory.deserializeNBT(compound.getCompound("Inventory"));
		cookTime = compound.getInt("CookTime");
		cookTimeTotal = compound.getInt("CookTimeTotal");
		mealContainerStack = ItemStack.of(compound.getCompound("Container"));
		if (compound.contains("CustomName", 8)) {
			customName = Component.Serializer.fromJson(compound.getString("CustomName"));
		}
		CompoundTag compoundRecipes = compound.getCompound("RecipesUsed");
		for (String key : compoundRecipes.getAllKeys()) {
			usedRecipeTracker.put(new ResourceLocation(key), compoundRecipes.getInt(key));
		}
	}

	@Override
	public void saveAdditional(CompoundTag compound) {
		super.saveAdditional(compound);
		compound.putInt("CookTime", cookTime);
		compound.putInt("CookTimeTotal", cookTimeTotal);
		compound.put("Container", mealContainerStack.serializeNBT());
		if (customName != null) {
			compound.putString("CustomName", Component.Serializer.toJson(customName));
		}
		compound.put("Inventory", inventory.serializeNBT());
		CompoundTag compoundRecipes = new CompoundTag();
		usedRecipeTracker
				.forEach((recipeId, craftedAmount) -> compoundRecipes.putInt(recipeId.toString(), craftedAmount));
		compound.put("RecipesUsed", compoundRecipes);
	}

	private CompoundTag writeItems(CompoundTag compound) {
		super.saveAdditional(compound);
		compound.put("Container", mealContainerStack.serializeNBT());
		compound.put("Inventory", inventory.serializeNBT());
		return compound;
	}

	public CompoundTag writeMeal(CompoundTag compound) {
		if (getMeal().isEmpty())
			return compound;

		ItemStackHandler drops = new ItemStackHandler(INVENTORY_SIZE);
		for (int i = 0; i < INVENTORY_SIZE; ++i) {
			drops.setStackInSlot(i, i == MEAL_DISPLAY_SLOT ? inventory.getStackInSlot(i) : ItemStack.EMPTY);
		}
		if (customName != null) {
			compound.putString("CustomName", Component.Serializer.toJson(customName));
		}
		compound.put("Container", mealContainerStack.serializeNBT());
		compound.put("Inventory", drops.serializeNBT());
		return compound;
	}

	public static void cookingTick(Level level, BlockPos pos, BlockState state, OvenBlockEntity Oven) {
		boolean isHeated = Oven.isHeated(level, pos);
		boolean didInventoryChange = false;

		if (isHeated && Oven.hasInput()) {
			Optional<OvenRecipe> recipe = Oven.getMatchingRecipe(new RecipeWrapper(Oven.inventory));
			if (recipe.isPresent() && Oven.canCook(recipe.get())) {
				didInventoryChange = Oven.processCooking(recipe.get(), Oven);
			} else {
				Oven.cookTime = 0;
			}
		} else if (Oven.cookTime > 0) {
			Oven.cookTime = Mth.clamp(Oven.cookTime - 2, 0, Oven.cookTimeTotal);
		}

		ItemStack mealStack = Oven.getMeal();
		if (!mealStack.isEmpty()) {
			if (!Oven.doesMealHaveContainer(mealStack)) {
				Oven.moveMealToOutput();
				didInventoryChange = true;
			} else if (!Oven.inventory.getStackInSlot(CONTAINER_SLOT).isEmpty()) {
				Oven.useStoredContainersOnMeal();
				didInventoryChange = true;
			}
		}

		if (didInventoryChange) {
			Oven.inventoryChanged();
		}
	}

	public static void animationTick(Level level, BlockPos pos, BlockState state, OvenBlockEntity Oven) {
		if (Oven.isHeated(level, pos)) {
			RandomSource random = level.random;
			if (random.nextFloat() < 0.2F) {
				double x = (double) pos.getX() + 0.5D + (random.nextDouble() * 0.6D - 0.3D);
				double y = (double) pos.getY() + 0.7D;
				double z = (double) pos.getZ() + 0.5D + (random.nextDouble() * 0.6D - 0.3D);
				level.addParticle(ParticleTypes.BUBBLE_POP, x, y, z, 0.0D, 0.0D, 0.0D);
			}
			if (random.nextFloat() < 0.05F) {
				double x = (double) pos.getX() + 0.5D + (random.nextDouble() * 0.4D - 0.2D);
				double y = (double) pos.getY() + 0.5D;
				double z = (double) pos.getZ() + 0.5D + (random.nextDouble() * 0.4D - 0.2D);
				double motionY = random.nextBoolean() ? 0.015D : 0.005D;
				level.addParticle(ModParticleTypes.STEAM.get(), x, y, z, 0.0D, motionY, 0.0D);
			}
		}

	}

	private Optional<OvenRecipe> getMatchingRecipe(RecipeWrapper inventoryWrapper) {
		if (level == null)
			return Optional.empty();

		if (lastRecipeID != null) {
			Recipe<RecipeWrapper> recipe = ((RecipeManagerAccessor) level.getRecipeManager())
					.getRecipeMap(ExtraDelightRecipes.OVEN.get()).get(lastRecipeID);
			if (recipe instanceof OvenRecipe) {
				if (recipe.matches(inventoryWrapper, level)) {
					return Optional.of((OvenRecipe) recipe);
				}
				if (recipe.getResultItem().sameItem(getMeal())) {
					return Optional.empty();
				}
			}
		}

		if (checkNewRecipe) {
			Optional<OvenRecipe> recipe = level.getRecipeManager().getRecipeFor(ExtraDelightRecipes.OVEN.get(),
					inventoryWrapper, level);
			if (recipe.isPresent()) {
				lastRecipeID = recipe.get().getId();
				return recipe;
			}
		}

		checkNewRecipe = false;
		return Optional.empty();
	}

	public ItemStack getContainer() {
		if (!mealContainerStack.isEmpty()) {
			return mealContainerStack;
		} else {
			return getMeal().getCraftingRemainingItem();
		}
	}

	private boolean hasInput() {
		for (int i = 0; i < MEAL_DISPLAY_SLOT; ++i) {
			if (!inventory.getStackInSlot(i).isEmpty())
				return true;
		}
		return false;
	}

	protected boolean canCook(OvenRecipe recipe) {
		if (hasInput()) {
			ItemStack resultStack = recipe.getResultItem();
			if (resultStack.isEmpty()) {
				return false;
			} else {
				ItemStack storedMealStack = inventory.getStackInSlot(MEAL_DISPLAY_SLOT);
				if (storedMealStack.isEmpty()) {
					return true;
				} else if (!storedMealStack.sameItem(resultStack)) {
					return false;
				} else if (storedMealStack.getCount() + resultStack.getCount() <= inventory
						.getSlotLimit(MEAL_DISPLAY_SLOT)) {
					return true;
				} else {
					return storedMealStack.getCount() + resultStack.getCount() <= resultStack.getMaxStackSize();
				}
			}
		} else {
			return false;
		}
	}

	private boolean processCooking(OvenRecipe recipe, OvenBlockEntity Oven) {
		if (level == null)
			return false;

		++cookTime;
		cookTimeTotal = recipe.getCookTime();
		if (cookTime < cookTimeTotal) {
			return false;
		}

		cookTime = 0;
		mealContainerStack = recipe.getOutputContainer();
		ItemStack resultStack = recipe.getResultItem();
		ItemStack storedMealStack = inventory.getStackInSlot(MEAL_DISPLAY_SLOT);
		if (storedMealStack.isEmpty()) {
			inventory.setStackInSlot(MEAL_DISPLAY_SLOT, resultStack.copy());
		} else if (storedMealStack.sameItem(resultStack)) {
			storedMealStack.grow(resultStack.getCount());
		}
		Oven.setRecipeUsed(recipe);

		for (int i = 0; i < MEAL_DISPLAY_SLOT; ++i) {
			ItemStack slotStack = inventory.getStackInSlot(i);
			if (slotStack.hasCraftingRemainingItem()) {
				Direction direction = getBlockState().getValue(OvenBlock.FACING).getCounterClockWise();
				double x = worldPosition.getX() + 0.5 + (direction.getStepX() * 0.25);
				double y = worldPosition.getY() + 0.7;
				double z = worldPosition.getZ() + 0.5 + (direction.getStepZ() * 0.25);
				ItemUtils.spawnItemEntity(level, inventory.getStackInSlot(i).getCraftingRemainingItem(), x, y, z,
						direction.getStepX() * 0.08F, 0.25F, direction.getStepZ() * 0.08F);
			}
			if (!slotStack.isEmpty())
				slotStack.shrink(1);
		}
		return true;
	}

	@Override
	public void setRecipeUsed(@Nullable Recipe<?> recipe) {
		if (recipe != null) {
			ResourceLocation recipeID = recipe.getId();
			usedRecipeTracker.addTo(recipeID, 1);
		}
	}

	@Nullable
	@Override
	public Recipe<?> getRecipeUsed() {
		return null;
	}

	@Override
	public void awardUsedRecipes(Player player) {
		List<Recipe<?>> usedRecipes = getUsedRecipesAndPopExperience(player.level, player.position());
		player.awardRecipes(usedRecipes);
		usedRecipeTracker.clear();
	}

	public List<Recipe<?>> getUsedRecipesAndPopExperience(Level level, Vec3 pos) {
		List<Recipe<?>> list = Lists.newArrayList();

		for (Object2IntMap.Entry<ResourceLocation> entry : usedRecipeTracker.object2IntEntrySet()) {
			level.getRecipeManager().byKey(entry.getKey()).ifPresent((recipe) -> {
				list.add(recipe);
				splitAndSpawnExperience((ServerLevel) level, pos, entry.getIntValue(),
						((OvenRecipe) recipe).getExperience());
			});
		}

		return list;
	}

	private static void splitAndSpawnExperience(ServerLevel level, Vec3 pos, int craftedAmount, float experience) {
		int expTotal = Mth.floor((float) craftedAmount * experience);
		float expFraction = Mth.frac((float) craftedAmount * experience);
		if (expFraction != 0.0F && Math.random() < (double) expFraction) {
			++expTotal;
		}

		ExperienceOrb.award(level, pos, expTotal);
	}

	public boolean isHeated() {
		if (level == null)
			return false;
		return this.isHeated(level, worldPosition);
	}

	public ItemStackHandler getInventory() {
		return inventory;
	}

	public ItemStack getMeal() {
		return inventory.getStackInSlot(MEAL_DISPLAY_SLOT);
	}

	public NonNullList<ItemStack> getDroppableInventory() {
		NonNullList<ItemStack> drops = NonNullList.create();
		for (int i = 0; i < INVENTORY_SIZE; ++i) {
			if (i != MEAL_DISPLAY_SLOT) {
				drops.add(inventory.getStackInSlot(i));
			}
		}
		return drops;
	}

	private void moveMealToOutput() {
		ItemStack mealStack = inventory.getStackInSlot(MEAL_DISPLAY_SLOT);
		ItemStack outputStack = inventory.getStackInSlot(OUTPUT_SLOT);
		int mealCount = Math.min(mealStack.getCount(), mealStack.getMaxStackSize() - outputStack.getCount());
		if (outputStack.isEmpty()) {
			inventory.setStackInSlot(OUTPUT_SLOT, mealStack.split(mealCount));
		} else if (outputStack.getItem() == mealStack.getItem()) {
			mealStack.shrink(mealCount);
			outputStack.grow(mealCount);
		}
	}

	private void useStoredContainersOnMeal() {
		ItemStack mealStack = inventory.getStackInSlot(MEAL_DISPLAY_SLOT);
		ItemStack containerInputStack = inventory.getStackInSlot(CONTAINER_SLOT);
		ItemStack outputStack = inventory.getStackInSlot(OUTPUT_SLOT);

		if (isContainerValid(containerInputStack) && outputStack.getCount() < outputStack.getMaxStackSize()) {
			int smallerStackCount = Math.min(mealStack.getCount(), containerInputStack.getCount());
			int mealCount = Math.min(smallerStackCount, mealStack.getMaxStackSize() - outputStack.getCount());
			if (outputStack.isEmpty()) {
				containerInputStack.shrink(mealCount);
				inventory.setStackInSlot(OUTPUT_SLOT, mealStack.split(mealCount));
			} else if (outputStack.getItem() == mealStack.getItem()) {
				mealStack.shrink(mealCount);
				containerInputStack.shrink(mealCount);
				outputStack.grow(mealCount);
			}
		}
	}

	public ItemStack useHeldItemOnMeal(ItemStack container) {
		if (isContainerValid(container) && !getMeal().isEmpty()) {
			container.shrink(1);
			return getMeal().split(1);
		}
		return ItemStack.EMPTY;
	}

	private boolean doesMealHaveContainer(ItemStack meal) {
		return !mealContainerStack.isEmpty() || meal.hasCraftingRemainingItem();
	}

	public boolean isContainerValid(ItemStack containerItem) {
		if (containerItem.isEmpty())
			return false;
		if (!mealContainerStack.isEmpty()) {
			return mealContainerStack.sameItem(containerItem);
		} else {
			return getMeal().getCraftingRemainingItem().sameItem(containerItem);
		}
	}

	@Override
	public Component getName() {
		return customName != null ? customName : TextUtils.getTranslation("container.oven");
	}

	@Override
	public Component getDisplayName() {
		return getName();
	}

	@Override
	@Nullable
	public Component getCustomName() {
		return customName;
	}

	public void setCustomName(Component name) {
		customName = name;
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory player, Player entity) {
		return new OvenMenu(id, player, this, OvenData);
	}

	@Override
	@Nonnull
	public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
		if (cap.equals(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY)) {
			if (side == null || side.equals(Direction.UP)) {
				return inputHandler.cast();
			} else {
				return outputHandler.cast();
			}
		}
		return super.getCapability(cap, side);
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		inputHandler.invalidate();
		outputHandler.invalidate();
	}

	@Override
	public CompoundTag getUpdateTag() {
		return writeItems(new CompoundTag());
	}

	private ItemStackHandler createHandler() {
		return new ItemStackHandler(INVENTORY_SIZE) {
			@Override
			protected void onContentsChanged(int slot) {
				if (slot >= 0 && slot < MEAL_DISPLAY_SLOT) {
					checkNewRecipe = true;
				}
				inventoryChanged();
			}
		};
	}

	private ContainerData createIntArray() {
		return new ContainerData() {
			@Override
			public int get(int index) {
				return switch (index) {
				case 0 -> OvenBlockEntity.this.cookTime;
				case 1 -> OvenBlockEntity.this.cookTimeTotal;
				default -> 0;
				};
			}

			@Override
			public void set(int index, int value) {
				switch (index) {
				case 0 -> OvenBlockEntity.this.cookTime = value;
				case 1 -> OvenBlockEntity.this.cookTimeTotal = value;
				}
			}

			@Override
			public int getCount() {
				return 2;
			}
		};
	}
}
