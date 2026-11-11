package dev.lull.emfmixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * EmfAdapter's carrier (only with the adapter on): Entity Model Features' and Entity Texture Features' own classes that
 * hold the localized statics, their LRU cache base and the classes keeping collections in fields - a mixin of nothing,
 * so that Mixin passes them through LateEventSites, where LocalizeTransformer rewrites them at the end of their
 * transformation (interfaces: EmfCarrierItfMixin). The list: the 1.20.1 jars' bytecode (EMF 3.3.12, ETF 7.2.7; scratch tool emfscan, notes/69).
 */
@Pseudo
@Mixin(targets = {
        "traben.entity_model_features.EMFManager",
        "traben.entity_model_features.models.animation.AnimSetupContext",
        "traben.entity_model_features.models.animation.math.EMFMath",
        "traben.entity_model_features.models.animation.math.EMFMath$1",
        "traben.entity_model_features.models.animation.math.asm.ASMVariableHandler",
        "traben.entity_model_features.models.animation.math.asm.ASMAnimationHandler",
        "traben.entity_model_features.models.animation.math.asm.MultiASMAnimationHandler",
        "traben.entity_model_features.models.animation.math.expression_tree.OldEMFAnimationHandler",
        "traben.entity_model_features.models.animation.math.methods.MethodRegistry",
        "traben.entity_model_features.models.animation.math.methods.emf.NBTMethod",
        "traben.entity_model_features.models.animation.math.variables.VariableRegistry",
        "traben.entity_model_features.models.animation.math.variables.factories.GlobalVariableFactory",
        "traben.entity_model_features.models.animation.state.EMFState",
        "traben.entity_model_features.models.animation.state.EMFState$EMFStateStaticSnapshot",
        "traben.entity_model_features.models.jem_objects.EMFJemData",
        "traben.entity_model_features.models.jem_objects.EMFPartData",
        "traben.entity_model_features.models.parts.EMFModelPart",
        "traben.entity_model_features.models.parts.EMFModelPartCustom",
        "traben.entity_model_features.models.parts.EMFModelPartCustom$EMFCube$1",
        "traben.entity_model_features.models.parts.EMFModelPartCustom$EMFCube$2",
        "traben.entity_model_features.models.parts.EMFModelPartRoot",
        "traben.entity_model_features.models.parts.EMFModelPartVanilla",
        "traben.entity_model_features.models.parts.EMFModelPartWithState",
        "traben.entity_model_features.models.parts.EMFModelPartWithState$1",
        "traben.entity_model_features.propeties.EntityVariableBooleanProperty",
        "traben.entity_model_features.propeties.GlobalVariableBooleanProperty",
        "traben.entity_model_features.utils.EMFAnimationPauseHandler",
        "traben.entity_model_features.utils.EMFLODHandler",
        "traben.entity_texture_features.ETF",
        "traben.entity_texture_features.features.ETFManager",
        "traben.entity_texture_features.features.property_reading.properties.RandomProperties",
        "traben.entity_texture_features.features.property_reading.properties.generic_properties.SimpleIntegerArrayProperty",
        "traben.entity_texture_features.features.property_reading.properties.generic_properties.StringArrayOrRegexProperty",
        "traben.entity_texture_features.features.property_reading.properties.optifine_properties.NBTProperty",
        "traben.entity_texture_features.features.state.ETFState",
        "traben.entity_texture_features.features.state.ETFSubmitData",
        "traben.entity_texture_features.features.texture_handlers.ETFTexture",
        "traben.entity_texture_features.features.texture_handlers.ETFTextureVariator$ETFTextureMultiple",
        "traben.entity_texture_features.features.texture_handlers.ETFTextureVariator$ETFTextureMultiple$1",
        "traben.entity_texture_features.utils.ETFLruCache",
        "traben.entity_texture_features.utils.ETFUtils2"
}, remap = false)
public abstract class EmfCarrierMixin {
}
