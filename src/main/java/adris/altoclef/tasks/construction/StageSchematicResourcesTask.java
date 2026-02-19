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
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Stages resources for a Litematica schematic by gathering required materials
 * and storing them in chests near the build site.
 */
public class StageSchematicResourcesTask extends Task {
    
    private static final int FOOD_UNITS = 20;
    private static final int MIN_CHEST_COUNT = 1;
    private static final int SEARCH_RADIUS = 15;
    private static final int MAX_CHESTS_TO_PLACE = 10;
    private static final int CHEST_SLOTS = 27;
    private static final int BUFFER_CHESTS = 2;
    private static final int MAX_PLACEMENT_ATTEMPTS = 50;
    private static final double MIN_SCHEMATIC_CLEARANCE = 3.0;
    private static final int MIN_CHEST_DISTANCE = 2;
    private static final int MAX_CHEST_DISTANCE = 10;
    private static final int MAX_DEPOSIT_ATTEMPTS = 3;
    private static final double DEPOSIT_COOLDOWN_SECONDS = 2.0;
    private static final double CHEST_PLACEMENT_COOLDOWN_SECONDS = 3.0;
    private static final double MIN_CHEST_SPACING = 1.5;
    private static final int PLACEMENT_WANDER_THRESHOLD = 10;
    private final String placementName;
    private LitematicaHelper.SchematicPlacementInfo placementInfo;
    private List<MaterialStaging> materialStaging;
    private List<BlockPos> stagingChests;
    private Task currentSubTask;
    private StagePhase currentPhase;
    private int currentMaterialIndex;
    private boolean preparationComplete;
    private int chestSearchAttempts = 0;
    private BlockPos currentDepositChest = null;
    private int depositAttempts = 0;
    private final TimerGame depositCooldown = new TimerGame(DEPOSIT_COOLDOWN_SECONDS);
    private final Set<BlockPos> attemptedChestPlacements = new HashSet<>();
    private final TimerGame chestPlacementCooldown = new TimerGame(CHEST_PLACEMENT_COOLDOWN_SECONDS);
    
    private enum StagePhase {
        INIT,
        PREPARE_TOOLS,
        FIND_OR_PLACE_CHESTS,
        VALIDATE_CHESTS,
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
        long currentlyInChests;
        long pendingDepositAmount;
        boolean isGathered;
        
        MaterialStaging(ItemStack stack, long required) {
            this.itemStack = stack.copy();
            this.totalRequired = required;
            this.alreadyStaged = 0;
            this.currentlyInInventory = 0;
            this.currentlyInChests = 0;
            this.pendingDepositAmount = 0;
            this.isGathered = false;
        }
        
        long getRemainingToGather() {
            // Use the best available count: alreadyStaged (tracked) or currentlyInChests (live scan)
            long inChests = Math.max(alreadyStaged, currentlyInChests);
            return Math.max(0, totalRequired - inChests - currentlyInInventory);
        }
        
        boolean isComplete() {
            // Use max of tracked deposits vs live chest scan for robustness
            return Math.max(alreadyStaged, currentlyInChests) >= totalRequired;
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
        currentDepositChest = null;
        depositAttempts = 0;
        attemptedChestPlacements.clear();
    }
    
