package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasks.container.StoreInContainerTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.LitematicaHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Stages resources for a Litematica schematic by gathering required materials
 * and storing them in chests near the build site.
 */
public class StageSchematicResourcesTask extends Task {
    
    private static final int FOOD_UNITS = 20;
    private static final int MIN_CHEST_COUNT = 1;
    private static final int SEARCH_RADIUS = 15;
    private static final int MAX_CHESTS_TO_PLACE = 10;
    // Optimistic estimate: chest has 27 slots, assuming full 64-item stacks
    // In practice, many items have smaller stack sizes or don't stack at all
    private static final int ITEMS_PER_CHEST_OPTIMISTIC = 27 * 64; // 1728
    
    private static final int MAX_CHEST_SEARCH_ATTEMPTS = 5;
    private static final int CHEST_SEARCH_LOG_INTERVAL = 10;
    private final String placementName;
    private LitematicaHelper.SchematicPlacementInfo placementInfo;
    private List<MaterialStaging> materialStaging;
    private List<BlockPos> stagingChests;
    private Task currentSubTask;
    private StagePhase currentPhase;
    private int currentMaterialIndex;
    private boolean preparationComplete;
    private int chestSearchAttempts = 0;
    
    private enum StagePhase {
        INIT,
        PREPARE_TOOLS,
        FIND_OR_PLACE_CHESTS,
        GATHER_MATERIALS,
        DEPOSIT_MATERIALS,
        COMPLETE
    }
    
    /**
     * Tracks staging progress for a single material type
     */
    private static class MaterialStaging {
        ItemStack itemStack;
        long totalRequired;
        long alreadyStaged;
        long currentlyInInventory;
        boolean isGathered;
        
        MaterialStaging(ItemStack stack, long required) {
            this.itemStack = stack.copy();
            this.totalRequired = required;
            this.alreadyStaged = 0;
            this.currentlyInInventory = 0;
            this.isGathered = false;
        }
        
        long getRemainingToGather() {
            return Math.max(0, totalRequired - alreadyStaged - currentlyInInventory);
        }
        
        boolean isComplete() {
            return alreadyStaged >= totalRequired;
        }
    }
    
    public StageSchematicResourcesTask() {
        this(null);
    }
    
    public StageSchematicResourcesTask(String placementName) {
        this.placementName = placementName;
        this.currentPhase = StagePhase.INIT;
        this.stagingChests = new ArrayList<>();
        this.materialStaging = new ArrayList<>();
        this.currentMaterialIndex = 0;
        this.preparationComplete = false;
    }
    
    @Override
    protected void onStart() {
        currentPhase = StagePhase.INIT;
        currentMaterialIndex = 0;
        preparationComplete = false;
        chestSearchAttempts = 0;
    }
    
    @Override
    protected Task onTick() {
        // Check if Litematica is available
        if (!LitematicaHelper.isLitematicaLoaded()) {
            Debug.logError("Litematica mod is not loaded! Cannot stage schematic resources.");
            return null;
        }
        
        switch (currentPhase) {
            case INIT:
                return handleInit();
            case PREPARE_TOOLS:
                return handlePrepareTools();
            case FIND_OR_PLACE_CHESTS:
                return handleChests();
            case GATHER_MATERIALS:
                return handleGatherMaterials();
            case DEPOSIT_MATERIALS:
                return handleDepositMaterials();
            case COMPLETE:
                return null;
        }
        
        return null;
    }
    
    private Task handleInit() {
        setDebugState("Initializing schematic staging...");
        
        // Get the placement info
        Optional<LitematicaHelper.SchematicPlacementInfo> infoOpt = LitematicaHelper.getSelectedPlacementInfo();
        if (infoOpt.isEmpty()) {
            Debug.logError("No schematic placement selected!");
            currentPhase = StagePhase.COMPLETE;
            return null;
        }
        
        placementInfo = infoOpt.get();
        Debug.logMessage("Staging schematic: " + placementInfo.getName());
        Debug.logMessage("Total unique items: " + placementInfo.getTotalUniqueItems());
        Debug.logMessage("Total item count: " + placementInfo.getTotalItemCount());
        
        // Process materials and apply filters
        materialStaging = processMaterialRequirements(placementInfo.getMaterials());
        
        if (materialStaging.isEmpty()) {
            Debug.logMessage("All materials already staged or no valid materials!");
            currentPhase = StagePhase.COMPLETE;
            return null;
        }
        
        Debug.logMessage("Processed " + materialStaging.size() + " material types to stage");
        currentPhase = StagePhase.PREPARE_TOOLS;
        return null;
    }
    
