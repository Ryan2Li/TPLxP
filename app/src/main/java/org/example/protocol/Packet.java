package org.example.protocol;

import java.nio.ByteBuffer;

public class Packet {
    public enum Type {
        DATA((byte) 'D'),
        ACK((byte) 'A'),
        SYN((byte) 'S');

        private final byte value;

        Type(byte value) {
            this.value = value;
        }

        public byte getValue() {
            return value;
        }

        public static Type fromByte(byte b) {
            for (Type type : Type.values()) {
                if (type.value == b) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown packet type: " + b);
        }
    }

    private static final int HEADER_SIZE = 5; // 1 byte type + 4 bytes seq_num
    public static final int MAX_DATA_SIZE = 1400;

    private final Type type;
    private final int seqNum;
    private final byte[] data;

    public Packet(Type type, int seqNum, byte[] data) {
        this.type = type;
        this.seqNum = seqNum;
        this.data = data != null ? data : new byte[0];
    }

    public Packet(Type type, int seqNum) {
        this(type, seqNum, new byte[0]);
    }

    public Type getType() {
        return type;
    }

    public int getSeqNum() {
        return seqNum;
    }

    public byte[] getData() {
        return data;
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + data.length);
        buffer.put(type.getValue());
        buffer.putInt(seqNum);
        buffer.put(data);
        return buffer.array();
    }

    public static Packet fromBytes(byte[] raw) {
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        Type type = Type.fromByte(buffer.get());
        int seqNum = buffer.getInt();
        byte[] data = new byte[raw.length - HEADER_SIZE];
        buffer.get(data);
        return new Packet(type, seqNum, data);
    }

    @Override
    public String toString() {
        return String.format("%s %d", type.name(), seqNum);
    }
}