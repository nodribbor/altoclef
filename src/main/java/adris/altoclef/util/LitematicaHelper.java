package adris.altoclef.util;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Helper class to interface with Litematica mod using reflection.
 * This allows Litematica to be an optional runtime dependency.
 */
public class LitematicaHelper {
    
    private static Boolean litematicaAvailable = null;
    private static Class<?> dataManagerClass = null;
    private static Class<?> placementManagerClass = null;
    private static Class<?> schematicPlacementClass = null;
    private static Class<?> materialListBaseClass = null;
    private static Class<?> materialListEntryClass = null;

    /** Maximum age (in milliseconds) of a material list file to be considered current (5 minutes). */
    private static final long MAX_MATERIAL_FILE_AGE_MS = 5 * 60 * 1000;
    
    /**
     * Check if Litematica mod is loaded and available
     */
    public static boolean isLitematicaLoaded() {
        if (litematicaAvailable != null) {
            return litematicaAvailable;
        }
        
        // Try modern packages first
        boolean modernSuccess = tryLoadModernPackages();
        if (modernSuccess) {
            litematicaAvailable = true;
            Debug.logMessage("Litematica integration enabled (modern version)");
            return true;
        }
        
        // Try legacy packages
        boolean legacySuccess = tryLoadLegacyPackages();
        if (legacySuccess) {
            litematicaAvailable = true;
            Debug.logMessage("Litematica integration enabled (legacy version)");
            return true;
        }
        
        litematicaAvailable = false;
        Debug.logWarning("Litematica not found - all class loading attempts failed");
        return false;
    }
    
