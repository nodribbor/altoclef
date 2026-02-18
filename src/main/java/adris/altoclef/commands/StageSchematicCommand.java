package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.construction.StageSchematicResourcesTask;
import adris.altoclef.util.LitematicaHelper;

import java.util.Optional;

/**
 * Command to stage resources for a Litematica schematic placement
 */
public class StageSchematicCommand extends Command {
    
    public StageSchematicCommand() throws CommandException {
        super("stageSchematic", 
              "Stage all required resources for the currently selected Litematica schematic placement into nearby chests",
              new String[]{"litematicaStage", "schematicStage"});
    }
    
    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        // Check if Litematica is loaded
        if (!LitematicaHelper.isLitematicaLoaded()) {
            mod.log("Litematica mod is not loaded! Please install Litematica to use this command.");
            finish();
            return;
        }
        
        // Get the currently selected placement
        Optional<LitematicaHelper.SchematicPlacementInfo> infoOpt = 
            LitematicaHelper.getSelectedPlacementInfo();
        
        if (infoOpt.isEmpty()) {
            mod.log("No schematic placement is currently selected in Litematica!");
            mod.log("Please select a placement in Litematica first.");
            finish();
            return;
        }
        
        LitematicaHelper.SchematicPlacementInfo info = infoOpt.get();
        
        // Display schematic info
        mod.log("========================================");
        mod.log("Staging resources for schematic: " + info.getName());
        mod.log("Location: " + info.getOrigin());
        mod.log("Unique item types: " + info.getTotalUniqueItems());
        mod.log("Total items required: " + info.getTotalItemCount());
        mod.log("========================================");
        
        // Display some of the materials
        int displayCount = Math.min(10, info.getMaterials().size());
        mod.log("Required materials (showing first " + displayCount + "):");
        for (int i = 0; i < displayCount; i++) {
            LitematicaHelper.MaterialRequirement req = info.getMaterials().get(i);
            mod.log("  - " + req.getItemStack().getName().getString() + " x" + req.getTotalCount());
        }
        
        if (info.getMaterials().size() > displayCount) {
            mod.log("  ... and " + (info.getMaterials().size() - displayCount) + " more types");
        }
        
        mod.log("========================================");
        mod.log("Starting resource staging process...");
        mod.log("The bot will:");
        mod.log("1. Prepare necessary tools (pickaxes, axes, shield, food)");
        mod.log("2. Find or place storage chests near the build site");
        mod.log("3. Gather all required materials");
        mod.log("4. Store materials in the staging chests");
        mod.log("========================================");
        
        // Start the task
        mod.runUserTask(new StageSchematicResourcesTask(), this::finish);
    }
}