    private Task handlePrepareTools() {
        if (preparationComplete) {
            currentPhase = StagePhase.FIND_OR_PLACE_CHESTS;
            return null;
        }
        
        setDebugState("Preparing tools and equipment...");
        
        // 1. Get shield first for defense
        if (!AltoClef.getInstance().getItemStorage().hasItem(Items.SHIELD)) {
            setDebugState("Getting shield for defense...");
            return TaskCatalogue.getItemTask(Items.SHIELD, 1);
        }
        // Equip shield to offhand if not already there
        if (!StorageHelper.isArmorEquipped(Items.SHIELD)) {
            setDebugState("Equipping shield to offhand...");
            return new EquipArmorTask(Items.SHIELD);
        }
        
        // 2. Get necessary tools based on materials BEFORE food
        // This makes food gathering much more efficient
        Task toolTask = determineRequiredTools();
        if (toolTask != null) {
            return toolTask;
        }

        // 3. Ensure we carry a portable crafting table to avoid placing new ones everywhere
        if (!AltoClef.getInstance().getItemStorage().hasItem(Items.CRAFTING_TABLE)) {
            setDebugState("Getting portable crafting table...");
            return getCraftingTableTask();
        }
        
        // 4. Get food LAST (now we have tools to hunt efficiently)
        if (StorageHelper.calculateInventoryFoodScore() < FOOD_UNITS) {
            setDebugState("Getting food (with tools)...");
            return new CollectFoodTask(FOOD_UNITS);
        }
        
        preparationComplete = true;
        return null;
    }
    
