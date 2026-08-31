package net.changedcreator.load;

import com.google.gson.annotations.SerializedName;

/**
 * JSON schema for a custom latex form definition
 * ({@code config/changedcreator/forms/*.json}).
 *
 * NOTE (Changed v0.15.7 API): faction / stepSize / jumpStrength are NOT variant
 * properties in this version — they are inherited from the base entity.
 * Customizable variant properties: breathing, climbing, vision, gliding, jumping,
 * item usage, legs, camera offset, transfur mode, abilities, sound.
 *
 * Example:
 * <pre>{@code
 * {
 *   "id": "red_wolf",
 *   "base_entity": "changed:dark_latex_wolf_male",
 *   "transfur_mode": "replication",
 *   "properties": { "gills": true, "canClimb": true, "nightVision": true },
 *   "abilities": ["changed:toggle_night_vision"]
 * }
 * }</pre>
 */
public class LatexFormDefinition {
    /** Variant id (namespace = changedcreator), e.g. "red_wolf" -> changedcreator:red_wolf. */
    public String id;

    /** ResourceLocation of an existing Changed latex entity to reuse, e.g. "changed:dark_latex_wolf_male". */
    @SerializedName("base_entity")
    public String baseEntity;

    /** Optional transfur mode: "replication" | "absorption" | "none". */
    @SerializedName("transfur_mode")
    public String transfurMode;

    /** Optional list of ability ids, e.g. ["changed:toggle_night_vision"]. */
    public String[] abilities;

    /** Optional gameplay properties. */
    public Properties properties;

    public static class Properties {
        @SerializedName("gills")
        public Boolean gills;
        @SerializedName(value = "can_climb", alternate = {"canClimb"})
        public Boolean canClimb;
        @SerializedName(value = "night_vision", alternate = {"nightVision"})
        public Boolean nightVision;
        @SerializedName(value = "reduced_fall", alternate = {"reducedFall"})
        public Boolean reducedFall;
        @SerializedName("glide")
        public Boolean glide;
        @SerializedName(value = "double_jump", alternate = {"doubleJump"})
        public Boolean doubleJump;
        @SerializedName(value = "extra_jumps", alternate = {"extraJumps"})
        public Integer extraJumps;
        @SerializedName("quadrupedal")
        public Boolean quadrupedal;
        @SerializedName(value = "no_legs", alternate = {"noLegs"})
        public Boolean noLegs;
        @SerializedName(value = "disable_items", alternate = {"disableItems"})
        public Boolean disableItems;
        @SerializedName(value = "hold_items_in_mouth", alternate = {"holdItemsInMouth"})
        public Boolean holdItemsInMouth;
        @SerializedName(value = "camera_z_offset", alternate = {"cameraZOffset"})
        public Float cameraZOffset;
        /** Optional transfur sound id, e.g. "changed:poison". */
        @SerializedName("sound")
        public String sound;
    }
}
