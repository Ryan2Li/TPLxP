package org.example.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class LowerLayerEndpoint {
    private static final Logger logger = LoggerFactory.getLogger(LowerLayerEndpoint.class);
    private static final int MAX_PACKET_SIZE = 4096;

    private final InetSocketAddress localAddress;
    private InetSocketAddress remoteAddress;
    private final BlockingQueue<byte[]> queue;
    private final double transmitDelay;
    private final double propagationDelay;
    private DatagramSocket socket;
    private volatile boolean shutdown = false;
    private Thread forwardThread;

    public LowerLayerEndpoint(InetSocketAddress localAddress, InetSocketAddress remoteAddress,
                              int queueSize, int bandwidth, double propagationDelay) {
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.queue = queueSize > 0 ? new LinkedBlockingQueue<>(queueSize) : new LinkedBlockingQueue<>();
        this.transmitDelay = 1.0 / bandwidth;
        this.propagationDelay = propagationDelay;

        try {
            socket = new DatagramSocket(localAddress);
            if (remoteAddress != null) {
                socket.connect(remoteAddress);
            }
        } catch (SocketException e) {
            logger.error("Failed to create socket", e);
            throw new RuntimeException(e);
        }

        startForwardThread();
    }

    public double getTransmitDelay() {
        return transmitDelay;
    }

    public double getPropagationDelay() {
        return propagationDelay;
    }

    public void send(byte[] data) {
        new Thread(() -> {
            try {
                Thread.sleep((long) (propagationDelay * 1000));
                enqueue(data);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void enqueue(byte[] data) {
        if (!queue.offer(data)) {
            logger.info("\u001B[31mLower layer queue full => dropped packet\u001B[0m");
        }
    }

    private void startForwardThread() {
        forwardThread = new Thread(() -> {
            while (!shutdown) {
                try {
                    byte[] data = queue.poll((long) (transmitDelay * 1000), TimeUnit.MILLISECONDS);
                    if (data != null) {
                        DatagramPacket packet;
                        if (remoteAddress != null) {
                            packet = new DatagramPacket(data, data.length, remoteAddress);
                        } else {
                            packet = new DatagramPacket(data, data.length);
                        }
                        socket.send(packet);
                        logger.debug("Lower layer forwarded packet");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    if (!shutdown) {
                        logger.error("Error forwarding packet", e);
                    }
                }
            }
        });
        forwardThread.setDaemon(true);
        forwardThread.start();
    }

    public byte[] recv() {
        try {
            byte[] buffer = new byte[MAX_PACKET_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            if (remoteAddress == null) {
                remoteAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
                socket.connect(remoteAddress);
            }

            byte[] data = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
            logger.debug("Lower layer received packet");
            return data;
        } catch (IOException e) {
            if (!shutdown) {
                logger.error("Error receiving packet", e);
            }
            return null;
        }
    }

    public void shutdown() {
        if (!shutdown) {
            shutdown = true;
            socket.close();
        }
    }
}