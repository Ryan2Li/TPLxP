package org.example;

import org.example.network.LowerLayerEndpoint;
import org.example.protocol.Receiver;
import org.example.protocol.Sender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Proxy acts as an intermediary with its own transport layer.
 * It receives data from a client using one transport connection,
 * and forwards it to a server using another transport connection.
 */
public class Proxy {
    private static final Logger logger = LoggerFactory.getLogger(Proxy.class);

    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("Usage: java Proxy -lp <local_port> -rp <remote_port> " +
                    "[-rh <remote_hostname>] [-q <queue_size>] [-b <bandwidth>] [-d <delay>] [-s] [-f]");
            System.exit(1);
        }

        int localPort = 0;
        String remoteHostname = "127.0.0.1";
        int remotePort = 0;
        int queueSize = 0;
        int bandwidth = 1;
        double delay = 1.0;
        boolean useSlowStart = false;
        boolean useFastRetransmit = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-lp":
                    localPort = Integer.parseInt(args[++i]);
                    break;
                case "-rp":
                    remotePort = Integer.parseInt(args[++i]);
                    break;
                case "-rh":
                    remoteHostname = args[++i];
                    break;
                case "-q":
                    queueSize = Integer.parseInt(args[++i]);
                    break;
                case "-b":
                    bandwidth = Integer.parseInt(args[++i]);
                    break;
                case "-d":
                    delay = Double.parseDouble(args[++i]);
                    break;
                case "-s":
                    useSlowStart = true;
                    break;
                case "-f":
                    useFastRetransmit = true;
                    break;
            }
        }

        logger.info("Starting proxy - listening on port {}, forwarding to {}:{}",
                localPort, remoteHostname, remotePort);
        logger.info("Configuration: queue={}, bandwidth={}, delay={}, slowStart={}, fastRetransmit={}",
                queueSize, bandwidth, delay, useSlowStart, useFastRetransmit);

        // Create receiver endpoint to accept client connections
        InetSocketAddress clientAddress = new InetSocketAddress(localPort);
        LowerLayerEndpoint clientEndpoint = new LowerLayerEndpoint(clientAddress, null,
                queueSize, bandwidth, delay);
        Receiver receiver = new Receiver(clientEndpoint);

        // Create sender endpoint to forward to server
        InetSocketAddress serverAddress = new InetSocketAddress(remoteHostname, remotePort);
        LowerLayerEndpoint serverEndpoint = new LowerLayerEndpoint(null, serverAddress,
                queueSize, bandwidth, delay);
        Sender sender = new Sender(serverEndpoint, useSlowStart, useFastRetransmit);

        logger.info("Proxy ready - forwarding data from client to server");

        // Forward data from client to server
        while (true) {
            byte[] data = receiver.recv();
            if (data != null) {
                logger.debug("Proxy forwarding {} bytes", data.length);
                sender.send(data);
            }
        }
    }
}