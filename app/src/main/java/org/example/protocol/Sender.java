package org.example.protocol;

import org.example.network.LowerLayerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;

public class Sender {
    private static final Logger logger = LoggerFactory.getLogger(Sender.class);
    private static final int BUF_SIZE = 5000;

    private final LowerLayerEndpoint llEndpoint;
    private double rtt;

    private int lastAckRecv = -1;
    private int lastSeqSent = -1;
    private int lastSeqWritten = 0;
    private final BufferedPacket[] buffer = new BufferedPacket[BUF_SIZE];
    private final Semaphore bufSlot = new Semaphore(BUF_SIZE);

    private final boolean useSlowStart;
    private final boolean useFastRetransmit;
    private double cwnd = 1;
    private double ssthresh = 64;
    private boolean fastRecovery = false;
    private int dupAckCount = 0;

    private volatile boolean shutdown = false;
    private Thread recvThread;
    private Timer timer;

    private static class BufferedPacket {
        Packet packet;
        Instant sendTime;

        BufferedPacket(Packet packet, Instant sendTime) {
            this.packet = packet;
            this.sendTime = sendTime;
        }
    }

    public Sender(LowerLayerEndpoint llEndpoint, boolean useSlowStart, boolean useFastRetransmit) {
        this.llEndpoint = llEndpoint;
        this.rtt = 2 * (llEndpoint.getTransmitDelay() + llEndpoint.getPropagationDelay());
        this.useSlowStart = useSlowStart;
        this.useFastRetransmit = useFastRetransmit;

        startRecvThread();

        try {
            bufSlot.acquire();
            Packet synPacket = new Packet(Packet.Type.SYN, 0);
            buffer[0] = new BufferedPacket(synPacket, null);
            transmit(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void transmit(int seqNum) {
        int slot = seqNum % BUF_SIZE;
        BufferedPacket buffered = buffer[slot];
        Packet packet = buffered.packet;

        llEndpoint.send(packet.toBytes());
        Instant sendTime = Instant.now();

        if (lastSeqSent < seqNum) {
            lastSeqSent = seqNum;
        }

        if (buffered.sendTime == null) {
            logger.info("Transmit: {}", packet);
            buffered.sendTime = sendTime;
        } else {
            logger.info("Retransmit: {}", packet);
            buffered.sendTime = Instant.EPOCH;
        }

        scheduleTimeout();
    }

    private void scheduleTimeout() {
        if (timer != null) {
            timer.cancel();
        }
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                handleTimeout();
            }
        }, (long) (2 * rtt * 1000));
    }

    public void send(byte[] data) {
        for (int i = 0; i < data.length; i += Packet.MAX_DATA_SIZE) {
            int end = Math.min(i + Packet.MAX_DATA_SIZE, data.length);
            byte[] chunk = new byte[end - i];
            System.arraycopy(data, i, chunk, 0, chunk.length);
            sendChunk(chunk);
        }
    }