    private Task determineRequiredTools() {
        AltoClef mod = AltoClef.getInstance();

        // Ensure we have a sword first for efficient food gathering and defense
        if (!mod.getItemStorage().hasItem(Items.WOODEN_SWORD) &&
            !mod.getItemStorage().hasItem(Items.STONE_SWORD) &&
            !mod.getItemStorage().hasItem(Items.IRON_SWORD) &&
            !mod.getItemStorage().hasItem(Items.DIAMOND_SWORD) &&
            !mod.getItemStorage().hasItem(Items.NETHERITE_SWORD)) {
            setDebugState("Getting sword for hunting...");
            return TaskCatalogue.getItemTask(Items.STONE_SWORD, 1);
        }

        // Analyze what we need to gather and get appropriate tools
        boolean needsStoneGathering = false;
        boolean needsWoodGathering = false;
        boolean needsOreGathering = false;
        boolean needsDirtGathering = false;
        boolean needsShearable = false;
        long stoneBlockCount = 0;
        
        for (MaterialStaging staging : materialStaging) {
            Item item = staging.itemStack.getItem();
            long count = staging.getRemainingToGather();
            
            // Check for stone-type materials
            if (isStoneBasedMaterial(item)) {
                needsStoneGathering = true;
                stoneBlockCount += count;
            }
            
            // Check for wood-type materials
            if (isWoodBasedMaterial(item)) {
                needsWoodGathering = true;
            }
            
            // Check for ore-type materials
            if (isOreBasedMaterial(item)) {
                needsOreGathering = true;
            }

            // Check for dirt/sand-type materials requiring a shovel
            if (isShovelMaterial(item)) {
                needsDirtGathering = true;
            }

            // Check for shearable materials
            if (isShearableMaterial(item)) {
                needsShearable = true;
            }
        }
        
        // If we need to gather a lot of stone (>500 blocks), get diamond pickaxe
        if (needsStoneGathering && stoneBlockCount > 500) {
            if (!mod.getItemStorage().hasItem(Items.DIAMOND_PICKAXE) &&
                !mod.getItemStorage().hasItem(Items.NETHERITE_PICKAXE)) {
                setDebugState("Getting diamond pickaxe for stone gathering...");
                return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
            }
        }
        // Otherwise iron pickaxe is fine
        else if (needsStoneGathering) {
            if (!mod.getItemStorage().hasItem(Items.DIAMOND_PICKAXE) &&
                !mod.getItemStorage().hasItem(Items.NETHERITE_PICKAXE) &&
                !mod.getItemStorage().hasItem(Items.IRON_PICKAXE)) {
                setDebugState("Getting iron pickaxe...");
                return TaskCatalogue.getItemTask(Items.IRON_PICKAXE, 1);
            }
        }
        
        // For wood gathering, get an iron axe
        if (needsWoodGathering) {
            if (!mod.getItemStorage().hasItem(Items.DIAMOND_AXE) &&
                !mod.getItemStorage().hasItem(Items.IRON_AXE)) {
                setDebugState("Getting iron axe for wood gathering...");
                return TaskCatalogue.getItemTask(Items.IRON_AXE, 1);
            }
        }
        
        // For ore gathering, ensure we have a good pickaxe
        if (needsOreGathering) {
            if (!mod.getItemStorage().hasItem(Items.DIAMOND_PICKAXE) &&
                !mod.getItemStorage().hasItem(Items.NETHERITE_PICKAXE)) {
                setDebugState("Getting diamond pickaxe for ore gathering...");
                return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
            }
        }

        // For dirt/sand gathering, get a shovel
        if (needsDirtGathering) {
            if (!mod.getItemStorage().hasItem(Items.WOODEN_SHOVEL) &&
                !mod.getItemStorage().hasItem(Items.STONE_SHOVEL) &&
                !mod.getItemStorage().hasItem(Items.IRON_SHOVEL) &&
                !mod.getItemStorage().hasItem(Items.DIAMOND_SHOVEL) &&
                !mod.getItemStorage().hasItem(Items.NETHERITE_SHOVEL)) {
                setDebugState("Getting shovel for dirt/sand gathering...");
                return TaskCatalogue.getItemTask(Items.STONE_SHOVEL, 1);
            }
        }

        // For shearable materials, get shears
        if (needsShearable) {
            if (!mod.getItemStorage().hasItem(Items.SHEARS)) {
                setDebugState("Getting shears...");
                return TaskCatalogue.getItemTask(Items.SHEARS, 1);
            }
        }
        
        return null;
    }

    private Task getCraftingTableTask() {
        AltoClef mod = AltoClef.getInstance();

        // Look for a nearby crafting table to pick up instead of crafting a new one
        Optional<BlockPos> nearbyTable = mod.getBlockScanner().getNearestBlock(
                mod.getPlayer().getPos(), WorldHelper::canBreak, Blocks.CRAFTING_TABLE);
        if (nearbyTable.isPresent() &&
                nearbyTable.get().isWithinDistance(mod.getPlayer().getPos(), 50)) {
            return new DestroyBlockTask(nearbyTable.get());
        }

        // Otherwise craft a new one
        return TaskCatalogue.getItemTask(Items.CRAFTING_TABLE, 1);
    }
    
    private boolean isStoneBasedMaterial(Item item) {
        return item == Items.STONE || item == Items.COBBLESTONE || 
               item == Items.STONE_BRICKS || item == Items.SMOOTH_STONE ||
               item == Items.ANDESITE || item == Items.DIORITE || item == Items.GRANITE
               //#if MC >= 11700
               || item == Items.DEEPSLATE || item == Items.COBBLED_DEEPSLATE
               //#endif
               ;
    }
    
    private boolean isWoodBasedMaterial(Item item) {
        String name = item.toString().toLowerCase();
        return name.contains("planks") || name.contains("log") || name.contains("wood");
    }
    
    private boolean isOreBasedMaterial(Item item) {
        return item == Items.IRON_INGOT || item == Items.GOLD_INGOT || 
               item == Items.DIAMOND || item == Items.EMERALD ||
               item == Items.COAL || item == Items.REDSTONE ||
               item == Items.LAPIS_LAZULI
               //#if MC >= 11700
               || item == Items.COPPER_INGOT
               //#endif
               ;
    }

