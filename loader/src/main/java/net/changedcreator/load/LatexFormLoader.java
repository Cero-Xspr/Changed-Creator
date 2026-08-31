package net.changedcreator.load;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import net.changedcreator.ChangedCreator;
import net.ltxprogrammer.changed.entity.ChangedEntity;
import net.ltxprogrammer.changed.entity.TransfurMode;
import net.ltxprogrammer.changed.entity.variant.TransfurVariant;
import net.ltxprogrammer.changed.init.ChangedRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Loads custom latex-form (transfur variant) definitions from
 * {@code <minecraft-dir>/config/changedcreator/forms/*.json} and registers each of them
 * as a {@link TransfurVariant} on the changed:latex_variant registry.
 *
 * No Java code is required from the user: every variant reuses an already-registered
 * Changed entity (model/texture/renderer), while all gameplay properties come from the JSON.
 */
public class LatexFormLoader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setLenient().create();

    public static final DeferredRegister<TransfurVariant<?>> REGISTRY =
            ChangedRegistry.TRANSFUR_VARIANT.createDeferred(ChangedCreator.MODID);

    /** Ids of all successfully registered forms (for post-setup verification). */
    private static final java.util.List<String> registeredIds = new java.util.ArrayList<>();

    /** Ids already claimed by another form file (duplicate detection). */
    private static final java.util.Set<String> seenIds = new java.util.HashSet<>();

    /** Valid registry path: lowercase letters, digits, underscore; must not start with a digit. */
    private static final java.util.regex.Pattern ID_PATTERN =
            java.util.regex.Pattern.compile("[a-z][a-z0-9_]{0,63}");

    private static final Map<String, TransfurMode> TRANSFUR_MODES = new HashMap<>();
    static {
        TRANSFUR_MODES.put("replication", TransfurMode.REPLICATION);
        TRANSFUR_MODES.put("absorption", TransfurMode.ABSORPTION);
        TRANSFUR_MODES.put("none", TransfurMode.NONE);
    }

    public static void registerRegistry(IEventBus modEventBus) {
        REGISTRY.register(modEventBus);
    }

    /** Called from {@link net.changedcreator.ChangedCreator} after common setup completes. */
    public static void verifyRegisteredForms() {
        var registry = ChangedRegistry.TRANSFUR_VARIANT.get();
        for (String id : registeredIds) {
            ResourceLocation key = ResourceLocation.fromNamespaceAndPath(ChangedCreator.MODID, id);
            LOGGER.info("VERIFY: transfur variant {} present in registry = {}", key, registry.containsKey(key));
        }
    }

    public static void loadFromConfig() {
        Path formsDir = FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("forms");
        if (!Files.isDirectory(formsDir)) {
            LOGGER.info("No custom latex forms directory at {}; creating it.", formsDir);
            try {
                Files.createDirectories(formsDir);
            } catch (IOException e) {
                LOGGER.error("Failed to create forms directory {}", formsDir, e);
            }
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(formsDir, "*.json")) {
            for (Path file : stream) {
                loadOne(file);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan forms directory {}", formsDir, e);
        }
    }

    private static void loadOne(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            LatexFormDefinition def = GSON.fromJson(reader, LatexFormDefinition.class);
            if (def == null || def.id == null || def.id.isBlank()) {
                LOGGER.error("Skipping {}: missing or blank \"id\"", file);
                return;
            }
            if (!ID_PATTERN.matcher(def.id).matches()) {
                LOGGER.error("Skipping {}: invalid id '{}' (must match [a-z][a-z0-9_]{{0,63}})", file, def.id);
                return;
            }
            if (!seenIds.add(def.id)) {
                LOGGER.error("Skipping {}: duplicate id '{}' already used by another form file", file, def.id);
                return;
            }
            if (def.baseEntity == null || def.baseEntity.isBlank()) {
                LOGGER.error("Skipping {}: missing or blank \"base_entity\"", file);
                return;
            }

            registerVariant(def);
            registeredIds.add(def.id);
            LOGGER.info("Registered custom latex form {} -> base {}", def.id, def.baseEntity);
        } catch (JsonSyntaxException e) {
            LOGGER.error("Invalid JSON in {}: {}", file, e.getMessage());
        } catch (IOException e) {
            LOGGER.error("Failed to read {}", file, e);
        } catch (RuntimeException e) {
            // Never let a single malformed file take down the whole game startup.
            LOGGER.error("Failed to load form from {}: {}", file, e.getMessage());
        }
    }

    public static void registerVariant(LatexFormDefinition def) {
        REGISTRY.register(def.id, () -> buildVariant(def));
    }

    /** Builds a TransfurVariant for the given definition (reused by startup registration AND hot-register). */
    public static TransfurVariant<?> buildVariant(LatexFormDefinition def) {
        ResourceLocation baseId = ResourceLocation.tryParse(def.baseEntity);
        if (baseId == null) {
            throw new IllegalStateException("Invalid base_entity id '" + def.baseEntity + "' for form " + def.id);
        }
        net.minecraft.world.entity.EntityType<?> rawType = ForgeRegistries.ENTITY_TYPES.getValue(baseId);
        if (rawType == null) {
            throw new IllegalStateException("base_entity '" + def.baseEntity + "' is not a registered entity type");
        }
        // Sanity check: is this entity used by any existing transfur variant?
        // (getBaseClass() is unreliable here: the shaded Changed jar erases the
        // generic signature, so it only reports net.minecraft.world.entity.Entity.)
        boolean knownLatex = TransfurVariant.getPublicTransfurVariants()
                .anyMatch(v -> v.ctor.get() == rawType);
        if (!knownLatex) {
            LOGGER.warn("base_entity '{}' is not used by any existing transfur variant; " +
                    "if it is not a Changed latex entity the form may misbehave", def.baseEntity);
        }

        @SuppressWarnings("unchecked")
        net.minecraft.world.entity.EntityType<? extends ChangedEntity> type =
                (net.minecraft.world.entity.EntityType<? extends ChangedEntity>) rawType;

        @SuppressWarnings("unchecked")
        TransfurVariant.Builder<ChangedEntity> builder = TransfurVariant.Builder
                .of(() -> (net.minecraft.world.entity.EntityType<ChangedEntity>) (net.minecraft.world.entity.EntityType<?>) type);
        applyProperties(builder, def);
        return builder.build();
    }

    private static void applyProperties(TransfurVariant.Builder<ChangedEntity> builder, LatexFormDefinition def) {
        var p = def.properties;
        if (p != null) {
            if (p.gills != null) builder.gills(p.gills);
            if (p.canClimb != null) builder.canClimb(p.canClimb);
            if (p.nightVision != null) builder.nightVision(p.nightVision);
            if (p.reducedFall != null) builder.reducedFall(p.reducedFall);
            if (p.glide != null) builder.glide(p.glide);
            if (p.doubleJump != null && p.doubleJump) builder.doubleJump();
            if (p.extraJumps != null) builder.extraJumps(p.extraJumps);
            if (p.quadrupedal != null && p.quadrupedal) builder.quadrupedal();
            if (p.noLegs != null && p.noLegs) builder.noLegs();
            if (p.disableItems != null && p.disableItems) builder.disableItems();
            if (p.holdItemsInMouth != null && p.holdItemsInMouth) builder.holdItemsInMouth();
            if (p.cameraZOffset != null) builder.cameraZOffset(p.cameraZOffset);
            if (p.sound != null) {
                ResourceLocation sound = ResourceLocation.tryParse(p.sound);
                if (sound == null) {
                    LOGGER.warn("Invalid sound id '{}' for form {}", p.sound, def.id);
                } else {
                    builder.sound(sound);
                }
            }
        }

        if (def.transfurMode != null) {
            TransfurMode mode = TRANSFUR_MODES.get(def.transfurMode.toLowerCase(Locale.ROOT));
            if (mode == null) {
                LOGGER.warn("Unknown transfur_mode '{}' for form {}; ignoring (allowed: {})",
                        def.transfurMode, def.id, TRANSFUR_MODES.keySet());
            } else {
                builder.transfurMode(mode);
            }
        }

        if (def.abilities != null) {
            for (String ability : def.abilities) {
                ResourceLocation id = ResourceLocation.tryParse(ability);
                if (id == null) {
                    LOGGER.warn("Invalid ability id '{}' for form {}", ability, def.id);
                    continue;
                }
                // Resolve lazily (ability registry is only populated during registration),
                // but fail with a clear message instead of a bare NPE if the id is unknown.
                builder.addAbility(() -> {
                    var abilityInstance = ChangedRegistry.ABILITY.getValue(id);
                    if (abilityInstance == null) {
                        throw new IllegalStateException("Unknown ability '" + ability + "' for form " + def.id);
                    }
                    return abilityInstance;
                });
            }
        }
    }
}
