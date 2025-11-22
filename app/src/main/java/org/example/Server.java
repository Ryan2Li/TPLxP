package org.example;

import org.example.network.LowerLayerEndpoint;
import org.example.protocol.Receiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class Server {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java Server -p <port> [-h <hostname>] [-q <queue_size>] " +
                    "[-b <bandwidth>] [-d <delay>]");
            System.exit(1);
        }

        String hostname = "";
        int port = 0;
        int queueSize = 0;
        int bandwidth = 1;
        double delay = 1.0;

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
            }
        }

        logger.info("Starting server on {}:{}", hostname.isEmpty() ? "0.0.0.0" : hostname, port);
        logger.info("Configuration: queue={}, bandwidth={}, delay={}", queueSize, bandwidth, delay);

        InetSocketAddress localAddress = new InetSocketAddress(hostname, port);
        LowerLayerEndpoint endpoint = new LowerLayerEndpoint(localAddress, null,
                queueSize, bandwidth, delay);

        Receiver receiver = new Receiver(endpoint);

        while (true) {
            byte[] data = receiver.recv();
            if (data != null) {
                System.out.print(new String(data));
            }
        }
    }
}