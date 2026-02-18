# Schematic Resource Staging Feature

## Overview

The Schematic Resource Staging feature allows AltoClef to automatically gather and stage all required building materials for a Litematica schematic placement. The bot will:

1. Read material requirements from your selected Litematica schematic
2. Prepare necessary tools (pickaxes, axes, shield, food)
3. Find or place storage chests near the build site
4. Gather all required materials
5. Store materials in the staging chests

## Requirements

- **Litematica mod** must be installed and loaded at runtime
- A schematic placement must be selected in Litematica before running the command

## Usage

### Command

```
@stageSchematic
```

**Aliases:** `@litematicaStage`, `@schematicStage`

### Workflow

1. Load Minecraft with both AltoClef and Litematica installed
2. Place a schematic in the world using Litematica (M menu → Load Schematics → Place)
3. Ensure the schematic placement is selected (it should be highlighted)
4. Run the `@stageSchematic` command in chat
5. The bot will display schematic info and begin the staging process

### Example Output

```
========================================
Staging resources for schematic: MyHouse
Location: BlockPos{x=100, y=64, z=200}
Unique item types: 15
Total items required: 2847
========================================
Required materials (showing first 10):
  - Oak Planks x640
  - Stone Bricks x892
  - Glass x256
  - Oak Log x128
  - Torch x64
  ... and 5 more types
========================================
Starting resource staging process...
The bot will:
1. Prepare necessary tools (pickaxes, axes, shield, food)
2. Find or place storage chests near the build site
3. Gather all required materials
4. Store materials in the staging chests
========================================
```

## Features

### Automatic Tool Preparation

The bot intelligently determines what tools it needs based on the materials:

- **Diamond Pickaxe**: For gathering large amounts of stone (>500 blocks) or ores
- **Iron Pickaxe**: For moderate stone gathering
- **Iron Axe**: For wood gathering
- **Shield**: Always equipped for defense
- **Food**: Maintains at least 20 food units

### Material Processing

The bot applies these transformations to material requirements:

- **Grass Block** → **Dirt**: Converts grass blocks to normal dirt
- **Special Dirt Types** (Podzol, Mycelium, Coarse Dirt) → **Dirt**: Converts to normal dirt
- **Water/Lava**: Ignored (not gathered)

### Chest Management

- Searches for existing chests within 15 blocks of the schematic origin
- Places new chests if needed (up to 10 chests)
- Uses proximity-based chest selection for deposits
- Estimates required chest count based on material volume

### Survival Integration

While staging resources, the bot maintains all survival behaviors:

- Eats food when hungry
- Defends against mobs
- Runs unstuck routines if blocked
- Can be interrupted and resumed

## Implementation Details

### Architecture

The feature consists of three main components:

1. **LitematicaHelper** (`util/LitematicaHelper.java`)
   - Uses reflection to integrate with Litematica (optional dependency)
   - Provides methods to query schematic placement data
   - Handles data type conversions

2. **StageSchematicResourcesTask** (`tasks/construction/StageSchematicResourcesTask.java`)
   - State machine with 6 phases: INIT → PREPARE_TOOLS → FIND_OR_PLACE_CHESTS → GATHER_MATERIALS → DEPOSIT_MATERIALS → COMPLETE
   - Orchestrates the entire staging process
   - Uses existing AltoClef task chains for gathering

3. **StageSchematicCommand** (`commands/StageSchematicCommand.java`)
   - User-facing command interface
   - Displays schematic information
   - Triggers the staging task

### Optional Dependency

Litematica is treated as an **optional runtime dependency** using reflection. This means:

- AltoClef can be built and run without Litematica
- If Litematica is not installed, the command will report it and fail gracefully
- No compile-time dependency on Litematica classes

### Performance Considerations

- Chest operations are optimized to avoid spam
- Gathering uses existing optimized AltoClef resource tasks
- Material list is processed once at initialization
- Progress is tracked to support interruption and resumption

## Limitations

### Current Limitations

1. **Chest Capacity**: Uses optimistic estimate assuming 64-item stacks; non-stackable items may require more chests
2. **Chest Fullness**: Does not check if a chest is full before depositing (will be handled by underlying task)
3. **Material Priority**: Gathers materials in order rather than by priority or difficulty
4. **Single Player Only**: Designed for single-player use

### Known Edge Cases

- Very large schematics (>10,000 blocks) may require manual chest placement
- Some modded items may not be recognized
- Schematic changes while staging is in progress are not detected

## Troubleshooting

### "Litematica mod is not loaded!"

**Solution**: Install Litematica mod for your Minecraft version

### "No schematic placement is currently selected!"

**Solution**: 
1. Open Litematica menu (M key by default)
2. Load a schematic
3. Place it in the world
4. Ensure it's selected (highlighted)

### Bot gets stuck during gathering

**Solution**:
1. Stop the task with `@stop`
2. Check if the material is obtainable in your world
3. Resume with `@stageSchematic` - it will continue from where it left off

### Not enough chests

**Solution**:
1. The bot will place up to 10 chests automatically
2. For very large schematics, you can manually place additional chests nearby
3. The bot will find and use them

## Future Enhancements

Potential improvements for future versions:

- [ ] Smart material prioritization (gather easy items first)
- [ ] Chest fullness detection before depositing
- [ ] Support for multiple simultaneous placements
- [ ] Progress percentage display
- [ ] Estimated time to completion
- [ ] Material availability pre-check (warn about unobtainable items)
- [ ] Ender chest support for valuable materials
- [ ] Shulker box packing for efficient storage

## Contributing

If you encounter issues or have suggestions:

1. Check existing GitHub issues
2. Create a new issue with:
   - Your Minecraft version
   - AltoClef version
   - Litematica version
   - Schematic details (size, material count)
   - Error messages or unexpected behavior

## Credits

- Feature implemented as part of AltoClef development
- Uses Litematica's material list API via reflection
- Integrates with existing AltoClef task and chain systems
