# Shears Implementation in AltoClef

## Overview

AltoClef now has comprehensive support for all items that require shears in Minecraft. The bot will automatically obtain shears when attempting to collect any shearable block or entity.

## How It Works

### Task Registration System

Items requiring shears are registered in `TaskCatalogue.java` using the `shear()` method:

```java
shear("cobweb", Blocks.COBWEB, Items.COBWEB).dontMineIfPresent();
shear("seagrass", Blocks.SEAGRASS, Items.SEAGRASS).dontMineIfPresent();
// ... etc
```

This creates a `ShearAndCollectBlockTask` that:
1. Checks if shears are in inventory
2. If not, recursively calls `TaskCatalogue.getItemTask(Items.SHEARS, 1)` to obtain shears
3. Forces shears to be equipped using `BotBehaviour.forceUseTool()`
4. Collects the target blocks

### Shearable Blocks Currently Registered

#### Plant Life
- **Grass & Ferns**: short_grass, tall_grass, fern, large_fern
- **Aquatic Plants**: seagrass, tall_seagrass, kelp, lily_pad
- **Vines**: vine, weeping_vines, twisting_vines, cave_vines (via age setting)
- **Roots & Sprouts**: hanging_roots, nether_sprouts
- **Other**: dead_bush, small_dripleaf

#### Blocks
- **Cobweb**: Guaranteed drop (vs destroyed when mined)
- **Leaves**: All tree types (oak, birch, spruce, etc.)
- **Glow Lichen**: Decorative light source

### Special Cases

#### Nether Vines (weeping_vines, twisting_vines)
These were changed from `mine()` to `shear()` because:
- **With shears**: 100% drop rate guaranteed
- **Without shears**: Only 33% drop chance when broken
- Both forced to Nether dimension

#### Carved Pumpkin
Uses `CarveThenCollectTask` which:
1. Gets shears if not in inventory
2. Places pumpkins if needed
3. Uses shears to carve them
4. Collects the carved pumpkins

#### Honeycomb
Uses `CollectHoneycombTask` which:
1. Gets shears if not in inventory
2. Optionally places campfire below nest for safety
3. Waits for honey level to reach 5
4. Uses shears on beehive/bee nest
5. Collects 3 honeycombs per interaction

#### Wool from Sheep
Uses `ShearSheepTask` which:
1. Finds unsheared sheep
2. Gets shears if not in inventory
3. Equips shears
4. Interacts with sheep to collect 1-3 wool

### Entity Shearing (Not Implemented)

These would require new entity tasks (similar to ShearSheepTask):
- **Mooshrooms**: Shearing drops 5 mushrooms, converts to cow
- **Snow Golems**: Shearing removes pumpkin head
- **Bogged**: Shearing drops 2 mushrooms (red/brown/mixed)
- **Copper Golems**: Shearing drops poppy flower

These are not currently implemented because:
1. Entity-based collection is more complex than block-based
2. These mobs are rarely targeted for farming
3. Alternative methods exist (mining mushroom blocks, etc.)

## Technical Details

### ShearAndCollectBlockTask

Extends `MineAndCollectTask` with these modifications:
- Uses `MiningRequirement.HAND` (shears don't need mining level)
- Registers tool forcing predicate: `ItemHelper.areShearsEffective(blockState.getBlock())`
- Automatically fetches shears via recursive task system

### BotBehaviour Tool Forcing

The `forceUseTool()` mechanism allows tasks to register predicates:
```java
mod.getBehaviour().forceUseTool((blockState, itemStack) -> 
    itemStack.getItem() == Items.SHEARS && 
    ItemHelper.areShearsEffective(blockState.getBlock())
);
```

This ensures shears are used even if Baritone would prefer a different tool.

### ItemHelper.areShearsEffective()

Located in `ItemHelper.java`, this method returns true for blocks where shears provide benefits:
- Faster breaking speed
- Guaranteed drops (cobwebs, leaves, etc.)
- Special interactions (pumpkin carving)

## Usage Examples

### Getting Cobwebs
```
@get cobweb 5
```
Bot will:
1. Check if it has shears → if not, craft/find shears first
2. Locate cobwebs (usually in mineshafts)
3. Equip shears
4. Break cobwebs with shears (guaranteed drop)
5. Collect cobweb items

### Getting Honeycombs
```
@get honeycomb 10
```
Bot will:
1. Check if it has shears → if not, craft/find shears
2. Get campfire for safety (optional)
3. Find bee nest
4. Place campfire below nest
5. Wait for honey level = 5
6. Shear nest to get 3 honeycombs
7. Repeat until target reached

### Getting Wool
```
@get wool 64
```
Bot will:
1. Check if it has shears → if not, craft/find shears
2. Find unsheared sheep
3. Equip shears
4. Shear each sheep (1-3 wool per sheep)
5. Repeat until target reached

## Performance Notes

### Drop Rate Improvements
Using shears provides significant efficiency gains:
- **Nether vines**: 100% drop (vs 33% when mined)
- **Cobwebs**: 100% drop (vs 0% destroyed when mined)
- **Leaves**: Faster collection, guaranteed drops
- **Honeycombs**: Only obtainable with shears

### Automatic Tool Management
The bot handles shears like any other tool:
- Crafts if needed (2 iron ingots → 1 shears)
- Tracks durability (238 uses)
- Keeps in inventory during collection tasks
- Doesn't accidentally deposit into chests during collection

## Future Enhancements

### Potential Additions
1. **Entity Shearing Tasks**: Mooshroom, snow golem, bogged shearing
2. **Durability Monitoring**: Replace shears before they break
3. **Multi-Tool Tasks**: Some tasks could use shears OR another tool
4. **Enchantment Support**: Efficiency enchanted shears for faster collection

### Known Limitations
1. Entity-based shearing (mooshrooms, etc.) not implemented
2. No automatic shears replacement when low durability
3. Cave vine age manipulation not implemented (stops growth feature)
4. Equipment removal (saddles, armor) not implemented

## References

### Key Files
- `src/main/java/adris/altoclef/TaskCatalogue.java` - Resource registration
- `src/main/java/adris/altoclef/tasks/resources/ShearAndCollectBlockTask.java` - Shearing implementation
- `src/main/java/adris/altoclef/tasks/entity/ShearSheepTask.java` - Sheep shearing
- `src/main/java/adris/altoclef/tasks/resources/CollectHoneycombTask.java` - Honeycomb collection
- `src/main/java/adris/altoclef/tasks/resources/CarveThenCollectTask.java` - Pumpkin carving
- `src/main/java/adris/altoclef/util/helpers/ItemHelper.java` - Shears effectiveness checks

### Related Systems
- **TaskCatalogue**: Central registry for all collectible resources
- **BotBehaviour**: Tool forcing mechanism
- **ResourceTask**: Base class for all collection tasks
- **MiningRequirement**: Tool tier system (shears use HAND tier)