    private boolean isShovelMaterial(Item item) {
        return item == Items.DIRT ||
               item == Items.GRASS_BLOCK ||
               item == Items.SAND ||
               item == Items.RED_SAND ||
               item == Items.GRAVEL ||
               item == Items.CLAY ||
               item == Items.SOUL_SAND ||
               item == Items.SOUL_SOIL ||
               item == Items.PODZOL ||
               item == Items.MYCELIUM ||
               item == Items.COARSE_DIRT;
    }

    private boolean isShearableMaterial(Item item) {
        // Explicit checks for well-known shearable item types
        if (item == Items.COBWEB) return true;
        if (item == Items.WHITE_WOOL || item == Items.ORANGE_WOOL || item == Items.MAGENTA_WOOL ||
            item == Items.LIGHT_BLUE_WOOL || item == Items.YELLOW_WOOL || item == Items.LIME_WOOL ||
            item == Items.PINK_WOOL || item == Items.GRAY_WOOL || item == Items.LIGHT_GRAY_WOOL ||
            item == Items.CYAN_WOOL || item == Items.PURPLE_WOOL || item == Items.BLUE_WOOL ||
            item == Items.BROWN_WOOL || item == Items.GREEN_WOOL || item == Items.RED_WOOL ||
            item == Items.BLACK_WOOL) return true;
        // Use string matching for leaves to handle all versions (e.g. MANGROVE_LEAVES added in 1.19)
        String name = item.toString().toLowerCase();
        if (name.endsWith("_leaves")) return true;
        return false;
    }
    
    private Task handleChests() {
        setDebugState("Finding or placing staging chests...");
        
        // Find nearby chests around the schematic origin
        BlockPos searchCenter = placementInfo.getOrigin();
        stagingChests = findNearbyChests(searchCenter, SEARCH_RADIUS);
        
        // Only log every 10 attempts to avoid spam
        if (chestSearchAttempts % CHEST_SEARCH_LOG_INTERVAL == 0) {
            Debug.logMessage("Found " + stagingChests.size() + " existing chests near build site (attempt " + chestSearchAttempts + ")");
        }
        chestSearchAttempts++;
        
        // If we've tried too many times, proceed without chests
        if (chestSearchAttempts > MAX_CHEST_SEARCH_ATTEMPTS && stagingChests.isEmpty()) {
            Debug.logWarning("Could not find or place chests after " + chestSearchAttempts + " attempts. Proceeding anyway.");
            chestSearchAttempts = 0;
            currentPhase = StagePhase.GATHER_MATERIALS;
            return null;
        }
        
        // If we don't have enough chests, place some
        if (stagingChests.size() < MIN_CHEST_COUNT) {
            int chestsToPlace = Math.min(MAX_CHESTS_TO_PLACE - stagingChests.size(), 
                                        estimateChestsNeeded() - stagingChests.size());
            
            if (chestsToPlace > 0) {
                setDebugState("Need to place " + chestsToPlace + " more chest(s)...");
                
                // Ensure we have chests
                int chestsInInventory = AltoClef.getInstance().getItemStorage()
                    .getItemCount(Items.CHEST);
                if (chestsInInventory < chestsToPlace) {
                    return TaskCatalogue.getItemTask(Items.CHEST, chestsToPlace);
                }
                
                // Place a chest near the origin
                BlockPos placePos = findChestPlacementLocation(searchCenter);
                if (placePos != null) {
                    return new PlaceBlockTask(placePos, new Block[]{Blocks.CHEST}, false, false);
                } else {
                    Debug.logWarning("Could not find suitable location to place chest!");
                }
            }
        }
        
        chestSearchAttempts = 0;
        currentPhase = StagePhase.GATHER_MATERIALS;
        return null;
    }
    