    /**
     * Try loading modern Litematica packages (fi.dy.masa.*)
     */
    private static boolean tryLoadModernPackages() {
        Debug.logMessage("Attempting to load modern Litematica packages (fi.dy.masa.*)...");
        
        boolean allSuccess = true;
        
        try {
            dataManagerClass = loadClassWithLog("fi.dy.masa.litematica.data.DataManager");
        } catch (ClassNotFoundException e) {
            allSuccess = false;
        }
        
        try {
            placementManagerClass = loadClassWithLog("fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager");
        } catch (ClassNotFoundException e) {
            allSuccess = false;
        }
        
        try {
            schematicPlacementClass = loadClassWithLog("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
        } catch (ClassNotFoundException e) {
            allSuccess = false;
        }
        
        try {
            materialListBaseClass = loadClassWithLog("fi.dy.masa.litematica.materials.MaterialListBase");
        } catch (ClassNotFoundException e) {
            allSuccess = false;
        }
        
        try {
            materialListEntryClass = loadClassWithLog("fi.dy.masa.litematica.materials.MaterialListEntry");
        } catch (ClassNotFoundException e) {
            allSuccess = false;
        }
        
        if (allSuccess) {
            Debug.logMessage("Modern package loading successful");
        } else {
            Debug.logMessage("Modern package loading failed - one or more classes not found");
        }
        
        return allSuccess;
    }
    
    /**
     * Try loading legacy Litematica packages (litematica.*)
     */
    private static boolean tryLoadLegacyPackages() {
        Debug.logMessage("Attempting to load legacy Litematica packages (litematica.*)...");
        
        boolean allSuccess = true;
        
        try {
            dataManagerClass = loadClassWithLog("litematica.data.DataManager");
        } catch (ClassNotFoundException e) {
            allSuccess = false;
        }
        
        try {
            placementManagerClass = loadClassWithLog("litematica.schematic.placement.SchematicPlacementManager");
        } catch (ClassNotFoundException e) {
            allSuccess = false;
        }
        
        try {
            schematicPlacementClass = loadClassWithLog("litematica.schematic.placement.SchematicPlacement");
        } catch (ClassNotFoundException e) {
            allSuccess = false;
        }
        
        try {
            materialListBaseClass = loadClassWithLog("litematica.materials.MaterialListBase");
        } catch (ClassNotFoundException e) {
            allSuccess = false;
        }
        
        try {
            materialListEntryClass = loadClassWithLog("litematica.materials.MaterialListEntry");
        } catch (ClassNotFoundException e) {
            allSuccess = false;
        }
        
        if (allSuccess) {
            Debug.logMessage("Legacy package loading successful");
        } else {
            Debug.logMessage("Legacy package loading failed - one or more classes not found");
        }
        
        return allSuccess;
    }
    
    /**
     * Load a class with detailed logging
     */
    private static Class<?> loadClassWithLog(String className) throws ClassNotFoundException {
        try {
            Class<?> clazz = Class.forName(className);
            Debug.logMessage("  ✓ Loaded: " + className);
            return clazz;
        } catch (ClassNotFoundException e) {
            Debug.logMessage("  ✗ Failed: " + className + " - " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Data class to hold material requirement information
     */
    public static class MaterialRequirement {
        private final ItemStack itemStack;
        private final long totalCount;
        private final long missingCount;
        
        public MaterialRequirement(ItemStack itemStack, long totalCount, long missingCount) {
            this.itemStack = itemStack;
            this.totalCount = totalCount;
            this.missingCount = missingCount;
        }
        
        public ItemStack getItemStack() {
            return itemStack;
        }
        
        public long getTotalCount() {
            return totalCount;
        }
        
        public long getMissingCount() {
            return missingCount;
        }
        
        @Override
        public String toString() {
            return String.format("%s x%d (missing: %d)", 
                itemStack.getName().getString(), totalCount, missingCount);
        }
    }
    
    /**
     * Data class to hold schematic placement information
     */
    public static class SchematicPlacementInfo {
        private final String name;
        private final BlockPos origin;
        private final List<MaterialRequirement> materials;
        
        public SchematicPlacementInfo(String name, BlockPos origin, List<MaterialRequirement> materials) {
            this.name = name;
            this.origin = origin;
            this.materials = materials;
        }
        
        public String getName() {
            return name;
        }
        
        public BlockPos getOrigin() {
            return origin;
        }
        
        public List<MaterialRequirement> getMaterials() {
            return materials;
        }
        
        public long getTotalUniqueItems() {
            return materials.size();
        }
        
        public long getTotalItemCount() {
            return materials.stream().mapToLong(MaterialRequirement::getTotalCount).sum();
        }
    }
    
    /**
     * Get the currently selected/active schematic placement
     */
    public static Optional<Object> getSelectedPlacement() {
        if (!isLitematicaLoaded()) {
            return Optional.empty();
        }
        
        try {
            // DataManager.getSchematicPlacementManager()
            Method getManagerMethod = dataManagerClass.getMethod("getSchematicPlacementManager");
            Object placementManager = getManagerMethod.invoke(null);
            
            if (placementManager == null) {
                return Optional.empty();
            }
            
            // placementManager.getSelectedSchematicPlacement()
            Method getSelectedMethod = placementManagerClass.getMethod("getSelectedSchematicPlacement");
            Object placement = getSelectedMethod.invoke(placementManager);
            
            return Optional.ofNullable(placement);
        } catch (Exception e) {
            Debug.logWarning("Error getting selected placement: " + e.getMessage());
            return Optional.empty();
        }
    }
    
    /**
     * Get detailed information about a schematic placement including material requirements
     */
    public static Optional<SchematicPlacementInfo> getPlacementInfo(Object placementObj) {
        if (!isLitematicaLoaded() || placementObj == null) {
            return Optional.empty();
        }
        
        try {
            // Get placement name
            Method getNameMethod = schematicPlacementClass.getMethod("getName");
            String name = (String) getNameMethod.invoke(placementObj);
            
            // Get placement origin
            Method getOriginMethod = schematicPlacementClass.getMethod("getOrigin");
            Object originObj = getOriginMethod.invoke(placementObj);

            // Extract coordinates - handle both malilib and vanilla BlockPos
            int x, y, z;

            if (originObj.getClass().getName().startsWith("fi.dy.masa.malilib")) {
                // Malilib BlockPos - has getX(), getY(), getZ()
                try {
                    Method getXMethod = originObj.getClass().getMethod("getX");
                    Method getYMethod = originObj.getClass().getMethod("getY");
                    Method getZMethod = originObj.getClass().getMethod("getZ");
                    x = (int) getXMethod.invoke(originObj);
                    y = (int) getYMethod.invoke(originObj);
                    z = (int) getZMethod.invoke(originObj);
                } catch (NoSuchMethodException e) {
                    // Fallback: try as vanilla BlockPos
                    net.minecraft.util.math.BlockPos pos = (net.minecraft.util.math.BlockPos) originObj;
                    x = pos.getX();
                    y = pos.getY();
                    z = pos.getZ();
                }
            } else {
                // Vanilla Minecraft BlockPos
                // In Fabric environment, these methods ARE available
                net.minecraft.util.math.BlockPos pos = (net.minecraft.util.math.BlockPos) originObj;
                x = pos.getX();
                y = pos.getY();
                z = pos.getZ();
            }

            BlockPos origin = new BlockPos(x, y, z);
            
            // Get material list from placement
            Method getMaterialListMethod = schematicPlacementClass.getMethod("getMaterialList");
            Object materialList = getMaterialListMethod.invoke(placementObj);

            if (materialList == null) {
                Debug.logWarning("Material list is null for placement: " + name);
                return Optional.empty();
            }

            // Force regeneration of the material list
            Debug.logMessage("Attempting to regenerate material list...");
            try {
                Method reCreateMethod = materialListBaseClass.getMethod("reCreateMaterialList");
                reCreateMethod.invoke(materialList);
                Debug.logMessage("Called reCreateMaterialList() successfully");

                // Small delay to let async operations complete (if any)
                Thread.sleep(100);
            } catch (Exception e) {
                Debug.logWarning("Could not call reCreateMaterialList(): " + e.getMessage());
            }

            // Try multiple methods to get materials, with debug logging
            Object materialsObj = null;
            int materialCount = 0;

            // Method 1: Try getMaterialsFiltered(true)
            try {
                Debug.logMessage("Trying getMaterialsFiltered(true)...");
                Method getFilteredMethod = materialListBaseClass.getMethod("getMaterialsFiltered", boolean.class);
                materialsObj = getFilteredMethod.invoke(materialList, true);

                if (materialsObj instanceof Iterable) {
                    int count = 0;
                    for (Object item : (Iterable<?>) materialsObj) {
                        count++;
                    }
                    materialCount = count;
                    Debug.logMessage("getMaterialsFiltered(true) returned " + materialCount + " entries");
                }
            } catch (Exception e) {
                Debug.logWarning("getMaterialsFiltered(true) failed: " + e.getMessage());
            }

            // Method 2: If empty, try getMaterialsAll()
            if (materialCount == 0) {
                try {
                    Debug.logMessage("Trying getMaterialsAll()...");
                    Method getAllMethod = materialListBaseClass.getMethod("getMaterialsAll");
                    materialsObj = getAllMethod.invoke(materialList);

                    if (materialsObj instanceof Iterable) {
                        int count = 0;
                        for (Object item : (Iterable<?>) materialsObj) {
                            count++;
                        }
                        materialCount = count;
                        Debug.logMessage("getMaterialsAll() returned " + materialCount + " entries");
                    }
                } catch (Exception e) {
                    Debug.logWarning("getMaterialsAll() failed: " + e.getMessage());
                }
            }

            // Method 3: If still empty, try getMaterialsMissingOnly(true)
            if (materialCount == 0) {
                try {
                    Debug.logMessage("Trying getMaterialsMissingOnly(true)...");
                    Method getMissingMethod = materialListBaseClass.getMethod("getMaterialsMissingOnly", boolean.class);
                    materialsObj = getMissingMethod.invoke(materialList, true);

                    if (materialsObj instanceof Iterable) {
                        int count = 0;
                        for (Object item : (Iterable<?>) materialsObj) {
                            count++;
                        }
                        materialCount = count;
                        Debug.logMessage("getMaterialsMissingOnly(true) returned " + materialCount + " entries");
                    }
                } catch (Exception e) {
                    Debug.logWarning("getMaterialsMissingOnly(true) failed: " + e.getMessage());
                }
            }

            Debug.logMessage("Final material count: " + materialCount);

            if (materialCount == 0) {
                Debug.logWarning("All methods returned 0 materials. The material list may not be generated yet.");
                Debug.logWarning("Please ensure you've opened the material list in Litematica's GUI first.");
            }

            // Convert materialsObj to List<MaterialRequirement>
            List<MaterialRequirement> materials = new ArrayList<>();
            if (materialsObj instanceof Iterable) {
                for (Object entryObj : (Iterable<?>) materialsObj) {
                    MaterialRequirement req = parseMaterialEntry(entryObj);
                    if (req != null) {
                        materials.add(req);
                    }
                }
            }
            
            return Optional.of(new SchematicPlacementInfo(name, origin, materials));
        } catch (Exception e) {
            Debug.logWarning("Error getting placement info: " + e.getMessage());
            e.printStackTrace();
            return Optional.empty();
        }
    }
    
    /**
     * Parse a MaterialListEntry object into our MaterialRequirement
     */
    private static MaterialRequirement parseMaterialEntry(Object entryObj) {
        try {
            // Get the ItemStack
            Method getStackMethod = materialListEntryClass.getMethod("getStack");
            ItemStack stack = (ItemStack) getStackMethod.invoke(entryObj);
            
            // Get total count
            Method getCountTotalMethod = materialListEntryClass.getMethod("getCountTotal");
            long totalCount = (long) getCountTotalMethod.invoke(entryObj);
            
            // Get missing count
            Method getCountMissingMethod = materialListEntryClass.getMethod("getCountMissing");
            long missingCount = (long) getCountMissingMethod.invoke(entryObj);
            
            return new MaterialRequirement(stack, totalCount, missingCount);
        } catch (Exception e) {
            Debug.logWarning("Error parsing material entry: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Get the currently selected placement's info.
     * First tries to read from the latest material list file written by Litematica,
     * then falls back to the reflection-based approach.
     */
    public static Optional<SchematicPlacementInfo> getSelectedPlacementInfo() {
        // Try file-based approach first
        Optional<File> fileOpt = findLatestMaterialListFile();
        if (fileOpt.isPresent()) {
            File file = fileOpt.get();
            long fileAge = System.currentTimeMillis() - file.lastModified();
            if (fileAge < MAX_MATERIAL_FILE_AGE_MS) {
                Debug.logMessage("Reading material list from file (age: " + (fileAge / 1000) + "s)");
                List<MaterialRequirement> materials = parseMaterialListFile(file);
                if (!materials.isEmpty()) {
                    String schematicName = "Unknown Schematic";
                    // Default origin of (0,0,0) is used as a fallback when reflection cannot retrieve it
                    BlockPos origin = new BlockPos(0, 0, 0);

                    // Try to get placement name and origin via reflection
                    if (isLitematicaLoaded()) {
                        try {
                            Method getManagerMethod = dataManagerClass.getMethod("getSchematicPlacementManager");
                            Object placementManager = getManagerMethod.invoke(null);
                            if (placementManager != null) {
                                Method getSelectedMethod = placementManagerClass.getMethod("getSelectedSchematicPlacement");
                                Object placement = getSelectedMethod.invoke(placementManager);
                                if (placement != null) {
                                    Method getNameMethod = schematicPlacementClass.getMethod("getName");
                                    schematicName = (String) getNameMethod.invoke(placement);

                                    Method getOriginMethod = schematicPlacementClass.getMethod("getOrigin");
                                    Object originObj = getOriginMethod.invoke(placement);
                                    if (originObj instanceof net.minecraft.util.math.BlockPos pos) {
                                        origin = pos;
                                    } else {
                                        try {
                                            Method getXMethod = originObj.getClass().getMethod("getX");
                                            Method getYMethod = originObj.getClass().getMethod("getY");
                                            Method getZMethod = originObj.getClass().getMethod("getZ");
                                            origin = new BlockPos(
                                                (int) getXMethod.invoke(originObj),
                                                (int) getYMethod.invoke(originObj),
                                                (int) getZMethod.invoke(originObj)
                                            );
                                        } catch (NoSuchMethodException e) {
                                            Debug.logWarning("Could not extract origin coordinates: " + e.getMessage());
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Debug.logWarning("Could not get placement details via reflection: " + e.getMessage());
                        }
                    }

                    return Optional.of(new SchematicPlacementInfo(schematicName, origin, materials));
                }
            } else {
                Debug.logMessage("Material list file is old (age: " + (fileAge / 1000) + "s). Please regenerate it using 'Write to file' in Litematica.");
            }
        }

        // Fall back to reflection-based approach
        Optional<Object> placement = getSelectedPlacement();
        if (placement.isPresent()) {
            return getPlacementInfo(placement.get());
        }
        return Optional.empty();
    }

    /**
     * Find the most recent material list file in the Litematica config directory.
     */
    private static Optional<File> findLatestMaterialListFile() {
        try {
            File minecraftDir = MinecraftClient.getInstance().runDirectory;
            File litematicaDir = new File(minecraftDir, "config/litematica");

            if (!litematicaDir.exists() || !litematicaDir.isDirectory()) {
                Debug.logWarning("Litematica config directory not found: " + litematicaDir.getAbsolutePath());
                return Optional.empty();
            }

            File[] files = litematicaDir.listFiles((dir, name) ->
                name.startsWith("material_list_") && name.endsWith(".txt")
            );

            if (files == null || files.length == 0) {
                Debug.logMessage("No material list files found. Please use 'Write to file' in Litematica's material list GUI.");
                return Optional.empty();
            }

            File latestFile = files[0];
            for (File file : files) {
                if (file.lastModified() > latestFile.lastModified()) {
                    latestFile = file;
                }
            }

            Debug.logMessage("Found material list file: " + latestFile.getName());
            return Optional.of(latestFile);

        } catch (Exception e) {
            Debug.logWarning("Error finding material list file: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parse a Litematica material list file into a list of MaterialRequirements.
     */
    private static List<MaterialRequirement> parseMaterialListFile(File file) {
        List<MaterialRequirement> materials = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            boolean inDataSection = false;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("+") || line.startsWith("| Material List")) {
                    continue;
                }

                if (line.contains("| Item") && line.contains("Total") && line.contains("Missing")) {
                    inDataSection = true;
                    continue;
                }

                if (line.trim().isEmpty()) {
                    continue;
                }

                if (inDataSection && line.startsWith("|")) {
                    try {
                        String[] parts = line.split("\\|");
                        if (parts.length >= 4) {
                            String itemName = parts[1].trim();
                            String totalStr = parts[2].trim();
                            String missingStr = parts[3].trim();

                            if (itemName.isEmpty() || itemName.equals("Item")) {
                                continue;
                            }

                            long totalCount = Long.parseLong(totalStr);
                            long missingCount = Long.parseLong(missingStr);

                            String itemId = convertItemNameToId(itemName);
                            Identifier identifier = Identifier.of(itemId);

                            if (Registries.ITEM.containsId(identifier)) {
                                Item item = Registries.ITEM.get(identifier);
                                ItemStack stack = new ItemStack(item);
                                materials.add(new MaterialRequirement(stack, totalCount, missingCount));
                                Debug.logMessage("  Parsed: " + itemName + " -> " + itemId + " (Total: " + totalCount + ", Missing: " + missingCount + ")");
                            } else {
                                Debug.logWarning("  Unknown item ID: " + itemId + " (from name: " + itemName + ")");
                            }
                        }
                    } catch (NumberFormatException e) {
                        // Skip lines with non-numeric count fields (e.g., separator or header lines)
                        Debug.logMessage("  Skipping line with non-numeric count: " + line.trim());
                    }
                }
            }

            Debug.logMessage("Parsed " + materials.size() + " materials from file");

        } catch (IOException e) {
            Debug.logWarning("Error reading material list file: " + e.getMessage());
        }

        return materials;
    }

    /**
     * Convert a display name to a Minecraft item ID.
     * e.g., "Grass Block" -> "minecraft:grass_block"
     * Handles common display name patterns used in Litematica material list files.
     */
    private static String convertItemNameToId(String displayName) {
        String id = displayName.toLowerCase()
            .replace(" ", "_")
            .replace("'", "")
            .replace("-", "_")
            .replace("(", "")
            .replace(")", "");

        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }

        return id;
    }

}
