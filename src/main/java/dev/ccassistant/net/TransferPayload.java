package dev.ccassistant.net;

import dev.ccassistant.CCAssistant;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * One chunk of a JSON message between server and client. Vanilla caps serverbound custom payloads at 32 KiB, so
 * messages are split into chunks of at most {@link Transfers#CHUNK_BYTES}.
 */
public record TransferPayload(int transfer, int index, int count, byte[] data) implements CustomPacketPayload {
    public static final Type<TransferPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(CCAssistant.MOD_ID, "transfer"));

    public static final StreamCodec<ByteBuf, TransferPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, TransferPayload::transfer,
        ByteBufCodecs.VAR_INT, TransferPayload::index,
        ByteBufCodecs.VAR_INT, TransferPayload::count,
        ByteBufCodecs.byteArray(Transfers.CHUNK_BYTES), TransferPayload::data,
        TransferPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