    private Task handleGatherMaterials() {
        // Update what's in our inventory
        updateInventoryCounts();
        
        // Find next material to gather
        while (currentMaterialIndex < materialStaging.size()) {
            MaterialStaging staging = materialStaging.get(currentMaterialIndex);
            
            long remainingToGather = staging.getRemainingToGather();
            
            if (remainingToGather > 0 && !staging.isGathered) {
                setDebugState("Gathering " + staging.itemStack.getName().getString() + 
                            " (" + remainingToGather + " more needed)");
                
                // Use TaskCatalogue to get the item
                Item item = staging.itemStack.getItem();
                staging.isGathered = true; // Mark as being worked on
                return TaskCatalogue.getItemTask(item, (int) Math.min(remainingToGather, Integer.MAX_VALUE));
            }
            
            if (staging.currentlyInInventory > 0) {
                // We have some of this item, deposit it
                currentPhase = StagePhase.DEPOSIT_MATERIALS;
                return null;
            }
            
            currentMaterialIndex++;
        }
        
        // Check if we have anything to deposit
        updateInventoryCounts();
        boolean hasItemsToDeposit = materialStaging.stream()
            .anyMatch(s -> s.currentlyInInventory > 0);
        
        if (hasItemsToDeposit) {
            currentPhase = StagePhase.DEPOSIT_MATERIALS;
            return null;
        }
        
        // All done!
        currentPhase = StagePhase.COMPLETE;
        return null;
    }
    
    private Task handleDepositMaterials() {
        updateInventoryCounts();
        
        // Find items we need to deposit
        List<MaterialStaging> toDeposit = materialStaging.stream()
            .filter(s -> s.currentlyInInventory > 0)
            .collect(Collectors.toList());
        
        if (toDeposit.isEmpty()) {
            // Reset gathered flags and continue gathering
            materialStaging.forEach(s -> s.isGathered = false);
            currentPhase = StagePhase.GATHER_MATERIALS;
            return null;
        }
        
        // Find a chest to deposit into
        if (stagingChests.isEmpty()) {
            Debug.logWarning("No staging chests available!");
            currentPhase = StagePhase.FIND_OR_PLACE_CHESTS;
            return null;
        }
        
        BlockPos targetChest = findBestChestForDeposit();
        if (targetChest == null) {
            Debug.logWarning("Could not find suitable chest!");
            currentPhase = StagePhase.FIND_OR_PLACE_CHESTS;
            return null;
        }
        
        setDebugState("Depositing items into chest...");
        
        // Create item targets for what we want to deposit
        ItemTarget[] targets = toDeposit.stream()
            .map(s -> new ItemTarget(s.itemStack.getItem(), (int) s.currentlyInInventory))
            .toArray(ItemTarget[]::new);
        
        // Update staged counts after successful deposit
        // (This will be tracked by the StoreInContainerTask)
        toDeposit.forEach(s -> {
            s.alreadyStaged += s.currentlyInInventory;
            s.currentlyInInventory = 0;
        });
        
        // Reset gathered flags
        materialStaging.forEach(s -> s.isGathered = false);
        currentPhase = StagePhase.GATHER_MATERIALS;
        
        return new StoreInContainerTask(targetChest, false, targets);
    }
    
    /**
     * Process material requirements, applying filters and transformations
     */
    private List<MaterialStaging> processMaterialRequirements(List<LitematicaHelper.MaterialRequirement> materials) {
        List<MaterialStaging> staging = new ArrayList<>();
        
        for (LitematicaHelper.MaterialRequirement req : materials) {
            ItemStack stack = req.getItemStack();
            Item item = stack.getItem();
            
            // Filter out water and lava buckets - in MC 1.21+, Items.WATER and Items.LAVA don't exist
            if (item == Items.WATER_BUCKET || item == Items.LAVA_BUCKET) {
                Debug.logMessage("Skipping liquid: " + item);
                continue;
            }
            
            // Convert grass block to dirt (as specified)
            if (item == Items.GRASS_BLOCK) {
                Debug.logMessage("Converting grass block to dirt");
                // Create a representative stack (count doesn't matter here, totalRequired is tracked separately)
                stack = new ItemStack(Items.DIRT, 1);
                item = Items.DIRT;
            }
            
            // Convert special dirt types to normal dirt
            if (item == Items.PODZOL || item == Items.MYCELIUM || 
                item == Items.COARSE_DIRT) {
                Debug.logMessage("Converting special dirt to normal dirt");
                // Create a representative stack (count doesn't matter here, totalRequired is tracked separately)
                stack = new ItemStack(Items.DIRT, 1);
                item = Items.DIRT;
            }
            
            // Create final variable for use in lambda
            final Item finalItem = item;
            
            // Check if we already have this material type in staging
            Optional<MaterialStaging> existing = staging.stream()
                .filter(s -> s.itemStack.getItem() == finalItem)
                .findFirst();
            
            if (existing.isPresent()) {
                // Combine counts
                existing.get().totalRequired += req.getTotalCount();
            } else {
                // Add new staging entry
                staging.add(new MaterialStaging(stack, req.getTotalCount()));
            }
        }
        
        return staging;
    }
    
