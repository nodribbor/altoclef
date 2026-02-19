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
    private static Class<?> itemTypeClass = null;
    
    /**
     * Check if Litematica mod is loaded and available
     */
    public static boolean isLitematicaLoaded() {
        if (litematicaAvailable != null) {
            return litematicaAvailable;
        }
        
        try {
            // Try modern package structure first (MC 1.14+ / Litematica 0.0.0-dev.20190902+)
            try {
                dataManagerClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
                placementManagerClass = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager");
                schematicPlacementClass = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
                materialListBaseClass = Class.forName("fi.dy.masa.litematica.materials.MaterialListBase");
                materialListEntryClass = Class.forName("fi.dy.masa.litematica.materials.MaterialListEntry");
                itemTypeClass = Class.forName("fi.dy.masa.malilib.util.data.ItemType");
                litematicaAvailable = true;
                Debug.logMessage("Litematica integration enabled (modern version)");
            } catch (ClassNotFoundException e) {
                // Fall back to legacy package structure (MC 1.13.x and below)
                dataManagerClass = Class.forName("litematica.data.DataManager");
                placementManagerClass = Class.forName("litematica.schematic.placement.SchematicPlacementManager");
                schematicPlacementClass = Class.forName("litematica.schematic.placement.SchematicPlacement");
                materialListBaseClass = Class.forName("litematica.materials.MaterialListBase");
                materialListEntryClass = Class.forName("litematica.materials.MaterialListEntry");
                itemTypeClass = Class.forName("malilib.util.data.ItemType");
                litematicaAvailable = true;
                Debug.logMessage("Litematica integration enabled (legacy version)");
            }
        } catch (ClassNotFoundException e) {
            litematicaAvailable = false;
            Debug.logMessage("Litematica not found, schematic staging features disabled");
        }
        
        return litematicaAvailable;
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
            
            // placementManager.getSelectedPlacement()
            Method getSelectedMethod = placementManagerClass.getMethod("getSelectedPlacement");
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
            Object malilibBlockPos = getOriginMethod.invoke(placementObj);
            
            // Convert malilib BlockPos to Minecraft BlockPos
            Method getXMethod = malilibBlockPos.getClass().getMethod("getX");
            Method getYMethod = malilibBlockPos.getClass().getMethod("getY");
            Method getZMethod = malilibBlockPos.getClass().getMethod("getZ");
            int x = (int) getXMethod.invoke(malilibBlockPos);
            int y = (int) getYMethod.invoke(malilibBlockPos);
            int z = (int) getZMethod.invoke(malilibBlockPos);
            BlockPos origin = new BlockPos(x, y, z);
            
            // Get material list
            Method getMaterialListMethod = schematicPlacementClass.getMethod("getMaterialList");
            Object materialList = getMaterialListMethod.invoke(placementObj);
            
            // Get all materials from the list
            Method getAllMaterialsMethod = materialListBaseClass.getMethod("getAllMaterials");
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