    @Override
    protected Task onTick() {
        // Check if Litematica is available
        if (!LitematicaHelper.isLitematicaLoaded()) {
            Debug.logError("Litematica mod is not loaded! Cannot stage schematic resources.");
            return null;
        }

        // Check completion at the start of every tick (skip INIT, COMPLETE, and early phases)
        if (currentPhase == StagePhase.GATHER_MATERIALS || currentPhase == StagePhase.DEPOSIT_MATERIALS) {
            if (isTaskComplete()) {
                Debug.logMessage("========================================");
                Debug.logMessage("TASK COMPLETE: All materials gathered!");
                Debug.logMessage("========================================");
                currentPhase = StagePhase.COMPLETE;
                return null;
            }
        }
        
        switch (currentPhase) {
            case INIT:
                return handleInit();
            case PREPARE_TOOLS:
                return handlePrepareTools();
            case FIND_OR_PLACE_CHESTS:
                return handleChests();
            case VALIDATE_CHESTS:
                return handleValidateChests();
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
        setDebugState("Setting up storage chests...");

        BlockPos searchCenter = placementInfo.getOrigin();
        int chestsNeeded = calculateRequiredChests();

        // CRITICAL: Refresh chest list to include newly placed chests
        stagingChests = findNearbyChests(searchCenter, SEARCH_RADIUS);

        if (chestSearchAttempts == 0) {
            Debug.logMessage("Found " + stagingChests.size() + " existing chests, need " + chestsNeeded + " total");
        }

        chestSearchAttempts++;

        // Check if we have enough chests
        if (stagingChests.size() >= chestsNeeded) {
            Debug.logMessage("✓ All " + chestsNeeded + " chests ready");
            chestSearchAttempts = 0;
            attemptedChestPlacements.clear();
            currentPhase = StagePhase.VALIDATE_CHESTS;
            return null;
        }

        // Safety check
        if (chestSearchAttempts > MAX_PLACEMENT_ATTEMPTS) {
            Debug.logError("Failed to place chests after " + MAX_PLACEMENT_ATTEMPTS + " attempts, aborting");
            currentPhase = StagePhase.COMPLETE;
            return null;
        }

        // Calculate how many more chests we need
        int chestsToPlace = chestsNeeded - stagingChests.size();
        AltoClef mod = AltoClef.getInstance();
        int chestsInInventory = mod.getItemStorage().getItemCount(Items.CHEST);

        // Ensure we have chests in inventory
        if (chestsInInventory < chestsToPlace) {
            setDebugState("Need " + chestsToPlace + " more chests, crafting...");
            return TaskCatalogue.getItemTask(Items.CHEST, chestsToPlace);
        }

        // Wait for cooldown before placing next chest
        if (!chestPlacementCooldown.elapsed()) {
            return null;
        }

        // Find a new placement location
        BlockPos placePos = findChestPlacementLocation(searchCenter);

        if (placePos == null) {
            Debug.logWarning("Could not find suitable location for chest placement (attempt " + chestSearchAttempts + ")");
            if (chestSearchAttempts > PLACEMENT_WANDER_THRESHOLD) {
                return new GetToBlockTask(searchCenter.add(5, 0, 0));
            }
            return new TimeoutWanderTask(2);
        }

        // Mark this position as attempted and reset cooldown
        attemptedChestPlacements.add(placePos);
        chestPlacementCooldown.reset();

        setDebugState("Placing chest " + (stagingChests.size() + 1) + "/" + chestsNeeded + " at " + placePos.toShortString());
        Debug.logMessage("Placing chest at " + placePos.toShortString());

        return new PlaceBlockTask(placePos, new Block[]{Blocks.CHEST}, false, false);
    }

    private Task handleValidateChests() {
        int chestsNeeded = calculateRequiredChests();

        BlockPos searchCenter = placementInfo.getOrigin();
        stagingChests = findNearbyChests(searchCenter, SEARCH_RADIUS);

        if (stagingChests.size() < chestsNeeded) {
            Debug.logWarning("Expected " + chestsNeeded + " chests but found " + stagingChests.size() + "; going back to placement");
            currentPhase = StagePhase.FIND_OR_PLACE_CHESTS;
            chestSearchAttempts = 0;
            return null;
        }

        Debug.logMessage("Validated " + stagingChests.size() + " chests ready for storage");
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

        // Only mark as complete if ALL materials are truly gathered
        if (isTaskComplete()) {
            currentPhase = StagePhase.COMPLETE;
            return null;
        }

        // Not all materials gathered — reset and try again
        Debug.logMessage("Not all materials gathered, resetting gather cycle");
        currentMaterialIndex = 0;
        materialStaging.forEach(s -> s.isGathered = false);
        return null;
    }
    
    private Task handleDepositMaterials() {
        AltoClef mod = AltoClef.getInstance();

        // Read actual inventory state from the game
        updateInventoryCounts();

        Debug.logMessage("=== DEPOSIT PHASE ===");
        Debug.logMessage("Available chests: " + stagingChests.size());

        // Find items that are actually in inventory (validate before depositing)
        List<MaterialStaging> toDeposit = new ArrayList<>();
        for (MaterialStaging staging : materialStaging) {
            int actualCount = mod.getItemStorage().getItemCount(staging.itemStack.getItem());
            if (actualCount > 0) {
                staging.currentlyInInventory = actualCount;
                toDeposit.add(staging);
                Debug.logMessage("  " + staging.itemStack.getItem() + ": " + actualCount + " in inventory");
            }
        }

        if (toDeposit.isEmpty()) {
            // Deposit completed — confirm alreadyStaged for any pending items
            for (MaterialStaging staging : materialStaging) {
                if (staging.pendingDepositAmount > 0) {
                    staging.alreadyStaged += staging.pendingDepositAmount;
                    staging.pendingDepositAmount = 0;
                }
            }
            Debug.logMessage("Deposit completed successfully");
            materialStaging.forEach(s -> s.isGathered = false);
            currentPhase = StagePhase.GATHER_MATERIALS;
            depositAttempts = 0;
            currentDepositChest = null;
            return null;
        }

        // Check if we have valid chests
        if (stagingChests.isEmpty()) {
            Debug.logError("No staging chests available for deposit! Returning to chest placement phase.");
            currentPhase = StagePhase.FIND_OR_PLACE_CHESTS;
            chestSearchAttempts = 0;
            return null;
        }

        BlockPos targetChest = findBestChestForDeposit();
        if (targetChest == null) {
            Debug.logWarning("Could not find suitable chest!");
            currentPhase = StagePhase.FIND_OR_PLACE_CHESTS;
            return null;
        }

        // On chest change, reset attempt counter and enforce a brief cooldown
        if (!targetChest.equals(currentDepositChest)) {
            currentDepositChest = targetChest;
            depositAttempts = 0;
            depositCooldown.reset();
        }

        // If we've exceeded max attempts on this chest, move to the next one
        if (depositAttempts > MAX_DEPOSIT_ATTEMPTS) {
            // Only act after cooldown to avoid rapid chest cycling
            if (depositCooldown.elapsed()) {
                Debug.logWarning("Deposit failed after " + MAX_DEPOSIT_ATTEMPTS + " attempts, trying different chest");
                stagingChests.remove(currentDepositChest);
                currentDepositChest = null;
                depositAttempts = 0;
                if (stagingChests.isEmpty()) {
                    Debug.logError("No more chests available!");
                    currentPhase = StagePhase.FIND_OR_PLACE_CHESTS;
                }
            }
            return null;
        }

        setDebugState("Depositing " + toDeposit.size() + " item type(s) into chest at " + targetChest.toShortString() + "...");

        // Build deposit targets from validated inventory counts
        ItemTarget[] targets = toDeposit.stream()
            .map(s -> new ItemTarget(s.itemStack.getItem(), (int) s.currentlyInInventory))
            .toArray(ItemTarget[]::new);

        // Record pending deposit amounts; alreadyStaged is updated only after confirmation
        toDeposit.forEach(s -> s.pendingDepositAmount = s.currentlyInInventory);

        // Keep currentPhase as DEPOSIT_MATERIALS — phase transitions when inventory is empty
        // The task system's isEqual check ensures we don't restart the deposit task each tick
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

        // Scan staging chests for item counts
        if (!stagingChests.isEmpty()) {
            for (MaterialStaging staging : materialStaging) {
                staging.currentlyInChests = 0;
            }
            for (BlockPos chestPos : stagingChests) {
                Block block = mod.getWorld().getBlockState(chestPos).getBlock();
                if (!block.equals(Blocks.CHEST) && !block.equals(Blocks.TRAPPED_CHEST)) {
                    continue;
                }
                BlockEntity be = mod.getWorld().getBlockEntity(chestPos);
                if (be instanceof Inventory inv) {
                    for (int slot = 0; slot < inv.size(); slot++) {
                        ItemStack stack = inv.getStack(slot);
                        if (stack.isEmpty()) continue;
                        Item stackItem = stack.getItem();
                        for (MaterialStaging staging : materialStaging) {
                            if (staging.itemStack.getItem() == stackItem) {
                                staging.currentlyInChests += stack.getCount();
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isTaskComplete() {
        updateInventoryCounts();
        for (MaterialStaging staging : materialStaging) {
            if (!staging.isComplete()) {
                return false;
            }
        }
        return true;
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
    
    private int calculateRequiredChests() {
        long totalSlots = 0;

        for (MaterialStaging staging : materialStaging) {
            Item item = staging.itemStack.getItem();
            int maxStackSize = item.getMaxCount();
            long slotsNeeded = (long) Math.ceil((double) staging.totalRequired / maxStackSize);
            totalSlots += slotsNeeded;
        }

        // Each chest has 27 slots; add buffer chests for overflow
        int chestsNeeded = (int) Math.ceil((double) totalSlots / CHEST_SLOTS) + BUFFER_CHESTS;
        chestsNeeded = Math.max(MIN_CHEST_COUNT, Math.min(chestsNeeded, MAX_CHESTS_TO_PLACE));

        Debug.logMessage("Calculated " + chestsNeeded + " chests needed for " + totalSlots + " inventory slots");
        return chestsNeeded;
    }
    
    private boolean isLiquid(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.WATER || block == Blocks.LAVA || block == Blocks.BUBBLE_COLUMN;
    }

    private BlockPos findChestPlacementLocation(BlockPos near) {
        AltoClef mod = AltoClef.getInstance();

        // Try positions at increasing distances in all 4 cardinal directions
        for (int distance = MIN_CHEST_DISTANCE; distance <= MAX_CHEST_DISTANCE; distance++) {
            BlockPos[] candidates = {
                near.add(distance, 0, 0),
                near.add(-distance, 0, 0),
                near.add(0, 0, distance),
                near.add(0, 0, -distance),
            };

            for (BlockPos candidate : candidates) {
                // Check a vertical range around each candidate
                for (int dy = 3; dy >= -3; dy--) {
                    BlockPos pos = candidate.add(0, dy, 0);
                    BlockPos below = pos.down();

                    // Skip positions we've already attempted
                    if (attemptedChestPlacements.contains(pos)) {
                        continue;
                    }

                    // Skip positions too close to existing chests
                    boolean tooClose = false;
                    for (BlockPos existing : stagingChests) {
                        if (existing.isWithinDistance(pos, MIN_CHEST_SPACING)) {
                            tooClose = true;
                            break;
                        }
                    }
                    if (tooClose) continue;

                    if (WorldHelper.canPlace(pos) &&
                        WorldHelper.isSolidBlock(below) &&
                        !isLiquid(mod.getWorld().getBlockState(below)) &&
                        isOutsideSchematicBounds(pos)) {
                        return pos;
                    }
                }
            }
        }

        // Fallback: try directly above the origin
        for (int dy = 1; dy <= 5; dy++) {
            BlockPos pos = near.add(0, dy, 0);
            if (!attemptedChestPlacements.contains(pos) &&
                WorldHelper.canPlace(pos) && WorldHelper.isSolidBlock(pos.down())) {
                return pos;
            }
        }

        return null;
    }

    private boolean isOutsideSchematicBounds(BlockPos pos) {
        BlockPos origin = placementInfo.getOrigin();
        double distance = Math.sqrt(pos.getSquaredDistance(origin));
        return distance >= MIN_SCHEMATIC_CLEARANCE;
    }
    
    private BlockPos findBestChestForDeposit() {
        if (stagingChests.isEmpty()) {
            return null;
        }

        AltoClef mod = AltoClef.getInstance();
        BlockPos playerPos = mod.getPlayer().getBlockPos();

        BlockPos bestChest = null;
        double bestDistance = Double.MAX_VALUE;

        Iterator<BlockPos> it = stagingChests.iterator();
        while (it.hasNext()) {
            BlockPos chest = it.next();
            // Skip chests that no longer exist in the world
            Block block = mod.getWorld().getBlockState(chest).getBlock();
            if (!block.equals(Blocks.CHEST) && !block.equals(Blocks.TRAPPED_CHEST)) {
                Debug.logWarning("Chest at " + chest.toShortString() + " no longer exists, removing from list");
                it.remove();
                continue;
            }
            double distance = playerPos.getSquaredDistance(chest);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestChest = chest;
            }
        }

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
