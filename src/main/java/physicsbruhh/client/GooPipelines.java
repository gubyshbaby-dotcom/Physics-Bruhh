package physicsbruhh.client;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.function.Function;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import physicsbruhh.PhysicsBruhh;

/**
 * The goo's own shader ({@code assets/physicsbruhh/shaders/core/goo.*}) wired into the game's
 * entity pipelines: same vertex format, same uniform blocks, so it works on either graphics backend
 * and with "improved transparency" on or off. Only the two shader stages are ours.
 */
public final class GooPipelines {
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "core/goo");

    public static final RenderPipeline GOO = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "pipeline/goo"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withCull(false)
            .build();

    public static final OitPipelineSet OIT_GOO = OitPipelineSet.builder(Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "goo"),
                    RenderPipeline.builder(RenderPipelines.OIT_ENTITY_SNIPPET).withVertexShader(SHADER).withFragmentShader(SHADER).withCull(false))
            .withAccumulateModifier(accumulate -> accumulate.withBindGroupLayout(BindGroupLayouts.SAMPLER2))
            .build();

    private static final Function<Identifier, RenderType> TYPES = Util.memoize(texture -> RenderType.create("physicsbruhh_goo",
            RenderSetup.builder(GOO).setOitPipelines(OIT_GOO).withTexture("Sampler0", texture).useLightmap().sortOnUpload().createRenderSetup()));

    private GooPipelines() {}

    static void register(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(GOO);
        event.registerOitPipelineSet(OIT_GOO);
    }

    /** The render type that draws goo with the given block texture as its body. */
    static RenderType type(Identifier texture) {
        return TYPES.apply(texture);
    }
}
