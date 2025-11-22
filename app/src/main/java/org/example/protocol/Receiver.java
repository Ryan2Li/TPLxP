package org.example.protocol;

import org.example.network.LowerLayerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Receiver {
    private static final Logger logger = LoggerFactory.getLogger(Receiver.class);
    private static final int BUF_SIZE = 1000;

    private final LowerLayerEndpoint llEndpoint;
    private int lastAckSent = -1;
    private int maxSeqRecv = -1;
    private final byte[][] recvWindow = new byte[BUF_SIZE][];
    private final BlockingQueue<byte[]> readyData = new LinkedBlockingQueue<>();
    private Thread recvThread;

    public Receiver(LowerLayerEndpoint llEndpoint) {
        this.llEndpoint = llEndpoint;
        startRecvThread();
    }

    public byte[] recv() {
        try {
            return readyData.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void startRecvThread() {
        recvThread = new Thread(() -> {
            while (true) {
                byte[] raw = llEndpoint.recv();
                if (raw == null) continue;

                Packet packet = Packet.fromBytes(raw);
                logger.debug("rwnd Received: {}", packet);

                if (packet.getSeqNum() <= lastAckSent) {
                    sendAck(lastAckSent);
                    logger.debug("rwnd Sent (Retransmit): ACK {}", lastAckSent);
                    continue;
                }

                int slot = packet.getSeqNum() % BUF_SIZE;
                recvWindow[slot] = packet.getData();
                if (packet.getSeqNum() > maxSeqRecv) {
                    maxSeqRecv = packet.getSeqNum();
                }

                int ackNum = lastAckSent;
                while (ackNum < maxSeqRecv) {
                    int nextSlot = (ackNum + 1) % BUF_SIZE;
                    byte[] data = recvWindow[nextSlot];

                    if (data == null) break;

                    ackNum++;
                    readyData.offer(data);
                    recvWindow[nextSlot] = null;
                }

                lastAckSent = ackNum;
                sendAck(lastAckSent);
                logger.debug("rwnd Sent: ACK {}", lastAckSent);
            }
        });
        recvThread.setDaemon(true);
        recvThread.start();
    }

    private void sendAck(int ackNum) {
        Packet ack = new Packet(Packet.Type.ACK, ackNum);
        llEndpoint.send(ack.toBytes());
    }
}