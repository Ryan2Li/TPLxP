package org.example;

import org.example.network.LowerLayerEndpoint;
import org.example.protocol.Sender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class Client {
    private static final Logger logger = LoggerFactory.getLogger(Client.class);

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java Client -p <port> [-h <hostname>] [-q <queue_size>] " +
                    "[-b <bandwidth>] [-d <delay>] [-s] [-f]");
            System.exit(1);
        }

        String hostname = "127.0.0.1";
        int port = 0;
        int queueSize = 0;
        int bandwidth = 1;
        double delay = 1.0;
        boolean useSlowStart = false;
        boolean useFastRetransmit = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-p":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "-h":
                    hostname = args[++i];
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

        logger.info("Starting client - connecting to {}:{}", hostname, port);
        logger.info("Configuration: queue={}, bandwidth={}, delay={}, slowStart={}, fastRetransmit={}",
                queueSize, bandwidth, delay, useSlowStart, useFastRetransmit);

        InetSocketAddress remoteAddress = new InetSocketAddress(hostname, port);
        LowerLayerEndpoint endpoint = new LowerLayerEndpoint(null, remoteAddress,
                queueSize, bandwidth, delay);

        Sender sender = new Sender(endpoint, useSlowStart, useFastRetransmit);

        int numTransmissions = 500;
        for (int i = 1; i <= numTransmissions; i++) {
            String line = String.format("Line%04d\n", i);
            sender.send(line.getBytes());
        }

        logger.info("All data sent. Waiting for completion...");
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sender.shutdown();
    }
}