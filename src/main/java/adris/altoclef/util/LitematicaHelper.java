package adris.altoclef.util;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

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
            
            // Get material list
            Method getMaterialListMethod = schematicPlacementClass.getMethod("getMaterialList");
            Object materialList = getMaterialListMethod.invoke(placementObj);
            
            // Get all materials from the list
            Method getAllMaterialsMethod = materialListBaseClass.getMethod("getMaterialsAll");
            Object materialsObj = getAllMaterialsMethod.invoke(materialList);
            
            // Convert ImmutableList to List
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
            Method getTotalCountMethod = materialListEntryClass.getMethod("getTotalCount");
            long totalCount = (long) getTotalCountMethod.invoke(entryObj);
            
            // Get missing count
            Method getMissingCountMethod = materialListEntryClass.getMethod("getMissingCount");
            long missingCount = (long) getMissingCountMethod.invoke(entryObj);
            
            return new MaterialRequirement(stack, totalCount, missingCount);
        } catch (Exception e) {
            Debug.logWarning("Error parsing material entry: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Get the currently selected placement's info
     */
    public static Optional<SchematicPlacementInfo> getSelectedPlacementInfo() {
        Optional<Object> placement = getSelectedPlacement();
        if (placement.isPresent()) {
            return getPlacementInfo(placement.get());
        }
        return Optional.empty();
    }
}
