package me.jellysquid.mods.sodium.render.chunk.renderer;

import com.google.common.collect.Lists;
import me.jellysquid.mods.sodium.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.model.quad.properties.ModelQuadFacingBits;
import me.jellysquid.mods.sodium.model.vertex.type.ChunkVertexType;
import me.jellysquid.mods.sodium.render.chunk.ChunkRenderList;
import me.jellysquid.mods.sodium.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.render.chunk.context.ChunkCameraContext;
import me.jellysquid.mods.sodium.render.chunk.context.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.render.chunk.format.ChunkMeshAttribute;
import me.jellysquid.mods.sodium.render.chunk.passes.BlockRenderPass;
import me.jellysquid.mods.sodium.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.render.chunk.region.RenderRegionStorage;
import me.jellysquid.mods.sodium.render.chunk.shader.ChunkShaderBindingPoints;
import me.jellysquid.mods.sodium.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.thingl.attribute.VertexAttributeBinding;
import me.jellysquid.mods.thingl.buffer.BufferUsage;
import me.jellysquid.mods.thingl.buffer.MutableBuffer;
import me.jellysquid.mods.thingl.device.RenderDevice;
import me.jellysquid.mods.thingl.lists.TessellationCommandList;
import me.jellysquid.mods.thingl.tessellation.IndexType;
import me.jellysquid.mods.thingl.tessellation.PrimitiveType;
import me.jellysquid.mods.thingl.tessellation.Tessellation;
import me.jellysquid.mods.thingl.tessellation.binding.ElementBufferBinding;
import me.jellysquid.mods.thingl.tessellation.binding.VertexBufferBinding;
import me.jellysquid.mods.thingl.util.ElementRange;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

public class RegionChunkRenderer extends ShaderChunkRenderer {
    private final MultiDrawBatch[] batches;
    private final VertexAttributeBinding[] vertexAttributeBindings;

    private final MutableBuffer chunkInfoBuffer;

