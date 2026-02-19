package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.multiversion.item.ItemVer;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInSmokerTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.slots.SmokerSlot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.passive.*;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.SmokerScreenHandler;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public class CollectFoodTask extends Task {
    public static final CookableFoodTarget[] COOKABLE_FOODS = new CookableFoodTarget[] {new CookableFoodTarget("beef", CowEntity.class), new CookableFoodTarget("porkchop", PigEntity.class), new CookableFoodTarget("chicken", ChickenEntity.class), new CookableFoodTarget("mutton", SheepEntity.class), new CookableFoodTarget("rabbit", RabbitEntity.class)};

    public static final Item[] ITEMS_TO_PICK_UP = new Item[] { Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE, Items.GOLDEN_CARROT, Items.BREAD, Items.BAKED_POTATO };

    public static final CropTarget[] CROPS = new CropTarget[] { new CropTarget(Items.WHEAT, Blocks.WHEAT), new CropTarget(Items.CARROT, Blocks.CARROTS) };

    private static final double ANIMAL_SEARCH_RANGE = 80.0;

    private final double unitsNeeded;
    private final TimerGame checkNewOptionsTimer = new TimerGame(10);
    private final SmeltInSmokerTask smeltTask = null;
    private Task currentResourceTask = null;

    public CollectFoodTask(double unitsNeeded) {
        this.unitsNeeded = unitsNeeded;
    }

    private static double getFoodPotential(ItemStack food) {
        if (food == null) return 0;
        int count = food.getCount();
        if (count <= 0) return 0;
        assert ItemVer.getFoodComponent(Items.BREAD) != null;

        if (food.getItem().equals(Items.HAY_BLOCK)) {
            return 3 * ItemVer.getFoodComponent(Items.BREAD).getHunger() * count;
        }

        if (food.getItem().equals(Items.WHEAT)) {
            return (double) (ItemVer.getFoodComponent(Items.BREAD).getHunger() * count) / 3;
        }

        if (ItemVer.isFood(food.getItem())) {
            assert ItemVer.getFoodComponent(food.getItem()) != null;
            return count * ItemVer.getFoodComponent(food.getItem()).getHunger();
        }

        return 0;
    }

    @SuppressWarnings("RedundantCast")
    public static double calculateFoodPotential(AltoClef mod) {
        double potentialFood = 0;
        for (ItemStack food : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            potentialFood += getFoodPotential(food);
        }

        int potentialBread = (int) (mod.getItemStorage().getItemCount(Items.WHEAT) / 3) + mod.getItemStorage().getItemCount(Items.HAY_BLOCK) * 3;
        potentialFood += Objects.requireNonNull(ItemVer.getFoodComponent(Items.BREAD)).getHunger() * potentialBread;

        ScreenHandler screen = mod.getPlayer().currentScreenHandler;
        if (screen instanceof SmokerScreenHandler) {
            potentialFood += getFoodPotential(StorageHelper.getItemStackInSlot(SmokerSlot.INPUT_SLOT_MATERIALS));
            potentialFood += getFoodPotential(StorageHelper.getItemStackInSlot(SmokerSlot.OUTPUT_SLOT));
        }

        return potentialFood;
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(ITEMS_TO_PICK_UP);
        mod.getBehaviour().addProtectedItems(Items.HAY_BLOCK, Items.SWEET_BERRIES);
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        blackListChickenJockeys(mod);
        List<BlockPos> haysPos = mod.getBlockScanner().getKnownLocations(Blocks.HAY_BLOCK);

        for (BlockPos HaysPos : haysPos) {
            BlockPos haysUpPos = HaysPos.up();
            if (mod.getWorld().getBlockState(haysUpPos).getBlock() == Blocks.CARVED_PUMPKIN) {
                Debug.logMessage("Blacklisting pillage hay bales.");
                mod.getBlockScanner().requestBlockUnreachable(HaysPos, 0);
            }
        }

        if (smeltTask != null && smeltTask.isActive() && !smeltTask.isFinished()) {
            setDebugState("Cooking...");
            return smeltTask;
        }

        if (checkNewOptionsTimer.elapsed()) {
            checkNewOptionsTimer.reset();
            currentResourceTask = null;
        }

        if (currentResourceTask != null && currentResourceTask.isActive() && !currentResourceTask.isFinished() && !currentResourceTask.thisOrChildAreTimedOut()) {
            return currentResourceTask;
        }

        double potentialFood = calculateFoodPotential(mod);
        if (potentialFood >= unitsNeeded) {
            if (mod.getItemStorage().getItemCount(Items.WHEAT) >= 3) {
                setDebugState("Crafting Bread");
                Item[] w = new Item[]{Items.WHEAT};
                Item[] o = null;
                currentResourceTask = new CraftInTableTask(new RecipeTarget(Items.BREAD, 99999999, CraftingRecipe.newShapedRecipe("bread", new Item[][]{w, w, w, o, o, o, o, o, o}, 1)), false, false);
                return currentResourceTask;
            }

            if (mod.getItemStorage().getItemCount(Items.HAY_BLOCK) >= 1) {
                setDebugState("Crafting Wheat");
                Item[] o = null;
                currentResourceTask = new CraftInInventoryTask(new RecipeTarget(Items.WHEAT, 99999999, CraftingRecipe.newShapedRecipe("wheat", new Item[][]{new Item[]{Items.HAY_BLOCK}, o, o, o}, 9)), false, false);
                return currentResourceTask;
            }
        } else {
            for (Item item : ITEMS_TO_PICK_UP) {
                Task t = this.pickupTaskOrNull(mod, item);
                if (t != null) {
                    setDebugState("Picking up Food: " + item.getTranslationKey());
                    currentResourceTask = t;
                    return currentResourceTask;
                }
            }

            for (CookableFoodTarget cookable : COOKABLE_FOODS) {
                Task t = this.pickupTaskOrNull(mod, cookable.getRaw(), 20);
                if (t == null) t = this.pickupTaskOrNull(mod, cookable.getCooked(), 40);
                if (t != null) {
                    setDebugState("Picking up Cookable food");
                    currentResourceTask = t;
                    return currentResourceTask;
                }
            }

            Task hayTaskBlock = this.pickupBlockTaskOrNull(mod, Blocks.HAY_BLOCK, Items.HAY_BLOCK, 300);
            if (hayTaskBlock != null) {
                setDebugState("Collecting Hay");
                currentResourceTask = hayTaskBlock;
                return currentResourceTask;
            }

            for (CropTarget target : CROPS) {
                Task t = pickupBlockTaskOrNull(mod, target.cropBlock, target.cropItem, (blockPos -> {
                    BlockState s = mod.getWorld().getBlockState(blockPos);
                    Block b = s.getBlock();
                    if (b instanceof CropBlock) {
                        boolean isWheat = !(b instanceof PotatoesBlock || b instanceof CarrotsBlock || b instanceof BeetrootsBlock);
                        if (isWheat) {
                            if (!mod.getChunkTracker().isChunkLoaded(blockPos)) {
                                return false;
                            }
                            CropBlock crop = (CropBlock) b;
                            return crop.isMature(s);
                        }
                    }
                    return WorldHelper.canBreak(blockPos);
                }), 96);
                if (t != null) {
                    setDebugState("Harvesting " + target.cropItem.getTranslationKey());
                    currentResourceTask = t;
                    return currentResourceTask;
                }
            }

            double bestScore = 0;
            Entity bestEntity = null;
            Item bestRawFood = null;
            Predicate<Entity> notBaby = entity -> entity instanceof LivingEntity livingEntity && !livingEntity.isBaby();

            for (CookableFoodTarget cookable : COOKABLE_FOODS) {
                if (!mod.getEntityTracker().entityFound(cookable.mobToKill)) continue;
                Optional<Entity> nearest = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(), notBaby ,cookable.mobToKill);
                if (nearest.isEmpty()) continue;

                int hungerPerformance = cookable.getCookedUnits();
                double sqDistance = nearest.get().squaredDistanceTo(mod.getPlayer());
                double score = (double) 100 * hungerPerformance / (sqDistance);
                if (cookable.isFish()) {
                    score = 0;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestEntity = nearest.get();
                    bestRawFood = cookable.getRaw();
                }
            }

            if (bestEntity == null) {
                for (Entity entity : mod.getWorld().getEntities()) {
                    if (!entity.isAlive()) continue;
                    if (!notBaby.test(entity)) continue;

                    CookableFoodTarget matched = null;
                    for (CookableFoodTarget cookable : COOKABLE_FOODS) {
                        if (!cookable.isFish() && cookable.mobToKill.isInstance(entity)) {
                            matched = cookable;
                            break;
                        }
                    }

                    if (matched == null) continue;
                    double sqDistance = entity.squaredDistanceTo(mod.getPlayer());
                    if (sqDistance > ANIMAL_SEARCH_RANGE * ANIMAL_SEARCH_RANGE) continue;
                    int hungerPerformance = matched.getCookedUnits();
                    double score = (double) 100 * hungerPerformance / (sqDistance + 1);
                    if (score > bestScore) {
                        bestScore = score;
                        bestEntity = entity;
                        bestRawFood = matched.getRaw();
                    }
                }
            }

            if (bestEntity != null) {
                setDebugState("Killing " + bestEntity.getType().getTranslationKey());
                mod.getEntityTracker().resetEntityBlacklist(bestEntity);
                currentResourceTask = killTaskOrNull(bestEntity, notBaby, bestRawFood);
                return currentResourceTask;
            }

            Task berryPickup = pickupBlockTaskOrNull(mod, Blocks.SWEET_BERRY_BUSH, Items.SWEET_BERRIES, 96);
            if (berryPickup != null) {
                setDebugState("Getting sweet berries (no better foods are present)");
                currentResourceTask = berryPickup;
                return currentResourceTask;
            }
        }

        setDebugState("Searching...");
        return new TimeoutWanderTask();
    }

    static void blackListChickenJockeys(AltoClef mod) {
        if (mod.getEntityTracker().entityFound(ChickenEntity.class)) {
            Optional<Entity> chickens = mod.getEntityTracker().getClosestEntity(ChickenEntity.class);
            if (chickens.isPresent()) {
                Iterable<Entity> entities = mod.getWorld().getEntities();
                for (Entity entity : entities) {
                    if (entity instanceof HostileEntity || entity instanceof SlimeEntity) {
                        if (chickens.get().hasPassenger(entity)) {
                            if (mod.getEntityTracker().isEntityReachable(entity)) {
                                Debug.logMessage("Blacklisting chicken jockey.");
                                mod.getEntityTracker().requestEntityUnreachable(chickens.get());
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef.getInstance().getBehaviour().pop();
    }

    @Override
    public boolean isFinished() {
        return StorageHelper.calculateInventoryFoodScore() >= unitsNeeded;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CollectFoodTask task) {
            return task.unitsNeeded == unitsNeeded;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Collect " + unitsNeeded + " units of food.";
    }

    private Task pickupBlockTaskOrNull(AltoClef mod, Block blockToCheck, Item itemToGrab, Predicate<BlockPos> accept, double maxRange) {
        Predicate<BlockPos> acceptPlus = (blockPos) -> {
            if (!WorldHelper.canBreak(blockPos)) return false;
            return accept.test(blockPos);
        };

        Optional<BlockPos> nearestBlock = mod.getBlockScanner().getNearestBlock(mod.getPlayer().getPos(), acceptPlus, blockToCheck);
        if (nearestBlock.isPresent() && !nearestBlock.get().isWithinDistance(mod.getPlayer().getPos(), maxRange)) {
            nearestBlock = Optional.empty();
        }

        Optional<ItemEntity> nearestDrop = Optional.empty();
        if (mod.getEntityTracker().itemDropped(itemToGrab)) {
            nearestDrop = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().getPos(), itemToGrab);
        }

        if (nearestDrop.isPresent()) {
            return pickupTaskOrNull(mod, itemToGrab);
        }

        if (nearestBlock.isPresent()) {
            return new DoToClosestBlockTask(DestroyBlockTask::new, acceptPlus, blockToCheck);
        }
        return null;
    }

    private Task pickupBlockTaskOrNull(AltoClef mod, Block blockToCheck, Item itemToGrab, double maxRange) {
        return pickupBlockTaskOrNull(mod, blockToCheck, itemToGrab, toAccept -> true, maxRange);
    }

    private Task killTaskOrNull(Entity entity, Predicate<Entity> entityPredicate, Item itemToGrab) {
        return new KillAndLootTask(entity.getClass(), entityPredicate, new ItemTarget(itemToGrab, 1));
    }

    private Task pickupTaskOrNull(AltoClef mod, Item itemToGrab, double maxRange) {
        Optional<ItemEntity> nearestDrop = Optional.empty();
        if (mod.getEntityTracker().itemDropped(itemToGrab)) {
            nearestDrop = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().getPos(), itemToGrab);
        }

        if (nearestDrop.isPresent()) {
            if (nearestDrop.get().isInRange(mod.getPlayer(), maxRange)) {
                if (mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(nearestDrop.get().getStack(), false).isEmpty()) {
                    Optional<Slot> slot = StorageHelper.getGarbageSlot(mod);
                    if (slot.isPresent()) {
                        ItemStack stack = StorageHelper.getItemStackInSlot(slot.get());
                        if (ItemVer.isFood(stack.getItem())) {
                            int inventoryCost = ItemVer.getFoodComponent(stack.getItem()).getHunger() * stack.getCount();
                            double hunger = 0;
                            if (ItemVer.isFood(itemToGrab)) {
                                hunger = ItemVer.getFoodComponent(itemToGrab).getHunger();
                            } else if (itemToGrab.equals(Items.WHEAT)) {
                                hunger += ItemVer.getFoodComponent(Items.BREAD).getHunger() / 3d;
                            } else {
                                mod.log("unknown food item: " + itemToGrab);
                            }
                            int groundCost = (int) (hunger * nearestDrop.get().getStack().getCount());
                            if (inventoryCost > groundCost) return null;
                        }
                    }
                }
                return new PickupDroppedItemTask(new ItemTarget(itemToGrab), true);
            }
        }
        return null;
    }

    private Task pickupTaskOrNull(AltoClef mod, Item itemToGrab) {
        return pickupTaskOrNull(mod, itemToGrab, Double.POSITIVE_INFINITY);
    }

    @SuppressWarnings({"rawtypes"})
    public static class CookableFoodTarget {
        public String rawFood;
        public String cookedFood;
        public Class mobToKill;

        public CookableFoodTarget(String rawFood, String cookedFood, Class mobToKill) {
            this.rawFood = rawFood;
            this.cookedFood = cookedFood;
            this.mobToKill = mobToKill;
        }

        public CookableFoodTarget(String rawFood, Class mobToKill) {
            this(rawFood, "cooked_" + rawFood, mobToKill);
        }

        public Item getRaw() {
            return Objects.requireNonNull(TaskCatalogue.getItemMatches(rawFood))[0];
        }

        public Item getCooked() {
            return Objects.requireNonNull(TaskCatalogue.getItemMatches(cookedFood))[0];
        }

        public int getCookedUnits() {
            assert ItemVer.getFoodComponent(getCooked()) != null;
            return ItemVer.getFoodComponent(getCooked()).getHunger();
        }

        public boolean isFish() {
            return false;
        }
    }

    @SuppressWarnings({"rawtypes"})
    private static class CookableFoodTargetFish extends CookableFoodTarget {
        public CookableFoodTargetFish(String rawFood, Class mobToKill) {
            super(rawFood, mobToKill);
        }

        @Override
        public boolean isFish() {
            return true;
        }
    }

    public static class CropTarget {
        public Item cropItem;
        public Block cropBlock;

        public CropTarget(Item cropItem, Block cropBlock) {
            this.cropItem = cropItem;
            this.cropBlock = cropBlock;
        }
    }
}