    private void updateInventoryCounts() {
        AltoClef mod = AltoClef.getInstance();
        for (MaterialStaging staging : materialStaging) {
            int count = mod.getItemStorage().getItemCount(staging.itemStack.getItem());
            staging.currentlyInInventory = count;
        }
    }
    
    private List<BlockPos> findNearbyChests(BlockPos center, int radius) {
        List<BlockPos> chests = new ArrayList<>();
        AltoClef mod = AltoClef.getInstance();
        
        for (BlockPos pos : WorldHelper.scanRegion(center.add(-radius, -radius, -radius),
                                                   center.add(radius, radius, radius))) {
            Block block = mod.getWorld().getBlockState(pos).getBlock();
            if (block.equals(Blocks.CHEST) || block.equals(Blocks.TRAPPED_CHEST)) {
                chests.add(pos);
            }
        }
        
        return chests;
    }
    
    private int estimateChestsNeeded() {
        long totalItems = materialStaging.stream()
            .mapToLong(s -> s.totalRequired)
            .sum();
        
        int chestsNeeded = (int) Math.ceil((double) totalItems / ITEMS_PER_CHEST_OPTIMISTIC);
        return Math.max(MIN_CHEST_COUNT, Math.min(chestsNeeded, MAX_CHESTS_TO_PLACE));
    }
    
    private BlockPos findChestPlacementLocation(BlockPos near) {
        // Try to find a suitable location near the schematic origin
        AltoClef mod = AltoClef.getInstance();
        
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos pos = near.add(dx, dy, dz);
                    // Check if this is a valid placement location
                    if (WorldHelper.canPlace(pos) && 
                        WorldHelper.isSolidBlock(pos.down())) {
                        return pos;
                    }
                }
            }
        }
        
        return null;
    }
    
    private BlockPos findBestChestForDeposit() {
        if (stagingChests.isEmpty()) {
            return null;
        }
        
        AltoClef mod = AltoClef.getInstance();
        BlockPos playerPos = mod.getPlayer().getBlockPos();
        
        // Find the closest chest to the player that we haven't filled yet
        // For now, we'll use a simple approach: try each chest in order of proximity
        BlockPos bestChest = null;
        double bestDistance = Double.MAX_VALUE;
        
        for (BlockPos chest : stagingChests) {
            double distance = Math.sqrt(playerPos.getSquaredDistance(chest));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestChest = chest;
            }
        }
        
        // TODO: Check if chest is full before selecting it
        // For now, if deposit fails, the task will handle it
        return bestChest;
    }
    
    @Override
    protected void onStop(Task interruptTask) {
        // Cleanup
    }
    
    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof StageSchematicResourcesTask) {
            StageSchematicResourcesTask task = (StageSchematicResourcesTask) other;
            if (placementName == null && task.placementName == null) {
                return true;
            }
            if (placementName != null && task.placementName != null) {
                return placementName.equals(task.placementName);
            }
        }
        return false;
    }
    
    @Override
    protected String toDebugString() {
        if (placementInfo != null) {
            long completed = materialStaging.stream().filter(MaterialStaging::isComplete).count();
            return "Staging schematic: " + placementInfo.getName() + 
                   " (Phase: " + currentPhase + ", Progress: " + completed + "/" + materialStaging.size() + ")";
        }
        return "Staging schematic resources (Phase: " + currentPhase + ")";
    }
    
    @Override
    public boolean isFinished() {
        if (currentPhase == StagePhase.COMPLETE) {
            return true;
        }
        
        // Check if all materials are staged
        if (!materialStaging.isEmpty()) {
            return materialStaging.stream().allMatch(MaterialStaging::isComplete);
        }
        
        return false;
    }
}
