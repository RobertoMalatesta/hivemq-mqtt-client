package org.mqttbee.mqtt5.message.unsubscribe;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.mqttbee.annotations.NotNull;
import org.mqttbee.mqtt5.codec.encoder.Mqtt5UnsubscribeEncoder;
import org.mqttbee.mqtt5.message.Mqtt5Message;

/**
 * @author Silvio Giebl
 */
public class Mqtt5UnsubscribeInternal extends Mqtt5Message.Mqtt5MessageWithProperties {

    private final Mqtt5UnsubscribeImpl unsubscribe;
    private int packetIdentifier;

    public Mqtt5UnsubscribeInternal(@NotNull final Mqtt5UnsubscribeImpl unsubscribe) {
        this.unsubscribe = unsubscribe;
    }

    @NotNull
    public Mqtt5UnsubscribeImpl getUnsubscribe() {
        return unsubscribe;
    }

    public int getPacketIdentifier() {
        return packetIdentifier;
    }

    public void setPacketIdentifier(final int packetIdentifier) {
        this.packetIdentifier = packetIdentifier;
    }

    @Override
    public void encode(@NotNull final Channel channel, @NotNull final ByteBuf out) {
        Mqtt5UnsubscribeEncoder.INSTANCE.encode(this, channel, out);
    }

    @Override
    protected int calculateEncodedRemainingLength() {
        return Mqtt5UnsubscribeEncoder.INSTANCE.encodedRemainingLength(this);
    }

    @Override
    protected int calculateEncodedPropertyLength() {
        return Mqtt5UnsubscribeEncoder.INSTANCE.encodedPropertyLength(this);
    }

}