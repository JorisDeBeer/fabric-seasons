package io.github.lucaargolo.seasons.utils;

import io.github.lucaargolo.seasons.FabricSeasons;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.nbt.*;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.registry.Registry;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;

import static io.github.lucaargolo.seasons.FabricSeasons.MOD_NAME;

public class CompatWarnState {

    private final HashMap<String, ModInfo> compatibilityMap = new HashMap<>();

    private final HashSet<String> biomeNamespaces = new HashSet<>();
    private final HashSet<String> warnedIds = new HashSet<>();


    private final MinecraftClient client;

    private CompatWarnState(MinecraftClient client) {
        this.client = client;
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if(handler != null) {
            biomeNamespaces.clear();
            handler.getRegistryManager().get(Registry.BIOME_KEY).getIndexedEntries().forEach(entry -> {
                entry.getKey().ifPresent(key -> biomeNamespaces.add(key.getValue().getNamespace()));
            });
        }
        compatibilityMap.put("byg", new ModInfo("seasonsbygcompat", "fabric-seasons-byg-url", "Oh The Biomes You'll Go"));
        compatibilityMap.put("terralith", new ModInfo("seasonsterralithcompat", "fabric-seasons-terralith-url", "Terralith"));
    }
    
    private void saveState() {
        NbtCompound nbt = new NbtCompound();
        NbtList list = new NbtList();
        warnedIds.forEach(s -> list.add(NbtString.of(s)));
        nbt.put("list", list);
        File compatWarnFile = new File(MinecraftClient.getInstance().runDirectory, File.separator+"data"+File.separator+"seasons_compat_warn.nbt");
        try {
            NbtIo.writeCompressed(nbt, compatWarnFile);
        } catch (IOException e) {
            FabricSeasons.LOGGER.error("["+MOD_NAME+"] Failed to save season url warn state.", e);
        }
    }

    public static CompatWarnState getState(MinecraftClient client) {
        CompatWarnState state = new CompatWarnState(client);
        File compatWarnFile = new File(MinecraftClient.getInstance().runDirectory, File.separator+"data"+File.separator+"seasons_compat_warn.nbt");
        NbtCompound nbt;
        try {
            nbt = NbtIo.readCompressed(compatWarnFile);
        } catch (Exception e) {
            nbt = new NbtCompound();
        }
        NbtElement element = nbt.get("list");
        if(element instanceof NbtList list) {
            list.forEach(listElement -> {
                if(listElement instanceof NbtString listString) {
                    state.warnedIds.add(listString.asString());
                }
            });
        }
        return state;
    }

    public void join() {
        biomeNamespaces.forEach(namespace -> {
            ModInfo info = compatibilityMap.get(namespace);
            if(info != null && !warnedIds.contains(namespace) && !FabricLoader.getInstance().isModLoaded(info.id)) {
                ClientPlayerEntity player = client.player;
                if(player != null) {

                    MutableText first = Text.literal("\n").append(Text.translatable("chat.seasons.mod_installed", Text.literal(info.name).formatted(Formatting.GREEN)).formatted(Formatting.YELLOW));
                    MutableText second = Text.literal(("\n§e"+Text.translatable("chat.seasons.compatibility").getString()).replace("Fabric Seasons", "§aFabric Seasons§e")+"\n");
                    MutableText third = Text.literal("§e"+Text.translatable("chat.seasons.available_at").getString());
                    MutableText curse = Text.literal("§6§nCurseForge§r ").styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://www.curseforge.com/minecraft/mc-mods/"+info.url())));
                    MutableText modrinth = Text.literal("§2§nModrinth§r ").styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://modrinth.com/mod/"+info.url())));
                    MutableText github = Text.literal("§5§nGitHub§r\n").styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://github.com/lucaargolo/"+info.url()+"/releases")));
                    MutableText fourth = Text.literal("§e"+Text.translatable("chat.seasons.show_once").getString()+"\n");
                    player.sendMessage(first.append(second).append(third).append(curse).append(modrinth).append(github).append(fourth));
                }
                warnedIds.add(namespace);
                saveState();
            }
        });
    }

    public record ModInfo(String id, String url, String name) {

    }

}