    public RegionChunkRenderer(RenderDevice device, ChunkVertexType vertexType, float detailDistance) {
        super(device, vertexType, detailDistance);

        this.vertexAttributeBindings = new VertexAttributeBinding[] {
                new VertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_POSITION_ID,
                        this.vertexFormat.getAttribute(ChunkMeshAttribute.POSITION_ID)),
                new VertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_COLOR,
                        this.vertexFormat.getAttribute(ChunkMeshAttribute.COLOR)),
                new VertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_BLOCK_TEXTURE,
                        this.vertexFormat.getAttribute(ChunkMeshAttribute.BLOCK_TEXTURE)),
                new VertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_TEXTURE,
                        this.vertexFormat.getAttribute(ChunkMeshAttribute.LIGHT_TEXTURE)),
                new VertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_BLOCK_FLAGS,
                        this.vertexFormat.getAttribute(ChunkMeshAttribute.BLOCK_FLAGS), true)
        };

        this.chunkInfoBuffer = device.createMutableBuffer();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            device.uploadData(this.chunkInfoBuffer, createChunkInfoBuffer(stack), BufferUsage.STATIC_DRAW);
        }

        this.batches = new MultiDrawBatch[IndexType.VALUES.length];

        for (int i = 0; i < this.batches.length; i++) {
            this.batches[i] = MultiDrawBatch.create(ModelQuadFacing.COUNT * RenderRegion.REGION_SIZE);
        }
    }

    private static ByteBuffer createChunkInfoBuffer(MemoryStack stack) {
        int stride = 4 * 4;
        ByteBuffer data = stack.malloc(RenderRegion.REGION_SIZE * stride);

        for (int x = 0; x < RenderRegion.REGION_WIDTH; x++) {
            for (int y = 0; y < RenderRegion.REGION_HEIGHT; y++) {
                for (int z = 0; z < RenderRegion.REGION_LENGTH; z++) {
                    int i = RenderRegion.getChunkIndex(x, y, z) * stride;

                    data.putFloat(i + 0, x * 16.0f);
                    data.putFloat(i + 4, y * 16.0f);
                    data.putFloat(i + 8, z * 16.0f);
                }
            }
        }

        return data;
    }

    @Override
    public void render(RenderDevice device, ChunkRenderMatrices matrices,
                       ChunkRenderList list, BlockRenderPass pass,
                       ChunkCameraContext camera) {
        device.usePipeline(pass.pipeline(), (pipelineCommands) -> {
            pipelineCommands.useProgram(this.getProgram(pass), (programCommands, programInterface) -> {
                super.setShaderParameters(programInterface);

                programInterface.setProjectionMatrix(matrices.projection());
                programInterface.setDrawUniforms(this.chunkInfoBuffer);

                for (Map.Entry<RenderRegion, List<ChunkRenderList.Entry>> entry : sortedRegions(list, pass.isTranslucent())) {
                    RenderRegion region = entry.getKey();
                    List<ChunkRenderList.Entry> regionSections = entry.getValue();

                    var storage = region.getStorage(pass);

                    if (storage == null || !this.buildDrawBatches(regionSections, pass, camera)) {
                        continue;
                    }

                    this.setModelMatrixUniforms(programInterface, matrices, region, camera);

                    programCommands.useTessellation(this.createTessellationForRegion(device, storage), this::executeDrawBatches);
                }
            });
        });
        
        super.end();
    }

    private boolean buildDrawBatches(List<ChunkRenderList.Entry> sections, BlockRenderPass pass, ChunkCameraContext camera) {
        for (MultiDrawBatch batch : this.batches) {
            batch.begin();
        }

        for (ChunkRenderList.Entry entry : sortedChunks(sections, pass.isTranslucent())) {
            RenderSection render = entry.section();
            int visibility = entry.visibility();

            ChunkGraphicsState state = render.getGraphicsState(pass);

            if (state == null) {
                continue;
            }

            long indexOffset = state.getIndexSegment()
                    .getOffset();

            int baseVertex = state.getVertexSegment()
                    .getOffset() / this.vertexFormat.getStride();

            for (int faceIndex = 0; faceIndex < ModelQuadFacing.COUNT; faceIndex++) {
                if ((visibility & ModelQuadFacingBits.bitfield(faceIndex)) != 0) {
                    this.addDrawCall(state.getModelFace(faceIndex), indexOffset, baseVertex);
                }
            }
        }

        boolean nonEmpty = false;

        for (MultiDrawBatch batch : this.batches) {
            batch.end();

            nonEmpty |= !batch.isEmpty();
        }

        return nonEmpty;
    }

    private Tessellation createTessellationForRegion(RenderDevice device, RenderRegionStorage storage) {
        if (storage.tessellation == null) {
            storage.tessellation = this.createRegionTessellation(device, storage);
        }

        return storage.tessellation;
    }

    private void executeDrawBatches(TessellationCommandList commandList) {
        for (int i = 0; i < this.batches.length; i++) {
            MultiDrawBatch batch = this.batches[i];

            if (!batch.isEmpty()) {
                commandList.multiDrawElementsBaseVertex(batch.getPointerBuffer(), batch.getCountBuffer(), batch.getBaseVertexBuffer(), IndexType.VALUES[i]);
            }
        }
    }

    private final Matrix4f cachedModelViewMatrix = new Matrix4f();

    private void setModelMatrixUniforms(ChunkShaderInterface shader, ChunkRenderMatrices matrices, RenderRegion region, ChunkCameraContext camera) {
        float x = getCameraTranslation(region.getOriginX(), camera.blockX, camera.deltaX);
        float y = getCameraTranslation(region.getOriginY(), camera.blockY, camera.deltaY);
        float z = getCameraTranslation(region.getOriginZ(), camera.blockZ, camera.deltaZ);

        Matrix4f matrix = this.cachedModelViewMatrix;
        matrix.set(matrices.modelView());
        matrix.translate(x, y, z);

        shader.setModelViewMatrix(matrix);
    }

    private void addDrawCall(ElementRange part, long baseIndexPointer, int baseVertexIndex) {
        if (part != null) {
            MultiDrawBatch batch = this.batches[part.indexType().ordinal()];
            batch.add(baseIndexPointer + part.elementPointer(), part.elementCount(), baseVertexIndex + part.baseVertex());
        }
    }

    private Tessellation createRegionTessellation(RenderDevice device, RenderRegionStorage arenas) {
        return device.createTessellation(PrimitiveType.TRIANGLES, new VertexBufferBinding[] {
                new VertexBufferBinding(arenas.vertexArena.getBufferObject(), this.vertexFormat, this.vertexAttributeBindings)
        }, new ElementBufferBinding(arenas.indicesArena.getBufferObject()));
    }

    @Override
    public void delete() {
        super.delete();

        for (MultiDrawBatch batch : this.batches) {
            batch.delete();
        }

        this.device.deleteBuffer(this.chunkInfoBuffer);
    }

    private static Iterable<Map.Entry<RenderRegion, List<ChunkRenderList.Entry>>> sortedRegions(ChunkRenderList list, boolean translucent) {
        return list.sortedSectionsByRegion(translucent);
    }

    private static Iterable<ChunkRenderList.Entry> sortedChunks(List<ChunkRenderList.Entry> chunks, boolean translucent) {
        return translucent ? Lists.reverse(chunks) : chunks;
    }

    private static float getCameraTranslation(int chunkBlockPos, int cameraBlockPos, float cameraPos) {
        return (chunkBlockPos - cameraBlockPos) - cameraPos;
    }
}
