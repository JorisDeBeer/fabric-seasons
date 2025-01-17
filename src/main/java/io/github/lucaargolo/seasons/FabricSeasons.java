package io.github.lucaargolo.seasons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import io.github.lucaargolo.seasons.commands.SeasonCommand;
import io.github.lucaargolo.seasons.mixed.BiomeMixed;
import io.github.lucaargolo.seasons.mixin.WeatherAccessor;
import io.github.lucaargolo.seasons.resources.CropConfigs;
import io.github.lucaargolo.seasons.utils.*;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.resource.ResourceType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.tag.BiomeTags;
import net.minecraft.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryEntry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class FabricSeasons implements ModInitializer {

    //TODO: Add Extras items/blocks (fallen leaves, greenhouse variants, patchouli book)
    //TODO: Fix calendar design

    //TODO: Update terralith compat
    //TODO: Traverse Resource Pack
    //TODO: Terrestria Resource Pack

    //TODO: Vanilla Crops Datapack
    //TODO: Croptopia Data Pack
    //TODO: Farmer's Delight Data Pack

    //TODO: Create compat pages/readme
    //TODO: Create extras page/readme
    //TODO: Update mod page/readme
    //TODO: Set up maven publish for addons


    private static final LongArraySet temporaryMeltableCache = new LongArraySet();
    public static final String MOD_ID = "seasons";
    public static final String MOD_NAME = "Fabric Seasons";

    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    public static ModConfig CONFIG;

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static HashMap<Item, Block> SEEDS_MAP = new HashMap<>();

    public static Identifier ASK_FOR_CONFIG = new ModIdentifier("ask_for_config");
    public static Identifier ANSWER_CONFIG = new ModIdentifier("anwer_config");

    public static Identifier UPDATE_CROPS = new ModIdentifier("update_crops");


    @Override
    public void onInitialize() {

        Path configPath = FabricLoader.getInstance().getConfigDir();
        File configFile = new File(configPath + File.separator + "seasons.json");

        LOGGER.info("["+MOD_NAME+"] Trying to read config file...");
        try {
            if (configFile.createNewFile()) {
                LOGGER.info("["+MOD_NAME+"] No config file found, creating a new one...");
                String json = GSON.toJson(JsonParser.parseString(GSON.toJson(new ModConfig())));
                try (PrintWriter out = new PrintWriter(configFile)) {
                    out.println(json);
                }
                CONFIG = new ModConfig();
                LOGGER.info("["+MOD_NAME+"] Successfully created default config file.");
            } else {
                LOGGER.info("["+MOD_NAME+"] A config file was found, loading it..");
                CONFIG = GSON.fromJson(new String(Files.readAllBytes(configFile.toPath())), ModConfig.class);
                if(CONFIG == null) {
                    throw new NullPointerException("["+MOD_NAME+"] The config file was empty.");
                }else{
                    LOGGER.info("["+MOD_NAME+"] Successfully loaded config file.");
                }
            }
        }catch (Exception exception) {
            LOGGER.error("["+MOD_NAME+"] There was an error creating/loading the config file!", exception);
            CONFIG = new ModConfig();
            LOGGER.warn("["+MOD_NAME+"] Defaulting to original config.");
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated, ignored) -> SeasonCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SEEDS_MAP.clear();
            Registry.ITEM.forEach(item -> {
                if(item instanceof BlockItem) {
                    Block block = ((BlockItem) item).getBlock();
                    if(block instanceof SeasonalFertilizable) {
                        FabricSeasons.SEEDS_MAP.put(item, ((BlockItem) item).getBlock());
                    }
                }
            });
        });

        ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register((player, joined) -> {
            PacketByteBuf buf = PacketByteBufs.create();
            CropConfigs.getDefaultCropConfig().toBuf(buf);
            CropConfigs.toBuf(buf);
            ServerPlayNetworking.send(player, UPDATE_CROPS, buf);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            GreenhouseCache.tick(server);
            temporaryMeltableCache.clear();
        });

        ServerPlayNetworking.registerGlobalReceiver(ASK_FOR_CONFIG, (server, player, handler, buf, responseSender) -> {
            String configJson = GSON.toJson(JsonParser.parseString(GSON.toJson(CONFIG)));
            PacketByteBuf configBuf = PacketByteBufs.create();
            configBuf.writeString(configJson);
            ServerPlayNetworking.send(player, ANSWER_CONFIG, configBuf);
        });

        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new CropConfigs());
    }

    public static void setMeltable(BlockPos blockPos) {
        temporaryMeltableCache.add(blockPos.asLong());
    }

    public static boolean isMeltable(BlockPos blockPos) {
        return temporaryMeltableCache.contains(blockPos.asLong());
    }

    public static PlacedMeltablesState getPlacedMeltablesState(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(PlacedMeltablesState::createFromNbt, PlacedMeltablesState::new, "seasons_placed_meltables");
    }

    public static ReplacedMeltablesState getReplacedMeltablesState(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(ReplacedMeltablesState::createFromNbt, ReplacedMeltablesState::new, "seasons_replaced_meltables");
    }

    public static Season getCurrentSeason(World world) {
        RegistryKey<World> dimension = world.getRegistryKey();
        if (CONFIG.isValidInDimension(dimension)) {
            if(CONFIG.isSeasonLocked()) {
                return CONFIG.getLockedSeason();
            }
            if(CONFIG.isSeasonTiedWithSystemTime()) {
                return getCurrentSystemSeason();
            }
            long worldTime = world.getTimeOfDay();
            long seasonTime = (worldTime / CONFIG.getSeasonLength());
            return Season.values()[(int) (seasonTime % 4)];
        }
        return Season.SPRING;
    }

    @Environment(EnvType.CLIENT)
    public static Season getCurrentSeason() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if(player != null && player.world != null) {
            return getCurrentSeason(player.world);
        }
        return Season.SPRING;
    }

    private static Season getCurrentSystemSeason() {
        LocalDateTime date = LocalDateTime.now();
        int m = date.getMonthValue();
        int d = date.getDayOfMonth();
        Season season;

        if (CONFIG.isInNorthHemisphere()) {
            if (m == 1 || m == 2 || m == 3)
                season = Season.WINTER;
            else if (m == 4 || m == 5 || m == 6)
                season = Season.SPRING;
            else if (m == 7 || m == 8 || m == 9)
                season = Season.SUMMER;
            else
                season = Season.FALL;

            if (m == 3 && d > 19)
                season = Season.SPRING;
            else if (m == 6 && d > 20)
                season = Season.SUMMER;
            else if (m == 9 && d > 21)
                season = Season.FALL;
            else if (m == 12 && d > 20)
                season = Season.WINTER;
        } else {
            if (m == 1 || m == 2 || m == 3)
                season = Season.SUMMER;
            else if (m == 4 || m == 5 || m == 6)
                season = Season.FALL;
            else if (m == 7 || m == 8 || m == 9)
                season = Season.WINTER;
            else
                season = Season.SPRING;

            if (m == 3 && d > 19)
                season = Season.FALL;
            else if (m == 6 && d > 20)
                season = Season.WINTER;
            else if (m == 9 && d > 21)
                season = Season.SPRING;
            else if (m == 12 && d > 20)
                season = Season.SUMMER;
        }

        return season;
    }

    @SuppressWarnings("ConstantValue")
    public static void injectBiomeTemperature(RegistryEntry<Biome> entry, World world) {
        if(!CONFIG.doTemperatureChanges()) return;

        List<TagKey<Biome>> ignoredCategories = Arrays.asList(BiomeTags.IS_NETHER, BiomeTags.IS_END, BiomeTags.IS_OCEAN);
        if(ignoredCategories.stream().anyMatch(entry::isIn)) return;

        Season season = FabricSeasons.getCurrentSeason(world);

        Biome biome = entry.value();
        Biome.Weather currentWeather = biome.weather;
        Biome.Weather originalWeather = ((BiomeMixed) (Object) biome).getOriginalWeather();
        if (originalWeather == null) {
            originalWeather = new Biome.Weather(currentWeather.precipitation, currentWeather.temperature, currentWeather.temperatureModifier, currentWeather.downfall);
            ((BiomeMixed) (Object) biome).setOriginalWeather(originalWeather);
        }
        WeatherAccessor weatherAccessor = ((WeatherAccessor) (Object) currentWeather);
        assert weatherAccessor != null;

        boolean isJungle = entry.isIn(BiomeTags.IS_JUNGLE) || entry.isIn(BiomeTags.HAS_CLOSER_WATER_FOG);
        Pair<Biome.Precipitation, Float> modifiedWeather = getSeasonWeather(season, isJungle, originalWeather.precipitation, originalWeather.temperature);
        weatherAccessor.setPrecipitation(modifiedWeather.getLeft());
        weatherAccessor.setTemperature(modifiedWeather.getRight());
    }

    public static Pair<Biome.Precipitation, Float> getSeasonWeather(Season season, boolean jungle, Biome.Precipitation precipitation, float temp) {
        if(jungle) {
            //Jungle Biomes
            if (season == Season.WINTER) {
                return new Pair<>(precipitation, temp-0.1f);
            } else {
                return new Pair<>(precipitation, temp);
            }
        }else if(temp <= 0.1) {
            //Frozen Biomes
            switch (season) {
                case SUMMER -> {
                    return new Pair<>(Biome.Precipitation.RAIN, temp + 0.3f);
                }
                case WINTER -> {
                    return new Pair<>(Biome.Precipitation.SNOW, temp - 0.2f);
                }
                default -> {
                    return new Pair<>(precipitation, temp);
                }
            }
        }else if(temp <= 0.3) {
            //Cold Biomes
            switch (season) {
                case SPRING -> {
                    return new Pair<>(Biome.Precipitation.RAIN, temp);
                }
                case SUMMER -> {
                    return new Pair<>(Biome.Precipitation.RAIN, temp + 0.2f);
                }
                case WINTER -> {
                    return new Pair<>(Biome.Precipitation.SNOW, temp - 0.2f);
                }
                default -> {
                    return new Pair<>(precipitation, temp);
                }
            }
        }else if(temp <= 0.95) {
            //Temperate Biomes
            switch (season) {
                case SUMMER -> {
                    return new Pair<>(precipitation, temp + 0.2f);
                }
                case FALL -> {
                    return new Pair<>(precipitation, temp - 0.1f);
                }
                case WINTER -> {
                    return new Pair<>(Biome.Precipitation.SNOW, temp - 0.7f);
                }
                default -> {
                    return new Pair<>(precipitation, temp);
                }
            }
        }else{
            //Hot biomes
            switch (season) {
                case SUMMER -> {
                    return new Pair<>(precipitation, temp + 0.2f);
                }
                case WINTER -> {
                    return new Pair<>(Biome.Precipitation.RAIN, temp - 0.2f);
                }
                default -> {
                    return new Pair<>(precipitation, temp);
                }
            }
        }
    }

}