    private void sendChunk(byte[] data) {
        try {
            bufSlot.acquire();

            lastSeqWritten++;
            Packet packet = new Packet(Packet.Type.DATA, lastSeqWritten, data);
            int slot = packet.getSeqNum() % BUF_SIZE;
            buffer[slot] = new BufferedPacket(packet, null);

            synchronized (this) {
                if (lastSeqSent - lastAckRecv < (int) cwnd) {
                    transmit(packet.getSeqNum());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void handleTimeout() {
        if (useSlowStart || useFastRetransmit) {
            ssthresh = Math.max(2, cwnd / 2);
            cwnd = 1;
            if (useFastRetransmit) {
                fastRecovery = false;
                dupAckCount = 0;
            }
            logger.debug("\u001B[31mTimeout occurred. ssthresh set to {}, CWND reset to 1\u001B[0m", ssthresh);
        } else {
            cwnd = Math.max(1, cwnd / 2);
            logger.debug("\u001B[31mTimeout occurred. CWND decreased to {}\u001B[0m", cwnd);
        }

        for (int seq = lastAckRecv + 1; seq <= lastSeqSent; seq++) {
            int slot = seq % BUF_SIZE;
            buffer[slot].sendTime = Instant.EPOCH;
        }
        lastSeqSent = lastAckRecv;

        transmit(lastAckRecv + 1);
    }

    private void startRecvThread() {
        recvThread = new Thread(() -> {
            while (!shutdown || lastAckRecv < lastSeqSent) {
                byte[] raw = llEndpoint.recv();
                if (raw == null) continue;

                Packet packet = Packet.fromBytes(raw);
                Instant recvTime = Instant.now();
                logger.info("Received: {}", packet);

                handleAck(packet, recvTime);
            }
            llEndpoint.shutdown();
        });
        recvThread.start();
    }

    private synchronized void handleAck(Packet packet, Instant recvTime) {
        if (packet.getSeqNum() == lastAckRecv) {
            handleDuplicateAck();
            return;
        }

        dupAckCount = 0;
        int prevAckRecv = lastAckRecv;
        lastAckRecv = packet.getSeqNum();

        while (prevAckRecv < lastAckRecv) {
            prevAckRecv++;
            int slot = prevAckRecv % BUF_SIZE;
            BufferedPacket buffered = buffer[slot];

            // Check if buffer slot is not null before accessing
            if (buffered != null) {
                if (buffered.sendTime != null && !buffered.sendTime.equals(Instant.EPOCH)) {
                    Duration elapsed = Duration.between(buffered.sendTime, recvTime);
                    rtt = rtt * 0.9 + elapsed.toMillis() / 1000.0 * 0.1;
                    logger.debug("Updated RTT estimate: {}", rtt);
                }

                buffer[slot] = null;
                bufSlot.release();
            }
        }

        if (lastSeqSent < lastAckRecv) {
            lastSeqSent = lastAckRecv;
        }

        if (timer != null && lastAckRecv == lastSeqSent) {
            timer.cancel();
            timer = null;
        }

        updateCwndOnNewAck();
        sendAvailablePackets();
    }

    private synchronized void handleDuplicateAck() {
        if (!useFastRetransmit) return;

        dupAckCount++;
        logger.debug("\u001B[33mDuplicate ACK count: {}\u001B[0m", dupAckCount);

        if (!fastRecovery && dupAckCount == 3) {
            ssthresh = Math.max(2, cwnd / 2);
            cwnd = ssthresh + 3;
            logger.debug("\u001B[1mFast Retransmit initiated. ssthresh: {}, CWND: {}\u001B[0m", ssthresh, cwnd);
            transmit(lastAckRecv + 1);
            fastRecovery = true;
        } else if (fastRecovery) {
            cwnd += 1;
            logger.debug("In Fast Recovery. CWND increased to {}", cwnd);
            sendAvailablePackets();
        }
    }

    private void updateCwndOnNewAck() {
        if (fastRecovery) {
            cwnd = ssthresh;
            fastRecovery = false;
            logger.debug("\u001B[1mExited Fast Recovery; CWND set to ssthresh: {}\u001B[0m", cwnd);
        } else {
            if (useSlowStart) {
                if (ssthresh > cwnd) {
                    cwnd += 1;
                    logger.debug("Slow Start. CWND increased to {}", cwnd);
                } else {
                    cwnd += 1.0 / cwnd;
                    logger.debug("Congestion Avoidance; CWND increased to {}", cwnd);
                }
            } else {
                cwnd += 1.0 / cwnd;
                logger.debug("AIMD. CWND increased to {}", cwnd);
            }
        }
    }

    private void sendAvailablePackets() {
        while (lastSeqSent < lastSeqWritten && lastSeqSent - lastAckRecv < (int) cwnd) {
            transmit(lastSeqSent + 1);
        }
    }

    public void shutdown() {
        shutdown = true;
    }
}