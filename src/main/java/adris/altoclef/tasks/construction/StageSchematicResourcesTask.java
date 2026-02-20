package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.container.StoreInContainerTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.misc.SleepThroughNightTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.LitematicaHelper;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.inventory.Inventory;
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
    private static final int CHEST_SLOTS = 27;
    private static final int BUFFER_CHESTS = 2;
    private static final int MAX_PLACEMENT_ATTEMPTS = 50;
    private static final double MIN_SCHEMATIC_CLEARANCE = 3.0;
    private static final int MIN_CHEST_DISTANCE = 2;
    private static final long TICKS_PER_DAY = 24000;
    private static final long NIGHT_START_TICK = 13000;
    private static final int MAX_CHEST_DISTANCE = 10;
    private static final int MAX_DEPOSIT_ATTEMPTS = 3;
    private static final double DEPOSIT_COOLDOWN_SECONDS = 2.0;
    private static final double CHEST_PLACEMENT_COOLDOWN_SECONDS = 3.0;
    private static final double MIN_CHEST_SPACING = 1.5;
    private static final int PLACEMENT_WANDER_THRESHOLD = 10;
    private static final double PROGRESS_REPORT_INTERVAL_SECONDS = 30.0;
    private static final int COBBLESTONE_FOR_PREP = 12;  // furnace (8) + extra (4)
    private static final int WOOD_BUFFER_LOGS = 3;        // extra logs for crafting table/sticks
    private static final int IRON_ORE_BUFFER = 2;         // extra iron for waste/anvil
    private static final int COAL_BUFFER = 5;             // extra coal for fuel
    private static final int DIAMONDS_FOR_ARMOR = 24;    // helmet(5)+chestplate(8)+leggings(7)+boots(4)
    private static final int DIAMONDS_FOR_TOOLS = 11;    // pickaxe(3)+axe(3)+sword(2)+shovel(1)+hoe(2)
    private static final int DIAMONDS_BUFFER = 10;       // extra diamonds for repairs
    private static final int DIAMONDS_TOTAL = DIAMONDS_FOR_ARMOR + DIAMONDS_FOR_TOOLS + DIAMONDS_BUFFER; // 45
    private static final double DANGER_DETECTION_RADIUS_SQ = 64;  // 8 block radius
    private static final int DANGER_HOSTILE_THRESHOLD = 5;
    private static final float DANGER_HEALTH_THRESHOLD = 10.0f;   // 5 hearts
    private static final double SAFE_LOCATION_RADIUS_SQ = 100;    // 10 block radius
    private static final int SAFE_LOCATION_MAX_MOBS = 2;
    private static final int SAFE_SEARCH_MIN_DIST = 20;
    private static final int SAFE_SEARCH_MAX_DIST = 50;
    private static final int SAFE_SEARCH_DIST_STEP = 10;
    private static final int SAFE_SEARCH_ANGLE_STEP = 45;
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
    private BlockPos permanentCraftingTablePos = null;
    private BlockPos craftingTableTargetPos = null;
    private boolean hasPlacedCraftingTable = false;
    private int cachedChestsNeeded = -1;
    private final TimerGame progressReportTimer = new TimerGame(PROGRESS_REPORT_INTERVAL_SECONDS);
    private PrepPhase currentPrepPhase = PrepPhase.CALCULATE_NEEDS;
    private ToolRequirements toolReqs = null;
    private final TimerGame dangerCheckTimer = new TimerGame(2);
    private boolean inDanger = false;
    
    private enum StagePhase {
        INIT,
        PREPARE_TOOLS,
        FIND_OR_PLACE_CHESTS,
        VALIDATE_CHESTS,
        GATHER_MATERIALS,
        DEPOSIT_MATERIALS,
        COMPLETE
    }

    private enum PrepPhase {
        CALCULATE_NEEDS,
        GATHER_WOOD,
        GATHER_STONE,
        MINE_IRON_AND_COAL,
        SMELT_IRON,
        CRAFT_ALL_TOOLS,
        MINE_DIAMONDS,
        CRAFT_DIAMOND_GEAR,
        COMPLETE
    }

    private static class ToolRequirements {
        int woodNeeded = 0;
        int ironNeeded = 0;
        int diamondsNeeded = 0;

        void addShield()      { woodNeeded += 6; ironNeeded += 1; }
        void addIronPickaxe() { woodNeeded += 2; ironNeeded += 3; }
        void addIronAxe()     { woodNeeded += 2; ironNeeded += 3; }
        void addIronSword()   { woodNeeded += 1; ironNeeded += 2; }
        void addWaterBucket() { ironNeeded += 3; }
        void addDiamondGear() { diamondsNeeded = DIAMONDS_TOTAL; }

        int getLogsNeeded()    { return (int) Math.ceil(woodNeeded / 4.0) + WOOD_BUFFER_LOGS; }
        int getIronOreNeeded() { return ironNeeded + IRON_ORE_BUFFER; }
        int getCoalNeeded()    { return ironNeeded + COAL_BUFFER; }
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
        AltoClef mod = AltoClef.getInstance();
        currentPhase = StagePhase.INIT;
        currentMaterialIndex = 0;
        preparationComplete = false;
        chestSearchAttempts = 0;
        currentDepositChest = null;
        depositAttempts = 0;
        attemptedChestPlacements.clear();
        permanentCraftingTablePos = null;
        craftingTableTargetPos = null;
        hasPlacedCraftingTable = false;
        cachedChestsNeeded = -1;
        currentPrepPhase = PrepPhase.CALCULATE_NEEDS;
        toolReqs = null;
        inDanger = false;

        // Protect the designated crafting table and staging chests from being broken
        mod.getBehaviour().push();
        mod.getBehaviour().avoidBlockBreaking(blockPos -> {
            if (permanentCraftingTablePos != null && blockPos.equals(permanentCraftingTablePos)) {
                return true;
            }
            if (craftingTableTargetPos != null && blockPos.equals(craftingTableTargetPos)) {
                return true;
            }
            return stagingChests != null && stagingChests.contains(blockPos);
        });
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef.getInstance().getBehaviour().pop();
    }
    
    @Override
    protected Task onTick() {
        // Check if Litematica is available
        if (!LitematicaHelper.isLitematicaLoaded()) {
            Debug.logError("Litematica mod is not loaded! Cannot stage schematic resources.");
            return null;
        }

        // Sleep through the night
        AltoClef mod = AltoClef.getInstance();
        long timeOfDay = mod.getWorld().getTimeOfDay() % TICKS_PER_DAY;
        if (timeOfDay >= NIGHT_START_TICK) {
            setDebugState("Sleeping through the night...");
            return new SleepThroughNightTask();
        }

        // Danger check every 2 seconds
        if (dangerCheckTimer.elapsed()) {
            dangerCheckTimer.reset();
            boolean dangerous = isDangerous(mod);
            if (dangerous && !inDanger) {
                Debug.logWarning("DANGER detected! Retreating to safety...");
                inDanger = true;
            } else if (!dangerous && inDanger) {
                Debug.logMessage("✓ Safe now, resuming task");
                inDanger = false;
            }
        }
        if (inDanger) {
            Task dangerTask = handleDanger(mod);
            if (dangerTask != null) return dangerTask;
        }

        // Periodic progress report
        if (progressReportTimer.elapsed() && currentPhase != StagePhase.INIT && currentPhase != StagePhase.COMPLETE) {
            logProgress();
            progressReportTimer.reset();
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

        AltoClef mod = AltoClef.getInstance();

        switch (currentPrepPhase) {
            case CALCULATE_NEEDS: {
                toolReqs = calculateToolRequirements(mod);
                currentPrepPhase = PrepPhase.GATHER_WOOD;
                return null;
            }
            case GATHER_WOOD: {
                int logsNeeded = toolReqs.getLogsNeeded();
                int logsHave = mod.getItemStorage().getItemCount(ItemHelper.LOG);
                if (logsHave < logsNeeded) {
                    setDebugState("Gathering wood: " + logsHave + "/" + logsNeeded + " logs");
                    return TaskCatalogue.getItemTask(Items.OAK_LOG, logsNeeded);
                }
                Debug.logMessage("✓ Wood gathered: " + logsHave + " logs");
                currentPrepPhase = PrepPhase.GATHER_STONE;
                return null;
            }
            case GATHER_STONE: {
                int cobbleNeeded = COBBLESTONE_FOR_PREP;
                int cobbleHave = mod.getItemStorage().getItemCount(Items.COBBLESTONE);
                if (cobbleHave < cobbleNeeded) {
                    setDebugState("Mining stone: " + cobbleHave + "/" + cobbleNeeded);
                    return TaskCatalogue.getItemTask(Items.COBBLESTONE, cobbleNeeded);
                }
                Debug.logMessage("✓ Stone gathered: " + cobbleHave + " cobblestone");
                currentPrepPhase = PrepPhase.MINE_IRON_AND_COAL;
                return null;
            }
            case MINE_IRON_AND_COAL: {
                int ironNeeded = toolReqs.getIronOreNeeded();
                int coalNeeded = toolReqs.getCoalNeeded();
                int ironHave = mod.getItemStorage().getItemCount(Items.RAW_IRON)
                        + mod.getItemStorage().getItemCount(Items.IRON_INGOT);
                int coalHave = mod.getItemStorage().getItemCount(Items.COAL);
                if (ironHave < ironNeeded) {
                    setDebugState("Mining iron ore: " + ironHave + "/" + ironNeeded);
                    return TaskCatalogue.getItemTask(Items.RAW_IRON, ironNeeded);
                }
                if (coalHave < coalNeeded) {
                    setDebugState("Mining coal: " + coalHave + "/" + coalNeeded);
                    return TaskCatalogue.getItemTask(Items.COAL, coalNeeded);
                }
                Debug.logMessage("✓ Iron gathered: " + ironHave + ", Coal: " + coalHave);
                currentPrepPhase = PrepPhase.SMELT_IRON;
                return null;
            }
            case SMELT_IRON: {
                int ingotNeeded = toolReqs.ironNeeded;
                int ingotHave = mod.getItemStorage().getItemCount(Items.IRON_INGOT);
                if (ingotHave < ingotNeeded) {
                    setDebugState("Smelting iron: " + ingotHave + "/" + ingotNeeded + " ingots");
                    return TaskCatalogue.getItemTask(Items.IRON_INGOT, ingotNeeded);
                }
                Debug.logMessage("✓ Iron ingots ready: " + ingotHave);
                currentPrepPhase = PrepPhase.CRAFT_ALL_TOOLS;
                return null;
            }
            case CRAFT_ALL_TOOLS: {
                // Ensure crafting table
                Task tableTask = ensureCraftingTableAvailable();
                if (tableTask != null) {
                    setDebugState("Setting up crafting table...");
                    return tableTask;
                }

                // 1. Water bucket (highest priority for safety)
                if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                    setDebugState("Getting water bucket...");
                    return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
                }

                // 2. Shield
                if (!hasShieldEquipped(mod)) {
                    setDebugState("Getting shield for defense...");
                    return TaskCatalogue.getItemTask(Items.SHIELD, 1);
                }
                if (!StorageHelper.isArmorEquipped(Items.SHIELD)) {
                    setDebugState("Equipping shield to offhand...");
                    return new EquipArmorTask(Items.SHIELD);
                }

                // 3. Sword
                if (!hasIronSword(mod)) {
                    setDebugState("Getting iron sword...");
                    return TaskCatalogue.getItemTask(Items.IRON_SWORD, 1);
                }

                // 4. Material-specific tools
                Task toolTask = determineRequiredTools();
                if (toolTask != null) return toolTask;

                // 5. Cook any raw meat before checking food level
                Task cookTask = getCookRawMeatTask(mod);
                if (cookTask != null) {
                    setDebugState("Cooking raw meat...");
                    return cookTask;
                }

                // 6. Food
                if (StorageHelper.calculateInventoryFoodScore() < FOOD_UNITS) {
                    setDebugState("Getting food (with tools)...");
                    return new CollectFoodTask(FOOD_UNITS);
                }

                Debug.logMessage("========================================");
                Debug.logMessage("✓✓✓ IRON TOOLS PREPARED ✓✓✓");
                Debug.logMessage("  ✓ Water bucket");
                Debug.logMessage("  ✓ Shield");
                Debug.logMessage("  ✓ Sword");
                Debug.logMessage("  ✓ Tools");
                Debug.logMessage("========================================");
                currentPrepPhase = PrepPhase.MINE_DIAMONDS;
                return null;
            }
            case MINE_DIAMONDS: {
                if (toolReqs.diamondsNeeded > 0) {
                    int diamondsHave = mod.getItemStorage().getItemCount(Items.DIAMOND);
                    if (diamondsHave < toolReqs.diamondsNeeded) {
                        setDebugState("Mining diamonds: " + diamondsHave + "/" + toolReqs.diamondsNeeded);
                        return TaskCatalogue.getItemTask(Items.DIAMOND, toolReqs.diamondsNeeded);
                    }
                    Debug.logMessage("✓ Diamonds gathered: " + diamondsHave);
                }
                currentPrepPhase = PrepPhase.CRAFT_DIAMOND_GEAR;
                return null;
            }
            case CRAFT_DIAMOND_GEAR: {
                // Craft diamond armor pieces
                if (!mod.getItemStorage().hasItem(Items.DIAMOND_HELMET)
                        && !StorageHelper.isArmorEquipped(Items.DIAMOND_HELMET)) {
                    setDebugState("Crafting diamond helmet...");
                    return TaskCatalogue.getItemTask(Items.DIAMOND_HELMET, 1);
                }
                if (!mod.getItemStorage().hasItem(Items.DIAMOND_CHESTPLATE)
                        && !StorageHelper.isArmorEquipped(Items.DIAMOND_CHESTPLATE)) {
                    setDebugState("Crafting diamond chestplate...");
                    return TaskCatalogue.getItemTask(Items.DIAMOND_CHESTPLATE, 1);
                }
                if (!mod.getItemStorage().hasItem(Items.DIAMOND_LEGGINGS)
                        && !StorageHelper.isArmorEquipped(Items.DIAMOND_LEGGINGS)) {
                    setDebugState("Crafting diamond leggings...");
                    return TaskCatalogue.getItemTask(Items.DIAMOND_LEGGINGS, 1);
                }
                if (!mod.getItemStorage().hasItem(Items.DIAMOND_BOOTS)
                        && !StorageHelper.isArmorEquipped(Items.DIAMOND_BOOTS)) {
                    setDebugState("Crafting diamond boots...");
                    return TaskCatalogue.getItemTask(Items.DIAMOND_BOOTS, 1);
                }

                // Equip diamond armor
                if (!StorageHelper.isArmorEquipped(Items.DIAMOND_HELMET)
                        && mod.getItemStorage().hasItem(Items.DIAMOND_HELMET)) {
                    setDebugState("Equipping diamond helmet...");
                    return new EquipArmorTask(Items.DIAMOND_HELMET);
                }
                if (!StorageHelper.isArmorEquipped(Items.DIAMOND_CHESTPLATE)
                        && mod.getItemStorage().hasItem(Items.DIAMOND_CHESTPLATE)) {
                    setDebugState("Equipping diamond chestplate...");
                    return new EquipArmorTask(Items.DIAMOND_CHESTPLATE);
                }
                if (!StorageHelper.isArmorEquipped(Items.DIAMOND_LEGGINGS)
                        && mod.getItemStorage().hasItem(Items.DIAMOND_LEGGINGS)) {
                    setDebugState("Equipping diamond leggings...");
                    return new EquipArmorTask(Items.DIAMOND_LEGGINGS);
                }
                if (!StorageHelper.isArmorEquipped(Items.DIAMOND_BOOTS)
                        && mod.getItemStorage().hasItem(Items.DIAMOND_BOOTS)) {
                    setDebugState("Equipping diamond boots...");
                    return new EquipArmorTask(Items.DIAMOND_BOOTS);
                }

                // Craft diamond tools
                if (!mod.getItemStorage().hasItem(Items.DIAMOND_PICKAXE)
                        && !mod.getItemStorage().hasItem(Items.NETHERITE_PICKAXE)) {
                    setDebugState("Crafting diamond pickaxe...");
                    return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
                }
                if (!mod.getItemStorage().hasItem(Items.DIAMOND_AXE)
                        && !mod.getItemStorage().hasItem(Items.NETHERITE_AXE)) {
                    setDebugState("Crafting diamond axe...");
                    return TaskCatalogue.getItemTask(Items.DIAMOND_AXE, 1);
                }
                if (!mod.getItemStorage().hasItem(Items.DIAMOND_SWORD)
                        && !mod.getItemStorage().hasItem(Items.NETHERITE_SWORD)) {
                    setDebugState("Crafting diamond sword...");
                    return TaskCatalogue.getItemTask(Items.DIAMOND_SWORD, 1);
                }
                if (!mod.getItemStorage().hasItem(Items.DIAMOND_SHOVEL)
                        && !mod.getItemStorage().hasItem(Items.NETHERITE_SHOVEL)) {
                    setDebugState("Crafting diamond shovel...");
                    return TaskCatalogue.getItemTask(Items.DIAMOND_SHOVEL, 1);
                }
                if (!mod.getItemStorage().hasItem(Items.DIAMOND_HOE)
                        && !mod.getItemStorage().hasItem(Items.NETHERITE_HOE)) {
                    setDebugState("Crafting diamond hoe...");
                    return TaskCatalogue.getItemTask(Items.DIAMOND_HOE, 1);
                }

                // Ensure shield
                if (!hasShieldEquipped(mod)) {
                    setDebugState("Getting shield for defense...");
                    return TaskCatalogue.getItemTask(Items.SHIELD, 1);
                }
                if (!StorageHelper.isArmorEquipped(Items.SHIELD)) {
                    setDebugState("Equipping shield to offhand...");
                    return new EquipArmorTask(Items.SHIELD);
                }

                // Food check
                if (StorageHelper.calculateInventoryFoodScore() < FOOD_UNITS) {
                    setDebugState("Getting food (after diamond gear)...");
                    return new CollectFoodTask(FOOD_UNITS);
                }

                Debug.logMessage("========================================");
                Debug.logMessage("✓✓✓ ALL DIAMOND GEAR PREPARED ✓✓✓");
                Debug.logMessage("  ✓ Diamond armor (helmet, chestplate, leggings, boots)");
                Debug.logMessage("  ✓ Diamond tools (pickaxe, axe, sword, shovel, hoe)");
                Debug.logMessage("  ✓ Shield");
                Debug.logMessage("  ✓ Food");
                Debug.logMessage("========================================");
                currentPrepPhase = PrepPhase.COMPLETE;
                return null;
            }
            case COMPLETE:
            default:
                preparationComplete = true;
                return null;
        }
    }

    private boolean hasShieldEquipped(AltoClef mod) {
        // Check offhand slot first
        if (StorageHelper.isArmorEquipped(Items.SHIELD)) {
            return true;
        }
        // Then check inventory
        return mod.getItemStorage().hasItem(Items.SHIELD);
    }

    private boolean hasIronSword(AltoClef mod) {
        return mod.getItemStorage().hasItem(Items.IRON_SWORD)
                || mod.getItemStorage().hasItem(Items.DIAMOND_SWORD)
                || mod.getItemStorage().hasItem(Items.NETHERITE_SWORD)
                || mod.getItemStorage().hasItem(Items.STONE_SWORD)
                || mod.getItemStorage().hasItem(Items.WOODEN_SWORD);
    }

    /**
     * Returns a task to smelt raw meat into cooked meat, or null if there is nothing to cook.
     */
    private Task getCookRawMeatTask(AltoClef mod) {
        Item[][] rawToCooked = {
            {Items.BEEF,     Items.COOKED_BEEF},
            {Items.PORKCHOP, Items.COOKED_PORKCHOP},
            {Items.CHICKEN,  Items.COOKED_CHICKEN},
            {Items.MUTTON,   Items.COOKED_MUTTON},
            {Items.RABBIT,   Items.COOKED_RABBIT},
        };
        for (Item[] pair : rawToCooked) {
            int rawCount = mod.getItemStorage().getItemCount(pair[0]);
            if (rawCount > 0) {
                return new SmeltInFurnaceTask(new SmeltTarget(
                    new ItemTarget(pair[1], rawCount),
                    new ItemTarget(pair[0], rawCount)
                ));
            }
        }
        return null;
    }

    private ToolRequirements calculateToolRequirements(AltoClef mod) {
        ToolRequirements req = new ToolRequirements();

        if (!hasShieldEquipped(mod))                               req.addShield();
        if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET))     req.addWaterBucket();

        boolean needsPickaxe = !mod.getItemStorage().hasItem(Items.IRON_PICKAXE)
                && !mod.getItemStorage().hasItem(Items.DIAMOND_PICKAXE)
                && !mod.getItemStorage().hasItem(Items.NETHERITE_PICKAXE);
        if (needsPickaxe)                                          req.addIronPickaxe();

        boolean needsAxe = !mod.getItemStorage().hasItem(Items.IRON_AXE)
                && !mod.getItemStorage().hasItem(Items.DIAMOND_AXE)
                && !mod.getItemStorage().hasItem(Items.NETHERITE_AXE);
        if (needsAxe)                                              req.addIronAxe();

        if (!hasIronSword(mod))                                    req.addIronSword();

        if (needsDiamondOrNetheriteGear(mod))                      req.addDiamondGear();

        Debug.logMessage("Tool requirements calculated:");
        Debug.logMessage("  Wood: " + req.getLogsNeeded() + " logs");
        Debug.logMessage("  Iron: " + req.getIronOreNeeded() + " ore");
        Debug.logMessage("  Coal: " + req.getCoalNeeded() + " pieces");
        Debug.logMessage("  Diamonds: " + req.diamondsNeeded);

        return req;
    }

    /**
     * Returns true if any piece of diamond (or better) gear is missing from inventory or equipment slots.
     */
    private boolean needsDiamondOrNetheriteGear(AltoClef mod) {
        // Armor pieces: check inventory and equipped slots (diamond or netherite both count)
        if (!mod.getItemStorage().hasItem(Items.DIAMOND_HELMET)
                && !StorageHelper.isArmorEquipped(Items.DIAMOND_HELMET)
                && !StorageHelper.isArmorEquipped(Items.NETHERITE_HELMET)) return true;
        if (!mod.getItemStorage().hasItem(Items.DIAMOND_CHESTPLATE)
                && !StorageHelper.isArmorEquipped(Items.DIAMOND_CHESTPLATE)
                && !StorageHelper.isArmorEquipped(Items.NETHERITE_CHESTPLATE)) return true;
        if (!mod.getItemStorage().hasItem(Items.DIAMOND_LEGGINGS)
                && !StorageHelper.isArmorEquipped(Items.DIAMOND_LEGGINGS)
                && !StorageHelper.isArmorEquipped(Items.NETHERITE_LEGGINGS)) return true;
        if (!mod.getItemStorage().hasItem(Items.DIAMOND_BOOTS)
                && !StorageHelper.isArmorEquipped(Items.DIAMOND_BOOTS)
                && !StorageHelper.isArmorEquipped(Items.NETHERITE_BOOTS)) return true;
        // Tools: check inventory only (netherite supersedes diamond)
        if (!mod.getItemStorage().hasItem(Items.DIAMOND_PICKAXE)
                && !mod.getItemStorage().hasItem(Items.NETHERITE_PICKAXE)) return true;
        if (!mod.getItemStorage().hasItem(Items.DIAMOND_AXE)
                && !mod.getItemStorage().hasItem(Items.NETHERITE_AXE)) return true;
        if (!mod.getItemStorage().hasItem(Items.DIAMOND_SWORD)
                && !mod.getItemStorage().hasItem(Items.NETHERITE_SWORD)) return true;
        if (!mod.getItemStorage().hasItem(Items.DIAMOND_SHOVEL)
                && !mod.getItemStorage().hasItem(Items.NETHERITE_SHOVEL)) return true;
        if (!mod.getItemStorage().hasItem(Items.DIAMOND_HOE)
                && !mod.getItemStorage().hasItem(Items.NETHERITE_HOE)) return true;
        return false;
    }

    private boolean isDangerous(AltoClef mod) {
        List<LivingEntity> hostiles = mod.getEntityTracker().getHostiles().stream()
                .filter(e -> e.squaredDistanceTo(mod.getPlayer()) < DANGER_DETECTION_RADIUS_SQ)
                .collect(Collectors.toList());
        if (hostiles.size() > DANGER_HOSTILE_THRESHOLD) {
            Debug.logWarning("DANGER: " + hostiles.size() + " hostile mobs nearby!");
            return true;
        }
        float health = mod.getPlayer().getHealth();
        if (health < DANGER_HEALTH_THRESHOLD) {
            Debug.logWarning("DANGER: Low health (" + health + "/20)");
            return true;
        }
        return false;
    }

    private Task handleDanger(AltoClef mod) {
        // Equip shield if available
        if (!StorageHelper.isArmorEquipped(Items.SHIELD) && mod.getItemStorage().hasItem(Items.SHIELD)) {
            return new EquipArmorTask(Items.SHIELD);
        }
        // Find a safe location away from mobs
        BlockPos safeSpot = findSafeLocation(mod);
        if (safeSpot != null) {
            setDebugState("DANGER! Retreating to safety...");
            return new GetToBlockTask(safeSpot);
        }
        return null;
    }

    private BlockPos findSafeLocation(AltoClef mod) {
        BlockPos playerPos = mod.getPlayer().getBlockPos();
        for (int dist = SAFE_SEARCH_MIN_DIST; dist <= SAFE_SEARCH_MAX_DIST; dist += SAFE_SEARCH_DIST_STEP) {
            for (int angle = 0; angle < 360; angle += SAFE_SEARCH_ANGLE_STEP) {
                double rad = Math.toRadians(angle);
                int dx = (int) (Math.cos(rad) * dist);
                int dz = (int) (Math.sin(rad) * dist);
                BlockPos candidate = playerPos.add(dx, 0, dz);
                long mobsNearby = mod.getEntityTracker().getHostiles().stream()
                        .filter(e -> e.squaredDistanceTo(candidate.getX(), candidate.getY(), candidate.getZ()) < SAFE_LOCATION_RADIUS_SQ)
                        .count();
                if (mobsNearby < SAFE_LOCATION_MAX_MOBS) {
                    return candidate;
                }
            }
        }
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

    private Task ensureCraftingTableAvailable() {
        AltoClef mod = AltoClef.getInstance();

        // Check if our confirmed crafting table still exists
        if (permanentCraftingTablePos != null) {
            Block block = mod.getWorld().getBlockState(permanentCraftingTablePos).getBlock();
            if (block == Blocks.CRAFTING_TABLE) {
                // Table is still there – nothing to do
                return null;
            } else {
                Debug.logMessage("Crafting table was destroyed, finding/placing new one...");
                permanentCraftingTablePos = null;
                craftingTableTargetPos = null;
                hasPlacedCraftingTable = false;
            }
        }

        // Check if a pending placement has completed
        if (craftingTableTargetPos != null) {
            Block block = mod.getWorld().getBlockState(craftingTableTargetPos).getBlock();
            if (block == Blocks.CRAFTING_TABLE) {
                permanentCraftingTablePos = craftingTableTargetPos;
                Debug.logMessage("Crafting table confirmed at " + permanentCraftingTablePos.toShortString());
                return null;
            }
            // Placement still in progress – keep returning the task
            return new PlaceBlockTask(craftingTableTargetPos, new Block[]{Blocks.CRAFTING_TABLE}, false, false);
        }

        // Look for a nearby crafting table we can claim as our permanent one
        Optional<BlockPos> nearbyTable = mod.getBlockScanner().getNearestBlock(Blocks.CRAFTING_TABLE);
        if (nearbyTable.isPresent() && nearbyTable.get().isWithinDistance(mod.getPlayer().getPos(), 50)) {
            permanentCraftingTablePos = nearbyTable.get();
            Debug.logMessage("Found existing crafting table at " + permanentCraftingTablePos.toShortString());
            return null;
        }

        // Need to place a new crafting table at a safe location
        if (!hasPlacedCraftingTable) {
            if (!mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)) {
                return TaskCatalogue.getItemTask(Items.CRAFTING_TABLE, 1);
            }

            if (placementInfo != null) {
                BlockPos safePos = findSafeCraftingTableLocation(placementInfo.getOrigin());
                if (safePos != null) {
                    craftingTableTargetPos = safePos;
                    hasPlacedCraftingTable = true;
                    Debug.logMessage("Placing crafting table at " + safePos.toShortString());
                    return new PlaceBlockTask(safePos, new Block[]{Blocks.CRAFTING_TABLE}, false, false);
                }
            }

            // Fallback: let CraftInTableTask handle placement
            hasPlacedCraftingTable = true;
        }

        return null;
    }

    private BlockPos findSafeCraftingTableLocation(BlockPos near) {
        AltoClef mod = AltoClef.getInstance();

        for (int distance = 5; distance <= 15; distance += 2) {
            for (int dx = -distance; dx <= distance; dx += 2) {
                for (int dz = -distance; dz <= distance; dz += 2) {
                    BlockPos candidate = near.add(dx, 0, dz);

                    for (int dy = 3; dy >= -2; dy--) {
                        BlockPos pos = candidate.add(0, dy, 0);
                        BlockPos below = pos.down();

                        BlockState belowState = mod.getWorld().getBlockState(below);

                        boolean canPlace = WorldHelper.canPlace(pos);
                        boolean solidBelow = WorldHelper.isSolidBlock(below);

                        if (canPlace && solidBelow && !isLiquid(belowState) && isOutsideSchematicBounds(pos)) {
                            return pos;
                        }
                    }
                }
            }
        }

        return null;
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
        // Calculate once and cache to avoid spam-logging every tick
        if (cachedChestsNeeded < 0) {
            cachedChestsNeeded = calculateRequiredChests();
        }
        int chestsNeeded = cachedChestsNeeded;

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
        if (cachedChestsNeeded < 0) {
            cachedChestsNeeded = calculateRequiredChests();
        }
        int chestsNeeded = cachedChestsNeeded;

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

    private void logProgress() {
        if (materialStaging == null || materialStaging.isEmpty()) return;
        updateInventoryCounts();

        int totalTypes = materialStaging.size();
        int completedTypes = 0;
        long totalItemsNeeded = 0;
        long totalItemsGathered = 0;

        Debug.logMessage("========== PROGRESS REPORT ==========");
        Debug.logMessage("Current phase: " + currentPhase);

        for (MaterialStaging staging : materialStaging) {
            long total = staging.currentlyInInventory + staging.currentlyInChests;
            long needed = staging.totalRequired;
            totalItemsNeeded += needed;
            totalItemsGathered += Math.min(total, needed);
            if (total >= needed) {
                completedTypes++;
            } else {
                Debug.logMessage("  " + staging.itemStack.getItem() + ": " + total + "/" + needed + " (" + (needed - total) + " left)");
            }
        }

        int percentTypes = totalTypes > 0 ? (int) ((completedTypes * 100.0) / totalTypes) : 0;
        int percentItems = totalItemsNeeded > 0 ? (int) ((totalItemsGathered * 100.0) / totalItemsNeeded) : 0;

        Debug.logMessage("Material types: " + completedTypes + "/" + totalTypes + " (" + percentTypes + "% complete)");
        Debug.logMessage("Total items: " + totalItemsGathered + "/" + totalItemsNeeded + " (" + percentItems + "% complete)");
        Debug.logMessage("=====================================");
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
