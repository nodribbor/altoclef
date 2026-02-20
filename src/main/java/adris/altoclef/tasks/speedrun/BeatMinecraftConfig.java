package adris.altoclef.tasks.speedrun;

public class BeatMinecraftConfig {
    public int targetEyes = 15; // how many eyes of ender to collect
    public int minimumEyes = 13; // the MINIMUM amount of eyes of ender to have, assuming we don't have our stronghold portal opened yet
    public boolean placeSpawnNearEndPortal = false;
    public boolean barterPearlsInsteadOfEndermanHunt;
    public boolean sleepThroughNight = true;
    public boolean rePickupCraftingTable = true;
    public int foodUnits = 300;
    public int requiredBeds = 12;
    public int minBuildMaterialCount = 5;
    public int buildMaterialCount = 64;
    public boolean rePickupSmoker = true;
    public boolean rePickupFurnace = true;
    public int panicHealthThreshold = 6; // drop everything and survive when health is at or below this
    public int retreatHealthThreshold = 12; // stop fighting and heal when health is at or below this
}
